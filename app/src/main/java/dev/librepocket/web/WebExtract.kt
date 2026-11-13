package dev.librepocket.web

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkPolicy
import dev.librepocket.policy.SystemHostResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * D4 `web.extract` 第一版（單 URL；#138 D4；純 JVM，不接線）。
 *
 * - 抓取前（首跳＋每一跳 redirect）必經 [NetworkPolicy.evaluateUrl]
 *  （per-endpoint [EndpointNetworkOptIn] 參數化，預設 deny-all
 *   [EndpointNetworkOptIn]）；拒絕即 `Unavailable`（detail＝denyCode 名），
 *   不送任何請求、不寫快取。
 * - Redirect 手動跟隨（上限 [MAX_REDIRECTS] 跳），逐跳重驗政策；
 *   `https → http` 降級永遠拒（fail-closed，含迴環在內，絕不發出明文請求），
 *   與 `tool/WebFetch.kt` 姿態對齊（只讀對齊，不修改 WebFetch）。
 * - Response 位元組上限：預設 [DEFAULT_MAX_BYTES]（256 KiB），呼叫方可調，
 *   硬頂 [ABSOLUTE_MAX_BYTES]（1 MiB）；超過即 `Failed(TOO_LARGE)`
 *   fail-closed（不存部分、不寫快取），與 malformed（`BAD_RESPONSE`）區分。
 * - Gzip：`Content-Encoding: gzip` 即解壓，但解壓同樣受位元組上限約束
 *  （bomb：小壓縮、大解壓 → `TOO_LARGE` fail-closed）；非 gzip 卻標 gzip
 *   或解壓失敗 → `BAD_RESPONSE`。
 * - Model 可見：預設 [DEFAULT_MODEL_CHARS]（20k），硬頂 [MAX_MODEL_CHARS]
 *  （50k）；超限截斷（UTF-16 surrogate 安全）＋`truncated=true`，非錯誤。
 * - 快取：命中（[WebExtractCache]，TTL [DEFAULT_TTL_MS] 60min）零 upstream、
 *   `cached=true`；`Failed`／`Unavailable`／取消一律不寫。
 * - 取消語義：外部取消原樣透傳 [CancellationException]，不映射、不寫快取、
 *   不 fallback（`fetchRawWithTimeout` 內五處 `ensureActive()` 守衛，與
 *   `WebHttp.fetchWithTimeout` 對稱）。
 * - 內容：`title/url/snippet/published/siteName` 正規化（見 [parseHtml]），
 *   全經 [WebContentEnvelope.sanitize] 清理；模型可見體為
 *   [WebContentEnvelope.wrap] 單層 envelope。
 *
 * 純 JVM fixture 可測：transport 經 [WebExtractHttpGet] 注入，DNS 經
 * [HostResolver] 注入，時鐘經 [WebExtractCache] 注入；零真網。
 */
object WebExtract {
    const val TOOL_NAME = "web.extract"
    const val SWITCH = "webextract"
    const val SWITCH_DEFAULT = false

    const val FALLBACK_HINT = "open the URL manually in a browser"

    /** 預設 response 位元組上限 256 KiB。 */
    const val DEFAULT_MAX_BYTES = 262_144

    /** Response 位元組硬頂 1 MiB（呼叫方要求再大也箝制於此）。 */
    const val ABSOLUTE_MAX_BYTES = 1_048_576

    /** Model 可見預設 20k chars。 */
    const val DEFAULT_MODEL_CHARS = 20_000

    /** Model 可見硬頂 50k chars。 */
    const val MAX_MODEL_CHARS = 50_000

    /** 快取 TTL 60min。 */
    const val DEFAULT_TTL_MS = 60 * 60 * 1_000L

    /** 快取上限筆數（LRU 有界）。 */
    const val MAX_CACHE_ENTRIES = 200

    /** 手動跟隨上限（超過即 `Failed(TOO_MANY_REDIRECTS)`）。 */
    const val MAX_REDIRECTS = 5

    /** 全程超時預算（D4 第一版；呼叫方可注入）。 */
    const val TIMEOUT_MS = 15_000L

