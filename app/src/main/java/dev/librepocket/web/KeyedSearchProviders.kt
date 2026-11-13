package dev.librepocket.web

import dev.librepocket.keystore.KeyVault
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * D5 keyed 搜尋 providers（#138 D5；純 JVM，不接線）。
 *
 * - 四家固定雲端 origin（phase 2）：Brave（GET）／Tavily（POST）／Exa（POST）／
 *   Firecrawl（POST）。各 provider 皆為 [WebSearchProvider] delegate，呼叫方以
 *   [WebSearchService] 包裝後即得 cache＋single-flight（見 [defaultKeyedProviders]
 *   與 `WebSearchD5Test`）。
 * - Key 來源：一律經 [KeyVault] 以 `keyId` 讀取，預設
 *   `web.<providerId>@<origin>`（如 `web.brave@https://api.search.brave.com`）；
 *   keyId 同時綁定 providerId 與 origin——endpoint 切換（不同 keyId）不可沿用舊 key
 *   （見 `keyIdBinding` 測試）。D3 的 `WebSearchSettings.keyIdFor` 為 Settings-UX
 *   的嚴格驗證真相源（本分支不依賴 D3 檔；production 接線時請顯式傳入其回傳值，
 *   本預設值僅覆蓋固定雲端 origin 的形狀）。
 * - 缺 key（vault 無值／讀取失敗／空字串）→ `Unavailable(MISSING_KEY)`，零網路請求，
 *   不靜默換 provider；401／403 → `Failed(AUTH)`、請求形狀錯誤 → `Failed(PROTOCOL)`，
 *   兩者皆不 fallback（見 [WebSearchSelector.mayFallback]）。
 * - Vault 安全：`getKey` 回傳的 [CharArray] 在 `finally` 以 `fill` 抹除後才離開
 *   `search()`；`probe()` 只用 [KeyVault.hasKey]（boolean，不洩漏存在性以外的資訊）。
 *   已知限制：傳輸層（header／JSON body）需不可變 [String] 拷貝，GC 前短暫駐留，
 *   與聊天 provider 的傳輸姿態一致，呼叫方不得記錄請求內容。
 * - POST 複用 GET 語義：Tavily／Exa／Firecrawl 經 [postAsGet] 把 [WebHttpPost]
 *   轉接為 [WebHttpGet]，直接沿用 [fetchWithTimeout] 的 timeout／取消／typed-error
 *   映射，不另起一份映射拷貝（語義漂移風險見 D2 KDoc）。
 * - D3 UI wiring 留待（#165 合併後另 PR）：本檔不註冊 [dev.librepocket.tool.ToolDef]、
 *   不翻 `executionReady`、不碰 Settings 卡；D4 extract（`WebExtract`）獨立於 provider
 *   方法，各家自帶抽取端點（Firecrawl `/scrape` 等）待 D4 落地後再議，不在本檔預留方法
 *   以免與 #170 衝突。
 */

/** 單次 POST（Tavily／Exa／Firecrawl 搜尋 API；production OkHttp adapter 待 D3 之後）。 */
fun interface WebHttpPost {
    suspend fun post(url: String, body: String, headers: Map<String, String>): WebHttpResponse
}

/** POST→GET 轉接（body 按呼叫捕獲；timeout／取消／typed-error 與 GET 完全一致）。 */
private fun postAsGet(httpPost: WebHttpPost, body: String): WebHttpGet =
    WebHttpGet { url, headers -> httpPost.post(url, body, headers) }

private val KeyedSearchJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** `Ok` 收斂（null → `BAD_RESPONSE`；client 端 `take(n)`＋`clipped` 標記，沿 D2 姿態）。 */
private fun finishOk(hits: List<WebSearchResult>?, n: Int): WebSearchOutcome {
    if (hits == null) return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
    val clipped = hits.size > n
    val capped = capResultsForModel(hits.take(n))
    return WebSearchOutcome.Ok(capped.results, capped.truncated || clipped)
}

