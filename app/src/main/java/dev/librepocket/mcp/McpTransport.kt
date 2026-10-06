package dev.librepocket.mcp

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    }

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
        val call = http.newCall(request)
        currentCoroutineContext().job?.invokeOnCompletion {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        try {
            return withTimeout(McpTimeouts.clamp(timeoutMs)) {
                withContext(Dispatchers.IO) {
                    call.execute().use { resp ->
                        val code = resp.code
                        val text = try {
                            resp.body?.string()?.take(64_000)
                        } catch (_: IOException) {
                            null
                        }
                        code to text
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
            return null
        }
    }
}