    // ---- typed details（穩定字串，供 transcript／測試斷言） ----

    const val DETAIL_EMPTY_URL = "EMPTY_URL"
    const val DETAIL_TOO_LARGE = "TOO_LARGE"
    const val DETAIL_BAD_RESPONSE = "BAD_RESPONSE"
    const val DETAIL_TOO_MANY_REDIRECTS = "TOO_MANY_REDIRECTS"
    const val DETAIL_BAD_REDIRECT = "BAD_REDIRECT"
    const val DETAIL_TIMEOUT = "TIMEOUT"
    const val DETAIL_TRANSPORT = "TRANSPORT"
    const val DETAIL_CLEARTEXT_DOWNGRADE = "CLEARTEXT_DOWNGRADE"
}

/** 正規化抽取內容（`title/url/snippet/published/siteName`，全已 sanitize）。 */
data class WebExtractContent(
    val title: String,
    val url: String,
    val snippet: String,
    val published: String? = null,
    val siteName: String? = null,
)

/** D4 單 URL 抽取結果。 */
sealed interface WebExtractOutcome {
    data class Ok(
        val content: WebExtractContent,
        /** 單層 envelope（`wrap(cappedSnippet)`，模型可見體）。 */
        val envelope: String,
        val truncated: Boolean = false,
        val cached: Boolean = false,
        val bytesKept: Int = 0,
    ) : WebExtractOutcome

    data class Unavailable(
        val detail: String,
        val denyCode: NetworkDenyCode? = null,
    ) : WebExtractOutcome

    data class Failed(val detail: String) : WebExtractOutcome
}

/** 位元組級原始回應（fake 可注入 gzip／大 body／跳轉）。 */
data class WebExtractRawResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebExtractRawResponse) return false
        return code == other.code && headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var r = code
        r = 31 * r + body.contentHashCode()
        r = 31 * r + headers.hashCode()
        return r
    }
}

/** 單次 GET（D4 extract 專用位元組通道；production OkHttp adapter 待 D3 之後）。 */
fun interface WebExtractHttpGet {
    suspend fun get(url: String, headers: Map<String, String>): WebExtractRawResponse
}

internal sealed interface RawFetchOut {
    data class Resp(val response: WebExtractRawResponse) : RawFetchOut
    data class Fail(val detail: String) : RawFetchOut
}

/**
 * 帶可注入 timeout 的抓取（與 `WebHttp.fetchWithTimeout` 同取消語義）。
 *
 * - 自家 timeout → `Fail(TIMEOUT)`；外部取消 → `ensureActive()` 拋 CE 透傳。
 * - `SocketTimeoutException`／`InterruptedIOException` → `TIMEOUT`；
 *   其他 `IOException`／未知 `Exception` → `TRANSPORT`（fail-closed）。
 */
internal suspend fun fetchRawWithTimeout(
    httpGet: WebExtractHttpGet,
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long,
): RawFetchOut {
    val response = try {
        withTimeout(timeoutMs.coerceAtLeast(1)) { httpGet.get(url, headers) }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TIMEOUT)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TIMEOUT)
    } catch (_: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TIMEOUT)
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TRANSPORT)
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        return RawFetchOut.Fail(WebExtract.DETAIL_TRANSPORT)
    }
    return RawFetchOut.Resp(response)
}

/**
 * D4 抽取快取（TTL 60min；純 JVM）。
 *
 * - key＝正規化 URL＋`maxBytes`＋`maxModelChars`（不同預算不互撞）；
 *   只存 `Ok`（`Failed`／`Unavailable`／取消不寫）。
 * - 時鐘可注入，TTL 到期 lazy 淘汰＋`put` 順手掃；有界 LRU
 *  （[WebExtract.MAX_CACHE_ENTRIES]）。
 */
