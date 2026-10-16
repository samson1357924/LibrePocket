package dev.librepocket.mcp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * D03 JSON-RPC 2.0 編解碼（Streamable HTTP 最小子集，原創）。
 *
 * 線路形狀（本包自定最小 Sup 集合，不依賴 org.json 以保持純 JVM 可測）：
 * - tools/list 请求：`{"jsonrpc":"2.0","id":<n>,"method":"tools/list","params":{}}`
 * - tools/list 回應：`{"jsonrpc":"2.0","id":<n>,"result":{"tools":[
 *     {"name":"..","description":"..","inputSchema":{...}}, ... ]}}`
 * - tools/call 请求：`{"jsonrpc":"2.0","id":<n>,"method":"tools/call",
 *     "params":{"name":"..","arguments":{... raw json ...}}}`
 *   `argumentsJson` 呼叫方可預檢，本函仍自驗證 fail-closed（非 object 即拋，不發送）。
 * - tools/call 回應：`{"jsonrpc":"2.0","id":<n>,"result":{
 *     "content":[...],"isError":false}}`；或 `{"error":{"code":..,"message":".."}}`。
 *
 * 注意：Token 永不進 body（builder 不收 token 參數，傳輸層只放標頭）。
 *
 * Stage D：單一嚴格遞迴 JSON parser（raw-span DOM）取代全部手寫掃描器。
 * P1（巢狀同名鍵遮蔽）與 P2（巢狀形狀錯誤回 OK）皆由 DOM 層級正確性關閉。
 *
 * 已知非目標（follow-up，不在本層驗證）：`jsonrpc == "2.0"` 版本斷言、
 * response id 與 request id 對應（呼叫方以 HTTP 一問一答配對，不做 id 複用檢查）。
 *
 * 修訂立場（#11 Stage2 決策，單一最小子集起步）：`2025-11-25` 與 `2026-07-28`
 * 皆為 Proposed（repo 內無規格文本、無外網可核對，不寫差異表）；本物件只保證
 * `tools/list`＋`tools/call` 單頁 POST 子集，不送 `initialize`、不接 SSE stream、
 * 不跟隨 `nextCursor` 分頁、不協商 `protocolVersion`。契約見
 * `McpProtocolContractsTest`；修訂支援須先納規格文本再逐項補契約。
 */
internal object McpWire {

    /** 輸入 Schema 字元上限：超限拒絕（拋 [McpProtocol]），不靜默截斷。 */
    internal const val INPUT_SCHEMA_MAX_CHARS = 8_192

    /**
     * RFC 8259 §2 的空白：僅 space/tab/LF/CR。Kotlin `isWhitespace()` 另接受
     * VT/FF/NBSP 等，parser 全程必須用此函數，不可用 `isWhitespace()`。
     */
    private fun isJsonWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** JSON 字面量（true/false/null/number）校驗：非法字面量一律視為無效（全字串匹配，錨點顯式鎖定）。 */
    private val NUMBER_RE = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    /** 遞迴深度上限：超過拋 `mcp: too deep`（防 StackOverflow；MCP payload 不可能 64 層）。 */
    private const val MAX_DEPTH = 64

    // ---- raw-span DOM（最小；每節點記原文子串，inputSchema/content 原樣保留） ----

    internal sealed interface JVal {
        val raw: String
    }

    internal data class JObj(
        val members: List<Pair<String, JVal>>,
        override val raw: String,
    ) : JVal {
        fun find(key: String): JVal? = members.firstOrNull { it.first == key }?.second
    }

    internal data class JArr(
        val items: List<JVal>,
        override val raw: String,
    ) : JVal

    internal data class JStr(
        val v: String,
        override val raw: String,
    ) : JVal

    internal data class JNum(
        override val raw: String,
    ) : JVal

    internal data class JBool(
        val v: Boolean,
        override val raw: String,
    ) : JVal

    internal data class JNull(
        override val raw: String,
    ) : JVal

