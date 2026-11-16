package dev.librepocket.agent.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * C1 session 宿主（Issue #137 C1a）：同一 `BIND_VOICE_INTERACTION` 門檻，
 * `SessionService` action；每個手勢喚起經 [onNewSession] 產生
 * [LibreVoiceInteractionSession]（C2-final：session 本體不承載 Compose／STT；UI 由 MainActivity
 * 於獨立 voice task 承載，STT 走既有 RecognizerIntent 委派；見 AssistantVoice.kt）。
 */
// C2-final（Issue #137）：session 內 Compose／串流 STT 已否決——直接
// SpeechRecognizer 需本 App 錄音權限，與 C1 零新增權限契約衝突；
// UI 由 MainActivity（獨立 voice task）承載，STT 走 RecognizerIntent 委派（見 AssistantVoice.kt）。
class LibreVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        LibreVoiceInteractionSession(this)
}
