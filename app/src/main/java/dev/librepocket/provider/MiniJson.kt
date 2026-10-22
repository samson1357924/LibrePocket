package dev.librepocket.provider

/**
 * Minimal JSON reader for SSE payloads.
 *
 * Zero dependencies on purpose: the provider module must stay JVM-unit-testable
 * (android.jar `org.json` stubs throw under plain JUnit) and must not gain new
 * Gradle dependencies in P1. Only the subset needed for event projection is
 * supported; malformed input throws [MiniJsonException]. Over-ceiling nesting
 * or node count throws a [MiniJsonFatalException] subtype (still caught by
 * existing [MiniJsonException] catches); protocol mappers translate a fatal
 * subtype into a typed non-retryable aggregation failure instead of dropping
 * the frame. Strictness pins (RFC 8259 subset): the root value must be
 * followed only by whitespace to EOF (no trailing junk such as `1_000` /
 * `0x10` / `{}x`), numbers use the RFC 8259 grammar and must be finite, and
 * raw U+0000–U+001F inside strings is rejected (escaped forms stay legal).
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

    /**
     * Maximum node count accepted before parsing fails closed with
     * [MiniJsonNodeLimitException] (a [MiniJsonFatalException]).
     *
     * Counting semantics: every parsed value node (object, array, string,
     * number, boolean, null) and every object key counts as one node — the
     * two kinds that allocate. `{"a":1}` is 3 nodes (object + key + number);
     * `[1,2]` is 3 (array + two numbers).
     *
     * Rationale: depth alone does not bound allocation for a FLAT hostile
     * frame. A 1 MiB payload of `[0,0,0,…]` nests one level yet allocates
     * ~524k value nodes (plus the backing list), and the same holds for a
     * wide object: every key is a fresh `String` and every value a node.
     * The cap keeps the failure typed and fail-closed — over-ceiling input
     * throws [MiniJsonNodeLimitException] (a [MiniJsonException] subtype, so
     * pre-existing per-frame catches still catch it) and the protocol mappers
     * translate the fatal base into a typed non-retryable aggregation
     * failure, the same semantics as the depth ceiling. A
     * dropped frame must never be folded into a later success: to a mapper
     * a node breach and a depth breach are both fatal latches, not
     * droppable malformed frames.
     *
     * The value is deliberately generous: legitimate SSE payloads are tiny
     * (the richest project fixture, `sse/chat-basic.txt`, is well under 100
     * nodes), so a well-formed event never trips this. A hostile dense flat
     * frame trips it at ~128 KiB (2 bytes/node), far below the 1 MiB frame
     * budget, keeping parsing allocation bounded.
     */
    internal const val MAX_NODES = 65_536

    sealed interface J

    data class JObj(val map: Map<String, J>) : J
    data class JArr(val items: List<J>) : J
    data class JStr(val v: String) : J
    data class JNum(val v: Double, val raw: String = v.toString()) : J
    data class JBool(val v: Boolean) : J
    object JNull : J

    open class MiniJsonException(message: String) : IllegalArgumentException(message)

    /**
     * Fatal parse breach that must NOT be treated as a droppable malformed
     * frame: over-ceiling nesting ([MiniJsonDepthException]) or over-ceiling
     * node count ([MiniJsonNodeLimitException]). A [MiniJsonException]
     * subtype, so pre-existing `catch (MiniJsonException)` sites still catch
     * it — but the protocol mappers discriminate this base from a plain
     * malformed-frame [MiniJsonException] and fail the
     * poisoned attempt with a typed non-retryable aggregation failure.
     * Dropping such a frame would lose its delta and could let a later
     * terminal fold the loss into a fake success; a silent retry would
     * re-request the poisoned stream.
     */
    open class MiniJsonFatalException(message: String) : MiniJsonException(message)

    /**
     * Nesting-depth ceiling breach ([MAX_DEPTH]). A fatal subtype of
     * [MiniJsonException], so pre-existing `catch (MiniJsonException)` sites
     * still catch it — but the protocol mappers discriminate this subtype and
     * fail the poisoned attempt with a typed non-retryable aggregation failure
     * instead of dropping the frame and continuing.
     */
    class MiniJsonDepthException(message: String) : MiniJsonFatalException(message)

    /**
     * Node-count ceiling breach ([MAX_NODES]). A fatal subtype alongside
     * [MiniJsonDepthException]: depth bounds recursion, this bounds flat
     * allocation (see [MAX_NODES] counting semantics). Same mapper contract
     * — typed non-retryable aggregation failure, never a dropped frame.
     */
    class MiniJsonNodeLimitException(message: String) : MiniJsonFatalException(message)

    /**
     * Parse one JSON document. [maxNodes] defaults to [MAX_NODES]; callers
     * with a justified larger legit-input budget (e.g. the 4 MiB models.dev
     * snapshot) pass their own documented ceiling.
     */
    fun parse(text: String, maxNodes: Int = MAX_NODES): J = Parser(text, maxNodes).parseDocument()

    private class Parser(val s: String, private val maxNodes: Int) {
        var pos = 0
        private var depth = 0
        private var nodes = 0

        /** Root value plus EOF check: trailing junk after the root is malformed. */
        fun parseDocument(): J {
            val v = parseValue()
            skipWs()
            if (pos < s.length) fail("unexpected trailing content")
            return v
        }

        /**
         * Charge one allocated node (value or object key). Over-ceiling
         * input throws [MiniJsonNodeLimitException] — the node counterpart
         * of the [withDepth] guard; both are fatal (never droppable).
         */
        private fun chargeNode() {
            nodes++
            if (nodes > maxNodes) throw MiniJsonNodeLimitException("node count exceeds $maxNodes at offset $pos")
        }

        fun parseValue(): J {
            chargeNode()
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
                chargeNode() // every key allocates a String node too
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
                    else -> {
                        // RFC 8259: unescaped U+0000–U+001F inside a string is
                        // illegal (raw controls are also a framing hazard for
                        // downstream consumers). Escaped forms (`\n`,
                        // `\u0000`, …) keep their existing semantics.
                        if (c < ' ') fail("unescaped control character")
                        sb.append(c)
                    }
                }
            }
        }

        /**
         * RFC 8259 number grammar: `-? (0 | [1-9][0-9]*) (\. [0-9]+)?
         * ([eE][+-]?[0-9]+)?`, then finite. The old loose scan
         * (`substring.toDoubleOrNull`) accepted junk shapes (`01`, `1.`,
         * `.5` at the caller, `1e`, `1..2`, …) and non-finite overflow
         * (`1e999` → Infinity); all fail closed here.
         */
        fun parseNum(): JNum {
            val start = pos
            if (peek() == '-') pos++
            when {
                pos >= s.length -> fail("bad number")
                s[pos] == '0' -> pos++
                s[pos] in '1'..'9' -> while (pos < s.length && s[pos] in '0'..'9') pos++
                else -> fail("bad number")
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail("bad number")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') fail("bad number")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val raw = s.substring(start, pos)
            val v = raw.toDouble()
            if (!v.isFinite()) fail("non-finite number '$raw'")
            return JNum(v, raw)
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
