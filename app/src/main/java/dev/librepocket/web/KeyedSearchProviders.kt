package dev.librepocket.web

import dev.librepocket.keystore.KeyVault
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * D5 keyed 搜尋 providers（#138 D5；純 JVM，不接線）。
 *
 * - 四家固定雲端 endpoint（phase 2）：Brave（GET）／Tavily（POST）／Exa（POST）／
 *   Firecrawl（POST）。各 provider 皆為 [WebSearchProvider] delegate，呼叫方以
 *   [WebSearchService] 包裝後即得 cache＋single-flight（見 [defaultKeyedProviders]
 *   與 `WebSearchD5Test`）。
 * - Key 來源：一律經 [KeyVault] 以 `keyId` 讀取，預設
 *   `web.<providerId>@<endpoint>`（如 `web.brave@https://api.search.brave.com/res/v1/web/search`；
 *   綁定的是完整 endpoint 路徑）；
 *   keyId 同時綁定 providerId 與 endpoint——本層建構期 fail-fast：任何非綁定 keyId
 *   （foreign 命名空間如 `preset:`／`custom:`／`mcp:`／`jev`、跨 provider、自訂
 *   endpoint）皆於建構拋 [IllegalArgumentException]，endpoint 切換不可沿用舊 key，
 *   聊天 key 也無法被誤送往搜尋 API（defense-in-depth，見 [BaseKeyedSearchProvider]
 *   的 init 檢查與 `mismatchedKeyIdFailsFastAtConstruction` 測試）。自訂 endpoint
 *   需未來 D3 驗證流程另行開放，本層有意不接受。D3 的 `WebSearchSettings.keyIdFor`
 *   留待 D3 落地；無論 D3 為何，本層皆 fail-fast。
 * - 持久故障（vault 無值／讀取完全失敗／blank key）→ `Unavailable(MISSING_KEY)`，零網路請求；
 *   暫時故障（vault 抖動）→ Service 旁路快取（uncacheable），delegate `search()` 會重讀 vault 一次，
 *   若當時恢復則照常打 upstream（query 原文 egress；仍 fail-closed、仍不寫快取、永不用過期憑證），
 *   接線前 consent 文案必須據此告知。不靜默換 provider；
 *   401／403 → `Failed(AUTH)`、請求形狀錯誤 → `Failed(PROTOCOL)`，
 *   兩者皆不 fallback（見 [WebSearchSelector.mayFallback]）。
 * - Vault 安全：`getKey` 回傳的 [CharArray] 以不可變 [CredentialSnapshot] 承載；
 *   snapshot 由最外層持有者於 `finally` 以 `fill` 抹除；
 *   `probe()` 只用 [KeyVault.hasKey]（boolean，不洩漏存在性以外的資訊）。
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

/**
 * `isLenient = true` 用於前向相容與寬鬆解析（沿 D2 姿態）；`maxBodyChars` 是「post-fetch String 級」檢查，不是傳輸層上限——production OkHttp adapter（D3）必須複製 McpTransport 的 content-length 預檢＋串流 byteCap 模式（含解壓後計數），否則上限形同虛設；本層不 log 回應體。
 */
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

/**
 * http(s) URL 白名單檢查：scheme 限 http/https（大小寫不拘）、host 非空白、無 userinfo
 * （拒絕 `https://user:pass@evil.example/`）；否則回傳 null。
 *
 * 未 punycode 化的 Unicode IDN 與含 `_` 的 host 會因 `URI.getHost()==null` 被拒
 * （URI 式白名單為 Phase 1 一併引入——相對 HEAD 為新行為；不改變 D4 之後的 NetworkPolicy 義務）。
 * 本層不 fetch，D4 必須過 NetworkPolicy（http:// 仍放行至 D4 為止）。
 */
