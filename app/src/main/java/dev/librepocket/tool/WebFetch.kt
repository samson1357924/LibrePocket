package dev.librepocket.tool

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * S1-B `web.fetch` (READ)：抓取單一 URL 的文字內容。
 *
 * 規則（S1-B 範圍）：
 * - https-only：明文 `http` 僅允許本地迴環（`localhost` / `127.0.0.0/8` / `::1`），
 *   非本地一律執行期 `Unavailable`（投影層級等價於 UNAVAILABLE），不發任何請求；
 *   自動重定向一律關閉，手動跟隨（上限 [MAX_REDIRECTS] 跳）且每跳重做策略檢查，
 *   `https → http` 降級一律 `Unavailable/CLEARTEXT_NON_LOCAL`；
 * - `maxBytes` 上限（預設 256 KiB，絕對上限 1 MiB），超限截斷並標記；
 * - 超時 15s：connect / read / write / 全程 call 皆 15s（[TIMEOUT_MS]）；
 * - 轉碼降級：按 `Content-Type` charset 解碼，未知或損壞編碼降級為 UTF-8
 *   replacement 解碼，永不因轉碼拋異常；
 * - 複用 OkHttp：共用單例 [sharedClient]（連線池/調度器共用），僅在呼叫方
 *   要求不同超時時才 `newBuilder` 衍生（仍共用底層連線池）。
 *
 * 純 JVM（無 Android API），可在單元測試直接執行。
 */
object WebFetch {

    const val TOOL_NAME = "web.fetch"
    const val SWITCH = "webfetch"
    const val SWITCH_DEFAULT = true

    /** 全程超時預算（S1-B：15s）。 */
    const val TIMEOUT_MS = 15_000L

    /** 預設位元組上限 256 KiB。 */
    const val DEFAULT_MAX_BYTES = 262_144

    /** 位元組上限硬頂 1 MiB（呼叫方要求再大也鉗制於此）。 */
    const val ABSOLUTE_MAX_BYTES = 1_048_576

    /** 手動跟隨重定向的上限跳數（超過即 `Failed/TOO_MANY_REDIRECTS`）。 */
    const val MAX_REDIRECTS = 5

    const val FALLBACK_HINT = "open the URL manually in a browser"

    sealed interface UrlCheck {
        data class Allowed(val url: String) : UrlCheck
        data class Blocked(val reason: DenyReason, val detail: String) : UrlCheck
    }

    sealed interface FetchOutcome {
        data class Ok(
            val text: String,
            val bytesKept: Int,
            val truncated: Boolean,
            val contentType: String?,
        ) : FetchOutcome

        data class Unavailable(val reason: DenyReason, val detail: String, val message: String) : FetchOutcome
        data class Failed(val detail: String, val message: String) : FetchOutcome
    }

