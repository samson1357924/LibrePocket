package dev.librepocket.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 確認競態防線測試（純 JVM，不釘原始碼字串）。
 *
 * 覆蓋 ChatScreen 確認接線的整合語義（capture 世代＋文字 → suspend ack →
 * 恢復後世代／身分比對，見 VoiceTtsConfirmState.shouldSpeakAfterConfirm /
 * shouldClearPendingAfterConfirm）：
 *
 * - 世代不變 → 可朗讀；世代不同（await 期間輸出開關翻轉）→ 丟棄；
 * - 待朗讀只在身分相符時清除；
 * - suspend ack 中途關開關 → 即使寫入成功仍丟棄（不 speak、不清錯），
 *   且關→開後的新 pending 不被舊協程誤清。
 */
class VoiceTtsConfirmGenerationTest {

    @Test
    fun generationUnchanged_allowsSpeak() {
        val state = VoiceTtsConfirmState()
        assertTrue(state.shouldSpeakAfterConfirm(0L, 0L))
        assertTrue(state.shouldSpeakAfterConfirm(2L, 2L))
    }

    @Test
    fun generationChanged_discardsSpeak() {
        val state = VoiceTtsConfirmState()
        assertFalse(state.shouldSpeakAfterConfirm(0L, 1L))
        assertFalse(state.shouldSpeakAfterConfirm(1L, 2L))
    }

    @Test
    fun pendingClear_onlyOnIdentityMatch() {
        val state = VoiceTtsConfirmState()
        assertTrue(state.shouldClearPendingAfterConfirm("a", "a"))
        assertFalse(state.shouldClearPendingAfterConfirm("a", "b"))
        assertFalse(state.shouldClearPendingAfterConfirm("a", null))
    }

    @Test
    fun ackInFlight_outputToggled_successStillDiscarded() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        // 接線側狀態：世代計數＋待朗讀（仿 ChatScreen 的 outputGeneration／pendingSpeakText）。
        var generation = 0L
        var pending: String? = "hello"
        var spoke = false
        var clearedError = false
        val capturedGeneration = generation
        val capturedText = pending
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // 中途關開關：仿 LaunchedEffect(outputEnabled)：世代 bump、清 pending。
        generation++
        pending = null
        gate.complete(true)
        assertEquals(TtsConfirmDecision.SPEAK, ack.await())
        // 恢復後世代不符 → 丟棄（不 speak、不清錯）；pending 已由開關效應清空，接線不另碰。
        if (state.shouldSpeakAfterConfirm(capturedGeneration, generation)) {
            clearedError = true
            spoke = true
            if (state.shouldClearPendingAfterConfirm(capturedText, pending)) pending = null
        }
        assertFalse(spoke)
        assertFalse(clearedError)
        assertNull(pending)
    }

    @Test
    fun ackInFlight_toggleThenNewPending_newPendingPreserved() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        var generation = 0L
        var pending: String? = "hello"
        var spoke = false
        val capturedGeneration = generation
        val capturedText = pending
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // 中途關→開（各 bump 一次），開後用戶對新訊息按下朗讀（新 pending）。
        generation++
        pending = null
        generation++
        pending = "world"
        gate.complete(true)
        ack.await()
        if (state.shouldSpeakAfterConfirm(capturedGeneration, generation)) {
            spoke = true
            if (state.shouldClearPendingAfterConfirm(capturedText, pending)) pending = null
        }
        assertFalse(spoke)
        assertEquals("world", pending)
    }

    @Test
    fun ackInFlight_noToggle_speaksAndClearsIdentityMatch() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        var generation = 0L
        var pending: String? = "hello"
        var spoke = false
        var clearedError = false
        val capturedGeneration = generation
        val capturedText = pending
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        gate.complete(true)
        assertEquals(TtsConfirmDecision.SPEAK, ack.await())
        if (state.shouldSpeakAfterConfirm(capturedGeneration, generation)) {
            clearedError = true
            spoke = true
            if (state.shouldClearPendingAfterConfirm(capturedText, pending)) pending = null
        }
        assertTrue(spoke)
        assertTrue(clearedError)
        assertNull(pending)
    }
}
