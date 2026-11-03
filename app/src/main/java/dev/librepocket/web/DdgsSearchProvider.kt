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
 * - Bot 牆（anomaly／challenge 標記）→ `SUPPLIER_ERROR`；空 body → `BAD_RESPONSE`；
 *   合法零命中頁 → `Ok(empty)`（與版式漂移無法廉價區分，見實作註記）。
 */
class DdgsSearchProvider(
    private val httpGet: WebHttpGet,
    private val endpoint: String = DDGS_DEFAULT_ENDPOINT,
    private val timeoutMs: Long = WebHttpTimeouts.DEFAULT_TIMEOUT_MS,
    private val maxBodyChars: Int = WebHttpTimeouts.MAX_BODY_CHARS,
) : WebSearchProvider {

    override val id: String = ID
    override val capabilities: Set<WebCapability> = emptySet()

    companion object {
        const val ID = "ddgs"
        const val DDGS_DEFAULT_ENDPOINT = "https://html.duckduckgo.com/html/"
    }

    override suspend fun probe(): WebProbe = WebProbe.Ready

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        if (query.isBlank()) return WebSearchOutcome.Unavailable(WebUnavailableReason.EMPTY_QUERY)
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
        failureForStatus(response.code)?.let { return WebSearchOutcome.Failed(it) }
        if (response.body.isBlank() || response.body.length > maxBodyChars.coerceAtLeast(1)) {
            return WebSearchOutcome.Failed(WebFailureReason.BAD_RESPONSE)
        }
        if (isBotWall(response.body)) return WebSearchOutcome.Failed(WebFailureReason.SUPPLIER_ERROR)
        val hits = parseDdgsHtml(response.body)
        val capped = capResultsForModel(hits.take(n))
        return WebSearchOutcome.Ok(capped.results, capped.truncated)
    }

    internal fun buildUrl(query: String): String? {
        val base = endpoint.trim()
        if (base.isEmpty()) return null
        return try {
            val uri = java.net.URI(base)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank()) return null
            base.trimEnd('/') + "/?q=" + URLEncoder.encode(query.trim(), "UTF-8")
        } catch (_: Exception) {
            null
        }
    }

    internal fun isBotWall(body: String): Boolean =
        body.contains("anomaly-modal", ignoreCase = true) ||
            body.contains("challenge-form", ignoreCase = true)

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
                        val r = out[pending]
                        out[pending] = r.copy(snippet = Html.stripText(inner))
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
                URLDecoder.decode(uddg, "UTF-8").trim().takeIf {
                    it.startsWith("http://") || it.startsWith("https://")
                }
            } catch (_: Exception) {
                null
            }
        }
        val trimmed = href.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else null
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
        fun unescapeHtml(raw: String): String {
            var s = raw
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            s = NUMERIC_ENTITY.replace(s) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            s = HEX_ENTITY.replace(s) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
            s = s.replace("&amp;", "&")
            return s
        }
    }
}
