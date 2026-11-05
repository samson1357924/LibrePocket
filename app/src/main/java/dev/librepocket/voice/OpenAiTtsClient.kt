package dev.librepocket.voice

import dev.librepocket.redact.Redactor
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
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
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.utf8Size

/**
 * Client for the OpenAI Text-to-Speech (TTS) API (`POST {baseUrl}/v1/audio/speech`).
 *
 * 生命週期與過渡聲明（短期過渡 Legacy Bridge）：
 * - 標明 `POST /v1/audio/speech`（`tts-1` / `tts-1-hd`）為**短期過渡 Legacy Bridge**。
 * - OpenAI 官方已公告退場時間為 **2027-01-06**（[SUNSET_DATE]）。
 * - 未來遷移目標為 Realtime API (`gpt-realtime-2.1-mini`，見 [MIGRATION_TARGET]）。
 *
 * Security and privacy guarantees & 記憶體行為校準：
 * - 受限記憶體緩衝播放與記憶體行為校準（Bounded in-memory buffering）：
 *   音訊採受限記憶體緩衝播放，app 層不寫入檔案系統（零 File 引用）；框架層(MediaPlayer/OEM)是否落盤依實作而定。誠實校準記憶體行為描述：網路下載後全額緩衝於記憶體中（Bounded in-memory buffering），
 *   在峰值時存在多份 ByteArray 拷貝（OkHttp 內部 chunk 讀取緩衝、ByteArrayOutputStream / 累計 ByteArray、
 *   下游播放器的防禦性拷貝等）。25MB 上限（[DEFAULT_MAX_AUDIO_BYTES]）可降低但無法杜絕極限情況的 OOM 風險。
 * - Credentials & zeroing: [apiKey] supplier returns `CharArray?`. The array is strictly wiped with `fill('\u0000')`
 *   inside a `try/finally` block immediately after OkHttp [Request] construction, before any network dispatch or streaming.
 * - OkHttp Header Limitation: OkHttp's internal header model stores header values as immutable Java [String]s
 *   ("Bearer $keyString"). The immutable string remains in memory until collected by GC; we do not make false security
 *   claims of zero heap exposure for OkHttp request headers.
 * - Scheme & loopback restriction: URLs must be the official OpenAI endpoint (`https://api.openai.com`
 *   with exactly the canonical path `/v1/audio/speech`),
 *   unless connecting to local loopback (`localhost`, `127.0.0.0/8`, `::1`) with [allowTestLoopback] set to true.
 *   Third-party hosts and unisolated plaintext remote endpoints are strictly rejected with
 *   [OpenAiTtsErrorCode.INVALID_INPUT] before invoking the key supplier or dispatching network requests.
 *   Loopback endpoints are likewise restricted to `http`/`https` schemes (`gopher`, `ftp`, etc. refused).
 * - [allowTestLoopback] is TEST-ONLY (default `false`; production code must always leave it `false`).
 *   Plaintext `http` loopback traffic and MockWebServer-style test doubles must never carry real keys
 *   in production. This module has no `BuildConfig` gate, so the guard is documentation + default-false
 *   + unit-test pinning (see `constructor_defaultAllowTestLoopback_isFalse_productionSafe`): no production
 *   source passes `true`.
 * - Resource bounding: Responses are streamed in bounded chunks up to [maxAudioBytes] (default 25MB). If the response
 *   exceeds this limit or declares a Content-Length exceeding it, the call is cancelled immediately and fails closed with
 *   [OpenAiTtsErrorCode.RESPONSE_TOO_LARGE] to reduce Out-Of-Memory (OOM) risk (note: 25MB cap reduces but cannot eliminate OOM under extreme low-memory conditions).
 * - Overall timeout: OkHttp's `callTimeout` (default 60 seconds) governs the complete call lifetime.
 * - Conservative retryability: Only explicit transient conditions (HTTP 429, 503, 504, and confirmed connection/socket timeouts)
 *   are marked [retryable = true]. 500, 501, 502, client errors, and unknown TLS/transport failures are marked non-retryable.
 *   engine 永不隱性重試：[retryable] 僅為呼叫端分類標籤，client/engine 本身不做任何自動重試（含 429/503/504 亦只回標籤不重送）；
 *   未來 UI 若重試需指數退避＋尊重 Retry-After＋上限＋同 epoch/手勢（同一次使用者手勢／同一世代內才可重試，失敗不自動重試）。
 * - Comprehensive error redaction: Error messages and exception causes are sanitized via [Redactor.redactError] and
 *   the active API key string is explicitly replaced with `⟦REDACTED:API_KEY⟧` to prevent leaking non-standard keys.
 * - Exception cause encapsulation: Exceptions are never chained with raw, unredacted cause instances.
 * - Privacy (best-effort, not anonymization): Text sent to cloud passes [VoiceTools.redactForCloud]
 *   (best-effort 已知樣式遮罩，非匿名化) before network dispatch; 朗讀文本將送 OpenAI 依其條款處理。
 *   Redaction-anchor note: `redactForCloud` currently shares its redaction policy implementation with
 *   `redactForStore`, but the two anchors differ semantically (cloud-upload vs store-write). A policy
 *   change to either side must be reviewed against the other; the two must not silently diverge in
 *   coverage, nor be "unified" without auditing both call-site contracts.
 * - Sunset runtime notice: HTTP 404/410 responses append a migration hint
 *   (`sunset 2027-01-06, migrate to gpt-realtime-2.1-mini`, see [SUNSET_DATE]/[MIGRATION_TARGET]).
 * - Transport security: HTTP redirects are disabled to prevent credential leaking across origins.
 *   The execution client additionally strips injected authenticators/proxies
 *   ([Authenticator.NONE]/[Proxy.NO_PROXY]); never inject a client carrying logging interceptors —
 *   the `Authorization` header value would leak the key to logcat.
 * - Cancellation: Blocking network I/O is cancelled immediately when the parent coroutine is cancelled.
 *
 * @param baseUrl Endpoint base URL (default official `https://api.openai.com`).
 * @param client Base OkHttp client. WARNING: never pass a client carrying logging interceptors
 *   (e.g. `HttpLoggingInterceptor` at BODY/HEADERS level) — the `Authorization` header value would
 *   leak the live API key to logcat. Injected authenticators/proxies are stripped defensively
 *   ([Authenticator.NONE]/[Proxy.NO_PROXY]); interceptors are intentionally preserved so tests can
 *   observe dispatch, hence the logging-interceptor prohibition must be honored by callers.
 * @param apiKey Key supplier returning `CharArray?`, strictly zeroed after Request construction.
 * @param maxAudioBytes Per-response audio cap (default 25MB, absolute max 64MB — 25MB is the default,
 *   not the absolute maximum).
 * @param allowTestLoopback TEST-ONLY escape hatch (default `false`). Admits `http`/`https` loopback
 *   endpoints (`localhost`, `127.0.0.0/8`, `::1`) for MockWebServer-style tests. Plaintext loopback
 *   traffic can expose keys to local observers — production code must always leave this `false`
 *   (pinned by unit test; no production source passes `true`).
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

    /**
     * Hardened execution client derived from the injected base client.
     *
     * Defensive strips: redirects off (no cross-origin credential leak), connection-failure retry
     * off (no duplicate keyed dispatches), [Authenticator.NONE] (an injected authenticator could
     * otherwise attach the key to unexpected challenges/hosts), [Proxy.NO_PROXY] (an injected
     * proxy could otherwise observe plaintext loopback traffic or exfiltrate the key).
     * Interceptors from the injected client are intentionally preserved so tests can observe
     * dispatch timing — therefore callers must NEVER inject logging interceptors.
     */
    val httpClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .apply {
            if (client.callTimeoutMillis == 0) {
                callTimeout(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        .build()

    companion object {
        /**
         * 官方公告之端點退場時間 (Sunset date: 2027-01-06)。
         * POST /v1/audio/speech (tts-1 / tts-1-hd) 為短期過渡 Legacy Bridge，未來遷移目標為 Realtime API (gpt-realtime-2.1-mini)。
         */
        const val SUNSET_DATE = OpenAiTtsModels.SUNSET_DATE
        const val MIGRATION_TARGET = OpenAiTtsModels.MIGRATION_TARGET
        const val IS_LEGACY_BRIDGE = OpenAiTtsModels.IS_LEGACY_BRIDGE

        const val DEFAULT_BASE_URL = OpenAiTtsModels.DEFAULT_BASE_URL
        /**
         * Canonical speech endpoint path — single-sourced from [OpenAiTtsModels.SPEECH_ENDPOINT_PATH].
         * [resolveEndpoint] builds URLs from this constant; [validateEndpointUrl] admits only this
         * exact resolved path.
         *
         * Realtime 遷移入口註記：遷移至 Realtime API（見 [MIGRATION_TARGET]）時，此 path allowlist
         * 必須重構（新路徑/傳輸不同，不可僅改字串；須同步重構 [resolveEndpoint] 與 [validateEndpointUrl]
         * 的精確路徑比對）。
         */
        const val SPEECH_ENDPOINT_PATH = OpenAiTtsModels.SPEECH_ENDPOINT_PATH
        /**
         * 預設最大音訊位元組限制（25MB）。
         * 音檔採受限記憶體緩衝播放，app 層不寫入檔案系統（零 File 引用）；框架層(MediaPlayer/OEM)是否落盤依實作而定。25MB 是預設值而非絕對上限：
         * 可經 [OpenAiTtsClient.maxAudioBytes] 上調，但不得超過 [MAX_SUPPORTED_AUDIO_BYTES]。
         * 誠實說明：網路下載後全額緩衝於記憶體中（Bounded in-memory buffering），
         * 在峰值時存在多份 ByteArray 拷貝，25MB 上限可降低但無法杜絕極限情況的 OOM 風險。
         */
        const val DEFAULT_MAX_AUDIO_BYTES = OpenAiTtsModels.MAX_AUDIO_BYTES.toLong()
        /**
         * 絕對硬上限（64MB）——單一來源 [OpenAiTtsModels.MAX_SUPPORTED_AUDIO_BYTES]。
         * 構造器維持 `maxAudioBytes <= MAX_SUPPORTED_AUDIO_BYTES` 檢查；此前 `Long.MAX_VALUE`
         * 的無意義上界（含錯誤訊息印出該值的空轉）已移除。
         */
        const val MAX_SUPPORTED_AUDIO_BYTES = OpenAiTtsModels.MAX_SUPPORTED_AUDIO_BYTES
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
         * Resolves the target speech endpoint URL from a base URL, built from [SPEECH_ENDPOINT_PATH].
         * If [baseUrl] already ends with the audio/speech suffix, it is preserved to avoid duplication.
         * If [baseUrl] ends with `/v1`, the audio/speech suffix is appended.
         * Otherwise the full [SPEECH_ENDPOINT_PATH] is appended.
         *
         * NOTE: path allow-listing is enforced by [validateEndpointUrl], which only admits the exact
         * canonical path — preserved non-standard prefixes never reach the network.
         */
        internal fun resolveEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val audioSpeechSuffix = SPEECH_ENDPOINT_PATH.removePrefix("/v1")
            return when {
                trimmed.endsWith(SPEECH_ENDPOINT_PATH) -> trimmed
                trimmed.endsWith(audioSpeechSuffix) -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed$audioSpeechSuffix"
                else -> "$trimmed$SPEECH_ENDPOINT_PATH"
            }
        }

        private val IPV4_LOOPBACK_REGEX = Regex(
            """^127(?:\.(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}$"""
        )

        /**
         * Tests whether [host] is a loopback host admissible in test mode.
         *
         * Strictly admits ONLY: the `localhost` name itself, the full `127.0.0.0/8` range, and `::1`.
         * `*.localhost` subdomains are intentionally REJECTED: wildcard DNS can resolve
         * attacker-controlled names such as `sub.localhost` to non-loopback addresses outside the
         * test sandbox, so test mode must not treat the suffix as proof of loopback.
         */
        internal fun isLoopbackHost(host: String): Boolean {
            val h = host.trim().trim('[', ']').removeSuffix(".").lowercase(Locale.ROOT)
            if (h == "localhost") {
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
                    httpUrl.password.isEmpty() &&
                    httpUrl.query == null &&
                    httpUrl.fragment == null
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 嚴格驗證目標語音端點 URL（精確路徑 allowlist + 主機 allowlist）。
         *
         * Realtime 遷移入口註記：遷移至 Realtime API（見 [MIGRATION_TARGET]）時，此 path allowlist
         * 必須重構（新路徑/傳輸不同；須與 [SPEECH_ENDPOINT_PATH]/[resolveEndpoint] 同步修改，
         * 不可僅放寬此處比對）。
         */
        internal fun validateEndpointUrl(endpointUrl: String, allowTestLoopback: Boolean = false) {
            val parsed = endpointUrl.toHttpUrlOrNull()
                ?: throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Invalid endpoint URL: ${Redactor.redactError(endpointUrl)}",
                )

            if (parsed.query != null || parsed.fragment != null) {
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Endpoint URL must not contain query parameters or fragments: ${Redactor.redactError(endpointUrl)}",
                )
            }

            // Scheme whitelist applies to BOTH branches (official and loopback): only http/https.
            // (`toHttpUrlOrNull` already rejects most exotic schemes, but enforce explicitly to
            // refuse `gopher`, `ftp`, etc. even when pointed at a loopback host.)
            val scheme = parsed.scheme.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Endpoint URL scheme must be http or https: ${Redactor.redactError(endpointUrl)}",
                )
            }

            // Resolved-path allow-list (official branch included): the final path must be exactly
            // the canonical speech path. Official base URLs carrying non-standard paths
            // (e.g. `https://api.openai.com/evil`) resolve to a non-canonical path and are rejected
            // here as INVALID_INPUT before the key supplier is invoked.
            if (parsed.encodedPath != SPEECH_ENDPOINT_PATH) {
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Endpoint path must be exactly $SPEECH_ENDPOINT_PATH: ${Redactor.redactError(endpointUrl)}",
                )
            }

            val host = parsed.host

            if (isOpenAiOfficialEndpoint(endpointUrl)) {
                return
            }

            // TEST-ONLY: plaintext http loopback is admitted solely under the explicit flag.
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
            // Force redaction for cloud upload. Anchor note: redactForCloud currently shares its
            // policy implementation with redactForStore, but anchors different semantics
            // (cloud-upload vs store-write) — a policy change on either side must be reviewed
            // against the other.
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
            // Single-copy trim: trim the CharArray bounds first so only ONE immutable String
            // is ever materialized (vs `String(keyChars).trim()`, which leaves both the
            // untrimmed and trimmed copies on the heap until GC).
            var keyStart = 0
            while (keyStart < keyChars.size && keyChars[keyStart].isWhitespace()) keyStart++
            var keyEnd = keyChars.size
            while (keyEnd > keyStart && keyChars[keyEnd - 1].isWhitespace()) keyEnd--
            val keyString = String(keyChars, keyStart, keyEnd - keyStart)

            // Security Note:
            // [keyChars] is strictly zeroed with fill('\u0000') immediately in this finally block,
            // before any network I/O or streaming occurs.
            // However, OkHttp's Request.Builder header internals store header values as immutable Java Strings
            // ("Bearer $keyString"). That immutable String reference remains in memory until garbage collected.
            // We do not make false security claims of zero heap exposure for OkHttp header strings.
            val req = try {
                Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $keyString")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()
            } catch (e: IllegalArgumentException) {
                throw OpenAiTtsException(
                    statusCode = null,
                    errorCode = OpenAiTtsErrorCode.INVALID_INPUT,
                    retryable = false,
                    safeMessage = "Failed to construct Authorization header: invalid characters in API key",
                    cause = e,
                    activeKey = keyString,
                )
            }

            req to keyString
        } finally {
            keyChars.fill('\u0000')
        }

        return executeRequest(httpRequest, activeKey)
    }

    /**
     * Synthesizes audio with explicit arguments.
     *
     * NOTE: parameters carry NO defaults — callers must pass explicit values (sourced from
     * [OpenAiTtsModels], the single source of truth). Default pass-through via companion
     * constants is intentionally absent so default triples cannot silently diverge.
     */
    suspend fun synthesize(
        text: String,
        model: String,
        voice: String,
        responseFormat: String,
        speed: Float,
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

    /**
     * 版本依賴註記：`invokeOnCompletion(onCancelling = true)` 需 `InternalCoroutinesApi`；
     * kotlinx.coroutines 升級時需複查是否有穩定替代（stable API）可取代，避免長期依賴 internal。
     */
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
                    val safeCause = OpenAiTtsException.sanitizeCause(e, activeKey)
                    throw CancellationException("TTS request coroutine was cancelled", safeCause).apply {
                        stackTrace = emptyArray()
                    }
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
                    val safeCause = OpenAiTtsException.sanitizeCause(e, activeKey)
                    throw CancellationException("TTS request coroutine was cancelled", safeCause).apply {
                        stackTrace = emptyArray()
                    }
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

            // Fail closed on empty success bodies: HTTP 200 with 0 bytes is a contract
            // violation, never valid audio. Never return "success" with empty audio.
            if (totalBytesRead == 0L) {
                throw OpenAiTtsException(
                    statusCode = code,
                    errorCode = OpenAiTtsErrorCode.UNEXPECTED,
                    retryable = false,
                    safeMessage = "Response audio body was empty (HTTP $code with 0 bytes)",
                )
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
        // Runtime sunset notice: 404/410 on the legacy speech endpoint likely signal the
        // announced retirement — append the migration hint (see SUNSET_DATE/MIGRATION_TARGET).
        val safeMessage = if (code == 404 || code == 410) {
            "$rawMessage (note: POST /v1/audio/speech sunset $SUNSET_DATE, migrate to $MIGRATION_TARGET)"
        } else {
            rawMessage
        }
        throw OpenAiTtsException(
            statusCode = code,
            errorCode = errorCode,
            retryable = retryable,
            safeMessage = safeMessage,
            activeKey = activeKey,
        )
    }

    private fun readBoundedErrorBody(response: Response): String? {
        return try {
            val body = response.body
            val source = body.source()
            val buffer = Buffer()
            while (buffer.size < MAX_ERROR_BODY_BYTES) {
                val toRead = MAX_ERROR_BODY_BYTES - buffer.size
                val read = source.read(buffer, toRead)
                if (read == -1L) break
            }
            buffer.readUtf8()
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

        // Log-forging guard: BOTH branches (parsed JSON message and raw body) are flattened to
        // a single line and bounded, so embedded newlines/control sequences in server error text
        // cannot forge log lines or bloat messages.
        val detail = when {
            !parsedMsg.isNullOrBlank() -> parsedMsg.trim().replace(Regex("\\s+"), " ").take(200)
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
        // Uses okio.utf8Size (okio.Utf8.size) to avoid allocating a new ByteArray per invocation
        override fun contentLength(): Long = json.utf8Size()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8(json)
        }
    }
}
