package dev.librepocket.web

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `fetchWithTimeout` 取消語義迴歸（#138 D2；全 fake，零真網）。
 *
 * Provider／Service 層已有外層取消不回退證據（`WebSearchPhase5Test`
 * `outerTimeoutIsCancelNotFallback` 走 `SearxngSearchProvider` 整條路、
 * `WebSearchD2Test.cancellationDoesNotWriteCache` 證不寫快取），但映射層
 * `fetchWithTimeout` 內五處 `ensureActive()` 守衛——`TimeoutCancellationException`／
 * `SocketTimeoutException`／`InterruptedIOException`／`IOException`／未知 `Exception`
 * 在遇到取消時不得映射為 `TIMEOUT`／`TRANSPORT` 值（否則上層 `mayFallback` 會把一次
 * 外部取消誤判為可重試的傳輸失敗）——此前沒有任何測試同時「拋 IO 錯」又
 * 「帶取消」進入 catch，刪掉守衛全部測試照綠。此檔補的正是這塊缺口：
 * 取消永遠贏過錯誤映射，原樣透傳 [CancellationException]，絕不回
 * [FetchOut.Fail]（回值即代表可 fallback／可快取判斷的輸入）。
 *
 * D3 銜接（#138）：production OkHttp adapter 落地後必須維持 cancellable
 * fetcher 契約（suspend 點透傳 CE；blocking OkHttp `Call` 以 Job
 * `invokeOnCompletion(onCancelling=true)` 橋接 `Call.cancel()`，對齊
 * `McpTransport`／`ProviderTransport` 做法），否則本檔以「吞取消後拋錯的
 * 壞 fetcher」證明的映射層保證，無法轉移到真實 IO 上。Adapter 本人須另補
 * MockWebServer＋慢響應＋cancel 的 `Call.cancel()` 證據測試（本 D2 假尚未存在）。
 *
 * 變異驗證（2026-11-13）：拔掉 `fetchWithTimeout` 五處 `ensureActive()` 守衛時，
 * 守衛 1（`TimeoutCancellationException`）由 `outerTimeoutSurfacesAsTimeoutCancellationNotFailTimeout`
 * 擊殺（`withTimeout(150)` 內捕獲 `returned` 並於 `catch` 斷言 `assertNull`，若映射回
 * `Fail(TIMEOUT)` 即轉紅）；守衛 2–5（`SocketTimeoutException`／`InterruptedIOException`／
 * `IOException`／未知 `Exception`）由 `transportErrorRacingWithCancelLosesToCancellation`
 * 擊殺（回 `Fail` 值而非拋 `CE`）。其餘傳播／自家超時測試釘住單元層級契約。
 */
class WebHttpFetchCancelTest {

    private val url = "https://search.example/search?format=json&q=q"

    private fun launchFetch(
        scope: kotlinx.coroutines.CoroutineScope,
        httpGet: WebHttpGet,
        timeoutMs: Long = 60_000,
    ): Pair<kotlinx.coroutines.Job, AtomicReference<Result<FetchOut>?>> {
        val outcome = AtomicReference<Result<FetchOut>?>(null)
        val job = scope.async {
            outcome.set(runCatching { fetchWithTimeout(httpGet, url, emptyMap(), timeoutMs) })
        }
        return job to outcome
    }

    @Test fun slowFetchExternalCancelPropagatesInsteadOfFail() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val httpGet = WebHttpGet { _, _ ->
            entered.complete(Unit)
            release.await()
            WebHttpResponse(200, "{}")
        }
        val (job, outcome) = launchFetch(this, httpGet)
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { job.cancelAndJoin() }
        val result = outcome.get()
        assertNotNull("子協程應已產出結果", result)
        val failure = result!!.exceptionOrNull()
        assertTrue(
            "外部取消必須透傳 CancellationException（非 Fail 值），實際=$result",
            failure is CancellationException,
        )
    }

    @Test fun outerTimeoutSurfacesAsTimeoutCancellationNotFailTimeout() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val httpGet = WebHttpGet { _, _ ->
            entered.complete(Unit)
            delay(30_000) // cooperative suspend：外層 withTimeout 可中斷
            WebHttpResponse(200, "{}")
        }
        var returned: FetchOut? = null
        val job = async {
            withTimeout(150) {
                returned = fetchWithTimeout(httpGet, url, emptyMap(), timeoutMs = 10_000)
            }
        }
        withTimeout(5_000) { entered.await() }
        try {
            job.await()
            fail("外層 withTimeout 必須拋 CE，不得回 Fail(TIMEOUT)")
        } catch (e: CancellationException) {
            assertNull("fetchWithTimeout 絕不得回傳 Fail 值，必須以異常透傳取消", returned)
            // 外層取消以 TCE 送達：自家 timeout 未到（10s），必須透傳而非映射。
            assertTrue("外層 deadline 必須透傳 TimeoutCancellationException，實際=$e", e is TimeoutCancellationException)
        }
    }

    @Test fun selfTimeoutStillMapsToFailTimeout() = runBlocking {
        var observedCancel = false
        val httpGet = WebHttpGet { _, _ ->
            try {
                delay(30_000)
                WebHttpResponse(200, "{}")
            } catch (e: CancellationException) {
                observedCancel = true
                throw e
            }
        }
        // 無外層取消：自家 timeout 到期 → Fail(TIMEOUT)（可 fallback 值）。
        val out = fetchWithTimeout(httpGet, url, emptyMap(), timeoutMs = 50)
        assertTrue("自家超時必須回 Fail，實際=$out", out is FetchOut.Fail)
        assertTrue(
            "自家超時必須映射 TIMEOUT，實際=$out",
            (out as FetchOut.Fail).reason == WebFailureReason.TIMEOUT,
        )
        assertTrue("自家 withTimeout 必須真的中斷底層 suspend", observedCancel)
    }

    /**
     * IO 錯誤與取消競速時取消獲勝：壞 fetcher 先吞掉取消（模擬不可取消的
     * blocking IO 中斷後才回報傳輸錯誤），再拋各類傳輸異常；映射層 catch 內的
     * `ensureActive()` 必須把 CE 丟出來，不得回傳可 fallback 的 Fail 值。
     * 拿掉守衛 2–5 任一 `ensureActive()` 此測即紅（回 Fail 而非拋 CE）。
     */
    @Test fun transportErrorRacingWithCancelLosesToCancellation() = runBlocking {
        val makers: List<Pair<String, () -> Exception>> = listOf(
            "SocketTimeoutException" to { SocketTimeoutException("read timed out") },
            "InterruptedIOException" to { InterruptedIOException("interrupted") },
            "IOException" to { IOException("down") },
            "genericException" to { IllegalStateException("broken fetcher") },
        )
        for ((name, makeError) in makers) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val httpGet = WebHttpGet { _, _ ->
                entered.complete(Unit)
                try {
                    release.await() // 永不放行：只靠 cancel 喚醒
                    throw makeError() // 不可達（防呆）
                } catch (e: CancellationException) {
                    // 吞取消後拋傳輸錯：此時外層 Job 已取消，映射層必須仍拋 CE。
                    throw makeError()
                }
            }
            val (job, outcome) = launchFetch(this, httpGet)
            withTimeout(5_000) { entered.await() }
            job.cancel()
            withTimeout(5_000) { job.join() }
            val result = outcome.get()
            assertNotNull("[$name] 子協程應已產出結果", result)
            val failure = result!!.exceptionOrNull()
            assertTrue(
                "[$name] 取消競速下必須透傳 CancellationException（非 Fail 值），實際=$result",
                failure is CancellationException,
            )
        }
    }
}
