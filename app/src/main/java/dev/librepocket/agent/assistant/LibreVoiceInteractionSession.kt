package dev.librepocket.agent.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dev.librepocket.agent.MainActivity

private const val TAG = "LibreVoiceSession"

// TODO(C2-assistant): session 內 UI／STT（Compose＋系統 STT）；follow-up 以
// voiceInteractionRoot（Activity.isVoiceInteractionRoot）區分可信系統手勢喚起與外部偽造。

/**
 * C1 session（Issue #137 C1a／C1b）。
 *
 * `onShow` 後以 [assistantVoiceIntent(android.content.Context)] 為基礎，
 * 此處再 `setClass` 轉真 explicit，掛 MainActivity component（真 explicit）
 * 並經 `startVoiceActivity` 發出
 * `ACTION_ASSIST`（沿用既有 ASSIST 入口），再 `hide()`。C1 不做 session 內
 * UI／STT（C2 範疇）；喚起失敗亦 `hide()`，fail-closed，不殘留空白 session。
 *
 * 手勢主張降級：`ACTION_ASSIST` 是既有隱式入口，第三方應用亦可偽造發起——此類
 * 不可信喚起僅開啟 App（EntryGate：ASSIST 無文字即只開 App、不自動發送），不授予
 * 額外能力。是否為可信系統手勢喚起，留待 follow-up 以 `voiceInteractionRoot`
 * 區分；C1 不在此斷言手勢來源。
 */
class LibreVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val assist = assistantVoiceIntent(context).setClass(
                context,
                MainActivity::class.java,
            )
            startVoiceActivity(assist)
        } catch (e: Exception) {
            Log.w(TAG, "startVoiceActivity failed", e)
        } finally {
            hide()
        }
    }
}
