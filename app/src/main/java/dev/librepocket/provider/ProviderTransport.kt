package dev.librepocket.provider

import dev.librepocket.redact.Redactor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/** Internal transport failure; [retryable] comes from [ProviderErrorClassifier]. */
internal class ProviderFailure(
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * OkHttp client per SPEC §5.1: 15s connect / 30s write / 5min read-idle,
 * with redirects and connection-failure retries disabled.
 */
fun defaultOkHttpClient(http: ProviderHttpConfig): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(http.connectTimeoutMs, TimeUnit.MILLISECONDS)
    .writeTimeout(http.writeTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(http.readTimeoutMs, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/** Enforce the provider redirect policy even when callers inject a client. */
internal fun providerTransportClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

/** A redirect is a terminal response: never replay credentials or request bodies. */
internal enum class SsePumpDecision { CONTINUE, STOP }

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
 * Execute a non-streaming provider request. The cancellation handler is active
 * from before execute() through complete body consumption and is disposed on
 * every exit path. Call.cancel() therefore interrupts blocking header/body IO
 * as soon as the Job enters cancelling, rather than waiting for completion.
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> executeProviderRequest(
    client: OkHttpClient,
    request: Request,
    consume: (Response) -> T,
): T = withContext(Dispatchers.IO) {
    val call = client.newCall(request)
    val job = currentCoroutineContext()[Job]
    val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }
    try {
        currentCoroutineContext().ensureActive()
        call.execute().use(consume)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        if (call.isCanceled()) throw CancellationException("provider request cancelled", e)
        throw e
    } finally {
        cancellation?.dispose()
    }
}

/**
 * Execute [request] and pump SSE frames into [onFrame]. HTTP error codes and
 * transport errors are classified as one provider attempt. [onFrame] returns
 * [SsePumpDecision.STOP] when the protocol reaches a terminal frame; the
 * response is then closed immediately without reading later bytes.
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun pumpSse(
    client: OkHttpClient,
    request: Request,
    onFrame: suspend (SseFrameParser.Frame) -> SsePumpDecision,
) {
    val call = client.newCall(request)
    val job = currentCoroutineContext()[Job]
    val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }
    try {
        currentCoroutineContext().ensureActive()
        val response = try {
            call.execute()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (call.isCanceled()) throw CancellationException("provider request cancelled", e)
            val kind = ProviderErrorClassifier.classify(null, e, null)
            throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("SSE_TRANSPORT", e.message), e)
        }
        var protocolTerminalReached = false
        try {
            response.use { response ->
                if (!response.isSuccessful) {
                    val snippet = try {
                        response.body?.string()?.take(2048)
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (call.isCanceled()) throw CancellationException("provider request cancelled", e)
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
                var stopped = false
                while (!stopped) {
                    currentCoroutineContext().ensureActive()
                    val read: Int = try {
                        source.read(buf, 0, buf.size)
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        if (call.isCanceled()) throw CancellationException("provider request cancelled", e)
                        val kind = ProviderErrorClassifier.classify(null, e, null)
                        throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("SSE_TRANSPORT", e.message), e)
                    }
                    if (read == -1) {
                        for (frame in parser.flush()) {
                            if (onFrame(frame) == SsePumpDecision.STOP) {
                                protocolTerminalReached = true
                                stopped = true
                                break
                            }
                        }
                        break
                    }
                    val frames = parser.feed(buf, 0, read)
                    for (frame in frames) {
                        if (onFrame(frame) == SsePumpDecision.STOP) {
                            protocolTerminalReached = true
                            stopped = true
                            // Stop reading the body; Response.use closes it on exit.
                            break
                        }
                    }
                    if (parser.lineTooLong && !protocolTerminalReached) {
                        throw ProviderFailure(false, "SSE_LINE_TOO_LONG")
                    }
                }
                if (parser.lineTooLong && !protocolTerminalReached) {
                    throw ProviderFailure(false, "SSE_LINE_TOO_LONG")
                }
            }
        } catch (e: IOException) {
            // A protocol terminal already emitted the authoritative Done/Failed.
            // Do not append a second terminal event if close reports cleanup IO.
            if (!protocolTerminalReached) throw e
        }
    } finally {
        cancellation?.dispose()
    }
}

/** Build a POST request with a JSON body; [headers] iteration order preserved. */
internal fun postJson(url: String, headers: LinkedHashMap<String, String>, bodyJson: String): Request {
    val b = Request.Builder().url(url).post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
    for ((k, v) in headers) b.header(k, v)
    return b.build()
}

/**
 * Provider stream plumbing: exactly one attempt, with upstream failures
 * classified into one terminal event. `catch` preserves Flow exception
 * transparency for failures thrown by the downstream collector.
 */
internal fun streamingFlow(block: suspend FlowCollector<StreamEvent>.() -> Unit): Flow<StreamEvent> =
    flow(block)
        .catch { e ->
            currentCoroutineContext().ensureActive()
            when (e) {
                is CancellationException -> throw e
                is ProviderFailure -> emit(StreamEvent.Failed(redactedError(e.message ?: "PROVIDER_FAILED"), e.retryable))
                is IllegalArgumentException -> emit(
                    StreamEvent.Failed(redactedError("PROVIDER_BAD_REQUEST", e.message), retryable = false),
                )
                is IOException -> {
                    val kind = ProviderErrorClassifier.classify(null, e, null)
                    emit(StreamEvent.Failed(redactedError("PROVIDER_TRANSPORT", e.message), kind == FailureKind.RETRYABLE))
                }
                else -> emit(StreamEvent.Failed(redactedError(e.message ?: "PROVIDER_FAILURE"), retryable = true))
            }
        }
        .flowOn(Dispatchers.IO)
