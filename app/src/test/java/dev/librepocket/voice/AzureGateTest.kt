package dev.librepocket.voice

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2 Azure TTS 門禁單測（純 JVM）：
 * - `voice.speak`（WRITE，`voice_output` 預設開）；
 * - `voice.speak.azure`（WRITE，僅 GITHUB，`azure_tts` 預設關，
 *   另需 `voice_output` 同開——雙開關由 [AzureSpeechGate] 與 ToolRegistry
 *   第二開關共同強制，任一關即 USER_DISABLED）。
 *
 * 系統 STT（`voice.transcribe`）投影見 [VoiceSttTest]：B2 後 Azure 引擎
 * 不再提供雲端辨識（STT 維持系統委託），本檔不測 STT。
 */
class AzureGateTest {

    // ---- ToolDef 靜態註解（TTS 側） ----

    @Test fun speakIsWriteWithOutputSwitchDefaultOn() {
        val tool = ToolRegistry.find(VoiceTools.SPEAK_NAME)!!
        assertEquals(SideEffect.WRITE, tool.sideEffect)
        assertEquals(VoiceTools.SWITCH_OUTPUT, tool.annotations.requiresSwitch)
        assertEquals(true, tool.annotations.switchDefault)
    }

    @Test fun azureSpeakIsWriteGithubOnlyDefaultOff() {
        val tool = ToolRegistry.find(VoiceTools.AZURE_SPEAK_NAME)!!
        assertEquals(SideEffect.WRITE, tool.sideEffect)
        assertEquals(setOf(Flavor.GITHUB), tool.supportedFlavors)
        assertEquals(VoiceTools.SWITCH_AZURE, tool.annotations.requiresSwitch)
        assertEquals(false, tool.annotations.switchDefault)
        assertEquals(VoiceTools.SWITCH_AZURE_DEFAULT, false)
        // M1 雙開關：第二開關 voice_output（預設開）任一關即 USER_DISABLED。
        assertEquals(VoiceTools.SWITCH_OUTPUT, tool.annotations.requiresSwitch2)
        assertEquals(true, tool.annotations.switchDefault2)
        assertEquals(VoiceTools.SWITCH_OUTPUT_DEFAULT, true)
    }

    // ---- 投影：系統 TTS 全風味可見，Azure 僅 github ----

    @Test fun systemSpeak_visibleOnAllFlavorsByDefault() {
        for (flavor in Flavor.entries) {
            val ctx = ProjectionContext(flavor = flavor)
            assertEquals(
                CapabilityLevel.NATIVE,
                ToolRegistry.projectAll(ctx)[VoiceTools.SPEAK_NAME]!!.level,
            )
        }
    }

