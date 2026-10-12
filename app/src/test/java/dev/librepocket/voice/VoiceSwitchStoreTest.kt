package dev.librepocket.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 語音開關真相源行為測試（#3，純 JVM，不釘原始碼字串）。
 *
 * - 缺鍵即預設開（與 [VoiceTools]／`ToolRegistry` 投影預設同源）；
 * - 寫入持久化（同檔新實例可見；測試間以獨立暫存目錄隔離）；
 * - 同一快照 map 同時驅動 UI 門禁判定與 `ToolRegistry.projectAll`
 *   （UI 開關與 tool 投影讀同一組值）；
 * - R4-2 三態快照（[VoiceSwitchSnapshot]）：首發射為 loading（門禁關閉，
 *   不經預設開）；讀取拋 IOException 即 loadFailed（門禁關閉＋投影拒絕，
 *   其餘例外照拋）；無 key 新戶維持預設開；
 * - 朗讀首次揭露確認狀態預設未確認、確認後持久化（只問一次）。
 */
class VoiceSwitchStoreTest {

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        tmpDirs.forEach { it.deleteRecursively() }
    }

    private fun newStore(dir: java.io.File = Files.createTempDirectory("voice-test").toFile().also {
        tmpDirs.add(it)
    }): VoiceSwitchStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { java.io.File(dir, "voice.preferences_pb") },
        )
        return VoiceSwitchStore(dataStore)
    }

    private fun throwingStore(): VoiceSwitchStore {
        // 讀取即拋 IOException 的假 store（DataStore.data 讀錯契約），
        // 走同一注入建構子（PreferenceDataStoreFactory 注入模式）。
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("injected voice read failure") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IOException("injected voice read failure")
            }
        }
        return VoiceSwitchStore(broken)
    }

    @Test
    fun nonIoException_isRethrownNotFailClosed() = runBlocking {
        // 非 IOException（程式錯誤／取消語義）照拋，不靜默成 fail-closed 快照。
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("injected non-io failure") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IllegalStateException("injected non-io failure")
            }
        }
        try {
            VoiceSwitchStore(broken).observeSwitches().first { it.loaded }
            fail("expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("injected non-io failure"))
        }
    }

    @Test
    fun freshStore_defaultsBothOn() = runBlocking {
        // 無 key 新戶：載入完成後維持預設開（行為不變）。
        val snapshot = newStore().observeSwitches().first { it.loaded }
        assertFalse(snapshot.loadFailed)
        assertEquals(
            mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to true,
            ),
            snapshot.switches,
        )
        assertTrue(VoiceTools.inputEnabled(snapshot))
        assertTrue(VoiceTools.outputEnabled(snapshot))
    }

    @Test
    fun switchOff_persistsAcrossStoreInstances() = runBlocking {
        val dir = Files.createTempDirectory("voice-test").toFile().also { tmpDirs.add(it) }
        val first = newStore(dir)
        first.setInputEnabled(false)
        first.setOutputEnabled(false)
        // 同檔不允許雙 active DataStore：先停掉第一個 scope 並等其完全結束
        // （guard 註銷含在 completion 內），再建第二實例讀檔。
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[Job]?.join() }

        val second = newStore(dir)
        assertEquals(
            mapOf(
                VoiceTools.SWITCH_INPUT to false,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
            second.observeSwitches().first { it.loaded }.switches,
        )
    }

    @Test
    fun switchFlip_roundTrips() = runBlocking {
        val store = newStore()
        store.setInputEnabled(false)
        assertFalse(VoiceTools.inputEnabled(store.observeSwitches().first { it.loaded }))
        store.setInputEnabled(true)
        assertTrue(VoiceTools.inputEnabled(store.observeSwitches().first { it.loaded }))
        store.setOutputEnabled(false)
        assertFalse(VoiceTools.outputEnabled(store.observeSwitches().first { it.loaded }))
    }

    @Test
    fun gateHelpers_missingKeyFallsBackToDefault() {
        assertTrue(VoiceTools.inputEnabled(emptyMap()))
        assertTrue(VoiceTools.outputEnabled(emptyMap()))
        assertFalse(VoiceTools.inputEnabled(mapOf(VoiceTools.SWITCH_INPUT to false)))
        assertFalse(VoiceTools.outputEnabled(mapOf(VoiceTools.SWITCH_OUTPUT to false)))
    }

    @Test
    fun sameSnapshot_drivesUiGateAndToolProjectionTogether() = runBlocking {
        val store = newStore()
        store.setOutputEnabled(false)
        val snapshot = store.observeSwitches().first { it.loaded }

        // UI 門禁：輸出關閉即隱藏朗讀入口。
        assertFalse(VoiceTools.outputEnabled(snapshot))
        assertTrue(VoiceTools.inputEnabled(snapshot))
        // Tool 投影讀同一組值：voice.speak 不可用＋USER_DISABLED，transcribe 維持可見。
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = VoiceTools.projectionSwitches(snapshot),
        )
        val projected = ToolRegistry.projectAll(ctx)
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.getValue(VoiceTools.SPEAK_NAME).level)
        assertEquals(DenyReason.USER_DISABLED, projected.getValue(VoiceTools.SPEAK_NAME).reason)
        assertEquals(CapabilityLevel.NATIVE, projected.getValue(VoiceTools.TRANSCRIBE_NAME).level)
    }

    @Test
    fun inputOff_projectsTranscribeUnavailable() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(VoiceTools.SWITCH_INPUT to false),
        )
        val projected = ToolRegistry.projectAll(ctx)[VoiceTools.TRANSCRIBE_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
    }

    @Test
    fun readFailure_emitsLoadFailedAndGatesClosed() = runBlocking {
        // (a) 讀取拋 IOException → loadFailed 快照、門禁關閉、投影拒絕。
        val snapshot = throwingStore().observeSwitches().first { it.loaded }
        assertTrue(snapshot.loadFailed)
        assertFalse(VoiceTools.inputEnabled(snapshot))
        assertFalse(VoiceTools.outputEnabled(snapshot))
        val projected = ToolRegistry.projectAll(
            ProjectionContext(
                flavor = Flavor.PLAY,
                userSwitches = VoiceTools.projectionSwitches(snapshot),
            ),
        )
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.getValue(VoiceTools.TRANSCRIBE_NAME).level)
        assertEquals(DenyReason.USER_DISABLED, projected.getValue(VoiceTools.TRANSCRIBE_NAME).reason)
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.getValue(VoiceTools.SPEAK_NAME).level)
        assertEquals(DenyReason.USER_DISABLED, projected.getValue(VoiceTools.SPEAK_NAME).reason)
    }

    @Test
    fun coldStart_disabledStaysClosedFromFirstFrame() = runBlocking {
        // (b) 冷啟動已關閉戶：首發射（loading）即關閉，不經預設開；載入後仍關閉。
        val dir = Files.createTempDirectory("voice-test").toFile().also { tmpDirs.add(it) }
        newStore(dir).also {
            it.setInputEnabled(false)
            it.setOutputEnabled(false)
        }
        // 同檔不允許雙 active DataStore：先停掉寫入方 scope 並等其完全結束。
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[Job]?.join() }

        val reader = newStore(dir)
        val first = reader.observeSwitches().first()
        assertFalse(first.loaded)
        assertFalse(first.loadFailed)
        assertFalse(VoiceTools.inputEnabled(first))
        assertFalse(VoiceTools.outputEnabled(first))
        // 載入完成後仍為關閉（真相源），非失敗。
        val loaded = reader.observeSwitches().first { it.loaded }
        assertFalse(loaded.loadFailed)
        assertEquals(
            mapOf(
                VoiceTools.SWITCH_INPUT to false,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
            loaded.switches,
        )
    }

    @Test
    fun projection_loadFailedTreatsSwitchesAsOff() {
        // (d) 即使快照攜帶開值，loadFailed 仍視 requiresSwitch 為關
        //（防 UI 隱藏但 tool 仍可用的分叉）。
        val snapshot = VoiceSwitchSnapshot(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to true,
            ),
            loaded = true,
            loadFailed = true,
        )
        val projected = ToolRegistry.projectAll(
            ProjectionContext(
                flavor = Flavor.PLAY,
                userSwitches = VoiceTools.projectionSwitches(snapshot),
            ),
        )
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.getValue(VoiceTools.TRANSCRIBE_NAME).level)
        assertEquals(DenyReason.USER_DISABLED, projected.getValue(VoiceTools.TRANSCRIBE_NAME).reason)
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.getValue(VoiceTools.SPEAK_NAME).level)
        assertEquals(DenyReason.USER_DISABLED, projected.getValue(VoiceTools.SPEAK_NAME).reason)
    }

    @Test
    fun projection_loadingKeepsExistingDefaults() {
        // loading 態沿用既有預設（缺鍵即開；tool 側無 UI 等待語義，且語音工具
        // 現階段 executionReady=false 不進模型名單）。
        val projected = ToolRegistry.projectAll(
            ProjectionContext(
                flavor = Flavor.PLAY,
                userSwitches = VoiceTools.projectionSwitches(VoiceSwitchSnapshot.LOADING),
            ),
        )
        assertEquals(CapabilityLevel.NATIVE, projected.getValue(VoiceTools.TRANSCRIBE_NAME).level)
        assertEquals(CapabilityLevel.NATIVE, projected.getValue(VoiceTools.SPEAK_NAME).level)
    }

    @Test
    fun ttsAck_readFailureIo_defaultsUnacknowledged() = runBlocking {
        // ack 鏡像 observeSwitches：IOException 降級為預設未確認，不拋。
        assertFalse(throwingStore().observeTtsAcknowledged().first())
    }

    @Test
    fun ttsAck_nonIoException_isRethrown() = runBlocking {
        // ack 鏡像 observeSwitches：非 IOException 照拋，不靜默成預設值。
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("injected ack non-io failure") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                throw IllegalStateException("injected ack non-io failure")
            }
        }
        try {
            VoiceSwitchStore(broken).observeTtsAcknowledged().first()
            fail("expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("injected ack non-io failure"))
        }
    }

    @Test
    fun disclosureAck_defaultsFalseAndPersists() = runBlocking {
        val dir = Files.createTempDirectory("voice-test").toFile().also { tmpDirs.add(it) }
        val first = newStore(dir)
        assertFalse(first.observeTtsAcknowledged().first())

        first.acknowledgeTtsDisclosure()
        assertTrue(first.observeTtsAcknowledged().first())
        // 同檔不允許雙 active DataStore：先停掉第一個 scope 並等其完全結束，
        // 只問一次經由檔案 durable。
        scopes.forEach { it.cancel() }
        scopes.forEach { it.coroutineContext[Job]?.join() }
        assertTrue(newStore(dir).observeTtsAcknowledged().first())
    }

    @Test
    fun sharedSwitches_resubscribeSeesCachedLoadedNotLoading() = runBlocking {
        // R5-1 回歸（helper 級，測共享包裝語義；UI 層 remember 身分由 MainScreen 側保證）：
        // MainScreen 曾在組合內聯調 observeSwitches() 建新 Flow，collectAsStateWithLifecycle
        // 以 Flow 為 key，重組即重收，onStart 每次先發 LOADING → 已載入設定閃斷。
        // 修復＝ remember + stateIn(WhileSubscribed(5000)) 快取 loaded 值；Store 側
        // onStart LOADING 保留給真正 cold start（生產語義不動）。
        val store = newStore()
        val sharingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scopes.add(sharingScope)
        val shared = store.observeSwitches().stateIn(
            sharingScope,
            SharingStarted.WhileSubscribed(5000),
            VoiceSwitchSnapshot.LOADING,
        )
        // 暖到 loaded（此 first 即第一個 collector，返回後即取消收集）。
        val loaded = shared.first { it.loaded }
        assertFalse(loaded.loadFailed)
        // 第二個 collector 立即重收：應直見快取 loaded，而非 LOADING。
        val second = shared.first()
        assertTrue(second.loaded)
        assertEquals(loaded, second)
        // 對照：裸 Flow 每次訂閱 onStart 仍先發 LOADING（鎖定修復的因果）。
        assertEquals(VoiceSwitchSnapshot.LOADING, store.observeSwitches().first())
    }

    @Test
    fun decideEffective_unknownDoesNotOverwriteKnown() {
        // R6-1 純函數：未知不覆蓋已知，明確失敗照常穿透。
        val loaded = VoiceSwitchSnapshot(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
            loaded = true,
            loadFailed = false,
        )
        val failed = VoiceSwitchSnapshot(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to false,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
            loaded = true,
            loadFailed = true,
        )
        // 重訂閱空窗 LOADING 不覆蓋已知 loaded。
        assertEquals(loaded, VoiceSwitchSnapshot.decideEffective(loaded, VoiceSwitchSnapshot.LOADING))
        // 冷啟動（previous 未 loaded）照常採用 incoming，保持 fail-closed 初值語義。
        assertEquals(
            VoiceSwitchSnapshot.LOADING,
            VoiceSwitchSnapshot.decideEffective(VoiceSwitchSnapshot.LOADING, VoiceSwitchSnapshot.LOADING),
        )
        assertEquals(
            loaded,
            VoiceSwitchSnapshot.decideEffective(VoiceSwitchSnapshot.LOADING, loaded),
        )
        // 明確失敗必須穿透（覆蓋已知 loaded，不可黏住舊值）。
        assertEquals(failed, VoiceSwitchSnapshot.decideEffective(loaded, failed))
        // 已知失敗上的 LOADING 空窗：兩者皆 fail-closed，維持 previous 無閃斷差別。
        assertEquals(failed, VoiceSwitchSnapshot.decideEffective(failed, VoiceSwitchSnapshot.LOADING))
        // loaded→loaded（不同開關值）必須採用新值，不可卡死在舊值。
        val loadedOff = loaded.copy(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to false,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
        )
        assertEquals(loadedOff, VoiceSwitchSnapshot.decideEffective(loaded, loadedOff))
    }

    @Test
    fun decideEffective_foldedOverGapSequenceNeverFlashes() {
        // R6-1 序列級：loaded→LOADING（空窗）→loadFailed 的 fold，
        // 中間態維持 loaded（門禁不閃斷），終態為明確失敗。
        val loaded = VoiceSwitchSnapshot(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to true,
            ),
            loaded = true,
            loadFailed = false,
        )
        val failed = VoiceSwitchSnapshot(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to false,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
            loaded = true,
            loadFailed = true,
        )
        val mid = listOf(loaded, VoiceSwitchSnapshot.LOADING)
            .fold(VoiceSwitchSnapshot.LOADING, VoiceSwitchSnapshot::decideEffective)
        assertEquals(loaded, mid)
        val final = (mid.let { listOf(it, failed) })
            .fold(mid, VoiceSwitchSnapshot::decideEffective)
        assertEquals(failed, final)
    }

    @Test
    fun resubscribeAfterTimeout_decideEffectiveKeepsLoadedThroughLoadingGap() = runTest {
        // R6-1 回歸（JVM 純 Flow）：stateIn 暖到 loaded → advanceTimeBy 超過
        // WhileSubscribed(5000) 超時 → 取消並重訂閱，第二次上游發射延遲
        // （既有測試只覆蓋立即重訂閱，此處覆蓋慢路徑空窗）。
        val loaded = VoiceSwitchSnapshot(
            switches = mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to true,
            ),
            loaded = true,
            loadFailed = false,
        )
        var subscriptions = 0
        val upstream = flow {
            val n = ++subscriptions
            // 仿 Store onStart：每次新訂閱先發 LOADING。
            emit(VoiceSwitchSnapshot.LOADING)
            if (n == 1) {
                emit(loaded)
            } else {
                // 第二次 DataStore 發射延遲：重訂閱空窗拉長。
                delay(10_000)
                emit(loaded)
            }
        }
        val shared = upstream.stateIn(
            // 不用 backgroundScope（experimental 且實測不由 advanceUntilIdle 驅動）：
            // 以測試 dispatcher 自建 sharing scope，同 scheduler 可虛擬時間推進。
            CoroutineScope(
                coroutineContext[kotlin.coroutines.ContinuationInterceptor]
                    as kotlinx.coroutines.CoroutineDispatcher + SupervisorJob(),
            ).also { scopes.add(it) },
            SharingStarted.WhileSubscribed(5000),
            VoiceSwitchSnapshot.LOADING,
        )
        // 暖到 loaded（第一個 collector，返回後即取消收集）。
        val warmed = ArrayList<VoiceSwitchSnapshot>()
        val firstJob = launch { shared.collect { warmed.add(it) } }
        advanceUntilIdle()
        assertTrue(warmed.last().loaded)
        firstJob.cancel()
        firstJob.join()
        // 超過 WhileSubscribed 超時，上游停止。
        advanceTimeBy(5001)
        runCurrent()
        // 重訂閱：空窗內必先再見 LOADING（鎖定 loaded→LOADING 空窗形狀）。
        val gapSeen = ArrayList<VoiceSwitchSnapshot>()
        val gapJob = launch { shared.collect { gapSeen.add(it) } }
        runCurrent()
        assertTrue(
            "expected LOADING gap after resubscribe, saw $gapSeen",
            gapSeen.contains(VoiceSwitchSnapshot.LOADING),
        )
        // decideEffective 折疊空窗全程維持 loaded（門禁不閃斷）。
        var effective = warmed.last()
        for (snapshot in gapSeen) {
            effective = VoiceSwitchSnapshot.decideEffective(effective, snapshot)
            assertTrue("effective flashed to $effective on $snapshot", effective.loaded)
            assertFalse(effective.loadFailed)
        }
        assertEquals(loaded, effective)
        assertTrue(VoiceTools.inputEnabled(effective))
        // 空窗結束（上游 loaded 到達）照常採用新值。
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(loaded, gapSeen.last())
        assertEquals(
            loaded,
            VoiceSwitchSnapshot.decideEffective(effective, gapSeen.last()),
        )
        gapJob.cancel()
        gapJob.join()
    }
}
