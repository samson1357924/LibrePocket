package dev.librepocket.agent.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import dev.librepocket.agent.MainActivity

/**
 * C1 session（Issue #137 C1a／C1b）。
 *
 * `onShow` 即使用者手勢（長按首頁／系統手勢）：以 [assistantVoiceIntent(android.content.Context)]
 * 掛 MainActivity component 後 `startVoiceActivity` 發出 `ACTION_ASSIST`
 *（保留現有 ASSIST 入口，見 MainActivity EntryGate：手勢放行），再 `hide()`。
 * C1 不做 session 內 UI／STT（C2 範疇）；喚起失敗亦 `hide()`，
 * fail-closed，不殘留空白 session。
 */
class LibreVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val assist = assistantVoiceIntent(context).setClass(context, MainActivity::class.java)
            startVoiceActivity(assist)
        } catch (_: Exception) {
            // C2 才有 session 內 UI 可回報；C1 只保證不殘留 session。
        } finally {
            hide()
        }
    }
}
