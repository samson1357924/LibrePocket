package dev.librepocket.mcp

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink

/**
 * D03 Streamable HTTP 傳輸：POST `endpoint(baseUrl)`（使用者自訂完整 URL；bare-origin 自動補 `/mcp`，有 path 原樣保留）+ Bearer（舊 SSE 不做）。
 *
 * 最小子集警示（#11 Stage2 本 PR 選型，非 Accepted ADR）：本層只發 `tools/list`／`tools/call` 單次 POST；
 * 不送 `initialize` 交握、不接 SSE event-stream、不跟隨 `nextCursor` 分頁、
 * 不協商 `protocolVersion`（`2025-11-25`／`2026-07-28` 為 #11 提及的候選修訂號，
 * repo 內無文本，皆為候選未採納）。
 * SSE 文本餵給 [McpWire] 一律 `mcp: bad envelope`（見 `McpProtocolContractsTest`）。
 *
 * 安全約定：
 * - [bearer] 只放 `Authorization` 標頭，永不拼進 body，不寫 log；
 * - 呼叫方（[McpServer]）負責從 KeyVault 取 key、用後即擦，本層不留存；
 * - 僅放行 `https` 與回環 `http`（見 [McpWire.requireSecureEndpoint]，
 *   直調亦擋；其餘明文拋 `McpProtocol`）；
 * - 不自動跟隨 redirect：3xx 為終端回應（[McpServer] 映射 PROTOCOL + 計熔斷），
 *   `tools/call` body／Bearer 只會送到預驗過的初始端點；
 *   注入的 client 經 [transportClient] 強制套用 redirect／連線失敗重試／503 重送／
 *   authenticator／cookie／cache 政策，且 POST body 為 one-shot（OkHttp 層的
 *   replay 分支一律拒送）；注入 client 自帶的 application interceptor 屬受信任的
 *   呼叫方元件，不在本層防禦邊界內（對齊 provider 同款界線）；
 * - 2xx 配非 JSON Content-Type（如 `text/event-stream`）早擋為 `McpProtocol`
 *   （不讀滿 body；缺席則交 parser 判定）；
 * - 回傳 null = 本步超時（呼叫方映射為 [McpStatus.TIMEOUT]，不重試）。
 */
