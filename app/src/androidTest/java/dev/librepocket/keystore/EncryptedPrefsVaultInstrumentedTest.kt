package dev.librepocket.keystore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [EncryptedPrefsVault] on-device tests (SPEC §10.3 [I] + §11.5).
 *
 * Runs against the REAL Android Keystore (real MasterKey +
 * EncryptedSharedPreferences); the Robolectric counterpart
 * (`EncryptedPrefsVaultTest`) only covers key-format / validation logic.
 *
 * Requires a device or emulator (API 33/37 matrix); see
 * `docs/specs/P1_ANDROIDTEST_RUNBOOK.md`. Never touches the network.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPrefsVaultInstrumentedTest {

    private fun appContext(): Context =
        ApplicationProvider.getApplicationContext<Context>()

    private fun vault(): EncryptedPrefsVault = EncryptedPrefsVault(appContext())

    @After
    fun clearPrefs() {
        appContext().getSharedPreferences(
            EncryptedPrefsVault.PREFS_FILE,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun put_get_delete_roundTripOnRealKeystore() = runBlocking {
        val v = vault()
        val secret = "sk-live-device-roundtrip-0123456789".toCharArray()
        v.putKey("prov-1", secret)

        // Caller array is wiped after write.
        assertTrue(secret.all { it == '\u0000' })

        val read = v.getKey("prov-1")
        assertArrayEquals(
            "sk-live-device-roundtrip-0123456789".toCharArray(),
            read,
        )
        read?.fill('\u0000')
        assertTrue(v.hasKey("prov-1"))

        v.deleteKey("prov-1")
        assertFalse(v.hasKey("prov-1"))
        assertNull(v.getKey("prov-1"))
    }

    @Test
    fun hasKeyDoesNotLeakLength() = runBlocking {
        val v = vault()
        assertFalse(v.hasKey("prov-missing"))
        v.putKey("prov-missing", "exactly-16-chars!".toCharArray())
        // Only presence is observable, never the stored length.
        assertTrue(v.hasKey("prov-missing"))
        v.deleteKey("prov-missing")
    }

    @Test
    fun keysSurviveVaultRecreation() = runBlocking {
        vault().putKey("prov-persist", "persist-me-0123456789".toCharArray())
        // New instance ~ process restart: same Keystore alias, same file.
        val read = vault().getKey("prov-persist")
        assertArrayEquals("persist-me-0123456789".toCharArray(), read)
        read?.fill('\u0000')
        vault().deleteKey("prov-persist")
    }

    @Test
    fun rejectsBlankAndShortKeys() = runBlocking {
        val v = vault()
        for (bad in listOf("   ", "short7!")) {
            var rejected = false
            try {
                v.putKey("prov-bad", bad.toCharArray())
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("expected rejection: '$bad'", rejected)
        }
        assertFalse(v.hasKey("prov-bad"))
    }
}
