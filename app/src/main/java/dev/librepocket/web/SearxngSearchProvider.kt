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
 * - `probe()` 只做設定檢查（[buildUrl]("probe") == null → `MISSING_URL`），不打網路。
 * - Ctor 參數順序與 [DdgsSearchProvider] 不同是歷史因素（此處為 baseUrl 先）；
 *   為避免 positional 誤用請 prefer named args；為 binary-compat 不更名。
 * - Cleartext（http）仍接受（tests＋自架 LAN 需要）；release cleartext 由
 *   NetworkSecurityConfig 管控，平台底層 cleartext 失敗可能被誤報為 TRANSPORT（已知）。
 * - TODO(D3): 由 OkHttp adapter 統一發送透明 app User-Agent（此層不加 header，避免 fake 測壞）。
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
        if (buildUrl("probe") == null) WebProbe.NotReady(WebUnavailableReason.MISSING_URL)
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
        val hits = parseSearxng(response.body, n) ?: return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        val clipped = hits.size > n
        val capped = capResultsForModel(hits.take(n))
        return WebSearchOutcome.Ok(capped.results, capped.truncated || clipped)
    }

    /**
     * URL 組裝（scheme 非 http/https、缺 host、解析失敗 → null → `PROTOCOL`）。
     *
     * 含 userinfo／query／fragment 的 base 視為形狀錯誤一律拒絕（→ null → `PROTOCOL`），
     * 保持 raw-string concat 形狀以縮小 blast radius（不做 URI-builder 重構）。
     * http 仍接受（cleartext 由 release NetworkSecurityConfig 管控，見 class KDoc）。
     */
    internal fun buildUrl(query: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return try {
            val uri = URI(base)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            "$base/search?format=json&q=${URLEncoder.encode(query.trim(), Charsets.UTF_8)}"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * SearXNG JSON 解析（`{"results":[{title,url,content,publishedDate,...}]}`）。
     *
     * - 缺 `url`／非 http(s) 條目跳過（scheme 比對大小寫不敏感）；`title` 缺或 trim 後空白 → 回退 URL；
     *   `content` 缺 → 空字串（回傳前 trim）；`publishedDate`／`published` trim 後空白 → null；
     *   `siteName` 由 URL host 衍生。
     * - 非 primitive 髒欄位以 `(as? JsonPrimitive)?.contentOrNull` 讀取，
     *   單筆髒資料跳過不拋（per-item try/catch）；頂層非 object／`results`
     *   非 array／JSON 損毀 → null（→ `BAD_RESPONSE`）。
     * - [limitHint] 僅用於結果 list 預分配（`coerceAtMost(limitHint + 8)`），不截斷。
     */
    internal fun parseSearxng(body: String, limitHint: Int = 16): List<WebSearchResult>? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val results = root["results"] as? JsonArray ?: return null
        val out = ArrayList<WebSearchResult>(results.size.coerceAtMost(limitHint + 8))
        for (item in results) {
            try {
                val obj = item as? JsonObject ?: continue
                val url = (obj["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) continue
                if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) continue
                val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: url
                val snippet = ((obj["content"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["snippet"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["text"] as? JsonPrimitive)?.contentOrNull
                    ?: "").trim()
                val published = ((obj["publishedDate"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["published"] as? JsonPrimitive)?.contentOrNull)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                out.add(WebSearchResult(title, url, snippet, published, hostOf(url)))
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }
}
