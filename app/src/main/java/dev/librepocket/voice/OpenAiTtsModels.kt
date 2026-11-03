package dev.librepocket.voice

import dev.librepocket.redact.Redactor
import java.io.IOException

/**
 * OpenAI Text-to-Speech (TTS) request and response data models, constants, and error definitions.
 *
 * 生命週期與過渡聲明（短期過渡 Legacy Bridge）：
 * - `POST /v1/audio/speech`（`tts-1` / `tts-1-hd`）為**短期過渡 Legacy Bridge**。
 * - 官方已公告退場時間為 **2027-01-06**（[SUNSET_DATE]）。
 * - 未來遷移目標為 Realtime API (`gpt-realtime-2.1-mini`，見 [MIGRATION_TARGET]）。
 */
object OpenAiTtsModels {
    /**
     * 官方公告退場時間 (Sunset date: 2027-01-06)。
     * POST /v1/audio/speech (tts-1 / tts-1-hd) 為短期過渡 Legacy Bridge，
     * 未來遷移目標為 Realtime API (gpt-realtime-2.1-mini)。
     */
    const val SUNSET_DATE = "2027-01-06"
    const val MIGRATION_TARGET = "gpt-realtime-2.1-mini"
    const val IS_LEGACY_BRIDGE = true

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
    const val VOICE_ASH = "ash"
    const val VOICE_CORAL = "coral"
    const val VOICE_SAGE = "sage"
    const val VOICE_BALLAD = "ballad"
    const val VOICE_VERSE = "verse"
    const val VOICE_MARIN = "marin"
    const val VOICE_CEDAR = "cedar"

    /**
     * Recommended voices currently supported by OpenAI TTS.
     * Legacy `tts-1` / `tts-1-hd` models officially support 9 voices (`alloy, echo, fable, onyx, nova, shimmer, ash, coral, sage`),
     * while `ballad, verse, marin, cedar` are supported on newer OpenAI models (`gpt-4o-mini-tts`, Realtime API).
     * Note: [OpenAiTtsRequest] validates non-blank strings only and delegates final voice validation
     * to the API server without artificial client-side rejection, ensuring forward compatibility with newly added OpenAI voices.
     */
    val SUPPORTED_VOICES = setOf(
        VOICE_ALLOY,
        VOICE_ECHO,
        VOICE_FABLE,
        VOICE_ONYX,
        VOICE_NOVA,
        VOICE_SHIMMER,
        VOICE_ASH,
        VOICE_CORAL,
        VOICE_SAGE,
        VOICE_BALLAD,
        VOICE_VERSE,
        VOICE_MARIN,
        VOICE_CEDAR,
    )

    const val DEFAULT_RESPONSE_FORMAT = "mp3"
    const val FORMAT_MP3 = "mp3"
    const val FORMAT_OPUS = "opus"
    const val FORMAT_AAC = "aac"
    const val FORMAT_FLAC = "flac"
    const val FORMAT_WAV = "wav"
    const val FORMAT_PCM = "pcm"

    /**
     * Recommended response formats currently supported by OpenAI TTS.
     * Note: [OpenAiTtsRequest] validates non-blank strings only and delegates final format validation
     * to the API server for forward compatibility.
     */
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
    /**
     * Canonical speech endpoint path. Single source of truth for the
     * `POST /v1/audio/speech` path; [OpenAiTtsClient.resolveEndpoint] must build
     * endpoint URLs from this constant instead of duplicating path literals.
     */
    const val SPEECH_ENDPOINT_PATH = "/v1/audio/speech"
    /**
     * 預設音訊位元組上限（25MB）。
     * 音檔採受限記憶體緩衝播放（不寫入儲存空間）。25MB 是預設值而非絕對上限：
     * 呼叫方可依需求上調，但不得超過 [MAX_SUPPORTED_AUDIO_BYTES]。
     * 誠實說明：網路下載後全額緩衝於記憶體中（Bounded in-memory buffering），
     * 峰值時存在多份 ByteArray 拷貝，25MB 上限可降低但無法杜絕極限情況的 OOM 風險。
     */
    const val MAX_AUDIO_BYTES = 25 * 1024 * 1024 // 25 MB
    /**
     * 絕對硬上限（64MB）：[MAX_AUDIO_BYTES] 預設值之上、呼叫方可配置的最大值。
     *
     * 選 64MB 的理由：約為預設 25MB 的 2.5 倍，保留未來較長文本／高位元率格式的
     * 成長空間；同時仍是有意義的上界——峰值瞬態拷貝約 3 倍（OkHttp chunk 讀取緩衝、
     * 累計緩衝、下游播放器防禦性拷貝），64MB × 3 仍在 fail-closed 可控範圍內。
     * 此前 `Long.MAX_VALUE` 作為上界是無意義的空轉（錯誤訊息亦會印出該無意義數字），
     * 現以此具名硬上限取代；構造器維持 `maxAudioBytes <= MAX_SUPPORTED_AUDIO_BYTES` 檢查。
     */
    const val MAX_SUPPORTED_AUDIO_BYTES = 64L * 1024 * 1024 // 64 MB
}

/**
 * Request configuration for OpenAI TTS synthesis.
 *
 * @param input Text to synthesize, maximum 4096 characters (OpenAI limit).
 * @param model Model identifier (default "tts-1", supports "tts-1-hd").
 * @param voice Voice name (default "alloy", supports "echo", "fable", "onyx", "nova", "shimmer", "ash", "coral", "sage", "ballad", "verse", "marin", "cedar", etc. Final validation is performed by the API server).
 * @param responseFormat Audio format (default "mp3", supports "opus", "aac", "flac", "wav", "pcm", etc. Final validation is performed by the API server).
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
        /**
         * Recommended voices currently supported by OpenAI TTS.
         * Legacy `tts-1` / `tts-1-hd` models officially support 9 voices (`alloy, echo, fable, onyx, nova, shimmer, ash, coral, sage`),
         * while `ballad, verse, marin, cedar` are supported on newer OpenAI models (`gpt-4o-mini-tts`, Realtime API).
         * The client permits any non-blank voice string; final validation is delegated to the API server without artificial client-side rejection.
         */
        val SUPPORTED_VOICES = OpenAiTtsModels.SUPPORTED_VOICES

        const val DEFAULT_RESPONSE_FORMAT = OpenAiTtsModels.DEFAULT_RESPONSE_FORMAT
        /**
         * Recommended response formats currently supported by OpenAI TTS.
         * The client permits any non-blank format string; final validation is performed by the API server.
         */
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
        if (!speed.isFinite() || speed !in MIN_SPEED..MAX_SPEED) {
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Speed must be between $MIN_SPEED and $MAX_SPEED (got $speed)",
            )
        }
    }

    override fun toString(): String =
        "OpenAiTtsRequest(input=[length=${input.length}], model=$model, voice=$voice, responseFormat=$responseFormat, speed=$speed)"
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
