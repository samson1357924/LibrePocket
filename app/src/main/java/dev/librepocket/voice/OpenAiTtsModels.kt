package dev.librepocket.voice

import dev.librepocket.redact.Redactor
import java.io.IOException

/**
 * OpenAI Text-to-Speech (TTS) request and response data models, constants, and error definitions.
 */
object OpenAiTtsModels {
    const val DEFAULT_MODEL = "tts-1"
    const val MODEL_TTS_1 = "tts-1"
    const val MODEL_TTS_1_HD = "tts-1-hd"

    const val DEFAULT_VOICE = "alloy"
    const val VOICE_ALLOY = "alloy"
    const val VOICE_ECHO = "echo"
    const val VOICE_FABLE = "fable"
    const val VOICE_ONYX = "onyx"
    const val VOICE_NOVA = "nova"
    const val VOICE_SHIMMER = "shimmer"

    val SUPPORTED_VOICES = setOf(
        VOICE_ALLOY,
        VOICE_ECHO,
        VOICE_FABLE,
        VOICE_ONYX,
        VOICE_NOVA,
        VOICE_SHIMMER,
    )

    const val DEFAULT_RESPONSE_FORMAT = "mp3"
    const val FORMAT_MP3 = "mp3"
    const val FORMAT_OPUS = "opus"
    const val FORMAT_AAC = "aac"
    const val FORMAT_FLAC = "flac"
    const val FORMAT_WAV = "wav"
    const val FORMAT_PCM = "pcm"

    val SUPPORTED_RESPONSE_FORMATS = setOf(
        FORMAT_MP3,
        FORMAT_OPUS,
        FORMAT_AAC,
        FORMAT_FLAC,
        FORMAT_WAV,
        FORMAT_PCM,
    )

    const val DEFAULT_SPEED = 1.0f
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f
    const val MAX_INPUT_LENGTH = 4096

    const val DEFAULT_BASE_URL = "https://api.openai.com"
    const val MAX_AUDIO_BYTES = 25 * 1024 * 1024 // 25 MB
}

/**
 * Request configuration for OpenAI TTS synthesis.
 *
 * @param input Text to synthesize, maximum 4096 characters (OpenAI limit).
 * @param model Model identifier (default "tts-1", supports "tts-1-hd").
 * @param voice Voice name (default "alloy", supports "echo", "fable", "onyx", "nova", "shimmer", etc.).
 * @param responseFormat Audio format (default "mp3", supports "opus", "aac", "flac", "wav", "pcm").
 * @param speed Audio speed between 0.25f and 4.0f (default 1.0f).
 */
data class OpenAiTtsRequest(
    val input: String,
    val model: String = DEFAULT_MODEL,
    val voice: String = DEFAULT_VOICE,
    val responseFormat: String = DEFAULT_RESPONSE_FORMAT,
    val speed: Float = DEFAULT_SPEED,
) {
    companion object {
        const val DEFAULT_MODEL = OpenAiTtsModels.DEFAULT_MODEL
        const val MODEL_TTS_1 = OpenAiTtsModels.MODEL_TTS_1
        const val MODEL_TTS_1_HD = OpenAiTtsModels.MODEL_TTS_1_HD

        const val DEFAULT_VOICE = OpenAiTtsModels.DEFAULT_VOICE
        val SUPPORTED_VOICES = OpenAiTtsModels.SUPPORTED_VOICES

        const val DEFAULT_RESPONSE_FORMAT = OpenAiTtsModels.DEFAULT_RESPONSE_FORMAT
        val SUPPORTED_RESPONSE_FORMATS = OpenAiTtsModels.SUPPORTED_RESPONSE_FORMATS

        const val DEFAULT_SPEED = OpenAiTtsModels.DEFAULT_SPEED
        const val MIN_SPEED = OpenAiTtsModels.MIN_SPEED
        const val MAX_SPEED = OpenAiTtsModels.MAX_SPEED
        const val MAX_INPUT_LENGTH = OpenAiTtsModels.MAX_INPUT_LENGTH
    }

    init {
        if (input.length > MAX_INPUT_LENGTH) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Input text exceeds maximum allowed length of $MAX_INPUT_LENGTH characters (got ${input.length})",
            )
        }
        if (input.isBlank()) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Input text must not be blank",
            )
        }
        if (model.isBlank()) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Model must not be blank",
            )
        }
        if (voice.isBlank()) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Voice must not be blank",
            )
        }
        if (responseFormat.isBlank()) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Response format must not be blank",
            )
        }
        if (speed !in MIN_SPEED..MAX_SPEED) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Speed must be between $MIN_SPEED and $MAX_SPEED (got $speed)",
            )
        }
    }
}

