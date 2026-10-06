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

    fun endpoint(baseUrl: String): String {
        val b = baseUrl.trimEnd('/')
        return if (b.endsWith("/mcp")) b else b + "/mcp"
    }

    fun listBody(id: Long = 1L): String =
        """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""

    fun callBody(toolName: String, argumentsJson: String, id: Long = 2L): String {
        val args = argumentsJson.trim().ifEmpty { "{}" }
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call"""" +
            ""","params":{"name":"${esc(toolName)}","arguments":$args}}"""
    }

    /** 解析 tools/list；惡形輸入拋 IllegalArgumentException（呼叫方映射為 PROTOCOL）。 */
    fun parseList(serverId: String, json: String): List<McpTool> {
        val toolsBlock = sectionArray(json, "\"tools\"")
            ?: throw IllegalArgumentException("mcp: missing result.tools")
        if (toolsBlock.isBlank()) return emptyList()
        return splitObjects(toolsBlock).map { obj ->
            val name = stringField(obj, "name")
                ?: throw IllegalArgumentException("mcp: tool without name")
            val desc = stringField(obj, "description") ?: ""
            val schema = rawField(obj, "inputSchema") ?: """{"type":"object"}"""
            McpTool(
                serverId = serverId,
                name = name.take(128),
                description = desc.take(500),
                inputSchema = schema.take(8_192),
            )
        }
    }

    /** 解析 tools/call：回傳 (resultJson, isError)；只有頂層 error 鍵才算 error 包。 */
    fun parseCall(json: String): Pair<String?, Boolean> {
        val errRaw = topLevelRawField(json, "error")
        if (errRaw != null && errRaw != "null") {
            // message 取自 error 物件內，避免全域搜到 content 文字的同名鍵。
            val msg = (if (errRaw.startsWith("{")) stringField(errRaw, "message") else null)
                ?: "tool error"
            throw McpToolFailure(msg.take(200))
        }
        val isError = boolField(json, "isError") ?: false
        val content = rawField(json, "content")
        val result = rawField(json, "result")
        // 內存消費用：優先保留 content，否則整個 result；調用方用後即丟，不寫盤。
        return ((content ?: result)?.take(32_768)) to isError
    }

    class McpToolFailure(message: String) : IllegalArgumentException(message)

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
