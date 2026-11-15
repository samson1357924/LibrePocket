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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

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

    /**
     * 攔截第 N 次（1-based）updateData：在委託 delegate 套用原 transform 之前，
     * 先執行 [interceptHandler]（此處用於注入一次並發 save：寫新 grant 鍵＋清 journal，
     * 模擬 save 落在 clear() 的 journal 寫入（call #2）與 erase edit（call #3）之間）。
     * 計數只對「經本假件」的呼叫生效（handler 直取 backing，不重複計數）。
     */
    private class InterceptOnUpdateDataCallDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var interceptCall: Int = -1
        var interceptHandler: (suspend () -> Unit)? = null
        private var call = 0

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            call += 1
            if (call == interceptCall) {
                interceptHandler?.invoke()
            }
            return delegate.updateData(transform)
        }
    }

    /**
     * 精準失敗第 N 次（1-based）updateData 的假件：
     * save() 消耗 call #1，clear() 的 journal 寫入為 call #2、擦除 edit 為 call #3。
     * failOnCall=2 亦有測試使用（journal 寫入失敗 → best-effort wipe →
     * rethrow 原始例外，見 journalWriteFailure_bestEffortWipesKeys_sameProcessAndRestartDeny）。
     */
    private class FailOnUpdateCallDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var failOnCall: Int? = null
        private var call = 0

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            call += 1
            if (failOnCall == call) {
                throw IOException("simulated update failure at call #$call")
            }
            return delegate.updateData(transform)
        }
    }

    @Test fun journalBlocksResurrection_whenClearFails_afterJournalWritten() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_journal_blocks.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "shizuku_journal_blocks.pending")
        val store = ShizukuGrantStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        val grant = ShizukuGrantPolicy.grant("preset:openai", 5L, 15)
        store.save(grant) // updateData call #1（save 內 journal 清除）
        failingDs.failOnCall = 3 // call #2＝journal 寫入（成功）；call #3＝擦除 edit（失敗）
        try {
            store.clear()
            fail("clear must throw when the grant-removal edit fails after journal is written")
        } catch (_: IOException) {
            // 預期
        }

        // journal 已落盤、grant 鍵仍在 backing（擦除被阻斷）——缺 journal 保護必復活。
        val prefsBefore = backing.data.first()
        assertEquals(true, prefsBefore[SHIZUKU_REVOKE_JOURNAL])
        assertEquals("preset:openai", prefsBefore[SHIZUKU_GRANT_PROVIDER_ID])

        // 模擬重啟：新 Store 實例＋同一 backing 檔；刻意用 in-memory-only marker
        // 排除 marker 分支，單獨證明 journal 自癒的復活防護。
        val restarted = ShizukuGrantStore(backing, revokeMarker = PendingRevokeMarker(null))
        assertNull("journal must block resurrection even without any marker", restarted.load())
        val prefsAfter = backing.data.first()
        assertFalse(prefsAfter.contains(SHIZUKU_REVOKE_JOURNAL))
        assertFalse(prefsAfter.contains(SHIZUKU_GRANT_PROVIDER_ID))
        assertNull(restarted.load())
    }

    @Test fun pendingMarker_parentIsRegularFile_clearFails_restartGrantMustNotResurrect() = runBlocking {
        // marker 父路徑為普通檔案：markPending() 落盤必敗（回 false），journal 是唯一持久保障。
        val regularParent = File(dir, "regular_parent_marker_dir")
        assertTrue(regularParent.createNewFile())
        val markerFile = File(regularParent, "x.pending")

        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_parent_regular_file.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val store = ShizukuGrantStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(ShizukuGrantPolicy.grant("preset:openai", 5L, 15))
        failingDs.failOnCall = 3 // clear() 擦除 edit 丟 IOException；journal（call #2）已成功落盤
        try {
            store.clear()
            fail("clear must throw")
        } catch (_: IOException) {
            // 預期
        }
        assertFalse("marker can never persist under a regular-file parent", markerFile.exists())
        assertEquals(true, backing.data.first()[SHIZUKU_REVOKE_JOURNAL])

        // 重啟：新實例＋同一不可落盤 marker 路徑＋同一 backing → 僅 journal 能擋下復活。
        val restarted = ShizukuGrantStore(
            backing,
            revokeMarker = PendingRevokeMarker(markerFile),
        )
        assertFalse("fresh marker under regular-file parent is not pending", restarted.revokeMarker.isPending())
        assertNull("journal must deny resurrection when the marker cannot persist", restarted.load())
        val prefsAfter = backing.data.first()
        assertFalse(prefsAfter.contains(SHIZUKU_REVOKE_JOURNAL))
        assertFalse(prefsAfter.contains(SHIZUKU_GRANT_PROVIDER_ID))
    }

    @Test fun journalWritten_clearThrows_restartLoadDenied() = runBlocking {
        // 跨「新 Store 實例＋同一 backing DataStore 檔」的復活防護：
        // 重啟實例的 marker 指向同一磁帶 marker 檔（前次 clear 已落盤），
        // marker 分支自癒並同時擦除 journal。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_journal_restart.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "shizuku_journal_restart.pending")
        val store = ShizukuGrantStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(ShizukuGrantPolicy.grant("preset:openai", 5L, 15))
        failingDs.failOnCall = 3
        try {
            store.clear()
            fail("clear must throw")
        } catch (_: IOException) {
            // 預期
        }
        assertEquals(true, backing.data.first()[SHIZUKU_REVOKE_JOURNAL])

        // 新實例＋同一 marker 檔＋同一 backing：load 必 null（marker 也好、journal 也好，絕不復活）。
        val restarted = ShizukuGrantStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        assertNull(restarted.load())
        val prefsAfter = backing.data.first()
        assertFalse("marker-branch self-heal must also remove the journal", prefsAfter.contains(SHIZUKU_REVOKE_JOURNAL))
        assertFalse(prefsAfter.contains(SHIZUKU_GRANT_PROVIDER_ID))
    }

    @Test fun journalWriteFailure_bestEffortWipesKeys_sameProcessAndRestartDeny() = runBlocking {
        // journal 寫入（call #2）即失敗：clear() 盡力擦除授權鍵（best-effort，
        // 不刪 journal——journal 本來就沒寫成），仍 rethrow 原始例外；
        // backing 不含 grant 鍵、journal 未落盤、marker 已落盤。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_journal_write_fail.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "shizuku_journal_write_fail.pending")
        val store = ShizukuGrantStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(ShizukuGrantPolicy.grant("preset:openai", 5L, 15)) // call #1
        failingDs.failOnCall = 2 // call #2 ＝ clear() 的 journal 寫入
        try {
            store.clear()
            fail("clear must throw when the journal write fails")
        } catch (_: IOException) {
            // 預期：原始例外 rethrow（不得遮蔽）
        }

        val prefs = backing.data.first()
        assertFalse("best-effort wipe must remove grant keys", prefs.contains(SHIZUKU_GRANT_PROVIDER_ID))
        assertFalse(prefs.contains(SHIZUKU_GRANT_CONFIG_REVISION))
        assertFalse(prefs.contains(SHIZUKU_GRANT_AT_MS))
        assertFalse(prefs.contains(SHIZUKU_GRANT_SERVICE_VERSION))
        assertFalse("journal must not be persisted (journal write itself failed)", prefs.contains(SHIZUKU_REVOKE_JOURNAL))
        assertTrue("marker must still be persisted (markPending ran before journal edit)", markerFile.isFile)

        // 同行程：記憶體 pending 旗標 → load() == null（marker 分支自癒並刪 marker）。
        assertNull(store.load())
        // 重啟實例：marker 已由上一行 load 的 marker 分支 heal 刪除，journal 與鍵
        // 亦已擦除——重啟半段走 never-granted/鍵已擦路徑 → null（絕不復活）。
        val restarted = ShizukuGrantStore(backing, revokeMarker = PendingRevokeMarker(markerFile))
        assertNull(restarted.load())
        assertFalse("marker must be gone after marker-branch self-heal", markerFile.exists())
    }

    @Test fun saveAfterCrashedClear_survivesStaleJournalHeal() = runBlocking {
        // TOCTOU 回歸：crash 殘留（journal=true＋舊 grant 鍵）經 save() 復原後，
        // load() 首次讀到 stale 快照（journal=true＋舊鍵）時，heal edit 應對
        // 「最新值」做條件式判斷——journal 已不存在 → no-op → 新 grant 存活。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_save_after_crashed_clear.preferences_pb")
        }
        // 1) Seed 崩潰殘留狀態（journal 落盤後、擦除前被殺）。
        backing.edit { prefs ->
            prefs[SHIZUKU_GRANT_PROVIDER_ID] = "preset:openai"
            prefs[SHIZUKU_GRANT_CONFIG_REVISION] = 5L
            prefs[SHIZUKU_GRANT_AT_MS] = 1L
            prefs[SHIZUKU_GRANT_SERVICE_VERSION] = 15
            prefs[SHIZUKU_REVOKE_JOURNAL] = true
        }
        val staleSnapshot = backing.data.first()

        // 2) 假件：首次 data 讀取回傳 stale 快照；updateData 一律走最新 backing。
        val staleFirst = object : DataStore<Preferences> by backing {
            private var reads = 0

            override val data: Flow<Preferences> = flow {
                if (reads == 0) {
                    reads += 1
                    emit(staleSnapshot)
                } else {
                    emitAll(backing.data)
                }
            }
        }
        val store = ShizukuGrantStore(staleFirst, revokeMarker = PendingRevokeMarker(null))

        // 3) 新授權取代撤權 journal（save 成功：寫新 grant＋清 journal）。
        val newGrant = ShizukuGrantPolicy.grant("preset:openai", 6L, 15)
        store.save(newGrant)

        // 4) load()：首次讀 stale（journal=true＋舊鍵）→ 條件式 heal →
        //    backing 最新值 journal 已不在 → no-op → fall-through re-read → 新 grant。
        val loaded = store.load()
        assertEquals("stale-journal heal must not wipe the newly saved grant", newGrant, loaded)

        // 5) backing 內新 grant 鍵存活、journal 已清。
        val prefsAfter = backing.data.first()
        assertEquals("preset:openai", prefsAfter[SHIZUKU_GRANT_PROVIDER_ID])
        assertEquals(6L, prefsAfter[SHIZUKU_GRANT_CONFIG_REVISION])
        assertFalse(prefsAfter.contains(SHIZUKU_REVOKE_JOURNAL))
    }

    @Test fun saveClearsJournal() = runBlocking {
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_save_clears_journal.preferences_pb")
        }
        val failingDs = FailOnUpdateCallDataStore(backing)
        val markerFile = File(dir, "shizuku_save_clears_journal.pending")
        val store = ShizukuGrantStore(failingDs, revokeMarker = PendingRevokeMarker(markerFile))

        store.save(ShizukuGrantPolicy.grant("preset:openai", 5L, 15))
        failingDs.failOnCall = 3
        try {
            store.clear()
            fail("clear must throw")
        } catch (_: IOException) {
            // 預期
        }
        assertEquals(true, backing.data.first()[SHIZUKU_REVOKE_JOURNAL])

        // 重新授權：save 必須清除 journal（新授權取代撤權日誌）。
        val newGrant = ShizukuGrantPolicy.grant("preset:openai", 6L, 15)
        store.save(newGrant)
        val prefs = backing.data.first()
        assertFalse("save must remove the revoke journal", prefs.contains(SHIZUKU_REVOKE_JOURNAL))
        assertEquals(newGrant, store.load())
    }

    @Test fun check_withUnhealedJournalPending_returnInvalid() = runBlocking {
        // journal 殘留且自癒被阻擋（updateData 全失敗）：check() 必 Invalid
        // （fail-closed），絕不可因 stale grant 鍵而 Valid。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "shizuku_check_unhealed_journal.preferences_pb")
        }
        backing.edit { prefs ->
            prefs[SHIZUKU_GRANT_PROVIDER_ID] = "preset:openai"
            prefs[SHIZUKU_GRANT_CONFIG_REVISION] = 7L
            prefs[SHIZUKU_GRANT_AT_MS] = 1L
            prefs[SHIZUKU_GRANT_SERVICE_VERSION] = 15
            prefs[SHIZUKU_REVOKE_JOURNAL] = true
        }
        val failing = object : DataStore<Preferences> by backing {
            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = throw IOException("heal blocked")
        }
        // in-memory-only marker：排除 marker 分支，單獨驗證 journal 路徑 fail-closed。
        val store = ShizukuGrantStore(failing, revokeMarker = PendingRevokeMarker(null))
        val checked = store.check("preset:openai", 7L)
        assertTrue("$checked", checked is GrantCheck.Invalid)
        assertEquals(
            ShizukuGrantPolicy.REASON_NEVER_GRANTED,
            (checked as GrantCheck.Invalid).reason,
        )
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
        assertFalse("restarted instance must clear its own marker state", restartedStore.revokeMarker.isPending())
        // #136-B1 P1（isPending OR 語義變更）：原 Store 實例的記憶體 pending 旗標不受
        // 他實例自癒重置（fail-closed）——原 marker 仍視為 pending。
        assertTrue("original in-memory pending flag must survive another instance's self-heal", marker.isPending())

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

    @Test fun saveLandsBetweenJournalAndErase_clearKeepsFreshGrant() = runBlocking {
        // clear() 的 erase edit 是條件式（journal==true 才擦）；並發 save 落在
        // journal 寫入與 erase edit 之間時，save 已寫新 grant 鍵並清 journal，
        // erase transform 必須 no-op，新 grant 存活、journal 不在盤上。
        val backing = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "save_between_journal_and_erase.preferences_pb")
        }
        val interceptor = InterceptOnUpdateDataCallDataStore(backing)
        val markerFile = File(dir, "save_between_journal_and_erase.pending")
        val store = ShizukuGrantStore(interceptor, revokeMarker = PendingRevokeMarker(markerFile))

        // 既有授權（updateData call #1）。
        store.save(ShizukuGrantPolicy.grant("provider-old", 1L, 15))

        // erase edit＝call #3（call #2＝journal 寫入）；套用前注入一次 save。
        val freshGrant = ShizukuGrantPolicy.grant("provider-new", 2L, 16)
        interceptor.interceptCall = 3
        interceptor.interceptHandler = {
            backing.edit { prefs ->
                prefs[SHIZUKU_GRANT_PROVIDER_ID] = freshGrant.providerId
                prefs[SHIZUKU_GRANT_CONFIG_REVISION] = freshGrant.configRevision
                prefs[SHIZUKU_GRANT_AT_MS] = freshGrant.grantedAtMs
                prefs[SHIZUKU_GRANT_SERVICE_VERSION] = freshGrant.serviceVersion
                // 新授權取代撤權日誌（同 save() 語義）。
                prefs.remove(SHIZUKU_REVOKE_JOURNAL)
            }
        }

        store.clear()

        val prefs = backing.data.first()
        assertEquals(
            "並發 save 落在 journal 與 erase 之間：新 grant 必須存活",
            "provider-new",
            prefs[SHIZUKU_GRANT_PROVIDER_ID],
        )
        assertEquals(2L, prefs[SHIZUKU_GRANT_CONFIG_REVISION])
        assertNull("新授權 save 已清 journal：journal 必須不在盤上", prefs[SHIZUKU_REVOKE_JOURNAL])
        assertEquals("fresh grant 必須可解析", freshGrant, store.load())
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
