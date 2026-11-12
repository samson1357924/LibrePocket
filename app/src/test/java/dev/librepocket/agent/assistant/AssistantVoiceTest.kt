package dev.librepocket.agent.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C2 純邏輯單測（Issue #137，純 JVM）。
 *
 * Intent 觸碰（putExtra／getBooleanExtra）只進 Robolectric
 *（見 AssistantVoiceRobolectricTest）；此處只測零 Android 行為：
 * extra key 字面穩定、回填語義、不自動送出鎖死。
 */
class AssistantVoiceTest {

    @Test fun extraKey_isStable() {
        assertEquals(
            "dev.librepocket.extra.ASSIST_VOICE_INPUT",
            AssistantVoice.EXTRA_VOICE_INPUT,
        )
    }

    @Test fun autoSend_isLockedOff() {
        // C2 回填只進輸入框：任何自動送出都必須另立決策並改此常數＋本測。
        assertFalse(AssistantVoice.AUTO_SEND_AFTER_VOICE)
    }

    @Test fun firstNonBlankResult_prefilled() {
        assertEquals("你好", AssistantVoice.resolveVoicePrefill(listOf("", "你好")))
        assertEquals("嗨", AssistantVoice.resolveVoicePrefill(listOf("嗨", "哈囉")))
    }

    @Test fun emptyResults_stayManual() {
        assertNull(AssistantVoice.resolveVoicePrefill(null))
        assertNull(AssistantVoice.resolveVoicePrefill(emptyList()))
        assertNull(AssistantVoice.resolveVoicePrefill(listOf("", "   ")))
    }

    @Test fun whitespaceOnlyResult_returnsNull() {
        assertNull(AssistantVoice.resolveVoicePrefill(listOf("   ")))
        assertNull(AssistantVoice.resolveVoicePrefill(listOf("\t\n  ")))
    }

    @Test fun leadingAndTrailingWhitespace_isTrimmed() {
        assertEquals("你好世界", AssistantVoice.resolveVoicePrefill(listOf("  你好世界 \n\t")))
        assertEquals("hello", AssistantVoice.resolveVoicePrefill(listOf("   ", "  hello  ")))
    }

    @Test fun longResult_isTruncatedToMaxTextLength() {
        val longText = "a".repeat(4500)
        val result = AssistantVoice.resolveVoicePrefill(listOf(longText))
        assertEquals("a".repeat(4000), result)
        assertEquals(4000, result?.length)
    }
}
