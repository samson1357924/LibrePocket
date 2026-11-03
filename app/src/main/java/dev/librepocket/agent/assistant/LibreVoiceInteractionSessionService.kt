package dev.librepocket.agent.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * C1 session 宿主（Issue #137 C1a）：同一 `BIND_VOICE_INTERACTION` 門檻，
 * `SessionService` action；每個手勢喚起經 [onNewSession] 產生
 * [LibreVoiceInteractionSession]（C1 無狀態、C2 才加 Compose／STT）。
 */
class LibreVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        LibreVoiceInteractionSession(this)
}
