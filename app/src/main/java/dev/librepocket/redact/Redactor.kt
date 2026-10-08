package dev.librepocket.redact

import kotlin.text.RegexOption.IGNORE_CASE

/**
 * P1 redaction table (M5, spec §6: R1–R12) plus R13 GEO_COORD, a beyond-spec
 * hardening addition (B3: coordinates must not reach transcript/audit/export),
 * plus R14 ANDROID_PATH (S4: absolute on-device paths must not reach
 * transcript/audit/export, covering S4 container path echoes).
 *
 * Pure functions; zero Android dependencies so plain JVM unit tests can run them.
 * Rules apply sequentially R1..R14; [RedactResult.hits] counts matches per rule
 * in table order (rules with zero hits are included with count 0).
 */
object Redactor {

    private data class Rule(val id: String, val pattern: Regex, val replacement: String)
    private data class QuotedValueBoundary(
        // Source suffix starts at the original quote, recovery delimiter, or line end.
        val copyFrom: Int,
        val resumeAt: Int,
        val synthesizeClosingQuote: Boolean,
    )

    private const val JSON_REDACTED_VALUE = "⟦REDACTED⟧"

    // Table order is load-bearing: e.g. R7 (TW mobile) must run before R8 (intl),
    // and R1/R2 (key shapes) before R3 (generic key=value fields).
    // R10 (CARD_16) is not in this list: it needs a Luhn check, so it runs in
    // table position via applyCardRule() (after ID_TW, before IPV4_PRIVATE).
    private val RULES: List<Rule> = listOf(
        Rule(
            "API_KEY_VALUE",
            Regex(
                """(sk-[A-Za-z0-9\-_]{8,}|xox[bpas]-[A-Za-z0-9\-]{8,}|ghp_[A-Za-z0-9]{8,}|gsk_[A-Za-z0-9]{8,}|AIza[A-Za-z0-9\-_]{8,})""",
                IGNORE_CASE,
            ),
            "⟦REDACTED:API_KEY⟧",
        ),
        Rule(
            "BEARER_TOKEN",
            Regex("""bearer\s+[A-Za-z0-9\-._~+/]+=*\s*""", IGNORE_CASE),
            "⟦REDACTED:TOKEN⟧",
        ),
        Rule(
            "JSON_KEY_FIELD",
            // Quoted JSON values are handled by the linear scanner at the R3
            // position (after R1/R2, see redact()). This legacy rule remains
            // for unquoted key=value fields; keep URL query delimiters in R5's
            // domain. Idempotence only skips an already-redacted marker when it
            // is the entire value (marker followed by closing quote, value
            // boundary, or end); a marker-prefixed live secret must still be
            // redacted.
            Regex(
                """("?(api[_-]?key|secret|token|password|passwd|auth)"?\s*[:=]\s*"?)(?!⟦REDACTED⟧(["\s,}?&]|$))[^",\s}&?]{4,}""",
                IGNORE_CASE,
            ),
            "\$1$JSON_REDACTED_VALUE",
        ),
        Rule(
            "URL_CREDENTIAL",
            Regex("""(https?://)[^/\s@]+@""", IGNORE_CASE),
            "\$1⟦REDACTED⟧@",
        ),
        Rule(
            "URL_TOKEN_PARAM",
            Regex("""([?&](api[_-]?key|token|access_token|secret)\s*=\s*)[^&\s]*""", IGNORE_CASE),
            "\$1⟦REDACTED⟧",
        ),
        Rule(
            "EMAIL",
            Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
            "⟦REDACTED:EMAIL⟧",
        ),
        Rule(
            "PHONE_GENERIC",
            Regex("""(?<!\d)(\+?886[\-.\s]?)?09\d{2}[\-.\s]?\d{3}[\-.\s]?\d{3}(?!\d)"""),
            "⟦REDACTED:PHONE⟧",
        ),
        Rule(
            "PHONE_INTL",
            Regex("""(?<!\d)\+\d{1,3}[\-.\s]?\d{4,14}(?!\d)"""),
            "⟦REDACTED:PHONE⟧",
        ),
        Rule(
            "ID_TW",
            Regex("""[A-Z][12]\d{8}"""),
            "⟦REDACTED:ID⟧",
        ),
        Rule(
            "IPV4_PRIVATE",
            Regex("""\b(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b"""),
            "⟦REDACTED:IP⟧",
        ),
        Rule(
            "ANDROID_ID_LIKE",
            Regex("""(android[_-]?id|device[_-]?id|imei|serial)\s*[:=]\s*\S+""", IGNORE_CASE),
            "\$1=⟦REDACTED⟧",
        ),
        Rule(
            "GEO_COORD",
            // R13 (B3 fix): coordinates must not reach transcript/audit/export.
            // Covers systema/NavigationTool output ("geo:lat,lng[?q=..]") as well as
            // bare "lat,lng" pairs (e.g. inside OSM/Google-Maps fallback URLs) and
            // labelled forms ("lat=..&lng=..", "座標：..,.."). Runs last: earlier
            // rules never emit digit-dot-comma shapes, and no earlier rule matches
            // inside a geo URI (R5 only knows api_key/token/secret keys).
            Regex(
                """(?<![A-Za-z])geo:-?\d+(?:\.\d+)?,\s*-?\d+(?:\.\d+)?(?:[?;][^\s,.;!?)]*)?|(?<![A-Za-z])(?:lat(?:itude)?|lng|lon(?:gitude)?|latlng|loc(?:ation)?|coordinates?|coords?|經緯度|座標|位置)\s*[:=：]?\s*-?\d+(?:\.\d+)?\s*[,，]\s*-?\d+(?:\.\d+)?|(?<![A-Za-z])(?:lat(?:itude)?|lng|lon(?:gitude)?)\s*[:=：]\s*-?\d+(?:\.\d+)?|(?<![A-Za-z0-9.])-?\d{1,3}\.\d{2,}\s*[,，]\s*-?\d{1,3}\.\d{2,}(?!\d)""",
                IGNORE_CASE,
            ),
            "⟦REDACTED:GEO⟧",
        ),
        Rule(
            "ANDROID_PATH",
            // R14 (S4): absolute on-device paths must not reach transcript/audit/export.
            // Covers the app private domain (/data/data|user|app/...), shared storage
            // (/sdcard, /storage/emulated/...), and S4 container echoes. Runs after
            // GEO_COORD: coordinates never contain slashes, paths never match geo.
            // Fail-closed: trailing punctuation (.,;!?) is redacted as part of the
            // path rather than left in place.
            Regex("""/data/(?:data|user|app|media|misc)/\S+|/storage/emulated/\S+|/sdcard/\S+"""),
            "⟦REDACTED:PATH⟧",
        ),
    )

