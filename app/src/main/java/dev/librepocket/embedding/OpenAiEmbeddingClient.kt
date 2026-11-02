package dev.librepocket.embedding

import dev.librepocket.redact.Redactor
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer

/**
 * Cancellation exception with an empty stack trace used when the API key supplier cancels.
 *
 * Implements [CopyableThrowable] with `createCopy() = null` to instruct kotlinx.coroutines'
 * stack trace recovery mechanism not to clone or reconstruct the exception across coroutine
 * suspension boundaries. In combination with clearing [stackTrace], this guarantees that no
 * internal coroutine machinery, key-supplier stack frames, or memory references leak through
 * unhandled exception reporting or crash handlers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CleanCancellationException(message: String) :
    CancellationException(message),
    CopyableThrowable<CleanCancellationException> {

    init {
        stackTrace = emptyArray()
    }

    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }

    override fun getStackTrace(): Array<StackTraceElement> = emptyArray()

    override fun createCopy(): CleanCancellationException? = null
}

/**
 * Secure OpenAI Embeddings API (`POST /v1/embeddings`) client.
 *
 * Security & Defense features:
 * - Strict official endpoint enforcement (`https://api.openai.com`) in production;
 *   loopback endpoints are only permitted when [allowTestLoopback] is true.
 * - Credential hygiene: Key Supplier [apiKey] returns a [CharArray] which is
 *   promptly zeroed in a `finally` block once the request is built.
 * - Key Supplier exception shielding: [CancellationException] is re-thrown clean with
 *   an empty stack trace; other exceptions are shielded behind a constant safe message
 *   without echoing unredacted causes or stack traces.
 * - Non-standard key redaction: Active API key is explicitly masked with `⟦REDACTED:API_KEY⟧`
 *   in all error messages before passing through [Redactor.redactError].
 * - Configurable input redaction: [redactInput] applies [Redactor.redact] to all input texts.
 * - Response size capping: Responses are read in bounded chunks up to [maxResponseBytes]
 *   (default 10MB); exceeding the cap cancels the OkHttp Call and fails with [EmbeddingErrorCode.RESPONSE_TOO_LARGE].
 * - Prompt coroutine cancellation: OkHttp call is cancelled immediately when the parent Job is cancelled.
 */
