package dev.librepocket.agent.assistant

import android.service.voice.VoiceInteractionService

/**
 * C1 系統預設助理入口（Issue #137 C1a，三 flavor 共用）。
 *
 * - 授予 `ROLE_ASSISTANT` 後，長按首頁／系統手勢由系統綁定本服務；
 * - 實際喚起在 [LibreVoiceInteractionSession.onShow]（startVoiceActivity
 *   MainActivity `ACTION_ASSIST` 後 `hide()`，C2 才加 session 內 Compose）；
 * - C1 不新增任何 uses-permission（`android:permission` 只是綁定方門檻，
 *   非本 App 要求的權限）；play 不加 a11y service overlay（本 PR 完全不碰
 *   a11y，base manifest 亦無 a11y 宣告）。
 */
class LibreVoiceInteractionService : VoiceInteractionService()
