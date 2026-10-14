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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * D03 Streamable HTTP 傳輸：POST `endpoint(baseUrl)`（使用者自訂完整 URL；bare-origin 自動補 `/mcp`，有 path 原樣保留）+ Bearer（舊 SSE 不做）。
 *
 * 安全約定：
 * - [bearer] 只放 `Authorization` 標頭，永不拼進 body，不寫 log；
 * - 呼叫方（[McpServer]）負責從 KeyVault 取 key、用後即擦，本層不留存；
 * - 回傳 null = 本步超時（呼叫方映射為 [McpStatus.TIMEOUT]，不重試）。
 */
class McpTransport(
    private val http: OkHttpClient = defaultHttp(),
) {
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 連接 5s / 寫 10s / 讀 30s；單步總超時另由 [McpTimeouts] 鉗制 15–30s。 */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

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
     *   保留 401/403→UNAUTHORIZED、其餘→TRANSPORT 語義，且 close() 不排空大 body；
     * - 其餘 `IOException` 透傳（呼叫方映射 TRANSPORT）。
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun postJson(
        baseUrl: String,
        bearer: String,
        bodyJson: String,
        timeoutMs: Long,
    ): Pair<Int, String?>? {
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
            .post(bodyJson.toRequestBody(JSON))
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
                            // 非 2xx：McpServer 只看 code（401/403→UNAUTHORIZED，其餘→TRANSPORT），
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
