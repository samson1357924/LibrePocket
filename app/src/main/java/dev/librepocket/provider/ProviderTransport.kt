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
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import okio.Buffer

/** Internal transport failure; [retryable] comes from [ProviderErrorClassifier] unless a typed code applies. */
internal enum class ProviderFailureCode {
    /** Bounded finite body (model list) exceeded its byte budget. */
    TOO_LARGE,
    /**
     * Unknown runtime failure from the provider stream (e.g. an unexpected
     * `RuntimeException` that is neither a typed `ProviderFailure`, validation
     * `IllegalArgumentException`, nor classified `IOException`). Always
     * non-retryable and never flushes poison-adjacent pending tool fragments
     * (see `shouldDiscardPendingTools`); the typed terminal is the record.
     */
    UNKNOWN_RUNTIME,
    /**
     * A streaming aggregate exceeded its memory budget (per-tool-call
     * ToolDelta fragments, the per-attempt whole-round tool total, the
     * distinct-tool count, one SSE event's total `data:` payload, or the
     * per-attempt visible-text budget `TEXT_TOO_LARGE`).
     * Always pairs with a distinct non-retryable message
     * (`TOOL_ARGS_TOO_LARGE` / `TEXT_TOO_LARGE` / `SSE_FRAME_TOO_LARGE` / `SSE_LINE_TOO_LONG`),
     * never silent truncation, and never a retry of the poisoned stream.
     */
    AGG_TOO_LARGE,
}

internal class ProviderFailure(
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val code: ProviderFailureCode? = null,
) : IOException(message, cause)

/**
 * Infer the typed failure-code name from a provider-failure message when
 * [ProviderFailure.code] is absent (legacy throws or message-only paths).
 * Matches the aggregation/SSE overflow markers case-insensitively, consistent
 * with [ProviderErrorClassifier]'s fatal markers; returns
 * `AGG_TOO_LARGE`'s name on a hit, null otherwise. Never infers `TOO_LARGE`
 * (finite model-list budget) here: streaming callers only need the
 * aggregation/SSE code for [StreamEvent.Failed.code] propagation.
 */
internal fun inferAggFailureCodeName(message: String?): String? {
    val lower = message?.lowercase(java.util.Locale.ROOT).orEmpty()
    return if (lower.contains(TOOL_ARGS_TOO_LARGE.lowercase(java.util.Locale.ROOT)) ||
        lower.contains(TEXT_TOO_LARGE.lowercase(java.util.Locale.ROOT)) ||
        lower.contains(SseFrameParser.FRAME_TOO_LARGE_MESSAGE.lowercase(java.util.Locale.ROOT)) ||
        lower.contains(SseFrameParser.LINE_TOO_LONG_MESSAGE.lowercase(java.util.Locale.ROOT))
    ) {
        ProviderFailureCode.AGG_TOO_LARGE.name
    } else {
        null
    }
}

private const val MODEL_LIST_RESPONSE_MAX_BYTES = 1024 * 1024
private const val HTTP_ERROR_BODY_MAX_BYTES = 16 * 1024
private const val RESPONSE_READ_CHUNK_BYTES = 8 * 1024

/**
 * OkHttp client per SPEC §5.1: 15s connect / 30s write / 5min read-idle,
 * with redirects and connection-failure retries disabled.
 *
 * Single-request contract: OkHttp 5.5.0 retries 503 + Retry-After: 0 inside
 * RetryAndFollowUpInterceptor without checking retryOnConnectionFailure.
 * The network interceptor below strips that trigger so the raw 503 returns
 * to TurnController (sole retry owner). Classifier/callers use code/body only.
 */
private fun OkHttpClient.Builder.denyUnbudgetedResends() = apply {
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
    addNetworkInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build()
        else response
    }
}

fun defaultOkHttpClient(http: ProviderHttpConfig): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(http.connectTimeoutMs, TimeUnit.MILLISECONDS)
    .writeTimeout(http.writeTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(http.readTimeoutMs, TimeUnit.MILLISECONDS)
    .denyUnbudgetedResends()
    .build()

/** Enforce the provider redirect policy even when callers inject a client. */
internal fun providerTransportClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
    .denyUnbudgetedResends()
    .build()

/** A redirect is a terminal response: never replay credentials or request bodies. */
internal enum class SsePumpDecision { CONTINUE, STOP }

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** POST bodies are one-shot so OkHttp never replays them outside the controller budget. */
private class OneShotJsonBody(private val json: String) : RequestBody() {
    override fun contentType() = JSON_MEDIA_TYPE
    override fun contentLength(): Long = json.toByteArray(Charsets.UTF_8).size.toLong()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(json)
    }
}

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
            else -> if (c < ' ') sb.append("\\u%04x".format(java.util.Locale.ROOT, c.code)) else sb.append(c)
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
 * Read a successful finite provider body with a hard byte cap before decoding.
 * The one-byte probe distinguishes an exact-cap EOF from an oversized body.
 * The actual Call must be cancelled before response.close() can try to discard
 * unread bytes from the network.
 */
