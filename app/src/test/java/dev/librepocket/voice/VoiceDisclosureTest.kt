package dev.librepocket.voice

import dev.librepocket.tool.DenyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage2 語音揭露單測（純 JVM）：
 * - 引擎探測 [VoiceTts.hasEngine] fail-closed；
 * - 失敗話術 [VoiceTts.ttsFallbackMessage] 引用原因碼；
 * - 「本地引擎」已修正為系統引擎 + 引擎揭露文案一致；
 * - ChatScreen 朗讀接線（失敗通道 + 揭露文案 + 開關門禁）不斷言 Compose，
 *   只釘選原始碼接線（與 `VoiceSttTest` 的 B1 探測斷言同模式）。
 */
class VoiceDisclosureTest {

    // ---- 引擎探測 ----

    @Test fun noEngineTable_isUnavailable() {
        assertFalse(VoiceTts.hasEngine(null))
        assertFalse(VoiceTts.hasEngine(emptyList<String>()))
    }

    @Test fun nonEmptyEngineTable_isAvailable() {
        assertTrue(VoiceTts.hasEngine(listOf("com.google.android.tts")))
    }

    // ---- 失敗話術 ----

    @Test fun ttsFallbackMessage_quotesReasonCode() {
        for (detail in listOf(
            VoiceTts.DETAIL_NO_ENGINE,
            VoiceTts.DETAIL_INIT_FAILED,
            VoiceTts.DETAIL_LANG_UNAVAILABLE,
            VoiceTts.DETAIL_SPEAK_FAILED,
        )) {
            val msg = VoiceTts.ttsFallbackMessage(DenyReason.NO_PRIVILEGE, detail)
            assertTrue("msg=$msg", msg.contains("NO_PRIVILEGE"))
            assertTrue("msg=$msg", msg.contains(detail))
            assertTrue("msg=$msg", msg.contains(VoiceTools.FALLBACK_TTS_HINT))
        }
    }

    // ---- 揭露文案 ----

    @Test fun speakButtonDescription_disclosesSystemEngine() {
        assertTrue(VoiceTts.SPEAK_BUTTON_DESCRIPTION.contains("朗讀"))
        assertTrue(VoiceTts.SPEAK_BUTTON_DESCRIPTION.contains("系統"))
        assertTrue(VoiceTts.SPEAK_BUTTON_DESCRIPTION.contains(VoiceTts.ENGINE_DISCLOSURE))
        assertFalse(VoiceTts.SPEAK_BUTTON_DESCRIPTION.contains("本地"))
        assertFalse(VoiceTts.ENGINE_DISCLOSURE.contains("本地"))
    }

    // ---- ChatScreen 接線（原始碼釘選） ----

    /** 單測工作目錄（模組 dir）出發，向上走最多 5 層找原始碼。 */
    private fun findSource(vararg relPaths: String): java.io.File {
        val bases = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.take(6).toList()
        for (base in bases) {
            for (rel in relPaths) {
                val f = java.io.File(base, rel)
                if (f.isFile) return f
            }
        }
        error("not found: ${relPaths.toList()} under ${System.getProperty("user.dir")}")
    }

    private fun chatScreen(): String = findSource(
        "src/main/java/dev/librepocket/agent/ui/chat/ChatScreen.kt",
        "app/src/main/java/dev/librepocket/agent/ui/chat/ChatScreen.kt",
    ).readText()

    @Test fun chatScreen_noLongerClaimsLocalEngine() {
        assertFalse(chatScreen().contains("本地引擎"))
    }

    @Test fun chatScreen_speakButtonUsesDisclosureCopy() {
        assertTrue(chatScreen().contains("VoiceTts.SPEAK_BUTTON_DESCRIPTION"))
    }

    @Test fun chatScreen_speakerFailureIsObservable() {
        // VoiceSpeaker 的失敗通道接到 voiceError（UI 可顯示），不再靜默。
        val src = chatScreen()
        assertTrue(src.contains("onError = { voiceError = it }"))
    }

    @Test fun chatScreen_speakStaysSwitchGated() {
        // UI 直連仍受 voice_output 開關門禁（與投影層同 key；決策見 ARCHITECTURE）。
        val src = chatScreen()
        assertTrue(src.contains("SWITCH_OUTPUT"))
        assertTrue(src.contains("showSpeak"))
    }

    @Test fun chatScreen_addsNoAudioPermission() {
        assertFalse(chatScreen().contains("RECORD_AUDIO"))
    }

    @Test fun settings_voiceCardDisclosesEngineDependence() {
        val settings = findSource(
            "src/main/java/dev/librepocket/agent/ui/settings/SettingsScreen.kt",
            "app/src/main/java/dev/librepocket/agent/ui/settings/SettingsScreen.kt",
        ).readText()
        assertTrue(settings.contains("取決於"))
        assertFalse(settings.contains("本地引擎"))
    }

    @Test fun speaker_failuresAreRoutedNotSilent() {
        // Stage2：utterance onError 不再是 no-op；各失敗點皆有 DETAIL。
        val speaker = findSource(
            "src/main/java/dev/librepocket/voice/VoiceSpeaker.kt",
            "app/src/main/java/dev/librepocket/voice/VoiceSpeaker.kt",
        ).readText()
        assertFalse(speaker.contains("override fun onError(utteranceId: String?) = Unit"))
        assertFalse(speaker.contains("override fun onError(utteranceId: String?, errorCode: Int) = Unit"))
        assertTrue(speaker.contains("DETAIL_INIT_FAILED"))
        assertTrue(speaker.contains("DETAIL_NO_ENGINE"))
        assertTrue(speaker.contains("DETAIL_LANG_UNAVAILABLE"))
        assertTrue(speaker.contains("DETAIL_SPEAK_FAILED"))
        assertEquals("azure_speech", VoiceTools.AZURE_KEY_REF)
    }
}