private fun httpUrlOrNull(raw: String): String? {
    val u = raw.trim()
    if (u.isEmpty()) return null
    val uri = try {
        URI(u)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null) return null
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
 * 建構期 fail-fast：`keyId` 必須等於 `expectedKeyId`（子類綁定之 `DEFAULT_KEY_ID`，
 * 形狀 `web.<id>@<endpoint>`），否則拋 [IllegalArgumentException]。此檢查防呼叫點誤帶
 * keyId（如 `preset:*`、跨 provider、自訂 endpoint）——foreign 命名空間
 * （`preset:`／`custom:`／`mcp:`／`jev`；聊天憑證與搜尋 key 共用同一 vault，此處防止
 * 聊天 key 被誤送往搜尋 API）、跨 provider（如 Brave 誤帶 `web.tavily@…`）、自訂
 * endpoint。自訂 endpoint 需未來經 D3 驗證流程開放，本層有意不接受；D3 的
 * `WebSearchSettings.keyIdFor` 留待 D3 落地，此層無論如何都 fail-fast。
 *
 * 範圍限定：base class 為 public；內建四家為 final 且各自綁定自身 `DEFAULT_KEY_ID`。
 * 第三方子類若自綁任意常數則不在本防護範圍；未來子類 MUST 綁定自身 `DEFAULT_KEY_ID`。
 *
 * Throw 契約沿 D1 [WebSearchProvider]：除 [CancellationException] 原樣透傳外不拋異常
 * （建構期的 [IllegalArgumentException] 除外）；
 * vault 讀取失敗 fail-closed 為 `Unavailable(MISSING_KEY)`（持久故障零網路；暫時故障見 search() 旁路語義）。
 */
abstract class BaseKeyedSearchProvider(
    private val vault: KeyVault,
    private val keyId: String,
    expectedKeyId: String,
    protected val timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    protected val maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    protected val json: Json = KeyedSearchJson,
) : WebSearchProvider {
    init {
        require(keyId == expectedKeyId) {
            "KEYID_PROVIDER_MISMATCH: keyId must equal this provider's bound credential id (web.<id>@<endpoint>)"
        }
    }

    abstract override val id: String

    override val capabilities: Set<WebCapability> = setOf(WebCapability.REQUIRES_KEY)

    override suspend fun probe(): WebProbe {
        // unreachable defense-in-depth（建構期已 fail-fast）
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

    /**
     * 單次 vault 讀取取得的憑證狀態（F1 fix）：讀取成功 → [CredentialState.Ready]，
     * 同一份不可變 [CredentialSnapshot] 既產生 fingerprint、亦供 HTTP 請求使用，
     * 消除原 read#1(指紋)／read#2(key) 之間的憑證漂移（TOCTOU）。
     * SHA-256（trim 後 byte-packed CharArray，避免中間 String）取前 8 bytes 之十六進位（16 hex chars，64 bits）；
     * in-memory only、不持久化，無 egress 通道；對高熵 key 實務不可逆推回 key material。
     * （低熵 key 的 dictionary 還原不在本層威脅模型：指紋永不落盤、不印出、不出本處理程序。）
     * key 判定前先正規化 trim（leading/trailing 空白，以 `Char.isWhitespace()`／`Character.isSpaceChar(code)` 判定，含全空白 → Unavailable）；
     * trim 僅正規化兩端；key 內任何非 printable ASCII 字元（含內嵌空白、控制／格式／DEL／>0x7E）→ Unavailable；
     * 正當 provider key 為可列印 ASCII，拒絕此類字元以防 header 注入與指紋異常。
     * 無法確定當前憑證狀態（vault 讀取失敗／缺 key／blank／含 FORMAT／CONTROL／非 ASCII／指紋計算失敗）→ [CredentialState.Unavailable]，Service 將完全旁路快取。
     * [CancellationException] 原樣透傳。
     */
    final override suspend fun credentialState(): CredentialState {
        val raw: CharArray = try {
            vault.getKey(keyId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return CredentialState.Unavailable
        } ?: return CredentialState.Unavailable
        // 預作 normalized trim：掃描 leading/trailing 空白（Char.isWhitespace() 或 Character.isSpaceChar(code)），
        // 取 [start, end)；全空白（start >= end）→ wipe raw → Unavailable（不產生 fingerprint 列）。
        var start = 0
        var end = raw.size
        while (start < end && (raw[start].isWhitespace() || Character.isSpaceChar(raw[start].code))) start++
        while (end > start && (raw[end - 1].isWhitespace() || Character.isSpaceChar(raw[end - 1].code))) end--
        if (start >= end) {
            raw.fill('\u0000')
            return CredentialState.Unavailable
        }
        // 範圍非全陣列時製 trimmed 副本並 wipe 原 raw；fingerprint 與 snapshot 皆以 trimmed 為準。
        // copy 在 try 內：Error（如 OOM）window 內亦不得殘留 key material（沿用本函數的 Error-window 加固）。
        val trimmed = if (start == 0 && end == raw.size) {
            raw
        } else {
            try {
                val t = raw.copyOfRange(start, end)
                raw.fill('\u0000')
                t
            } catch (t: Throwable) {
                raw.fill('\u0000')
                throw t
            }
        }
        // 單一可列印 ASCII 述詞（trim 後才判定；trim 僅正規化兩端）：每字符 code 須落在 0x21 '!' ～ 0x7E '~'；
        // key 內任何非 printable ASCII 字元（含內嵌空白、控制／格式／DEL／>0x7E）→ Unavailable
        // （防 header 注入與指紋異常；正當 provider key 為可列印 ASCII，永不含此類字元；blank 全空白亦已由上方 trim 涵蓋）。
        if (trimmed.any { it.code !in 0x21..0x7E }) {
            trimmed.fill('\u0000')
            return CredentialState.Unavailable
        }
        // buf 配置移入 try（Error-window 加固）；任何 Throwable 皆先抹除 trimmed 再原樣拋出。
        val fingerprint = try {
            val buf = ByteArray(trimmed.size * 2)
            try {
                fingerprintOf(trimmed, buf)
            } finally {
                buf.fill(0)
            }
        } catch (e: CancellationException) {
            // CE 原樣透傳；trimmed 一併抹除
            trimmed.fill('\u0000')
            throw e
        } catch (t: Throwable) {
            // Error（如 OOM）window 內亦不得殘留 key material；不改例外型別、原樣拋出。
            trimmed.fill('\u0000')
            throw t
        }
        if (fingerprint == null) {
            // 指紋計算失敗（含理論上的 NoSuchAlgorithmException）→ 回 Unavailable 而非往外拋。
            trimmed.fill('\u0000')
            return CredentialState.Unavailable
        }
        // 所有權移交呼叫方（呼叫方負責 wipe）；此處不得 wipe trimmed。
        return CredentialState.Ready(CredentialSnapshot(trimmed, fingerprint))
    }

    /**
     * 計算 [raw] 的 byte-packed SHA-256 指紋：取 digest 前 8 bytes 直接組出 16 hex chars（64 bits），
     * 不產生完整 hash 字串暫存（S1）；digest 於 finally 清零。任何失敗（含 NoSuchAlgorithmException）→ null。
     * 呼叫方負責 wipe [raw]／[buf]。
     */
    private fun fingerprintOf(raw: CharArray, buf: ByteArray): String? {
        var j = 0
        try {
            for (c in raw) {
                buf[j++] = (c.code and 0xFF).toByte()
                buf[j++] = ((c.code shr 8) and 0xFF).toByte()
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(buf)
            val d = digest.digest()
            return try {
                val sb = StringBuilder(16)
                for (i in 0 until 8) {
                    val b = d[i].toInt() and 0xFF
                    sb.append((b ushr 4).digitToChar(16))
                    sb.append((b and 0x0F).digitToChar(16))
                }
                sb.toString()
            } finally {
                d.fill(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * 以 [snapshot] 執行搜尋（**不再讀 vault**；HTTP 使用 snapshot 的 key）。
     * snapshot 由最外層持有者負責 wipe，本層不得清零。
     */
    final override suspend fun searchWithSnapshot(snapshot: CredentialSnapshot, query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        val key = snapshot.keyAsTrimmedString()
        if (key.isEmpty()) return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
        return searchWithKey(query.trim(), WebSearchBudgets.countOf(count), key)
    }

    /**
     * 單次 vault 讀取：request 與 cache fingerprint 同源（F1 fix），不再有第二次 vault 讀取。
     * 持久 vault 故障／缺 key／blank key → `Unavailable(MISSING_KEY)`、零網路請求、不寫 cache
     *（Ok 以外不進 cache 的既有不變量維持）。
     * 暫時故障則由 delegate 每次呼叫重讀 vault——恢復則照常打 upstream（query egress），仍不快取。
     */
    final override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        // unreachable defense-in-depth（建構期已 fail-fast）
        if (keyId.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
        val n = WebSearchBudgets.countOf(count)
        when (val state = credentialState()) {
            CredentialState.Unavailable -> return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
            is CredentialState.Ready -> {
                try {
                    return searchWithSnapshot(state.snapshot, query, n)
                } finally {
                    state.snapshot.wipe()
                }
            }
            CredentialState.NotApplicable ->
                // unreachable：keyed base 必覆寫 credentialState()
                return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_KEY)
        }
    }

    /** 子類實作單次請求（key 已就緒非空；沿 D2 契約回值，不拋異常）。 */
    protected abstract suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome
}

/**
 * Brave Search API（GET `{ENDPOINT}?q=...&count=...`；`X-Subscription-Token` 傳 key）。
 *
 * 回應 `{"web":{"results":[{title,url,description,page_age}]}}`；缺 url／非 http(s)
 * 條目跳過（沿 D2 SearXNG 姿態）。發布日期欄位為 `page_age`（解析另容忍
 * `published_date`／`published`）。
 */
class BraveSearchProvider(
    vault: KeyVault,
    private val httpGet: WebHttpGet,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, DEFAULT_KEY_ID, timeoutMs, maxBodyChars, json) {
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
        return finishOk(parseBraveResults(json, response.body, count), count)
    }

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
                published = primitiveOf(obj, "page_age", "published_date", "published"),
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
 * Tavily Search API（POST `{ENDPOINT}`；`Authorization: Bearer` header 傳 key，官方現行形狀）。
 *
 * Body `{"query","max_results","include_answer":false}`（Bearer header 為官方現行形狀；
 * 現行 OpenAPI spec 僅餘 bearerAuth，body 不含 api_key 欄位；body 攜帶秘密有寫入日誌風險）；
 * 回應 `{"results":[{title,url,content,…}]}`（`answer` 欄位忽略）。
 * 發布日期欄位需 `include_published_date:true`（本層未要求），故 `published` 維持 null。
 */
class TavilySearchProvider(
    vault: KeyVault,
    private val httpPost: WebHttpPost,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, DEFAULT_KEY_ID, timeoutMs, maxBodyChars, json) {
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
            put("query", query)
            put("max_results", count)
            put("include_answer", false)
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
        return finishOk(parseTavilyResults(json, response.body, count), count)
    }

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
 * Body `{"query","numResults","contents":{"highlights":true}}`（`contents` 選填，但缺則不回
 * text/highlights（本形狀必須帶 highlights 才有摘要））；回應 `{"results":[{title,url,highlights,publishedDate}]}`；
 * snippet 優先取 `highlights` 陣列以 `" "` 接合並 trim，缺則回退 `text`→`snippet`→`description`；
 * 下游以 [capResultsForModel] 收斂（沿 D2 姿態）。
 */
class ExaSearchProvider(
    vault: KeyVault,
    private val httpPost: WebHttpPost,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, DEFAULT_KEY_ID, timeoutMs, maxBodyChars, json) {
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
            putJsonObject("contents") {
                put("highlights", true)
            }
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
        return finishOk(parseExaResults(json, response.body, count), count)
    }

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
            val highlightSnippet = (obj["highlights"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.joinToString(" ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val hit = resultOrNull(
                url = url,
                title = primitiveOf(obj, "title"),
                snippet = highlightSnippet ?: primitiveOf(obj, "text", "snippet", "description"),
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
 * Firecrawl Search API（POST `{ENDPOINT}`，v2；`Authorization: Bearer` 傳 key）。
 *
 * Body `{"query","limit"}`（`query` 前 500 個 UTF-16 code units（Firecrawl v2 契約上限），超過靜默截斷於本層
 * （上層未對 query 設長度上限——本截斷即實際防線；query 上限由 Firecrawl v2 契約決定）；
 * 截斷不切斷 surrogate pair（與 capResultsForModel 同一 [takeUtf16Prefix] 規則））；回應 `{"success":true,"data":{"web":[{title,url,description}]}}`
 * （v2 的 `data` 為物件、`web` 為陣列，非平陣列；v1 已不再針對）。
 * `success` 缺席／非布林（含字串 "true"/"false"）→ `BAD_RESPONSE`；`true` 才進 `data.web` 解析；
 * `false` → `SUPPLIER_ERROR`。
 * snippet 優先 `description`、缺則回退 `markdown`（無 scrapeOptions 時通常缺；description 缺席時
 * 回退 markdown/snippet 仍支援）、再缺 `snippet`。
 * DEFAULT_KEY_ID 綁定 v2 endpoint；自 v1 升級時舊 vault 項目失效，需重新輸入 Firecrawl key（未隨本 PR 發佈）。
 */
class FirecrawlSearchProvider(
    vault: KeyVault,
    private val httpPost: WebHttpPost,
    keyId: String = DEFAULT_KEY_ID,
    timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
    json: Json = KeyedSearchJson,
) : BaseKeyedSearchProvider(vault, keyId, DEFAULT_KEY_ID, timeoutMs, maxBodyChars, json) {
    override val id: String = ID

    // Same posture as SearXNG/DDGS: namespace includes the fixed endpoint
    // so a future custom-endpoint variant cannot share cached rows.
    override val cacheNamespace: String get() = "$id@$ENDPOINT"

    companion object {
        const val ID = "firecrawl"
        const val ENDPOINT = "https://api.firecrawl.dev/v2/search"
        const val DEFAULT_KEY_ID = "web.$ID@$ENDPOINT"
    }

    override suspend fun searchWithKey(query: String, count: Int, key: String): WebSearchOutcome {
        val body = buildJsonObject {
            // Firecrawl v2 `query` 契約上限 500 字元：超過靜默截斷於本層（見類別 KDoc）；
            // 截斷不切斷 surrogate pair（與 capResultsForModel 同一 takeUtf16Prefix 規則）。
            put("query", query.takeUtf16Prefix(500))
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
        val success = root["success"]
        val ok = (success as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        when (ok) {
            true -> Unit
            false -> return WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
            null -> return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        }
        return finishOk(parseFirecrawlData(root, count), count)
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
    val data = root["data"] as? JsonObject ?: return null
    val arr = data["web"] as? JsonArray ?: return null
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
