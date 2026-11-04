package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 *
 * 測試策略註記（Phase 4 review WARN 回填）：
 * - 單 flavor 足夠：三 service 宣告於 main manifest（`src/main/AndroidManifest.xml`），
 *   foss／github／play 共用同一接線；`testFossDebugUnitTest` 即代表全體，
 *   無需三 flavor 各跑一次。
 * - minSdk 33（見 app/build.gradle.kts）故無需 API<29 guard：
 *   `MATCH_ALL` 在舊版語義不同，但本專案下限即 33，`@Config(sdk = [33])`
 *   已是支援矩陣下限。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantRoleRobolectricTest {

    @Test
    fun voiceIntent_reusesAssistAction() {
        assertEquals(Intent.ACTION_ASSIST, assistantVoiceIntent().action)
    }

    /** 無參版隱式契約：action 外，component 必須為 null 且不帶 extras（防誤掛參數被 session 誤讀）。 */
    @Test
    fun voiceIntent_implicitContract_noComponentNoExtras() {
        val intent = assistantVoiceIntent()
        assertEquals(Intent.ACTION_ASSIST, intent.action)
        assertNull(intent.component)
        assertTrue(
            "無參 voiceIntent 不應帶 extras",
            intent.extras == null || intent.extras!!.isEmpty,
        )
    }

    /** 有參版契約：action 維持 ASSIST，且以呼叫方包名限包（防第三方助理應用劫持）。 */
    @Test
    fun voiceIntent_withContext_targetsOwnPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = assistantVoiceIntent(context)
        assertEquals(Intent.ACTION_ASSIST, intent.action)
        assertEquals(context.packageName, intent.`package`)
    }

    @Test
    fun fallbackIntent_opensVoiceInputSettings() {
        // 用常數比對而非硬編碼字串：字串改名時由編譯器把關。
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, assistantFallbackIntent().action)
    }

    /** 工廠不帶 NEW_TASK flag：flag 由調用方（applicationContext＋無條件 NEW_TASK）負責，見 AssistantRoleUi。 */
    @Test
    fun fallbackIntent_carriesNoNewTaskFlag() {
        val flags = assistantFallbackIntent().flags
        assertTrue(
            "fallback 工廠不應自帶 FLAG_ACTIVITY_NEW_TASK",
            flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0,
        )
    }

    @Test
    fun manifest_resolvesVoiceInteractionService() {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        val found = pm.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionService"),
            PackageManager.MATCH_ALL,
        )
        // 全限定名精確斷言＋size==1：避免 endsWith 誤判別家同名 service，且鎖定無重複宣告。
        assertEquals(1, found.size)
        assertEquals(
            "dev.librepocket.agent.assistant.LibreVoiceInteractionService",
            found.single().serviceInfo.name,
        )
    }

    @Test
    fun manifest_resolvesVoiceInteractionSessionService() {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        val found = pm.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionSessionService"),
            PackageManager.MATCH_ALL,
        )
        assertEquals(1, found.size)
        assertEquals(
            "dev.librepocket.agent.assistant.LibreVoiceInteractionSessionService",
            found.single().serviceInfo.name,
        )
    }

    @Test
    fun manifest_resolvesRecognitionServiceStub() {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        val found = pm.queryIntentServices(
            Intent("android.speech.RecognitionService"),
            PackageManager.MATCH_ALL,
        )
        assertEquals(1, found.size)
        assertEquals(
            "dev.librepocket.agent.assistant.LibreRecognitionService",
            found.single().serviceInfo.name,
        )
    }
}
