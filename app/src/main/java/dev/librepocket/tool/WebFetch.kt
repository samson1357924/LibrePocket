package dev.librepocket.tool

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkEvaluation
import dev.librepocket.policy.NetworkPolicy
import dev.librepocket.policy.PinnedNetworkDns
import dev.librepocket.policy.SystemHostResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * S1-B `web.fetch` (READ)＋Issue #135 A4 出站政策接線：抓取單一 URL 的文字內容。
 *
 * 規則（S1-B 基礎＋A4 接線）：
 * - URL 檢查一律走 [NetworkPolicy.evaluateUrl]（per-endpoint [EndpointNetworkOptIn]，
 *   預設 `HTTPS_ONLY` 即 deny-all）：公網 https-only；迴環／私有（含 `localhost`）
 *   須 opt-in（`ALLOW_PRIVATE_DNS` 走 https、`ALLOW_CLEARTEXT_PRIVATE` 另開
 *   迴環／RFC1918／ULA 明文，公網 http 永不開放）；metadata／link-local／
 *   multicast／unspecified／CGNAT 等 opt-in 也永遠阻擋；判定一律以解析後位元組
 *   [NetworkPolicy.classifyAddress] 為準，不做字串前綴比對；
 * - 自動重定向一律關閉，手動跟隨（上限 [MAX_REDIRECTS] 跳），每跳以原始 resolver
 *   重走政策（新決策）＋`https → http` 降級永遠拒；任何跳轉失敗（含超跳數、
 *   壞 Location、跳往被政策拒絕的目標）一律 typed `REDIRECT_BLOCKED`
 *   （[FetchOutcome.Unavailable]），絕不向 redirect target 發請求；
 * - DNS pinning：首跳放行答案經 [PinnedNetworkDns] 釘住，逐跳通過政策才補釘，
 *   建連一律走釘住值（evaluate 與 connect 之間無二次解析）；client 強制直連
 *   （`Proxy.NO_PROXY`），代理不得外包解析繞過 pin；
 * - `maxBytes` 上限（預設 256 KiB，絕對上限 1 MiB），超限截斷並標記；
 * - 超時 15s：connect / read / write / 全程 call 皆 15s（[TIMEOUT_MS]）；
 * - 轉碼降級：按 `Content-Type` charset 解碼，未知或損壞編碼降級為 UTF-8
 *   replacement 解碼，永不因轉碼拋異常；
 * - 取消：同步阻塞 API，無第二傳輸、無 cache；無 coroutine 取消路徑——
 *   中斷唯一靠外層超時 bound（全程 call [TIMEOUT_MS]，`InterruptedIOException`
 *   → `TIMEOUT`），絕不 fallback、不回傳部分 Ok；
 * - 複用 OkHttp：共用單例 [sharedClient]（連線池/調度器共用），僅在呼叫方
 *   要求不同超時時才 `newBuilder` 衍生（仍共用底層連線池）；每跳建連另疊
 *   pinning DNS＋直連（`newBuilder` 衍生，不污染單例）。
 *
 * 純 JVM（無 Android API），可在單元測試直接執行（測試一律注入 fake
 * [HostResolver]／MockWebServer，禁真網路／真 DNS）。
 */
object WebFetch {

    const val TOOL_NAME = "web.fetch"
    const val SWITCH = "webfetch"
    const val SWITCH_DEFAULT = true

    /** 全程超時預算（S1-B：15s）。 */
    const val TIMEOUT_MS = 15_000L

    /** 超時上限（防資源耗盡；直調 API 才可能超出預設）。 */
    const val MAX_TIMEOUT_MS = 60_000L

    /** 預設位元組上限 256 KiB。 */
    const val DEFAULT_MAX_BYTES = 262_144

    /** 位元組上限硬頂 1 MiB（呼叫方要求再大也鉗制於此）。 */
    const val ABSOLUTE_MAX_BYTES = 1_048_576

    /** 手動跟隨重定向的上限跳數（超過即 `Unavailable/REDIRECT_BLOCKED`）。 */
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

    /**
     * 純策略檢查：改走 [NetworkPolicy.evaluateUrl]（A4），建連前短路拒絕。
     *
     * 預設 [EndpointNetworkOptIn]（`HTTPS_ONLY`）即 deny-all：迴環／私有
     * 無論明文與否一律先拒，須呼叫方按 per-endpoint 授權傳入 opt-in。
     * 阻擋時 `detail` 即 [NetworkDenyCode] 名（typed）；舊字串碼已對應：
     * `BAD_URL`→`INVALID_HOST`、`CLEARTEXT_NON_LOCAL`→`CLEARTEXT_NOT_PERMITTED`。
     */
    fun checkUrl(
        rawUrl: String,
        optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
        resolver: HostResolver = SystemHostResolver,
    ): UrlCheck {
        val eval = NetworkPolicy.evaluateUrl(rawUrl, optIn, resolver)
        return if (eval.allowed) {
            UrlCheck.Allowed(eval.normalizedUrl ?: rawUrl)
        } else {
            UrlCheck.Blocked(DenyReason.NO_PRIVILEGE, eval.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name)
        }
    }

