package dev.librepocket.privilege.github

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // 失敗只影響上游註冊，不清本地表：本地回呼仍有效。
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(1, fired)
        requests.detach()
        assertFalse(requests.isAttached())
        requests.emitResult(1001, PackageManager.PERMISSION_GRANTED)
        assertEquals(1, fired)
        requests.detach()
        assertFalse(requests.isAttached())
    }
}