    /**
     * 嚴格解析整份文件為 DOM。
     *
     * 要求前導空白 + `{` 或 `[`（頂層必須是值；[parseList]/[parseCall] 再要求 object）、
     * 嚴格走訪、尾隨僅空白；每層重複 key（解碼後比對）拋 `mcp: duplicate key`；
     * 深度 > 64 拋 `mcp: too deep`；其餘形狀錯誤拋 `mcp: bad envelope`。
     */
    internal fun parseStrictDoc(json: String): JVal {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        var p = 0
        while (p < json.length && isJsonWs(json[p])) p++
        if (p >= json.length) throw McpProtocol("mcp: bad envelope")
        val c = json[p]
        if (c != '{' && c != '[') throw McpProtocol("mcp: bad envelope")
        val (v, next) = parseValue(json, p, 0)
        var q = next
        while (q < json.length && isJsonWs(json[q])) q++
        if (q != json.length) throw McpProtocol("mcp: bad envelope")
        return v
    }

    private fun parseValue(json: String, pos: Int, depth: Int): Pair<JVal, Int> {
        if (depth > MAX_DEPTH) throw McpProtocol("mcp: too deep")
        var p = pos
        while (p < json.length && isJsonWs(json[p])) p++
        if (p >= json.length) throw McpProtocol("mcp: bad envelope")
        return when (val c = json[p]) {
            '"' -> parseString(json, p)
            '{' -> parseObject(json, p, depth)
            '[' -> parseArray(json, p, depth)
            't', 'f', 'n' -> parseLiteral(json, p)
            '-', in '0'..'9' -> parseNumber(json, p)
            else -> throw McpProtocol("mcp: bad envelope")
        }
    }

    private fun parseString(json: String, start: Int): Pair<JStr, Int> {
        // start 指向開引號；嚴格轉義，raw 含引號原樣保留。
        var p = start + 1
        val sb = StringBuilder()
        while (p < json.length) {
            val c = json[p++]
            when (c) {
                '"' -> return JStr(sb.toString(), json.substring(start, p)) to p
                '\\' -> {
                    if (p >= json.length) throw McpProtocol("mcp: bad envelope")
                    when (val e = json[p++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (p + 4 > json.length) throw McpProtocol("mcp: bad envelope")
                            val hex = json.substring(p, p + 4)
                            if (hex.length != 4 || !hex.all {
                                    it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
                                }
                            ) {
                                throw McpProtocol("mcp: bad envelope")
                            }
                            sb.append(hex.toInt(16).toChar())
                            p += 4
                        }
                        else -> throw McpProtocol("mcp: bad envelope")
                    }
                }
                else -> {
                    // 未轉義控制字元（U+0000..U+001F）非法。
                    if (c.code < 0x20) throw McpProtocol("mcp: bad envelope")
                    sb.append(c)
                }
            }
        }
        throw McpProtocol("mcp: bad envelope")
    }

    private fun parseObject(json: String, start: Int, depth: Int): Pair<JObj, Int> {
        // start 指向 `{`；值遞迴 depth+1；同層重複 key 拋 duplicate key。
        var p = start + 1
        while (p < json.length && isJsonWs(json[p])) p++
        if (p >= json.length) throw McpProtocol("mcp: bad envelope")
        if (json[p] == '}') {
            p++
            return JObj(emptyList(), json.substring(start, p)) to p
        }
        val members = ArrayList<Pair<String, JVal>>()
        val seen = HashSet<String>()
        while (true) {
            while (p < json.length && isJsonWs(json[p])) p++
            if (p >= json.length || json[p] != '"') throw McpProtocol("mcp: bad envelope")
            val (keyVal, keyEnd) = parseString(json, p)
            p = keyEnd
            while (p < json.length && isJsonWs(json[p])) p++
            if (p >= json.length || json[p] != ':') throw McpProtocol("mcp: bad envelope")
            p++
            while (p < json.length && isJsonWs(json[p])) p++
            if (p >= json.length) throw McpProtocol("mcp: bad envelope")
            val (v, vEnd) = parseValue(json, p, depth + 1)
            p = vEnd
            if (!seen.add(keyVal.v)) throw McpProtocol("mcp: duplicate key")
            members.add(keyVal.v to v)
            while (p < json.length && isJsonWs(json[p])) p++
            if (p >= json.length) throw McpProtocol("mcp: bad envelope")
            when (json[p]) {
                ',' -> {
                    p++
                    var q = p
                    while (q < json.length && isJsonWs(json[q])) q++
                    if (q >= json.length) throw McpProtocol("mcp: bad envelope")
                    if (json[q] == '}') throw McpProtocol("mcp: bad envelope")
                    p = q
                    // 下一輪頂端要求 `"`；若為 `,`/結尾等一律 bad envelope。
                }
                '}' -> {
                    p++
                    return JObj(members.toList(), json.substring(start, p)) to p
                }
                else -> throw McpProtocol("mcp: bad envelope")
            }
        }
    }

