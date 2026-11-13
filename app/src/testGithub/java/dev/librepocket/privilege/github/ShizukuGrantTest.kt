package dev.librepocket.privilege.github

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.librepocket.shell.PendingRevokeMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * B1 授權持久化測試（Issue #136 B1）：
 * grant 綁定 endpoint/config revision，撤銷即失效，fail-closed。
 * DataStore 以檔案級假實例（`PreferenceDataStoreFactory`＋暫存檔）驅動，
 * 純 JVM，不碰真機/真 su/Shizuku 連線。
 */
class ShizukuGrantTest {

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("shizuku-grant-test").toFile()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun store(name: String = "grant_${System.nanoTime()}.preferences_pb"): ShizukuGrantStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, name) }
        return ShizukuGrantStore(dataStore)
    }

    @Test fun neverGrantedIsInvalid() {
        val check = ShizukuGrantPolicy.check(null, "preset:openai", 3L)
        assertTrue("$check", check is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, (check as GrantCheck.Invalid).reason)
    }

    @Test fun malformedGrantIsInvalid() {
        val blankCheck = ShizukuGrantPolicy.check(
            ShizukuGrant("", 3L, 0L, 15),
            "preset:openai",
            3L,
        )
        assertTrue("$blankCheck", blankCheck is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_MALFORMED, (blankCheck as GrantCheck.Invalid).reason)

        val isoControlCheck = ShizukuGrantPolicy.check(
            ShizukuGrant("provider\u0000", 3L, 0L, 15),
            "provider\u0000",
            3L,
        )
        assertTrue("$isoControlCheck", isoControlCheck is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_MALFORMED, (isoControlCheck as GrantCheck.Invalid).reason)

        val zeroRevCheck = ShizukuGrantPolicy.check(
            ShizukuGrant("preset:openai", 0L, 0L, 15),
            "preset:openai",
            0L,
        )
        assertTrue("$zeroRevCheck", zeroRevCheck is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_MALFORMED, (zeroRevCheck as GrantCheck.Invalid).reason)

        val negativeRevCheck = ShizukuGrantPolicy.check(
            ShizukuGrant("preset:openai", -1L, 0L, 15),
            "preset:openai",
            -1L,
        )
        assertTrue("$negativeRevCheck", negativeRevCheck is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_MALFORMED, (negativeRevCheck as GrantCheck.Invalid).reason)

        val negAtCheck = ShizukuGrantPolicy.check(
            ShizukuGrant("preset:openai", 3L, -1L, 15),
            "preset:openai",
            3L,
        )
        assertTrue("$negAtCheck", negAtCheck is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_MALFORMED, (negAtCheck as GrantCheck.Invalid).reason)
    }

    @Test fun grantValidationRejectsInvalidInputs() {
        try {
            ShizukuGrantPolicy.grant("  ", 3L, 15)
            fail("blank providerId must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_ID_INVALID", e.message)
        }
        try {
            ShizukuGrantPolicy.grant("provider\u001b", 3L, 15)
            fail("control char in providerId must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_ID_INVALID", e.message)
        }
        try {
            ShizukuGrantPolicy.grant("preset:openai", 0L, 15)
            fail("revision 0 must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("CONFIG_REVISION_INVALID", e.message)
        }
        try {
            ShizukuGrantPolicy.grant("preset:openai", -1L, 15)
            fail("negative revision must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("CONFIG_REVISION_INVALID", e.message)
        }
        try {
            ShizukuGrantPolicy.grant("preset:openai", 3L, 15, atMs = -1L)
            fail("negative atMs must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("AT_MS_NEGATIVE", e.message)
        }
    }

    @Test fun unreadableConfigFailsClosed() {
        val grant = ShizukuGrantPolicy.grant("preset:openai", 3L, 15)
        assertTrue(ShizukuGrantPolicy.check(grant, null, 3L) is GrantCheck.Invalid)
        assertTrue(ShizukuGrantPolicy.check(grant, "", 3L) is GrantCheck.Invalid)
        assertTrue(ShizukuGrantPolicy.check(grant, "preset:openai", null) is GrantCheck.Invalid)
    }

    @Test fun endpointSwitchInvalidatesGrant() {
        val grant = ShizukuGrantPolicy.grant("preset:openai", 3L, 15)
        val check = ShizukuGrantPolicy.check(grant, "custom:abcdef", 3L)
        assertTrue("$check", check is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED, (check as GrantCheck.Invalid).reason)
    }

    @Test fun revisionBumpInvalidatesGrant() {
        val grant = ShizukuGrantPolicy.grant("preset:openai", 3L, 15)
        val check = ShizukuGrantPolicy.check(grant, "preset:openai", 4L)
        assertTrue("$check", check is GrantCheck.Invalid)
        assertEquals(ShizukuGrantPolicy.REASON_REVISION_STALE, (check as GrantCheck.Invalid).reason)
    }

    @Test fun matchingEndpointAndRevisionIsValid() {
        val grant = ShizukuGrantPolicy.grant("preset:openai", 3L, 15)
        assertTrue(ShizukuGrantPolicy.check(grant, "preset:openai", 3L) is GrantCheck.Valid)
    }

    @Test fun storeRoundTripBindsRevision() = runBlocking {
        val store = store()
        assertNull(store.load())
        val grant = ShizukuGrantPolicy.grant("preset:openai", 7L, 15, atMs = 1_700_000_000_000L)
        store.save(grant)
        assertEquals(grant, store.load())
        assertTrue(store.check("preset:openai", 7L) is GrantCheck.Valid)
        // 設定推進即失效（endpoint 切換/重存皆同）。
        assertTrue(store.check("preset:openai", 8L) is GrantCheck.Invalid)
        assertTrue(store.check("custom:other", 7L) is GrantCheck.Invalid)
    }

    @Test fun revokeClearsPersistence() = runBlocking {
        val store = store()
        store.save(ShizukuGrantPolicy.grant("preset:openai", 7L, 15))
        store.clear()
        assertNull(store.load())
        val check = store.check("preset:openai", 7L)
        assertTrue("$check", check is GrantCheck.Invalid)
    }

    @Test fun serviceVersionChangeKeepsValid() {
        // serviceVersion 只留存稽核，不參與有效性判定：版本變更仍 Valid。
        val old = ShizukuGrantPolicy.grant("preset:openai", 3L, 15)
        val newer = ShizukuGrantPolicy.grant("preset:openai", 3L, 99)
        assertTrue(ShizukuGrantPolicy.check(old, "preset:openai", 3L) is GrantCheck.Valid)
        assertTrue(ShizukuGrantPolicy.check(newer, "preset:openai", 3L) is GrantCheck.Valid)
    }

    @Test fun grantedAtMsDoesNotAffectCheck() {
        // grantedAtMs 為任意時間戳，不影響判定。
        val epoch = ShizukuGrantPolicy.grant("preset:openai", 3L, 15, atMs = 0L)
        val future = ShizukuGrantPolicy.grant("preset:openai", 3L, 15, atMs = Long.MAX_VALUE)
        assertTrue(ShizukuGrantPolicy.check(epoch, "preset:openai", 3L) is GrantCheck.Valid)
        assertTrue(ShizukuGrantPolicy.check(future, "preset:openai", 3L) is GrantCheck.Valid)
    }

    @Test fun saveBlankProviderThrows() {
        // policy 層先擋空白 providerId。
        try {
            ShizukuGrantPolicy.grant("  ", 3L, 15)
            fail("blank providerId must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVIDER_ID_INVALID", e.message)
        }
        // store 層亦擋（繞過 policy 直構造仍不得寫入）。
        runBlocking {
            val store = store()
            try {
                store.save(ShizukuGrant("", 3L, 0L, 15))
                fail("blank providerId save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_ID_INVALID", e.message)
            }
            try {
                store.save(ShizukuGrant("provider\u0000", 3L, 0L, 15))
                fail("control char in providerId save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("PROVIDER_ID_INVALID", e.message)
            }
            try {
                store.save(ShizukuGrant("provider", 0L, 0L, 15))
                fail("zero revision save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("CONFIG_REVISION_INVALID", e.message)
            }
            try {
                store.save(ShizukuGrant("provider", 3L, -1L, 15))
                fail("negative atMs save must throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("AT_MS_NEGATIVE", e.message)
            }
        }
    }

    private class FailOnceClearDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var failOnUpdate = false

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failOnUpdate) {
                failOnUpdate = false
                throw IOException("simulated clear failure")
            }
            return delegate.updateData(transform)
        }
    }

    @Test fun pendingRevokeMarkerPreventsResurrectionAndSelfHeals() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "shizuku_marker.preferences_pb") }
        val failOnceDs = FailOnceClearDataStore(backing)
        val markerFile = File(dir, SHIZUKU_PENDING_REVOKE_FILE_NAME)
        val marker = PendingRevokeMarker(markerFile)
        val store = ShizukuGrantStore(failOnceDs, revokeMarker = marker)

        val grant = ShizukuGrantPolicy.grant("preset:openai", 5L, 15)
        store.save(grant)
        assertEquals(grant, store.load())
        assertFalse("Marker should not be pending after successful save", marker.isPending())

        // Simulate clear failure
        failOnceDs.failOnUpdate = true
        try {
            store.clear()
            fail("store.clear should fail")
        } catch (_: IOException) {
            // Expected
        }

        // Marker remains pending on disk
        assertTrue("Marker must remain pending after failed clear", marker.isPending())
        assertTrue(markerFile.exists())

        // Simulate app restart: new Store instance with normal DataStore and the same marker file
        val restartedStore = ShizukuGrantStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        // Load must detect pending marker, self-heal by clearing DataStore, and return null
        assertNull("Pending marker must prevent resurrection on load", restartedStore.load())
        assertFalse("Self-healing must have cleared the pending marker", markerFile.exists())
        assertFalse(marker.isPending())

        // Subsequent load returns null
        assertNull(restartedStore.load())
    }

    @Test fun loadMissingKeysYieldsNull() = runBlocking {
        // 全空 DataStore → null（從未授權）。
        assertNull(store().load())
        // 缺鍵（只寫 providerId）→ null。
        val partial = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "partial_${System.nanoTime()}.preferences_pb")
        }
        partial.edit { it[SHIZUKU_GRANT_PROVIDER_ID] = "preset:openai" }
        assertNull(ShizukuGrantStore(partial).load())
        // 空白 providerId（繞過 save 校驗直寫）→ null。
        val blank = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "blank_${System.nanoTime()}.preferences_pb")
        }
        blank.edit {
            it[SHIZUKU_GRANT_PROVIDER_ID] = "  "
            it[SHIZUKU_GRANT_CONFIG_REVISION] = 3L
            it[SHIZUKU_GRANT_AT_MS] = 0L
            it[SHIZUKU_GRANT_SERVICE_VERSION] = 15
        }
        assertNull(ShizukuGrantStore(blank).load())
    }

    @Test fun cancellationExceptionPropagatesWithoutBeingSwallowed() {
        runBlocking {
            val cancellingDataStore = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw kotlinx.coroutines.CancellationException("test cancel")
                }
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                    throw kotlinx.coroutines.CancellationException("test cancel")
                }
            }
            val store = ShizukuGrantStore(cancellingDataStore)
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { store.load() }
            }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { store.save(ShizukuGrantPolicy.grant("preset:openai", 1L, 15)) }
            }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking { store.clear() }
            }
        }
    }
}
