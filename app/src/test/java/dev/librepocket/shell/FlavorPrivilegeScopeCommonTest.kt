package dev.librepocket.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Common flavor test verifying FlavorPrivilegeScope.withdraw(context)
 * executes cleanly across all flavors (Play, FOSS, GitHub).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FlavorPrivilegeScopeCommonTest {

    @Test
    fun withdrawCompletesWithoutException() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        FlavorPrivilegeScope.withdraw(context)
    }
}
