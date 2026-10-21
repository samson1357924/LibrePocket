package dev.librepocket.mcp

import java.util.Locale
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
 *   `argumentsJson` 呼叫方可預檢，本函仍自驗證 fail-closed（非 object 即拋，不發送）；
 *   [callBody] 建構期以 [parseStrictDoc] fail-closed 釘住該約定（上層已過 Schema 校驗）。
 * - tools/call 回應：`{"jsonrpc":"2.0","id":<n>,"result":{
 *     "content":[...],"isError":false}}`；或 `{"error":{"code":..,"message":".."}}`
 *   （`code` 必備整數、`message` 必備字串——JSON-RPC 2.0 §5.1，缺/型別錯一律拒）。
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
 * （單發單回，不生成隨機 id）；server 路徑對錯 id 回應一律 fail-closed 不採納。
 * **B-07（expectedId 顯式化）**：[parseList]／[parseListPage]／[parseCall] 的
 * `expectedId` **沒有預設值**——每個呼叫點必須顯式傳值或顯式 `null`
 * （生產只傳 [LIST_ID]／[CALL_ID]；`null`＝僅驗 id 存在與型別，**僅限測試直呼叫**），
 * 「放寬語意」不再是可誤觸的預設，由簽名機械強制
 * （`McpEnvelopeTest.expectedIdHasNoImplicitDefaultOnAnyParserEntryPoint` 釘面）。
 *
 * 修訂立場（#11 Stage2 本 PR 選型，非 Accepted ADR；單一傳輸子集起步）：
 * 依 #11 的 2026-10-07 review 引註，`2026-07-28` 是現行 revision（per-request metadata
 * 模式）、`2025-11-25` 是前一版（需 `initialize`／`initialized` 與條件式 session header）；
 * 兩者皆為 #11 提及的候選修訂號（repo 內無規格文本、無外網可核對，不寫差異表），
 * 故皆未採納——本物件不對任一 revision 宣稱相容：只保證 `tools/list`＋`tools/call`
 * 單頁 POST 子集，不送 `initialize`、不接 SSE stream、不跟隨 `nextCursor` 分頁、
 * 不協商 `protocolVersion`。契約見 `McpProtocolContractsTest`（由 #93 引入並已合併進 main）；
 * 修訂支援須先納規格文本再逐項補契約。
 */
internal object McpWire {

    /** 輸入 Schema 字元上限：超限拒絕（拋 [McpProtocol]），不靜默截斷。 */
    internal const val INPUT_SCHEMA_MAX_CHARS = 8_192

    /**
     * tools/list description 字元上限：超限**截斷**（[truncateUnits]，surrogate-safe）
     * 而非拒絕——描述僅供展示、不影響工具可用性，與 name（[TOOL_NAME_MAX_CHARS] 拒絕）
     * 及 inputSchema（[INPUT_SCHEMA_MAX_CHARS] 拒絕）的政策區別在此。
     */
    internal const val DESCRIPTION_MAX_CHARS = 500

    /**
     * tools/call error 包 `message` 顯示上限（UTF-16 code units）：
     * [escapeControlUnits] 消毒後仍不得超過此上限（轉義展開計入預算，
     * 在轉義/surrogate 邊界截斷）；[McpServer.callTool] 透傳前再以此值
     * 防禦性鉗一次（消毒後已是 no-op，單一真相在此常數）。
     */
    internal const val ERROR_MESSAGE_MAX_UNITS = 200

    /** tools/list 請求固定 id（單發子集；[McpServer] 收回應時以 expectedId=LIST_ID 校驗）。 */
    internal const val LIST_ID = 1L

    /** tools/call 請求固定 id（同 [LIST_ID]；以 expectedId=CALL_ID 校驗回應）。 */
    internal const val CALL_ID = 2L

    /**
     * toolName 字元上限（入站 [parseListPage] 與出站 [callBody] 同政策，已測）：
     * 入站超限名一律 [McpProtocol] 拒絕整頁（fail-closed，不靜默截斷——
     * 截斷後名會被 [callBody] 當成另一支工具真的送達伺服器，且 `enabledTools`／
     * `McpScope` 以名為鍵，前綴相同的長名碰撞會造成授權對象錯位）；
     * 出站超限直接拒、不發送。
     * 交叉引用：`McpScope.requireBareToolName`（C-02 授權路徑形狀閘）**直接引用本常數**
     * ——單一真相，不另立魔數。
     */
    internal const val TOOL_NAME_MAX_CHARS = 128

    /**
     * 保留工具名前綴（`mcp.`）：**單一真相直接引用 [McpScope.SWITCH_PREFIX]**（本常數
     * 是該常數的別名，不另立魔數——`McpScope` 與本物件永不各寫一份字面值）。
     *
     * 保留前綴**兩端同政策**（見本常數、[parseListPage] 與 `McpScope.requireBareToolName`
     * 三處 KDoc）：
     * - 入站（遠端 `tools/list`）：以此前綴開頭的遠端工具名**整頁拒**
     *   （拋 [McpProtocol] → PROTOCOL + 計熔斷）——`mcp.` 是 [dev.librepocket.tool.ToolDef.name]／
     *   開關鍵的形狀，不是遠端 bare 名；照收會「列出但不可用」（所有 `McpScope`
     *   授權入口都拒該前綴），且未來接線 `map { McpScope.toToolDef(...) }`
     *   會拋未映射 `IllegalArgumentException` 打斷整輪；
     * - 出站／授權側（`McpScope.requireBareToolName`）：拒用此前綴名（同政策的另一端）。
     *
     * 大小寫敏感（正向已測）：只有小寫 `mcp.` 是保留前綴，`MCP.x` 屬合法 bare 名照收
     * （與 `McpScope.SWITCH_PREFIX` 的 `startsWith` 語義一致，不做正規化）。
     */
    internal const val RESERVED_TOOL_PREFIX: String = McpScope.SWITCH_PREFIX

    /**
     * 出站 arguments 字元上限（值與回應字元上限 [McpTransport.RESPONSE_MAX_CHARS]
     * 同值但語意獨立：入站回應／出站 arguments 各管各的；
     * 超限拒絕不發送，訊息明指 arguments——不得誤稱 response。
     * 注意為 UTF-16 code units 計數：64,000 units 最壞 187.5 KiB（全 BMP 3-byte CJK，
     * 64,000×3 B）或 125 KiB（全 astral emoji，32,000×4 B），無出站位元組上限，
     * 僅本地配置可控——伺服器可拒收）。
     */
    internal const val ARGUMENTS_MAX_CHARS = McpTransport.RESPONSE_MAX_CHARS

    /** 回應 `jsonrpc` 必須嚴格等於此值（JSON-RPC 2.0；缺席／其他版本一律拒）。 */
    private const val JSONRPC_VERSION = "2.0"