class OpenAiEmbeddingClient(
    val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: suspend () -> CharArray?,
    httpClient: OkHttpClient? = null,
    val redactInput: Boolean = false,
    val allowTestLoopback: Boolean = false,
    val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
) {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive, was: $maxResponseBytes" }
    }

    internal val client: OkHttpClient = if (httpClient != null) {
        httpClient.newBuilder()
            .apply {
                if (httpClient.callTimeoutMillis == 0) {
                    callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    } else {
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Creates vector embeddings for the given [request].
     */
    suspend fun createEmbeddings(
        request: EmbeddingRequest,
        redactInput: Boolean = this.redactInput,
    ): EmbeddingResponse = withContext(Dispatchers.IO) {
        validateEndpoint(baseUrl, allowTestLoopback)

        val safeInput = request.input.toList()
        require(safeInput.isNotEmpty()) { "input cannot be empty" }
        require(safeInput.size <= EmbeddingRequest.MAX_BATCH_SIZE) {
            "batch size ${safeInput.size} exceeds maximum limit of ${EmbeddingRequest.MAX_BATCH_SIZE}"
        }
        require(safeInput.none { it.isBlank() }) { "input items cannot be blank" }

        val keyChars: CharArray = try {
            apiKey()
        } catch (ce: CancellationException) {
            throw CleanCancellationException("API key supplier cancelled")
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (_: Exception) {
            throw EmbeddingException(
                statusCode = null,
                errorCode = EmbeddingErrorCode.UNAUTHORIZED,
                retryable = false,
                safeMessage = "Failed to obtain API key",
                cause = null,
            )
        } ?: throw EmbeddingException(
            statusCode = 401,
            errorCode = EmbeddingErrorCode.UNAUTHORIZED,
            retryable = false,
            safeMessage = "API key is missing or empty",
        )

        try {
            validateApiKey(keyChars)

            val httpRequest: Request = try {
                val endpoint = resolveEndpoint(baseUrl)
                val bodyJson = buildRequestBodyJson(request.copy(input = safeInput), redactInput)
                val requestBody = bodyJson.toRequestBody(JSON_MEDIA_TYPE)
                Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + String(keyChars))
                    .post(requestBody)
                    .build()
            } catch (iae: IllegalArgumentException) {
                val safeMsg = redactError("Invalid request configuration: ${iae.message}", keyChars)
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.INVALID_REQUEST,
                    retryable = false,
                    safeMessage = safeMsg,
                    cause = null,
                )
            }

            val (call, cancellation) = newCancellableCall(httpRequest, currentCoroutineContext()[Job])

            try {
                currentCoroutineContext().ensureActive()
                val response = call.execute()
                try {
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) {
                        val errorSnippet = readBoundedErrorPrefix(response.body) { call.cancel() }.orEmpty()
                        val (code, retryable) = classifyHttpError(response.code, errorSnippet)
                        val snippet = safeSnippet(errorSnippet, keyChars)
                        val safeMsg = if (snippet.isEmpty()) "HTTP ${response.code}" else "HTTP ${response.code}: $snippet"
                        throw EmbeddingException(
                            statusCode = response.code,
                            errorCode = code,
                            retryable = retryable,
                            safeMessage = safeMsg,
                        )
                    }

                    val body = response.body

                    val responseString = readBoundedBody(
                        body = body,
                        maxBytes = maxResponseBytes,
                        cancelCall = { call.cancel() },
                        statusCode = response.code,
                    )

                    currentCoroutineContext().ensureActive()
                    parseResponseBodyJson(
                        responseString = responseString,
                        fallbackModel = request.model,
                        statusCode = response.code,
                        expectedInputSize = safeInput.size,
                        expectedDimensions = request.dimensions,
                    )
                } finally {
                    try {
                        response.close()
                    } catch (_: NullPointerException) {
                        // Defensive: OkHttp Response.close() crashes if body is null in rare cases
                    }
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            } catch (ee: EmbeddingException) {
                throw ee
            } catch (ioe: IOException) {
                currentCoroutineContext().ensureActive()
                val (code, retryable) = classifyNetworkError(ioe)
                val safeMsg = redactError("Network error: ${ioe.message}", keyChars)
                val safeCause = IOException(safeMsg).apply { stackTrace = emptyArray() }
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = code,
                    retryable = retryable,
                    safeMessage = safeMsg,
                    cause = safeCause,
                )
            } catch (se: SecurityException) {
                currentCoroutineContext().ensureActive()
                val safeMsg = redactError("Security error: ${se.message}", keyChars)
                val safeCause = SecurityException(safeMsg).apply { stackTrace = emptyArray() }
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.NETWORK_ERROR,
                    retryable = false,
                    safeMessage = safeMsg,
                    cause = safeCause,
                )
            } finally {
                cancellation?.dispose()
            }
        } finally {
            keyChars.fill('\u0000')
        }
    }

    /**
     * Convenience alias for [createEmbeddings].
     */
    suspend fun embed(
        request: EmbeddingRequest,
        redactInput: Boolean = this.redactInput,
    ): EmbeddingResponse = createEmbeddings(request, redactInput)

    /**
     * Convenience function to embed a single text string.
     */
    suspend fun embed(
        text: String,
        model: String = EmbeddingRequest.DEFAULT_MODEL,
        dimensions: Int? = null,
    ): FloatArray {
        val response = createEmbeddings(
            EmbeddingRequest(
                input = listOf(text),
                model = model,
                dimensions = dimensions,
            )
        )
        return response.data.first().embedding
    }

    /**
     * Creates an OkHttp [Call] bound to the coroutine [job] cancellation lifecycle.
     *
     * Note on [@OptIn(InternalCoroutinesApi::class)]:
     * Invokes `Job.invokeOnCompletion(onCancelling = true, invokeImmediately = true)`, an internal
     * coroutines API. Passing `onCancelling = true` ensures that the underlying OkHttp [Call.cancel]
     * is triggered immediately at the moment cancellation begins (in the 'Cancelling' state),
     * rather than waiting for all child coroutines to complete (the 'Cancelled' state).
     * This promptly aborts the active network socket and terminates the HTTP connection without
     * blocking on downstream coroutine cleanup.
     */
    @OptIn(InternalCoroutinesApi::class)
    internal fun newCancellableCall(
        request: Request,
        job: Job? = null,
    ): Pair<Call, DisposableHandle?> {
        val call = client.newCall(request)
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                call.cancel()
            }
        }
        return call to cancellation
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024L // 10 MB (safely bounds decoded response-body bytes on mobile)
        const val CALL_TIMEOUT_SECONDS = 60L
        const val MAX_SUPPORTED_DIMENSION = 3072
        const val BEARER_MAX_CHARS = 8_192
        private const val CHUNK_SIZE = 8192L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun validateApiKey(keyChars: CharArray) {
            if (keyChars.isEmpty()) {
                throw EmbeddingException(
                    statusCode = 401,
                    errorCode = EmbeddingErrorCode.UNAUTHORIZED,
                    retryable = false,
                    safeMessage = "API key is missing or empty",
                )
            }
            if (keyChars.size > BEARER_MAX_CHARS) {
                throw EmbeddingException(
                    statusCode = 401,
                    errorCode = EmbeddingErrorCode.UNAUTHORIZED,
                    retryable = false,
                    safeMessage = "API key exceeds maximum allowed length of $BEARER_MAX_CHARS characters",
                )
            }
            for (c in keyChars) {
                if (c.code !in 0x21..0x7E) {
                    throw EmbeddingException(
                        statusCode = 401,
                        errorCode = EmbeddingErrorCode.UNAUTHORIZED,
                        retryable = false,
                        safeMessage = "API key contains invalid characters (must be printable ASCII)",
                    )
                }
            }
        }

        internal fun isLoopbackHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']').trimEnd('.')
            if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            val parts = h.split('.')
            if (parts.size == 4 && parts[0] == "127") {
                return parts.all { it.toIntOrNull() in 0..255 }
            }
            return false
        }

        internal fun isOpenAiOfficialEndpoint(url: HttpUrl): Boolean {
            val scheme = url.scheme.lowercase()
            val host = url.host.lowercase()
            val path = url.encodedPath.trimEnd('/')
            val validOfficialPath = path.isEmpty() || path == "/v1" || path == "/v1/embeddings" || path == "/embeddings"
            return scheme == "https" && host == "api.openai.com" && url.port == 443 && validOfficialPath
        }

        internal fun validateEndpoint(baseUrl: String, allowTestLoopback: Boolean): HttpUrl {
            val httpUrl = baseUrl.toHttpUrlOrNull()
                ?: throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED,
                    retryable = false,
                    safeMessage = "Invalid baseUrl: cannot parse URL",
                )
            if (httpUrl.query != null || httpUrl.fragment != null) {
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED,
                    retryable = false,
                    safeMessage = "Disallowed endpoint: baseUrl cannot contain query parameters or fragments",
                )
            }
            if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED,
                    retryable = false,
                    safeMessage = "Disallowed endpoint: baseUrl cannot contain credentials",
                )
            }
            val isOfficialOpenAi = isOpenAiOfficialEndpoint(httpUrl)
            val host = httpUrl.host.lowercase()
            val isLoopback = isLoopbackHost(host)

            if (allowTestLoopback) {
                if (isOfficialOpenAi || isLoopback) {
                    return httpUrl
                }
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED,
                    retryable = false,
                    safeMessage = "Disallowed endpoint: $host is neither official OpenAI nor loopback",
                )
            } else {
                if (isOfficialOpenAi) {
                    return httpUrl
                }
                throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED,
                    retryable = false,
                    safeMessage = "Disallowed endpoint: production requires official OpenAI endpoint (https://api.openai.com)",
                )
            }
        }

        internal fun resolveEndpoint(baseUrl: String): String {
            val httpUrl = baseUrl.toHttpUrlOrNull()
            if (httpUrl != null) {
                val path = httpUrl.encodedPath.trimEnd('/')
                val newPath = when {
                    path.endsWith("/v1/embeddings") -> path
                    path.endsWith("/embeddings") -> path.removeSuffix("/embeddings") + "/v1/embeddings"
                    path.endsWith("/v1") -> "$path/embeddings"
                    path.isEmpty() -> "/v1/embeddings"
                    else -> "$path/v1/embeddings"
                }
                return httpUrl.newBuilder()
                    .encodedPath(newPath)
                    .query(null)
                    .fragment(null)
                    .build()
                    .toString()
            }
            val trimmed = baseUrl.trimEnd('/')
            return when {
                trimmed.endsWith("/v1/embeddings") -> trimmed
                trimmed.endsWith("/embeddings") -> trimmed.removeSuffix("/embeddings") + "/v1/embeddings"
                trimmed.endsWith("/v1") -> "$trimmed/embeddings"
                else -> "$trimmed/v1/embeddings"
            }
        }

        internal fun buildRequestBodyJson(request: EmbeddingRequest, redactInput: Boolean): String =
            buildJsonObject {
                put("model", request.model)
                put("input", buildJsonArray {
                    for (item in request.input) {
                        val text = if (redactInput) Redactor.redact(item).text else item
                        add(text)
                    }
                })
                request.dimensions?.let { put("dimensions", it) }
                request.user?.let { put("user", it) }
            }.toString()

        internal fun parseResponseBodyJson(
            responseString: String,
            fallbackModel: String,
            statusCode: Int?,
            expectedInputSize: Int? = null,
            expectedDimensions: Int? = null,
        ): EmbeddingResponse {
            try {
                return parseStreamingResponse(responseString, fallbackModel, statusCode, expectedInputSize, expectedDimensions)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ee: EmbeddingException) {
                throw ee
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (so: StackOverflowError) {
                throw EmbeddingException(
                    statusCode = statusCode,
                    errorCode = EmbeddingErrorCode.INVALID_RESPONSE,
                    retryable = false,
                    safeMessage = "Nesting depth limit exceeded",
                    cause = null,
                )
            } catch (_: Exception) {
                throw EmbeddingException(
                    statusCode = statusCode,
                    errorCode = EmbeddingErrorCode.INVALID_RESPONSE,
                    retryable = false,
                    safeMessage = "Failed to parse embedding response JSON",
                    cause = null,
                )
            }
        }

        /**
         * Hand-rolled streaming recursive-descent JSON parser.
         *
         * Architectural Justification over kotlinx.serialization / reflection-based parsers:
         * 1. Zero-Boxing Memory Efficiency:
         *    Embedding responses can contain up to 307,200 floats (100 items x 3072 dimensions).
         *    Standard JSON parsers (e.g. kotlinx.serialization with JsonTree or intermediate models)
         *    allocate millions of boxed [java.lang.Float] objects and [JsonElement] AST nodes,
         *    generating tens of megabytes of short-lived heap churn and triggering frequent GC pauses.
         *    This streaming parser decodes primitive floating-point numbers directly from the JSON
         *    string into flat primitive [FloatArray] buffers with zero object boxing.
         * 2. Eager Fail-Fast Dimension Bounds:
         *    Validates [MAX_SUPPORTED_DIMENSION] (3072) and [expectedDimensions] incrementally while
         *    reading float tokens, aborting immediately upon exceeding limits rather than parsing
         *    unbounded payloads into memory.
         * 3. Strict RFC 8259 Conformance & Injection Defense:
         *    Rejects non-compliant JSON syntax (leading zeros, leading '+', trailing commas) and
         *    validates [Float.isFinite] to block NaN/Infinity injection attacks.
         * 4. Bounded Call Stack:
         *    Recursion depth is strictly capped (max depth 64 via [skipValue]) to prevent
         *    [StackOverflowError] Denial of Service from deeply nested malicious payloads.
         */
        private fun parseStreamingResponse(
            json: String,
            fallbackModel: String,
            statusCode: Int?,
            expectedInputSize: Int?,
            expectedDimensions: Int? = null,
        ): EmbeddingResponse {
            var pos = 0
            val len = json.length

            fun error(msg: String): Nothing {
                val fullMsg = if (msg.startsWith("Embedding dimension")) msg else "Invalid response: $msg"
                throw EmbeddingException(
                    statusCode = statusCode,
                    errorCode = EmbeddingErrorCode.INVALID_RESPONSE,
                    retryable = false,
                    safeMessage = fullMsg,
                    cause = null,
                )
            }

            fun skipWs() {
                while (pos < len) {
                    val c = json[pos]
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
                }
            }

            fun expect(ch: Char) {
                skipWs()
                if (pos >= len || json[pos] != ch) error("expected '$ch'")
                pos++
            }

            fun readString(): String {
                skipWs()
                if (pos >= len || json[pos] != '"') error("expected string quote")
                pos++
                val sb = StringBuilder()
                while (pos < len) {
                    val c = json[pos++]
                    if (c == '"') return sb.toString()
                    if (c < ' ') error("unescaped control character in string")
                    if (c == '\\') {
                        if (pos >= len) error("unexpected EOF in string escape")
                        when (val esc = json[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > len) error("unexpected EOF in unicode escape")
                                for (i in 0..3) {
                                    val hc = json[pos + i]
                                    if (hc !in '0'..'9' && hc !in 'a'..'f' && hc !in 'A'..'F') {
                                        error("invalid unicode escape '\\u${json.substring(pos, pos + 4)}'")
                                    }
                                }
                                val hex = json.substring(pos, pos + 4).toInt(16)
                                sb.append(hex.toChar())
                                pos += 4
                            }
                            else -> error("invalid escape sequence '\\$esc'")
                        }
                    } else {
                        sb.append(c)
                    }
                }
                error("unterminated string")
            }

            fun skipString() {
                skipWs()
                if (pos >= len || json[pos] != '"') error("expected string quote")
                pos++
                while (pos < len) {
                    val c = json[pos++]
                    if (c == '"') return
                    if (c < ' ') error("unescaped control character in string")
                    if (c == '\\') {
                        if (pos >= len) error("unexpected EOF in string escape")
                        when (val esc = json[pos++]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {}
                            'u' -> {
                                if (pos + 4 > len) error("unexpected EOF in unicode escape")
                                for (i in 0..3) {
                                    val hc = json[pos + i]
                                    if (hc !in '0'..'9' && hc !in 'a'..'f' && hc !in 'A'..'F') {
                                        error("invalid unicode escape '\\u${json.substring(pos, pos + 4)}'")
                                    }
                                }
                                pos += 4
                            }
                            else -> error("invalid escape sequence '\\$esc'")
                        }
                    }
                }
                error("unterminated string")
            }

            fun skipValue(depth: Int = 0) {
                if (depth > 64) error("Nesting depth limit exceeded (max 64)")
                skipWs()
                if (pos >= len) error("unexpected EOF in value")
                when (val c = json[pos]) {
                    '{' -> {
                        expect('{')
                        var first = true
                        while (true) {
                            skipWs()
                            if (pos >= len) error("unexpected EOF in skipped object")
                            if (json[pos] == '}') { pos++; break }
                            if (!first) {
                                expect(',')
                                skipWs()
                                if (pos < len && json[pos] == '}') error("trailing comma in skipped object")
                            }
                            first = false
                            skipString()
                            expect(':')
                            skipValue(depth + 1)
                        }
                    }
                    '[' -> {
                        expect('[')
                        var first = true
                        while (true) {
                            skipWs()
                            if (pos >= len) error("unexpected EOF in skipped array")
                            if (json[pos] == ']') { pos++; break }
                            if (!first) {
                                expect(',')
                                skipWs()
                                if (pos < len && json[pos] == ']') error("trailing comma in skipped array")
                            }
                            first = false
                            skipValue(depth + 1)
                        }
                    }
                    '"' -> skipString()
                    't' -> {
                        if (json.startsWith("true", pos)) pos += 4
                        else error("invalid literal")
                    }
                    'f' -> {
                        if (json.startsWith("false", pos)) pos += 5
                        else error("invalid literal")
                    }
                    'n' -> {
                        if (json.startsWith("null", pos)) pos += 4
                        else error("invalid literal")
                    }
                    in '0'..'9', '-' -> {
                        val start = pos
                        if (json[pos] == '-') pos++
                        if (pos >= len || json[pos] !in '0'..'9') error("invalid number in skipped value")
                        if (json[pos] == '0' && pos + 1 < len && json[pos + 1] in '0'..'9') {
                            error("leading zero is not permitted in JSON numbers")
                        }
                        while (pos < len && json[pos] in '0'..'9') pos++
                        if (pos < len && json[pos] == '.') {
                            pos++
                            if (pos >= len || json[pos] !in '0'..'9') error("invalid fraction in skipped number")
                            while (pos < len && json[pos] in '0'..'9') pos++
                        }
                        if (pos < len && (json[pos] == 'e' || json[pos] == 'E')) {
                            pos++
                            if (pos < len && (json[pos] == '+' || json[pos] == '-')) pos++
                            if (pos >= len || json[pos] !in '0'..'9') error("invalid exponent in skipped number")
                            while (pos < len && json[pos] in '0'..'9') pos++
                        }
                    }
                    else -> error("unexpected character '$c' in value")
                }
            }

            fun readFloat(): Float {
                skipWs()
                if (pos >= len) throw IllegalArgumentException("expected number")
                if (json[pos] == '"') throw IllegalArgumentException("Non-numeric embedding component")
                if (json[pos] == '+') throw IllegalArgumentException("leading '+' is not permitted in JSON numbers")
                val start = pos
                if (json[pos] == '-') pos++
                if (pos >= len || json[pos] !in '0'..'9') throw IllegalArgumentException("invalid float format")
                if (json[pos] == '0' && pos + 1 < len && json[pos + 1] in '0'..'9') {
                    throw IllegalArgumentException("leading zero is not permitted in JSON numbers")
                }
                while (pos < len && json[pos] in '0'..'9') pos++
                if (pos < len && json[pos] == '.') {
                    pos++
                    if (pos >= len || json[pos] !in '0'..'9') throw IllegalArgumentException("invalid float fraction")
                    while (pos < len && json[pos] in '0'..'9') pos++
                }
                if (pos < len && (json[pos] == 'e' || json[pos] == 'E')) {
                    pos++
                    if (pos < len && (json[pos] == '+' || json[pos] == '-')) pos++
                    if (pos >= len || json[pos] !in '0'..'9') throw IllegalArgumentException("invalid float exponent")
                    while (pos < len && json[pos] in '0'..'9') pos++
                }
                val raw = json.substring(start, pos)
                val f = raw.toFloatOrNull() ?: throw IllegalArgumentException("invalid float format: $raw")
                if (!f.isFinite()) throw IllegalArgumentException("Non-finite embedding component")
                return f
            }

            fun readInt(): Int {
                skipWs()
                if (pos >= len) error("expected integer")
                if (json[pos] == '+') error("leading '+' is not permitted in JSON numbers")
                val start = pos
                if (json[pos] == '-') pos++
                if (pos >= len || json[pos] !in '0'..'9') error("expected integer digits")
                if (json[pos] == '0' && pos + 1 < len && json[pos + 1] in '0'..'9') {
                    error("leading zero is not permitted in JSON numbers")
                }
                while (pos < len && json[pos] in '0'..'9') pos++
                return json.substring(start, pos).toIntOrNull() ?: error("invalid integer")
            }

            skipWs()
            expect('{')
            var model: String = fallbackModel
            var dataItems: ArrayList<EmbeddingItem>? = null
            var promptTokens = 0
            var totalTokens = 0
            var firstRootKey = true

            while (true) {
                skipWs()
                if (pos >= len) error("unexpected EOF")
                if (json[pos] == '}') { pos++; break }
                if (!firstRootKey) {
                    expect(',')
                    skipWs()
                    if (pos < len && json[pos] == '}') error("trailing comma in root object")
                }
                firstRootKey = false
                val key = readString()
                expect(':')
                when (key) {
                    "model" -> model = readString()
                    "data" -> {
                        skipWs()
                        expect('[')
                        val list = ArrayList<EmbeddingItem>()
                        var firstItem = true
                        while (true) {
                            skipWs()
                            if (pos >= len) error("unexpected EOF in data array")
                            if (json[pos] == ']') { pos++; break }
                            if (!firstItem) {
                                expect(',')
                                skipWs()
                                if (pos < len && json[pos] == ']') error("trailing comma in data array")
                            }
                            firstItem = false
                            expect('{')
                            var itemIndex: Int? = null
                            var floatArr: FloatArray? = null
                            var firstItemField = true
                            while (true) {
                                skipWs()
                                if (pos >= len) error("unexpected EOF in item")
                                if (json[pos] == '}') { pos++; break }
                                if (!firstItemField) {
                                    expect(',')
                                    skipWs()
                                    if (pos < len && json[pos] == '}') error("trailing comma in item object")
                                }
                                firstItemField = false
                                val itemKey = readString()
                                expect(':')
                                when (itemKey) {
                                    "index" -> itemIndex = readInt()
                                    "embedding" -> {
                                        skipWs()
                                        expect('[')
                                        var count = 0
                                        var capacity = minOf(MAX_SUPPORTED_DIMENSION, expectedDimensions ?: 1536)
                                        var buf = FloatArray(capacity)
                                        var firstFloat = true
                                        while (true) {
                                            skipWs()
                                            if (pos >= len) error("unexpected EOF in embedding array")
                                            if (json[pos] == ']') { pos++; break }
                                            if (!firstFloat) {
                                                expect(',')
                                                skipWs()
                                                if (pos < len && json[pos] == ']') error("trailing comma in embedding array")
                                            }
                                            firstFloat = false
                                            if (count >= MAX_SUPPORTED_DIMENSION) {
                                                error("Embedding dimension exceeds maximum allowed ($MAX_SUPPORTED_DIMENSION)")
                                            }
                                            val f = readFloat()
                                            if (count >= buf.size) {
                                                buf = buf.copyOf(minOf(MAX_SUPPORTED_DIMENSION, buf.size * 2))
                                            }
                                            buf[count++] = f
                                        }
                                        if (count == 0) error("empty embedding array in item")
                                        floatArr = if (buf.size == count) buf else buf.copyOf(count)
                                        if (expectedDimensions != null && floatArr.size != expectedDimensions) {
                                            error("Embedding dimension mismatch: expected $expectedDimensions, got ${floatArr.size}")
                                        }
                                    }
                                    else -> skipValue()
                                }
                            }
                            if (itemIndex == null) error("missing or invalid index in item")
                            if (floatArr == null) error("missing embedding array in item")
                            list.add(EmbeddingItem(itemIndex, floatArr))
                            if (expectedInputSize != null && list.size > expectedInputSize) {
                                error("item count mismatch (expected $expectedInputSize, got ${list.size})")
                            }
                        }
                        dataItems = list
                    }
                    "usage" -> {
                        skipWs()
                        expect('{')
                        var firstUsageField = true
                        while (true) {
                            skipWs()
                            if (pos >= len) error("unexpected EOF in usage")
                            if (json[pos] == '}') { pos++; break }
                            if (!firstUsageField) {
                                expect(',')
                                skipWs()
                                if (pos < len && json[pos] == '}') error("trailing comma in usage object")
                            }
                            firstUsageField = false
                            val uKey = readString()
                            expect(':')
                            when (uKey) {
                                "prompt_tokens" -> promptTokens = readInt()
                                "total_tokens" -> totalTokens = readInt()
                                else -> skipValue()
                            }
                        }
                    }
                    else -> skipValue()
                }
            }

            skipWs()
            if (pos < len) error("trailing characters after JSON root")

            if (dataItems == null) error("missing data array")
            if (expectedInputSize != null && dataItems.size != expectedInputSize) {
                error("item count mismatch (expected $expectedInputSize, got ${dataItems.size})")
            }
            val targetSize = expectedInputSize ?: dataItems.size
            if (targetSize == 0) error("empty data array")

            val seen = BooleanArray(targetSize)
            for (item in dataItems) {
                if (item.index !in 0 until targetSize) {
                    error("index ${item.index} out of bounds (0 until $targetSize)")
                }
                if (seen[item.index]) {
                    error("duplicate index ${item.index}")
                }
                seen[item.index] = true
            }
            if (seen.any { !it }) error("missing index in items")
            dataItems.sortBy { it.index }

            return EmbeddingResponse(
                data = dataItems,
                model = model,
                promptTokens = promptTokens,
                totalTokens = if (totalTokens > 0) totalTokens else promptTokens,
            )
        }

        internal fun readBoundedBody(
            body: ResponseBody,
            maxBytes: Long,
            cancelCall: () -> Unit,
            statusCode: Int?,
        ): String {
            val contentLength = body.contentLength()
            if (contentLength > maxBytes) {
                cancelCall()
                throw EmbeddingException(
                    statusCode = statusCode,
                    errorCode = EmbeddingErrorCode.RESPONSE_TOO_LARGE,
                    retryable = false,
                    safeMessage = "Response size ($contentLength bytes) exceeded limit of $maxBytes bytes",
                )
            }

            val source = body.source()
            val buffer = Buffer()
            var remaining = maxBytes
            while (true) {
                if (remaining <= 0L) {
                    val probe = Buffer()
                    if (source.read(probe, 1L) != -1L) {
                        cancelCall()
                        throw EmbeddingException(
                            statusCode = statusCode,
                            errorCode = EmbeddingErrorCode.RESPONSE_TOO_LARGE,
                            retryable = false,
                            safeMessage = "Response exceeded maximum size limit of $maxBytes bytes",
                        )
                    }
                    break
                }
                val toRead = minOf(CHUNK_SIZE, remaining)
                val read = source.read(buffer, toRead)
                if (read == -1L) break
                remaining -= read
            }
            return buffer.readUtf8()
        }

        internal fun readBoundedErrorPrefix(
            body: ResponseBody?,
            cancelCall: () -> Unit,
        ): String? {
            if (body == null) return null
            var remaining = 16 * 1024L
            val source = body.source()
            val buffer = Buffer()
            while (remaining > 0L) {
                val read = source.read(buffer, minOf(CHUNK_SIZE, remaining))
                if (read == -1L) break
                remaining -= read
            }
            if (remaining == 0L) {
                val probe = Buffer()
                if (source.read(probe, 1L) != -1L) {
                    probe.clear()
                    cancelCall()
                }
            }
            return buffer.readUtf8()
        }

        private val NON_RETRYABLE_429_CODES = setOf(
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "insufficient_quota",
            "billing_hard_limit_reached",
        )

        internal data class ParsedErrorPayload(
            val code: String? = null,
            val type: String? = null,
        )

        internal fun parseErrorPayload(errorBody: String?): ParsedErrorPayload {
            if (errorBody.isNullOrBlank()) return ParsedErrorPayload()
            val trimmed = errorBody.trim()
            if (trimmed.startsWith("{")) {
                try {
                    val element = Json.parseToJsonElement(trimmed)
                    if (element is JsonObject) {
                        val errorObj = element["error"] as? JsonObject
                        val codePrim = errorObj?.get("code") as? JsonPrimitive
                        val typePrim = errorObj?.get("type") as? JsonPrimitive
                        val code = if (codePrim != null && codePrim !is JsonNull) codePrim.content else null
                        val type = if (typePrim != null && typePrim !is JsonNull) typePrim.content else null
                        return ParsedErrorPayload(code, type)
                    }
                } catch (_: Exception) {
                    // Not valid JSON or parse failed
                }
            }
            val lower = trimmed.lowercase()
            for (nonRetryable in NON_RETRYABLE_429_CODES) {
                if (lower.contains(nonRetryable)) {
                    return ParsedErrorPayload(code = nonRetryable)
                }
            }
            return ParsedErrorPayload(code = trimmed)
        }

        internal fun isNonRetryable429(code: String?, type: String?): Boolean {
            val codeToken = code?.trim()?.lowercase()
            val typeToken = type?.trim()?.lowercase()
            if (codeToken != null && codeToken in NON_RETRYABLE_429_CODES) return true
            if (typeToken != null && typeToken in NON_RETRYABLE_429_CODES) return true
            return false
        }

        internal fun classifyHttpError(
            statusCode: Int,
            errorCode: String?,
            errorType: String?,
        ): Pair<EmbeddingErrorCode, Boolean> = when (statusCode) {
            400, 404 -> EmbeddingErrorCode.INVALID_REQUEST to false
            401, 403 -> EmbeddingErrorCode.UNAUTHORIZED to false
            429 -> {
                val isNonRetryable = isNonRetryable429(errorCode, errorType)
                EmbeddingErrorCode.RATE_LIMITED to !isNonRetryable
            }
            500, 502, 504 -> EmbeddingErrorCode.SERVER_ERROR to true
            503 -> EmbeddingErrorCode.SERVICE_UNAVAILABLE to true
            in 500..599 -> EmbeddingErrorCode.SERVER_ERROR to false
            else -> EmbeddingErrorCode.INVALID_REQUEST to false
        }

        internal fun classifyHttpError(
            statusCode: Int,
            errorBody: String? = null,
        ): Pair<EmbeddingErrorCode, Boolean> {
            val parsed = parseErrorPayload(errorBody)
            return classifyHttpError(statusCode, parsed.code, parsed.type)
        }

        internal fun classifyNetworkError(ioe: IOException): Pair<EmbeddingErrorCode, Boolean> {
            val isTimeout = isTimeoutException(ioe)
            return EmbeddingErrorCode.NETWORK_ERROR to isTimeout
        }

        internal fun isTimeoutException(throwable: Throwable): Boolean {
            if (hasSecurityOrSslExceptionInChain(throwable)) {
                return false
            }
            if (hasCancellationOrInterruptionInChain(throwable)) {
                return false
            }
            return hasExplicitTimeoutInChain(throwable)
        }

        internal fun isSecurityOrSslException(e: Throwable): Boolean =
            e is SSLException ||
            e is GeneralSecurityException ||
            e is SecurityException

        internal fun isSslOrTlsException(e: Throwable): Boolean =
            isSecurityOrSslException(e)

        internal fun hasSecurityOrSslExceptionInChain(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                if (isSecurityOrSslException(current)) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        internal fun hasSslExceptionInChain(throwable: Throwable): Boolean =
            hasSecurityOrSslExceptionInChain(throwable)

        private fun hasCancellationOrInterruptionInChain(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                if (current is CancellationException ||
                    current is java.util.concurrent.CancellationException ||
                    current is InterruptedException
                ) {
                    return true
                }
                val msg = current.message?.lowercase().orEmpty()
                if (current is InterruptedIOException &&
                    current !is SocketTimeoutException &&
                    (msg.contains("interrupted") || msg.contains("cancel"))
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        private fun hasExplicitTimeoutInChain(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                if (current is SocketTimeoutException) {
                    return true
                }
                if (current is InterruptedIOException) {
                    val msg = current.message?.lowercase().orEmpty()
                    if (msg.contains("timeout") || msg.contains("timed out")) {
                        return true
                    }
                }
                current = current.cause
            }
            return false
        }

        internal fun safeSnippet(raw: String?, activeKey: String? = null, limit: Int = 200): String =
            safeSnippet(raw, activeKey?.toCharArray(), limit)

        internal fun safeSnippet(raw: String?, keyChars: CharArray?, limit: Int = 200): String {
            if (raw.isNullOrBlank()) return ""
            val redacted = redactError(raw, keyChars)
            val oneLine = redacted.replace(Regex("\\s+"), " ").trim()
            if (oneLine.length <= limit) return oneLine

            var cutIndex = limit
            val openBracket = oneLine.lastIndexOf("⟦", cutIndex)
            val closeBracket = oneLine.lastIndexOf("⟧", cutIndex)
            if (openBracket != -1 && openBracket > closeBracket) {
                val nextClose = oneLine.indexOf("⟧", cutIndex)
                if (nextClose != -1 && nextClose <= limit + 64) {
                    cutIndex = nextClose + 1
                }
            }
            return if (cutIndex >= oneLine.length) oneLine else oneLine.take(cutIndex) + "…"
        }

        internal fun redactKey(message: String, keyChars: CharArray?): String {
            if (keyChars == null || keyChars.isEmpty()) return message
            val keyLen = keyChars.size
            val msgLen = message.length
            if (msgLen < keyLen) return message

            var out: StringBuilder? = null
            var cursor = 0
            var i = 0
            while (i <= msgLen - keyLen) {
                var matches = true
                for (j in 0 until keyLen) {
                    val mc = message[i + j]
                    val kc = keyChars[j]
                    if (mc.lowercaseChar() != kc.lowercaseChar()) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    if (out == null) out = StringBuilder(msgLen + 32)
                    out.append(message, cursor, i)
                    out.append("⟦REDACTED:API_KEY⟧")
                    cursor = i + keyLen
                    i = cursor
                } else {
                    i++
                }
            }
            if (out == null) return message
            if (cursor < msgLen) out.append(message, cursor, msgLen)
            return out.toString()
        }

        internal fun redactError(message: String, keyChars: CharArray?): String {
            val text = redactKey(message, keyChars)
            return Redactor.redactError(text)
        }

        internal fun redactError(message: String, activeKey: String?): String {
            var text = message
            if (!activeKey.isNullOrEmpty()) {
                text = text.replace(activeKey, "⟦REDACTED:API_KEY⟧", ignoreCase = true)
            }
            return Redactor.redactError(text)
        }
    }
}