class McpTransport(
    client: OkHttpClient = defaultHttp(),
) {
    /** 注入 client 一律正規化（見 [transportClient]）：不重定向、不預算外重送。 */
    private val http: OkHttpClient = transportClient(client)

    companion object {
        /** 連接 5s / 寫 10s / 讀 30s；單步總超時另由 [McpTimeouts] 鉗制 15–30s。 */
        fun defaultHttp(): OkHttpClient = transportClient(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
        )

        /**
         * MCP 單次預算：不自動跟隨 redirect（含 SSL 降級）、不重試連線失敗，
         * 剝除 OkHttp 5.5.0 在 `RetryAndFollowUpInterceptor` 內部、**不看**
         * `retryOnConnectionFailure` 的 `503 + Retry-After: 0` 預算外重送，
         * 並清空 `authenticator`／`proxyAuthenticator`／`cookieJar`／`cache`
         * （這四者會各自觸發 OkHttp 重送或把舊回應送回來；
         * `421` 同源連線層重送由 one-shot body 擋下，cached-503 由 `cache(null)` 消除，
         * `503 + Retry-After: 0` 由 [RetryAfterStrip] 擋下，`authenticator` 分支由 NONE 擋下）。
         *
         * 理由：[McpWire.requireSecureEndpoint] 只驗初始 URL。一旦跟隨 307/308，
         * `tools/call` body（含 arguments）會被原樣重放到未驗證 origin；301/302/303
         * 則改寫為 GET 內網 SSRF，且回程 200 會被當成正常結果（熔斷還會 onSuccess）。
         * 本層政策對**注入的 client 同樣強制**（對齊 `providerTransportClient`）；
         * 注入 client 的 application interceptor／eventListener／dispatcher／pool
         * 保留（受信任的呼叫方元件，用於觀測與測試）。
         *
         * @return 套用本層政策後的 client（idempotent：已有本攔截器則不重複掛）。
         */
        internal fun transportClient(client: OkHttpClient): OkHttpClient {
            val builder = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
            if (client.networkInterceptors.none { it is RetryAfterStrip }) {
                builder.addNetworkInterceptor(RetryAfterStrip())
            }
            return builder.build()
        }

        /**
         * 成功 body 位元組安全上限：覆蓋既有 `take(64_000)` 字元語義的所有語言
         * （64k 字元 × UTF-8 最大 4 bytes = 256KiB），讀塊 8KiB 同級 provider。
         * 超量一律拋 [McpResponseTooLarge]（fail-closed），不回截斷 JSON。
         */
        internal const val RESPONSE_MAX_BYTES = 256L * 1024L

        /**
         * 成功 body 字元上限：相容既有 `.string()?.take(64_000)`（Kotlin UTF-16 單位）。
         * 解碼後超量同樣拋 [McpResponseTooLarge]，避免半個 JSON 誤報 OK。
         */
        internal const val RESPONSE_MAX_CHARS = 64_000
    }

    /**
     * 取消語義（對齊 provider `executeProviderRequest`）：
     * - handler 自 `execute()` 前覆蓋到 body 消費完，`onCancelling=true` 使外部取消
     *   或總 deadline 到期立即 `call.cancel()` 中斷 blocking header/body IO，而非等其自然返回；
     * - 取消與否只以 coroutine Job（[ensureActive]）判斷，不以 `Call.isCanceled()` 判斷；
     * - 總 deadline = `withTimeoutOrNull(McpTimeouts.clamp)`（沿用既有常數），僅本層到期回 null（TIMEOUT）；
     *   父層 `withTimeout`/外部取消原樣透傳 `CancellationException`（不斷熔斷、不映射 TRANSPORT，見 [McpServer]）；
     * - 成功 body 雙上限：[RESPONSE_MAX_BYTES] 位元組與 [RESPONSE_MAX_CHARS] 字元
     *   （相容舊 `take(64_000)`）；超量拋 [McpResponseTooLarge]，
     *   呼叫方映射 PROTOCOL + 計熔斷，絕不回截斷半 JSON 充 OK；
     *   非 2xx 不讀 body（呼叫方只看 code）：先 cancel 再回 `code to null`，
     *   保留 401/403→UNAUTHORIZED、3xx→PROTOCOL、其餘→TRANSPORT 語義，
     *   且 close() 不排空大 body；
     * - 其餘 `IOException` 透傳（呼叫方映射 TRANSPORT）。
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun postJson(
        baseUrl: String,
        bearer: String,
        bodyJson: String,
        timeoutMs: Long,
    ): Pair<Int, String?>? {
        // 縱深：直調 transport 亦 fail-closed（McpServer 已預驗，此處僅防直調）。
        try {
            McpWire.requireSecureEndpoint(baseUrl)
        } catch (e: IllegalArgumentException) {
            throw McpWire.McpProtocol(e.message ?: "mcp: bad endpoint")
        }
        // bare-origin 相容：無 path 時 endpoint() 自動補 /mcp（舊配置相容，建議記 warning 提醒手動補完整 path）。
        val url = McpWire.endpoint(baseUrl)
        // 可觀測性：自動補 /mcp 時記 warning（runCatching 使 JVM 單測無 mocked Log 仍綠）。
        if (McpWire.isBareOrigin(baseUrl)) {
            runCatching {
                android.util.Log.w(
                    "McpTransport",
                    "bare-origin baseUrl auto-appended /mcp; prefer full path",
                )
            }
        }
        val request = Request.Builder()
            .url(url)
            .post(OneShotJsonBody(bodyJson))
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        val deadlineMs = McpTimeouts.clamp(timeoutMs)
        // withTimeoutOrNull 僅將本層 deadline 到期轉為 null；父層 withTimeout/外部取消
        // 以 TimeoutCancellationException/CancellationException 原樣傳播，不吞為 null。
        return withTimeoutOrNull(deadlineMs) {
            withContext(Dispatchers.IO) {
                val call = http.newCall(request)
                val job = currentCoroutineContext()[Job]
                val cancellation =
                    job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                        if (cause != null) {
                            try {
                                call.cancel()
                            } catch (_: Exception) {
                            }
                        }
                    }
                try {
                    currentCoroutineContext().ensureActive()
                    call.execute().use { resp ->
                        val code = resp.code
                        if (code !in 200..299) {
                            // 非 2xx：McpServer 只看 code
                            // （401/403→UNAUTHORIZED、3xx→PROTOCOL、其餘→TRANSPORT），
                            // 不消費 body 文本；跳過成功-body 有界讀，否則超大 error body 會拋
                            // McpResponseTooLarge 而抹掉 HTTP 狀態語義（誤判 PROTOCOL）。
                            // 先 cancel 再回（use{} 離開即 close）：close() 不得排空未讀位元組，
                            // 否則大 error body / 慢滴流會卡住 blocking IO 直到 deadline。
                            try {
                                call.cancel()
                            } catch (_: Exception) {
                            }
                            code to null
                        } else {
                            // 子集聲明僅收 JSON：2xx 配非 JSON Content-Type（如 event-stream）
                            // 即早擋（cancel + 拋 McpProtocol），不讀滿 256KiB 才判；
                            // Content-Type 缺席則放行交 parser 判定（相容 MockWebServer 預設）。
                            val ct = resp.body.contentType()
                            if (ct != null && !ct.toString().contains("json", ignoreCase = true)) {
                                try {
                                    call.cancel()
                                } catch (_: Exception) {
                                }
                                throw McpWire.McpProtocol("mcp: bad content-type")
                            }
                            val text = readBoundedBody(resp.body) {
                                try {
                                    call.cancel()
                                } catch (_: Exception) {
                                }
                            }
                            code to text
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
    }
}

/**
 * 本層唯一的 JSON media type（top-level：[OneShotJsonBody] 共用）。
 */
private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

/**
 * one-shot POST body：OkHttp 5.5.0 的 `408`、`421` 與 `recover()` 分支檢查到
 * `isOneShot` 即原樣回傳該回應、不再重送（同 provider `OneShotJsonBody`）。
 * `503 + Retry-After: 0` 與 `authenticator` 兩個分支**不看** `isOneShot`，
 * 分別由 [RetryAfterStrip] 與 `Authenticator.NONE` 擋下（三道閘各守一路）。
 * `tools/call` 非 idempotent（MCP 工具預設 WRITE），這是把「非 idempotent 只送一次」
 * 釘在 OkHttp 層的最後一道閘。每次 `postJson` 皆新建 body，單次消費不影響正常單發路徑。
 */
internal class OneShotJsonBody(private val json: String) : RequestBody() {
    override fun contentType(): MediaType = JSON_MEDIA_TYPE
    override fun contentLength(): Long = json.toByteArray(Charsets.UTF_8).size.toLong()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(json)
    }
}

