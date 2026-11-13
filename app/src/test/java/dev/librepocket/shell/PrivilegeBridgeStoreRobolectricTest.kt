package dev.librepocket.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
