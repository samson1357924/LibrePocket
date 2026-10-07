package dev.librepocket.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the user-visible notice copy (F4): a mapping typo must redden here. */
class ChatNoticeTextTest {
    @Test
    fun mapsKnownNoticeCodesToUserMessages() {
        assertEquals("尚未設定端點，請先設定 API 金鑰。", chatNoticeText("NO_ENDPOINT"))
        assertEquals("政策拒絕讀取金鑰（key.read DENY），本次未發送任何請求。", chatNoticeText("POLICY_DENIED"))
        assertEquals("找不到該會話，可能已被刪除。", chatNoticeText("UNKNOWN_SESSION"))
        assertEquals("端點已變更，本次未送出，請確認後重送。", chatNoticeText("SEND_CANCELLED_ENDPOINT_CHANGED"))
    }

    @Test
    fun unknownNoticeCodeFallsThroughRaw() {
        assertEquals("SOME_FUTURE_CODE", chatNoticeText("SOME_FUTURE_CODE"))
    }
}