/**
 * 剝除 `503 + Retry-After: 0` 觸發：OkHttp 5.5.0 會在攔截器內部直接重送
 * 原本的 method＋body，不檢查 `retryOnConnectionFailure(false)`。
 * `tools/call` 非 idempotent（MCP 工具預設 WRITE），重送即重複副作用，
 * 故一律移除該 header 讓 503 原樣回到呼叫方（同 provider 層做法）。
 */
internal class RetryAfterStrip : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        return if (response.code == 503) {
            response.newBuilder().removeHeader("Retry-After").build()
        } else {
            response
        }
    }
}

/**
 * 成功 body 超量信號（fail-closed）：位元組超 [McpTransport.RESPONSE_MAX_BYTES] 或
 * 解碼字元超 [McpTransport.RESPONSE_MAX_CHARS] 時拋出。
 *
 * 訊息刻意不含 body 內容（不洩漏傳輸資料，稽核只記 id/status/latency）；
 * [McpServer] 將其映射為 PROTOCOL + 計熔斷，不當 OK，不當 TRANSPORT。
 */
internal class McpResponseTooLarge(message: String) : IOException(message)

/**
 * 有界讀成功 body（雙上限，超量即拋 [McpResponseTooLarge]）。
 *
 * - 位元組上限 [McpTransport.RESPONSE_MAX_BYTES]：`Content-Length` 預檢 +
 *   `remaining > 0` 迴圈控制，達 cap 立即 cancel + 拋，不為區別「恰好等於 cap」
 *   額外阻塞等第 257KiB 個位元組（同級 provider `readProviderErrorPrefix` 做法；
 *   舊 `+1` 探針在 chunked 恰滿 cap 卻不送 EOF/終止塊時會卡到總 deadline）。
 *   註：位元組達 cap 必同時超字元上限（256KiB 最少 65536 字元 > 64k，
 *   全 4-byte emoji 情形），故達 cap 直接判超量無誤判；
 * - 字元上限 [McpTransport.RESPONSE_MAX_CHARS]：完整解碼後檢查，相容舊
 *   `.string()?.take(64_000)`；超量直接拋（此時 body 已讀到 EOF、連線可重用，無需 cancel）。
 *
 * 未截斷才解碼回傳：絕不在 UTF-8 多位元組中間切開，不產生 replacement character；
 * 呼叫方永遠拿到完整 JSON 或明確例外，不會有半個 JSON 充 OK。
 */
private fun readBoundedBody(body: ResponseBody?, cancelCall: () -> Unit): String? {
    if (body == null) return null
    val byteCap = McpTransport.RESPONSE_MAX_BYTES
    val charCap = McpTransport.RESPONSE_MAX_CHARS
    if (body.contentLength() > byteCap) {
        cancelCall()
        throw McpResponseTooLarge("mcp: response body exceeds $byteCap bytes")
    }
    val source = body.source()
    val bytes = Buffer()
    var remaining = byteCap
    while (remaining > 0L) {
        val read = source.read(bytes, minOf(8 * 1024L, remaining))
        if (read == -1L) break
        remaining -= read
    }
    if (remaining == 0L) {
        cancelCall()
        throw McpResponseTooLarge("mcp: response body exceeds $byteCap bytes")
    }
    val text = bytes.readByteArray().toResponseBody(body.contentType()).string()
    if (text.length > charCap) {
        throw McpResponseTooLarge("mcp: response body exceeds $charCap chars")
    }
    return text
}