private fun primitiveOf(obj: JsonObject, vararg keys: String): String? {
    for (k in keys) {
        val v = (obj[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (v != null) return v
    }
    return null
}

private fun httpUrlOrNull(raw: String): String? {
    val u = raw.trim()
    if (u.isEmpty()) return null
    if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) return null
    return u
}

private fun resultOrNull(
    url: String,
    title: String?,
    snippet: String?,
    published: String?,
): WebSearchResult? {
    val u = httpUrlOrNull(url) ?: return null
    return WebSearchResult(
        title = title?.trim()?.takeIf { it.isNotEmpty() } ?: u,
        url = u,
        snippet = snippet?.trim() ?: "",
        published = published?.trim()?.takeIf { it.isNotEmpty() },
        siteName = hostOf(u),
    )
}

/**
 * Keyed provider 共用骨架（key 經 [KeyVault]＋`keyId` 讀取；無 key 零網路）。
 *
 * Throw 契約沿 D1 [WebSearchProvider]：除 [CancellationException] 原樣透傳外不拋異常；
 * vault 讀取失敗 fail-closed 為 `Unavailable(MISSING_KEY)`。
 */
abstract class BaseKeyedSearchProvider(
    private val vault: KeyVault,
    private val keyId: String,
    protected val timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    protected val maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    protected val json: Json = KeyedSearchJson,
) : WebSearchProvider {
    abstract override val id: String

    override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_KEY)

    override suspend fun probe(): WebProbe {
        if (keyId.isBlank()) return WebProbe.NotReady(WebUnavailableReason.MISSING_KEY)
        return try {
            if (vault.hasKey(keyId)) WebProbe.Ready
            else WebProbe.NotReady(WebUnavailableReason.MISSING_KEY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            WebProbe.NotReady(WebUnavailableReason.MISSING_KEY)
        }
    }

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        if (keyId.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
        val n = WebSearchBudgets.countOf(count)
        val raw: CharArray = try {
            vault.getKey(keyId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
        try {
            val key = raw.concatToString().trim()
            if (key.isEmpty()) return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
            return searchWithKey(query.trim(), n, key)
        } finally {
            raw.fill('\u0000')
        }
    }

    /** 子類實作單次請求（key 已就緒非空；沿 D2 契約回值，不拋異常）。 */
    protected abstract suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome
}

/**
 * Brave Search API（GET `{ENDPOINT}?q=...&count=...`；`X-Subscription-Token` 傳 key）。
 *
 * 回應 `{"web":{"results":[{title,url,description,published_date}]}}`；缺 url／非 http(s)
 * 條目跳過（沿 D2 SearXNG 姿態）。
 */
class BraveSearchProvider(
    vault: KeyVault,
    private val httpGet: WebHttpGet,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, timeoutMs, maxBodyChars, json) {
    override val id: String = ID

    // Same posture as SearXNG/DDGS: namespace includes the fixed endpoint
    // so a future custom-endpoint variant cannot share cached rows.
    override val cacheNamespace: String get() = "$id@$ENDPOINT"

    companion object {
        const val ID = "brave"
        const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        const val DEFAULT_KEY_ID = "web.$ID@$ENDPOINT"
    }

    override suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome {
        val url = "$ENDPOINT?q=${URLEncoder.encode(query, Charsets.UTF_8)}&count=$count"
        val response = when (
            val fetched = fetchWithTimeout(
                httpGet,
                url,
                mapOf("Accept" to "application/json", "X-Subscription-Token" to key),
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
        return finishOk(parseBraveResults(json, response.body), count)
    }

    internal fun parseBraveResults(body: String, limitHint: Int = 16): List<WebSearchResult>? =
        parseBraveResults(json, body, limitHint)
}

internal fun parseBraveResults(json: Json, body: String, limitHint: Int = 16): List<WebSearchResult>? {
    val root = try {
        json.parseToJsonElement(body) as? JsonObject ?: return null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    val arr = (root["web"] as? JsonObject)?.get("results") as? JsonArray ?: return null
    val out = ArrayList<WebSearchResult>(arr.size.coerceAtMost(limitHint + 8))
    for (item in arr) {
        try {
            val obj = item as? JsonObject ?: continue
            val url = primitiveOf(obj, "url") ?: continue
            val hit = resultOrNull(
                url = url,
                title = primitiveOf(obj, "title"),
                snippet = primitiveOf(obj, "description", "snippet", "text"),
                published = primitiveOf(obj, "published_date", "published"),
            ) ?: continue
            out.add(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            continue
        }
    }
    return out
}

/**
 * Tavily Search API（POST `{ENDPOINT}`；`api_key` 置於 JSON body，官方文件形狀）。
 *
 * Body `{"api_key","query","max_results","include_answer":false}`；
 * 回應 `{"results":[{title,url,content,published_date}]}`（`answer` 欄位忽略）。
 */
class TavilySearchProvider(
    vault: KeyVault,
    private val httpPost: WebHttpPost,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, timeoutMs, maxBodyChars, json) {
    override val id: String = ID

    // Same posture as SearXNG/DDGS: namespace includes the fixed endpoint
    // so a future custom-endpoint variant cannot share cached rows.
    override val cacheNamespace: String get() = "$id@$ENDPOINT"

    companion object {
        const val ID = "tavily"
        const val ENDPOINT = "https://api.tavily.com/search"
        const val DEFAULT_KEY_ID = "web.$ID@$ENDPOINT"
    }

    override suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome {
        val body = buildJsonObject {
            put("api_key", key)
            put("query", query)
            put("max_results", count)
            put("include_answer", false)
        }.toString()
        val response = when (
            val fetched = fetchWithTimeout(
                postAsGet(httpPost, body),
                ENDPOINT,
                mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
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
        return finishOk(parseTavilyResults(json, response.body), count)
    }

    internal fun parseTavilyResults(body: String, limitHint: Int = 16): List<WebSearchResult>? =
        parseTavilyResults(json, body, limitHint)
}

internal fun parseTavilyResults(json: Json, body: String, limitHint: Int = 16): List<WebSearchResult>? {
    val root = try {
        json.parseToJsonElement(body) as? JsonObject ?: return null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    val arr = root["results"] as? JsonArray ?: return null
    val out = ArrayList<WebSearchResult>(arr.size.coerceAtMost(limitHint + 8))
    for (item in arr) {
        try {
            val obj = item as? JsonObject ?: continue
            val url = primitiveOf(obj, "url") ?: continue
            val hit = resultOrNull(
                url = url,
                title = primitiveOf(obj, "title"),
                snippet = primitiveOf(obj, "content", "snippet", "description"),
                published = primitiveOf(obj, "published_date", "published"),
            ) ?: continue
            out.add(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            continue
        }
    }
    return out
}

/**
 * Exa Search API（POST `{ENDPOINT}`；`x-api-key` header 傳 key）。
 *
 * Body `{"query","numResults"}`；回應 `{"results":[{title,url,text,publishedDate}]}`；
 * `text` 可能很長，下游以 [capResultsForModel] 收斂（沿 D2 姿態）。
 */
class ExaSearchProvider(
    vault: KeyVault,
    private val httpPost: WebHttpPost,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, timeoutMs, maxBodyChars, json) {
    override val id: String = ID

    // Same posture as SearXNG/DDGS: namespace includes the fixed endpoint
    // so a future custom-endpoint variant cannot share cached rows.
    override val cacheNamespace: String get() = "$id@$ENDPOINT"

    companion object {
        const val ID = "exa"
        const val ENDPOINT = "https://api.exa.ai/search"
        const val DEFAULT_KEY_ID = "web.$ID@$ENDPOINT"
    }

    override suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome {
        val body = buildJsonObject {
            put("query", query)
            put("numResults", count)
        }.toString()
        val response = when (
            val fetched = fetchWithTimeout(
                postAsGet(httpPost, body),
                ENDPOINT,
                mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "x-api-key" to key,
                ),
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
        return finishOk(parseExaResults(json, response.body), count)
    }

    internal fun parseExaResults(body: String, limitHint: Int = 16): List<WebSearchResult>? =
        parseExaResults(json, body, limitHint)
}

internal fun parseExaResults(json: Json, body: String, limitHint: Int = 16): List<WebSearchResult>? {
    val root = try {
        json.parseToJsonElement(body) as? JsonObject ?: return null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    val arr = root["results"] as? JsonArray ?: return null
    val out = ArrayList<WebSearchResult>(arr.size.coerceAtMost(limitHint + 8))
    for (item in arr) {
        try {
            val obj = item as? JsonObject ?: continue
            val url = primitiveOf(obj, "url") ?: continue
            val hit = resultOrNull(
                url = url,
                title = primitiveOf(obj, "title"),
                snippet = primitiveOf(obj, "text", "snippet", "description"),
                published = primitiveOf(obj, "publishedDate", "published_date", "published"),
            ) ?: continue
            out.add(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            continue
        }
    }
    return out
}

/**
 * Firecrawl Search API（POST `{ENDPOINT}`；`Authorization: Bearer` 傳 key）。
 *
 * Body `{"query","limit"}`；回應 `{"success":true,"data":[{title,url,description,markdown}]}`。
 * `success:false`（HTTP 200 包錯誤）→ `Failed(SUPPLIER_ERROR)`；`description` 優先、
 * 缺則回退 `markdown`（長文本由 [capResultsForModel] 收斂）。
 */
class FirecrawlSearchProvider(
    vault: KeyVault,
    private val httpPost: WebHttpPost,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, timeoutMs, maxBodyChars, json) {
    override val id: String = ID

    // Same posture as SearXNG/DDGS: namespace includes the fixed endpoint
    // so a future custom-endpoint variant cannot share cached rows.
    override val cacheNamespace: String get() = "$id@$ENDPOINT"

    companion object {
        const val ID = "firecrawl"
        const val ENDPOINT = "https://api.firecrawl.dev/v1/search"
        const val DEFAULT_KEY_ID = "web.$ID@$ENDPOINT"
    }

    override suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome {
        val body = buildJsonObject {
            put("query", query)
            put("limit", count)
        }.toString()
        val response = when (
            val fetched = fetchWithTimeout(
                postAsGet(httpPost, body),
                ENDPOINT,
                mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $key",
                ),
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
        val root = try {
            json.parseToJsonElement(response.body) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            return WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
        }
        return finishOk(parseFirecrawlData(root), count)
    }

    internal fun parseFirecrawlDataForTest(body: String): List<WebSearchResult>? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        return parseFirecrawlData(root)
    }
}

internal fun parseFirecrawlData(root: JsonObject, limitHint: Int = 16): List<WebSearchResult>? {
    val arr = root["data"] as? JsonArray ?: return null
    val out = ArrayList<WebSearchResult>(arr.size.coerceAtMost(limitHint + 8))
    for (item in arr) {
        try {
            val obj = item as? JsonObject ?: continue
            val url = primitiveOf(obj, "url") ?: continue
            val hit = resultOrNull(
                url = url,
                title = primitiveOf(obj, "title"),
                snippet = primitiveOf(obj, "description", "markdown", "snippet"),
                published = primitiveOf(obj, "publishedDate", "published_date", "published"),
            ) ?: continue
            out.add(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            continue
        }
    }
    return out
}

/**
 * D5 預設 keyed 註冊序（Brave→Tavily→Exa→Firecrawl；純建構，不接 production）。
 *
 * 回傳的 delegate 由呼叫方以 [WebSearchService] 包裝後註冊進 [WebSearchRegistry]
 *（cache＋single-flight 在 Service 層）；排序僅為 registry 順序，
 * 實際路由優先序由 [WebSearchSelector]／D3 adapter 決定。
 * 本函數不做任何 key 讀寫、不打網路；D3 UI wiring 落地前無 production 呼叫點。
 */
fun defaultKeyedProviders(
    vault: KeyVault,
    httpGet: WebHttpGet,
    httpPost: WebHttpPost,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
): List<WebSearchProvider> = listOf(
    BraveSearchProvider(vault, httpGet, timeoutMs = timeoutMs, maxBodyChars = maxBodyChars),
    TavilySearchProvider(vault, httpPost, timeoutMs = timeoutMs, maxBodyChars = maxBodyChars),
    ExaSearchProvider(vault, httpPost, timeoutMs = timeoutMs, maxBodyChars = maxBodyChars),
    FirecrawlSearchProvider(vault, httpPost, timeoutMs = timeoutMs, maxBodyChars = maxBodyChars),
)