internal fun readBoundedProviderBody(
    body: ResponseBody,
    cancelCall: () -> Unit,
): String {
    val limit = MODEL_LIST_RESPONSE_MAX_BYTES.toLong()
    if (body.contentLength() > limit) {
        cancelCall()
        throw tooLargeProviderFailure()
    }

    val source = body.source()
    val bytes = Buffer()
    var remaining = limit
    while (true) {
        val requested = minOf(RESPONSE_READ_CHUNK_BYTES.toLong(), remaining + 1L)
        val read = source.read(bytes, requested)
        if (read == -1L) break
        if (read > remaining) {
            cancelCall()
            throw tooLargeProviderFailure()
        }
        remaining -= read
    }
    return bytes.readByteArray().toResponseBody(body.contentType()).string()
}

/**
 * Read and decode only the bounded prefix of an HTTP error body. Unlike a
 * successful JSON body, a capped error remains classified by its HTTP status
 * and markers present in this prefix. A one-byte sentinel probe distinguishes
 * an exact-cap EOF from a truly oversized body: only when a byte exists
 * beyond the cap is the actual Call cancelled, so closing the response cannot
 * spend time draining the remainder. An exact-cap body ending at the boundary
 * leaves the Call uncancelled.
 */
internal fun readProviderErrorPrefix(
    body: ResponseBody?,
    cancelCall: () -> Unit,
): String? {
    if (body == null) return null
    var remaining = HTTP_ERROR_BODY_MAX_BYTES.toLong()
    val source = body.source()
    val bytes = Buffer()
    while (remaining > 0L) {
        val read = source.read(bytes, minOf(RESPONSE_READ_CHUNK_BYTES.toLong(), remaining))
        if (read == -1L) break
        remaining -= read
    }
    if (remaining == 0L) {
        // Sentinel: exact-cap EOF probes -1 (no cancel); true overflow probes
        // one byte (cancel). The probe byte is discarded; the prefix stays capped.
        val probe = Buffer()
        if (source.read(probe, 1L) != -1L) {
            probe.clear()
            cancelCall()
        }
    }
    return bytes.readByteArray().toResponseBody(body.contentType()).string()
}

private fun tooLargeProviderFailure() = ProviderFailure(
    retryable = false,
    message = "TOO_LARGE provider model-list response",
    code = ProviderFailureCode.TOO_LARGE,
)

/**
 * Execute a non-streaming provider request. The cancellation handler is active
 * from before execute() through complete body consumption and is disposed on
 * every exit path. Call.cancel() therefore interrupts blocking header/body IO
 * as soon as the Job enters cancelling, rather than waiting for completion.
 *
 * Cancellation is judged by the coroutine Job (ensureActive) only, never by
 * Call.isCanceled(): OkHttp's own callTimeout self-cancels the call, and that
 * timeout must stay a classifiable IOException, not a CancellationException.
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> executeProviderRequest(
    client: OkHttpClient,
    request: Request,
    consume: (response: Response, cancelCall: () -> Unit) -> T,
): T = withContext(Dispatchers.IO) {
    val call = client.newCall(request)
    val job = currentCoroutineContext()[Job]
    val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) call.cancel()
    }
    try {
        currentCoroutineContext().ensureActive()
        call.execute().use { response -> consume(response) { call.cancel() } }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
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
            val kind = ProviderErrorClassifier.classify(null, e, null)
            throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("SSE_TRANSPORT", e.message), e)
        }
        var protocolTerminalReached = false
        try {
            response.use { response ->
                if (!response.isSuccessful) {
                    val snippet = try {
                        readProviderErrorPrefix(response.body) { call.cancel() }
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
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
                        val kind = ProviderErrorClassifier.classify(null, e, null)
                        throw ProviderFailure(kind == FailureKind.RETRYABLE, redactedError("SSE_TRANSPORT", e.message), e)
                    }
                    if (read == -1) {
                        currentCoroutineContext().ensureActive()
                        for (frame in parser.flush()) {
                            currentCoroutineContext().ensureActive()
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
                        currentCoroutineContext().ensureActive()
                        if (onFrame(frame) == SsePumpDecision.STOP) {
                            protocolTerminalReached = true
                            stopped = true
                            // Stop reading the body; Response.use closes it on exit.
                            break
                        }
                    }
                    if (!protocolTerminalReached) {
                        currentCoroutineContext().ensureActive()
                        if (parser.lineTooLong) {
                            throw ProviderFailure(false, SseFrameParser.LINE_TOO_LONG_MESSAGE, code = ProviderFailureCode.AGG_TOO_LARGE)
                        }
                        if (parser.frameTooLong) {
                            throw ProviderFailure(false, SseFrameParser.FRAME_TOO_LARGE_MESSAGE, code = ProviderFailureCode.AGG_TOO_LARGE)
                        }
                    }
                }
                if (!protocolTerminalReached) {
                    currentCoroutineContext().ensureActive()
                    if (parser.lineTooLong) {
                        throw ProviderFailure(false, SseFrameParser.LINE_TOO_LONG_MESSAGE, code = ProviderFailureCode.AGG_TOO_LARGE)
                    }
                    if (parser.frameTooLong) {
                        throw ProviderFailure(false, SseFrameParser.FRAME_TOO_LARGE_MESSAGE, code = ProviderFailureCode.AGG_TOO_LARGE)
                    }
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

/** Build a POST request with a one-shot JSON body; [headers] iteration order preserved. */
internal fun postJson(url: String, headers: LinkedHashMap<String, String>, bodyJson: String): Request {
    val b = Request.Builder().url(url).post(OneShotJsonBody(bodyJson))
    for ((k, v) in headers) b.header(k, v)
    return b.build()
}

