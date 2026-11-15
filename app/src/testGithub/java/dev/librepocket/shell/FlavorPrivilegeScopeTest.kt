package dev.librepocket.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.privilege.github.ShizukuGrantPolicy
import dev.librepocket.privilege.github.ShizukuGrantStore
import dev.librepocket.privilege.github.ShizukuPermissionRequests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlavorPrivilegeScopeTest {

    @After
    fun tearDown() {
        ShizukuPermissionRequests.resetProcessForTest()
    }

    @Test
    fun withdraw_detachesUpstreamListener() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 重置 singleton，避免其他測試殘留狀態影響斷言
        ShizukuPermissionRequests.resetProcessForTest()
        val requests = ShizukuPermissionRequests.process()
        // Robolectric 環境下 Shizuku clinit 成功、attach 可成功置守衛；withdraw 應走 detach()
        // （反註冊上游 + 清表 + 守衛 false），不得殘留 attached=true。
        // 此測試在舊 invalidatePending 實作（只作廢/清表、不置守衛 false）上會紅，
        // 是 withdraw→detach 路徑的有效 pin。
        requests.attach()
        // 自我驗證：attach 在此環境下必須成功（否則本測試對 withdraw→detach 無鑑別力）。
        assertTrue("attach must succeed under Robolectric or this test is vacuous", requests.isAttached())
        FlavorPrivilegeScope.withdraw(context)
        assertFalse(ShizukuPermissionRequests.process().isAttached())
    }

    @Test
    fun withdrawClearsGrantStoreAndInvalidatesPendingRequests() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val grantStore = ShizukuGrantStore(context)
        val grant = ShizukuGrantPolicy.grant(
            providerId = "test-provider",
            configRevision = 1L,
            serviceVersion = 13,
        )
        grantStore.save(grant)
        assertNotNull(grantStore.load())

        val requests = ShizukuPermissionRequests.process()
        var callbackFired = false
        val oldEpoch = requests.currentEpoch()
        requests.onResult(1001) { callbackFired = true }

        FlavorPrivilegeScope.withdraw(context)

        // Store is cleared
        assertNull(grantStore.load())
        // Pending callback under old epoch is invalidated and cleared
        requests.emitResult(1001, android.content.pm.PackageManager.PERMISSION_GRANTED, epoch = oldEpoch)
        assertFalse(callbackFired)
        // Requests process epoch has advanced
        assertTrue(requests.currentEpoch() > oldEpoch)
    }
}