    private val CARD_CANDIDATE = Regex("""(?<!\d)(?:\d[ \-]?){15,16}(?!\d)""")
    private const val CARD_RULE_ID = "CARD_16"
    private const val CARD_REPLACEMENT = "⟦REDACTED:CARD⟧"

    /**
     * Error-message subset: spec §6.5 (keys/tokens/URLs + private IPs) PLUS
     * GEO_COORD as beyond-spec hardening — error/log strings are a
     * coordinate-leak path (e.g. NavigationTool failures), so coordinates
     * are stripped here too. Truncated to [ERROR_MAX_CHARS] characters.
     */
    private val ERROR_RULE_IDS = setOf(
        "API_KEY_VALUE",
        "BEARER_TOKEN",
        "JSON_KEY_FIELD",
        "URL_CREDENTIAL",
        "URL_TOKEN_PARAM",
        "IPV4_PRIVATE",
        "GEO_COORD",
        "ANDROID_PATH",
    )

    const val ERROR_MAX_CHARS = 500

    /**
     * Full redaction: applies R1–R14 in order (R13 GEO is beyond-spec; R14
     * ANDROID_PATH is the S4 addition, see class KDoc).
     */
    fun redact(input: String): RedactResult {
        // Table order is load-bearing: R1/R2 observe the original text first so
        // overlapping key shapes (e.g. sk-... inside a "password" value) are
        // counted before whole-field normalization. The quoted-JSON scanner runs
        // at the R3 position; its hits are attributed to JSON_KEY_FIELD.
        var text = input
        val hits = LinkedHashMap<String, Int>()
        for (rule in RULES.subList(0, 2)) {
            val count = rule.pattern.findAll(text).count()
            if (count > 0) {
                text = rule.pattern.replace(text, rule.replacement)
            }
            hits[rule.id] = count
        }
        val (prepared, quotedJsonHits) = redactQuotedJsonSecretValues(text)
        text = prepared
        for (rule in RULES.subList(2, RULES.size)) {
            val count = rule.pattern.findAll(text).count()
            if (count > 0) {
                text = rule.pattern.replace(text, rule.replacement)
            }
            hits[rule.id] = count + if (rule.id == "JSON_KEY_FIELD") quotedJsonHits else 0
            if (rule.id == "ID_TW") {
                // R10 runs here in table position (after ID_TW, before IPV4_PRIVATE).
                val (cardText, cardCount) = applyCardRule(text)
                text = cardText
                hits[CARD_RULE_ID] = cardCount
            }
        }
        return RedactResult(text, hits)
    }