    private fun parseArray(json: String, start: Int, depth: Int): Pair<JArr, Int> {
        var p = start + 1
        while (p < json.length && isJsonWs(json[p])) p++
        if (p >= json.length) throw McpProtocol("mcp: bad envelope")
        if (json[p] == ']') {
            p++
            return JArr(emptyList(), json.substring(start, p)) to p
        }
        val items = ArrayList<JVal>()
        while (true) {
            while (p < json.length && isJsonWs(json[p])) p++
            if (p >= json.length) throw McpProtocol("mcp: bad envelope")
            // 空元素（`,,`/`[,]`）非法。
            if (json[p] == ',' || json[p] == ']') throw McpProtocol("mcp: bad envelope")
            val (v, vEnd) = parseValue(json, p, depth + 1)
            items.add(v)
            p = vEnd
            while (p < json.length && isJsonWs(json[p])) p++
            if (p >= json.length) throw McpProtocol("mcp: bad envelope")
            when (json[p]) {
                ',' -> {
                    p++
                    var q = p
                    while (q < json.length && isJsonWs(json[q])) q++
                    if (q >= json.length) throw McpProtocol("mcp: bad envelope")
                    if (json[q] == ']') throw McpProtocol("mcp: bad envelope")
                    p = q
                }
                ']' -> {
                    p++
                    return JArr(items.toList(), json.substring(start, p)) to p
                }
                else -> throw McpProtocol("mcp: bad envelope")
            }
        }
    }

    private fun parseNumber(json: String, start: Int): Pair<JNum, Int> {
        var p = start
        while (p < json.length && (json[p].isLetterOrDigit() || json[p] == '-' || json[p] == '+' ||
                json[p] == '.' || json[p] == '_' || json[p] == 'e' || json[p] == 'E')
        ) {
            p++
        }
        val lit = json.substring(start, p)
        if (lit.isEmpty() || !NUMBER_RE.matches(lit)) throw McpProtocol("mcp: bad envelope")
        return JNum(lit) to p
    }

    private fun parseLiteral(json: String, start: Int): Pair<JVal, Int> {
        fun delimOk(end: Int): Boolean {
            if (end > json.length) return false
            if (end == json.length) return true
            val c = json[end]
            return isJsonWs(c) || c == ',' || c == ']' || c == '}'
        }
        if (json.startsWith("true", start)) {
            val end = start + 4
            if (!delimOk(end)) throw McpProtocol("mcp: bad envelope")
            return JBool(true, "true") to end
        }
        if (json.startsWith("false", start)) {
            val end = start + 5
            if (!delimOk(end)) throw McpProtocol("mcp: bad envelope")
            return JBool(false, "false") to end
        }
        if (json.startsWith("null", start)) {
            val end = start + 4
            if (!delimOk(end)) throw McpProtocol("mcp: bad envelope")
            return JNull("null") to end
        }
        throw McpProtocol("mcp: bad envelope")
    }

    /**
     * 使用者自訂完整 URL（bare-origin 自動補 `/mcp`；有 path 原樣保留）。
     *
     * 相容舊配置：舊只寫 origin（如 `https://host` 或 `https://host/`）的配置自動補 `/mcp`；
     * 有 path 則整段原樣保留（尾斜線、大小寫、query/fragment 全不動：
     * `https://h/api/mcp/` 與 `https://h/api/mcp` 對某些 server 不等價，不得正規化）。
     * query/fragment（`?`/`#`）保留並在前面插入 `/mcp`
     *（如 `https://h?x=1` → `https://h/mcp?x=1`；`/` 視為 bare）。
     * 呼叫方對自動補 path 的情況建議記 warning（見 [McpTransport]）。
     */
    fun endpoint(baseUrl: String): String {
        val (main, suffix) = splitMainAndSuffix(baseUrl)
        // 無 path（bare-origin）→ 補 /mcp；有 path 原樣保留（尾斜線亦保留，query/fragment 原樣接回）。
        // bare-origin 判斷複用 isBareOrigin()（單一真相來源；行為見 endpoint 測試鎖定）。
        return if (isBareOrigin(baseUrl)) "${main.trimEnd('/')}/mcp$suffix" else "$main$suffix"
    }