/**
 * Provider stream plumbing: exactly one attempt, with upstream failures
 * classified into one terminal event. `catch` preserves Flow exception
 * transparency for failures thrown by the downstream collector.
 *
 * `code` propagation (Stage D): a [ProviderFailure] becomes
 * [StreamEvent.Failed] with `code = e.code?.name ?: inferAggFailureCodeName(e.message)`,
 * so aggregation/SSE budget overflows (`AGG_TOO_LARGE` with distinct
 * `TOOL_ARGS_TOO_LARGE` / `TEXT_TOO_LARGE` / `SSE_FRAME_TOO_LARGE` / `SSE_LINE_TOO_LONG`
 * messages) stay typed end-to-end for TurnController's pending-flush guard.
 * The `IllegalArgumentException` and `IOException` branches carry
 * `code = inferAggFailureCodeName(e.message)` (null for non-aggregation
 * messages), and the unknown-runtime fallback carries
 * `inferAggFailureCodeName(e.message) ?: UNKNOWN_RUNTIME`, so the controller
 * can discard poison-adjacent pending tools without changing retry semantics
 * of other fatal events (401/validation stay flushable).
 * Unknown runtime failures fail closed (`retryable = false`): only
 * classifier-approved branches may retry (`ProviderFailure.retryable` /
 * `IOException` via `ProviderErrorClassifier`; `IAE` and unknown never
 * retry), and TurnController owns that budget.
 * `VM-fatal` (`VirtualMachineError`/`ThreadDeath`) and `CancellationException`
 * always propagate without conversion (never a `Failed` event), matching
 * TurnController's rethrow guarantees.
 */
internal fun streamingFlow(block: suspend FlowCollector<StreamEvent>.() -> Unit): Flow<StreamEvent> =
    flow(block)
        .catch { e ->
            if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            currentCoroutineContext().ensureActive()
            when (e) {
                is ProviderFailure -> emit(
                    StreamEvent.Failed(
                        redactedError(e.message ?: "PROVIDER_FAILED"),
                        e.retryable,
                        e.code?.name ?: inferAggFailureCodeName(e.message),
                    ),
                )
                is IllegalArgumentException -> emit(
                    StreamEvent.Failed(
                        redactedError("PROVIDER_BAD_REQUEST", e.message),
                        retryable = false,
                        code = inferAggFailureCodeName(e.message),
                    ),
                )
                is IOException -> {
                    val kind = ProviderErrorClassifier.classify(null, e, null)
                    // Aggregation-budget overflow markers dominate transport
                    // classification: an AGG_TOO_LARGE stream is poisoned and
                    // never retries, even when the classifier sees a plain
                    // IOException shape.
                    val aggCode = inferAggFailureCodeName(e.message)
                    emit(
                        StreamEvent.Failed(
                            redactedError("PROVIDER_TRANSPORT", e.message),
                            retryable = kind == FailureKind.RETRYABLE && aggCode == null,
                            code = aggCode,
                        ),
                    )
                }
                else -> emit(
                    StreamEvent.Failed(
                        redactedError(e.message ?: "PROVIDER_FAILURE"),
                        retryable = false,
                        code = inferAggFailureCodeName(e.message) ?: ProviderFailureCode.UNKNOWN_RUNTIME.name,
                    ),
                )
            }
        }
        .flowOn(Dispatchers.IO)