    /**
     * Stricter error-message redaction: spec §6.5 (R1–R5 + R11) plus GEO_COORD
     * (beyond-spec, see [ERROR_RULE_IDS]), truncated to [ERROR_MAX_CHARS]
     * characters.
     */
    fun redactError(input: String): String {
        // Keep the same R1/R2-then-scanner ordering as redact() so the final
        // whole-value marker choice stays consistent (scanner overwrites any
        // partial R1/R2 marker inside a quoted secret value).
        var text = input
        for (rule in RULES.subList(0, 2)) {
            if (rule.id in ERROR_RULE_IDS) {
                text = rule.pattern.replace(text, rule.replacement)
            }
        }
        text = redactQuotedJsonSecretValues(text).first
        for (rule in RULES.subList(2, RULES.size)) {
            if (rule.id in ERROR_RULE_IDS) {
                text = rule.pattern.replace(text, rule.replacement)
            }
        }
        return if (text.length > ERROR_MAX_CHARS) text.take(ERROR_MAX_CHARS) else text
    }

    /**
     * Masks values of the supported, literal JSON secret keys without parsing
     * and re-serializing the surrounding document. Valid quoted strings are
     * scanned linearly with JSON escape handling, preserving both quote
     * boundaries and all neighboring source bytes.
     *
     * For malformed quoted values, a comma followed by a quoted key and colon
     * is treated as a recoverable next-member boundary: a closing quote is
     * synthesized after the marker and that neighbor is left untouched. If no
     * such boundary exists, the value is masked to the end of its physical line
     * (or input), since there is no reliable way to distinguish a secret tail
     * from unrelated text beyond that point.
     */
    private fun redactQuotedJsonSecretValues(input: String): Pair<String, Int> {
        val out = StringBuilder(input.length)
        var copiedThrough = 0
        var cursor = 0
        var hits = 0
        // Cache each physical line end and only move forward. Re-scanning to
        // the end of a flat JSON line for every short key would be quadratic.
        var lineEnd = physicalLineEnd(input, 0)

        while (cursor < input.length) {
            while (cursor >= lineEnd && lineEnd < input.length) {
                cursor = afterLineBreak(input, lineEnd)
                lineEnd = physicalLineEnd(input, cursor)
            }
            if (cursor >= input.length) break
            if (input[cursor] != '"') {
                cursor++
                continue
            }

            val keyEnd = findUnescapedQuote(input, cursor + 1, lineEnd)
            if (keyEnd < 0) {
                cursor = afterLineBreak(input, lineEnd)
                lineEnd = physicalLineEnd(input, cursor)
                continue
            }

            if (!isSupportedSecretKey(input, cursor + 1, keyEnd)) {
                // Quoted JSON strings are opaque: escaped quotes inside ordinary
                // values must not be reinterpreted as possible field names.
                cursor = keyEnd + 1
                continue
            }

            val colon = skipJsonWhitespace(input, keyEnd + 1, input.length)
            if (colon >= input.length || input[colon] != ':') {
                cursor = keyEnd + 1
                continue
            }
            val valueOpen = skipJsonWhitespace(input, colon + 1, input.length)
            if (valueOpen >= input.length || input[valueOpen] != '"') {
                cursor = keyEnd + 1
                continue
            }

            while (valueOpen > lineEnd && lineEnd < input.length) {
                val nextLineStart = afterLineBreak(input, lineEnd)
                lineEnd = physicalLineEnd(input, nextLineStart)
            }
            val boundary = findQuotedValueBoundary(input, valueOpen, lineEnd)
            val valueContentStart = valueOpen + 1
            val valueLength = boundary.copyFrom - valueContentStart
            val isAlreadyRedacted = valueLength == JSON_REDACTED_VALUE.length &&
                input.regionMatches(
                    valueContentStart,
                    JSON_REDACTED_VALUE,
                    0,
                    JSON_REDACTED_VALUE.length,
                )
            if (valueLength > 0 && !isAlreadyRedacted) {
                out.append(input, copiedThrough, valueContentStart)
                out.append(JSON_REDACTED_VALUE)
                if (boundary.synthesizeClosingQuote) out.append('"')
                copiedThrough = boundary.copyFrom
                hits++
            }
            cursor = boundary.resumeAt
        }

        if (hits == 0) return input to 0
        out.append(input, copiedThrough, input.length)
        return out.toString() to hits
    }

    // Match the literal spellings recognized by R3, case-insensitively. Escaped
    // JSON key spellings (for example, "pass\\u0077ord") are intentionally not
    // decoded by this bounded scanner.
    private fun isSupportedSecretKey(input: String, start: Int, end: Int): Boolean =
        matchesAsciiIgnoreCase(input, start, end, "secret") ||
            matchesAsciiIgnoreCase(input, start, end, "token") ||
            matchesAsciiIgnoreCase(input, start, end, "password") ||
            matchesAsciiIgnoreCase(input, start, end, "passwd") ||
            matchesAsciiIgnoreCase(input, start, end, "auth") ||
            matchesAsciiIgnoreCase(input, start, end, "apikey") ||
            matchesAsciiIgnoreCase(input, start, end, "api_key") ||
            matchesAsciiIgnoreCase(input, start, end, "api-key")