/**
 * Result of successful OpenAI TTS synthesis.
 *
 * @param audioBytes Raw synthesized audio binary data.
 * @param contentType MIME type returned by the endpoint (e.g. "audio/mpeg").
 */
data class OpenAiTtsResult(
    val audioBytes: ByteArray,
    val contentType: String,
) {
    val audio: ByteArray get() = audioBytes

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OpenAiTtsResult
        if (!audioBytes.contentEquals(other.audioBytes)) return false
        if (contentType != other.contentType) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioBytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

/**
 * Categorical failure codes for OpenAI TTS operations.
 */
enum class OpenAiTtsErrorCode {
    MISSING_KEY,
    INVALID_INPUT,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMIT,
    SERVER_ERROR,
    CLIENT_ERROR,
    NETWORK_ERROR,
    RESPONSE_TOO_LARGE,
    CANCELLED,
    UNEXPECTED,
}

/**
 * Safe, sanitized exception thrown when OpenAI TTS fails.
 *
 * Security guarantees:
 * - [safeMessage] is sanitized via [Redactor.redactError] and [activeKey] masking.
 * - [cause] is never chained as a raw exception to avoid leaking sensitive headers, URLs, or credentials.
 *   If [cause] is provided, it is converted to a sanitized [IOException] with a redacted message.
 *
 * @param statusCode HTTP response status code (null if the request never completed or failed before HTTP exchange).
 * @param errorCode Classified error category.
 * @param retryable Whether the failure is safe and worthwhile to retry.
 * @param safeMessage Sanitized error message, guaranteed free of credentials via [Redactor.redactError] and [activeKey] masking.
 * @param cause Underlying exception, safely sanitized without retaining raw exception references.
 * @param activeKey The active API key string used in the current call (if any) to ensure exact masking of non-standard keys.
 */
class OpenAiTtsException(
    val statusCode: Int? = null,
    val errorCode: OpenAiTtsErrorCode,
    val retryable: Boolean,
    safeMessage: String,
    cause: Throwable? = null,
    activeKey: String? = null,
) : IOException(
    sanitizeMessage(safeMessage, activeKey),
    sanitizeCause(cause, activeKey),
) {
    // Secondary constructor for 5-argument calls without activeKey
    constructor(
        statusCode: Int? = null,
        errorCode: OpenAiTtsErrorCode,
        retryable: Boolean,
        safeMessage: String,
        cause: Throwable? = null,
    ) : this(
        statusCode = statusCode,
        errorCode = errorCode,
        retryable = retryable,
        safeMessage = safeMessage,
        cause = cause,
        activeKey = null,
    )

    val safeMessage: String = message.orEmpty()

    companion object {
        internal fun sanitizeMessage(safeMessage: String, activeKey: String? = null): String {
            var msg = safeMessage
            if (!activeKey.isNullOrEmpty()) {
                val trimmed = activeKey.trim()
                if (trimmed.isNotEmpty()) {
                    msg = msg.replace(trimmed, "⟦REDACTED:API_KEY⟧", ignoreCase = true)
                }
                msg = msg.replace(activeKey, "⟦REDACTED:API_KEY⟧", ignoreCase = true)
            }
            return Redactor.redactError(msg)
        }

        internal fun sanitizeCause(cause: Throwable?, activeKey: String? = null): Throwable? {
            if (cause == null) return null
            val rawMsg = cause.message ?: cause.javaClass.simpleName
            val safeMsg = sanitizeMessage(rawMsg, activeKey)
            val prefix = if (cause.javaClass == IOException::class.java || safeMsg.startsWith(cause.javaClass.simpleName)) {
                ""
            } else {
                "${cause.javaClass.simpleName}: "
            }
            val sanitized = IOException(sanitizeMessage("$prefix$safeMsg", activeKey))
            sanitized.stackTrace = emptyArray()
            return sanitized
        }
    }

    override fun toString(): String =
        "OpenAiTtsException(statusCode=$statusCode, errorCode=$errorCode, retryable=$retryable, safeMessage=$safeMessage)"
}
