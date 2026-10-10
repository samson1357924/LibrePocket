package dev.librepocket.mcp

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * D03 Streamable HTTP 傳輸：POST `{baseUrl}/mcp` + Bearer（舊 SSE 不做）。
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

        /** 成功 body 上限：既有 `take(64_000)` 語義的有界讀版本（讀塊 8KiB 同級 provider）。 */
        internal const val RESPONSE_MAX_BYTES = 64_000L
    }

    /**
     * 取消語義（對齊 provider `executeProviderRequest`）：
     * - handler 自 `execute()` 前覆蓋到 body 消費完，`onCancelling=true` 使外部取消
     *   或總 deadline 到期立即 `call.cancel()` 中斷 blocking header/body IO，而非等其自然返回；
     * - 取消與否只以 coroutine Job（[ensureActive]）判斷，不以 `Call.isCanceled()` 判斷；
     * - 總 deadline = `withTimeout(McpTimeouts.clamp)`（沿用既有常數），到期回 null（TIMEOUT）；
     * - 外部取消透傳 `CancellationException`（不斷熔斷、不映射 TRANSPORT，見 [McpServer]）；
     * - 其餘 `IOException` 透傳（呼叫方映射 TRANSPORT）。
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun postJson(
        baseUrl: String,
        bearer: String,
        bodyJson: String,
        timeoutMs: Long,
    ): Pair<Int, String?>? {
        val url = McpWire.endpoint(baseUrl)
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(JSON))
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        val deadlineMs = McpTimeouts.clamp(timeoutMs)
        try {
            return withTimeout(deadlineMs) {
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
                            val text = readBoundedBody(resp.body) {
                                try {
                                    call.cancel()
                                } catch (_: Exception) {
                                }
                            }
                            code to text
                        }
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    } finally {
                        cancellation?.dispose()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            return null
        }
    }
}

/**
 * 有界讀成功 body（至多 [McpTransport.RESPONSE_MAX_BYTES] 位元組）。
 *
 * 64k 內位元組與既有 `.string()?.take(64_000)` 同值（同 contentType 解碼）；
 * 達上限即 terminal：先 cancel 再停讀關閉，不等 EOF、不排空剩餘位元組
 * （關閉時不再花時間丟棄未讀資料，同級 provider `readProviderErrorPrefix` 做法）。
 */
private fun readBoundedBody(body: ResponseBody?, cancelCall: () -> Unit): String? {
    if (body == null) return null
    val cap = McpTransport.RESPONSE_MAX_BYTES
    val source = body.source()
    val bytes = Buffer()
    var remaining = cap
    while (true) {
        val read = source.read(bytes, minOf(8 * 1024L, remaining + 1L))
        if (read == -1L) break
        if (read > remaining) {
            cancelCall()
            val capped = bytes.readByteArray(cap)
            return capped.toResponseBody(body.contentType()).string()
        }
        remaining -= read
    }
    return bytes.readByteArray().toResponseBody(body.contentType()).string()
}
