package dev.librepocket.mcp

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.librepocket.policy.EndpointNetworkOptIn
import dev.librepocket.policy.NetworkEgressPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #18 取消邊界的裝置契約（minimal journey，bounded）：在真機／模擬器的
 * OkHttp stack 上，取消必須中斷正在等待 body 的 blocking IO，實際呼叫
 * [Call.cancel]，且不得殘留 dispatcher call。
 *
 * 定位：這是 `McpTransportCancelTest`（JVM）同一 contract 的**裝置執行版**，
 * 刻意重疊場景；不計為新增一塊 contract 覆蓋。JVM 版釘邏輯與假 transport，
 * 此檔只補真機 OkHttp 排程／IO 中斷與 `Call.cancel()` 的裝置證據。
 *
 * 合成資料：MockWebServer（loopback）、假 bearer "local-test-token"、假 body
 * "{}"；不碰 production endpoint、不讀 credential／私人 transcript、不寫檔。
 * 同步完全靠 MockWebServer 的 request 信號 + [CountDownLatch]，沒有固定
 * `Thread.sleep`；3s／2s／1.5s 皆為 liveness 上限，不是用時間證明正確性。
 *
 * 此檔是 source contract；本次變更未執行 Gradle／adb，尚未在裝置實跑。
 * 實跑須串行執行並保留 SHA／API／flavor／報告證據。
 */
@RunWith(AndroidJUnit4::class)
class CancelBoundaryInstrumentedTest {

    @Test
    fun cancelDuringBlockedBodyReadInterruptsCallAndLeavesNoLeftover() = runBlocking {
        val web = MockWebServer()
        web.start()
        val cancelled = CountDownLatch(1)
        val client: OkHttpClient = McpTransport.defaultHttp().newBuilder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.countDown()
                }
            })
            .build()
        val transport = McpTransport(client)
        val outcome = AtomicReference<Result<Pair<Int, String?>?>>()
        try {
            // Headers 立即到，body 延遲 10s：取消必須中斷讀取而不是等自然超時。
            web.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("{}")
                    .setBodyDelay(10, TimeUnit.SECONDS),
            )
            val job = launch(Dispatchers.IO) {
                outcome.set(
                    runCatching {
                        transport.postJson(
                            web.url("/").toString(),
                            "local-test-token",
                            "{}",
                            McpTimeouts.DEFAULT_MS,
                            EndpointNetworkOptIn(NetworkEgressPolicy.ALLOW_CLEARTEXT_PRIVATE),
                        )
                    },
                )
            }
            try {
                val request = withContext(Dispatchers.IO) { web.takeRequest(5, TimeUnit.SECONDS) }
                assertNotNull("request must reach the local fake server before cancel", request)
                val joined = withTimeoutOrNull(3_000) {
                    job.cancelAndJoin()
                    true
                }
                assertTrue("cancel must unblock blocking IO on device", joined == true)
                assertTrue(
                    "real OkHttp Call.cancel() must fire on cancel",
                    withContext(Dispatchers.IO) { cancelled.await(2, TimeUnit.SECONDS) },
                )
                assertEquals("exactly one synthetic request", 1, web.requestCount)
                assertEquals("no running call left", 0, client.dispatcher.runningCallsCount())
                assertEquals("no queued call left", 0, client.dispatcher.queuedCallsCount())
                val result = outcome.get()
                assertNotNull("cancelled coroutine must have settled", result)
                assertTrue(
                    "cancel must propagate CancellationException (not a value/timeout), got $result",
                    result!!.exceptionOrNull() is CancellationException,
                )
            } finally {
                job.cancel()
                withTimeoutOrNull(1_500) { job.join() }
            }
        } finally {
            web.shutdown()
        }
    }
}
