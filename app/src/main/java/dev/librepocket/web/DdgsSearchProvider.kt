package dev.librepocket.web

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * D2 DDGS（DuckDuckGo HTML endpoint）client（#138 D2；純 JVM，不接線）。
 *
 * - 形狀：`GET {endpoint}?q=...`（預設 html.duckduckgo.com）；keyless，
 *   非官方 endpoint 警語是 D3 設定 UX 的事（見 #138 Scope）。
 * - 連結包在 `//duckduckgo.com/l/?uddg=<urlencode>` 轉址裡，解析時解開；
 *   標題／snippet 去 tag＋HTML unescape；`count` 在 client 端 `take(n)`。
 * - Bot 牆（anomaly／challenge 標記，含 403 附標記）→ `SUPPLIER_ERROR`；401／無標記
 *   403 仍 `AUTH`；空 body → `BAD_RESPONSE`；
 *   合法零命中頁 → `Ok(empty)`（與版式漂移無法廉價區分，見實作註記；零命中仍會被快取，
 *   為 Phase 2 有意識決定）。
 * - Ctor 參數順序與 [SearxngSearchProvider] 不同是歷史因素（此處為 httpGet 先）；
 *   為避免 positional 誤用請 prefer named args；為 binary-compat 不更名。
 * - Cleartext（http）仍接受（tests＋自架 LAN 需要）；release cleartext 由
 *   NetworkSecurityConfig 管控，平台底層 cleartext 失敗可能被誤報為 TRANSPORT（已知）。
 * - TODO(D3): 由 OkHttp adapter 統一發送透明 app User-Agent（此層不加 header，避免 fake 測壞）。
 */
