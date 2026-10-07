package dev.librepocket.redact

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for whole-value JSON secret redaction. */
class SecretValueRedactionRegressionTest {

    private val json = Json

    private fun assertWholeSecretValueRedacted(secret: String) {
        val neighbor = "普通文字 & query?x=1 stays visible"
        val input = buildJsonObject {
            put("password", secret)
            put("備註", neighbor)
        }.toString()

        // Valid JSON input must remain valid JSON; only the secret value changes.
        json.parseToJsonElement(input)
        val result = Redactor.redact(input)
        val output = json.parseToJsonElement(result.text).jsonObject

        assertEquals("⟦REDACTED⟧", output.getValue("password").jsonPrimitive.content)
        assertEquals(neighbor, output.getValue("備註").jsonPrimitive.content)
        assertFalse("entire secret leaked: ${result.text}", result.text.contains(secret))
        assertEquals(1, result.hits["JSON_KEY_FIELD"])
        assertTrue("legacy JSON redaction marker changed: ${result.text}", "⟦REDACTED⟧" in result.text)
    }

    @Test
    fun quotedSecretWithWhitespaceAmpersandAndQuestionMarkIsRedactedAsOneValue() {
        assertWholeSecretValueRedacted("fake-alpha beta gamma&tail?x=three")
    }

    @Test
    fun quotedSecretWithEscapedQuoteBackslashAndUnicodeIsRedactedAsOneValue() {
        assertWholeSecretValueRedacted("""fake prefix with "quoted text" and \\ slash 雪秘密🔐""")
    }

    @Test
    fun longQuotedSecretWithRepeatedEscapesIsRedactedAsOneValue() {
        val secret = buildString {
            repeat(8_192) { append("segment \\\"quoted\\\" \\\\ 雪;") }
        }
        assertWholeSecretValueRedacted(secret)
    }

    @Test
    fun flatDocumentWithManyShortOrdinaryKeysStillRedactsFinalSecret() {
        val input = buildString {
            append('{')
            repeat(2_048) { index ->
                if (index > 0) append(',')
                append("\"k$index\":\"v\"")
            }
            append(",\"password\":\"synthetic final secret tail\"}")
        }

        val output = json.parseToJsonElement(Redactor.redact(input).text).jsonObject

        assertEquals("v", output.getValue("k2047").jsonPrimitive.content)
        assertEquals("⟦REDACTED⟧", output.getValue("password").jsonPrimitive.content)
    }

    @Test
    fun malformedQuotedValueRecoversAtLikelyNeighborFieldWithoutSwallowingIt() {
        // The password's closing quote is missing. The comma + quoted key + colon
        // is a bounded recovery point; the unrelated note must survive.
        val malformed = """{"password":"synthetic broken secret, "note":"keep this neighbor"}"""

        val result = Redactor.redact(malformed)
        val repaired = json.parseToJsonElement(result.text).jsonObject

        assertEquals("⟦REDACTED⟧", repaired.getValue("password").jsonPrimitive.content)
        assertEquals("keep this neighbor", repaired.getValue("note").jsonPrimitive.content)
        assertFalse("malformed secret leaked: ${result.text}", result.text.contains("synthetic broken secret"))
        assertEquals(1, result.hits["JSON_KEY_FIELD"])
    }

    @Test
    fun malformedUnterminatedValueStopsAtLineBoundary() {
        val malformed = "{\"password\":\"synthetic unterminated secret\nnext-line neighbor remains\"}"

        val output = Redactor.redact(malformed).text

        assertFalse("malformed secret leaked: $output", output.contains("synthetic unterminated secret"))
        assertTrue("next physical line was swallowed: $output", output.contains("next-line neighbor remains"))
    }

    @Test
    fun emptyLinebreakAndTrailingNewlineInputsAreSafeInBothModes() {
        for (input in listOf("", "\n", "\r", "\r\n", "ordinary prose\n", "first line\nsecond line\n")) {
            assertEquals(input, Redactor.redact(input).text)
            assertEquals(input, Redactor.redactError(input))
        }

        val multiline = "\n{\"password\":\"synthetic multiline secret\",\"note\":\"kept\"}\nnext-line neighbor\n"
        val fullyRedacted = Redactor.redact(multiline).text
        val errorRedacted = Redactor.redactError(multiline)
        for (output in listOf(fullyRedacted, errorRedacted)) {
            assertFalse("multiline secret leaked: $output", output.contains("synthetic multiline secret"))
            assertTrue("multiline neighbor was lost: $output", output.contains("next-line neighbor\n"))
            val jsonLine = output.lineSequence().first { it.startsWith('{') }
            assertEquals("⟦REDACTED⟧", json.parseToJsonElement(jsonLine).jsonObject
                .getValue("password").jsonPrimitive.content)
        }
    }

