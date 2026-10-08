package dev.librepocket.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the user-visible notice copy (F4): a mapping typo must redden here. */
class ChatNoticeTextTest {
    @Test
    fun mapsKnownNoticeCodesToUserMessages() {
        assertEquals("尚未設定端點，請先設定 API 金鑰。", chatNoticeText("NO_ENDPOINT"))
        assertEquals("政策拒絕讀取金鑰（key.read DENY），本次未發送任何請求。", chatNoticeText("POLICY_DENIED"))
        assertEquals("對話送出被政策拒絕（chat.send DENY），本次未發送任何請求。", chatNoticeText("CHAT_SEND_DENIED"))
        assertEquals(
            "此操作需要明確核准；目前沒有互動核准流程，本次未送出，也未自動允許。",
            chatNoticeText("CHAT_APPROVAL_REQUIRED"),
        )
        assertEquals("無法驗證對話權限，本次未送出，請稍後重試", chatNoticeText("CHAT_POLICY_UNAVAILABLE"))
        assertEquals(
            "對話已取消；尚未送出的排隊訊息已保留，請確認後手動送出。",
            chatNoticeText("CHAT_CANCELLED_RECOVERY"),
        )
        assertEquals(
            "排隊中的訊息尚未送出，已保留，請確認後手動送出。",
            chatNoticeText("CHAT_QUEUE_RECOVERY_REQUIRED"),
        )
        assertEquals("找不到該會話，可能已被刪除。", chatNoticeText("UNKNOWN_SESSION"))
        assertEquals("端點已變更，本次未送出，請確認後重送。", chatNoticeText("SEND_CANCELLED_ENDPOINT_CHANGED"))
    }

    @Test
    fun unknownNoticeCodeFallsThroughRaw() {
        assertEquals("SOME_FUTURE_CODE", chatNoticeText("SOME_FUTURE_CODE"))
    }
}
