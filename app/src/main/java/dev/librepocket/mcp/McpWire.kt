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

    /**
     * 使用者自訂完整 URL（僅去尾斜線，不附加任何 suffix，原樣使用）。
     *
     * 呼叫方應在配置寫完整端點（含 path，如 `/mcp`）。
     */
    fun endpoint(baseUrl: String): String {
        return baseUrl.trimEnd('/')
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
     * 只認頂層 `result` 包裝（先取頂層 `result`，缺/`null` 即拋 fail-closed，與 parseCall 一致），
     * 再從 `result` 內取頂層 `tools` 陣列；無 `result` 包裝的 `{"tools":[...]}` 必須拒絕。
     * inputSchema 超 [INPUT_SCHEMA_MAX_CHARS] 字元拋 [McpProtocol]（拒絕＋原因碼），
     * 不靜默 take 截斷；name/description 短欄位 take 裝飾保留。
     */
    fun parseList(serverId: String, json: String): List<McpTool> {
        val resultRaw = topLevelRawField(json, "result")
            ?: throw IllegalArgumentException("mcp: missing result")
        if (resultRaw == "null") throw IllegalArgumentException("mcp: missing result")
        val toolsRaw = (if (resultRaw.startsWith("{")) topLevelRawField(resultRaw, "tools") else null)
            ?: throw IllegalArgumentException("mcp: missing result.tools")
        val toolsTrimmed = toolsRaw.trim()
        if (!toolsTrimmed.startsWith("[") || !toolsTrimmed.endsWith("]")) {
            throw IllegalArgumentException("mcp: missing result.tools")
        }
        val toolsBlock = toolsTrimmed.substring(1, toolsTrimmed.length - 1)
        if (toolsBlock.isBlank()) return emptyList()
        return splitObjects(toolsBlock).map { obj ->
            val name = stringField(obj, "name")
                ?: throw IllegalArgumentException("mcp: tool without name")
            val desc = stringField(obj, "description") ?: ""
            val schema = rawField(obj, "inputSchema") ?: """{"type":"object"}"""
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
     * 只有頂層 `error` 鍵才算 error 包；`isError`/`content` 只認頂層 `result`
     * 物件的直接成員（depth 1），巢狀 content 內同名鍵不得誤判整包為 TOOL_ERROR；
     * 缺 `result` 即拋 IllegalArgumentException（fail-closed，呼叫方映射 PROTOCOL）。
     */
    fun parseCall(json: String): Pair<String?, Boolean> {
        val errRaw = topLevelRawField(json, "error")
        if (errRaw != null && errRaw != "null") {
            // message 取自 error 物件內，避免全域搜到 content 文字的同名鍵。
            val msg = (if (errRaw.startsWith("{")) stringField(errRaw, "message") else null)
                ?: "tool error"
            throw McpToolFailure(msg.take(200))
        }
        val resultRaw = topLevelRawField(json, "result")
            ?: throw IllegalArgumentException("mcp: missing result")
        if (resultRaw == "null") throw IllegalArgumentException("mcp: missing result")
        // result 非 object（如字串/陣列字面量）時無成員可取：isError 預設 false，直接回原文。
        val contentRaw = if (resultRaw.startsWith("{")) topLevelRawField(resultRaw, "content") else null
        val isErrorRaw = if (resultRaw.startsWith("{")) topLevelRawField(resultRaw, "isError") else null
        val isError = isErrorRaw?.trim() == "true"
        // 內存消費用：優先保留 content，否則整個 result；調用方用後即丟，不寫盤。
        // 上限由傳輸層雙上限保證（64k 字元內完整 JSON）：此處不靜默 take，
        // 缺頂層 result（截斷半 JSON 含）即拋 PROTOCOL，不回 OK(null) 假成功。
        return ((contentRaw ?: resultRaw)) to isError
    }

    class McpToolFailure(message: String) : IllegalArgumentException(message)

    /** 協議形狀錯誤（inputSchema 超限、缺 result 等）：[McpServer] 映射為 PROTOCOL。 */
    class McpProtocol(message: String) : IllegalArgumentException(message)

    // ---- 極簡 JSON 掃描器（只夠本包兩種回應形狀；完整 JSON 解析不在此做） ----

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
                var depth = 0
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
                            '{', '[' -> depth++
                            '}', ']' -> {
                                depth--
                                if (depth == 0) return p + 1
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
                val close = when (open) {
                    '"' -> '"'
                    '{' -> '}'
                    else -> ']'
                }
                var depth = 0
                var inStr = open == '{' || open == '['
                // 對 object/array：逐字掃描配對；對 string：找非轉義引號。
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
                while (p < json.length) {
                    val c = json[p]
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
                                if (depth == 0) return json.substring(start, p + 1)
                            }
                        }
                    }
                    // 外層 close 由 depth 計數處理；inStr 初始值不再使用。
                    @Suppress("UNUSED_VARIABLE")
                    val ignored = close
                    p++
                }
                return null
            }
            else -> {
                while (p < json.length && ",}]".indexOf(json[p]) < 0) p++
                json.substring(from, p).trim().ifEmpty { null }
            }
        }
    }

    /** 切分陣列內部文本為頂層 object 列表（字串感知）。 */
    internal fun splitObjects(inner: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var instr = false
        var es = false
        var start = -1
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
                    '{' -> {
                        if (depth == 0) start = p
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            out.add(inner.substring(start, p + 1))
                            start = -1
                        }
                    }
                }
            }
            p++
        }
        return out
    }
}
