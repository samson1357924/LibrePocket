package dev.librepocket.web

import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.HostResolver
import dev.librepocket.policy.NetworkDenyCode
import dev.librepocket.policy.NetworkEvaluation
import dev.librepocket.policy.NetworkPolicy
import dev.librepocket.policy.PinnedNetworkDns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * D3 接線前置的 **internal transport seam**（#138；尚未接線、沒有 production caller）。
 *
 * 設計意圖：未來的 production caller **只能**透過 [forExtract]／[forSearch] 建出的
 * transport 送出 requests；兩者皆為：
 * - 每次請求先經 [NetworkPolicy.evaluateUrl]（per-hop 政策單一真相，不重寫政策邏輯），
 *   拒絕即 fail-closed，**零連線、零 credential/body**；
 * - 建連釘住該跳已核准的位址（[PinnedNetworkDns]，`strict=true`）——evaluate 與 connect
 *   之間無二次解析（TOCTOU rebind）；`pinnedAddresses`（由 service 的 policy gate 提供）
 *   與 adapter 自己重驗的結果必須集合相等，否則 `PIN_BINDING_MISMATCH` 拒絕，
 *   不接受呼叫方任意替換 pin；
 * - `followRedirects(false)`／`followSslRedirects(false)`／無重試／無 cookie／無 cache／
 *   直連（`Proxy.NO_PROXY`），每請求獨立連線池，避免舊 keep-alive 繞過 pin；
 * - 重複語意標頭（`content-encoding`／`content-type`／`location`／`transfer-encoding`）
 *   以 `Headers.values()`（getAll 語意）觀察，>1 值即拒——service 的 `headerLookup`
 *   只取首值，adapter 是唯一 backstop（D3 gate #10）；
 * - raw read 一律截斷於 `maxBytes + 1`，讓 service 以 `TOO_LARGE` fail-closed；
 *   `Accept-Encoding: identity` 阻止 OkHttp 隱性透明解壓，使 raw cap 與 service 的
 *   gunzip cap 成為兩道獨立上限（gzip bomb 雙 cap）；此標頭由 transport 自身設定，
 *   不接受 caller 覆寫，亦非 credential／自訂標頭；
 * - `206` fail-closed（不交付部分內容、不寫快取）；
 * - 請求標頭走 **allowlist**：只接受 `Accept`（service 契約唯一送出之標頭），其餘一律
 *   `HEADER_NOT_ALLOWED` 拒——`Cookie`／`Authorization`／`Proxy-Authorization`／
 *   `X-Api-Key`／`X-Subscription-Token` 等憑證類標頭即使由呼叫手動放入也永不 egress
 *   （`CookieJar.NO_COOKIES` 只擋 OkHttp 自動附加，擋不住 caller 顯式 header）；
 * - **零 logging**：不記錄 URL／body／headers（D3 gate (d)）；拒絕只回 typed detail，
 *   不含敏感資料。
 *
 * Consent 沒有隱性預設：[forExtract]／[forSearch] 的 `consentGranted` 預設為 `{ false }`，
 * 呼叫方必須顯式傳入由 consent／opt-in store 派生的閘；未授予時連 DNS 都不查詢。
 * 這只是 pre-wire seam：**不代表已接線、已翻 `executionReady` 或已裝置驗證**。
 */
internal object WebPinnedHttpAdapter {

    const val DENIAL_CONSENT = "CONSENT_REQUIRED"
    const val DENIAL_POLICY = "NETWORK_POLICY_BLOCKED"
    const val DENIAL_PIN_MISMATCH = "PIN_BINDING_MISMATCH"
    const val DENIAL_DUPLICATE_HEADER = "DUPLICATE_HEADER"
    const val DENIAL_HTTP_206 = "HTTP_206"
    const val DENIAL_HEADER_NOT_ALLOWED = "HEADER_NOT_ALLOWED"

    /** 具語意且不容許重複的 response headers（小寫；`Headers.values()` 不分大小寫）。 */
    private val SINGLE_VALUE_HEADERS = listOf(
        "content-encoding",
        "content-type",
        "location",
        "transfer-encoding",
    )

