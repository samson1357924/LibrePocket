package dev.librepocket.voice

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2 系統 STT 單測（純 JVM）。
 *
 * 斷言 RecognizerIntent 委託參數（FREE_FORM / zh-TW / MAX 1）、
 * 無辨識服務降級原因碼（NO_PRIVILEGE / USER_DISABLED）、
 * manifest `<queries>` 動作為 RECOGNIZE_SPEECH 且探測帶 MATCH_DEFAULT_ONLY（B1），
 * 以及 `voice.transcribe` 投影（READ，`voice_input` 預設開）。
 */
class VoiceSttTest {

    @Test fun intentParams_matchSystemRecognizerContract() {
        // 與 android.speech.RecognizerIntent 字面值一致（AOSP 穩定值，見 ChatScreen 用法）。
        assertEquals("android.speech.action.RECOGNIZE_SPEECH", VoiceStt.ACTION)
        assertEquals("android.speech.extra.LANGUAGE_MODEL", VoiceStt.EXTRA_LANGUAGE_MODEL)
        assertEquals("free_form", VoiceStt.LANGUAGE_MODEL_FREE_FORM)
        assertEquals("android.speech.extra.LANGUAGE", VoiceStt.EXTRA_LANGUAGE)
        assertEquals("zh-TW", VoiceStt.LANGUAGE)
        assertEquals("android.speech.extra.MAX_RESULTS", VoiceStt.EXTRA_MAX_RESULTS)
        assertEquals(1, VoiceStt.MAX_RESULTS)
    }

    @Test fun firstNonBlankResult_prefilled() {
        assertEquals("你好", VoiceStt.pickResult(listOf("", "你好")))
        assertEquals("嗨", VoiceStt.pickResult(listOf("嗨", "哈囉")))
    }

    @Test fun emptyResults_stayManual() {
        assertNull(VoiceStt.pickResult(null))
        assertNull(VoiceStt.pickResult(emptyList()))
        assertNull(VoiceStt.pickResult(listOf("", "   ")))
    }

    @Test fun switchOff_isUserDisabled() {
        assertEquals(
            DenyReason.USER_DISABLED,
            VoiceStt.degradeReason(hasRecognitionService = true, inputEnabled = false),
        )
        // 開關關閉優先於無服務（先看使用者意願）。
        assertEquals(
            DenyReason.USER_DISABLED,
            VoiceStt.degradeReason(hasRecognitionService = false, inputEnabled = false),
        )
    }

    @Test fun noService_isNoPrivilege() {
        assertEquals(
            DenyReason.NO_PRIVILEGE,
            VoiceStt.degradeReason(hasRecognitionService = false, inputEnabled = true),
        )
    }

    @Test fun servicePresent_isAvailable() {
        assertNull(VoiceStt.degradeReason(hasRecognitionService = true, inputEnabled = true))
    }

    @Test fun fallbackMessage_quotesReasonCode() {
        val noService = VoiceStt.fallbackMessage(DenyReason.NO_PRIVILEGE, VoiceStt.DETAIL_NO_SERVICE)
        assertTrue(noService.contains("NO_PRIVILEGE"))
        assertTrue(noService.contains(VoiceStt.DETAIL_NO_SERVICE))
        val switchedOff = VoiceStt.fallbackMessage(DenyReason.USER_DISABLED, VoiceStt.DETAIL_SWITCH_OFF)
        assertTrue(switchedOff.contains("USER_DISABLED"))
    }

    // ---- B1：manifest <queries> + 探測旗標 ----

    /** 單測工作目錄（模組 dir）出發，向上走最多 5 層找原始碼/清單檔。 */
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

    @Test fun manifestQueriesAction_isRecognizeSpeech() {
        // B1：queries 必須是 intent action（RECOGNIZE_SPEECH），不可用
        // RecognitionService（服務類名，PackageManager 解析不到）。
        val manifest = findSource(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        ).readText()
        assertTrue(
            manifest.contains("<action android:name=\"android.speech.action.RECOGNIZE_SPEECH\" />"),
        )
        assertFalse(manifest.contains("android.speech.RecognitionService"))
        assertEquals("android.speech.action.RECOGNIZE_SPEECH", VoiceStt.ACTION)
    }

    @Test fun sttProbe_usesMatchDefaultOnly() {
        // B1：探測只認 DEFAULT 可處理的辨識服務（見 ChatScreen.launchSystemStt）。
        val chatScreen = findSource(
            "src/main/java/dev/librepocket/agent/ui/chat/ChatScreen.kt",
            "app/src/main/java/dev/librepocket/agent/ui/chat/ChatScreen.kt",
        ).readText()
        assertTrue(chatScreen.contains("MATCH_DEFAULT_ONLY"))
    }

    // ---- voice.transcribe 投影（系統委託；B2 後 Azure 不再碰 STT） ----

    @Test fun transcribeIsReadWithInputSwitchDefaultOn() {
        val tool = ToolRegistry.find(VoiceTools.TRANSCRIBE_NAME)!!
        assertEquals(dev.librepocket.tool.SideEffect.READ, tool.sideEffect)
        assertEquals(VoiceTools.SWITCH_INPUT, tool.annotations.requiresSwitch)
        assertEquals(true, tool.annotations.switchDefault)
        assertEquals(VoiceTools.SWITCH_INPUT_DEFAULT, true)
    }

    @Test fun transcribe_visibleOnAllFlavorsByDefault() {
        for (flavor in Flavor.entries) {
            val ctx = ProjectionContext(flavor = flavor)
            assertEquals(
                CapabilityLevel.NATIVE,
                ToolRegistry.projectAll(ctx)[VoiceTools.TRANSCRIBE_NAME]!!.level,
            )
        }
    }

    @Test fun transcribe_switchOffIsUserDisabled() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(VoiceTools.SWITCH_INPUT to false),
        )
        val projected = ToolRegistry.projectAll(ctx)[VoiceTools.TRANSCRIBE_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
    }
}