    /** 是否本地迴環主機（`http` 例外的唯一判據）。 */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost") return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        val parts = h.split(".")
        if (parts.size == 4 && parts[0] == "127") {
            return parts.all { it.toIntOrNull() in 0..255 }
        }
        return false
    }

    private val schemePrefix = Regex("^\\s*([A-Za-z][A-Za-z0-9+.-]*):")

    /** 純策略檢查：不觸碰網路，可在請求發出前短路拒絕。 */
    fun checkUrl(rawUrl: String): UrlCheck {
        // OkHttp HttpUrl 僅解析 http(s)：先顯式識別其餘 scheme，保留細分碼。
        val prefix = schemePrefix.find(rawUrl)?.groupValues?.get(1)?.lowercase()
        if (prefix != null && prefix != "http" && prefix != "https") {
            return UrlCheck.Blocked(DenyReason.NO_PRIVILEGE, "UNSUPPORTED_SCHEME")
        }
        val url = rawUrl.toHttpUrlOrNull()
            ?: return UrlCheck.Blocked(DenyReason.NO_PRIVILEGE, "BAD_URL")
        return when (url.scheme) {
            "https" -> UrlCheck.Allowed(url.toString())
            "http" ->
                if (isLocalHost(url.host)) UrlCheck.Allowed(url.toString())
                else UrlCheck.Blocked(DenyReason.NO_PRIVILEGE, "CLEARTEXT_NON_LOCAL")
            else -> UrlCheck.Blocked(DenyReason.NO_PRIVILEGE, "UNSUPPORTED_SCHEME")
        }
    }

    @Volatile
    private var shared: OkHttpClient? = null

    /** 共用 OkHttp 單例（S1-B「複用 OkHttp」：連線池共用；自動重定向一律關閉，由 [fetch] 手動跟隨並逐跳重做 [checkUrl]）。 */
    fun sharedClient(): OkHttpClient =
        shared ?: synchronized(this) {
            shared ?: OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
                .also { shared = it }
        }

    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    private fun unavailable(detail: String): FetchOutcome.Unavailable =
        FetchOutcome.Unavailable(
            reason = DenyReason.NO_PRIVILEGE,
            detail = detail,
            message = S1bFallback.message(
                what = "抓取該連結（$detail）",
                reason = DenyReason.NO_PRIVILEGE,
                detail = detail,
                alternative = FALLBACK_HINT,
                needFromUser = "手動在瀏覽器開啟該連結",
            ),
        )

    /** 以目前 URL 為基準解析 `Location`（支援相對路徑）；非法回 null。 */
    fun resolveRedirect(currentUrl: String, location: String): String? =
        currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString()

    /**
     * 同步抓取（呼叫方負責切到 IO 執行緒）。
     * 非本地明文 http 在觸碰 [client] 之前即短路為 [FetchOutcome.Unavailable]；
     * 自動重定向一律關閉（[sharedClient] 及本函式內衍生 client 皆
     * `followRedirects(false)`），最多 [MAX_REDIRECTS] 跳手動跟隨，每跳重做
     * [checkUrl]，`https → http` 降級一律 [FetchOutcome.Unavailable]
     *（`CLEARTEXT_NON_LOCAL`，含本地迴環在內 fail-closed），絕不發出明文請求。
     */
    fun fetch(
        rawUrl: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        client: OkHttpClient = sharedClient(),
        timeoutMs: Long = TIMEOUT_MS,
    ): FetchOutcome {
        val cap = maxBytes.coerceIn(1, ABSOLUTE_MAX_BYTES)
        val first = when (val check = checkUrl(rawUrl)) {
            is UrlCheck.Allowed -> check.url
            is UrlCheck.Blocked -> return unavailable(check.detail)
        }
        // 呼叫方傳入的 client 可能仍開啟自動重定向：一律強制關閉，改由下方手動跟隨。
        val baseClient =
            if (timeoutMs == TIMEOUT_MS) client
            else client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val callClient = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var current = first
        var hops = 0
        while (true) {
            val request = Request.Builder().url(current).get()
                .header("Accept", "text/*, application/*+json, application/json;q=0.9, */*;q=0.1")
                .build()
            val next: String = try {
                callClient.newCall(request).execute().use { response ->
                    if (response.code in redirectCodes) {
                        if (hops >= MAX_REDIRECTS) return transportFailed("TOO_MANY_REDIRECTS")
                        val location = response.header("Location")
                            ?: return transportFailed("BAD_REDIRECT")
                        val resolved = resolveRedirect(current, location)
                            ?: return transportFailed("BAD_REDIRECT")
                        // https → http 降級一律拒絕（fail-closed，含本地迴環），絕不發出明文請求。
                        val fromHttps = current.toHttpUrlOrNull()?.scheme == "https"
                        val toHttp = resolved.toHttpUrlOrNull()?.scheme == "http"
                        if (fromHttps && toHttp) return unavailable("CLEARTEXT_NON_LOCAL")
                        when (val check = checkUrl(resolved)) {
                            is UrlCheck.Blocked -> return unavailable(check.detail)
                            is UrlCheck.Allowed -> check.url
                        }
                    } else {
                        if (!response.isSuccessful) {
                            val detail = "HTTP_${response.code}"
                            return FetchOutcome.Failed(
                                detail = detail,
                                message = S1bFallback.message(
                                    what = "抓取該連結（$detail）",
                                    reason = DenyReason.NO_PRIVILEGE,
                                    detail = detail,
                                    alternative = FALLBACK_HINT,
                                    needFromUser = "手動在瀏覽器開啟該連結",
                                ),
                            )
                        }
                        val body = response.body
                            ?: return FetchOutcome.Failed(
                                detail = "EMPTY_BODY",
                                message = S1bFallback.message(
                                    what = "讀取該連結內容（EMPTY_BODY）",
                                    reason = DenyReason.NO_PRIVILEGE,
                                    detail = "EMPTY_BODY",
                                    alternative = FALLBACK_HINT,
                                    needFromUser = "手動在瀏覽器開啟該連結",
                                ),
                            )
                        val rawType = body.contentType()
                        val bytes = readCapped(body.byteStream(), cap)
                        val text = decodeDowngraded(bytes.data, rawType?.charsetName())
                        return FetchOutcome.Ok(
                            text = text,
                            bytesKept = bytes.data.size,
                            truncated = bytes.truncated,
                            contentType = rawType?.toString(),
                        )
                    }
                }
            } catch (e: SocketTimeoutException) {
                return transportFailed("TIMEOUT")
            } catch (e: InterruptedIOException) {
                return transportFailed("TIMEOUT")
            } catch (e: IOException) {
                val detail = if ((e.message ?: "").contains("timeout", ignoreCase = true)) "TIMEOUT" else "TRANSPORT"
                return transportFailed(detail)
            }
            current = next
            hops++
        }
    }

    private fun transportFailed(detail: String): FetchOutcome.Failed =
        FetchOutcome.Failed(
            detail = detail,
            message = S1bFallback.message(
                what = "抓取該連結（$detail）",
                reason = DenyReason.NO_PRIVILEGE,
                detail = detail,
                alternative = FALLBACK_HINT,
                needFromUser = "稍後重試，或手動在瀏覽器開啟該連結",
            ),
        )

    private data class Capped(val data: ByteArray, val truncated: Boolean)

    private fun readCapped(stream: java.io.InputStream, cap: Int): Capped {
        val out = ByteArrayOutputStream(minOf(cap, 8192))
        val buf = ByteArray(8192)
        var total = 0
        var truncated = false
        stream.use { input ->
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                if (total + n > cap) {
                    out.write(buf, 0, cap - total)
                    total = cap
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
        }
        return Capped(out.toByteArray(), truncated)
    }

    private fun okhttp3.MediaType.charsetName(): String? =
        try {
            charset(Charsets.UTF_8)?.name()
        } catch (_: Exception) {
            null
        }

    /**
     * 轉碼降級：未知 charset 名或損壞位元組一律降級為 UTF-8 replacement
     * 解碼，永不拋異常。
     */
    fun decodeDowngraded(data: ByteArray, charsetName: String?): String {
        val charset: Charset = try {
            if (charsetName.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(charsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: Exception) {
            String(data, Charsets.UTF_8)
        }
    }
}
