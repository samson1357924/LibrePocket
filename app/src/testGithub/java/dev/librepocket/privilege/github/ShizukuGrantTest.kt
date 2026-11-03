package dev.librepocket.privilege.github

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
        val check = ShizukuGrantPolicy.check(
            ShizukuGrant("", 3L, 0L, 15),
            "preset:openai",
            3L,
        )
        assertTrue("$check", check is GrantCheck.Invalid)
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
}
