package dev.librepocket.shell

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric 測試（#136-B1 Phase 2）：
 * 驗證真實 `Context`、`noBackupFilesDir` 撤銷標記檔與自我修復（self-healing）生命週期。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivilegeBridgeStoreRobolectricTest {

    private lateinit var context: Context
    private val testScopes = mutableListOf<CoroutineScope>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val markerFile = File(context.noBackupFilesDir, PRIVILEGE_PENDING_REVOKE_FILE_NAME)
        if (markerFile.exists()) markerFile.delete()
    }

    @After fun tearDown() {
        val markerFile = File(context.noBackupFilesDir, PRIVILEGE_PENDING_REVOKE_FILE_NAME)
        if (markerFile.exists()) markerFile.delete()
        val corruptedFile = File(context.filesDir, "corrupted_test.preferences_pb")
        if (corruptedFile.exists()) corruptedFile.delete()
        testScopes.forEach { it.cancel() }
        testScopes.clear()
    }

    @Test fun contextConstructorUsesNoBackupFilesDirForPendingRevokeMarker() {
        val store = PrivilegeBridgeStore(context)
        val expectedMarkerFile = File(context.noBackupFilesDir, PRIVILEGE_PENDING_REVOKE_FILE_NAME)
        assertEquals(expectedMarkerFile.absolutePath, store.revokeMarker.file?.absolutePath)
        assertFalse(store.revokeMarker.isPending())
    }

    @Test fun realContextRoundTripAndClear() = runBlocking {
        val store = PrivilegeBridgeStore(context)
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 1L)

        store.save(grant)
        assertEquals(grant, store.load())
        assertFalse(store.revokeMarker.isPending())

        store.clear()
        assertNull(store.load())
        assertFalse(store.revokeMarker.isPending())
    }

    @Test fun pendingRevokeMarkerSelfHealsAcrossRestarts() = runBlocking {
        val store = PrivilegeBridgeStore(context)
        val grant = PrivilegeGrantPolicy.grant("preset:openai", 2L)
        store.save(grant)
        assertEquals(grant, store.load())

        // 模擬撤銷過程中進程被殺：殘留標記檔於磁碟
        val markerFile = File(context.noBackupFilesDir, PRIVILEGE_PENDING_REVOKE_FILE_NAME)
        markerFile.createNewFile()
        assertTrue(markerFile.exists())

        // 模擬重啟：以相同 context 重新構造 Store
        val restartStore = PrivilegeBridgeStore(context)
        assertTrue(restartStore.revokeMarker.isPending())

        // load() 察覺 pending marker，自我修復清庫並刪除標記檔，回傳 null（防死灰復燃）
        val loaded = restartStore.load()
        assertNull("Pending marker must return null on load", loaded)
        assertFalse("Self-healing must delete the pending marker file", markerFile.exists())
        assertFalse(restartStore.revokeMarker.isPending())

        // 後續 load() 仍為 null
        assertNull(restartStore.load())
    }

    @Test fun selfHealEditFailure_loadReturnsNullAndMarkerAndJournalRemain() = runBlocking {
        // self-heal edit 也失敗時：load() 回 null；pending marker 與 journal 必須殘留（下次啟動重試自癒）。
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { testScopes += it }
        val prefsFile = File(context.noBackupFilesDir, "self_heal_fail.preferences_pb")
        val markerFile = File(context.noBackupFilesDir, "self_heal_fail.pending")
        try {
            val backing = PreferenceDataStoreFactory.create(scope = scope) { prefsFile }
            backing.edit { prefs ->
                prefs[PRIVILEGE_GRANT_PROVIDER_ID] = "preset:openai"
                prefs[PRIVILEGE_GRANT_CONFIG_REVISION] = 5L
                prefs[PRIVILEGE_GRANT_AT_MS] = 1L
                prefs[PRIVILEGE_REVOKE_JOURNAL] = true
            }
            // 所有寫入皆失敗的 DataStore 假件：self-heal 的 edit 必敗。
            val failing = object : DataStore<Preferences> by backing {
                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences = throw IOException("self-heal blocked")
            }
            assertTrue(markerFile.createNewFile())

            val store = PrivilegeBridgeStore(failing, revokeMarker = PendingRevokeMarker(markerFile))
            assertNull("self-heal failure must fail-closed to null", store.load())
            assertTrue("marker must remain pending when the self-heal edit fails", markerFile.exists())
            val prefsAfter = backing.data.first()
            assertEquals(true, prefsAfter[PRIVILEGE_REVOKE_JOURNAL])
            assertEquals("preset:openai", prefsAfter[PRIVILEGE_GRANT_PROVIDER_ID])
        } finally {
            prefsFile.delete()
            markerFile.delete()
        }
    }

    @Test fun journalSelfHealEditFailure_loadReturnsNullAndJournalRemains() = runBlocking {
        // 無 marker、僅 journal 殘留：journal 自癒 edit 失敗 → null、journal 保留（下次啟動重試自癒）。
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { testScopes += it }
        val prefsFile = File(context.noBackupFilesDir, "journal_self_heal_fail.preferences_pb")
        try {
            val backing = PreferenceDataStoreFactory.create(scope = scope) { prefsFile }
            backing.edit { prefs ->
                prefs[PRIVILEGE_GRANT_PROVIDER_ID] = "preset:openai"
                prefs[PRIVILEGE_GRANT_CONFIG_REVISION] = 5L
                prefs[PRIVILEGE_GRANT_AT_MS] = 1L
                prefs[PRIVILEGE_REVOKE_JOURNAL] = true
            }
            val failing = object : DataStore<Preferences> by backing {
                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences = throw IOException("journal self-heal blocked")
            }

            // in-memory-only marker：isPending()==false，journal 分支必須獨立擋下復活。
            val store = PrivilegeBridgeStore(failing, revokeMarker = PendingRevokeMarker(null))
            assertNull("journal self-heal failure must fail-closed to null", store.load())
            val prefsAfter = backing.data.first()
            assertEquals(true, prefsAfter[PRIVILEGE_REVOKE_JOURNAL])
            assertEquals("preset:openai", prefsAfter[PRIVILEGE_GRANT_PROVIDER_ID])
        } finally {
            prefsFile.delete()
        }
    }

    @Test fun corruptedPreferencesSelfHealsViaCorruptionHandler() = runBlocking {
        val corruptedFile = File(context.filesDir, "corrupted_test.preferences_pb")
        corruptedFile.parentFile?.mkdirs()
        corruptedFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0xFE.toByte()))

        val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { testScopes += it }
        val ds = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
            scope = testScope,
        ) { corruptedFile }

        val store = PrivilegeBridgeStore(ds)
        val result = store.load()
        assertNull("Corrupted preferences must fail-closed to null via corruption handler", result)
    }
}
