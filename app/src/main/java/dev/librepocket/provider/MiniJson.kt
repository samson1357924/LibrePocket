package dev.librepocket.provider

/**
 * Minimal JSON reader for SSE payloads.
 *
 * Zero dependencies on purpose: the provider module must stay JVM-unit-testable
 * (android.jar `org.json` stubs throw under plain JUnit) and must not gain new
 * Gradle dependencies in P1. Only the subset needed for event projection is
 * supported; malformed input throws [MiniJsonException]. Over-ceiling nesting
 * throws the [MiniJsonDepthException] subtype (still caught by existing
 * [MiniJsonException] catches); protocol mappers translate the depth subtype
 * into a typed non-retryable aggregation failure instead of dropping the frame.
 */
internal object MiniJson {
    /**
     * Maximum nesting depth (nested objects/arrays) accepted before parsing
     * fails closed with [MiniJsonDepthException].
     *
     * A hostile ≤1 MiB SSE frame can nest hundreds of thousands of containers
     * (up to ~524k array levels, ~131k with object keys); recursive descent
     * overflows the native stack ([StackOverflowError]) orders of magnitude
     * before that. The protocol mappers only catch [MiniJsonException] per
     * frame, so an escaping [StackOverflowError] would instead be swallowed by
     * the transport's general branch and mislabeled a retryable failure. Ceiling
     * the depth keeps the failure typed and fail-closed: over-ceiling input
     * throws [MiniJsonDepthException] (a [MiniJsonException] subtype, so the
     * existing per-frame catches still catch it), and the mappers translate
     * the depth subtype into a typed non-retryable `ProviderFailure`
     * (`TOOL_ARGS_TOO_LARGE` / `AGG_TOO_LARGE`) — the same semantics as every
     * aggregation-budget overflow. The poisoned attempt then fails without
     * re-requesting that frame, without folding a later terminal into a
     * success `Done` (the mapper latches the breach), and without degrading
     * to a retryable `SSE_TRUNCATED` at EOF.
     *
     * The value is deliberately generous: the deepest legitimate payload in
     * project fixtures nests 7 (`chat-basic.txt` tool_calls.function); even a
     * very deep real envelope stays an order of magnitude below 64, so a
     * well-formed event never trips this while a hostile one is rejected far
     * below the native stack limit.
     */
    internal const val MAX_DEPTH = 64

    sealed interface J

    data class JObj(val map: Map<String, J>) : J
    data class JArr(val items: List<J>) : J
    data class JStr(val v: String) : J
    data class JNum(val v: Double) : J
    data class JBool(val v: Boolean) : J
    object JNull : J

    open class MiniJsonException(message: String) : IllegalArgumentException(message)

    /**
     * Nesting-depth ceiling breach ([MAX_DEPTH]). A subtype of
     * [MiniJsonException], so pre-existing `catch (MiniJsonException)` sites
     * still catch it — but the protocol mappers discriminate this subtype and
     * fail the poisoned attempt with a typed non-retryable aggregation failure
     * instead of dropping the frame and continuing.
     */
    class MiniJsonDepthException(message: String) : MiniJsonException(message)

    fun parse(text: String): J = Parser(text).parseValue().also { it }

    private class Parser(val s: String) {
        var pos = 0
        private var depth = 0

        fun parseValue(): J {
            skipWs()
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> withDepth { parseObj() }
                '[' -> withDepth { parseArr() }
                '"' -> JStr(parseString())
                't' -> { expect("true"); JBool(true) }
                'f' -> { expect("false"); JBool(false) }
                'n' -> { expect("null"); JNull }
                '-', in '0'..'9' -> parseNum()
                else -> fail("unexpected char '$c'")
            }
        }

        /**
         * Fail-closed recursion guard. Objects and arrays each enter a nesting
         * level here (the only two recursive productions); the [try]/[finally]
         * restores [depth] on every exit path — normal return or thrown — so a
         * sibling container never inherits a leaked depth. Over-ceiling input
         * throws [MiniJsonDepthException] (not the plain [MiniJsonException]
         * used for malformed frames) so the mappers fail the poisoned attempt
         * typed non-retryable instead of dropping one frame and later
         * succeeding without its delta.
         */
        private inline fun <T> withDepth(block: () -> T): T {
            if (depth >= MAX_DEPTH) throw MiniJsonDepthException("nesting exceeds $MAX_DEPTH at offset $pos")
            depth++
            try {
                return block()
            } finally {
                depth--
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
                                // Strict 4-hex gate (parity with McpWire.parseString):
                                // `toIntOrNull(16)` also accepts signed prefixes —
                                // `\u-123` would decode to U+FEDD (negation
                                // overflow), `\u+0bb` would silently read as
                                // 0x00bb — while RFC 8259 only admits hex
                                // digits. Any `\uXXXX` whose 4 units are not
                                // exactly [0-9a-fA-F]{4} fails closed with the
                                // same parser error as the other malformed
                                // escape branches.
                                val hex = s.substring(pos, pos + 4)
                                if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                                    fail("bad unicode escape")
                                }
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
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