    @Test
    fun shortQuotedSecretIsStillRedacted() {
        assertWholeSecretValueRedacted("xy")
        assertWholeSecretValueRedacted("密")
    }

    @Test
    fun everyLiteralSecretKeyAliasRedactsWholeValuesInFullAndErrorModes() {
        val aliases = listOf(
            "api_key", "API-KEY", "apiKey", "Secret", "TOKEN", "PassWord", "PASSWD", "Auth",
        )
        val secret = "alpha beta&tail?x=three"

        for (key in aliases) {
            val input = """{"$key":"$secret","neighbor":"kept"}"""
            val full = Redactor.redact(input)
            val fullJson = json.parseToJsonElement(full.text).jsonObject
            assertEquals("⟦REDACTED⟧", fullJson.getValue(key).jsonPrimitive.content)
            assertEquals("kept", fullJson.getValue("neighbor").jsonPrimitive.content)
            assertFalse("$key leaked in full redaction: ${full.text}", full.text.contains("beta&tail"))
            assertEquals("$key was not counted", 1, full.hits["JSON_KEY_FIELD"])

            val error = Redactor.redactError("synthetic error: $input")
            val errorJson = json.parseToJsonElement(error.substringAfter("synthetic error: ")).jsonObject
            assertEquals("⟦REDACTED⟧", errorJson.getValue(key).jsonPrimitive.content)
            assertEquals("kept", errorJson.getValue("neighbor").jsonPrimitive.content)
            assertFalse("$key leaked in error redaction: $error", error.contains("beta&tail"))
        }
    }

    @Test
    fun redactionIsIdempotentAndPreservesStableRuleIdsAndOrder() {
        val input = buildJsonObject {
            put("password", "fake alpha beta&tail?x=three")
            put("備註", "可見文字")
        }.toString()

        val once = Redactor.redact(input)
        val twice = Redactor.redact(once.text)

        assertEquals(once.text, twice.text)
        assertEquals(
            listOf(
                "API_KEY_VALUE", "BEARER_TOKEN", "JSON_KEY_FIELD", "URL_CREDENTIAL",
                "URL_TOKEN_PARAM", "EMAIL", "PHONE_GENERIC", "PHONE_INTL", "ID_TW",
                "CARD_16", "IPV4_PRIVATE", "ANDROID_ID_LIKE", "GEO_COORD", "ANDROID_PATH",
            ),
            once.hits.keys.toList(),
        )
        assertEquals(1, once.hits["JSON_KEY_FIELD"])
        assertEquals("⟦REDACTED⟧", json.parseToJsonElement(once.text).jsonObject
            .getValue("password").jsonPrimitive.content)
    }

    @Test
    fun urlQueryBoundariesAndNonSecretNeighborsRemainIntact() {
        val input = "https://example.test/p?token=fakeToken987654&x=keep&label=plain"
        val expected = "https://example.test/p?token=⟦REDACTED⟧&x=keep&label=plain"
        assertEquals(expected, Redactor.redact(input).text)
        assertEquals("the word password in ordinary prose", Redactor.redact("the word password in ordinary prose").text)
        assertEquals(
            "https://example.test/p?x=one&label=a?b",
            Redactor.redact("https://example.test/p?x=one&label=a?b").text,
        )
    }

    @Test
    fun errorRedactionRemovesTheWholeQuotedValueAndIsIdempotent() {
        val input = "request failed: body=" + buildJsonObject {
            put("password", "fake error alpha beta&tail?x=three")
            put("requestId", "safe-request-42")
        }.toString()

        val once = Redactor.redactError(input)
        val twice = Redactor.redactError(once)

        assertFalse("error redaction leaked a secret tail: $once", once.contains("beta&tail"))
        assertFalse("error redaction leaked a secret prefix: $once", once.contains("fake error"))
        assertTrue("unrelated request id was lost: $once", once.contains("safe-request-42"))
        assertTrue("legacy JSON redaction marker changed: $once", once.contains("⟦REDACTED⟧"))
        assertEquals(once, twice)
    }
}
