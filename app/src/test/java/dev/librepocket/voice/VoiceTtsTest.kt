package dev.librepocket.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2 系統 TTS 單測（純 JVM）：4000 字分段 + zh-TW→zh-CN→voices 找 zh。
 */
class VoiceTtsTest {

    @Test fun chunkLimit_is4000() {
        assertEquals(4000, VoiceTts.MAX_CHUNK_CHARS)
    }

    @Test fun shortText_isSingleChunk() {
        assertEquals(listOf("你好"), VoiceTts.split("你好"))
    }

    @Test fun emptyText_isNoChunk() {
        assertTrue(VoiceTts.split("").isEmpty())
    }

    @Test fun exactLimit_isSingleChunk() {
        val text = "a".repeat(VoiceTts.MAX_CHUNK_CHARS)
        val chunks = VoiceTts.split(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single())
    }

    @Test fun overLimit_splitsWithoutLoss() {
        val text = "b".repeat(VoiceTts.MAX_CHUNK_CHARS + 1)
        val chunks = VoiceTts.split(text)
        assertEquals(2, chunks.size)
        assertEquals(VoiceTts.MAX_CHUNK_CHARS, chunks[0].length)
        assertEquals(1, chunks[1].length)
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun multiChunk_roundTrips() {
        val text = "c".repeat(VoiceTts.MAX_CHUNK_CHARS * 2 + 7)
        val chunks = VoiceTts.split(text)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= VoiceTts.MAX_CHUNK_CHARS })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun twSupported_prefersTw() {
        val picked = VoiceTts.resolveLocale(
            isSupported = { it == VoiceTts.PREFERRED_TW },
            voiceLangs = listOf("zh-CN", "en-US"),
        )
        assertEquals(Locale.forLanguageTag("zh-TW"), picked)
    }

    @Test fun twMissing_fallsBackToCn() {
        val picked = VoiceTts.resolveLocale(
            isSupported = { it == VoiceTts.PREFERRED_CN },
            voiceLangs = listOf("zh-TW", "en-US"),
        )
        assertEquals(Locale.forLanguageTag("zh-CN"), picked)
    }

    @Test fun bothMissing_findsZhVoice() {
        val picked = VoiceTts.resolveLocale(
            isSupported = { false },
            voiceLangs = listOf("en-US", "zh-HK"),
        )
        assertEquals("zh", picked?.language)
    }

    @Test fun noZhAnywhere_isNull() {
        assertNull(
            VoiceTts.resolveLocale(
                isSupported = { false },
                voiceLangs = listOf("en-US", "ja-JP"),
            ),
        )
        assertNull(
            VoiceTts.resolveLocale(
                isSupported = { false },
                voiceLangs = emptyList(),
            ),
        )
    }
}
