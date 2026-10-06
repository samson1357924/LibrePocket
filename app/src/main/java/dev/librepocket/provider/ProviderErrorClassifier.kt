package dev.librepocket.provider

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Retryable vs fatal, per SPEC §5.2. */
enum class FailureKind { RETRYABLE, FATAL }

/**
 * Pure classifier: transient transport problems and rate-limit/server errors
 * are [FailureKind.RETRYABLE]; auth, billing, protocol-shape and policy
 * errors are [FailureKind.FATAL].
 *
 * Heuristics on [bodySnippet] are case-insensitive. Explicit FATAL markers
 * (auth/billing, policy rejections) win over everything; explicit RETRYABLE
 * markers win over an ambiguous/unknown code.
 */
object ProviderErrorClassifier {
    private val fatalBodyMarkers = listOf(
        "insufficient_quota",
        "billing",
        "invalid_api_key",
        "unauthorized",
        "image_too_large",
        "sse_line_too_long",
    )
    private val retryableBodyMarkers = listOf(
        "rate_limit",
        "rate-limited",
        "overloaded",
        "timeout",
        "temporarily",
        "sse_truncated",
    )

    fun classify(httpCode: Int?, ioError: Throwable?, bodySnippet: String?): FailureKind {
        val body = bodySnippet?.lowercase().orEmpty()
        if (fatalBodyMarkers.any { body.contains(it) }) return FailureKind.FATAL

        if (httpCode != null) return classifyCode(httpCode, body)

        if (ioError != null) return classifyError(ioError, body)

        if (retryableBodyMarkers.any { body.contains(it) }) return FailureKind.RETRYABLE
        // Unknown cause (e.g. clean EOF without [DONE]): retry, per §3.2.
        return FailureKind.RETRYABLE
    }

    private fun classifyCode(code: Int, body: String): FailureKind {
        if (retryableBodyMarkers.any { body.contains(it) } && code != 501) {
            // A retryable marker only softens ambiguous codes, never auth/billing.
            if (code !in FATAL_CODES) return FailureKind.RETRYABLE
        }
        if (code in RETRYABLE_CODES) return FailureKind.RETRYABLE
        if (code in FATAL_CODES) return FailureKind.FATAL
        return if (code in 500..599) FailureKind.RETRYABLE else FailureKind.FATAL
    }

    private fun classifyError(error: Throwable, body: String): FailureKind {
        if (retryableBodyMarkers.any { body.contains(it) }) return FailureKind.RETRYABLE
        var cur: Throwable? = error
        while (cur != null) {
            when (cur) {
                is SSLException,
                is java.security.cert.CertificateException,
                is IllegalArgumentException,
                is java.net.MalformedURLException,
                is java.net.URISyntaxException,
                -> return FailureKind.FATAL
                is SocketTimeoutException,
                is ConnectException,
                is NoRouteToHostException,
                is UnknownHostException,
                is EOFException,
                is InterruptedIOException,
                -> return FailureKind.RETRYABLE
                is IOException -> {
                    val msg = cur.message?.lowercase().orEmpty()
                    if (msg.contains("unexpected end of stream") ||
                        msg.contains("eof") ||
                        msg.contains("connection reset") ||
                        msg.contains("broken pipe") ||
                        msg.contains("stream was reset")
                    ) {
                        return FailureKind.RETRYABLE
                    }
                }
            }
            cur = cur.cause
        }
        return FailureKind.RETRYABLE
    }

    private val RETRYABLE_CODES = setOf(408, 409, 425, 429)
    private val FATAL_CODES = setOf(400, 401, 402, 403, 422, 501)
}
