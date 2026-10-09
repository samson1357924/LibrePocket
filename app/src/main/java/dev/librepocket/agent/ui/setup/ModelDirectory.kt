package dev.librepocket.agent.ui.setup

import dev.librepocket.models.ModelsDevSnapshot
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** Bounded, keyless models.dev directory transport. */
object ModelDirectory {
    data class FetchBudget(
        val maxWireBytes: Long = DEFAULT_MAX_WIRE_BYTES,
        val maxDecodedBytes: Long = DEFAULT_MAX_DECODED_BYTES,
        val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        val readIdleTimeoutMs: Long = DEFAULT_READ_IDLE_TIMEOUT_MS,
        val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    ) {
        init {
            require(maxWireBytes > 0 && maxWireBytes < Long.MAX_VALUE) {
                "maxWireBytes must be positive and less than Long.MAX_VALUE"
            }
            require(maxDecodedBytes > 0 && maxDecodedBytes < Long.MAX_VALUE) {
                "maxDecodedBytes must be positive and less than Long.MAX_VALUE"
            }
            require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
            require(readIdleTimeoutMs > 0) { "readIdleTimeoutMs must be positive" }
            require(callTimeoutMs > 0) { "callTimeoutMs must be positive" }
        }
    }

    const val DEFAULT_MAX_WIRE_BYTES = 4L * 1024 * 1024
    const val DEFAULT_MAX_DECODED_BYTES = 4L * 1024 * 1024
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    const val DEFAULT_READ_IDLE_TIMEOUT_MS = 10_000L
    const val DEFAULT_CALL_TIMEOUT_MS = 20_000L

