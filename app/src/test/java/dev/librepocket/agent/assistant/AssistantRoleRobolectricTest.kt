package dev.librepocket.agent.assistant

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C1a／C1b Robolectric 測試（Issue #137）。
 *
 * - Intent 契約：session 喚起沿用 `ACTION_ASSIST`（保留現有入口，不新增）；
 *   fallback 走 `ACTION_VOICE_INPUT_SETTINGS`（C1 無新權限）。
 * - Manifest 接線：三 service（VoiceInteraction／Session／Recognition stub）
 *   在 main manifest 宣告、intent-filter 可被系統解析（三 flavor 共用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantRoleRobolectricTest {

    @Test
    fun voiceIntent_reusesAssistAction() {
        assertEquals(Intent.ACTION_ASSIST, assistantVoiceIntent().action)
    }

    @Test
    fun fallbackIntent_opensVoiceInputSettings() {
        assertEquals("android.settings.VOICE_INPUT_SETTINGS", assistantFallbackIntent().action)
    }

    @Test
    fun manifest_resolvesVoiceInteractionService() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val found = pm.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionService"),
            PackageManager.MATCH_ALL,
        )
        assertTrue(
            "main manifest 須宣告 LibreVoiceInteractionService",
            found.any { it.serviceInfo.name.endsWith(".assistant.LibreVoiceInteractionService") },
        )
    }

    @Test
    fun manifest_resolvesVoiceInteractionSessionService() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val found = pm.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionSessionService"),
            PackageManager.MATCH_ALL,
        )
        assertTrue(
            "main manifest 須宣告 LibreVoiceInteractionSessionService",
            found.any { it.serviceInfo.name.endsWith(".assistant.LibreVoiceInteractionSessionService") },
        )
    }

    @Test
    fun manifest_resolvesRecognitionServiceStub() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val found = pm.queryIntentServices(
            Intent("android.speech.RecognitionService"),
            PackageManager.MATCH_ALL,
        )
        assertTrue(
            "main manifest 須宣告 LibreRecognitionService stub",
            found.any { it.serviceInfo.name.endsWith(".assistant.LibreRecognitionService") },
        )
    }
}
