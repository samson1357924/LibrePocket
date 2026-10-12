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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
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
}
