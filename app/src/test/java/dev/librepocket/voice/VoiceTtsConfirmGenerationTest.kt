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
 * P2 確認競態＋身分防線測試（純 JVM，唯 session effect 接線釘選原始碼字串）。
 *
 * 覆蓋 ChatScreen 確認接線的整合語義（capture 待朗讀身分＋世代 → suspend ack →
 * 恢復後 token／世代／session 三者比對，見 VoiceTtsConfirmState.shouldSpeakAfterConfirm）：
 *
 * - 世代不變 → 可朗讀；世代不同（await 期間輸出開關翻轉）→ 丟棄；
 * - token 失配（await 期間取消或新 pending 覆寫）→ 丟棄，新 pending 不被舊協程誤清；
 * - session 失配（新對話不卸載 ChatScreen，殘留 pending 不得跨會話朗讀）→ 丟棄；
 * - suspend ack 中途取消 → 即使寫入成功仍丟棄（不 speak、不清錯），pending 保持 null；
 * - suspend ack 中途關開關 → 即使寫入成功仍丟棄（不 speak、不清錯），
 *   且關→開後的新 pending 不被舊協程誤清；
 * - 同文不同 messageId 的新 pending（token 不同）→ 舊 job 不清新 pending。
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
    fun identityMatch_allowsSpeak() {
        val state = VoiceTtsConfirmState()
        val captured = PendingSpeak("m1", "s1", "hello", 0L)
        assertTrue(state.shouldSpeakAfterConfirm(captured, captured, 0L, 0L, "s1"))
    }

    @Test
    fun tokenMismatch_discardsSpeak() {
        val state = VoiceTtsConfirmState()
        val captured = PendingSpeak("m1", "s1", "hello", 0L)
        val current = PendingSpeak("m1", "s1", "hello", 1L)
        assertFalse(state.shouldSpeakAfterConfirm(captured, current, 0L, 0L, "s1"))
    }

    @Test
    fun pendingCleared_discardsSpeak() {
        val state = VoiceTtsConfirmState()
        val captured = PendingSpeak("m1", "s1", "hello", 0L)
        assertFalse(state.shouldSpeakAfterConfirm(captured, null, 0L, 0L, "s1"))
    }

    @Test
    fun sessionMismatch_discardsSpeak() {
        val state = VoiceTtsConfirmState()
        val captured = PendingSpeak("m1", "s1", "hello", 0L)
        // session 切換：pending 殘留亦不得跨會話朗讀。
        assertFalse(state.shouldSpeakAfterConfirm(captured, captured, 0L, 0L, "s2"))
        assertFalse(state.shouldSpeakAfterConfirm(captured, null, 0L, 0L, "s2"))
    }

    @Test
    fun ackInFlight_cancelDuringConfirm_discardsWithoutClearingError() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        // 接線側狀態：世代計數＋待朗讀＋單調 token（仿 ChatScreen 的 outputGeneration／
        // pendingSpeak／speakToken）＋既有 tts 錯誤（取消不碰錯誤）。
        var generation = 0L
        var token = 0L
        var pending: PendingSpeak? = PendingSpeak("m1", "s1", "hello", token)
        var spoke = false
        var ttsError: String? = "old"
        val captured = pending
        val capturedGeneration = generation
        val capturedSession = "s1"
        var currentSession: String? = "s1"
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // 中途取消：仿取消鈕（token 遞增＋清 pending，不碰錯誤）。
        token++
        pending = null
        gate.complete(true)
        assertEquals(TtsConfirmDecision.SPEAK, ack.await())
        // 恢復後 token 失配 → 丟棄（不 speak、不清錯、不碰 pending，後者保持 null）。
        if (state.shouldSpeakAfterConfirm(captured!!, pending, capturedGeneration, generation, currentSession)) {
            ttsError = null
            spoke = true
            if (pending?.token == captured.token) pending = null
        }
        assertFalse(spoke)
        assertEquals("old", ttsError)
        assertNull(pending)
        assertEquals("s1", capturedSession)
        assertEquals("s1", currentSession)
    }

    @Test
    fun ackInFlight_outputToggled_successStillDiscarded() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        var generation = 0L
        var token = 0L
        var pending: PendingSpeak? = PendingSpeak("m1", "s1", "hello", token)
        var spoke = false
        var clearedError = false
        val captured = pending!!
        val capturedGeneration = generation
        val currentSession: String? = "s1"
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // 中途關開關：仿 LaunchedEffect(outputEnabled)：世代 bump、token 失效、清 pending。
        generation++
        token++
        pending = null
        gate.complete(true)
        assertEquals(TtsConfirmDecision.SPEAK, ack.await())
        // 恢復後世代＋token 不符 → 丟棄（不 speak、不清錯）；pending 已由開關效應清空，接線不另碰。
        if (state.shouldSpeakAfterConfirm(captured, pending, capturedGeneration, generation, currentSession)) {
            clearedError = true
            spoke = true
            if (pending?.token == captured.token) pending = null
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
        var token = 0L
        var pending: PendingSpeak? = PendingSpeak("m1", "s1", "hello", token)
        var spoke = false
        val captured = pending!!
        val capturedGeneration = generation
        val currentSession: String? = "s1"
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // 中途關→開（各 bump 一次），開後用戶對新訊息按下朗讀（新 pending，新 token）。
        generation++
        token++
        pending = null
        generation++
        token++
        pending = PendingSpeak("m2", "s1", "world", token)
        gate.complete(true)
        ack.await()
        if (state.shouldSpeakAfterConfirm(captured, pending, capturedGeneration, generation, currentSession)) {
            spoke = true
            val current: PendingSpeak? = pending
            if (current?.token == captured.token) pending = null
        }
        assertFalse(spoke)
        assertEquals(PendingSpeak("m2", "s1", "world", 2L), pending)
    }

    @Test
    fun ackInFlight_sameTextNewMessage_newPendingPreserved() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        // 同文不同訊息：文字身分相同但 token 不同，舊 job 不得清新 pending。
        var token = 0L
        var pending: PendingSpeak? = PendingSpeak("m1", "s1", "hello", token)
        var spoke = false
        val captured = pending!!
        val currentSession: String? = "s1"
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // 用戶對同文另一則訊息按下朗讀（後按覆蓋前按）：token 遞增＋置新 pending。
        token++
        pending = PendingSpeak("m2", "s1", "hello", token)
        gate.complete(true)
        ack.await()
        if (state.shouldSpeakAfterConfirm(captured, pending, 0L, 0L, currentSession)) {
            spoke = true
            val current: PendingSpeak? = pending
            if (current?.token == captured.token) pending = null
        }
        assertFalse(spoke)
        assertEquals(PendingSpeak("m2", "s1", "hello", 1L), pending)
    }

    @Test
    fun ackInFlight_sessionSwitched_oldPendingInvalidated() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        var token = 0L
        var pending: PendingSpeak? = PendingSpeak("m1", "s1", "hello", token)
        var spoke = false
        val captured = pending!!
        var currentSession: String? = "s1"
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        // session 切換：仿 LaunchedEffect(currentSessionId)：token 失效＋清 pending。
        token++
        pending = null
        currentSession = "s2"
        gate.complete(true)
        ack.await()
        if (state.shouldSpeakAfterConfirm(captured, pending, 0L, 0L, currentSession)) {
            spoke = true
            if (pending?.token == captured.token) pending = null
        }
        assertFalse(spoke)
        assertNull(pending)
    }

    @Test
    fun ackInFlight_noToggle_speaksAndClearsTokenMatch() = runBlocking {
        val state = VoiceTtsConfirmState()
        val gate = CompletableDeferred<Boolean>()
        var pending: PendingSpeak? = PendingSpeak("m1", "s1", "hello", 0L)
        var spoke = false
        var clearedError = false
        val captured = pending!!
        val currentSession: String? = "s1"
        val ack = async { state.confirm { gate.await() } }
        while (!state.confirming) kotlinx.coroutines.yield()
        gate.complete(true)
        assertEquals(TtsConfirmDecision.SPEAK, ack.await())
        if (state.shouldSpeakAfterConfirm(captured, pending, 0L, 0L, currentSession)) {
            clearedError = true
            spoke = true
            val current: PendingSpeak? = pending
            if (current?.token == captured.token) pending = null
        }
        assertTrue(spoke)
        assertTrue(clearedError)
        assertNull(pending)
    }

    // ---- session effect 接線釘選（原始碼字串，僅此節） ----
    //
    // 脆弱性說明：JVM 單測看不到 Compose 渲染結果，只能做字串包含檢查。
    // 重構（改名/搬檔/改寫法）易誤報；UI 斷線（如 effect 被刪但判定函數仍在）由此守住。
    // 保留理由：session 切換不清 pending 會跨會話朗讀，屬殘留狀態接線，
    // 無法以純狀態測試行使（LaunchedEffect 需 Compose 運行時），
    // 而補 Compose 渲染測試成本超出本輪範圍；判定函數層語義由上方行為測試覆蓋。

    /** 單測工作目錄（模組 dir）出發，向上走最多 5 層找原始碼（仿 VoiceDisclosureTest）。 */
    private fun findSource(vararg relPaths: String): java.io.File {
        val bases = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.take(6).toList()
        for (base in bases) {
            for (rel in relPaths) {
                val f = java.io.File(base, rel)
                if (f.isFile) return f
            }
        }
        error("not found: ${relPaths.toList()} under ${System.getProperty("user.dir")}")
    }

    private fun chatScreen(): String = findSource(
        "src/main/java/dev/librepocket/agent/ui/chat/ChatScreen.kt",
        "app/src/main/java/dev/librepocket/agent/ui/chat/ChatScreen.kt",
    ).readText()

    @Test fun chatScreen_subscribesCurrentSessionId() {
        assertTrue(chatScreen().contains("viewModel.currentSessionId"))
    }

    @Test fun chatScreen_sessionSwitchInvalidatesPending() {
        assertTrue(chatScreen().contains("LaunchedEffect(currentSessionId)"))
    }

    @Test fun chatScreen_pendingCarriesMessageAndSessionIdentity() {
        assertTrue(chatScreen().contains("PendingSpeak(messageId, currentSessionId"))
    }
}