    /**
     * Fetch the directory body under independent byte caps and a whole-call
     * deadline. Wire bytes are the encoded HTTP body before transparent gzip,
     * not TCP/TLS, headers, chunk framing, or a socket-buffer hard cap. Okio may
     * prefetch bounded internal buffers beyond the cap+1 bytes delivered here.
     * The fetch never inherits the caller's interceptors, cookieJar, or cache;
     * it builds an isolated client from [budget] timeouts and strips
     * Authorization, Proxy-Authorization, Cookie, x-api-key, api-key, and
     * x-goog-api-key at both the application and network layers. The [client]
     * argument is accepted for API compatibility; its configuration is
     * intentionally ignored so any passed client upholds the same guarantee,
     * except its EventListener.Factory, which is propagated as observability
     * only and cannot alter requests.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun fetchBody(
        client: OkHttpClient,
        url: String,
        budget: FetchBudget = FetchBudget(),
    ): String = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .get()
                .build()
        } catch (_: IllegalArgumentException) {
            throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.INVALID_URL)
        }
        if (request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) {
            throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.INVALID_URL)
        }

        // Build an isolated client from scratch so no caller configuration is
        // inherited: no application/network interceptors, cookieJar, cache,
        // authenticator, or redirect/retry policy. Only the budget timeouts
        // are reused. The wire budget interceptor below is the sole network
        // interceptor, so with no Cache configured nothing can short-circuit the cap.
        // The caller's EventListener.Factory is propagated as observability
        // only: listeners cannot modify requests, so the keyless guarantee is
        // unaffected, and callers keep visibility into cancel/close behavior.
        val boundedClient = OkHttpClient.Builder()
            .connectTimeout(budget.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(budget.readIdleTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(budget.callTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .eventListenerFactory(client.eventListenerFactory)
            // Defense in depth: strip auth material at the application layer
            // even though the isolated client inherits no caller interceptors.
            // removeHeader is case-insensitive.
            .addInterceptor { chain ->
                val keyless = chain.request().newBuilder()
                    .removeHeader("Authorization")
                    .removeHeader("Proxy-Authorization")
                    .removeHeader("Cookie")
                    .removeHeader("x-api-key")
                    .removeHeader("api-key")
                    .removeHeader("x-goog-api-key")
                    .build()
                chain.proceed(keyless)
            }
            .addNetworkInterceptor(wireBudgetInterceptor(budget.maxWireBytes))
            .build()

        val call = boundedClient.newCall(request)
        val job = currentCoroutineContext()[Job]
        val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        var responseReceived = false
        try {
            currentCoroutineContext().ensureActive()
            val response = call.execute()
            responseReceived = true
            response.use {
                try {
                    if (!it.isSuccessful) {
                        call.cancel()
                        throw fetchFailure(
                            reason = ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS,
                            httpStatusCode = it.code,
                        )
                    }
                    val body = it.body
                    val contentEncoding = it.header("Content-Encoding")
                    if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
                        call.cancel()
                        throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.UNSUPPORTED_CONTENT_ENCODING)
                    }
                    readDecodedBody(body, budget.maxDecodedBytes, call)
                } catch (failure: Exception) {
                    // Cancel before use{} closes the body; HTTP/1 close may
                    // otherwise try to discard unread bytes outside our counter.
                    call.cancel()
                    throw failure
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelsDevSnapshot.FetchException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: IOException) {
            // OkHttp.cancel() interrupts blocking execute/body reads. If the
            // caller Job is cancelling, preserve cancellation, never fallback.
            currentCoroutineContext().ensureActive()
            val reason = if (e is InterruptedIOException) {
                ModelsDevSnapshot.SnapshotFallbackReason.TIMEOUT
            } else if (responseReceived) {
                ModelsDevSnapshot.SnapshotFallbackReason.TRUNCATED_BODY
            } else {
                ModelsDevSnapshot.SnapshotFallbackReason.NETWORK_ERROR
            }
            throw fetchFailure(reason)
        } finally {
            cancellation?.dispose()
        }
    }

    private fun wireBudgetInterceptor(maxBytes: Long): Interceptor = Interceptor { chain ->
        // Defense in depth: strip auth material at the final request layer in
        // case this interceptor is ever attached to a client that inherits
        // caller network interceptors. removeHeader is case-insensitive.
        val keylessRequest = chain.request().newBuilder()
            .removeHeader("Authorization")
            .removeHeader("Proxy-Authorization")
            .removeHeader("Cookie")
            .removeHeader("x-api-key")
            .removeHeader("api-key")
            .removeHeader("x-goog-api-key")
            .build()
        val response = chain.proceed(keylessRequest)
        // Fail before RetryAndFollowUpInterceptor can replay a non-2xx response
        // (including 503 + Retry-After: 0 and coalesced HTTP/2 421). Cancel
        // before close so HTTP/1 close-discard cannot drain an unbounded body.
        if (!response.isSuccessful) {
            val statusCode = response.code
            chain.call().cancel()
            response.close()
            throw fetchFailure(
                ModelsDevSnapshot.SnapshotFallbackReason.HTTP_STATUS,
                httpStatusCode = statusCode,
            )
        }
        val body = response.body
        val declaredLength = response.header("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxBytes) {
            chain.call().cancel()
            response.close()
            throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.WIRE_BODY_TOO_LARGE)
        }

        val countedSource = object : ForwardingSource(body.source()) {
            private var bytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0L
                val remainingIncludingDetectionByte = maxBytes - bytesRead + 1L
                val read = super.read(sink, minOf(byteCount, remainingIncludingDetectionByte))
                if (read > 0L) {
                    bytesRead += read
                    if (bytesRead > maxBytes) {
                        chain.call().cancel()
                        throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.WIRE_BODY_TOO_LARGE)
                    }
                }
                return read
            }
        }.buffer()

        response.newBuilder()
            .body(countedSource.asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    private fun readDecodedBody(body: ResponseBody, maxBytes: Long, call: Call): String {
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            call.cancel()
            throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.DECODED_BODY_TOO_LARGE)
        }

        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val remainingIncludingDetectionByte = maxBytes - buffer.size + 1L
            val read = source.read(buffer, minOf(8_192L, remainingIncludingDetectionByte))
            if (read == -1L) break
            if (buffer.size > maxBytes) {
                call.cancel()
                throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.DECODED_BODY_TOO_LARGE)
            }
        }
        if (buffer.size == 0L) {
            throw fetchFailure(ModelsDevSnapshot.SnapshotFallbackReason.EMPTY_BODY)
        }
        return buffer.readString(Charsets.UTF_8)
    }

    private fun fetchFailure(
        reason: ModelsDevSnapshot.SnapshotFallbackReason,
        httpStatusCode: Int? = null,
    ) = ModelsDevSnapshot.FetchException(reason, httpStatusCode)
}
