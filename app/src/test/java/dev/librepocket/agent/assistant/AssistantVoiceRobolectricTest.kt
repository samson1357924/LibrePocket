package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C2 Robolectric 測試（Issue #137）。
 *
 * - Intent 契約：session 喚起在既有 ASSIST（限包→真 explicit）上掛
 *   voice flag；解析預設 false，null 安全。
 * - Manifest 零新增權限：主 manifest `uses-permission` 集合鎖死
 *   （INTERNET／ACCESS_NETWORK_STATE／POST_NOTIFICATIONS／
 *   FOREGROUND_SERVICE／SET_ALARM），C2 不加 RECORD_AUDIO
 *  （RecognizerIntent 委派由系統辨識 Activity 錄音）。
 * - MainActivity ASSIST 接收無損：flag 不影響既有 ASSIST 解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantVoiceRobolectricTest {

    @Test
    fun withVoiceInput_setsFlagWithoutClobbering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = assistantVoiceIntent(context)
        val flagged = AssistantVoice.withVoiceInput(base)
        assertEquals(Intent.ACTION_ASSIST, flagged.action)
        assertEquals(context.packageName, flagged.`package`)
        assertTrue(AssistantVoice.isVoiceInputRequested(flagged))
    }

    @Test
    fun voiceFlag_defaultsToFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(AssistantVoice.isVoiceInputRequested(assistantVoiceIntent(context)))
        assertFalse(AssistantVoice.isVoiceInputRequested(Intent(Intent.ACTION_ASSIST)))
        assertFalse(AssistantVoice.isVoiceInputRequested(null))
    }

    @Test
    fun sessionStyleExplicitIntent_carriesFlag() {
        // session 接線形狀：限包 implicit＋voice flag，再 setClass 轉真 explicit。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = AssistantVoice.withVoiceInput(assistantVoiceIntent(context))
            .setClass(context, dev.librepocket.agent.MainActivity::class.java)
        assertEquals(Intent.ACTION_ASSIST, intent.action)
        assertEquals(
            dev.librepocket.agent.MainActivity::class.java.name,
            intent.component?.className,
        )
        assertTrue(AssistantVoice.isVoiceInputRequested(intent))
    }

    @Test
    fun manifest_declaresNoRecordAudio() {
        val manifest = findSourceManifest(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        )
        assertFalse(
            "C2 不新增錄音權限（RecognizerIntent 委派，見 AssistantVoice）",
            manifest.contains("RECORD_AUDIO"),
        )
        val permissions = Regex(
            """<uses-permission android:name="([^"]+)"\s*/>""",
        ).findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.FOREGROUND_SERVICE",
                "com.android.alarm.permission.SET_ALARM",
            ),
            permissions,
        )
    }

    @Test
    fun mainActivity_stillResolvesAssistWithFlag() {
        // flag 是 extra，不影響 intent-filter 解析：ASSIST 仍命中 MainActivity。
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        val context = ApplicationProvider.getApplicationContext<Context>()
        val probe = AssistantVoice.withVoiceInput(Intent(Intent.ACTION_ASSIST))
            .setPackage(context.packageName)
        val found = pm.queryIntentActivities(
            probe,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(
            "ASSIST＋voice flag 仍應解析到 MainActivity",
            found.any { it.activityInfo.name.endsWith(".MainActivity") },
        )
    }

    /** 單測工作目錄（模組 dir）出發，向上走最多 5 層找原始碼/清單檔（同既有模式）。 */
    private fun findSourceManifest(vararg relPaths: String): String {
        val bases = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.take(6).toList()
        for (base in bases) {
            for (rel in relPaths) {
                val f = java.io.File(base, rel)
                if (f.isFile) return f.readText()
            }
        }
        error("not found: ${relPaths.toList()} under ${System.getProperty("user.dir")}")
    }
}
