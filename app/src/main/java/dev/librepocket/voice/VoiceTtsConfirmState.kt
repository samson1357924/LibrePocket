package dev.librepocket.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/**
 * 朗讀確認寫入的編排決策（[VoiceTtsConfirmState.confirm] 回傳）。
 */
enum class TtsConfirmDecision {
    /** 確認已持久化：執行本次朗讀，下次不再詢問。 */
    SPEAK,

    /** 寫入失敗：仍執行本次朗讀（用戶已明確確認），但保持未確認，下次重問。 */
    SPEAK_WITH_WARNING,

    /** 前一次確認寫入尚未完成：本次點按被抑制（連打防護）。 */
    SUPPRESSED,
}

/**
 * 朗讀內聯確認編排（#2）：await 寫入 → 按結果行動。
 *
 * - 寫入成功 → [TtsConfirmDecision.SPEAK]；
 * - 寫入失敗（回 false 或拋錯）→ [TtsConfirmDecision.SPEAK_WITH_WARNING]
 *  （本次仍朗讀，[ackWriteFailed] 置 true 供內聯警告顯示）；
 * - 寫入中（[confirming]）的第二次 [confirm] → [TtsConfirmDecision.SUPPRESSED]。
 *
 * 純狀態 holder（仿 [VoiceErrorState]：Compose `mutableStateOf` 驅動重組，
 * 轉換規則可純 JVM 單測，不依賴 Compose 測試框架；ChatScreen 只接線）。
 */
class VoiceTtsConfirmState {
    var confirming by mutableStateOf(false)
        private set
    var ackWriteFailed by mutableStateOf(false)
        private set

    suspend fun confirm(ack: suspend () -> Boolean): TtsConfirmDecision {
        if (confirming) return TtsConfirmDecision.SUPPRESSED
        confirming = true
        try {
            val ok = try {
                ack()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            ackWriteFailed = !ok
            return if (ok) TtsConfirmDecision.SPEAK else TtsConfirmDecision.SPEAK_WITH_WARNING
        } finally {
            confirming = false
        }
    }

    /** 成功寫入／輸出關閉時清除寫入失敗警告。 */
    fun clearAckWarning() {
        ackWriteFailed = false
    }

    /**
     * 確認寫入完成後的可朗讀判定（P2 競態防線，純函數）。
     *
     * 確認協程在 `confirm(ack)` 前記下 `outputGeneration`（見 ChatScreen 接線；
     * `voice_output` 每次變更即 bump）；`ack` suspend 期間開關翻轉會先 dispose
     * 舊 speaker 並清空 pending，恢復後若世代不符必須丟棄（不 speak、不清錯、
     * 不碰 pending），否則會用已 dispose 的舊 speaker 朗讀，甚至清掉翻轉後的新 pending。
     */
    fun shouldSpeakAfterConfirm(capturedGeneration: Long, currentGeneration: Long): Boolean =
        capturedGeneration == currentGeneration

    /**
     * 待朗讀清除判定（P2 身分防線，純函數）：只在當代 pending 與 capture
     * 的文字身分相符時清除，避免清掉開關翻轉後的新待確認朗讀。
     */
    fun shouldClearPendingAfterConfirm(capturedText: String?, currentPendingText: String?): Boolean =
        capturedText == currentPendingText
}
