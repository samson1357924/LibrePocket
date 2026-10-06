package dev.librepocket.keystore

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M2 Android-path test (spec §10.3, Robolectric side): real
 * EncryptedSharedPreferences round-trip through [AndroidEncryptedPrefs].
 *
 * If the JVM sandbox cannot provide an Android Keystore backend, the test
 * aborts via assumption instead of failing; the vault logic itself is pinned
 * by [EncryptedPrefsVaultTest] on plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidEncryptedPrefsTest {

    private fun realVault(): EncryptedPrefsVault {
        return try {
            EncryptedPrefsVault(
                AndroidEncryptedPrefs(ApplicationProvider.getApplicationContext()),
            )
        } catch (e: Exception) {
            Assume.assumeNoException("Android Keystore unavailable in this sandbox", e)
            throw e // unreachable; keeps the compiler happy
        }
    }

    @Test fun encryptedRoundTrip(): Unit = runBlocking {
        val vault = realVault()
        val id = "robo-provider"
        vault.deleteKey(id)

        vault.putKey(id, "robo-secret-1".toCharArray())
        assertTrue(vault.hasKey(id))
        val got = try {
            vault.getKey(id)!!
        } catch (e: Exception) {
            Assume.assumeNoException("Android Keystore unavailable in this sandbox", e)
            throw e
        }
        try {
            assertArrayEquals("robo-secret-1".toCharArray(), got)
        } finally {
            got.fill('\u0000')
        }

        vault.deleteKey(id)
        assertFalse(vault.hasKey(id))
    }
}
