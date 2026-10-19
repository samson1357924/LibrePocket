package dev.librepocket.provider

import java.io.EOFException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ProviderErrorClassifier] table tests (SPEC §5.2).
 * Every row of the retryable/fatal table plus the case-insensitive
 * body heuristics and the null-everything default.
 */
class ProviderErrorTest {

    private fun code(code: Int, body: String? = null) =
        ProviderErrorClassifier.classify(code, null, body)

    @Test
    fun transientHttpCodesAreRetryable() {
        for (c in listOf(408, 409, 425, 429, 500, 502, 503, 504)) {
            assertEquals("code $c", FailureKind.RETRYABLE, code(c))
        }
    }

    @Test
    fun authBillingAndShapeCodesAreFatal() {
        for (c in listOf(400, 401, 402, 403, 422, 501, 404)) {
            assertEquals("code $c", FailureKind.FATAL, code(c))
        }
    }

    @Test
    fun fatalBodyMarkersWinOverRetryableCodes() {
        assertEquals(
            FailureKind.FATAL,
            code(429, """{"error":{"message":"invalid_api_key: check your key"}}"""),
        )
        assertEquals(
            FailureKind.FATAL,
            code(500, """{"error":"insufficient_quota: billing hard limit"}}"""),
        )
    }

    @Test
    fun fatalCodesWinOverRetryableBodyMarkers() {
        assertEquals(
            FailureKind.FATAL,
            code(401, """{"error":"rate_limit_exceeded, slow down"}"""),
        )
    }

