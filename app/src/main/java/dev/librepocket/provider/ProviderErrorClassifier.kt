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
 *
 * Structured provider error envelopes (P1-4) contribute two normalized
 * exact-match token sets on top of the body heuristics:
 * - FATAL tokens (`invalid_api_key`, `insufficient_quota`,
 *   `billing_hard_limit_reached`, `authentication_error`, `permission_error`,
 *   `invalid_request_error`, `not_found_error`, `request_too_large`,
 *   `content_filter`, `content_policy_violation`, `invalid_prompt`,
 *   `model_not_found`, `context_length_exceeded`) are checked in the same
 *   precedence slot as the fatal body markers (before any HTTP code or IO
 *   error), so a 429-wrapped `invalid_api_key` still fails closed.
 * - RETRYABLE tokens (`rate_limit_exceeded`, `rate_limit_error`,
 *   `overloaded_error`, `engine_overloaded_error`, `server_error`, `api_error`,
 *   `timeout`, `service_unavailable`) only soften an ambiguous HTTP code, with
 *   the same guard as the retryable body markers: never [FATAL_CODES], never
 *   501. A 400 with `overloaded_error` stays FATAL; a 500 with it retries; an
 *   ambiguous code such as 418 flips from its FATAL default to RETRYABLE.
 *
 * Tokens are matched exactly after trim + lowercase (not substring), so
 * `invalid_request_error` cannot be softened by an unrelated retryable
 * substring and unknown tokens default to retryable per §3.2.
 */
object ProviderErrorClassifier {
    private val fatalBodyMarkers = listOf(
        "insufficient_quota",
        "billing",
        "invalid_api_key",
        "unauthorized",
        "image_too_large",
        "sse_line_too_long",
        "sse_frame_too_large",
        "tool_args_too_large",
        "text_too_large",
        // Phase 2 deterministic stream-contract failures: a streamed/terminal
        // text contradiction or an unidentifiable function-call frame must
        // never be retried if the message is later routed through the
        // classifier (the mapper emits them non-retryable directly today).
        "responses_output_mismatch",
        "responses_identity_missing",
    )
    private val retryableBodyMarkers = listOf(
        "rate_limit",
        "rate-limited",
        "overloaded",
        "timeout",
        "temporarily",
        "sse_truncated",
    )
    private val fatalStructuredTokens = setOf(
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
    private val retryableStructuredTokens = setOf(
        "rate_limit_exceeded",
        "rate_limit_error",
        "overloaded_error",
        "engine_overloaded_error",
        "server_error",
        "api_error",
        "timeout",
        "service_unavailable",
    )

    fun classify(
        httpCode: Int?,
        ioError: Throwable?,
        bodySnippet: String?,
        structuredCode: String? = null,
        structuredType: String? = null,
    ): FailureKind {
        val body = bodySnippet?.lowercase().orEmpty()
        if (fatalBodyMarkers.any { body.contains(it) }) return FailureKind.FATAL
        val codeToken = structuredCode.normalizedToken()
        val typeToken = structuredType.normalizedToken()
        if (isFatalStructured(codeToken) || isFatalStructured(typeToken)) {
            return FailureKind.FATAL
        }

        if (httpCode != null) return classifyCode(httpCode, body, codeToken, typeToken)

        if (ioError != null) return classifyError(ioError, body)

        if (retryableBodyMarkers.any { body.contains(it) }) return FailureKind.RETRYABLE
        if (isRetryableStructured(codeToken) || isRetryableStructured(typeToken)) {
            return FailureKind.RETRYABLE
        }
        // Unknown cause (e.g. clean EOF without [DONE]): retry, per §3.2.
        return FailureKind.RETRYABLE
    }

    private fun String?.normalizedToken(): String? =
        this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun isFatalStructured(token: String?): Boolean =
        token != null && token in fatalStructuredTokens

    private fun isRetryableStructured(token: String?): Boolean =
        token != null && token in retryableStructuredTokens

    private fun classifyCode(
        code: Int,
        body: String,
        structuredCode: String?,
        structuredType: String?,
    ): FailureKind {
        val retryableStructured =
            isRetryableStructured(structuredCode) || isRetryableStructured(structuredType)
        if ((retryableBodyMarkers.any { body.contains(it) } || retryableStructured) && code != 501) {
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
    private val FATAL_CODES = setOf(400, 401, 402, 403, 404, 422, 501)
}