    private fun matchesAsciiIgnoreCase(input: String, start: Int, end: Int, expected: String): Boolean {
        if (end - start != expected.length) return false
        for (offset in expected.indices) {
            if (!input[start + offset].equals(expected[offset], ignoreCase = true)) return false
        }
        return true
    }

    /** Returns the next unescaped quote before [limit], or -1. */
    private fun findUnescapedQuote(input: String, start: Int, limit: Int): Int {
        var index = start
        while (index < limit) {
            when (input[index]) {
                '\\' -> index += 2
                '"' -> return index
                else -> index++
            }
        }
        return -1
    }

    private fun skipJsonWhitespace(input: String, start: Int, limit: Int): Int {
        var index = start
        while (index < limit && isJsonWhitespace(input[index])) index++
        return index
    }

    private fun isJsonWhitespace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\r' || char == '\n'

    private fun physicalLineEnd(input: String, start: Int): Int {
        var index = start
        while (index < input.length && input[index] != '\r' && input[index] != '\n') index++
        return index
    }

    private fun afterLineBreak(input: String, lineEnd: Int): Int =
        if (lineEnd < input.length && input[lineEnd] == '\r' &&
            lineEnd + 1 < input.length && input[lineEnd + 1] == '\n'
        ) {
            lineEnd + 2
        } else if (lineEnd < input.length) {
            lineEnd + 1
        } else {
            lineEnd
        }

    private fun findQuotedValueBoundary(
        input: String,
        valueOpen: Int,
        lineEnd: Int,
    ): QuotedValueBoundary {
        var index = valueOpen + 1
        while (index < lineEnd) {
            when (input[index]) {
                '\\' -> index += 2
                '"' -> {
                    if (isJsonValueTerminatorAfter(input, index + 1)) {
                        return QuotedValueBoundary(index, index + 1, synthesizeClosingQuote = false)
                    }
                    val comma = recoveryCommaBeforeLikelyMember(
                        input,
                        index,
                        valueOpen + 1,
                        lineEnd,
                    )
                    if (comma >= 0) {
                        return QuotedValueBoundary(comma, comma, synthesizeClosingQuote = true)
                    }
                    index++
                }
                else -> index++
            }
        }
        return QuotedValueBoundary(lineEnd, lineEnd, synthesizeClosingQuote = true)
    }

    private fun isJsonValueTerminatorAfter(input: String, start: Int): Boolean {
        val next = skipJsonWhitespace(input, start, input.length)
        return next == input.length || input[next] == ',' || input[next] == '}' || input[next] == ']'
    }

    /**
     * Recover only when an unescaped quote begins a plausible following object
     * member (`comma + optional whitespace + quoted key + colon`). The scan of
     * that candidate is forward-only; unrelated prose is not treated as a member.
     */
    private fun recoveryCommaBeforeLikelyMember(
        input: String,
        possibleKeyStart: Int,
        lowerBound: Int,
        lineEnd: Int,
    ): Int {
        val possibleKeyEnd = findUnescapedQuote(input, possibleKeyStart + 1, lineEnd)
        if (possibleKeyEnd < 0) return -1
        val colon = skipJsonWhitespace(input, possibleKeyEnd + 1, lineEnd)
        if (colon >= lineEnd || input[colon] != ':') return -1

        var comma = possibleKeyStart - 1
        while (comma >= lowerBound && (input[comma] == ' ' || input[comma] == '\t')) comma--
        return if (comma >= lowerBound && input[comma] == ',') comma else -1
    }

    private fun applyCardRule(text: String): Pair<String, Int> {
        var count = 0
        val out = StringBuilder(text.length)
        var cursor = 0
        for (match in CARD_CANDIDATE.findAll(text)) {
            var end = match.value.length
            while (end > 0 && (match.value[end - 1] == ' ' || match.value[end - 1] == '-')) {
                end--
            }
            val digits = match.value.substring(0, end).filter { it.isDigit() }
            if (digits.length in 15..16 && luhnOk(digits)) {
                out.append(text, cursor, match.range.first)
                out.append(CARD_REPLACEMENT)
                cursor = match.range.first + end
                count++
            }
        }
        out.append(text, cursor, text.length)
        return out.toString() to count
    }

    internal fun luhnOk(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (double) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            double = !double
        }
        return sum % 10 == 0
    }
}

/** Redacted text plus per-rule hit counts. */
data class RedactResult(val text: String, val hits: Map<String, Int>)