    @Test fun systemSpeak_switchOffIsUserDisabled() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(VoiceTools.SWITCH_OUTPUT to false),
        )
        assertEquals(CapabilityLevel.UNAVAILABLE, ToolRegistry.projectAll(ctx)[VoiceTools.SPEAK_NAME]!!.level)
        assertEquals(DenyReason.USER_DISABLED, ToolRegistry.projectAll(ctx)[VoiceTools.SPEAK_NAME]!!.reason)
    }

    @Test fun azure_hiddenOutsideGithub() {
        for (flavor in listOf(Flavor.PLAY, Flavor.FOSS)) {
            val ctx = ProjectionContext(
                flavor = flavor,
                userSwitches = mapOf(VoiceTools.SWITCH_AZURE to true),
            )
            val projected = ToolRegistry.projectAll(ctx)[VoiceTools.AZURE_SPEAK_NAME]!!
            assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
            assertEquals(DenyReason.FLAVOR_BLOCKED, projected.reason)
            assertFalse(
                ToolRegistry.visibleTools(ctx).any { it.name == VoiceTools.AZURE_SPEAK_NAME },
            )
        }
    }

    @Test fun azure_githubNeedsOptInSwitch() {
        val off = ProjectionContext(flavor = Flavor.GITHUB)
        assertEquals(CapabilityLevel.UNAVAILABLE, ToolRegistry.projectAll(off)[VoiceTools.AZURE_SPEAK_NAME]!!.level)
        assertEquals(DenyReason.USER_DISABLED, ToolRegistry.projectAll(off)[VoiceTools.AZURE_SPEAK_NAME]!!.reason)
        val on = ProjectionContext(
            flavor = Flavor.GITHUB,
            userSwitches = mapOf(VoiceTools.SWITCH_AZURE to true),
        )
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(on)[VoiceTools.AZURE_SPEAK_NAME]!!.level)
    }

    @Test fun azure_descriptionMarksScaffoldUntilWired() {
        // PR#1 review 收斂：雲端分派接線落地前，模型可見的 description 必須誠實標註 SCAFFOLD。
        val desc = ToolRegistry.find(VoiceTools.AZURE_SPEAK_NAME)!!.description
        assertTrue("desc=$desc", desc.contains("SCAFFOLD"))
    }

    @Test fun azure_voiceOutputOffIsUserDisabled() {       // M1：雙開關任一關即 UNAVAILABLE + USER_DISABLED（與 AzureSpeechGate 同語義）。
        val outputOff = ProjectionContext(
            flavor = Flavor.GITHUB,
            userSwitches = mapOf(
                VoiceTools.SWITCH_AZURE to true,
                VoiceTools.SWITCH_OUTPUT to false,
            ),
        )
        val projected = ToolRegistry.projectAll(outputOff)[VoiceTools.AZURE_SPEAK_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.USER_DISABLED, projected.reason)
        assertFalse(
            ToolRegistry.visibleTools(outputOff).any { it.name == VoiceTools.AZURE_SPEAK_NAME },
        )
        // 雙關齊開才可見。
        val bothOn = ProjectionContext(
            flavor = Flavor.GITHUB,
            userSwitches = mapOf(
                VoiceTools.SWITCH_AZURE to true,
                VoiceTools.SWITCH_OUTPUT to true,
            ),
        )
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(bothOn)[VoiceTools.AZURE_SPEAK_NAME]!!.level)
        assertTrue(
            ToolRegistry.visibleTools(bothOn).any { it.name == VoiceTools.AZURE_SPEAK_NAME },
        )
    }

    // ---- 雙開關門禁 ----

    @Test fun gate_needsBothSwitchesOnGithub() {
        assertTrue(AzureSpeechGate.canUse(Flavor.GITHUB, voiceOutputOn = true, azureTtsOn = true))
        assertFalse(AzureSpeechGate.canUse(Flavor.GITHUB, voiceOutputOn = false, azureTtsOn = true))
        assertFalse(AzureSpeechGate.canUse(Flavor.GITHUB, voiceOutputOn = true, azureTtsOn = false))
        assertFalse(AzureSpeechGate.canUse(Flavor.PLAY, voiceOutputOn = true, azureTtsOn = true))
        assertFalse(AzureSpeechGate.canUse(Flavor.FOSS, voiceOutputOn = true, azureTtsOn = true))
    }

    @Test fun gate_reasons() {
        assertEquals(
            DenyReason.FLAVOR_BLOCKED,
            AzureSpeechGate.denyReason(Flavor.PLAY, voiceOutputOn = true, azureTtsOn = true, hasKey = true),
        )
        assertEquals(
            DenyReason.USER_DISABLED,
            AzureSpeechGate.denyReason(Flavor.GITHUB, voiceOutputOn = false, azureTtsOn = true, hasKey = true),
        )
        assertEquals(
            DenyReason.USER_DISABLED,
            AzureSpeechGate.denyReason(Flavor.GITHUB, voiceOutputOn = true, azureTtsOn = false, hasKey = true),
        )
        assertEquals(
            DenyReason.NO_PRIVILEGE,
            AzureSpeechGate.denyReason(Flavor.GITHUB, voiceOutputOn = true, azureTtsOn = true, hasKey = false),
        )
        assertNull(
            AzureSpeechGate.denyReason(Flavor.GITHUB, voiceOutputOn = true, azureTtsOn = true, hasKey = true),
        )
    }

    // ---- 隱私：金鑰引用 + 送雲/寫庫前 redact ----

    @Test fun azureKeyRef_isPinned() {
        assertEquals("azure_speech", VoiceTools.AZURE_KEY_REF)
    }

    @Test fun cloudRedact_stripsSensitiveText() {
        val redacted = VoiceTools.redactForCloud("聯絡 test@example.com 再說")
        assertFalse(redacted.contains("test@example.com"))
    }

    @Test fun storeRedact_stripsSensitiveText() {
        val redacted = VoiceTools.redactForStore("電話 0912-345-678 請回電")
        assertFalse(redacted.contains("0912"))
    }

    @Test fun voiceTools_haveNoPermissionLiterals() {
        for (tool in ToolRegistry.VOICE_TOOLS) {
            val blob = tool.name + tool.description + tool.jsonSchema +
                (tool.annotations.requiresPermission ?: "")
            assertFalse(blob, blob.contains("RECORD_AUDIO"))
            assertFalse(blob, blob.contains("SEND_SMS"))
        }
        // 全表亦不可出現錄音權限（S2 零新權限）。
        for (tool in ToolRegistry.ALL) {
            assertFalse(
                tool.name,
                (tool.annotations.requiresPermission ?: "").contains("RECORD_AUDIO"),
            )
        }
    }
}
