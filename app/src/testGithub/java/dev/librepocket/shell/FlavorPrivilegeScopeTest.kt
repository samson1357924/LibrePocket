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
