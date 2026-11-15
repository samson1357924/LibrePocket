package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import rikka.shizuku.Shizuku

/**
 * B1 Shizuku 授權請求通道測試（Issue #136 B1）：
 * 請求送出／按碼分發／拒絕映射／異常隔離，零真機、零 binder。
 * 真實 `Shizuku.requestPermission` 全注入假 lambda，不觸碰 Shizuku 類；
 * attach 語義測試注入假 addListener/removeListener 模擬成功/失敗路徑。
 * 未 attach 時 request/requestWithCallback 一律 fail-closed 回傳 false。
 */
class ShizukuPermissionRequestTest {

    /** 以注入假 addListener 成功 attach 一個新實例。 */
    private fun attachedRequests(
        addListener: (Shizuku.OnRequestPermissionResultListener) -> Unit = { },
        removeListener: (Shizuku.OnRequestPermissionResultListener) -> Unit = { },
    ): ShizukuPermissionRequests =
        ShizukuPermissionRequests(addListener, removeListener).also { it.attach() }

    @Test fun requestDispatchesCodeAndReturnsTrue() {
        val requests = attachedRequests()
        var received: Int? = null
        val dispatched = requests.request(1001) { code -> received = code }
        assertTrue(dispatched)
        assertEquals(1001, received)
    }

    @Test fun requestThrowFoldsToFalse() {
        val requests = attachedRequests()
        val dispatched = requests.request(1001) { throw SecurityException("no binder") }
        assertFalse(dispatched)
    }

    @Test fun requestUnattachedFailsClosed() {
        val requests = ShizukuPermissionRequests()
        var received: Int? = null
        val dispatched = requests.request(1001) { code -> received = code }
        assertFalse(dispatched)
        assertEquals(null, received)
    }