    /** URL 切分：`?`/`#` 之前為 main，之後（含符號）為 suffix；供 [endpoint] 與 [isBareOrigin] 共用。 */
    internal data class UrlParts(val main: String, val suffix: String)

    internal fun splitMainAndSuffix(url: String): UrlParts {
        val trimmed = url.trim()
        val q = trimmed.indexOf('?')
        val h = trimmed.indexOf('#')
        val cut = when {
            q >= 0 && h >= 0 -> minOf(q, h)
            q >= 0 -> q
            h >= 0 -> h
            else -> -1
        }
        return UrlParts(
            if (cut >= 0) trimmed.substring(0, cut) else trimmed,
            if (cut >= 0) trimmed.substring(cut) else "",
        )
    }

    /** endpoint() 是否會自動補 `/mcp`（bare-origin 判斷；供傳輸層 warning 用）。 */
    internal fun isBareOrigin(baseUrl: String): Boolean {
        val mainTrimmed = splitMainAndSuffix(baseUrl).main.trimEnd('/')
        val schemeEnd = mainTrimmed.indexOf("://")
        val pathStart = if (schemeEnd >= 0) {
            mainTrimmed.indexOf('/', schemeEnd + 3)
        } else {
            mainTrimmed.indexOf('/')
        }
        return pathStart < 0
    }

    /**
     * 安全端點預驗（fail-closed，供 [McpServer] 在碰 vault／發送前呼叫）。
     *
     * - 空白即拋 `mcp: blank endpoint`；
     * - 經 [endpoint] 正規化後必須能解析為 http(s) URL，否則拋 `mcp: bad endpoint`；
     * - 僅放行 `https`，以及回環 `http`（`localhost`／`127.0.0.1`／`[::1]`，
     *   供 MockWebServer／本機開發；其餘 `http` 一律拋 `mcp: insecure endpoint`）。
     * 回傳正規化後 endpoint（可直接發送）。
     */
    fun requireSecureEndpoint(baseUrl: String): String {
        if (baseUrl.isBlank()) throw IllegalArgumentException("mcp: blank endpoint")
        val url = endpoint(baseUrl)
        val parsed = try {
            url.toHttpUrlOrNull()
        } catch (_: Exception) {
            null
        } ?: throw IllegalArgumentException("mcp: bad endpoint")
        if (parsed.scheme == "https") return url
        if (parsed.scheme == "http" && isLoopbackHost(parsed.host)) return url
        throw IllegalArgumentException("mcp: insecure endpoint")
    }

