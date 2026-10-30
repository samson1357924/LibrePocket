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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CleanCancellationException(message: String) :
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
 *   (default 5MB); exceeding the cap cancels the OkHttp Call and fails with [EmbeddingErrorCode.RESPONSE_TOO_LARGE].
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
    @OptIn(InternalCoroutinesApi::class)
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

        if (keyChars.isEmpty()) {
            throw EmbeddingException(
                statusCode = 401,
                errorCode = EmbeddingErrorCode.UNAUTHORIZED,
                retryable = false,
                safeMessage = "API key is missing or empty",
            )
        }

        val activeKeyString = String(keyChars)
        val httpRequest: Request
        try {
            val endpoint = resolveEndpoint(baseUrl)
            val bodyJson = buildRequestBodyJson(request.copy(input = safeInput), redactInput)
            val requestBody = bodyJson.toRequestBody(JSON_MEDIA_TYPE)
            httpRequest = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $activeKeyString")
                .post(requestBody)
                .build()
        } finally {
            keyChars.fill('\u0000')
        }

        fun redactErrMsg(msg: String): String = redactError(msg, activeKeyString)

        val call = client.newCall(httpRequest)
        val job = currentCoroutineContext()[Job]
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                call.cancel()
            }
        }

        try {
            currentCoroutineContext().ensureActive()
            val response = call.execute()
            try {
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) {
                    val errorSnippet = readBoundedErrorPrefix(response.body) { call.cancel() }.orEmpty()
                    val (code, retryable) = classifyHttpError(response.code)
                    val snippet = safeSnippet(errorSnippet, activeKeyString)
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
            val safeMsg = redactErrMsg("Network error: ${ioe.message}")
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
            val safeMsg = redactErrMsg("Security error: ${se.message}")
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

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024L // 5 MB (safely bounds decoded response-body bytes on mobile)
        const val CALL_TIMEOUT_SECONDS = 60L
        const val MAX_SUPPORTED_DIMENSION = 3072
        private const val CHUNK_SIZE = 8192L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
            return scheme == "https" && host == "api.openai.com" && url.port == 443
        }

        internal fun validateEndpoint(baseUrl: String, allowTestLoopback: Boolean): HttpUrl {
            val httpUrl = baseUrl.toHttpUrlOrNull()
                ?: throw EmbeddingException(
                    statusCode = null,
                    errorCode = EmbeddingErrorCode.ENDPOINT_NOT_ALLOWED,
                    retryable = false,
                    safeMessage = "Invalid baseUrl: cannot parse URL",
                )
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
            val trimmed = baseUrl.trimEnd('/')
            return when {
                trimmed.endsWith("/v1/embeddings") -> trimmed
                trimmed.endsWith("/embeddings") -> trimmed
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
                            readString()
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
                    '"' -> readString()
                    't' -> {
                        if (pos + 4 <= len && json.substring(pos, pos + 4) == "true") pos += 4
                        else error("invalid literal")
                    }
                    'f' -> {
                        if (pos + 5 <= len && json.substring(pos, pos + 5) == "false") pos += 5
                        else error("invalid literal")
                    }
                    'n' -> {
                        if (pos + 4 <= len && json.substring(pos, pos + 4) == "null") pos += 4
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
                                        var capacity = 512
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

        internal fun classifyHttpError(statusCode: Int): Pair<EmbeddingErrorCode, Boolean> = when (statusCode) {
            400, 404 -> EmbeddingErrorCode.INVALID_REQUEST to false
            401, 403 -> EmbeddingErrorCode.UNAUTHORIZED to false
            429 -> EmbeddingErrorCode.RATE_LIMITED to true
            503 -> EmbeddingErrorCode.SERVICE_UNAVAILABLE to true
            504 -> EmbeddingErrorCode.SERVER_ERROR to true
            in 500..599 -> EmbeddingErrorCode.SERVER_ERROR to false
            else -> EmbeddingErrorCode.INVALID_REQUEST to false
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

        internal fun safeSnippet(raw: String?, activeKey: String? = null, limit: Int = 200): String {
            if (raw.isNullOrBlank()) return ""
            val redacted = redactError(raw, activeKey)
            val oneLine = redacted.replace(Regex("\\s+"), " ").trim()
            if (oneLine.length <= limit) return oneLine

            var cutIndex = limit
            val openBracket = oneLine.lastIndexOf("⟦", cutIndex)
            val closeBracket = oneLine.lastIndexOf("⟧", cutIndex)
            if (openBracket != -1 && openBracket > closeBracket) {
                val nextClose = oneLine.indexOf("⟧", cutIndex)
                if (nextClose != -1) {
                    cutIndex = nextClose + 1
                }
            }
            return if (cutIndex >= oneLine.length) oneLine else oneLine.take(cutIndex) + "…"
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