class WebExtractCache(
    val ttlMs: Long = WebExtract.DEFAULT_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be > 0" }
    }

    private data class Entry(val outcome: WebExtractOutcome.Ok, val expiresAt: Long)

    private val map: MutableMap<String, Entry> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
                override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Entry>): Boolean =
                    size > WebExtract.MAX_CACHE_ENTRIES
            },
        )

    fun key(url: String, maxBytes: Int, maxModelChars: Int): String {
        val u = url.trim()
        return "${u.length}:$u\u0001$maxBytes\u0001$maxModelChars"
    }

    fun get(url: String, maxBytes: Int, maxModelChars: Int): WebExtractOutcome.Ok? =
        getByKey(key(url, maxBytes, maxModelChars))

    internal fun getByKey(k: String): WebExtractOutcome.Ok? {
        val entry: Entry?
        synchronized(map) {
            entry = map[k]
            if (entry != null && clockMs() >= entry.expiresAt) {
                map.remove(k)
                return null
            }
        }
        return entry?.outcome?.copy(content = entry.outcome.content.copy())
    }

    fun put(url: String, maxBytes: Int, maxModelChars: Int, outcome: WebExtractOutcome.Ok) {
        putByKey(key(url, maxBytes, maxModelChars), outcome)
    }

    internal fun putByKey(k: String, outcome: WebExtractOutcome.Ok) {
        val now = clockMs()
        val expiresAt = now.coerceAtMost(Long.MAX_VALUE - ttlMs) + ttlMs
        val snapshot = outcome.copy(content = outcome.content.copy())
        synchronized(map) {
            sweepLocked(now)
            map[k] = Entry(snapshot, expiresAt)
        }
    }

    fun evictExpired() {
        synchronized(map) {
            sweepLocked(clockMs())
        }
    }

    fun clear() {
        map.clear()
    }

    val size: Int get() = map.size

    private fun sweepLocked(nowMs: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (nowMs >= it.next().value.expiresAt) it.remove()
        }
    }
}

/**
 * D4 抽取服務（cache＋single-flight；取消不寫 cache）。
 *
 * 與 [WebSearchService] 同併發姿態：
 * - 命中快取 → `copy(cached=true)` 零 upstream。
 * - 同 key 併發合流（leader 打、follower `await()`；follower 取消不影響 leader）。
 * - 取消／`Failed`／`Unavailable` 一律不寫快取。
 */