    /**
     * 協議／伺服器端 JSON-RPC 錯誤碼（parse error／invalid request／method not found／
     * internal error）：表示協議不可互通或端點配置錯誤，[parseCall] 拋 [McpProtocol]
     * （[McpServer] 映射 PROTOCOL + 計熔斷）。`-32602`（invalid params）與
     * （此集合及 [SERVER_ERROR_RANGE] 之外的）未知碼屬工具級中性（[McpToolFailure]），
     * 見 [parseCall]。
     */
    private val PROTOCOL_ERROR_CODES = setOf(-32700L, -32600L, -32601L, -32603L)

    /**
     * JSON-RPC 2.0 §5.1 保留的 implementation-defined server error 範圍
     * （−32099..−32000）：伺服器端失敗（後端不可用等），非工具業務錯。
     * [parseCall] 與協議碼同待遇拋 [McpProtocol]（PROTOCOL + 計熔斷）——
     * 否則持續回此類錯誤的伺服器會被當中性業務錯、熔斷永不開路（外部 review「前輪 P2-1」）。
     * 與 [parseListPage] 對 list 端「error 包一律 PROTOCOL（含 −320xx）」一致。
     * 未來子集若納入 resources 且需區分 MCP 自用碼（如 −32002 resource not found），
     * 須先逐碼定義中性例外並補契約測試——目前 tools 子集無此通道。
     */
    private val SERVER_ERROR_RANGE = -32099L..-32000L

    /**
     * 已支援 ContentBlock 型別的必填欄位（MCP ContentBlock schema 子集；
     * second＝true 表示必須為字串、false 表示必須為 object）：
     * `text`→`text`；`image`／`audio`→`data`+`mimeType`；`resource`→`resource`
     * （本表只管**外層** `resource` 是 object；**內層**再經 [requireResourceContents]
     * 二段驗證：`uri` 非空字串 ＋ `text`/`blob` 至少一個存在且為字串——外部 review
     * 「2026-10-19 re-review P2-1」fail-open，缺 text/blob 的 resource 無可呈現內容，不得當成功）；
     * `resource_link`→`uri`+`name`。未知型別（含空字串）一律 [McpProtocol]
     * ——本子集 fail-closed，不把「沒有實際內容的型別」當成功（外部 review「前輪 P2-2」）；
     * `type` 之外的未知鍵仍寬容。未來納入新型別時逐碼擴充此表並補契約測試。
     */
    private val CONTENT_REQUIRED_FIELDS: Map<String, List<Pair<String, Boolean>>> = mapOf(
        "text" to listOf("text" to true),
        "image" to listOf("data" to true, "mimeType" to true),
        "audio" to listOf("data" to true, "mimeType" to true),
        "resource" to listOf("resource" to false),
        "resource_link" to listOf("uri" to true, "name" to true),
    )

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
     * 要求前導空白 + `{` 或 `[`（頂層僅收 object/array——純量雖是 RFC 8259 合法
     * JSON text，但不屬本子集，一律 bad envelope；[parseList]/[parseCall] 再要求 object）、
     * 嚴格走訪、尾隨僅空白；每層重複 key（解碼後比對）拋 `mcp: duplicate key`；
     * 深度 > 64 拋 `mcp: too deep`；其餘形狀錯誤拋 `mcp: bad envelope`。
     *
     * 記憶體放大（RT-02，已知取捨）：raw-span DOM 對每個容器／字串節點保存
     * `json.substring(...)`（JVM 會複製字元），深度 d 的巢狀鏈最壞保留
     * O(body × depth) 字元——本檔上界約 64 × 64,000 ≈ 4.1M UTF-16 units 的
     * 暫態配置。上界由 [McpTransport.RESPONSE_MAX_CHARS]（64k）與 [MAX_DEPTH]
     * 固定，非無界；擴大 depth 或 body cap 前須重評。此為免依賴 parser 的
     * 已知放大取捨（見 THREAT_MODEL MCP 行），不改變解析語義。
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
                '"' -> return JStr(requirePairedSurrogates(sb), json.substring(start, p)) to p
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

    /**
     * surrogate 配對校驗（比 RFC 8259 嚴格，刻意 fail-closed）：`"\uD800"` 類孤立
     * surrogate escape 語法合法但語意無效——出站 [esc]／UTF-8 編碼會把它變形為
     * U+FFFD，送達伺服器的名稱將與列表不一致（工具識別錯位），故一律拒收；
     * 成對 escape（`😀`）逐 char append 後自然通過。
     * 規格文本尚未納入本 repo（見 [McpWire] 修訂立場），此收緊屬子集政策。
     */
    private fun requirePairedSurrogates(sb: StringBuilder): String {
        if (hasLoneSurrogate(sb)) throw McpProtocol("mcp: bad envelope")
        return sb.toString()
    }

    /** 是否含未配對 surrogate（孤立 high、孤立 low、high 後接非 low）。 */
    private fun hasLoneSurrogate(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                    i += 2
                }
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
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

