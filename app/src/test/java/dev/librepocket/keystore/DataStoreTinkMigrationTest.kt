package dev.librepocket.keystore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Minimal in-memory [KeyVault] for migration tests. */
private class FakeVault : KeyVault {
    val map = LinkedHashMap<String, CharArray>()
    var failOnPut: String? = null

    override suspend fun putKey(providerId: String, apiKey: CharArray) {
        if (providerId == failOnPut) {
            throw IllegalStateException("injected failure")
        }
        map[providerId] = apiKey.copyOf()
    }

    override suspend fun getKey(providerId: String): CharArray? = map[providerId]?.copyOf()

    override suspend fun deleteKey(providerId: String) {
        map.remove(providerId)
    }

    override suspend fun hasKey(providerId: String): Boolean = map.containsKey(providerId)
}

/** Aead stub: never used (the vault itself throws first), only satisfies the signature. */
private class NoopAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = ciphertext
}

/**
 * M2 migration placeholder tests (spec §10.3 T4, JVM/Robolectric sides):
 * the P2 stub throws, and [KeyMigration] copies + verifies + cleans up,
 * retaining the source when interrupted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataStoreTinkMigrationTest {

    private fun stub(): DataStoreTinkVault {
        val context: Context = ApplicationProvider.getApplicationContext()
        return DataStoreTinkVault(context, NoopAead())
    }

    @Test fun stubThrowsP2(): Unit = runBlocking {
        val vault = stub()
        try {
            vault.putKey("p", "some-key-000".toCharArray())
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("P2"))
        }
        try {
            vault.getKey("p")
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("P2"))
        }
        try {
            vault.deleteKey("p")
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("P2"))
        }
        try {
            vault.hasKey("p")
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message!!.contains("P2"))
        }
    }

    @Test fun migrateCopiesVerifiesAndCleansSource(): Unit = runBlocking {
        val source = FakeVault()
        val target = FakeVault()
        source.putKey("a", "key-for-aaaa".toCharArray())
        source.putKey("b", "key-for-bbbb".toCharArray())

        val result = KeyMigration.migrate(listOf("a", "b", "missing"), source, target)

        assertEquals(2, result.migrated)
        val ta = target.getKey("a")!!
        try {
            assertArrayEquals("key-for-aaaa".toCharArray(), ta)
        } finally {
            ta.fill('\u0000')
        }
        assertTrue(target.hasKey("b"))
        assertFalse(source.hasKey("a"))
        assertFalse(source.hasKey("b"))
    }

    @Test fun interruptedMigrationRetainsSource(): Unit = runBlocking {
        val source = FakeVault()
        val target = FakeVault().also { it.failOnPut = "b" }
        source.putKey("a", "key-for-aaaa".toCharArray())
        source.putKey("b", "key-for-bbbb".toCharArray())

        try {
            KeyMigration.migrate(listOf("a", "b"), source, target)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("injected"))
        }
        // Atomicity: nothing was deleted from the source.
        assertTrue(source.hasKey("a"))
        assertTrue(source.hasKey("b"))
    }

    @Test fun migrateWithoutSourceDelete(): Unit = runBlocking {
        val source = FakeVault()
        val target = FakeVault()
        source.putKey("a", "key-for-aaaa".toCharArray())

        val result = KeyMigration.migrate(
            listOf("a"),
            source,
            target,
            deleteSourceAfterVerify = false,
        )
        assertEquals(1, result.migrated)
        assertTrue(source.hasKey("a"))
        assertTrue(target.hasKey("a"))
    }
}
