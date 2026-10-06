package dev.librepocket.provider

import dev.librepocket.redact.Redactor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Internal transport failure; [retryable] comes from [ProviderErrorClassifier]. */
internal class ProviderFailure(
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** OkHttp client per SPEC §5.1: 15s connect / 30s write / 5min read-idle, no built-in retry. */
fun defaultOkHttpClient(http: ProviderHttpConfig): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(http.connectTimeoutMs, TimeUnit.MILLISECONDS)
    .writeTimeout(http.writeTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(http.readTimeoutMs, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(false)
    .build()

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Append [suffixPath] unless [base] already ends with it (path completion). */
internal fun joinEndpoint(base: String, suffixPath: String): String {
    val b = base.trimEnd('/')
    val s = "/" + suffixPath.trimStart('/')
    return if (b.endsWith(s)) b else b + s
}

internal fun jsonEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 2)
    for (c in raw) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}

internal fun q(s: String): String = "\"${jsonEscape(s)}\""

/** Short, key-free snippet for error messages (never the request or headers). */
internal fun safeSnippet(raw: String?, limit: Int = 200): String {
    if (raw.isNullOrBlank()) return ""
    val oneLine = raw.replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= limit) oneLine else oneLine.take(limit) + "…"
}

/**
 * B2: every provider error message funnels through here.
 * Two-level semantics preserved: [safeSnippet] truncates the body to 200,
 * then [Redactor.redactError] strips keys/tokens/URL credentials/private
 * IPs and caps the whole message at 500.
 */
internal fun redactedError(prefix: String, rawSnippet: String?): String =
    Redactor.redactError("$prefix ${safeSnippet(rawSnippet)}".trim())

/** Redact a fully-assembled error message (e.g. re-emitted [ProviderFailure] text). */
internal fun redactedError(message: String): String = Redactor.redactError(message)

/**
 * Retry wrapper shared by all providers (SPEC §5.3):
 * - every attempt runs [block]; a [ProviderFailure] with `retryable=true`
 *   and remaining budget emits [StreamEvent.Retrying] and waits the fixed
 *   2s/4s/8s sequence (cancellable [delay]);
 * - fatal failures and exhausted budgets end in [StreamEvent.Failed];
 * - coroutine cancellation is never converted into [StreamEvent.Failed].
 */
internal suspend fun FlowCollector<StreamEvent>.runWithRetry(
    http: ProviderHttpConfig,
    block: suspend (attempt: Int) -> Unit,
) {
    var attempt = 0
    while (true) {
        try {
            block(attempt)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailure) {
            currentCoroutineContext().ensureActive()
            val exhausted = !e.retryable || attempt >= http.maxRetries
            if (exhausted) {
                emit(StreamEvent.Failed(redactedError(e.message ?: "PROVIDER_FAILED"), e.retryable))
                return
            }
            val waitMs = http.retryDelaysMs.getOrElse(attempt) { http.retryDelaysMs.last() }
            emit(StreamEvent.Retrying(attempt + 1, http.maxRetries, waitMs))
            delay(waitMs)
            attempt++
        }
    }
}

/**
 * Execute [request] and pump SSE frames into [onFrame].
 * HTTP error codes are classified via [ProviderErrorClassifier]; transport
 * errors likewise. Throws [ProviderFailure] (or [CancellationException]).
 */
internal suspend fun pumpSse(
    client: OkHttpClient,
    request: Request,
    onFrame: suspend (SseFrameParser.Frame) -> Unit,
) {
    val call = client.newCall(request)
    currentCoroutineContext().job?.invokeOnCompletion { call.cancel() }
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val snippet = try {
                    response.body?.string()?.take(2048)
                } catch (_: IOException) {
                    null
                }
                val kind = ProviderErrorClassifier.classify(response.code, null, snippet)
                throw ProviderFailure(
                    retryable = kind == FailureKind.RETRYABLE,
                    message = redactedError("HTTP ${response.code}", snippet),
                )
            }
            val body = response.body
                ?: throw ProviderFailure(true, "SSE_EMPTY_BODY")
            val parser = SseFrameParser()
            val source = body.source()
            val buf = ByteArray(8192)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read: Int = try {
                        source.read(buf, 0, buf.size)
                    } catch (e: IOException) {
                        if (call.isCanceled()) throw CancellationException("cancelled", e)
                        throw e
                    }
                    if (read == -1) break
                    for (frame in parser.feed(buf, 0, read)) onFrame(frame)
                    if (parser.lineTooLong) {
                        throw ProviderFailure(false, "SSE_LINE_TOO_LONG")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderFailure) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                val kind = ProviderErrorClassifier.classify(null, e, null)
                throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("SSE_TRANSPORT", e.message), e)
            }
            if (parser.lineTooLong) throw ProviderFailure(false, "SSE_LINE_TOO_LONG")
            for (frame in parser.flush()) onFrame(frame)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProviderFailure) {
        throw e
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        if (call.isCanceled()) throw CancellationException("cancelled", e)
        val kind = ProviderErrorClassifier.classify(null, e, null)
        throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("SSE_TRANSPORT", e.message), e)
    } catch (e: IllegalArgumentException) {
        // URL/protocol misconfiguration.
        throw ProviderFailure(false, "PROVIDER_BAD_REQUEST", e)
    }
}

/** Build a POST request with a JSON body; [headers] iteration order preserved. */
internal fun postJson(url: String, headers: LinkedHashMap<String, String>, bodyJson: String): Request {
    val b = Request.Builder().url(url).post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
    for ((k, v) in headers) b.header(k, v)
    return b.build()
}

internal fun streamingFlow(block: suspend FlowCollector<StreamEvent>.() -> Unit): Flow<StreamEvent> =
    flow(block).flowOn(Dispatchers.IO)