    /**
     * 驗證 baseUrl 可形成有效的 http(s) endpoint（供 [McpServer] 在 vault read /
     * HTTP / breaker failure 之前 fail-closed 使用）。
     *
     * blank 或 malformed 一律 false：呼叫端據此回 UNAUTHORIZED（零 vault / 零 HTTP /
     * 零熔斷計數），不得讓壞地址落到傳輸層——否則 transport 會拋
     * IllegalArgumentException，被外層 catch 映射成 TRANSPORT 並計一次熔斷失敗
     * （配置錯誤會污染熔斷器）。
     *
     * 判定分四層（皆對「使用者寫的原始地址」判定，不是對 [endpoint] 的改寫結果）：
     * 1. `scheme://` 必須存在且為 http/https：無 scheme（`not-a-url`）、單斜線
     *    （`https:/host`）、非 http(s)（`ftp://host`）一律拒絕——OkHttp 的 parser
     *    對單斜線很寬鬆，會把 `https:/host` 讀成 host `host`；
     * 2. authority（`://` 與第一個 `/`、`?`、`#` 之間）不得為空、不得含空白：
     *    `http://` 根本沒有 host；
     * 3. OkHttp `toHttpUrlOrNull` 形狀檢查（IDN/punycode、host 字元、埠號範圍）；
     * 4. 真正上線的 [endpoint] 結果也必須可解析。
     *
     * 空白規則（刻意比 OkHttp 嚴，fail-closed）：authority 內任何位置出現空白
     * 一律拒絕——包含 userinfo（`https://user:pa ss@host/mcp`）。OkHttp 會把
     * 空白 percent-encode 成 `%20` 後照樣解析成功（於是 userinfo 裡的空白會
     * 靜默變成合法 token）；本檢查寧可誤拒也不放行（misconfiguration 早失敗，
     * 好過帶著一個被改寫過的憑證上線）。
     *
     * 刻意拒絕的「搶救家族」（salvage family）：`http://`、`https://`（無 host）、
     * 單斜線 `https:/host`、`https:///mcp` 這類 OkHttp 寬容解析器「猜得回」
     * 的形狀一律拒絕——它們多半是設定打錯（少一個斜線、多一個斜線、漏了 host），
     * 讓 OkHttp 猜一個 host 只會把設定錯誤變成對錯主機的請求。
     *
     * 方向性（differential）：`isValidBaseUrl` 絕不可放行一個
     * `endpoint().toHttpUrlOrNull()` 會拒絕的地址（放行 = 壞地址掉到傳輸層
     * → TRANSPORT + 計熔斷）；反之，`endpoint()` 補出的 `/mcp` 形式能被
     * OkHttp 解析是必要不充分條件（`http://` 補完成 `http:/mcp` 仍會被當成
     * host `mcp`——所以必須對「原始地址」判定）。
     *
     * 為何不只看 [endpoint]：endpoint() 對 bare origin 補 `/mcp`，而
     * `trimEnd('/')` 會把 `http://` 削成 `http:`，補完即成 `http:/mcp`——
     * OkHttp 會把它讀成 host `mcp`（合法 URL！），於是「沒有 host 的地址」被
     * 驗成有效，壞設定反而通過了 pre-flight。原始地址沒有這條補救路徑。
     *
     * 與 [requireSecureEndpoint] 的分工（兩者並存、不取代）：本函只答「形狀是否
     * 成立」（設定問題 → UNAUTHORIZED、零熔斷）；[requireSecureEndpoint] 答
     * 「傳輸是否允許」（明文非回環 → PROTOCOL）。後者更嚴（另拒 userinfo／backslash／
     * 單標籤 authority），於是形狀合法但傳輸不允許的地址仍由它擋下。
     */
    internal fun isValidBaseUrl(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return false
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        val authority = trimmed.substring(schemeEnd + 3)
        val hostEnd = authority.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val hostPart = if (hostEnd < 0) authority else authority.substring(0, hostEnd)
        if (hostPart.isEmpty() || hostPart.any { it.isWhitespace() }) return false
        val url = trimmed.toHttpUrlOrNull() ?: return false
        if (url.host.isEmpty()) return false
        if (url.scheme != "http" && url.scheme != "https") return false
        endpoint(trimmed).toHttpUrlOrNull() ?: return false
        return true
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
     * - 經 [endpoint] 正規化後**只認 scheme 分隔點**：在**第一個** `:` 之後必須緊接
     *   `//`（`<scheme>://…`），否則拋 `mcp: bad endpoint`。刻意不用
     *   `indexOf("://")`——path／query／fragment 內的 `://` 不是 scheme 分隔點
     *   （R2-01／S2-01：`https:/evil-host?x=://trusted.example` 曾以 attacker
     *   可控的尾段當 authority 檢查，實際卻連線到 `evil-host`；單斜線／
     *   scheme-only 形經 [endpoint] 正規化後亦不得 fail-open 成單標籤 host `mcp`）；
     * - 由該分隔點起取**原始 authority**（第一個 `/`／`?`／`#` 之前；刻意不在 `\`
     *   停下——WHATWG 以反斜線作 authority/path 分隔，必須留著檢查，否則
     *   `https://evil.example\@trusted.example/mcp` 會以可見的 `@trusted.example`
     *   尾段通過 userinfo 檢查而實際連線到 `evil.example`）；
     * - 原始 authority 含 `\` 即拋 `mcp: bad endpoint`（**先於 `@` 判定**，避免
     *   對 `\@` 形誤報 userinfo）；
     * - 原始 authority 含 `@`（含空 userinfo `https://@host`／`https://:@host`）即拋
     *   `mcp: userinfo not allowed in endpoint`——顯示主機與實際連線主機可被
     *   分離（貼錯／釣魚網址形），Bearer 與 arguments 會送達與使用者認知不同的主機；
     * - 原始 authority 為空（`https://`／`https:/`／`https:`／`https:///`／
     *   `https://?x=1`／`https://#f`，或任何未以 `<scheme>://` 開頭者）、或解析後
     *   host 為空，一律拋 `mcp: bad endpoint`；
     * - 經 [endpoint] 正規化後必須能解析為 http(s) URL，否則拋 `mcp: bad endpoint`；
     * - 解析後 URL 亦不得含 userinfo（防禦深度，與原始 authority 檢查同訊息）；
     * - 僅放行 `https`，以及回環 `http`（`localhost`／`127.0.0.1`／`[::1]`，
     *   供 MockWebServer／本機開發；其餘 `http` 一律拋 `mcp: insecure endpoint`）。
     * 回傳正規化後 endpoint（可直接發送）。
     */
    fun requireSecureEndpoint(baseUrl: String): String {
        if (baseUrl.isBlank()) throw IllegalArgumentException("mcp: blank endpoint")
        val url = endpoint(baseUrl)
        // 原始 authority 必須由 **scheme 分隔點**界定（R2-01／S2-01）：第一個 `:`
        // 之後緊接 `//`，否則 path／query／fragment 內的 `://` 會被誤認為 scheme
        // 分隔點（`https:/evil-host?x=://trusted.example` 實際連線到 `evil-host`）。
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0 || !url.startsWith("//", schemeEnd + 1)) {
            throw IllegalArgumentException("mcp: bad endpoint")
        }
        val authStart = schemeEnd + 3
        // 原始 authority：分隔點之後、第一個 `/`／`?`／`#` 之前。不在 `\` 停下——
        // 反斜線是 WHATWG authority/path 分隔符，必須留在 authority 內檢查（F-01）。
        var authEnd = url.length
        for (i in authStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                authEnd = i
                break
            }
        }
        val rawAuthority = url.substring(authStart, authEnd)
        // 空 authority（scheme-only／`https:///`／`https://?x=1` 等）fail-closed（F-02）。
        if (rawAuthority.isEmpty()) throw IllegalArgumentException("mcp: bad endpoint")
        // authority 內反斜線：顯示主機與實際連線主機可被分離（F-01）。
        // **先於 `@` 判定**：`https://evil.example\@trusted.example/mcp` 內沒有
        // userinfo，報 `mcp: bad endpoint` 才不會誤導（R2-03）。
        if ('\\' in rawAuthority) throw IllegalArgumentException("mcp: bad endpoint")
        // 任何 `@`（含空 userinfo）一律拒（F-01）。
        if ('@' in rawAuthority) {
            throw IllegalArgumentException("mcp: userinfo not allowed in endpoint")
        }
        val parsed = try {
            url.toHttpUrlOrNull()
        } catch (_: Exception) {
            null
        } ?: throw IllegalArgumentException("mcp: bad endpoint")
        // 空 parsed host 不得 fail-open（舊路徑曾把 scheme-only 輸入解析成 host `mcp`）。
        if (parsed.host.isEmpty()) throw IllegalArgumentException("mcp: bad endpoint")
        // 防禦深度：原始 authority 已拒 `@`；解析後 userinfo 非空亦同訊息。
        if (parsed.username.isNotEmpty() || !parsed.password.isNullOrEmpty()) {
            throw IllegalArgumentException("mcp: userinfo not allowed in endpoint")
        }
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
     * - `toolName` 含 C0 控制字元（`< 0x20`）或 DEL（`0x7F`）即拋（靜態訊息
     *   `mcp: tool name has control chars`，與入站 [parseListPage] 對稱——Stage3
     *   A-01：CR/LF 可偽造稽核列、C0/ESC 可干擾終端；toolName 是**識別欄位**，
     *   一律拒而非轉義。對照 `argumentsJson`：那是**遠端資料通道**，控制字元
     *   屬合法 payload 內容，經嚴格 JSON parser 驗證後原樣保留（不經 [esc]），
     *   兩者政策差異在此）；
     * - `toolName` 含孤立 surrogate 即拋（[esc] 只轉義控制字元與引號，不修 surrogate；
     *   出站後 UTF-8 編碼會把孤立 surrogate 變 U+FFFD——送達伺服器的名與列表名
     *   不再一致，工具識別錯位，故發送前 fail-closed）；
     *   （名形狀驗證順序：空白 → 超限 → C0/DEL → 孤立 surrogate，與下方程式一致）；
     * - `argumentsJson` 必須是合法 JSON object 文本（空字串視為 `{}`），
     *   非 object（`null`／陣列／畸形）即拋，不發送；
     *   字元數超 [ARGUMENTS_MAX_CHARS] 即拋（訊息明指 arguments，非 response）；
     * - `toolName` 經 [esc] 轉義：C0/DEL 已先在此拒（其 [esc] C0 分支屬防禦深度，
     *   見該函 KDoc），實際會走到的只剩引號／反斜線等 JSON 必要編碼。
     */
    fun callBody(toolName: String, argumentsJson: String, id: Long = CALL_ID): String {
        require(toolName.isNotBlank()) { "mcp: blank tool name" }
        require(toolName.length <= TOOL_NAME_MAX_CHARS) {
            "mcp: tool name exceeds $TOOL_NAME_MAX_CHARS chars"
        }
        // C0/DEL 拒（與入站 parseListPage 對稱，A-01）：識別欄位不轉義、一律拒；
        // 靜態訊息（不插值名——名含伺服器/呼叫端字串，不回顯進錯誤面）。
        if (toolName.any { it.code < 0x20 || it.code == 0x7F }) {
            throw IllegalArgumentException("mcp: tool name has control chars")
        }
        if (hasLoneSurrogate(toolName)) {
            throw IllegalArgumentException("mcp: tool name has lone surrogate")
        }
        val args = argumentsJson.trim().ifEmpty { "{}" }
        if (args.length > ARGUMENTS_MAX_CHARS) {
            throw IllegalArgumentException("mcp: arguments exceed $ARGUMENTS_MAX_CHARS chars")
        }
        // fail-closed：arguments 必須是合法 JSON object 文本（上層不再另行保證，
        // 此處直接驗證；非法即拋，呼叫方在發送前擋下，不送畸形 JSON-RPC）。
        // P2 slice 硬化：惡形／非 object 的 arguments 與其他惡形包**同型**
        // （[McpProtocol] `mcp: bad envelope`）——[McpProtocol] 是
        // [IllegalArgumentException] 子類，既有的 `catch (IllegalArgumentException)`
        // 契約不變，而 [McpServer.callTool] 據此映射為 PROTOCOL（零 vault、
        // 零發送、零熔斷——client-arg 錯不得被誤標成傳輸失敗）。
        val parsed = try {
            parseStrictDoc(args)
        } catch (_: McpProtocol) {
            throw McpProtocol("mcp: bad envelope")
        }
        if (parsed !is JObj) throw McpProtocol("mcp: bad envelope")
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
     * **生產禁用（test-only，B-07 後無預設可誤用）**：此 API 同時失去
     * hasMorePages（PARTIAL）與 [expectedId] 強制便利——`expectedId` 必須顯式傳
     * （本入口慣例傳 `null`＝只驗 id 存在與型別）；生產呼叫方一律用
     * [parseListPage]＋[LIST_ID]（`McpServer.listTools` 是唯一生產 list 呼叫點）。
     * 可見性：本物件 `internal`，此函在模組外不可見；包內生產碼零呼叫
     * （人工 grep 維護；zero-wiring gate 不涵蓋此內部 test-only 用法），
     * KDoc 與簽名雙重宣示 test-only。
     *
     * @param expectedId **無預設值（B-07）**——顯式 `null`（測試）或顯式 id 比對值。
     */
    fun parseList(serverId: String, json: String, expectedId: Long?): List<McpTool> =
        parseListPage(serverId, json, expectedId).tools

    /**
     * 解析 tools/list 單頁；惡形輸入拋 IllegalArgumentException（呼叫方映射為 PROTOCOL）。
     *
     * 經 [parseStrictDoc] 嚴格走訪全文（截斷/尾隨 garbage/缺逗號/重複鍵/非法字面量/
     * 非法 escape/超深一律 [McpProtocol]）；再經 [requireResponseEnvelope]
     * 驗 `jsonrpc=="2.0"`、`id` 存在／相符、request-shaped 拒；
     * tools/list 回應不得是 error 包：**error 鍵存在即拒**（含 `error:null`、
     * 含 result+error 矛盾包；不分 code：協議碼、`-32602`、未知碼、
     * 保留 server 範圍（−32099..−32000）一律 [McpProtocol] PROTOCOL + 計熔斷——
     * list 無業務錯通道，
     * 不當空列表、不採納）；只認頂層 `result` 包裝，
     * 再從 `result` 內取 `tools` 陣列；層級一律經 DOM 直接成員查找，
     * 巢狀同名鍵不得遮蔽外層。inputSchema 為 MCP wire **必備**欄位（遠端契約，
     * fail-closed）：缺席 → `mcp: missing inputSchema`（不憑空合成預設——外部 review
     * 「2026-10-19 re-review P2-2」fail-open）；非 object（null／字串／數字／陣列／布林）→ `mcp: bad inputSchema`；
     * object 但根 `type` 缺席／非字串／值非 `"object"` → `mcp: inputSchema root type
     * must be object`（**只驗根 type**，不做完整 JSON Schema meta-validation）；
     * 超 [INPUT_SCHEMA_MAX_CHARS] 拒絕不截斷（驗證順序：先形狀——缺席/非 object/
     * 根 type——後長度）。`McpScope.toToolDef`／[McpTool.inputSchema] 的
     * `{"type":"object"}` 預設參數是**本地建構**預設值（直呼叫／測試用），
     * 與遠端契約無關，不會出現在任何解析結果；
     * name 超 [TOOL_NAME_MAX_CHARS] 一律拒（與出站同政策，見該常數 KDoc）、
     * 以 [RESERVED_TOOL_PREFIX]（`mcp.`＝[McpScope.SWITCH_PREFIX]，單一真相）開頭的
     * **保留前綴名整頁拒**（靜態訊息 `mcp: tool name uses reserved prefix`，
     * 與既有「超限名整頁拒」同政策——保留前綴兩端同政策：**入站整頁拒、
     * 出站拒用**（`McpScope.requireBareToolName`），否則「列出但不可用」＋
     * 未來接線 `map { McpScope.toToolDef(...) }` 拋未映射 IAE 打斷整輪；
     * 大小寫敏感：`MCP.x` 照收，正向已測）、
     * 空白／空名**整頁拒**（靜態訊息 `mcp: tool name is blank`，`isBlank()` 涵蓋全形
     * 空白／NBSP 等 Unicode 空白；與保留前綴同政策——空白名在所有 [McpScope] 授權
     * 入口都被 [McpScope.requireBareToolName] 拒，照收同樣會讓未來接線
     * `map { McpScope.toToolDef(...) }` 拋未映射 IAE 打斷整輪；名**內含**空格
     * （如 `my tool`）不受影響，正向已測）、
     * **同頁重複名整頁拒**（靜態訊息 `mcp: duplicate tool name`，Stage3 B-04：
     * `enabledTools`／[McpScope] 授權路徑**以名為鍵**（精確比對、缺鍵預設啟用），
     * 同頁重複名會讓「哪一支工具被停用／授權」失去唯一對象，且
     * [McpListResult.auditMap] 的 `toolCount`／`tools` 名清單對重複名失真——
     * 與超限名／保留前綴／空白名同政策整頁 fail-closed 拒，不靜默略過單支、
     * 不採納首支；重複判定在單頁內（**跨頁不適用**：本子集不跟隨
     * `nextCursor` 分頁，見 [McpWire] 修訂立場——每頁各自獨立驗證）、
     * 名形狀驗證順序：超限 → 控制字元 → 空白 → 保留前綴 → 同頁重複，與上方
     * KDoc 文件化順序一致；description **缺席 → 空字串，出現即須字串**
     * （null/數字/物件/布林 → [McpProtocol] `mcp: bad description`，B-05
     * fail-closed——型別寬容曾讓伺服器以錯型別靜默降級為空描述，與
     * `nextCursor`／`inputSchema` 的型別 fail-closed 同政策——唯 `nextCursor`
     * 例外：顯式 null 同缺席（→ false，非整頁拒，見下段）、
     * 超 [DESCRIPTION_MAX_CHARS] 截斷（截斷 vs 拒絕的
     * 政策區別見該常數 KDoc）。
     *
     * `nextCursor`（外部 review #3）：非空字串 → [McpListPage.hasMorePages]=true
     * （呼叫方映射 PARTIAL，不回 OK 假完整）；缺席／null／空字串 → false；
     * 其他型別（數字／object／布林）→ [McpProtocol] `mcp: bad nextCursor`。
     * 僅認 `result` 內的 `nextCursor`（頂層同名鍵視為未知鍵忽略，與未知鍵政策一致）；
     * 驗證順序：`tools` 陣列型別 → `nextCursor` → `tools` 陣列項目內容
     * （cursor 異常與 tools 項目異常同時存在時，`mcp: bad nextCursor` 勝出）。
     *
     * @param expectedId **無預設值（B-07 顯式化）**：生產（[McpServer.listTools]）
     *   一律傳 [LIST_ID]（數值比對回應 id，錯 id 回應 fail-closed 不採納）；
     *   顯式 `null` 只驗 id 存在與型別——**僅限測試直呼叫**，放寬不再是預設。
     */
    fun parseListPage(serverId: String, json: String, expectedId: Long?): McpListPage {
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
            // 同頁重複名追蹤（B-04）：以名為鍵的 enabledTools／McpScope 授權語意
            // 需要「頁內名唯一」——重複名整頁拒，不靜默略過、不採納首支。
            val seenNames = HashSet<String>()
            toolsVal.items.map { item ->
                if (item !is JObj) throw McpProtocol("mcp: bad tools item")
                val nameVal = item.find("name")
                if (nameVal !is JStr) throw IllegalArgumentException("mcp: tool without name")
                // 超限名整頁拒（與出站 callBody 同政策）：截斷後名會被當成另一支工具
                // 送達伺服器，且 enabledTools／McpScope 以名為鍵——前綴碰撞即授權錯位。
                if (nameVal.v.length > TOOL_NAME_MAX_CHARS) {
                    throw McpProtocol("mcp: tool name exceeds $TOOL_NAME_MAX_CHARS chars")
                }
                // C0/DEL 控制字元一律拒（fail-closed）：名流進 auditMap（tools 名清單）與
                // 以名為鍵的 enabledTools／McpScope——CR/LF 可偽造稽核列、C0/ESC 可干擾終端。
                if (nameVal.v.any { it.code < 0x20 || it.code == 0x7F }) {
                    throw McpProtocol("mcp: tool name has control chars")
                }
                // 空白／空名整頁拒（與保留前綴同政策；名形狀驗證順序：超限 → 控制字元 →
                // 空白 → 保留前綴 → 同頁重複，與上方 KDoc 文件化順序一致）：空白名在所有
                // McpScope 授權入口都被 requireBareToolName 拒（isNotBlank），照收會
                // 「列出但不可用」且讓未來接線 `map { McpScope.toToolDef(...) }` 拋
                // 未映射 IAE 打斷整輪——與保留前綴同災難形（re-review F-S2-02）。
                // isBlank() 涵蓋全形空白/NBSP 等 Unicode 空白；名「內含」空格不受影響。
                if (nameVal.v.isBlank()) {
                    throw McpProtocol("mcp: tool name is blank")
                }
                // 保留前綴整頁拒（名形狀驗證順序：超限 → 控制字元 → 空白 → 保留前綴 →
                // 同頁重複，與上方 KDoc 文件化順序一致）：`mcp.`（＝McpScope.SWITCH_PREFIX，單一真相經
                // RESERVED_TOOL_PREFIX 引用）是開關鍵/ToolDef.name 的形狀，不是遠端 bare 名。
                // 照收會「列出但不可用」（所有 McpScope 授權入口都拒此前綴），且未來接線
                // `map { McpScope.toToolDef(...) }` 會拋未映射 IAE 打斷整輪——與超限名同政策：
                // 整頁 fail-closed 拒，不靜默略過單支。
                if (nameVal.v.startsWith(RESERVED_TOOL_PREFIX)) {
                    throw McpProtocol("mcp: tool name uses reserved prefix")
                }
                // 同頁重複名整頁拒（B-04，驗證順序末位）：靜態訊息、不回顯名——
                // enabledTools／McpScope 以名為鍵（精確比對、缺鍵預設啟用），重複名
                // 使「停用／授權哪一支」失去唯一對象，auditMap toolCount 失真。
                if (!seenNames.add(nameVal.v)) {
                    throw McpProtocol("mcp: duplicate tool name")
                }
                val descVal = item.find("description")
                // B-05：description 缺席 → 空字串（欄位本身可選）；出現即須字串——
                // null/數字/物件/布林一律整頁拒（與 inputSchema 型別 fail-closed
                // 同政策，不靜默降級為空描述；nextCursor 例外：顯式 JNull 同缺席
                // ——其政策是 null/缺席 → hasMorePages=false，非整頁拒）。
                if (descVal != null && descVal !is JStr) throw McpProtocol("mcp: bad description")
                val desc = if (descVal is JStr) descVal.v else ""
                // inputSchema 為 MCP wire 必備欄位：缺席不得憑空合成預設
                //（外部 review「2026-10-19 re-review P2-2」fail-open——舊碼缺 schema 合成 {"type":"object"}
                // 讓不互通的伺服器回 OK 並重置熔斷）。
                val schemaVal = item.find("inputSchema")
                    ?: throw McpProtocol("mcp: missing inputSchema")
                // 非 object（字串/數字/陣列/布林/null）維持既有 bad inputSchema。
                if (schemaVal !is JObj) throw McpProtocol("mcp: bad inputSchema")
                // 根 type 必須恰為字串 "object"（缺 type／非字串／其他值一律拒；
                // 只驗根 type，不做完整 JSON Schema meta-validation）。
                val rootType = schemaVal.find("type")
                if (rootType !is JStr || rootType.v != "object") {
                    throw McpProtocol("mcp: inputSchema root type must be object")
                }
                val schema = schemaVal.raw
                // 形狀驗證先於長度（與 KDoc 文件化順序一致）；超限拒絕不截斷。
                if (schema.length > INPUT_SCHEMA_MAX_CHARS) {
                    throw McpProtocol("mcp: inputSchema exceeds $INPUT_SCHEMA_MAX_CHARS chars")
                }
                McpTool(
                    serverId = serverId,
                    name = nameVal.v,
                    description = truncateUnits(desc, DESCRIPTION_MAX_CHARS),
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
     * `code` 必須是整數（缺 code／字串 code 一律 `mcp: bad error`，不靜默當業務錯）、
     * `message` 必備且須為字串（JSON-RPC 2.0 §5.1；缺／非字串同樣 `mcp: bad error`
     * ——形狀不完整不得默默當業務錯，否則畸形 error 永遠中性、不計熔斷，外部 review P3-1）。
     *
     * error code 分類（外部 review #4＋本輪 server range 修正）：
     * - `-32700`／`-32600`／`-32601`／`-32603`（協議／伺服器端錯，見 [PROTOCOL_ERROR_CODES]）
     *   及保留 server 範圍 −32099..−32000（見 [SERVER_ERROR_RANGE]）
     *   → 拋 [McpProtocol]（[McpServer] 映射 PROTOCOL + 計熔斷，breaker 能得知不互通）；
     * - `-32602`（invalid params）與範圍外未知碼 → 拋 [McpToolFailure]（工具級中性；
     *   message 經 [escapeControlUnits] 消毒——C0/DEL、C1、U+2028/2029、bidi 控制
     *   （U+061C／U+200E／U+200F／U+202A..U+202E／U+2066..U+2069）與零寬
     *   （U+200B..U+200D／U+2060／U+FEFF）換成可見 `\\uXXXX` 轉義、上限
     *   [ERROR_MESSAGE_MAX_UNITS] UTF-16 code units
     *   （轉義/surrogate 邊界截斷，不切 surrogate pair、不裸露上述控制/格式字元；
     *   其餘 Cf 仍原樣，屬名混淆殘餘 S2-03，邊界見該函 KDoc）、
     *   空／純空白 message 填靜態 `jsonrpc error <code>`（B-03：伺服器不得以空
     *   message 讓錯誤不可辨識；code 為本地解析整數，插值非伺服器自由文字；
     *   blank 判同時覆蓋**原始**與**消毒後**——預算截斷可把「<200 空白>x」切成
     *   轉義後仍純空白，S3-R01），由 [McpServer.callTool]
     *   透傳至 [McpCallResult.errorMessage]，不進 auditMap）。
     *
     * `code` 必須是整數字面量（不含指數／小數／溢位；否則 `mcp: bad error`）。
     * 注意不對稱：[parseList] 對任何 error 包（含 `-32602`/未知碼）一律
     * [McpProtocol]（list 無業務錯通道，協議碼與保留 server 範圍碼同等計熔斷）。
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
     *   `structuredContent` 的寬容 null 分歧），且該型別的必填欄位齊備
     *   （[CONTENT_REQUIRED_FIELDS]：缺欄位 → `mcp: content missing <type>.<field>`、
     *   型別錯 → `mcp: content bad <type>.<field>`、未知型別 → `mcp: unknown content type`，
     *   皆 [McpProtocol] PROTOCOL + 計熔斷——不得把無實際內容的畸形 block 當成功，
     *   外部 review「前輪 P2-2」）；`type` 之外的未知鍵仍寬容；
     * - `resource` 型別另有**內層二段驗證**（[requireResourceContents]，外部 review
     *   「2026-10-19 re-review P2-1」fail-open）：`resource` 物件必須含**非空字串** `uri`，且 `text`/`blob`
     *   **至少一個存在且為字串**（兩者並存時兩個都必須是字串，否則拒——不完整
     *   resource 無可呈現內容，不得原文透傳回 OK）；任一不符 → 靜態訊息
     *   `mcp: bad resource contents`（不插值伺服器文字）＋ PROTOCOL + 計熔斷；
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
     * @param expectedId **無預設值（B-07 顯式化）**：生產（[McpServer.callTool]）
     *   一律傳 [CALL_ID]（數值比對回應 id）；顯式 `null` 只驗 id 存在與型別——
     *   **僅限測試直呼叫**，放寬不再是預設。
     */
    fun parseCall(json: String, expectedId: Long?): Pair<String?, Boolean> {
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
            // message 必備且須為字串（JSON-RPC 2.0 §5.1）：缺／非字串屬形狀不完整，
            // 不得默默回退預設字串當業務錯（否則畸形 error 永遠中性、不計熔斷）。
            val msgVal = errVal.find("message")
            if (msgVal !is JStr) throw McpProtocol("mcp: bad error")
            if (code in PROTOCOL_ERROR_CODES || code in SERVER_ERROR_RANGE) {
                throw McpProtocol("mcp: jsonrpc error $code")
            }
            // B-03（空訊息靜態替代）：空／純空白 message 不得原樣透傳——伺服器刻意
            // 送空 message 會讓上層錯誤不可辨識。code 已是**本地解析的整數**
            //（非伺服器自由文字），插值安全；純數字插值外皆為靜態字面。
            // 原文層 blank 判先行且涵蓋 C0 空白（TAB 等）：這類輸入直接走靜態
            // 替代、不消毒（B-03 語意「空白＝無可辨識訊息」，不產出 \u0009 之類
            // 的轉義空白充當訊息）。
            // A-02（控制/格式字元消毒）：非空 message 經 escapeControlUnits 把 C0/DEL、
            // C1、LS/PS、bidi 控制與零寬（U+200B..U+200D／U+2060／U+FEFF）換成可見
            // \uXXXX 轉義（≤ERROR_MESSAGE_MAX_UNITS units、轉義邊界截斷、
            // surrogate 安全），不裸露上述字元、預算內不靜默丟字；其餘 Cf 仍原樣
            //（名混淆殘餘 S2-03，非換行/重排通道）。
            // S3-R01（post-escape blank 防繞過）：200 units 預算截斷可能把
            //「<200 個空白>x」切成**轉義後仍純空白**的字串——對 escape 結果
            // 再判一次 blank，仍補同一靜態替代（兩分支各消毒至多一次，正常
            // 非空白路徑語意不變：sanitize＋截斷、無 fallback）。
            val message = if (msgVal.v.isBlank()) {
                "jsonrpc error $code"
            } else {
                escapeControlUnits(msgVal.v, ERROR_MESSAGE_MAX_UNITS)
                    .ifBlank { "jsonrpc error $code" }
            }
            throw McpToolFailure(message)
        }
        if (resultVal == null || resultVal is JNull) throw IllegalArgumentException("mcp: missing result")
        if (resultVal !is JObj) throw IllegalArgumentException("mcp: bad result")
        val contentVal = resultVal.find("content")
        if (contentVal != null && contentVal !is JArr) {
            throw McpProtocol("mcp: bad content")
        }
        // 元素層驗證（fail-closed）：每個 content 元素必須是含字串 type 的 object，
        // 且該型別的必填欄位齊備（[CONTENT_REQUIRED_FIELDS]）；[1]/["x"]/缺 type、
        // 缺必填欄位、未知型別 → 拒，不得原文透傳回 OK（更不得由 onSuccess 重置熔斷）。
        if (contentVal is JArr) {
            for (item in contentVal.items) {
                val obj = item as? JObj ?: throw McpProtocol("mcp: bad content item")
                val typeVal = obj.find("type")
                if (typeVal !is JStr) throw McpProtocol("mcp: bad content item")
                val required = CONTENT_REQUIRED_FIELDS[typeVal.v]
                    // 訊息刻意靜態：type 欄位是伺服器字串，不得回顯進錯誤訊息
                    //（回顯會讓伺服器控制的文字出現在上層錯誤呈現／稽核面）。
                    ?: throw McpProtocol("mcp: unknown content type")
                for ((field, mustBeString) in required) {
                    val v = obj.find(field)
                    if (v == null || v is JNull) {
                        throw McpProtocol("mcp: content missing ${typeVal.v}.$field")
                    }
                    val shapeOk = if (mustBeString) v is JStr else v is JObj
                    if (!shapeOk) throw McpProtocol("mcp: content bad ${typeVal.v}.$field")
                }
                // resource 二段驗證（外部 review「2026-10-19 re-review P2-1」fail-open）：上表已保證外層
                // `resource` 是 object，內層再驗 uri 非空字串＋text/blob 至少一個
                // 字串；不完整 resource 無可呈現內容，不得原文透傳回 OK（更不得由
                // onSuccess 重置熔斷）——[requireResourceContents]。
                if (typeVal.v == "resource") {
                    requireResourceContents(obj.find("resource"))
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

    /**
     * `resource` ContentBlock **內層**二段驗證（外部 review「2026-10-19 re-review P2-1」fail-open）：
     * - `resource` 必須是 object（外層 [CONTENT_REQUIRED_FIELDS] 已驗，此處防禦性再擋）；
     * - `uri` 必須是**非空**字串（缺／null／非字串／空字串皆拒）；
     *   `uri` 只拒空字串、不 trim（單一空白字串照收；與 `nextCursor` 政策一致——
     *   同樣只以 `isEmpty()` 區分，不做 isBlank 歸一化）；
     * - `text` 與 `blob` **至少一個存在且為字串**；兩者並存時兩個都必須是字串
     *   （一正一錯即拒，不得只驗存在的那個）；`null` 不視為存在（可選鍵寬容
     *   不適用於「內容載體」——null 內容＝無內容，fail-closed）；
     *   **mixed null 與 null-only 同拒**（`text:null`＋`blob:"AA=="` 一併拒——
     *   null 不視為缺席載體，不得被另一個合法載體掩蓋）。
     *
     * 任一不符拋 [McpProtocol]（[McpServer] 映射 PROTOCOL + 計熔斷）。
     * 訊息刻意靜態（`mcp: bad resource contents`）：不逐項區分缺哪个欄位，
     * 亦不插值任何伺服器字串——避免伺服器控制的文字進入上層錯誤呈現／稽核面。
     * 不做完整 MCP Resource 內容 meta-validation（本子集只驗此三形狀要件）。
     */
    private fun requireResourceContents(inner: JVal?) {
        val obj = inner as? JObj ?: throw McpProtocol("mcp: bad resource contents")
        val uri = obj.find("uri")
        if (uri !is JStr || uri.v.isEmpty()) {
            throw McpProtocol("mcp: bad resource contents")
        }
        val text = obj.find("text")
        val blob = obj.find("blob")
        if (text != null && text !is JStr) throw McpProtocol("mcp: bad resource contents")
        if (blob != null && blob !is JStr) throw McpProtocol("mcp: bad resource contents")
        if (text == null && blob == null) throw McpProtocol("mcp: bad resource contents")
    }

    class McpToolFailure(message: String) : IllegalArgumentException(message)

    /**
     * 協議形狀錯誤（envelope／error 形狀、inputSchema 超限、超長工具名等）：
     * [McpServer] 映射為 PROTOCOL + 計熔斷。
     * 注意 `mcp: missing result`／`mcp: bad result`／`mcp: bad result.tools`／
     * `mcp: tool without name` 四類結果形狀錯刻意拋一般 [IllegalArgumentException]
     * （與本類在 [McpServer] 同映射，行為無差異；型別區分由 McpWireD1Test 釘住）。
     */
    class McpProtocol(message: String) : IllegalArgumentException(message)

    /**
     * UTF-16 code-unit 截斷（[description][DESCRIPTION_MAX_CHARS] 500——
     * name 不截斷，超限即拒；errorMessage 的顯示上限改走 [escapeControlUnits]，
     * 內建預算與 surrogate 邊界截斷）：
     * 若截斷點落在 surrogate pair 中間，去掉尾端孤立 high surrogate——
     * 避免孤立 surrogate 序列化成 U+FFFD／'?'（出站名錯字、渲染亂碼）。
     */
    internal fun truncateUnits(s: String, maxUnits: Int): String {
        // 非正預算（RT-05）：`take(0).last()` 會拋 NoSuchElementException（非 IAE），
        // 空預算語意就是空字串——先短路，與空輸入同結果。
        if (maxUnits <= 0) return ""
        if (s.length <= maxUnits) return s
        val cut = s.take(maxUnits)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /**
     * 顯示面需轉義的字元集合（[escapeControlUnits] 單一真相）：C0、DEL、C1、
     * 行/段落分隔、bidi 控制/isolate、零寬（U+200B–200D／U+2060／U+FEFF）。
     * 成對 surrogate 不在此列（於 [escapeControlUnits] 另以 pair 分支原樣保留）。
     * **非全部 `Cf`**：其餘（U+00AD、U+180E、U+2061–2064、U+206A–206F、
     * U+FFF9–FFFB、tag U+E0000–E007F 等）仍原樣，屬名混淆殘餘（S2-03）。
     */
    private fun needsDisplayEscape(c: Char): Boolean = when (c.code) {
        in 0x00..0x1F, 0x7F, in 0x80..0x9F -> true
        0x061C, 0x200E, 0x200F, in 0x202A..0x202E, in 0x2066..0x2069 -> true
        0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF -> true
        0x2028, 0x2029 -> true
        else -> false
    }

    /**
     * 顯示消毒（Stage3 A-02 errorMessage＋A-01 稽核面，單一真相；PR #93 F-03 擴大）：
     * 下列字元逐一替換為**可見的六字元字面** `\\uXXXX`（小寫 hex，與 [esc] 的 C0
     * 分支同格式）：
     * - C0（U+0000..U+001F）與 DEL（U+007F）；
     * - **孤立 surrogate**（未成對的 high/low，S3-D02——出站 [callBody] 對孤立
     *   surrogate 亦 fail-closed 拒，稽核面不得原樣放行讓日誌損壞編碼）；
     * - C1 控制字元（U+0080..U+009F，含 U+0085 NEL——部分行導向工具視為換行）；
     * - 行／段落分隔 U+2028／U+2029（JSONL/日誌工具常視為換行）；
     * - bidi 控制／isolate（U+061C、U+200E、U+200F、U+202A..U+202E、
     *   U+2066..U+2069——視覺重排／Trojan Source 類偽裝）；
     * - 零寬（U+200B、U+200C、U+200D、U+2060、U+FEFF——名混淆）。
     * 其餘字元（含**成對** surrogate／emoji、CJK、重音字元、一般空白）原樣保留。
     * **殘餘（S2-03，誠實揭露）**：本集合**不是**全部 Unicode `Cf`——U+00AD、
     * U+180E、U+2061–U+2064、U+206A–U+206F、U+FFF9–U+FFFB、tag 字元
     * U+E0000–U+E007F（以及 U+115F／U+1160、U+2800、U+3164、U+FFA0 等非
     * `Cf` 不可見填充）仍原樣輸出。它們不能換行或重排顯示（line/bidi 集合
     * 已完整），屬**名混淆／稽核可讀性殘餘**，不是換行偽造或視覺重排通道。
     * 取捨（KDoc 文件化）：
     * - **預算內不靜默丟字**：轉義形式保留「是哪個控制字元」的診斷資訊（對照
     *   U+FFFD 替換——1:1 不展開但資訊全失，且 U+FFFD 本身語意是「解碼錯」
     *   誤導）；「預算外捨棄」（預算放不下完整六字元轉義或 surrogate pair 即止）
     *   屬截斷語意，非本條所稱的「靜默丟字」——邊界由
     *   `escapeTruncationStopsAtEscapeBoundaryWithoutHalfEscapeOrTail` pin；
     * - **不讓上述字元裸外顯**：CR/LF/NEL/LS/PS 偽造稽核列、ESC 干擾終端、
     *   bidi 視覺偽裝、零寬名混淆的通道關閉（轉義後是純可列印文字）；其餘 `Cf`
     *   殘餘見上，不屬換行/重排通道（S2-03）；
     * - **不得超過 [maxUnits] 上限**（UTF-16 code units）：轉義展開**計入預算**，
     *   截斷落在轉義邊界（放不下完整六字元即止，不留半截 `\u00`）與 surrogate
     *   邊界（pair 放不下即止，不切斷代理對；孤立 surrogate 經本函只會被轉義
     *   **消除**——本函不產生新的孤立 surrogate）。
     * 政策釘面：`bidiC1AndLineSeparatorsAreEscaped`、
     * `formatOnlyMessageEscapedWithoutStaticFallback`、
     * `displayEscapeCoversC1SeparatorsBidiAndZeroWidth`、
     * `loneSurrogateIsEscapedNotPassthrough`。
     *
     * 與 [esc] 的差異（兩者不可互換）：[esc] 是**出站 JSON 編碼**（toolName 經
     * [callBody] 已先拒 C0/DEL，其 C0 分支屬防禦深度）；本函是**入站伺服器文字／
     * 呼叫端字串的顯示消毒**（[McpCallResult.errorMessage]、`auditMap`/`toString`
     * 的 serverId 與 tool/tools 欄），不產出 JSON，只保證安全呈現。
     *
     * @param maxUnits 預算上限；預設不設限——[McpCallResult.auditMap] 的 tool 欄
     *   對**被拒**名只轉義不截斷（呼叫端自備字串，保留完整診斷；合法名恆 ≤
     *   [TOOL_NAME_MAX_CHARS] 且不含 C0，走此路徑原樣保留）。稽核面 tool 欄
     *   **無長度上限**是已知取捨（S3-D05）：被拒名只可能由本地呼叫端提供、
     *   不經 wire，超長名的記憶體放大風險以「呼叫端可控」抵償；在此截斷反而
     *   使稽核證據失真（截斷後無法還原原名）。
     */
    internal fun escapeControlUnits(s: String, maxUnits: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder(s.length)
        var budget = maxUnits
        var i = 0
        while (i < s.length && budget > 0) {
            val c = s[i]
            when {
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    if (budget < 2) return sb.toString()
                    sb.append(c).append(s[i + 1])
                    budget -= 2
                    i += 2
                }
                // S3-D02：走到這裡的 surrogate 必為孤立（成對 high+low 已於上分支
                // 以 i+=2 消耗——low 無前導 high、high 無後繼 low）：與控制/格式
                // 字元同待遇轉成 \uXXXX（計入預算）。成對 surrogate 原樣保留。
                c.isSurrogate() || needsDisplayEscape(c) -> {
                    val escText = "\\u%04x".format(c.code)
                    if (escText.length > budget) return sb.toString()
                    sb.append(escText)
                    budget -= escText.length
                    i++
                }
                else -> {
                    sb.append(c)
                    budget--
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * 出站 JSON 字串編碼（引號／反斜線／C0 短形式與 `\\u00XX`；DEL 原樣——
     * 它不是 JSON 控制字元）。
     *
     * 孤立 surrogate 亦轉義（P2 slice 硬化）：`\uD800` 等無伴的 high/low
     * 以 `\\uXXXX`（小寫 4-hex）輸出——UTF-8 encode 會把它換成 `?` (0x3F)，
     * 讓上線值與解析值不一致；**成對** surrogate 原樣輸出（不切開、不重組）。
     *
     * 注意（Stage3 A-01）：[callBody] 對 **toolName** 已先拒 C0/DEL（識別欄位
     * 不編碼、一律拒），本函的 C0 分支對 toolName 路徑是**防禦深度**
     *（單元測試直呼覆蓋）；`argumentsJson` 是遠端資料通道，控制字元屬合法
     * payload 內容，但那條路徑**不經本函**——arguments 以原文進 body、
     * 經 [parseStrictDoc] 驗證。本函不得用於放行本應拒絕的識別欄位。
     */
    internal fun esc(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                // Lone surrogate（無伴的 high/low）：OkHttp/okio 在 UTF-8 encode
                // 時把孤立 surrogate 靜默換成 `?` (0x3F)（**不是** U+FFFD），
                // 故上線值會與解析值不同——以確定性 \uXXXX 轉義（與 C0 分支
                // 同小寫 4-hex 約定）。**成對** surrogate 原樣通過（兩個原始
                // UTF-16 單元成對輸出，不重組 code point、不切開）。
                c.isSurrogate() -> {
                    val paired = c.isHighSurrogate() &&
                        i + 1 < raw.length && raw[i + 1].isLowSurrogate()
                    if (paired) {
                        sb.append(c).append(raw[i + 1])
                        i++
                    } else {
                        sb.append("\\u%04x".format(c.code))
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
