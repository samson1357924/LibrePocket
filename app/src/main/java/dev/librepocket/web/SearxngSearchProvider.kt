package dev.librepocket.web

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * D2 SearXNG client（#138 D2；純 JVM，不接線）。
 *
 * - 形狀：`GET {baseUrl}/search?format=json&q=...`；SearXNG 以分頁回傳，
 *   `count` 在 client 端 `take(n)`（不自創 server-side 參數）。
 * - Timeout 可注入（[timeoutMs]）；狀態映射見 [failureForStatus]；
 *   解析失敗／body 超限 → `BAD_RESPONSE`。
 * - `probe()` 只做設定檢查（缺 URL → `MISSING_URL`），不打網路。
 */
class SearxngSearchProvider(
    private val baseUrl: String,
    private val httpGet: WebHttpGet,
    private val timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    private val maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    private val json: Json = SearxngJson,
) : WebSearchProvider {

    override val id: String = ID
    override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_URL)

    companion object {
        const val ID = "searxng"
        internal val SearxngJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    override suspend fun probe(): WebProbe =
        if (baseUrl.isBlank()) WebProbe.NotReady(WebUnavailableReason.MISSING_URL)
        else WebProbe.Ready

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        if (baseUrl.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_URL)
        val n = WebSearchBudgets.countOf(count)
        val url = buildUrl(query) ?: return WebSearchOutcome.Failed(WebFailureReason.PROTOCOL)
        val response = when (
            val fetched = fetchWithTimeout(
                httpGet,
                url,
                mapOf("Accept" to "application/json"),
                timeoutMs,
            )
        ) {
            is FetchOut.Fail -> return WebSearchOutcome.Failed(fetched.reason)
            is FetchOut.Resp -> fetched.response
        }
        failureForStatus(response.code)?.let { return WebSearchOutcome.Failed(it) }
        if (response.body.length > maxBodyChars.coerceAtLeast(1)) {
            return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        }
        val hits = parseSearxng(response.body) ?: return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        val capped = capResultsForModel(hits.take(n))
        return WebSearchOutcome.Ok(capped.results, capped.truncated)
    }

    /** URL 組裝（scheme 非 http/https、缺 host、解析失敗 → null → `PROTOCOL`）。 */
    internal fun buildUrl(query: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return try {
            val uri = URI(base)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            "$base/search?format=json&q=${URLEncoder.encode(query.trim(), "UTF-8")}"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * SearXNG JSON 解析（`{"results":[{title,url,content,publishedDate,...}]}`）。
     *
     * - 缺 `url`／非 http(s) 條目跳過；`title` 缺或空白 → 回退 URL；
     *   `content` 缺 → 空字串；`siteName` 由 URL host 衍生。
     * - 非 primitive 髒欄位以 `(as? JsonPrimitive)?.contentOrNull` 讀取，
     *   單筆髒資料跳過不拋（per-item try/catch）；頂層非 object／`results`
     *   非 array／JSON 損毀 → null（→ `BAD_RESPONSE`）。
     */
    internal fun parseSearxng(body: String): List<WebSearchResult>? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val results = root["results"] as? JsonArray ?: return null
        val out = ArrayList<WebSearchResult>(results.size)
        for (item in results) {
            try {
                val obj = item as? JsonObject ?: continue
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) continue
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: url
                val snippet = (obj["content"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["snippet"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["text"] as? JsonPrimitive)?.contentOrNull
                    ?: ""
                val published = (obj["publishedDate"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["published"] as? JsonPrimitive)?.contentOrNull
                out.add(WebSearchResult(title, url, snippet, published, hostOf(url)))
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }
}