    @Test
    fun retryableBodyMarkersSoftenAmbiguousCodes() {
        assertEquals(
            FailureKind.RETRYABLE,
            code(500, "server overloaded, try again"),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, null, "SSE_TRUNCATED"),
        )
    }

    @Test
    fun bodyHeuristicsAreCaseInsensitive() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, "INVALID_API_KEY"),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, "Unauthorized"),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, null, "Rate_Limit hit"),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(503, null, "Temporarily Unavailable"),
        )
    }

    @Test
    fun policyRejectionMarkersAreFatal() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, "IMAGE_TOO_LARGE"),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, "SSE_LINE_TOO_LONG"),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, "TEXT_TOO_LARGE"),
        )
    }

    @Test
    fun transportErrorsSplit() {
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, SocketTimeoutException("read timed out"), null),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, UnknownHostException("dns"), null),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, EOFException("end"), null),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, java.io.IOException("unexpected end of stream"), null),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, SSLHandshakeException("PKIX path building failed"), null),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, MalformedURLException("no protocol"), null),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, IllegalArgumentException("bad url"), null),
        )
    }

    @Test
    fun nullEverythingDefaultsRetryable() {
        assertEquals(FailureKind.RETRYABLE, ProviderErrorClassifier.classify(null, null, null))
        assertEquals(FailureKind.RETRYABLE, ProviderErrorClassifier.classify(null, null, ""))
    }

    // ---- P1-4: structured code/type token sets ----

    @Test
    fun structuredFatalTokensAreFatalForCodeAndType() {
        val fatalTokens = listOf(
            "invalid_api_key",
            "insufficient_quota",
            "billing_hard_limit_reached",
            "authentication_error",
            "permission_error",
            "invalid_request_error",
            "not_found_error",
            "request_too_large",
            "content_filter",
            "content_policy_violation",
            "invalid_prompt",
            "model_not_found",
            "context_length_exceeded",
        )
        for (token in fatalTokens) {
            assertEquals(
                "code $token",
                FailureKind.FATAL,
                ProviderErrorClassifier.classify(null, null, null, structuredCode = token),
            )
            assertEquals(
                "type $token",
                FailureKind.FATAL,
                ProviderErrorClassifier.classify(null, null, null, structuredType = token),
            )
        }
    }

    @Test
    fun structuredRetryableTokensSoftenOnlyAmbiguousCodes() {
        val retryableTokens = listOf(
            "rate_limit_exceeded",
            "rate_limit_error",
            "overloaded_error",
            "engine_overloaded_error",
            "server_error",
            "api_error",
            "timeout",
            "service_unavailable",
        )
        for (token in retryableTokens) {
            assertEquals(
                "type $token",
                FailureKind.RETRYABLE,
                ProviderErrorClassifier.classify(500, null, null, structuredType = token),
            )
        }
        // Never FATAL_CODES, never 501.
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(400, null, null, structuredType = "overloaded_error"),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(401, null, null, structuredType = "service_unavailable"),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(501, null, null, structuredCode = "overloaded_error"),
        )
    }

    @Test
    fun structuredFatalTokensWinOverAmbiguousHttpCodes() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(429, null, null, structuredType = "invalid_api_key"),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(500, null, null, structuredCode = "billing_hard_limit_reached"),
        )
    }

    @Test
    fun retryableTokensSoftenOnlyAmbiguousCodes() {
        // 418 is the discriminating ambiguous code: in neither explicit set and
        // below 5xx, so alone it takes the FATAL default; a retryable
        // body/structured marker is the documented tie-breaker that flips it
        // RETRYABLE (this is the only observable effect of the branch).
        assertEquals(FailureKind.FATAL, ProviderErrorClassifier.classify(418, null, null))
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(418, null, "teapot"),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(418, null, "overloaded, try again"),
        )
        for (token in listOf("overloaded_error", "rate_limit_exceeded", "timeout")) {
            assertEquals(
                "code $token",
                FailureKind.RETRYABLE,
                ProviderErrorClassifier.classify(418, null, null, structuredCode = token),
            )
            assertEquals(
                "type $token",
                FailureKind.RETRYABLE,
                ProviderErrorClassifier.classify(418, null, null, structuredType = token),
            )
        }
        // The guard still wins over the marker: never FATAL_CODES, never 501.
        for (c in listOf(400, 401, 402, 403, 422)) {
            assertEquals(
                "code $c must never be softened",
                FailureKind.FATAL,
                ProviderErrorClassifier.classify(c, null, null, structuredCode = "overloaded_error"),
            )
            assertEquals(
                "code $c must never be softened by a body marker",
                FailureKind.FATAL,
                ProviderErrorClassifier.classify(c, null, "overloaded, try again"),
            )
        }
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(501, null, null, structuredCode = "overloaded_error"),
        )
    }

    @Test
    fun fatalMarkersWinOverRetryableMarkers() {
        // Body-level: one fatal marker is enough even next to retryables.
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(
                500,
                null,
                "rate_limit_exceeded then invalid_api_key",
            ),
        )
        // Structured-level: a fatal token wins over a retryable token regardless
        // of field (code vs type) and HTTP code.
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(
                500,
                null,
                null,
                structuredCode = "invalid_api_key",
                structuredType = "overloaded_error",
            ),
        )
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(
                429,
                null,
                null,
                structuredCode = "rate_limit_exceeded",
                structuredType = "context_length_exceeded",
            ),
        )
    }

    @Test
    fun deterministicStreamContractFailuresAreFatal() {
        for (marker in listOf(
            "RESPONSES_OUTPUT_MISMATCH",
            "responses_identity_missing",
            "responses_output_mismatch",
        )) {
            assertEquals(
                marker,
                FailureKind.FATAL,
                ProviderErrorClassifier.classify(null, null, marker),
            )
        }
    }

    @Test
    fun structuredTokensAreTrimmedLowercasedExactMatches() {
        assertEquals(
            FailureKind.FATAL,
            ProviderErrorClassifier.classify(null, null, null, structuredCode = "  Authentication_Error "),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(503, null, null, structuredCode = "Overloaded_Error"),
        )
        // Exact-match sets: near-miss/substring tokens stay unknown -> retryable.
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, null, null, structuredCode = "auth_error"),
        )
        assertEquals(
            FailureKind.RETRYABLE,
            ProviderErrorClassifier.classify(null, null, null, structuredCode = "invalid_request_error_extra"),
        )
    }
}