class WebExtractService(
    private val httpGet: WebExtractHttpGet,
    private val cache: WebExtractCache = WebExtractCache(),
    private val timeoutMs: Long = WebExtract.TIMEOUT_MS,
) {
    fun invalidate() = cache.clear()

    private val mutex = Mutex()
    private val inFlight: MutableMap<String, CompletableDeferred<WebExtractOutcome>> = mutableMapOf()

    suspend fun extract(
        rawUrl: String,
        optIn: EndpointNetworkOptIn = EndpointNetworkOptIn(),
        resolver: HostResolver = SystemHostResolver,
        maxBytes: Int = WebExtract.DEFAULT_MAX_BYTES,
        maxModelChars: Int = WebExtract.DEFAULT_MODEL_CHARS,
    ): WebExtractOutcome {
        if (rawUrl.isBlank()) {
            return WebExtractOutcome.Unavailable(WebExtract.DETAIL_EMPTY_URL, null)
        }
        val effBytes = maxBytes.coerceIn(1, WebExtract.ABSOLUTE_MAX_BYTES)
        val effModel = maxModelChars.coerceIn(1, WebExtract.MAX_MODEL_CHARS)
        val k = cache.key(rawUrl.trim(), effBytes, effModel)
        while (true) {
            cache.getByKey(k)?.let { return it.copy(cached = true) }

            var lateHit: WebExtractOutcome.Ok? = null
            var mine: CompletableDeferred<WebExtractOutcome>? = null
            var leader = false
            mutex.withLock {
                lateHit = cache.getByKey(k)
                if (lateHit == null) {
                    val existing = inFlight[k]
                    if (existing != null) {
                        mine = existing
                    } else {
                        val fresh = CompletableDeferred<WebExtractOutcome>()
                        mine = fresh
                        inFlight[k] = fresh
                        leader = true
                    }
                }
            }
            lateHit?.let { return it.copy(cached = true) }
            val m = mine ?: continue
            if (!leader) {
                try {
                    return m.await()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            var okForFollowers: WebExtractOutcome.Ok? = null
            try {
                val outcome = extractUncached(rawUrl, optIn, resolver, effBytes, effModel)
                if (outcome is WebExtractOutcome.Ok) okForFollowers = outcome
                currentCoroutineContext().ensureActive()
                if (outcome is WebExtractOutcome.Ok) {
                    cache.putByKey(k, outcome)
                }
                m.complete(outcome)
                return outcome
            } catch (e: CancellationException) {
                val ok = okForFollowers
                if (ok != null) {
                    m.complete(ok)
                } else {
                    m.completeExceptionally(e)
                }
                throw e
            } catch (_: Exception) {
                try {
                    currentCoroutineContext().ensureActive()
                } catch (ce: CancellationException) {
                    m.completeExceptionally(ce)
                    throw ce
                }
                val failed = WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
                m.complete(failed)
                return failed
            } catch (e: Throwable) {
                m.completeExceptionally(e)
                throw e
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight[k] === m) inFlight.remove(k)
                    }
                }
            }
        }
    }

    private suspend fun extractUncached(
        rawUrl: String,
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        effBytes: Int,
        effModel: Int,
    ): WebExtractOutcome {
        var current = rawUrl.trim()
        var fromSchemeHttps = false
        var first = true
        var hops = 0
        while (true) {
            // 建連前必經政策（含首跳；redirect 逐跳重驗）。
            val eval = try {
                NetworkPolicy.evaluateUrl(current, optIn, resolver)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TRANSPORT)
            }
            if (!eval.allowed) {
                return WebExtractOutcome.Unavailable(
                    eval.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name,
                    eval.denyCode,
                )
            }
            val normalized = eval.normalizedUrl ?: current
            // https → http 降級永遠拒（含迴環，fail-closed），絕不發出明文請求。
            val curScheme = try {
                normalized.toHttpUrlOrNull()?.scheme?.lowercase()
            } catch (_: Exception) {
                null
            }
            if (!first && fromSchemeHttps && curScheme == "http") {
                return WebExtractOutcome.Unavailable(
                    WebExtract.DETAIL_CLEARTEXT_DOWNGRADE,
                    NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
                )
            }
            fromSchemeHttps = curScheme == "https"
            first = false

            val fetched = fetchRawWithTimeout(
                httpGet,
                normalized,
                mapOf("Accept" to "text/html, text/plain;q=0.9, */*;q=0.1"),
                timeoutMs,
            )
            val response = when (fetched) {
                is RawFetchOut.Fail -> return WebExtractOutcome.Failed(fetched.detail)
                is RawFetchOut.Resp -> fetched.response
            }

            if (response.code in REDIRECT_CODES) {
                if (hops >= WebExtract.MAX_REDIRECTS) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_MANY_REDIRECTS)
                }
                val location = headerLookup(response.headers, "location")
                    ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_REDIRECT)
                val resolved = resolveRedirect(normalized, location)
                    ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_REDIRECT)
                // 預檢 downgrade（未發請求前擋；下一圈政策再驗一次，雙保險）。
                val fromHttps = normalized.toHttpUrlOrNull()?.scheme == "https"
                val toHttp = resolved.toHttpUrlOrNull()?.scheme == "http"
                if (fromHttps && toHttp) {
                    return WebExtractOutcome.Unavailable(
                        WebExtract.DETAIL_CLEARTEXT_DOWNGRADE,
                        NetworkDenyCode.CLEARTEXT_NOT_PERMITTED,
                    )
                }
                current = resolved
                hops++
                continue
            }

            if (response.code !in 200..299) {
                return WebExtractOutcome.Failed("HTTP_${response.code}")
            }

            // 位元組上限（含 gzip 解壓後；超過即 TOO_LARGE fail-closed）。
            val rawBytes = response.body
            if (rawBytes.size > effBytes) {
                // 未解壓即超限：直接 TOO_LARGE（gzip bomb 即使壓縮小也由下步解壓再驗）。
                val enc = headerLookup(response.headers, "content-encoding")
                if (enc == null || !enc.contains("gzip", ignoreCase = true)) {
                    return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
                }
                // 標 gzip 但本體已超限：照樣走解壓路徑（上限內截斷判定仍 TOO_LARGE）。
            }
            val bytes: ByteArray = run {
                val enc = headerLookup(response.headers, "content-encoding")
                if (enc != null && enc.contains("gzip", ignoreCase = true)) {
                    when (val dec = gunzipCapped(rawBytes, effBytes)) {
                        is GunzipOut.Ok -> dec.data
                        is GunzipOut.TooLarge -> return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
                        is GunzipOut.Corrupt -> return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
                    }
                } else {
                    rawBytes
                }
            }
            if (bytes.size > effBytes) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_TOO_LARGE)
            }
            if (bytes.isEmpty()) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
            }

            val charsetName = charsetOf(headerLookup(response.headers, "content-type"))
            val decoded = decodeDowngraded(bytes, charsetName)
            if (decoded.isBlank()) {
                return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)
            }
            val parsed = parseHtml(decoded, normalized)
                ?: return WebExtractOutcome.Failed(WebExtract.DETAIL_BAD_RESPONSE)

            // Model 可見截斷（先剝偽造 marker、sanitize 後、截斷後、envelope 最外）。
            val strippedSnippet = WebContentEnvelope.stripForgedMarkers(parsed.snippet)
            val cleanSnippet = WebContentEnvelope.sanitize(strippedSnippet)
            val strippedTitle = WebContentEnvelope.stripForgedMarkers(parsed.title)
            val cleanTitle = WebContentEnvelope.sanitize(strippedTitle)
            val capped = cleanSnippet.takeExtractPrefix(effModel)
            val truncated = capped.length < cleanSnippet.length
            val content = WebExtractContent(
                title = cleanTitle,
                url = parsed.url,
                snippet = capped,
                published = parsed.published?.let { WebContentEnvelope.sanitize(WebContentEnvelope.stripForgedMarkers(it)) },
                siteName = parsed.siteName,
            )
            val envelope = WebContentEnvelope.wrap(capped)
            return WebExtractOutcome.Ok(
                content = content,
                envelope = envelope,
                truncated = truncated,
                cached = false,
                bytesKept = bytes.size,
            )
        }
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** 不分大小寫的 header 查找（首個命中）。 */
internal fun headerLookup(headers: Map<String, String>, name: String): String? {
    for ((k, v) in headers) {
        if (k.equals(name, ignoreCase = true)) return v
    }
    return null
}