class DdgsSearchProvider(
    private val httpGet: WebHttpGet,
    private val endpoint: String = DDGS_DEFAULT_ENDPOINT,
    private val timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    private val maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
) : WebSearchProvider {

    override val id: String = ID
    override val capabilities: Set<WebCapability> = emptySet()
    override val cacheNamespace: String get() = "ddgs@" + normalizedEndpoint()

    companion object {
        const val ID = "ddgs"
        const val DDGS_DEFAULT_ENDPOINT = "https://html.duckduckgo.com/html/"
        const val BOT_MARKER_1 = "anomaly-modal"
        const val BOT_MARKER_2 = "challenge-form"
    }

    override suspend fun probe(): WebProbe = when {
        endpoint.isBlank() -> WebProbe.NotReady(WebUnavailableReason.MISSING_URL)
        parseHttpBaseUri(endpoint) == null -> WebProbe.NotReady(WebUnavailableReason.INVALID_URL)
        else -> WebProbe.Ready
    }

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
        if (endpoint.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.MISSING_URL)
        val n = WebSearchBudgets.countOf(count)
        val url = buildUrl(query) ?: return WebSearchOutcome.Failed(WebFailureReason.PROTOCOL)
        val response = when (
            val fetched = fetchWithTimeout(
                httpGet,
                url,
                mapOf("Accept" to "text/html"),
                timeoutMs,
            )
        ) {
            is FetchOut.Fail -> return WebSearchOutcome.Failed(fetched.reason)
            is FetchOut.Resp -> fetched.response
        }
        val botWall by lazy { isBotWall(response.body) }
        if (response.code == 403 && botWall) return WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
        failureForStatus(response.code)?.let { return WebSearchOutcome.Failed(it) }
        if (response.body.isBlank() || response.body.length > maxBodyChars.coerceAtLeast(1)) {
            return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        }
        if (botWall) return WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
        val hits = parseDdgsHtml(response.body)
        val clipped = hits.size > n
        val capped = capResultsForModel(hits.take(n))
        return WebSearchOutcome.Ok(capped.results, capped.truncated || clipped)
    }

    /**
     * 快取命名空間用的正規化 endpoint（scheme＋host 小寫、raw path 保大小寫＋保編碼；
     * 解析失敗回 raw trimmed；尾 `/` 已去；與 Searxng 對稱。
     * Explicit default ports (:443/:80) are NOT canonicalized (kept as distinct namespaces —
     * safe direction: extra upstream, never cross-hit); userinfo/query/fragment are dropped
     * because buildUrl rejects such shapes）。
     * Raw (encoded) path is used so equivalent-decoding paths (e.g. `%2F` vs `/`,
     * `%41` vs `A`) stay isolated (safe direction: extra upstream, never cross-hit)。
     */
    private fun normalizedEndpoint(): String =
        normalizeHttpOrigin(endpoint) ?: endpoint.trim().trimEnd('/')

    /**
     * Endpoint URL 組裝（scheme 非 http/https、缺 host、解析失敗 → null → `PROTOCOL`）。
     *
     * 含 userinfo／query／fragment／控制字元的 base 視為形狀錯誤一律拒絕（→ null → `PROTOCOL`），
     * 保持 raw-string concat 形狀以縮小 blast radius（不做 URI-builder 重構）。
     * http 仍接受（cleartext 由 release NetworkSecurityConfig 管控，見 class KDoc）。
     * Query 以 form encoding（`+` 表空格）編碼，適用於 query component。
     */
    internal fun buildUrl(query: String): String? {
        val base = normalizeHttpOrigin(endpoint) ?: return null
        return "$base/?q=${URLEncoder.encode(query.trim(), Charsets.UTF_8)}"
    }

    internal fun isBotWall(body: String): Boolean =
        body.contains(BOT_MARKER_1, ignoreCase = true) ||
            body.contains(BOT_MARKER_2, ignoreCase = true)

    internal fun parseDdgsHtml(body: String): List<WebSearchResult> {
        val out = ArrayList<WebSearchResult>()
        var pending: Int = -1
        for (match in Html.ANCHOR.findAll(body)) {
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            when {
                Html.RESULT_LINK.containsMatchIn(attrs) -> {
                    val href = Html.hrefOf(attrs)
                        ?.let(Html::unescapeHtml)?.trim().orEmpty()
                    val url = unwrapDdg(href) ?: continue
                    val title = Html.stripText(inner).takeIf { it.isNotEmpty() } ?: url
                    out.add(WebSearchResult(title, url, "", published = null, siteName = hostOf(url)))
                    pending = out.lastIndex
                }
                Html.SNIPPET_LINK.containsMatchIn(attrs) -> {
                    if (pending in out.indices && out[pending].snippet.isEmpty()) {
                        val text = Html.stripText(inner)
                        if (text.isNotEmpty()) {
                            val r = out[pending]
                            out[pending] = r.copy(snippet = text)
                        }
                    }
                }
            }
        }
        return out
    }

    /** `uddg=` 解開；無 `uddg` 的 http(s) 絕對連結照收；其餘（相對路徑等）跳過。 */
    internal fun unwrapDdg(href: String): String? {
        if (href.isEmpty()) return null
        val uddg = Html.UDDG.find(href)?.groupValues?.get(1)
        if (uddg != null) {
            return try {
                URLDecoder.decode(uddg, Charsets.UTF_8).trim().takeIf {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                }
            } catch (_: Exception) {
                null
            }
        }
        val trimmed = href.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) trimmed else null
    }

    private object Html {
        val ANCHOR = Regex(
            """<a\b([^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        val UDDG = Regex("""[?&]uddg=([^&"'\s]+)""")
        val RESULT_LINK = Regex("""\bresult__a\b""")
        val SNIPPET_LINK = Regex("""\bresult__snippet\b""")
        val TAGS = Regex("<[^>]*>")
        val SPACES = Regex("\\s+")
        val NUMERIC_ENTITY = Regex("&#(\\d+);")
        val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

        /** 取 href（雙引號／單引號皆支援；空字串回 ""，缺失回 null）。 */
        fun hrefOf(attrs: String): String? {
            val m = HREF.find(attrs) ?: return null
            return if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2]
        }

        fun stripText(html: String): String =
            unescapeHtml(TAGS.replace(html, " ")).let { SPACES.replace(it, " ").trim() }

        /**
         * HTML entity 解碼（`&lt;`／`&gt;`／`&quot;`／`&#39;`／數字／hex 先解，
         * `&amp;` 最後解，避免 `&amp;lt;` 被二次解成 `<`）。
         */
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
}
