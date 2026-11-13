package dev.librepocket.privilege.github

import android.content.pm.PackageManager
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
 * 僅 attach/detach 語義測試走失敗路徑（JVM 無 binder，守衛恆 false）。
 */
class ShizukuPermissionRequestTest {

    @Test fun requestDispatchesCodeAndReturnsTrue() {
        val requests = ShizukuPermissionRequests()
        var received: Int? = null
        val dispatched = requests.request(1001) { code -> received = code }
        assertTrue(dispatched)
        assertEquals(1001, received)
    }

    @Test fun requestThrowFoldsToFalse() {
        val requests = ShizukuPermissionRequests()
        val dispatched = requests.request(1001) { throw SecurityException("no binder") }
        assertFalse(dispatched)
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
        val requests = ShizukuPermissionRequests()
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
        val requests = ShizukuPermissionRequests()
        var callbackFired = false
        val ok = requests.requestWithCallback(1001, { callbackFired = true }) {
            throw SecurityException("binder dead")
        }
        assertFalse(ok)
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertFalse(callbackFired)
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