/** 以目前 URL 為基準解析 `Location`（支援相對路徑；非法回 null）。 */
internal fun resolveExtractRedirect(currentUrl: String, location: String): String? =
    resolveRedirect(currentUrl, location)

internal fun resolveRedirect(currentUrl: String, location: String): String? =
    try {
        currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString()
    } catch (_: Exception) {
        null
    }

internal sealed interface GunzipOut {
    data class Ok(val data: ByteArray) : GunzipOut
    data object TooLarge : GunzipOut
    data object Corrupt : GunzipOut
}

/**
 * 受上限的 gzip 解壓（bomb fail-closed）。
 *
 * - 解壓超過 [cap] 即回 [GunzipOut.TooLarge]（不回部分資料）；
 * - 非 gzip／損壞即回 [GunzipOut.Corrupt]（→ `BAD_RESPONSE`，與超限區分）。
 */
internal fun gunzipCapped(data: ByteArray, cap: Int): GunzipOut {
    return try {
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            val out = ByteArrayOutputStream(minOf(cap.coerceAtLeast(1), 8192))
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = gz.read(buf)
                if (n == -1) break
                if (total + n > cap) return GunzipOut.TooLarge
                out.write(buf, 0, n)
                total += n
            }
            GunzipOut.Ok(out.toByteArray())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        GunzipOut.Corrupt
    }
}

