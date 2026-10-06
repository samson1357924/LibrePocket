package dev.librepocket.provider

/**
 * Minimal JSON reader for SSE payloads.
 *
 * Zero dependencies on purpose: the provider module must stay JVM-unit-testable
 * (android.jar `org.json` stubs throw under plain JUnit) and must not gain new
 * Gradle dependencies in P1. Only the subset needed for event projection is
 * supported; malformed input throws [MiniJsonException].
 */
internal object MiniJson {
    sealed interface J

    data class JObj(val map: Map<String, J>) : J
    data class JArr(val items: List<J>) : J
    data class JStr(val v: String) : J
    data class JNum(val v: Double) : J
    data class JBool(val v: Boolean) : J
    object JNull : J

    class MiniJsonException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): J = Parser(text).parseValue().also { it }

    private class Parser(val s: String) {
        var pos = 0

        fun parseValue(): J {
            skipWs()
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> JStr(parseString())
                't' -> { expect("true"); JBool(true) }
                'f' -> { expect("false"); JBool(false) }
                'n' -> { expect("null"); JNull }
                '-', in '0'..'9' -> parseNum()
                else -> fail("unexpected char '$c'")
            }
        }

        fun parseObj(): JObj {
            pos++ // {
            val m = LinkedHashMap<String, J>()
            skipWs()
            if (peek() == '}') {
                pos++
                return JObj(m)
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected object key")
                val k = parseString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                m[k] = parseValue()
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> break
                    else -> fail("expected ',' or '}'")
                }
            }
            return JObj(m)
        }

        fun parseArr(): JArr {
            pos++ // [
            val items = ArrayList<J>()
            skipWs()
            if (peek() == ']') {
                pos++
                return JArr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> break
                    else -> fail("expected ',' or ']'")
                }
            }
            return JArr(items)
        }

        fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNum(): JNum {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            val raw = s.substring(start, pos)
            return JNum(raw.toDoubleOrNull() ?: fail("bad number '$raw'"))
        }

        fun expect(word: String) {
            if (!s.startsWith(word, pos)) fail("expected '$word'")
            pos += word.length
        }

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        fun fail(msg: String): Nothing = throw MiniJsonException("$msg at offset $pos")
    }
}

/** Top-level field accessors (usable from anywhere in the module). */
internal fun MiniJson.JObj.string(key: String): String? = (map[key] as? MiniJson.JStr)?.v
internal fun MiniJson.JObj.obj(key: String): MiniJson.JObj? = map[key] as? MiniJson.JObj
internal fun MiniJson.JObj.arr(key: String): MiniJson.JArr? = map[key] as? MiniJson.JArr
internal fun MiniJson.JObj.bool(key: String): Boolean? = (map[key] as? MiniJson.JBool)?.v
internal fun MiniJson.JObj.long(key: String): Long? = (map[key] as? MiniJson.JNum)?.v?.toLong()
internal fun MiniJson.JObj.int(key: String): Int? = (map[key] as? MiniJson.JNum)?.v?.toInt()
internal fun MiniJson.JObj.num(key: String): Double? = (map[key] as? MiniJson.JNum)?.v
