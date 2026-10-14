package dev.librepocket.mcp

/**
 * D03 JSON-RPC 2.0 編解碼（Streamable HTTP 最小子集，原創）。
 *
 * 線路形狀（本包自定最小 Sup 集合，不依賴 org.json 以保持純 JVM 可測）：
 * - tools/list 请求：`{"jsonrpc":"2.0","id":<n>,"method":"tools/list","params":{}}`
 * - tools/list 回應：`{"jsonrpc":"2.0","id":<n>,"result":{"tools":[
 *     {"name":"..","description":"..","inputSchema":{...}}, ... ]}}`
 * - tools/call 请求：`{"jsonrpc":"2.0","id":<n>,"method":"tools/call",
 *     "params":{"name":"..","arguments":{... raw json ...}}}`
 *   `argumentsJson` 由呼叫方保證為合法 JSON object 文本（上層已過 Schema 校驗）。
 * - tools/call 回應：`{"jsonrpc":"2.0","id":<n>,"result":{
 *     "content":[...],"isError":false}}`；或 `{"error":{"code":..,"message":".."}}`。
 *
 * 注意：Token 永不進 body（builder 不收 token 參數，傳輸層只放標頭）。
 */
internal object McpWire {

    /** 輸入 Schema 字元上限：超限拒絕（拋 [McpProtocol]），不靜默截斷。 */
    internal const val INPUT_SCHEMA_MAX_CHARS = 8_192

    /** JSON 字面量（true/false/null/number）校驗：非法字面量一律視為無效。 */
    private val NUMBER_RE = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

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

    fun listBody(id: Long = 1L): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""