internal fun charsetOf(contentType: String?): String? {
    if (contentType.isNullOrBlank()) return null
    // `text/html; charset=gbk` 形狀；大小寫不敏感、引號可選。
    val m = Regex("""charset\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE).find(contentType)
    return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

/** 轉碼降級：未知 charset／損壞位元組一律 UTF-8 replacement 解碼，永不拋異常。 */
internal fun decodeExtractText(data: ByteArray, charsetName: String?): String {
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

internal fun decodeDowngraded(data: ByteArray, charsetName: String?): String =
    decodeExtractText(data, charsetName)

/** 取前 n 個 UTF-16 code units，不切斷 surrogate pair（D4 extract 內部版）。 */
internal fun String.takeExtractPrefix(n: Int): String {
    if (n >= length) return this
    if (n <= 0) return ""
    val s = take(n)
    return if (s.isNotEmpty() && s.last().isHighSurrogate()) s.dropLast(1) else s
}

/**
 * 最小 HTML→內容解析（無依賴、正規化 schema）。
 *
 * - `title`：首個 `<title>` 文本（去 tag＋unescape＋trim）；缺或空白 → 回退 URL。
 * - `snippet`：剔除 `<script>/<style>` 後去 tag＋unescape＋空白收斂；空白 → 回退
 *   title（仍空白即整體空白，由上層判 `BAD_RESPONSE`）。
 * - `published`：`article:published_time`／`datePublished`／`publish_date`／
 *   `<time datetime>` 首個非空；無 → null。
 * - `siteName`：[hostOf]（失敗回 null，不拋）。
 * - 全欄位不拋異常；解析期任何異常 → 回 null（→ `BAD_RESPONSE`）。
 */
internal fun parseHtml(html: String, finalUrl: String): WebExtractContent? {
    return try {
        val titleRaw = HtmlExtract.TITLE.find(html)?.groupValues?.get(1) ?: ""
        val titleText = HtmlExtract.stripText(titleRaw).trim().takeIf { it.isNotEmpty() } ?: finalUrl
        var body = html
        body = HtmlExtract.SCRIPT_STYLE.replace(body, " ")
        val text = HtmlExtract.stripText(body).trim().let { HtmlExtract.Spaces.replace(it, " ") }
        val snippet = text.takeIf { it.isNotEmpty() } ?: titleText
        val published = HtmlExtract.publishedOf(html)?.trim()?.takeIf { it.isNotEmpty() }
        WebExtractContent(
            title = titleText,
            url = finalUrl,
            snippet = snippet,
            published = published,
            siteName = hostOf(finalUrl),
        )
    } catch (_: Exception) {
        null
    }
}

private object HtmlExtract {
    val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val SCRIPT_STYLE = Regex(
        "<(script|style)[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val TAGS = Regex("<[^>]*>")
    val Spaces = Regex("\\s+")
    val META_PUBLISHED = Regex(
        """<meta[^>]+(?:property|name)\s*=\s*["']?(?:article:published_time|datePublished|publish_date|pubdate|DC\.date)["']?[^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    val META_PUBLISHED_REV = Regex(
        """<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]*(?:property|name)\s*=\s*["']?(?:article:published_time|datePublished|publish_date|pubdate|DC\.date)["']?""",
        RegexOption.IGNORE_CASE,
    )
    val TIME_DATETIME = Regex(
        """<time[^>]+datetime\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    val NUMERIC_ENTITY = Regex("&#(\\d+);")
    val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    fun publishedOf(html: String): String? {
        META_PUBLISHED.find(html)?.groupValues?.get(1)?.let { return unescapeHtml(it) }
        META_PUBLISHED_REV.find(html)?.groupValues?.get(1)?.let { return unescapeHtml(it) }
        TIME_DATETIME.find(html)?.groupValues?.get(1)?.let { return unescapeHtml(it) }
        return null
    }

    fun stripText(html: String): String =
        unescapeHtml(TAGS.replace(html, " ")).let { Spaces.replace(it, " ").trim() }

    private fun decodeCodePoint(cp: Int?): String? =
        if (cp != null && cp in 0..0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) String(Character.toChars(cp)) else null

    fun unescapeHtml(raw: String): String {
        var s = raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        s = NUMERIC_ENTITY.replace(s) { m ->
            decodeCodePoint(m.groupValues[1].toIntOrNull()) ?: m.value
        }
        s = HEX_ENTITY.replace(s) { m ->
            decodeCodePoint(m.groupValues[1].toIntOrNull(16)) ?: m.value
        }
        s = s.replace("&amp;", "&")
        return s
    }
}
