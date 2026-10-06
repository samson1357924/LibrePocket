package dev.librepocket.keystore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * M2 vault-logic tests (spec §10.3, JVM side): round-trip, overwrite,
 * delete, validation, wipe semantics, and key-format rules.
 *
 * Uses [InMemoryPrefs] so these run on plain JVM; the real
 * EncryptedSharedPreferences path is covered by [AndroidEncryptedPrefsTest].
 */
class EncryptedPrefsVaultTest {

    private lateinit var vault: EncryptedPrefsVault

    @Before
    fun setUp() {
        vault = EncryptedPrefsVault(InMemoryPrefs())
    }

    @Test fun roundTrip(): Unit = runBlocking {
        assertFalse(vault.hasKey("prov-1"))
        assertNull(vault.getKey("prov-1"))

        vault.putKey("prov-1", "secret-key-123".toCharArray())
        assertTrue(vault.hasKey("prov-1"))

        val got = vault.getKey("prov-1")!!
        try {
            assertArrayEquals("secret-key-123".toCharArray(), got)
        } finally {
            got.fill('\u0000')
        }

        vault.deleteKey("prov-1")
        assertFalse(vault.hasKey("prov-1"))
        assertNull(vault.getKey("prov-1"))
    }

    @Test fun overwrite(): Unit = runBlocking {
        vault.putKey("p", "first-key-000".toCharArray())
        vault.putKey("p", "second-key-111".toCharArray())
        val got = vault.getKey("p")!!
        try {
            assertArrayEquals("second-key-111".toCharArray(), got)
        } finally {
            got.fill('\u0000')
        }
    }

    @Test fun keysAreIsolatedPerProvider(): Unit = runBlocking {
        vault.putKey("a", "key-for-aaaa".toCharArray())
        vault.putKey("b", "key-for-bbbb".toCharArray())
        val ga = vault.getKey("a")!!
        val gb = vault.getKey("b")!!
        try {
            assertArrayEquals("key-for-aaaa".toCharArray(), ga)
            assertArrayEquals("key-for-bbbb".toCharArray(), gb)
        } finally {
            ga.fill('\u0000')
            gb.fill('\u0000')
        }
        vault.deleteKey("a")
        assertFalse(vault.hasKey("a"))
        assertTrue(vault.hasKey("b"))
    }

    @Test fun inputArrayWipedAfterPut(): Unit = runBlocking {
        val input = "wipe-me-12345".toCharArray()
        vault.putKey("p", input)
        assertArrayEquals(CharArray(input.size) { '\u0000' }, input)
    }

    @Test fun returnedArrayIsACopy(): Unit = runBlocking {
        vault.putKey("p", "copy-me-12345".toCharArray())
        val first = vault.getKey("p")!!
        first.fill('X')
        val second = vault.getKey("p")!!
        try {
            assertArrayEquals("copy-me-12345".toCharArray(), second)
        } finally {
            first.fill('\u0000')
            second.fill('\u0000')
        }
    }

    @Test fun rejectsShortKey(): Unit = runBlocking {
        try {
            vault.putKey("p", "zz9".toCharArray())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Message must not echo the key material.
            assertTrue(!e.message!!.contains("zz9"))
        }
        assertFalse(vault.hasKey("p"))
    }

    @Test fun rejectsBlankKey(): Unit = runBlocking {
        try {
            vault.putKey("p", "        ".toCharArray())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message != null)
        }
    }

    @Test fun rejectsBlankProviderId(): Unit = runBlocking {
        for (bad in listOf("", "   ")) {
            try {
                vault.putKey(bad, "valid-key-123".toCharArray())
                fail("expected IllegalArgumentException for [$bad]")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message != null)
            }
            assertNull(vault.getKey("missing"))
        }
    }

    @Test fun trimsBeforeWrite(): Unit = runBlocking {
        vault.putKey("p", "  padded-key-123  ".toCharArray())
        val got = vault.getKey("p")!!
        try {
            assertArrayEquals("padded-key-123".toCharArray(), got)
        } finally {
            got.fill('\u0000')
        }
    }

    @Test fun prefsKeyFormat(): Unit = runBlocking {
        assertEquals("provider_key/abc", EncryptedPrefsVault.prefsKey("abc"))
        // Format carries no length/content signal beyond the id itself.
        vault.putKey("abc", "some-key-000".toCharArray())
        assertTrue(vault.hasKey("abc"))
    }

    @Test fun deleteMissingIsNoop(): Unit = runBlocking {
        vault.deleteKey("ghost")
        assertFalse(vault.hasKey("ghost"))
    }
}
