package dev.librepocket.foss

import dev.librepocket.hardening.HardeningPolicy
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import dev.librepocket.voice.VoiceTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2 foss 語音合規（foss 風味單測）：
 * - FOSS_STRING_BLACKLIST 鎖定 Azure 針（含 ML Kit / GMS 同步）；
 * - `voice.speak.azure` 在 foss 為 FLAVOR_BLOCKED；
 * - 系統語音（transcribe/speak）在 foss 預設可見。
 */
class FossVoiceComplianceTest {

    @Test fun fossBlacklist_containsAzureNeedle() {
        assertTrue(
            HardeningPolicy.FOSS_STRING_BLACKLIST.contains("com.microsoft.cognitiveservices.speech"),
        )
        assertTrue(HardeningPolicy.FOSS_STRING_BLACKLIST.contains("com.google.mlkit"))
        assertTrue(HardeningPolicy.FOSS_STRING_BLACKLIST.contains("com.google.android.gms"))
    }

    @Test fun azureDescriptor_flaggedInFoss() {
        val violations = HardeningPolicy.checkFossArtifact(
            classDescriptors = listOf("Lcom/microsoft/cognitiveservices/speech/SpeechConfig;"),
        )
        assertEquals(1, violations.size)
    }

    @Test fun azureHiddenOnFoss() {
        val ctx = ProjectionContext(
            flavor = Flavor.FOSS,
            userSwitches = mapOf(VoiceTools.SWITCH_AZURE to true),
        )
        val projected = ToolRegistry.projectAll(ctx)[VoiceTools.AZURE_SPEAK_NAME]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, projected.level)
        assertEquals(DenyReason.FLAVOR_BLOCKED, projected.reason)
    }

    @Test fun systemVoiceVisibleOnFossByDefault() {
        val ctx = ProjectionContext(flavor = Flavor.FOSS)
        assertEquals(
            CapabilityLevel.NATIVE,
            ToolRegistry.projectAll(ctx)[VoiceTools.TRANSCRIBE_NAME]!!.level,
        )
        assertEquals(
            CapabilityLevel.NATIVE,
            ToolRegistry.projectAll(ctx)[VoiceTools.SPEAK_NAME]!!.level,
        )
    }
}
