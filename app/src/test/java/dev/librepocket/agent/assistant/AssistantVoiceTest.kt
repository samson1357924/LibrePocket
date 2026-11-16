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
        assertNull(AssistantVoice.resolveVoicePrefill(emptyList<Any?>()))
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

    @Test fun prefill_candidateOverCapWindowEndsWithWhitespaceRun_keepsFirst4000CharsExactly() {
        // 3999 字 x + 兩空白 + 10 字 y = 4011 chars；body > cap → 取 start 起前 4000 字元。
        // 索引 0..3999＝3999 個 x＋第一個空白（第二個空白在 index 4000，落在 cap 外）——
        // 恰好 4000 字元、尾端單一空白；≡ 舊 trim→take(4000) 的前綴（逐位元組）。
        val candidate = "x".repeat(3999) + "  " + "y".repeat(10)
        assertEquals(4000 + 11, candidate.length)
        assertEquals("x".repeat(3999) + " ", AssistantVoice.resolveVoicePrefill(listOf(candidate)))
    }

    @Test fun prefill_candidateOverCapWithLeadingWhitespace_identicalToLegacyPipeline() {
        val candidate = "   " + "a".repeat(4500)
        assertEquals("a".repeat(4000), AssistantVoice.resolveVoicePrefill(listOf(candidate)))
    }

    @Test fun prefill_candidateExactlyAtCap_unchanged() {
        assertEquals("b".repeat(4000), AssistantVoice.resolveVoicePrefill(listOf("b".repeat(4000))))
        assertEquals(
            "b".repeat(3900),
            AssistantVoice.resolveVoicePrefill(listOf("b".repeat(3900) + " ")),
        )
    }

    @Test fun prefill_trailingWhitespaceRunOverCap_matchesLegacyPipeline() {
        // 尾端超長空白跑（>4000）：end 反向掃描跳過整段空白跑後，take = min(end-start, cap)——
        // 等同舊 pipeline trim → take(4000) 的前綴（逐位元組）。
        assertEquals("a", AssistantVoice.resolveVoicePrefill(listOf("a" + " ".repeat(5000))))
        assertEquals("abc", AssistantVoice.resolveVoicePrefill(listOf("abc" + " ".repeat(5000))))
        assertEquals(
            "b".repeat(2000),
            AssistantVoice.resolveVoicePrefill(listOf("b".repeat(2000) + " ".repeat(5000))),
        )
    }

    @Test fun wrongElementTypes_returnNull() {
        assertNull(AssistantVoice.resolveVoicePrefill(listOf(42)))
    }

    @Test fun mixedAndNullElements_resolveFirstValidTrimmedString() {
        assertEquals("ok", AssistantVoice.resolveVoicePrefill(listOf(null, 1, "  ok  ")))
        assertNull(AssistantVoice.resolveVoicePrefill(listOf<Any?>(null, 1, "", "  ")))
        val longText = "a".repeat(4500)
        assertEquals(
            "a".repeat(4000),
            AssistantVoice.resolveVoicePrefill(listOf<Any?>(42, "  $longText  ")),
        )
    }
}