    fun callBody(toolName: String, argumentsJson: String, id: Long = 2L): String {
        val args = argumentsJson.trim().ifEmpty { "{}" }
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call"""" +
            ""","params":{"name":"${esc(toolName)}","arguments":$args}}"""
    }

    /**
     * 解析 tools/list；惡形輸入拋 IllegalArgumentException（呼叫方映射為 PROTOCOL）。
     *
     * 入口先經 [validateTopLevelDocument] 驗頂層形狀（截斷/尾隨 garbage/缺逗號/重複鍵即 [McpProtocol]）；
     * 只認頂層 `result` 包裝（先取頂層 `result`，缺/`null` 即拋 fail-closed，與 parseCall 一致），
     * 再從 `result` 內取頂層 `tools` 陣列；無 `result` 包裝的 `{"tools":[...]}` 必須拒絕。
     * inputSchema 超 [INPUT_SCHEMA_MAX_CHARS] 字元拋 [McpProtocol]（拒絕＋原因碼），
     * 不靜默 take 截斷；name/description 短欄位 take 裝飾保留。
     */
    fun parseList(serverId: String, json: String): List<McpTool> {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        // 頂層形狀先行驗證：截斷（缺外層 }）、尾隨 garbage、缺逗號、重複頂層鍵一律 McpProtocol，
        // 不再只取局部 result 值就回 OK；缺 key 的 missing/bad 語義由下方保持。
        validateTopLevelDocument(json)
        val resultRaw = topLevelRawField(json, "result")
            ?: throw IllegalArgumentException(
                if (hasTopLevelKey(json, "result")) "mcp: bad result.tools" else "mcp: missing result",
            )
        val resultTrimmed = resultRaw.trim()
        if (resultTrimmed == "null") throw IllegalArgumentException("mcp: missing result")
        if (!resultTrimmed.startsWith("{")) throw IllegalArgumentException("mcp: bad result.tools")
        val toolsRaw = topLevelRawField(resultTrimmed, "tools")
            ?: throw IllegalArgumentException("mcp: bad result.tools")
        val toolsTrimmed = toolsRaw.trim()
        if (!toolsTrimmed.startsWith("[") || !toolsTrimmed.endsWith("]")) {
            throw IllegalArgumentException("mcp: bad result.tools")
        }
        val toolsBlock = toolsTrimmed.substring(1, toolsTrimmed.length - 1)
        if (toolsBlock.isBlank()) return emptyList()
        return splitObjects(toolsBlock).map { obj ->
            val name = stringField(obj, "name")
                ?: throw IllegalArgumentException("mcp: tool without name")
            val desc = stringField(obj, "description") ?: ""
            val schemaRaw = rawField(obj, "inputSchema")
            val schema = when {
                schemaRaw != null -> schemaRaw
                // 鍵存在但值非法（非法字面量/截斷半 object，rawField 取不到）→ PROTOCOL，不靜默套預設。
                hasTopLevelKey(obj, "inputSchema") -> throw McpProtocol("mcp: bad inputSchema")
                else -> """{"type":"object"}"""
            }
            // inputSchema 若存在必須是 object 形狀（與超限訊息區分；缺席才套預設）。
            if (!schema.trimStart().startsWith("{")) throw McpProtocol("mcp: bad inputSchema")
            if (schema.length > INPUT_SCHEMA_MAX_CHARS) {
                throw McpProtocol("mcp: inputSchema exceeds $INPUT_SCHEMA_MAX_CHARS chars")
            }
            McpTool(
                serverId = serverId,
                name = name.take(128),
                description = desc.take(500),
                inputSchema = schema,
            )
        }
    }

    /**
     * 解析 tools/call：回傳 (resultJson, isError)。
     *
     * 入口先經 [validateTopLevelDocument] 驗頂層形狀（截斷/尾隨 garbage/缺逗號/重複鍵即 [McpProtocol]）；
     * 只有頂層 `error` 鍵才算 error 包；`isError`/`content` 只認頂層 `result`
     * 物件的直接成員（depth 1），巢狀 content 內同名鍵不得誤判整包為 TOOL_ERROR；
     * 缺 `result` 即拋 IllegalArgumentException（fail-closed，呼叫方映射 PROTOCOL）。
     */
    fun parseCall(json: String): Pair<String?, Boolean> {
        if (json.length > McpTransport.RESPONSE_MAX_CHARS) {
            throw McpProtocol("mcp: response body exceeds ${McpTransport.RESPONSE_MAX_CHARS} chars")
        }
        // 頂層形狀先行驗證（同 parseList）：截斷/尾隨/缺逗號/重複鍵一律 McpProtocol。
        validateTopLevelDocument(json)
        val errRaw = topLevelRawField(json, "error")
        if (errRaw != null) {
            val errTrimmed = errRaw.trim()
            if (errTrimmed != "null") {
                // 只有 object 形狀才算 error 包；其他非 null 形狀（false/字串/數字等）一律 PROTOCOL。
                if (!errTrimmed.startsWith("{")) throw McpProtocol("mcp: bad error")
                // message 取自 error 物件內，避免全域搜到 content 文字的同名鍵。
                val msg = stringField(errTrimmed, "message")
                    ?: "tool error"
                throw McpToolFailure(msg.take(200))
            }
        } else if (hasTopLevelKey(json, "error")) {
            // error 鍵存在但值非法（非法字面量/截斷半 JSON）→ PROTOCOL，不當 OK。
            throw McpProtocol("mcp: bad error")
        }
        val resultRaw = topLevelRawField(json, "result")
            ?: throw IllegalArgumentException(
                if (hasTopLevelKey(json, "result")) "mcp: bad result" else "mcp: missing result",
            )
        val resultTrimmed = resultRaw.trim()
        if (resultTrimmed == "null") throw IllegalArgumentException("mcp: missing result")
        // result 非 object（一律 fail-closed）：字串/數字/陣列/布林字面量不得回 OK。
        if (!resultTrimmed.startsWith("{")) throw IllegalArgumentException("mcp: bad result")
        val contentRaw = topLevelRawField(resultTrimmed, "content")
        val isErrorRaw = topLevelRawField(resultTrimmed, "isError")
        val isError = when {
            isErrorRaw == null ->
                if (hasTopLevelKey(resultTrimmed, "isError")) {
                    throw McpProtocol("mcp: bad isError")
                } else {
                    false
                }
            isErrorRaw.trim().startsWith("\"") -> throw McpProtocol("mcp: bad isError")
            else -> when (isErrorRaw.trim()) {
                "true" -> true
                "false", "null" -> false
                // 畸形字面量（字串 "true"、True/1 等）不再靜默壓 false，一律 PROTOCOL。
                else -> throw McpProtocol("mcp: bad isError")
            }
        }
        // 內存消費用：優先保留 content，否則整個 result；調用方用後即丟，不寫盤。
        // 上限由傳輸層雙上限保證（64k 字元內完整 JSON）：此處不靜默 take，
        // 缺頂層 result（截斷半 JSON 含）即拋 PROTOCOL，不回 OK(null) 假成功。
        return ((contentRaw ?: resultRaw)) to isError
    }

    class McpToolFailure(message: String) : IllegalArgumentException(message)

    /** 協議形狀錯誤（inputSchema 超限、缺 result 等）：[McpServer] 映射為 PROTOCOL。 */
    class McpProtocol(message: String) : IllegalArgumentException(message)

    // ---- 極簡 JSON 掃描器（只夠本包兩種回應形狀；完整 JSON 解析不在此做） ----

    /**
     * 頂層 document 形狀驗證（Stage B P2-1：parseCall/parseList 入口先行調用）。
     *
     * 嚴格走訪頂層 object 全成員（複用 [skipValue] 的嚴格 stack 配對 + key 轉義解析），要求：
     * 前導只有空白 + `{`、成員為 `key:string` + `:` + value（value 經 [skipValue] 完整跳過）、
     * 分隔符正確（無缺逗號、無尾隨逗號）、唯一結尾 `}`、尾隨只有空白；
     * 重複頂層鍵一律拋 [McpProtocol]（嚴格拒絕，不做 first-wins）。
     * 只管形狀不管 key 有無：缺 key 的 missing/bad 語義由呼叫方保持（如 `{}` 可通過，此處不拋）。
     */
    internal fun validateTopLevelDocument(json: String) {
        var p = 0
        while (p < json.length && json[p].isWhitespace()) p++
        if (p >= json.length || json[p] != '{') throw McpProtocol("mcp: bad envelope")
        p++
        val seen = HashSet<String>()
        while (true) {
            while (p < json.length && json[p].isWhitespace()) p++
            if (p >= json.length) throw McpProtocol("mcp: bad envelope")
            // 空 object `{}` 合法（缺 key 語義由呼叫方判定，此處只驗形狀）。
            if (json[p] == '}') {
                p++
                while (p < json.length && json[p].isWhitespace()) p++
                if (p != json.length) throw McpProtocol("mcp: bad envelope")
                return
            }
            if (json[p] != '"') throw McpProtocol("mcp: bad envelope")
            val kb = StringBuilder()
            var r = p + 1
            var closed = false
            while (r < json.length) {
                val c = json[r++]
                if (c == '\\') {
                    if (r >= json.length) throw McpProtocol("mcp: bad envelope")
                    when (val e = json[r++]) {
                        '"', '\\', '/' -> kb.append(e)
                        'n' -> kb.append('\n')
                        'r' -> kb.append('\r')
                        't' -> kb.append('\t')
                        'b' -> kb.append('\b')
                        'f' -> kb.append('\u000C')
                        'u' -> {
                            if (r + 4 > json.length) throw McpProtocol("mcp: bad envelope")
                            kb.append(
                                json.substring(r, r + 4).toIntOrNull(16)?.toChar()
                                    ?: throw McpProtocol("mcp: bad envelope"),
                            )
                            r += 4
                        }
                        else -> kb.append(e)
                    }
                } else if (c == '"') {
                    closed = true
                    break
                } else {
                    kb.append(c)
                }
            }
            if (!closed) throw McpProtocol("mcp: bad envelope")
            var s = r
            while (s < json.length && json[s].isWhitespace()) s++
            if (s >= json.length || json[s] != ':') throw McpProtocol("mcp: bad envelope")
            s++
            while (s < json.length && json[s].isWhitespace()) s++
            if (s >= json.length) throw McpProtocol("mcp: bad envelope")
            val end = skipValue(json, s)
            if (end < 0 || end <= s) throw McpProtocol("mcp: bad envelope")
            if (!seen.add(kb.toString())) throw McpProtocol("mcp: duplicate key")
            p = end
            while (p < json.length && json[p].isWhitespace()) p++
            if (p >= json.length) throw McpProtocol("mcp: bad envelope")
            when (json[p]) {
                ',' -> {
                    p++
                    // 尾隨逗號（`,}`）非法：先行排除，迴圈頂的 `}` 只保留給空 object。
                    var q = p
                    while (q < json.length && json[q].isWhitespace()) q++
                    if (q >= json.length) throw McpProtocol("mcp: bad envelope")
                    if (json[q] == '}') throw McpProtocol("mcp: bad envelope")
                }
                '}' -> {
                    p++
                    while (p < json.length && json[p].isWhitespace()) p++
                    if (p != json.length) throw McpProtocol("mcp: bad envelope")
                    return
                }
                else -> throw McpProtocol("mcp: bad envelope")
            }
        }
    }

    internal fun esc(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 找 `"key":` 後的值起點（跳過空白）。 */
    private fun valueStart(json: String, key: String): Int {
        val k = json.indexOf(key)
        if (k < 0) return -1
        var i = json.indexOf(':', k + key.length)
        if (i < 0) return -1
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        return i
    }

    internal fun stringField(json: String, key: String): String? {
        val i = valueStart(json, "\"$key\"")
        if (i < 0 || i >= json.length || json[i] != '"') return null
        val sb = StringBuilder()
        var p = i + 1
        while (p < json.length) {
            val c = json[p++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (p >= json.length) return null
                    when (val e = json[p++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (p + 4 > json.length) return null
                            val hex = json.substring(p, p + 4)
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                            p += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
        return null
    }

    internal fun boolField(json: String, key: String): Boolean? {
        val i = valueStart(json, "\"$key\"")
        if (i < 0) return null
        return when {
            json.startsWith("true", i) -> true
            json.startsWith("false", i) -> false
            else -> null
        }
    }

    /** 取 `"key":` 後的原始值文本（object/array/string/字面量均可）。 */
    internal fun rawField(json: String, key: String): String? {
        val i = valueStart(json, "\"$key\"")
        if (i < 0 || i >= json.length) return null
        return rawValue(json, i)
    }

    /**
     * 取頂層 object `"key":` 後的原始值（只認 depth 1 成員鍵）。
     * 巢狀同名鍵（如 result.content 內的 `"error"`）不算，避免誤判丟資料。
     */
    internal fun topLevelRawField(json: String, key: String): String? {
        val i = topLevelValueStart(json, key)
        if (i < 0 || i >= json.length) return null
        return rawValue(json, i)
    }

    /** 頂層 object 是否含 `"key"` 成員（值合法與否不論；區分缺席與非法字面量）。 */
    internal fun hasTopLevelKey(json: String, key: String): Boolean =
        topLevelValueStart(json, key) >= 0

    /** 頂層 object 成員的值起點；缺席或形狀不合回 -1（字串感知）。 */
    private fun topLevelValueStart(json: String, key: String): Int {
        var p = 0
        while (p < json.length && json[p].isWhitespace()) p++
        if (p >= json.length || json[p] != '{') return -1
        p++
        while (true) {
            var q = p
            while (q < json.length && json[q].isWhitespace()) q++
            if (q >= json.length) return -1
            if (json[q] == '}') return -1
            if (json[q] == ',') {
                p = q + 1
                continue
            }
            if (json[q] != '"') return -1
            val kb = StringBuilder()
            var r = q + 1
            var closed = false
            while (r < json.length) {
                val c = json[r++]
                if (c == '\\') {
                    if (r >= json.length) return -1
                    when (val e = json[r++]) {
                        '"', '\\', '/' -> kb.append(e)
                        'n' -> kb.append('\n')
                        'r' -> kb.append('\r')
                        't' -> kb.append('\t')
                        'b' -> kb.append('\b')
                        'f' -> kb.append('\u000C')
                        'u' -> {
                            if (r + 4 > json.length) return -1
                            kb.append(json.substring(r, r + 4).toIntOrNull(16)?.toChar() ?: return -1)
                            r += 4
                        }
                        else -> kb.append(e)
                    }
                } else if (c == '"') {
                    closed = true
                    break
                } else {
                    kb.append(c)
                }
            }
            if (!closed) return -1
            var s = r
            while (s < json.length && json[s].isWhitespace()) s++
            if (s >= json.length || json[s] != ':') return -1
            s++
            while (s < json.length && json[s].isWhitespace()) s++
            if (s >= json.length) return -1
            if (kb.toString() == key) return s
            val end = skipValue(json, s)
            if (end < 0) return -1
            p = end
        }
    }

    /** 跳過一個 JSON 值，回值後索引；失敗回 -1。 */
    private fun skipValue(json: String, from: Int): Int {
        if (from >= json.length) return -1
        when (json[from]) {
            '"' -> {
                var p = from + 1
                while (p < json.length) {
                    val c = json[p++]
                    if (c == '\\') {
                        if (p < json.length) p++ else return -1
                    } else if (c == '"') {
                        return p
                    }
                }
                return -1
            }
            '{', '[' -> {
                // `{` 只能 `}` 關、`[` 只能 `]` 關：stack 配對，mismatch 回 -1。
                val stack = ArrayDeque<Char>()
                var instr = false
                var es = false
                var p = from
                while (p < json.length) {
                    val c = json[p]
                    if (instr) {
                        if (es) es = false
                        else if (c == '\\') es = true
                        else if (c == '"') instr = false
                    } else {
                        when (c) {
                            '"' -> instr = true
                            '{' -> stack.addLast('}')
                            '[' -> stack.addLast(']')
                            '}', ']' -> {
                                if (stack.isEmpty() || stack.removeLast() != c) return -1
                                if (stack.isEmpty()) return p + 1
                            }
                        }
                    }
                    p++
                }
                return -1
            }
            else -> {
                var p = from
                while (p < json.length && ",}]".indexOf(json[p]) < 0) p++
                return p
            }
        }
    }

    /** 取 `"key": [` 陣列的內部文本（不含外括號）；缺席回 null。 */
    internal fun sectionArray(json: String, key: String): String? {
        val i = valueStart(json, key)
        if (i < 0 || i >= json.length || json[i] != '[') return null
        var depth = 0
        var inStr = false
        var esc = false
        var p = i
        while (p < json.length) {
            val c = json[p]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return json.substring(i + 1, p)
                    }
                }
            }
            p++
        }
        return null
    }

    private fun rawValue(json: String, from: Int): String? {
        var p = from
        if (p >= json.length) return null
        return when (json[p]) {
            '"', '{', '[' -> {
                val open = json[p]
                // 對 object/array：stack 配對逐字掃描；對 string：找非轉義引號。
                if (open == '"') {
                    p++
                    val sb = StringBuilder()
                    sb.append('"')
                    while (p < json.length) {
                        val c = json[p++]
                        sb.append(c)
                        if (c == '\\' && p < json.length) sb.append(json[p++])
                        else if (c == '"') return sb.toString()
                    }
                    return null
                }
                var instr = false
                var es = false
                val start = from
                // `{` 只能 `}` 關、`[` 只能 `]` 關：stack 配對，mismatch 視為截斷（回 null）。
                val stack = ArrayDeque<Char>()
                while (p < json.length) {
                    val c = json[p]
                    if (instr) {
                        if (es) es = false
                        else if (c == '\\') es = true
                        else if (c == '"') instr = false
                    } else {
                        when (c) {
                            '"' -> instr = true
                            '{' -> stack.addLast('}')
                            '[' -> stack.addLast(']')
                            '}', ']' -> {
                                if (stack.isEmpty() || stack.removeLast() != c) return null
                                if (stack.isEmpty()) return json.substring(start, p + 1)
                            }
                        }
                    }
                    p++
                }
                return null
            }
            else -> {
                while (p < json.length && ",}]".indexOf(json[p]) < 0) p++
                val lit = json.substring(from, p).trim()
                if (lit.isEmpty()) return null
                // 合法字面量僅 true/false/null/number；其他（TRUE/undefined/NaN 等）回 null 使上層拋。
                if (lit == "true" || lit == "false" || lit == "null" || NUMBER_RE.matches(lit)) return lit
                return null
            }
        }
    }

    /**
     * 切分陣列內部文本為頂層 object 列表（字串感知；逗號僅在 depth 0 分隔）。
     *
     * Stage B P2-2 嚴格化：每項必須是 `{` 起始的完整 object（經 [skipValue] 完整），
     * 否則拋 [McpProtocol]（`mcp: bad tools item`），不再靜默忽略 `null`/數字/字串項。
     */
    internal fun splitObjects(inner: String): List<String> {
        if (inner.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var depth = 0
        var instr = false
        var es = false
        var start = 0
        var p = 0
        while (p < inner.length) {
            val c = inner[p]
            if (instr) {
                if (es) es = false
                else if (c == '\\') es = true
                else if (c == '"') instr = false
            } else {
                when (c) {
                    '"' -> instr = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth < 0) throw McpProtocol("mcp: bad tools item")
                    }
                    ',' -> if (depth == 0) {
                        out.add(strictToolItem(inner.substring(start, p)))
                        start = p + 1
                    }
                }
            }
            p++
        }
        out.add(strictToolItem(inner.substring(start)))
        return out
    }

    /** 單一 tools 陣列項必須是完整 object，否則拋 [McpProtocol]（fail-closed，不忽略）。 */
    private fun strictToolItem(raw: String): String {
        val item = raw.trim()
        if (!item.startsWith("{") || skipValue(item, 0) != item.length) {
            throw McpProtocol("mcp: bad tools item")
        }
        return item
    }
}
