package dev.librepocket.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 雙錯誤通道轉換測試（#7，純 JVM，不釘原始碼字串）。
 *
 * STT 成功不再清除 TTS 錯誤；TTS 錯誤只在新 speak 發起、TTS 成功、
 * 輸出關閉三處清除；兩通道互不干擾。
 */
class VoiceErrorStateTest {

    @Test
    fun initial_bothNull() {
        val state = VoiceErrorState()
        assertNull(state.sttError)
        assertNull(state.ttsError)
    }

    @Test
    fun sttFailure_setsOnlyStt() {
        val state = VoiceErrorState()
        state.onSttFailure("stt down")
        assertEquals("stt down", state.sttError)
        assertNull(state.ttsError)
    }

    @Test
    fun sttSuccess_clearsOnlyStt() {
        val state = VoiceErrorState()
        state.onSttFailure("stt down")
        state.onTtsFailure("tts down")
        state.onSttSuccess()
        assertNull(state.sttError)
        assertEquals("tts down", state.ttsError)
    }

    @Test
    fun ttsFailure_setsOnlyTts() {
        val state = VoiceErrorState()
        state.onTtsFailure("tts down")
        assertNull(state.sttError)
        assertEquals("tts down", state.ttsError)
    }

    @Test
    fun speakStarted_clearsOnlyTts() {
        val state = VoiceErrorState()
        state.onSttFailure("stt down")
        state.onTtsFailure("tts down")
        state.onSpeakStarted()
        assertEquals("stt down", state.sttError)
        assertNull(state.ttsError)
    }

    @Test
    fun ttsSuccess_clearsOnlyTts() {
        val state = VoiceErrorState()
        state.onSttFailure("stt down")
        state.onTtsFailure("tts down")
        state.onTtsSuccess()
        assertEquals("stt down", state.sttError)
        assertNull(state.ttsError)
    }

    @Test
    fun outputDisabled_clearsOnlyTts() {
        val state = VoiceErrorState()
        state.onSttFailure("stt down")
        state.onTtsFailure("tts down")
        state.onOutputDisabled()
        assertEquals("stt down", state.sttError)
        assertNull(state.ttsError)
    }
}
