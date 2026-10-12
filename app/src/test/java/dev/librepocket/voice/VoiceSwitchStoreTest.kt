package dev.librepocket.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 語音開關真相源行為測試（#3，純 JVM，不釘原始碼字串）。
 *
 * - 缺鍵即預設開（與 [VoiceTools]／`ToolRegistry` 投影預設同源）；
 * - 寫入持久化（同檔新實例可見；測試間以獨立暫存目錄隔離）；
 * - 同一快照 map 同時驅動 UI 門禁判定與 `ToolRegistry.projectAll`
 *   （UI 開關與 tool 投影讀同一組值）；
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

    @Test
    fun freshStore_defaultsBothOn() = runBlocking {
        val snapshot = newStore().observeSwitches().first()
        assertEquals(
            mapOf(
                VoiceTools.SWITCH_INPUT to true,
                VoiceTools.SWITCH_OUTPUT to true,
            ),
            snapshot,
        )
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
            second.observeSwitches().first(),
        )
    }

    @Test
    fun switchFlip_roundTrips() = runBlocking {
        val store = newStore()
        store.setInputEnabled(false)
        assertFalse(VoiceTools.inputEnabled(store.observeSwitches().first()))
        store.setInputEnabled(true)
        assertTrue(VoiceTools.inputEnabled(store.observeSwitches().first()))
        store.setOutputEnabled(false)
        assertFalse(VoiceTools.outputEnabled(store.observeSwitches().first()))
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
        val snapshot = store.observeSwitches().first()

        // UI 門禁：輸出關閉即隱藏朗讀入口。
        assertFalse(VoiceTools.outputEnabled(snapshot))
        assertTrue(VoiceTools.inputEnabled(snapshot))
        // Tool 投影讀同一組值：voice.speak 不可用＋USER_DISABLED，transcribe 維持可見。
        val ctx = ProjectionContext(flavor = Flavor.PLAY, userSwitches = snapshot)
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
}
