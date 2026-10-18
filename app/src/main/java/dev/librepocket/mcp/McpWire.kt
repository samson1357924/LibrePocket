package dev.librepocket.mcp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * D03 JSON-RPC 2.0 編解碼（Streamable HTTP 最小子集，原創）。
 *
 * 線路形狀（本包自定最小 subset 集合，不依賴 org.json 以保持純 JVM 可測）：
 * - tools/list 請求：`{"jsonrpc":"2.0","id":<n>,"method":"tools/list","params":{}}`
 * - tools/list 回應：`{"jsonrpc":"2.0","id":<n>,"result":{"tools":[
 *     {"name":"..","description":"..","inputSchema":{...}}, ... ]}}`
 * - tools/call 請求：`{"jsonrpc":"2.0","id":<n>,"method":"tools/call",
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
 * Stage2 envelope 完整性（外部 review #2）：回應必須 `jsonrpc == "2.0"`、
 * `id` 存在（server 呼叫時以 expectedId 數值對應，固定 list=1／call=2）、
 * `result` XOR `error`、request-shaped（含 `method`）一律拒；
 * error 依 code 分類（見 [parseCall]）。HTTP 一問一答配對仍不支援 id 複用
 * （單發單回，不生成隨機 id）；server 路徑（必傳 expectedId）對錯 id 回應
 * 一律 fail-closed 不採納，null expectedId 僅限測試直呼叫。
 *
 * 修訂立場（#11 Stage2 本 PR 選型，非 Accepted ADR；單一傳輸子集起步）：
 * `2025-11-25` 與 `2026-07-28`（#11 提及的候選修訂號，repo 內無文本）
 * 皆為候選未採納（repo 內無規格文本、無外網可核對，不寫差異表）；本物件只保證
 * `tools/list`＋`tools/call` 單頁 POST 子集，不送 `initialize`、不接 SSE stream、
 * 不跟隨 `nextCursor` 分頁、不協商 `protocolVersion`。契約見
 * `McpProtocolContractsTest`；修訂支援須先納規格文本再逐項補契約。
 */
internal object McpWire {

    /** 輸入 Schema 字元上限：超限拒絕（拋 [McpProtocol]），不靜默截斷。 */
    internal const val INPUT_SCHEMA_MAX_CHARS = 8_192

    /** tools/list 請求固定 id（單發子集；[McpServer] 收回應時以 expectedId=LIST_ID 校驗）。 */
    internal const val LIST_ID = 1L

    /** tools/call 請求固定 id（同 [LIST_ID]；以 expectedId=CALL_ID 校驗回應）。 */
    internal const val CALL_ID = 2L

    /**
     * 出站 toolName 字元上限（超限 [callBody] 拒絕不發送）。
     * 不對稱政策（已測）：入站 [parseListPage] 超限名截到此上限後採納
     * （保頁面可用；前綴相同的長名會碰撞成同名——`enabledTools`／`McpScope`
     * 以截斷後名為鍵，屬已知取捨），出站 [callBody] 則直接拒。
     */
    internal const val TOOL_NAME_MAX_CHARS = 128

    /**
     * 出站 arguments 字元上限（值與回應字元上限 [McpTransport.RESPONSE_MAX_CHARS]
     * 同值但語意獨立：入站回應／出站 arguments 各管各的；
     * 超限拒絕不發送，訊息明指 arguments——不得誤稱 response。
     * 注意為 UTF-16 code units 計數（64k emoji＝256KB，無出站位元組上限，
     * 僅本地配置可控——伺服器可拒收）。
     */
    internal const val ARGUMENTS_MAX_CHARS = McpTransport.RESPONSE_MAX_CHARS

    /** 回應 `jsonrpc` 必須嚴格等於此值（JSON-RPC 2.0；缺席／其他版本一律拒）。 */
    private const val JSONRPC_VERSION = "2.0"

    /**
     * 協議／伺服器端 JSON-RPC 錯誤碼（parse error／invalid request／method not found／
     * internal error）：表示協議不可互通或端點配置錯誤，[parseCall] 拋 [McpProtocol]
     * （[McpServer] 映射 PROTOCOL + 計熔斷）。`-32602`（invalid params）與未知碼
     * 屬工具級中性（[McpToolFailure]），見 [parseCall]。
     */
    private val PROTOCOL_ERROR_CODES = setOf(-32700L, -32600L, -32601L, -32603L)

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

    fun listBody(id: Long = LIST_ID): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""

    /**
     * 組裝 tools/call 請求體（fail-closed）。
     *
     * - `toolName` 空白即拋（不送 `"name":""` 無效請求）；
     * - `toolName` 超 [TOOL_NAME_MAX_CHARS] 即拋（[parseList] 入站名同用此常數；
     *   出站同樣不放行超長名，不送可預期被拒的請求）；
     * - `argumentsJson` 必須是合法 JSON object 文本（空字串視為 `{}`），
     *   非 object（`null`／陣列／畸形）即拋，不發送；
     *   字元數超 [ARGUMENTS_MAX_CHARS] 即拋（訊息明指 arguments，非 response）；
     * - `toolName` 經 [esc] 轉義（含 `\b`／`\f`／C0 控制字元 `\\u00XX`）。
     */
    fun callBody(toolName: String, argumentsJson: String, id: Long = CALL_ID): String {
        require(toolName.isNotBlank()) { "mcp: blank tool name" }
        require(toolName.length <= TOOL_NAME_MAX_CHARS) {
            "mcp: tool name exceeds $TOOL_NAME_MAX_CHARS chars"
        }
        val args = argumentsJson.trim().ifEmpty { "{}" }
        if (args.length > ARGUMENTS_MAX_CHARS) {
            throw IllegalArgumentException("mcp: arguments exceed $ARGUMENTS_MAX_CHARS chars")
        }
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
     * 通用回應 envelope 驗證（[parseList]／[parseCall] 共用，外部 review #2）。
     *
     * - `jsonrpc` 必須存在且嚴格等於 `"2.0"`（缺席／`"1.0"`／數字 2.0 一律
     *   [McpProtocol] `mcp: bad jsonrpc`）；
     * - `id` 必須存在且非 null（否則 `mcp: missing id`）；[expectedId] 非 null 時
     *   必須為數值且相等（`mcp: id mismatch`——單發子集不支援 id 複用，
     *   錯 id 回應不得採納，也不得重置熔斷）；無 [expectedId] 時僅要求
     *   數值或字串型別（`mcp: bad id`）；
     * - request-shaped（含 `method` 鍵）一律拒（`mcp: request-shaped response`）：
     *   server 端請求包不是本子集的回應。
     */
    private fun requireResponseEnvelope(doc: JObj, expectedId: Long?) {
        val jsonrpc = doc.find("jsonrpc")
        if (jsonrpc !is JStr || jsonrpc.v != JSONRPC_VERSION) {
            throw McpProtocol("mcp: bad jsonrpc")
        }
        val id = doc.find("id")
        if (id == null || id is JNull) throw McpProtocol("mcp: missing id")
        if (expectedId != null) {
            if (id !is JNum || id.raw.toLongOrNull() != expectedId) {
                throw McpProtocol("mcp: id mismatch")
            }
        } else if (id !is JNum && id !is JStr) {
            throw McpProtocol("mcp: bad id")
        }
        if (doc.find("method") != null) {
            throw McpProtocol("mcp: request-shaped response")
        }
    }

    /** tools/list 單頁解析結果：本頁工具＋是否還有下一頁（非空 `nextCursor`）。 */
    internal data class McpListPage(
        val tools: List<McpTool>,
        val hasMorePages: Boolean,
    )

    /**
     * 解析 tools/list（僅工具，不含分頁旗標；見 [parseListPage]）。
     * 契約與 [parseListPage] 相同，為既有測試的薄委託。
     * **生產禁用**：此 API 同時失去 hasMorePages（PARTIAL）與 expectedId 強制
     *（預設 null 只驗 id 存在），生產呼叫方一律用 [parseListPage]＋[LIST_ID]。
     */
    fun parseList(serverId: String, json: String, expectedId: Long? = null): List<McpTool> =
        parseListPage(serverId, json, expectedId).tools

    /**
     * 解析 tools/list 單頁；惡形輸入拋 IllegalArgumentException（呼叫方映射為 PROTOCOL）。
     *
     * 經 [parseStrictDoc] 嚴格走訪全文（截斷/尾隨 garbage/缺逗號/重複鍵/非法字面量/
     * 非法 escape/超深一律 [McpProtocol]）；再經 [requireResponseEnvelope]
     * 驗 `jsonrpc=="2.0"`、`id` 存在／相符、request-shaped 拒；
     * tools/list 回應不得是 error 包：**error 鍵存在即拒**（含 `error:null`、
     * 含 result+error 矛盾包；不分 code：協議碼、`-32602`、未知碼、
     * 暫態 `-320xx` 一律 [McpProtocol] PROTOCOL + 計熔斷——list 無業務錯通道，
     * 不當空列表、不採納）；只認頂層 `result` 包裝，
     * 再從 `result` 內取 `tools` 陣列；層級一律經 DOM 直接成員查找，
     * 巢狀同名鍵不得遮蔽外層。inputSchema 超限拒絕不截斷；
     * name 超限截到 [TOOL_NAME_MAX_CHARS]（與出站拒的不對稱政策見該常數 KDoc）、
     * 空白／空名照收（列出即不可用——[callBody] 呼叫時拒，fail-closed 於使用點）、
     * description 非字串（含 null/數字/物件）降為空字串（描述欄位型別寬容，
     * 不影響工具可用性）。
     *
     * `nextCursor`（外部 review #3）：非空字串 → [McpListPage.hasMorePages]=true
     * （呼叫方映射 PARTIAL，不回 OK 假完整）；缺席／null／空字串 → false；
     * 其他型別（數字／object／布林）→ [McpProtocol] `mcp: bad nextCursor`。
     * 僅認 `result` 內的 `nextCursor`（頂層同名鍵視為未知鍵忽略，與未知鍵政策一致）；
     * 驗證順序：`tools` 陣列型別 → `nextCursor` → `tools` 陣列項目內容
     * （cursor 異常與 tools 項目異常同時存在時，`mcp: bad nextCursor` 勝出）。
     *
     * @param expectedId server 呼叫時傳 [LIST_ID]（數值比對回應 id）；
     *   null（測試直呼叫）只驗 id 存在與型別。
     */
    fun parseListPage(serverId: String, json: String, expectedId: Long? = null): McpListPage {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        val doc = parseStrictDoc(json)
        if (doc !is JObj) throw McpProtocol("mcp: bad envelope")
        requireResponseEnvelope(doc, expectedId)
        if (doc.find("error") != null) {
            // error 回應不屬 tools/list 成功子集（-32601 等協議/伺服器錯；
            // result+error 同時存在亦先在此擋下）：一律 PROTOCOL + 計熔斷。
            throw McpProtocol("mcp: unexpected error response")
        }
        val resultVal = doc.find("result")
            ?: throw IllegalArgumentException("mcp: missing result")
        if (resultVal is JNull) throw IllegalArgumentException("mcp: missing result")
        if (resultVal !is JObj) throw IllegalArgumentException("mcp: bad result.tools")
        val toolsVal = resultVal.find("tools")
            ?: throw IllegalArgumentException("mcp: bad result.tools")
        if (toolsVal !is JArr) throw IllegalArgumentException("mcp: bad result.tools")
        val cursorVal = resultVal.find("nextCursor")
        val hasMorePages = when (cursorVal) {
            null, is JNull -> false
            is JStr -> cursorVal.v.isNotEmpty()
            else -> throw McpProtocol("mcp: bad nextCursor")
        }
        val tools = if (toolsVal.items.isEmpty()) {
            emptyList()
        } else {
            toolsVal.items.map { item ->
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
                    name = truncateUnits(nameVal.v, TOOL_NAME_MAX_CHARS),
                    description = truncateUnits(desc, 500),
                    inputSchema = schema,
                )
            }
        }
        return McpListPage(tools, hasMorePages)
    }

    /**
     * 解析 tools/call：回傳 (resultJson, isError)。
     *
     * 經 [parseStrictDoc] 嚴格走訪全文；再經 [requireResponseEnvelope] 驗
     * `jsonrpc=="2.0"`、`id` 存在／相符、request-shaped（含 `method`）拒。
     *
     * `result` XOR `error`（外部 review #2）：先驗 `error` 型別（鍵存在即必須是 object，
     * `null`／字串／數字皆 [McpProtocol] `mcp: bad error`），再驗互斥
     * （object error ＋ 任何 `result` 鍵存在 → `mcp: result and error both present`）；
     * `code` 必須是整數（缺 code／字串 code 一律 `mcp: bad error`，不靜默當業務錯）。
     *
     * error code 分類（外部 review #4）：
     * - `-32700`／`-32600`／`-32601`／`-32603`（協議／伺服器端錯，見 [PROTOCOL_ERROR_CODES]）
     *   → 拋 [McpProtocol]（[McpServer] 映射 PROTOCOL + 計熔斷，breaker 能得知不互通）；
     * - `-32602`（invalid params）與未知碼 → 拋 [McpToolFailure]（工具級中性；
     *   message 取伺服器原文上限 200 UTF-16 code units（[truncateUnits]，
     *   不切 surrogate pair），由 [McpServer.callTool] 透傳至
     *   [McpCallResult.errorMessage]，不進 auditMap）。
     *
     * `code` 必須是整數字面量（不含指數／小數／溢位；否則 `mcp: bad error`）。
     * 注意不對稱：[parseList] 對任何 error 包（含 `-32602`/未知碼）一律
     * [McpProtocol]（list 無業務錯通道，協議碼與暫態碼同等計熔斷）。
     * 另注意 envelope 驗證先於 code 分類：規格合法的 `id:null` error 回應
     * （parse/invalid-request 場景）在 [requireResponseEnvelope] 即以
     * `mcp: missing id` 判 PROTOCOL＋計熔斷，不進 code 分類（更嚴，fail-closed）。
     *
     * 結果完整性（外部 review #5，Stage3 修正）：
     * - 結果分支驗證順序：`content` 型別 → `content` 元素 → `structuredContent` 型別 →
     *   `isError` 型別 → 兩鍵皆無判定（與 list 端優先序同為文件化順序，非巧合）；
     * - `content` 可缺席（structuredContent-only 為本子集已測的刻意寬容，MCP 規格
     *   `content` 必備——此為已知規格偏差）；但存在時必須是陣列且**每個元素**是
     *   含字串 `type` 的 object（null／非陣列 → `mcp: bad content`；元素非 object
     *   或缺字串 type → `mcp: bad content item`——null 不視為缺席，與
     *   `structuredContent` 的寬容 null 分歧）；
     * - `result` 既無 `content` 又無 `structuredContent`（空 `{}`、或僅 `isError` 的
     *   無內容變體）→ [McpProtocol] `mcp: empty result`（不當成功、不當業務錯：
     *   形狀不完整一律 PROTOCOL + 計熔斷）；
     * - `structuredContent` 存在時 null 視為缺席（與 `nextCursor: null`／
     *   `isError: null` 的可選鍵寬容對稱），其餘非 object → `mcp: bad structuredContent`；
     * - `resultJson` 保留資料不丟：僅 `content` → `content.raw`；
     *   僅 `structuredContent` → `structuredContent.raw`；
     *   兩者皆有 → 整包 `result.raw`（兩種資料都保留）。
     *
     * `isError`/`content`/`structuredContent` 只認頂層 `result` 物件的直接成員
     * （DOM depth-1），巢狀同名鍵不得誤判；缺 `result` 即拋
     * IllegalArgumentException（fail-closed）。
     *
     * @param expectedId server 呼叫時傳 [CALL_ID]（數值比對回應 id）；
     *   null（測試直呼叫）只驗 id 存在與型別。
     */
    fun parseCall(json: String, expectedId: Long? = null): Pair<String?, Boolean> {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        val doc = parseStrictDoc(json)
        if (doc !is JObj) throw McpProtocol("mcp: bad envelope")
        requireResponseEnvelope(doc, expectedId)
        val errVal = doc.find("error")
        val resultVal = doc.find("result")
        if (errVal != null) {
            if (errVal !is JObj) throw McpProtocol("mcp: bad error")
            if (resultVal != null) {
                // result XOR error：同時存在是矛盾包（伺服器 bug 或非本子集），
                // 不得擇一採納（舊「error 優先」會採納矛盾內容並重置熔斷）。
                throw McpProtocol("mcp: result and error both present")
            }
            val codeVal = errVal.find("code")
            val code = (codeVal as? JNum)?.raw?.toLongOrNull()
                ?: throw McpProtocol("mcp: bad error")
            val msgVal = errVal.find("message")
            val msg = if (msgVal is JStr) msgVal.v else "tool error"
            if (code in PROTOCOL_ERROR_CODES) throw McpProtocol("mcp: jsonrpc error $code")
            throw McpToolFailure(truncateUnits(msg, 200))
        }
        if (resultVal == null || resultVal is JNull) throw IllegalArgumentException("mcp: missing result")
        if (resultVal !is JObj) throw IllegalArgumentException("mcp: bad result")
        val contentVal = resultVal.find("content")
        if (contentVal != null && contentVal !is JArr) {
            throw McpProtocol("mcp: bad content")
        }
        // 元素層驗證（fail-closed）：每個 content 元素必須是含字串 type 的 object
        // （MCP ContentBlock 必備欄位）；[1]/["x"]/缺 type → 拒，不得原文透傳回 OK。
        if (contentVal is JArr) {
            for (item in contentVal.items) {
                if (item !is JObj || item.find("type") !is JStr) {
                    throw McpProtocol("mcp: bad content item")
                }
            }
        }
        val structuredVal = resultVal.find("structuredContent")
        // null 視為缺席（與 nextCursor: null／isError: null 的可選鍵寬容對稱）；
        // 其餘非 object（array/string/number）拒。
        if (structuredVal != null && structuredVal !is JNull && structuredVal !is JObj) {
            throw McpProtocol("mcp: bad structuredContent")
        }
        val isErrorVal = resultVal.find("isError")
        val isError = when {
            isErrorVal == null -> false
            isErrorVal is JBool -> isErrorVal.v
            isErrorVal is JNull -> false
            else -> throw McpProtocol("mcp: bad isError")
        }
        // 結果保留（外部 review #5）：兩種資料皆存時整包保留、不得擇一丟棄；
        // 兩者皆無（空 result）fail-closed 拒，不當成功。調用方用後即丟，不寫盤。
        // structuredContent: null 同上視為缺席（不得把 "null" 原文當結構化結果）。
        val resultJson = when {
            contentVal != null && structuredVal != null && structuredVal !is JNull -> resultVal.raw
            contentVal != null -> contentVal.raw
            structuredVal != null && structuredVal !is JNull -> structuredVal.raw
            else -> throw McpProtocol("mcp: empty result")
        }
        return resultJson to isError
    }

    class McpToolFailure(message: String) : IllegalArgumentException(message)

    /** 協議形狀錯誤（inputSchema 超限、缺 result 等）：[McpServer] 映射為 PROTOCOL。 */
    class McpProtocol(message: String) : IllegalArgumentException(message)

    /**
     * UTF-16 code-unit 截斷（[errorMessage][McpCallResult.errorMessage] 200／入站名 128）：
     * 若截斷點落在 surrogate pair 中間，去掉尾端孤立 high surrogate——
     * 避免孤立 surrogate 序列化成 U+FFFD／'?'（出站名錯字、渲染亂碼）。
     */
    internal fun truncateUnits(s: String, maxUnits: Int): String {
        if (s.length <= maxUnits) return s
        val cut = s.take(maxUnits)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

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
