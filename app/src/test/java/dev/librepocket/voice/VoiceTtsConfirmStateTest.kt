package dev.librepocket.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 朗讀確認編排測試（#2，純 JVM，不釘原始碼字串）。
 *
 * - 寫入成功 → SPEAK（本次朗讀，下次不問）；
 * - 寫入失敗（false／拋錯）→ SPEAK_WITH_WARNING（本次仍朗讀，
 *   ackWriteFailed 置 true 供內聯警告顯示，保持未確認）；
 * - 寫入中的第二次 confirm → SUPPRESSED（連打防護）；
 * - 前一次完成後可再次 confirm（成功會清掉舊警告）。
 */
class VoiceTtsConfirmStateTest {

    @Test
    fun initial_idleAndNoWarning() {
        val state = VoiceTtsConfirmState()
        assertFalse(state.confirming)
        assertFalse(state.ackWriteFailed)
    }

    @Test
    fun confirm_success_returnsSpeak() = runBlocking {
        val state = VoiceTtsConfirmState()
        var calls = 0
        val decision = state.confirm { calls++; true }
        assertEquals(TtsConfirmDecision.SPEAK, decision)
        assertEquals(1, calls)
        assertFalse(state.confirming)
        assertFalse(state.ackWriteFailed)
    }

    @Test
    fun confirm_writeFalse_returnsSpeakWithWarning() = runBlocking {
        val state = VoiceTtsConfirmState()
        val decision = state.confirm { false }
        assertEquals(TtsConfirmDecision.SPEAK_WITH_WARNING, decision)
        assertFalse(state.confirming)
        assertTrue(state.ackWriteFailed)
    }

    @Test
    fun confirm_writeThrows_returnsSpeakWithWarning() = runBlocking {
        val state = VoiceTtsConfirmState()
        val decision = state.confirm { throw java.io.IOException("datastore down") }
        assertEquals(TtsConfirmDecision.SPEAK_WITH_WARNING, decision)
        assertFalse(state.confirming)
        assertTrue(state.ackWriteFailed)
    }

    @Test
    fun confirm_inFlight_secondIsSuppressedAndAckRunsOnce() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        var calls = 0
        val first = async {
            state.confirm {
                calls++
                gate.await()
            }
        }
        // 等第一個 confirm 進入寫入（confirming=true），再送第二個。
        while (!state.confirming) kotlinx.coroutines.yield()
        assertTrue(state.confirming)
        assertEquals(TtsConfirmDecision.SUPPRESSED, state.confirm { true })
        assertEquals(1, calls)
        gate.complete(true)
        assertEquals(TtsConfirmDecision.SPEAK, first.await())
        assertFalse(state.confirming)
        assertFalse(state.ackWriteFailed)
    }

    @Test
    fun confirm_afterFailure_successClearsWarning() = runBlocking {
        val state = VoiceTtsConfirmState()
        assertEquals(TtsConfirmDecision.SPEAK_WITH_WARNING, state.confirm { false })
        assertTrue(state.ackWriteFailed)
        assertEquals(TtsConfirmDecision.SPEAK, state.confirm { true })
        assertFalse(state.ackWriteFailed)
    }

    @Test
    fun clearAckWarning_resetsFlag() = runBlocking {
        val state = VoiceTtsConfirmState()
        state.confirm { false }
        assertTrue(state.ackWriteFailed)
        state.clearAckWarning()
        assertFalse(state.ackWriteFailed)
    }
}
