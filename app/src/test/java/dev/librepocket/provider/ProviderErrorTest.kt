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
}