    @Test fun resultDispatchesOnlyToMatchingCode() {
        val requests = ShizukuPermissionRequests()
        val seen = mutableListOf<String>()
        requests.onResult(1001) { granted -> seen += "a=$granted" }
        requests.onResult(1002) { granted -> seen += "b=$granted" }
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf("a=true"), seen)
    }

    @Test fun denialMapsToFalse() {
        val requests = ShizukuPermissionRequests()
        var granted: Boolean? = null
        requests.onResult(1001) { granted = it }
        requests.emitResult(1001, PackageManager.PERMISSION_DENIED)
        assertEquals(false, granted)
    }

    @Test fun unknownCodeIsNoop() {
        val requests = ShizukuPermissionRequests()
        var fired = false
        requests.onResult(1001) { fired = true }
        requests.emitResult(9999, PackageManager.PERMISSION_GRANTED)
        assertFalse(fired)
    }

    @Test fun resultCallbackFaultIsolated() {
        val requests = ShizukuPermissionRequests()
        val seen = mutableListOf<String>()
        requests.onResult(1001) { seen += "a" }
        requests.onResult(1001) { throw IllegalStateException("bad callback") }
        requests.onResult(1001) { seen += "b" }
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf("a", "b"), seen)
    }

    @Test fun attachWithoutBinderEnvironmentDoesNotThrow() {
        // JVM 單測無 Shizuku 服務：類初始化即失敗，註冊異常由內部收斂，
        // 守衛恆 false（失敗不卡死，允許重試）；detach 冪等不抛。
        val requests = ShizukuPermissionRequests()
        requests.attach()
        assertFalse(requests.isAttached())
        requests.attach()
        assertFalse(requests.isAttached())
        requests.detach()
        assertFalse(requests.isAttached())
    }

    @Test fun detachClearsCallbacks() {
        val requests = ShizukuPermissionRequests()
        var fired = 0
        requests.onResult(1001) { fired++ }
        requests.attach()
        assertFalse(requests.isAttached())
        requests.detach()
        assertFalse(requests.isAttached())
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(0, fired)
    }

    @Test fun oneShotCallbackConsumption() {
        val requests = ShizukuPermissionRequests()
        var count = 0
        requests.onResult(1001) { count++ }
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(1, count)
        // 第二次分發相同碼不得再次觸發
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(1, count)
    }

    @Test fun epochInvalidationViaInvalidatePending() {
        val requests = ShizukuPermissionRequests()
        var fired = false
        requests.onResult(1001) { fired = true }
        val oldEpoch = requests.currentEpoch()
        requests.invalidatePending()
        // 舊 epoch 的分發不觸發回呼
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED, epoch = oldEpoch)
        assertFalse(fired)
    }

    @Test fun epochInvalidationViaDetach() {
        val requests = ShizukuPermissionRequests()
        var fired = false
        requests.onResult(1001) { fired = true }
        val oldEpoch = requests.currentEpoch()
        requests.detach()
        // 舊 epoch 的分發不觸發回呼
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED, epoch = oldEpoch)
        assertFalse(fired)
    }

    @Test fun invalidatePendingClearsCallbacksAndAllowsSubsequentRequests() {
        val requests = ShizukuPermissionRequests()
        var firstFired = false
        var secondFired = false
        requests.onResult(1001) { firstFired = true }
        requests.invalidatePending()
        requests.onResult(1002) { secondFired = true }
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        requests.emitResult(1002, PackageManager.PERMISSION_GRANTED)
        assertFalse(firstFired)
        assertTrue(secondFired)
    }

    @Test fun injectableListenersAttachDispatchAndDetach() {
        var registered: Shizuku.OnRequestPermissionResultListener? = null
        var removed: Shizuku.OnRequestPermissionResultListener? = null
        val requests = ShizukuPermissionRequests(
            addListener = { registered = it },
            removeListener = { removed = it },
        )
        requests.attach()
        assertTrue(requests.isAttached())
        assertNotNull(registered)

        var grantedResult: Boolean? = null
        requests.onResult(1001) { grantedResult = it }
        registered?.onRequestPermissionResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(true, grantedResult)

        requests.detach()
        assertFalse(requests.isAttached())
        assertEquals(registered, removed)
    }

    @Test fun concurrentDetachDuringAttachRemovesStagedListener() {
        var removedListener: Shizuku.OnRequestPermissionResultListener? = null
        lateinit var requests: ShizukuPermissionRequests
        requests = ShizukuPermissionRequests(
            addListener = { requests.detach() },
            removeListener = { removedListener = it },
        )
        requests.attach()
        assertFalse(requests.isAttached())
        assertNotNull(removedListener)
    }

    @Test fun requestWithCallbackSuccessRegistersAndDispatches() {
        val requests = attachedRequests()
        var granted: Boolean? = null
        var requestedCode: Int? = null
        val ok = requests.requestWithCallback(1001, { granted = it }) { code ->
            requestedCode = code
        }
        assertTrue(ok)
        assertEquals(1001, requestedCode)
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(true, granted)
    }

    @Test fun requestWithCallbackRollsBackOnException() {
        val requests = attachedRequests()
        var callbackFired = false
        val ok = requests.requestWithCallback(1001, { callbackFired = true }) {
            throw SecurityException("binder dead")
        }
        assertFalse(ok)
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertFalse(callbackFired)
    }

    @Test fun requestWithCallback_unattached_returnsFalseAndRegistersNothing() {
        val requests = ShizukuPermissionRequests()
        var fired = false
        var requestSent = false
        val ok = requests.requestWithCallback(1001, { fired = true }) { requestSent = true }
        assertFalse(ok)
        assertFalse(requestSent)
        // 未登記任何回呼：即使結果到達也不觸發
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertFalse(fired)
    }

    @Test fun attachFails_thenRetry_succeeds() {
        var addCalls = 0
        val requests = ShizukuPermissionRequests(
            addListener = {
                addCalls++
                if (addCalls == 1) throw LinkageError("shizuku not ready")
            },
            removeListener = { },
        )
        requests.attach()
        assertFalse(requests.isAttached())
        // 第一次 addListener 拋 LinkageError：守衛不置 true；第二次成功
        requests.attach()
        assertTrue(requests.isAttached())
        assertEquals(2, addCalls)
        var requestedCode: Int? = null
        val ok = requests.request(1001) { code -> requestedCode = code }
        assertTrue(ok)
        assertEquals(1001, requestedCode)
    }

    @Test fun requestWithCallback_duplicateRequestCode_rejected() {
        val requests = attachedRequests()
        var firstFired = false
        var secondFired = false
        val ok1 = requests.requestWithCallback(1001, { firstFired = true }) { }
        val ok2 = requests.requestWithCallback(1001, { secondFired = true }) { }
        assertTrue(ok1)
        assertFalse(ok2)
        // 表內僅一筆：結果只派發給第一位回呼
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertTrue(firstFired)
        assertFalse(secondFired)
    }

    @Test fun requestWithCallback_capEnforced() {
        val requests = attachedRequests()
        repeat(ShizukuPermissionRequests.MAX_OUTSTANDING) { i ->
            val ok = requests.requestWithCallback(5000 + i, { }) { }
            assertTrue("registration $i should succeed", ok)
        }
        // 第 33 筆（超限）拒絕
        val ok = requests.requestWithCallback(5999, { }) { }
        assertFalse(ok)
    }

    @Test fun staleListener_afterDetachReattach_doesNotConsumeNewEpochCallbacks() {
        val listeners = mutableListOf<Shizuku.OnRequestPermissionResultListener>()
        val requests = ShizukuPermissionRequests(
            addListener = { listeners += it },
            // removeListener 靜默失敗（吞掉）：模擬上游反註冊失敗
            removeListener = { throw LinkageError("remove failed") },
        )
        requests.attach()
        val l1 = listeners.last()
        requests.detach() // removeListener 拋 LinkageError → 吞掉，但 attached=false、epoch 前進
        assertFalse(requests.isAttached())
        requests.attach()
        val l2 = listeners.last()
        assertNotSame(l1, l2)

        var fired = false
        val ok = requests.requestWithCallback(42, { fired = true }) { }
        assertTrue(ok)

        // 舊 listener（上游反註冊靜默失敗仍殘留）以新碼觸發：身份檢查必須擋下，
        // 不得以 reattach 後的 epoch 分發 stale 結果
        l1.onRequestPermissionResult(42, PackageManager.PERMISSION_GRANTED)
        assertFalse(fired)
        // 新 listener 正常分發
        l2.onRequestPermissionResult(42, PackageManager.PERMISSION_GRANTED)
        assertTrue(fired)
    }

    @Test fun callback_invalidatingDuringDispatch_stopsBatch() {
        val requests = ShizukuPermissionRequests()
        val seen = mutableListOf<String>()
        requests.onResult(1001) {
            seen += "a"
            requests.invalidatePending()
        }
        requests.onResult(1001) { seen += "b" }
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        // 第一筆回呼內作廢：第二筆必不執行（逐 callback epoch 重查）
        assertEquals(listOf("a"), seen)
    }

    @Test fun dispatch_perCallbackEpochRecheck_afterInvalidateBetweenBatchMembers() {
        var registered: Shizuku.OnRequestPermissionResultListener? = null
        val requests = ShizukuPermissionRequests(
            addListener = { registered = it },
            removeListener = { },
        )
        requests.attach()
        val seen = mutableListOf<String>()
        requests.onResult(7) {
            seen += "a"
            requests.invalidatePending()
        }
        requests.onResult(7) { seen += "b" }
        registered?.onRequestPermissionResult(7, PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf("a"), seen)
    }

    @Test fun requestWithCallback_cancellationRollsBackAndRethrows() {
        val requests = attachedRequests()
        var callbackFired = false
        try {
            requests.requestWithCallback(1001, { callbackFired = true }) {
                throw CancellationException("cancelled")
            }
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected：rethrow
        }
        // 回呼已回滾：表空，結果到達也不觸發
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertFalse(callbackFired)
    }

    @Test fun detach_removeListenerThrows_doesNotPropagate_andDetaches() {
        val requests = ShizukuPermissionRequests(
            addListener = { },
            removeListener = { throw LinkageError("remove failed") },
        )
        requests.attach()
        assertTrue(requests.isAttached())
        var fired = false
        requests.onResult(1001) { fired = true }
        requests.detach() // 不得拋出
        assertFalse(requests.isAttached())
        // 表已清空 + epoch 前進：結果到達不觸發
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertFalse(fired)
    }

    @Test fun detachThenReattach_dispatchesThroughNewListener() {
        val listeners = mutableListOf<Shizuku.OnRequestPermissionResultListener>()
        val removed = mutableListOf<Shizuku.OnRequestPermissionResultListener>()
        val requests = ShizukuPermissionRequests(
            addListener = { listeners += it },
            removeListener = { removed += it },
        )
        requests.attach()
        val l1 = listeners.last()
        requests.detach()
        assertEquals(listOf(l1), removed)
        requests.attach()
        val l2 = listeners.last()
        assertNotSame(l1, l2)
        var granted: Boolean? = null
        requests.onResult(1001) { granted = it }
        l2.onRequestPermissionResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(true, granted)
    }

    @Test fun request_cancellation_rethrows() {
        val requests = attachedRequests()
        try {
            requests.request(1001) { throw CancellationException("cancelled") }
            org.junit.Assert.fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected：rethrow，不得吞成 false
        }
    }

    @Test fun requestWithCallback_afterDetach_failsClosed() {
        val requests = attachedRequests()
        assertTrue(requests.isAttached())
        requests.detach()
        assertFalse(requests.isAttached())
        var fired = false
        var requestSent = false
        val ok = requests.requestWithCallback(1001, { fired = true }) { requestSent = true }
        assertFalse(ok)
        assertFalse(requestSent)
        // 未登記任何回呼：結果到達也不觸發
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertFalse(fired)
        // request() 同樣 fail-closed
        var plainSent = false
        assertFalse(requests.request(1001) { plainSent = true })
        assertFalse(plainSent)
    }

    @Test fun processSingletonStabilityAndReset() {
        try {
            val instance1 = ShizukuPermissionRequests.process()
            val instance2 = ShizukuPermissionRequests.process()
            assertSame(instance1, instance2)
            ShizukuPermissionRequests.resetProcessForTest()
            val instance3 = ShizukuPermissionRequests.process()
            assertNotSame(instance1, instance3)
        } finally {
            ShizukuPermissionRequests.resetProcessForTest()
        }
    }
}