    /** 失敗只帶 typed detail，不帶 URL／body／headers，且屬 [IOException] 由既有 fetcher 映射。 */
    internal class Denied(detail: String) : IOException("WEB_PINNED_TRANSPORT_DENIED:$detail")

    /** 共用 client 骨架；DNS／direct／redirect／retry／pool 由 [pinnedClient] 逐請求覆寫。 */
    fun defaultBaseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(WebExtract.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(WebExtract.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WebExtract.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(WebExtract.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .build()

    /**
     * D3 `web.extract` transport seam。production 呼叫方未來必須把 service policy gate
     * 使用的同一個 [optIn]／[resolver] 傳入，並由 consent store 提供 [consentGranted]。
     */
    fun forExtract(
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        consentGranted: () -> Boolean = { false },
        baseClient: OkHttpClient = defaultBaseClient(),
    ): WebExtractHttpGet = ExtractPinnedGet(optIn, resolver, consentGranted, baseClient)

    /** D3 `web.search` transport seam（與 extract 同一政策／pin／consent 紀律）。 */
    fun forSearch(
        optIn: EndpointNetworkOptIn,
        resolver: HostResolver,
        consentGranted: () -> Boolean = { false },
        baseClient: OkHttpClient = defaultBaseClient(),
    ): WebHttpGet = SearchPinnedGet(optIn, resolver, consentGranted, baseClient)

    private fun requireConsent(consentGranted: () -> Boolean) {
        if (!consentGranted()) throw Denied(DENIAL_CONSENT)
    }

    private fun evaluate(url: String, optIn: EndpointNetworkOptIn, resolver: HostResolver): NetworkEvaluation {
        // URL 硬上限先於任何政策／DNS／連線（與 service 入口同一常數）。
        if (url.length > WebExtract.MAX_URL_CHARS) throw Denied(WebExtract.DETAIL_URL_TOO_LONG)
        val eval = NetworkPolicy.evaluateUrl(url, optIn, resolver)
        if (!eval.allowed || eval.resolvedAddresses.isEmpty()) {
            throw Denied("$DENIAL_POLICY:${eval.denyCode?.name ?: NetworkDenyCode.INVALID_HOST.name}")
        }
        return eval
    }

    /**
     * 逐請求 pinning client：永遠以 [PinnedNetworkDns] 覆寫 caller 傳入 client 的 DNS，
     * 因此 injected baseClient（測試用 TLS trust seam）不能變成 non-pinning adapter。
     * [pinnedAddresses] 非空時必須與本次政策重驗的已核准集合相等——不接受跨 binding 沿用。
     */
    private fun pinnedClient(
        url: String,
        eval: NetworkEvaluation,
        pinnedAddresses: List<InetAddress>,
        resolver: HostResolver,
        baseClient: OkHttpClient,
    ): OkHttpClient {
        val host = url.toHttpUrlOrNull()?.host ?: throw Denied(NetworkDenyCode.INVALID_HOST.name)
        val approved = eval.resolvedAddresses
        if (pinnedAddresses.isNotEmpty() &&
            sortedDistinctAddressTokens(pinnedAddresses).toSet() != sortedDistinctAddressTokens(approved).toSet()
        ) {
            throw Denied(DENIAL_PIN_MISMATCH)
        }
        val addresses = if (pinnedAddresses.isEmpty()) approved else pinnedAddresses
        val pinned = PinnedNetworkDns.forHost(host, addresses, resolver)
        return baseClient.newBuilder()
            .dns(pinned)
            .proxy(Proxy.NO_PROXY)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()
    }

    /** getAll 語意：任一具語意標頭重複即拒，不取首值放行（D3 gate #10）。 */
    private fun rejectDuplicateHeaders(response: Response) {
        for (name in SINGLE_VALUE_HEADERS) {
            if (response.headers.values(name).size > 1) throw Denied("$DENIAL_DUPLICATE_HEADER:$name")
        }
    }

    /** 攤平成 service 可消費的 Map；重複已在上游拒絕，此處只保留每個名字的第一值。 */
    private fun flattenHeaders(response: Response): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (i in 0 until response.headers.size) {
            val name = response.headers.name(i)
            if (!out.containsKey(name)) out[name] = response.headers.value(i)
        }
        return out
    }

    /**
     * raw read 上限 `cap + 1`（位元組）。超過即回傳長度 `cap + 1` 的 body，
     * 由 service 既有檢查映射為 `TOO_LARGE`；**不**在此靜默截斷成「合法內容」。
     */
    private fun readCapped(response: Response, cap: Int): ByteArray {
        val limit = cap.coerceAtLeast(1)
        val out = ByteArrayOutputStream(minOf(limit + 1, 8192))
        val buf = ByteArray(8192)
        val stream = response.body?.byteStream() ?: return ByteArray(0)
        stream.use { input ->
            while (out.size() <= limit) {
                val n = input.read(buf)
                if (n == -1) break
                val room = limit + 1 - out.size()
                if (n >= room) {
                    out.write(buf, 0, room)
                    break
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /**
     * 請求標頭 allowlist：只接受 `Accept`（大小寫不敏感），其餘（含憑證類）在送出前
     * fail-closed。transport 自身補上的 `Accept-Encoding: identity` 不經 caller map，
     * 不被 allowlist 過濾（亦不接受 caller 覆寫）。
     */
    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        for ((name, value) in headers) {
            if (!name.equals("Accept", ignoreCase = true)) {
                throw Denied("$DENIAL_HEADER_NOT_ALLOWED:${name.lowercase()}")
            }
            builder.header(name, value)
        }
        // 阻止 OkHttp 透明 gunzip；raw cap 與 service 的 gunzip cap 因此各自生效。
        builder.header("Accept-Encoding", "identity")
        return builder.build()
    }

    private class ExtractPinnedGet(
        private val optIn: EndpointNetworkOptIn,
        private val resolver: HostResolver,
        private val consentGranted: () -> Boolean,
        private val baseClient: OkHttpClient,
    ) : WebExtractHttpGet {
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            pinnedAddresses: List<InetAddress>,
            maxBytes: Int,
        ): WebExtractRawResponse = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            requireConsent(consentGranted)
            val eval = evaluate(url, optIn, resolver)
            val client = pinnedClient(url, eval, pinnedAddresses, resolver, baseClient)
            val call = client.newCall(buildRequest(url, headers))
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause != null) runCatching { call.cancel() }
            }
            try {
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    rejectDuplicateHeaders(response)
                    if (response.code == 206) {
                        // RFC 9110 §15.3.7：本層不發 Range、不組裝分段、不交付 body。
                        // 以空 body 的 206 原樣交給 service 既有非 2xx 終態分支映射
                        // Failed(HTTP_206)，不寫快取（throw IOException 會被映射成 TRANSPORT，
                        // 反而抹掉 206 語意）。
                        runCatching { call.cancel() }
                        WebExtractRawResponse(206, ByteArray(0), flattenHeaders(response))
                    } else {
                        WebExtractRawResponse(
                            code = response.code,
                            body = readCapped(response, maxBytes),
                            headers = flattenHeaders(response),
                        )
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                cancellation?.dispose()
            }
        }
    }

    private class SearchPinnedGet(
        private val optIn: EndpointNetworkOptIn,
        private val resolver: HostResolver,
        private val consentGranted: () -> Boolean,
        private val baseClient: OkHttpClient,
    ) : WebHttpGet {
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun get(url: String, headers: Map<String, String>): WebHttpResponse =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                requireConsent(consentGranted)
                val eval = evaluate(url, optIn, resolver)
                val client = pinnedClient(url, eval, emptyList(), resolver, baseClient)
                val call = client.newCall(buildRequest(url, headers))
                val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                    onCancelling = true,
                    invokeImmediately = true,
                ) { cause ->
                    if (cause != null) runCatching { call.cancel() }
                }
                try {
                    currentCoroutineContext().ensureActive()
                    call.execute().use { response ->
                        rejectDuplicateHeaders(response)
                        if (response.code == 206) {
                            runCatching { call.cancel() }
                            throw Denied(DENIAL_HTTP_206)
                        }
                        val bytes = readCapped(response, WebHttpTimeouts.MAX_BODY_CHARS)
                        WebHttpResponse(
                            code = response.code,
                            body = String(bytes, Charsets.UTF_8),
                            headers = flattenHeaders(response),
                        )
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } finally {
                    cancellation?.dispose()
                }
            }
    }
}
