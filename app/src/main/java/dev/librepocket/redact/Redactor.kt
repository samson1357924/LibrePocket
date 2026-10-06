package dev.librepocket.redact

import kotlin.text.RegexOption.IGNORE_CASE

/**
 * P1 redaction table (M5, spec §6: R1–R12) plus R13 GEO_COORD, a beyond-spec
 * hardening addition (B3: coordinates must not reach transcript/audit/export).
 *
 * Pure functions; zero Android dependencies so plain JVM unit tests can run them.
 * Rules apply sequentially R1..R13; [RedactResult.hits] counts matches per rule
 * in table order (rules with zero hits are included with count 0).
 */
object Redactor {

    private data class Rule(val id: String, val pattern: Regex, val replacement: String)

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
            // Value stops at whitespace/quote/comma/brace AND at &/? so URL query
            // strings stay R5's domain (otherwise R3 would swallow "&x=1" tails).
            Regex(
                """("?(api[_-]?key|secret|token|password|passwd|auth)"?\s*[:=]\s*"?)[^",\s}&?]{4,}""",
                IGNORE_CASE,
            ),
            "\$1⟦REDACTED⟧",
        ),
        Rule(
            "URL_CREDENTIAL",
            Regex("""(https?://)[^/\s:@]+:[^/\s@]+@""", IGNORE_CASE),
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
    )

    const val ERROR_MAX_CHARS = 500

    /** Full redaction: applies R1–R13 in order (R13 GEO is beyond-spec; see class KDoc). */
    fun redact(input: String): RedactResult {
        var text = input
        val hits = LinkedHashMap<String, Int>()
        for (rule in RULES) {
            val count = rule.pattern.findAll(text).count()
            if (count > 0) {
                text = rule.pattern.replace(text, rule.replacement)
            }
            hits[rule.id] = count
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
        var text = input
        for (rule in RULES) {
            if (rule.id in ERROR_RULE_IDS) {
                text = rule.pattern.replace(text, rule.replacement)
            }
        }
        return if (text.length > ERROR_MAX_CHARS) text.take(ERROR_MAX_CHARS) else text
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