    internal fun isLoopbackHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        return h == "localhost" || h == "127.0.0.1" || h == "::1"
    }

    fun listBody(id: Long = 1L): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""

    /**
     * 組裝 tools/call 請求體（fail-closed）。
     *
     * - `toolName` 空白即拋（不送 `"name":""` 無效請求）；
     * - `argumentsJson` 必須是合法 JSON object 文本（空字串視為 `{}`），
     *   非 object（`null`／陣列／畸形）即拋，不發送；
     * - `toolName` 經 [esc] 轉義（含 `\b`／`\f`／C0 控制字元 `\\u00XX`）。
     */
    fun callBody(toolName: String, argumentsJson: String, id: Long = 2L): String {
        require(toolName.isNotBlank()) { "mcp: blank tool name" }
        val args = argumentsJson.trim().ifEmpty { "{}" }
        // fail-closed：arguments 必須是合法 JSON object 文本（上層不再另行保證，
        // 此處直接驗證；非法即拋，呼叫方在發送前擋下，不送畸形 JSON-RPC）。
        val parsed = try {
            parseStrictDoc(args)
        } catch (e: McpProtocol) {
            throw IllegalArgumentException("mcp: bad arguments: ${e.message}")
        }
        if (parsed !is JObj) throw IllegalArgumentException("mcp: bad arguments")
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call"""" +
            ""","params":{"name":"${esc(toolName)}","arguments":$args}}"""
    }

    /**
     * 解析 tools/list；惡形輸入拋 IllegalArgumentException（呼叫方映射為 PROTOCOL）。
     *
     * 經 [parseStrictDoc] 嚴格走訪全文（截斷/尾隨 garbage/缺逗號/重複鍵/非法字面量/
     * 非法 escape/超深一律 [McpProtocol]）；只認頂層 `result` 包裝，
     * 再從 `result` 內取 `tools` 陣列；層級一律經 DOM 直接成員查找，
     * 巢狀同名鍵不得遮蔽外層。inputSchema 超限拒絕不截斷；name/description 短欄位 take 保留。
     */
    fun parseList(serverId: String, json: String): List<McpTool> {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        val doc = parseStrictDoc(json)
        if (doc !is JObj) throw McpProtocol("mcp: bad envelope")
        val resultVal = doc.find("result")
            ?: throw IllegalArgumentException("mcp: missing result")
        if (resultVal is JNull) throw IllegalArgumentException("mcp: missing result")
        if (resultVal !is JObj) throw IllegalArgumentException("mcp: bad result.tools")
        val toolsVal = resultVal.find("tools")
            ?: throw IllegalArgumentException("mcp: bad result.tools")
        if (toolsVal !is JArr) throw IllegalArgumentException("mcp: bad result.tools")
        if (toolsVal.items.isEmpty()) return emptyList()
        return toolsVal.items.map { item ->
            if (item !is JObj) throw McpProtocol("mcp: bad tools item")
            val nameVal = item.find("name")
            if (nameVal !is JStr) throw IllegalArgumentException("mcp: tool without name")
            val descVal = item.find("description")
            val desc = if (descVal is JStr) descVal.v else ""
            val schemaVal = item.find("inputSchema")
            val schema = when {
                schemaVal == null -> """{"type":"object"}"""
                schemaVal is JObj -> schemaVal.raw
                else -> throw McpProtocol("mcp: bad inputSchema")
            }
            if (schema.length > INPUT_SCHEMA_MAX_CHARS) {
                throw McpProtocol("mcp: inputSchema exceeds $INPUT_SCHEMA_MAX_CHARS chars")
            }
            McpTool(
                serverId = serverId,
                name = nameVal.v.take(128),
                description = desc.take(500),
                inputSchema = schema,
            )
        }
    }

    /**
     * 解析 tools/call：回傳 (resultJson, isError)。
     *
     * 經 [parseStrictDoc] 嚴格走訪全文；只有頂層 `error` 鍵才算 error 包；
     * `isError`/`content` 只認頂層 `result` 物件的直接成員（DOM depth-1），
     * 巢狀同名鍵不得誤判；缺 `result` 即拋 IllegalArgumentException（fail-closed）。
     * 回傳的 resultJson 用 DOM 節點 `raw`（content 優先，否則整包 result）。
     *
     * 已知限制（鎖定現狀，不視為相容承諾）：
     * - 空 `result`（`{}`，既無 `content` 又無 `structuredContent`）視為 OK
     *  （`resultJson="{}"`，`isError=false`）；
     * - `content` 存在時 `structuredContent` 被丟棄（僅保留 `content.raw`）。
     */
    fun parseCall(json: String): Pair<String?, Boolean> {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        val doc = parseStrictDoc(json)
        if (doc !is JObj) throw McpProtocol("mcp: bad envelope")
        val errVal = doc.find("error")
        if (errVal != null) {
            if (errVal is JNull) {
                // 忽略，續走 result（既有 nullTopLevelErrorIsOk 語義）。
            } else if (errVal is JObj) {
                val msgVal = errVal.find("message")
                val msg = if (msgVal is JStr) msgVal.v else "tool error"
                throw McpToolFailure(msg.take(200))
            } else {
                throw McpProtocol("mcp: bad error")
            }
        }
        val resultVal = doc.find("result")
            ?: throw IllegalArgumentException("mcp: missing result")
        if (resultVal is JNull) throw IllegalArgumentException("mcp: missing result")
        if (resultVal !is JObj) throw IllegalArgumentException("mcp: bad result")
        val contentVal = resultVal.find("content")
        if (contentVal != null && contentVal !is JArr) {
            throw McpProtocol("mcp: bad content")
        }
        val isErrorVal = resultVal.find("isError")
        val isError = when {
            isErrorVal == null -> false
            isErrorVal is JBool -> isErrorVal.v
            isErrorVal is JNull -> false
            else -> throw McpProtocol("mcp: bad isError")
        }
        // 內存消費用：優先保留 content，否則整個 result；調用方用後即丟，不寫盤。
        return ((contentVal?.raw ?: resultVal.raw)) to isError
    }

    class McpToolFailure(message: String) : IllegalArgumentException(message)

    /** 協議形狀錯誤（inputSchema 超限、缺 result 等）：[McpServer] 映射為 PROTOCOL。 */
    class McpProtocol(message: String) : IllegalArgumentException(message)

    internal fun esc(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