    @Volatile
    private var shared: OkHttpClient? = null

    /** 共用 OkHttp 單例（S1-B「複用 OkHttp」：連線池共用；自動重定向一律關閉，由 [fetch] 手動跟隨並逐跳重驗政策/opt-in）。 */
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
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .addNetworkInterceptor(WebFetchRetryAfterStrip())
                .build()
                .also { shared = it }
        }

    /**
     * 跳轉碼判定單一真相：[NetworkPolicy.isRedirectStatus]
     * （`300..303`／`307`／`308`；`304`（Not Modified）／`305`（Use Proxy）／
     * `306` 無 `Location` 跳轉語義，明確排除——落到下方 `!isSuccessful` 分支
     * 以 `HTTP_304` 等 `Failed` 終端，fail-closed，絕不跟隨）。
     */
    private fun isRedirect(code: Int): Boolean = NetworkPolicy.isRedirectStatus(code)

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

    /** 比較兩個 URL 的 origin（scheme + host + port）是否相同。 */
    private fun sameOrigin(a: String, b: String): Boolean {
        val ua = a.toHttpUrlOrNull() ?: return false
        val ub = b.toHttpUrlOrNull() ?: return false
        return ua.scheme == ub.scheme && ua.host == ub.host && ua.port == ub.port
    }

    /** 跳轉失敗一律 typed `REDIRECT_BLOCKED`（[FetchOutcome.Unavailable]，不建第二連線）。 */
    private fun redirectBlocked(): FetchOutcome.Unavailable =
        unavailable(NetworkDenyCode.REDIRECT_BLOCKED.name)

    private fun isCleartextPolicyError(t: Throwable, isCleartextEndpoint: Boolean): Boolean {
        if (!isCleartextEndpoint) return false
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        val stack = ArrayDeque<Throwable>()
        stack.addLast(t)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (seen.put(cur, true) != null) continue
            if (cur is java.net.UnknownServiceException) {
                val msg = cur.message
                if (msg != null && (
                        msg.contains("CLEARTEXT communication", ignoreCase = true) ||
                            (
                                msg.contains("cleartext", ignoreCase = true) &&
                                    (
                                        msg.contains("not permitted", ignoreCase = true) ||
                                            msg.contains("not enabled", ignoreCase = true)
                                        )
                                )
                        )
                ) {
                    return true
                }
            }
            cur.cause?.let { stack.addLast(it) }
            for (suppressed in cur.suppressed) {
                stack.addLast(suppressed)
            }
        }
        return false
    }

    @Volatile
    internal var preflightExecutor: ExecutorService = NetworkPolicy.defaultPreflightExecutor

    private fun evaluateWithBudget(
        budgetMs: Long,
        block: () -> NetworkEvaluation,
    ): NetworkPolicy.PreflightOutcome<NetworkEvaluation> {
        val safeBudgetMs = budgetMs.coerceAtLeast(1L)
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeBudgetMs)
        return try {
            NetworkPolicy.runPreflightBlocking(
                deadlineNs = deadlineNs,
                executor = preflightExecutor,
                block = block,
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            Thread.currentThread().interrupt()
            NetworkPolicy.PreflightOutcome.TimedOut
        }
    }

    /**
     * A4 逐跳 redirect 目標判定（純函數，可單測；零建連）。
     *
     * - 解析失敗／非 http(s) → 拒；
     * - `https → http` 降級永遠拒（先於 DNS：攻擊者可控 Location 不觸發解析）；
     * - 其餘走 [NetworkPolicy.evaluateUrl] 重驗（新決策），拒絕一律正規化為
     *   [NetworkDenyCode.REDIRECT_BLOCKED]（跳往迴環／私有／metadata 等皆同），
     *   放行回該跳評估（含釘住用答案與正規化 URL）。
     */
    /**
     * Redirect opt-in 狀態轉移：跨 origin 不可逆降級為 HTTPS_ONLY（deny-all），
     * 同 origin 則維持目前有效 opt-in（不得恢復已降級權限）。
     */
    internal fun downgradedOptInForRedirect(
        currentUrl: String,
        resolvedUrl: String,
        effectiveOptIn: EndpointNetworkOptIn,
    ): EndpointNetworkOptIn =
        if (sameOrigin(currentUrl, resolvedUrl)) effectiveOptIn else EndpointNetworkOptIn()

    internal fun evaluateRedirectTarget(
        currentUrl: String,
        resolvedUrl: String,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
    ): NetworkEvaluation {
        val fromHttps = currentUrl.toHttpUrlOrNull()?.scheme == "https"
        val toScheme = resolvedUrl.toHttpUrlOrNull()?.scheme
        if (toScheme == null || (fromHttps && toScheme == "http")) {
            return NetworkEvaluation.denied(NetworkDenyCode.REDIRECT_BLOCKED, null)
        }
        val eval = NetworkPolicy.evaluateUrl(resolvedUrl, optIn, resolver)
        if (eval.allowed) return eval
        return NetworkEvaluation.denied(
            NetworkDenyCode.REDIRECT_BLOCKED,
            eval.addressClass,
            eval.resolvedAddresses,
        )
    }

    /**
     * 同步抓取（呼叫方負責切到 IO 執行緒）。
     * 首跳先走 [NetworkPolicy.evaluateUrl]（per-endpoint [optIn]，預設 deny-all），
     * 拒絕即 [FetchOutcome.Unavailable]（typed deny code），不觸碰 [client]；
     * 自動重定向一律關閉（[sharedClient] 及本函式內衍生 client 皆
     * `followRedirects(false)`），最多 [MAX_REDIRECTS] 跳手動跟隨，每跳重做
     * [evaluateRedirectTarget]（`https → http` 降級永不跟隨，含本地迴環在內
     * fail-closed，絕不發出該跳請求；任何跳轉失敗一律 `REDIRECT_BLOCKED`）。
     * 每跳建連走 per-fetch [PinnedNetworkDns] 釘住答案並強制直連。
     */
    fun fetch(
        rawUrl: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        client: OkHttpClient = sharedClient(),
        timeoutMs: Long = TIMEOUT_MS,
        optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
        resolver: HostResolver = SystemHostResolver,
    ): FetchOutcome {
        val cap = maxBytes.coerceIn(1, ABSOLUTE_MAX_BYTES)
        // 超時鉗制：0／負值不得關閉 OkHttp 超時（OkHttp 以 0 表「不超時」），
        // 一律鉗到 1ms–MAX_TIMEOUT_MS；並納入全程端到端 deadline（含首跳及
        // 逐跳 DNS preflight 與 HTTP）。
        val safeTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeTimeout)
        val firstEval = when (
            val pre = evaluateWithBudget(safeTimeout) {
                NetworkPolicy.evaluateUrl(rawUrl, optIn, resolver)
            }
        ) {
            is NetworkPolicy.PreflightOutcome.Completed -> pre.value
            NetworkPolicy.PreflightOutcome.TimedOut -> return transportFailed("TIMEOUT")
            NetworkPolicy.PreflightOutcome.OverloadRejected -> return transportFailed("TRANSPORT")
        }
        if (!firstEval.allowed || firstEval.resolvedAddresses.isEmpty()) {
            return unavailable(firstEval.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name)
        }
        val first = firstEval.normalizedUrl ?: rawUrl
        val firstHost = first.toHttpUrlOrNull()?.host
            ?: return unavailable(NetworkDenyCode.INVALID_HOST.name)
        // Per-fetch DNS pin（A4）：首跳放行答案先釘住；逐跳以原始 [resolver]
        // 重驗（新決策）後才補釘，建連一律走釘住值，evaluate 與 connect 之間
        // 無二次解析（無 TOCTOU rebind）。`strict = true`：未釘住 host 絕不建連。
        val pinned = PinnedNetworkDns(delegate = resolver, strict = true)
        pinned.pin(firstHost, firstEval.resolvedAddresses)
        // 呼叫方傳入的 client 可能仍開啟自動重定向或無界超時：一律強制關閉重定向並直連，
        // 每跳建連再覆寫剩餘端到端 budget 到 connect/read/write/call。
        // 強制直連（`Proxy.NO_PROXY`，與 provider `pinnedTransportFor` 同姿態）：
        // `newBuilder` 繼承呼叫方 client 的 proxy 設定，必須顯式覆寫，否則建連外包
        // 給代理另行解析，繞過 DNS pin（pin 只管直連路徑）。
        // 跨 fetch 連線池重用已關閉：per-fetch `callClientBase` 疊加無閒置池
        //（`ConnectionPool(0, 1, NANOSECONDS)`，對齊 provider `pinnedTransportFor`
        // 與 `McpTransport.postJson`）：舊 keep-alive socket 不得跨 fetch 重用、
        // 繞過當次 pin；同 fetch 內多跳共用同一 pin 生命週期。
        val builder = client.newBuilder()
            .dns(pinned)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
        if (client.networkInterceptors.none { it is WebFetchRetryAfterStrip }) {
            builder.addNetworkInterceptor(WebFetchRetryAfterStrip())
        }
        val callClientBase = builder.build()
        var current = first
        var effectiveOptIn = optIn
        var hops = 0
        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
            if (remainingMs <= 0L) return transportFailed("TIMEOUT")
            val hopClient = callClientBase.newBuilder()
                .connectTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .readTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .writeTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(current).get()
                .header("Accept", "text/*, application/*+json, application/json;q=0.9, */*;q=0.1")
                .build()
            val call = hopClient.newCall(request)
            val next: String = try {
                call.execute().use { response ->
                    if (isRedirect(response.code)) {
                        if (hops >= MAX_REDIRECTS) return redirectBlocked()
                        val location = response.header("Location")
                            ?: return redirectBlocked()
                        val resolved = resolveRedirect(current, location)
                            ?: return redirectBlocked()
                        // A4 逐跳重驗（含 `https → http` 降級永遠拒）：任何拒絕一律
                        // typed `REDIRECT_BLOCKED`，絕不向 redirect target 發請求；
                        // 通過才補釘並跟隨（建連只走釘住答案）。
                        val hopEvalRemaining = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime())
                        if (hopEvalRemaining <= 0L) return transportFailed("TIMEOUT")
                        // 跨 origin 跳轉時 opt-in 不可逆降級為 HTTPS_ONLY（deny-all）：
                        // 原端點授權不得沿用到跨站目標，且後續同 origin 跳轉亦不得恢復。
                        effectiveOptIn = downgradedOptInForRedirect(current, resolved, effectiveOptIn)
                        val hopEval = when (
                            val pre = evaluateWithBudget(hopEvalRemaining) {
                                evaluateRedirectTarget(current, resolved, effectiveOptIn, resolver)
                            }
                        ) {
                            is NetworkPolicy.PreflightOutcome.Completed -> pre.value
                            NetworkPolicy.PreflightOutcome.TimedOut -> return transportFailed("TIMEOUT")
                            NetworkPolicy.PreflightOutcome.OverloadRejected -> return transportFailed("TRANSPORT")
                        }
                        if (!hopEval.allowed || hopEval.resolvedAddresses.isEmpty()) return redirectBlocked()
                        val hopUrl = hopEval.normalizedUrl ?: resolved
                        val hopHost = hopUrl.toHttpUrlOrNull()?.host ?: return redirectBlocked()
                        pinned.pin(hopHost, hopEval.resolvedAddresses)
                        hopUrl
                    } else {
                        if (!response.isSuccessful) {
                            call.cancel()
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
                        val rawType = body.contentType()
                        val capped = readCapped(body.byteStream(), cap, deadlineNs)
                        if (capped.truncated) call.cancel()
                        val text = decodeDowngraded(capped.data, rawType?.charsetName())
                        return FetchOutcome.Ok(
                            text = text,
                            bytesKept = capped.data.size,
                            truncated = capped.truncated,
                            contentType = rawType?.toString(),
                        )
                    }
                }
            } catch (e: SocketTimeoutException) {
                call.cancel()
                return transportFailed("TIMEOUT")
            } catch (e: InterruptedIOException) {
                call.cancel()
                return transportFailed("TIMEOUT")
            } catch (e: IOException) {
                call.cancel()
                if (isCleartextPolicyError(e, current.startsWith("http://", ignoreCase = true))) {
                    return unavailable(NetworkDenyCode.CLEARTEXT_NOT_PERMITTED.name)
                }
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

    private fun readCapped(stream: java.io.InputStream, cap: Int, deadlineNs: Long): Capped {
        val out = ByteArrayOutputStream(minOf(cap, 8192))
        val buf = ByteArray(8192)
        var total = 0
        var truncated = false
        stream.use { input ->
            while (true) {
                if (deadlineNs - System.nanoTime() <= 0L) {
                    throw InterruptedIOException("webfetch: deadline exceeded")
                }
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

/**
 * 剝除 `503 + Retry-After` 觸發 OkHttp 重送：雖 GET 重送相對 POST 安全，
 * 仍為縱深防禦，一律移除該 header 讓 503 原樣回到呼叫方。
 */
internal class WebFetchRetryAfterStrip : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        return if (response.code == 503) {
            response.newBuilder().removeHeader("Retry-After").build()
        } else {
            response
        }
    }
}
