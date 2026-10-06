package dev.librepocket.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2: provider error paths must pass through [Redactor.redactError] —
 * tokens / URL secrets / private IPs never leak into
 * [StreamEvent.Failed] / [ProviderFailure] messages.
 * Two-level truncation preserved: body snippet 200, whole error 500.
 */
class ProviderErrorRedactionTest {

    @Test
    fun httpSnippetWithApiKeyIsRedacted() {
        val raw = """{"error":{"message":"invalid key sk-testSECRET1234567890 rejected"}}"""
        val msg = redactedError("HTTP 401", raw)
        assertFalse("raw key leaked: $msg", msg.contains("sk-testSECRET1234567890"))
        assertTrue("expected redaction marker: $msg", msg.contains("⟦REDACTED"))
        assertTrue(msg.startsWith("HTTP 401"))
    }

    @Test
    fun transportBearerAndPrivateIpAreRedacted() {
        val msg = redactedError("SSE_TRANSPORT", "Bearer abcDEF1234567890 from 192.168.0.7 reset")
        assertFalse(msg.contains("abcDEF1234567890"))
        assertFalse(msg.contains("192.168.0.7"))
        assertTrue(msg.contains("⟦REDACTED"))
    }

    @Test
    fun urlTokenParamIsRedacted() {
        val msg = redactedError(
            "SSE_TRANSPORT",
            "fetch failed https://api.example.com/v1?api_key=SECRET1234567890&x=1",
        )
        assertFalse(msg.contains("SECRET1234567890"))
        assertTrue(msg.contains("⟦REDACTED⟧"))
    }

    @Test
    fun errorMessageKeepsTwoLevelTruncation() {
        // Body snippet caps at ~200, whole error caps at 500 (Redactor.ERROR_MAX_CHARS).
        val msg = redactedError("HTTP 500", "x".repeat(5000))
        assertTrue("expected <=500 chars, got ${msg.length}", msg.length <= 500)
    }

    @Test
    fun mappersRedactServerErrorMessages() {
        val key = "sk-testSECRET1234567890"
        val respFailed = ResponsesMapper().mapPayload(
            """{"type":"response.failed","response":{"error":{"message":"bad $key"}}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(respFailed.message.contains(key))

        val anthFailed = AnthropicMapper().mapPayload(
            """{"type":"error","error":{"message":"token=$key denied"}}""",
        ).filterIsInstance<StreamEvent.Failed>().single()
        assertFalse(anthFailed.message.contains(key))
    }
}
