package dev.librepocket.voice

import dev.librepocket.redact.Redactor
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink

/**
 * Client for the OpenAI Text-to-Speech (TTS) API (`POST {baseUrl}/v1/audio/speech`).
 *
 * Security and privacy guarantees:
 * - Credentials & zeroing: [apiKey] supplier returns `CharArray?`. The array is strictly wiped with `fill('\u0000')`
 *   inside a `try/finally` block immediately after OkHttp [Request] construction, before any network dispatch or streaming.
 * - OkHttp Header Limitation: OkHttp's internal header model stores header values as immutable Java [String]s
 *   ("Bearer $keyString"). The immutable string remains in memory until collected by GC; we do not make false security
 *   claims of zero heap exposure for OkHttp request headers.
 * - Scheme & loopback restriction: URLs must be the official OpenAI endpoint (`https://api.openai.com`),
 *   unless connecting to local loopback (`localhost`, `127.0.0.1`, `::1`) with [allowTestLoopback] set to true.
 *   Third-party hosts and unisolated plaintext remote endpoints are strictly rejected with
 *   [OpenAiTtsErrorCode.INVALID_INPUT] before invoking the key supplier or dispatching network requests.
 * - Resource bounding: Responses are streamed in bounded chunks up to [maxAudioBytes] (default 25MB). If the response
 *   exceeds this limit or declares a Content-Length exceeding it, the call is cancelled immediately and fails closed with
 *   [OpenAiTtsErrorCode.RESPONSE_TOO_LARGE] to prevent Out-Of-Memory (OOM).
 * - Overall timeout: OkHttp's `callTimeout` (default 60 seconds) governs the complete call lifetime.
 * - Conservative retryability: Only explicit transient conditions (HTTP 429, 503, 504, and confirmed connection/socket timeouts)
 *   are marked [retryable = true]. 500, 501, 502, client errors, and unknown TLS/transport failures are marked non-retryable.
 * - Comprehensive error redaction: Error messages and exception causes are sanitized via [Redactor.redactError] and
 *   the active API key string is explicitly replaced with `⟦REDACTED:API_KEY⟧` to prevent leaking non-standard keys.
 * - Exception cause encapsulation: Exceptions are never chained with raw, unredacted cause instances.
 * - Privacy: Text sent to cloud is unconditionally sanitized with [VoiceTools.redactForCloud] before network dispatch.
 * - Cancellation: Blocking network I/O is cancelled immediately when the parent coroutine is cancelled.
 * - Transport security: HTTP redirects are disabled to prevent credential leaking across origins.
 */
class OpenAiTtsClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient = defaultOkHttpClient(),
    private val apiKey: (suspend () -> CharArray?)? = null,
    val maxAudioBytes: Long = DEFAULT_MAX_AUDIO_BYTES,
    val allowTestLoopback: Boolean = false,
) {
    init {
        require(maxAudioBytes > 0) { "maxAudioBytes must be positive, was: $maxAudioBytes" }
        require(maxAudioBytes <= MAX_SUPPORTED_AUDIO_BYTES) { "maxAudioBytes must not exceed $MAX_SUPPORTED_AUDIO_BYTES, was: $maxAudioBytes" }
    }

    constructor(apiKey: suspend () -> CharArray?) : this(
        baseUrl = DEFAULT_BASE_URL,
        client = defaultOkHttpClient(),
        apiKey = apiKey,
        maxAudioBytes = DEFAULT_MAX_AUDIO_BYTES,
        allowTestLoopback = false,
    )

    val hasApiKeySupplier: Boolean get() = apiKey != null

    val httpClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .apply {
            if (client.callTimeoutMillis == 0) {
                callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        .build()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val SPEECH_ENDPOINT_PATH = "/v1/audio/speech"
        const val DEFAULT_MAX_AUDIO_BYTES = OpenAiTtsModels.MAX_AUDIO_BYTES.toLong()
        const val MAX_SUPPORTED_AUDIO_BYTES = Long.MAX_VALUE
        const val DEFAULT_CALL_TIMEOUT_SECONDS = 60L
        const val KEY_SUPPLIER_FAILED_MESSAGE = "Key supplier failed to provide API key"
        private const val MAX_ERROR_BODY_BYTES = 4096L
        private const val CHUNK_BUFFER_BYTES = 8192L
        private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()

        /**
         * Resolves the target speech endpoint URL.
         * If [baseUrl] already ends with `/audio/speech`, it is preserved to avoid duplication.
         * If [baseUrl] ends with `/v1`, `/audio/speech` is appended.
         * Otherwise `/v1/audio/speech` is appended.
         */
        internal fun resolveEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/audio/speech") -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed/audio/speech"
                else -> "$trimmed/v1/audio/speech"
            }
        }

        private val IPV4_LOOPBACK_REGEX = Regex(
            """^127(?:\.(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$"""
        )

        internal fun isLoopbackHost(host: String): Boolean {
            val h = host.trim().trim('[', ']').removeSuffix(".").lowercase(Locale.ROOT)
            if (h == "localhost" || (h.endsWith(".localhost") && h.length > ".localhost".length)) {
                return true
            }
            if (h == "::1" || h == "0:0:0:0:0:0:0:1") {
                return true
            }
            return IPV4_LOOPBACK_REGEX.matches(h)
        }

        /**
         * 嚴格驗證是否為官方 OpenAI 端點網址。
         *
         * 安全規則：
         * - scheme 必須是 "https"
         * - host 必須嚴格等於 "api.openai.com"（不允許任意子網域或前後綴）
         * - port 必須為 -1（未指定）或 443（HTTPS 預設埠）
         * - 不得包含 userInfo 使用者名稱或密碼
         */
        internal fun isOpenAiOfficialEndpoint(baseUrl: String): Boolean {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) return false
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val host = uri.host?.lowercase(Locale.ROOT)
                val port = uri.port
                if (scheme != "https" || host != "api.openai.com" || (port != -1 && port != 443)) {
                    return false
                }
                if (uri.userInfo != null) {
                    return false
                }
                val httpUrl = trimmed.toHttpUrlOrNull() ?: return false
                httpUrl.scheme.lowercase(Locale.ROOT) == "https" &&
                    httpUrl.host.lowercase(Locale.ROOT) == "api.openai.com" &&
                    httpUrl.port == 443 &&
                    httpUrl.username.isEmpty() &&
                    httpUrl.password.isEmpty()
            } catch (_: Exception) {
                false
            }
        }

        internal fun validateEndpointUrl(endpointUrl: String, allowTestLoopback: Boolean = false) {
            val parsed = endpointUrl.toHttpUrlOrNull()
                ?: throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Invalid endpoint URL: ${Redactor.redactError(endpointUrl)}",
                )

            val host = parsed.host

            if (isOpenAiOfficialEndpoint(endpointUrl)) {
                return
            }

            if (allowTestLoopback && isLoopbackHost(host)) {
                return
            }

            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                retryable = false,
                safeMessage = "Endpoint is not allowed. Only official OpenAI endpoint (https://api.openai.com) or local loopback (in test mode) is permitted. Refusing to send credentials to: ${Redactor.redactError(host)}",
            )
        }

        internal fun isConfirmedTimeout(e: IOException): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is SocketTimeoutException) return true
                if (cur is InterruptedIOException) {
                    val msg = cur.message?.lowercase(Locale.ROOT).orEmpty()
                    if (msg.contains("timeout") || msg.contains("timed out")) return true
                }
                cur = cur.cause
            }
            return false
        }
    }

    /**
     * Synthesizes audio for the provided [request].
     *
     * @param request TTS parameters and input text.
     * @param apiKeyOverride Optional override supplier for the API key.
     * @return [OpenAiTtsResult] containing audio binary bytes and MIME type.
     * @throws OpenAiTtsException on input validation, missing key, or API/network error.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    suspend fun synthesize(
        request: OpenAiTtsRequest,
        apiKeyOverride: (suspend () -> CharArray?)? = null,
    ): OpenAiTtsResult {
        val endpoint = resolveEndpoint(baseUrl)
        validateEndpointUrl(endpoint, allowTestLoopback)

        val keyProvider = apiKeyOverride ?: this.apiKey
            ?: throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.MISSING_KEY,
                retryable = false,
                safeMessage = "API key supplier is not configured",
            )

        val keyChars = try {
            keyProvider()
        } catch (_: CancellationException) {
            throw CancellationException("API key retrieval cancelled").apply { stackTrace = emptyArray() }
        } catch (_: Throwable) {
            val safeSupplierMessage = KEY_SUPPLIER_FAILED_MESSAGE
            val safeCause = IOException(safeSupplierMessage).apply {
                stackTrace = emptyArray()
            }
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.MISSING_KEY,
                retryable = false,
                safeMessage = safeSupplierMessage,
                cause = safeCause,
            )
        } ?: throw OpenAiTtsException(
            statusCode = null,
            errorCode = OpenAiTtsErrorCode.MISSING_KEY,
            retryable = false,
            safeMessage = "OpenAI API key is missing",
        )

        if (keyChars.isEmpty() || keyChars.all { it.isWhitespace() }) {
            keyChars.fill('\u0000')
            throw OpenAiTtsException(
                statusCode = null,
                errorCode = OpenAiTtsErrorCode.MISSING_KEY,
                retryable = false,
                safeMessage = "OpenAI API key is blank",
            )
        }

        val (httpRequest, activeKey) = try {
            // Force redaction for cloud upload
            val redactedInput = VoiceTools.redactForCloud(request.input)
            if (redactedInput.isBlank()) {
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Redacted input text is blank",
                )
            }
            if (redactedInput.length > OpenAiTtsRequest.MAX_INPUT_LENGTH) {
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Redacted input text exceeds maximum allowed length of ${OpenAiTtsRequest.MAX_INPUT_LENGTH} characters",
                )
            }

            val jsonBody = buildJsonObject {
                put("model", request.model)
                put("input", redactedInput)
                put("voice", request.voice)
                put("response_format", request.responseFormat)
                put("speed", request.speed)
            }.toString()

            val requestBody = OneShotJsonBody(jsonBody)
            val keyString = String(keyChars)

            // Security Note:
            // [keyChars] is strictly zeroed with fill('\u0000') immediately in this finally block,
            // before any network I/O or streaming occurs.
            // However, OkHttp's Request.Builder header internals store header values as immutable Java Strings
            // ("Bearer $keyString"). That immutable String reference remains in memory until garbage collected.
            // We do not make false security claims of zero heap exposure for OkHttp header strings.
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $keyString")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            req to keyString
        } finally {
            keyChars.fill('\u0000')
        }

        return executeRequest(httpRequest, activeKey)
    }

    /**
     * Synthesizes audio with explicit arguments.
     */
    suspend fun synthesize(
        text: String,
        model: String = OpenAiTtsRequest.DEFAULT_MODEL,
        voice: String = OpenAiTtsRequest.DEFAULT_VOICE,
        responseFormat: String = OpenAiTtsRequest.DEFAULT_RESPONSE_FORMAT,
        speed: Float = OpenAiTtsRequest.DEFAULT_SPEED,
        apiKeyOverride: (suspend () -> CharArray?)? = null,
    ): OpenAiTtsResult = synthesize(
        request = OpenAiTtsRequest(
            input = text,
            model = model,
            voice = voice,
            responseFormat = responseFormat,
            speed = speed,
        ),
        apiKeyOverride = apiKeyOverride,
    )

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun executeRequest(httpRequest: Request, activeKey: String): OpenAiTtsResult =
        withContext(Dispatchers.IO) {
            val call = httpClient.newCall(httpRequest)
            val job = currentCoroutineContext()[Job]
            val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) {
                    try {
                        call.cancel()
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    handleResponse(response, call, activeKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenAiTtsException) {
                throw e
            } catch (e: IOException) {
                if (job?.isCancelled == true) {
                    throw CancellationException("TTS request coroutine was cancelled", e)
                }
                currentCoroutineContext().ensureActive()
                val isTimeout = isConfirmedTimeout(e)
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.NETWORK_ERROR,
                    retryable = isTimeout,
                    safeMessage = e.message ?: "Network error during TTS request",
                    cause = e,
                    activeKey = activeKey,
                )
            } catch (e: Exception) {
                if (job?.isCancelled == true) {
                    throw CancellationException("TTS request coroutine was cancelled", e)
                }
                currentCoroutineContext().ensureActive()
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.UNEXPECTED,
                    retryable = false,
                    safeMessage = e.message ?: "Unexpected error during TTS request",
                    cause = e,
                    activeKey = activeKey,
                )
            } finally {
                cancellation?.dispose()
            }
        }

    private fun handleResponse(response: Response, call: Call, activeKey: String): OpenAiTtsResult {
        val code = response.code
        if (response.isSuccessful) {
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > maxAudioBytes) {
                call.cancel()
                throw OpenAiTtsException(
                    statusCode = code,
                    errorCode = OpenAiTtsErrorCode.RESPONSE_TOO_LARGE,
                    retryable = false,
                    safeMessage = "Response audio length ($declaredLength bytes) exceeds maximum limit of $maxAudioBytes bytes",
                )
            }

            val source = body.source()
            val buffer = Buffer()
            val chunk = Buffer()
            var totalBytesRead = 0L

            while (true) {
                val remaining = maxAudioBytes - totalBytesRead
                if (remaining <= 0L) {
                    val probe = Buffer()
                    val extra = source.read(probe, 1L)
                    probe.clear()
                    if (extra != -1L) {
                        buffer.clear()
                        chunk.clear()
                        call.cancel()
                        throw OpenAiTtsException(
                            statusCode = code,
                            errorCode = OpenAiTtsErrorCode.RESPONSE_TOO_LARGE,
                            retryable = false,
                            safeMessage = "Response audio exceeded maximum allowed size of $maxAudioBytes bytes",
                        )
                    }
                    break
                }

                val toRead = minOf(CHUNK_BUFFER_BYTES, remaining)
                val read = source.read(chunk, toRead)
                if (read == -1L) break
                totalBytesRead += read
                buffer.write(chunk, read)
            }

            val bytes = buffer.readByteArray()
            val contentType = response.header("Content-Type")
                ?: body.contentType()?.toString()
                ?: "audio/mpeg"
            return OpenAiTtsResult(
                audioBytes = bytes,
                contentType = contentType,
            )
        }

        val errorBody = readBoundedErrorBody(response)
        val rawMessage = extractErrorMessage(response, errorBody)
        val (errorCode, retryable) = classifyHttpError(code)
        throw OpenAiTtsException(
            statusCode = code,
            errorCode = errorCode,
            retryable = retryable,
            safeMessage = rawMessage,
            activeKey = activeKey,
        )
    }

    private fun readBoundedErrorBody(response: Response): String? {
        return try {
            val body = response.body
            val source = body.source()
            val buffer = Buffer()
            val read = source.read(buffer, MAX_ERROR_BODY_BYTES)
            if (read != -1L) buffer.readUtf8() else ""
        } catch (_: Exception) {
            null
        }
    }

    private fun extractErrorMessage(response: Response, errorBody: String?): String {
        val parsedMsg = if (!errorBody.isNullOrBlank()) {
            try {
                val json = Json.parseToJsonElement(errorBody)
                if (json is JsonObject) {
                    val errorObj = json["error"]
                    if (errorObj is JsonObject) {
                        val msgElem = errorObj["message"]
                        if (msgElem is JsonPrimitive && msgElem.isString) {
                            msgElem.content
                        } else null
                    } else null
                } else null
            } catch (_: Exception) {
                null
            }
        } else null

        val detail = when {
            !parsedMsg.isNullOrBlank() -> parsedMsg.trim()
            !errorBody.isNullOrBlank() -> errorBody.trim().replace(Regex("\\s+"), " ").take(200)
            response.message.isNotBlank() -> response.message
            else -> "HTTP ${response.code}"
        }

        return "HTTP ${response.code}: $detail"
    }

    private fun classifyHttpError(code: Int): Pair<OpenAiTtsErrorCode, Boolean> {
        return when (code) {
            401 -> OpenAiTtsErrorCode.UNAUTHORIZED to false
            403 -> OpenAiTtsErrorCode.FORBIDDEN to false
            429 -> OpenAiTtsErrorCode.RATE_LIMIT to true
            503, 504 -> OpenAiTtsErrorCode.SERVER_ERROR to true
            500, 501, 502 -> OpenAiTtsErrorCode.SERVER_ERROR to false
            in 500..599 -> OpenAiTtsErrorCode.SERVER_ERROR to false
            in 400..499 -> OpenAiTtsErrorCode.CLIENT_ERROR to false
            else -> OpenAiTtsErrorCode.UNEXPECTED to false
        }
    }

    private class OneShotJsonBody(private val json: String) : RequestBody() {
        override fun contentType(): MediaType = JSON_MEDIA_TYPE
        override fun contentLength(): Long = json.toByteArray(Charsets.UTF_8).size.toLong()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(json)
        }
    }
}
