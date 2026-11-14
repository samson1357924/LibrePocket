package dev.librepocket.agent.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * - Manifest 零新增權限：合併後 manifest `requestedPermissions`
 *   鎖死安全集合（INTERNET／ACCESS_NETWORK_STATE／POST_NOTIFICATIONS／
 *   FOREGROUND_SERVICE／SET_ALARM + flavor 既有宣告），C2 不加 RECORD_AUDIO
 *   （主 manifest 並以 `tools:node="remove"` 防範 transitive AAR 帶入，
 *   RecognizerIntent 委派由系統辨識 Activity 錄音）。
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val requestedPermissions = packageInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(
            "C2 不新增錄音權限（RecognizerIntent 委派，見 AssistantVoice；合併後 manifest 不得含 RECORD_AUDIO）",
            Manifest.permission.RECORD_AUDIO in requestedPermissions,
        )
        val expectedSafePermissions = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            "com.android.alarm.permission.SET_ALARM",
        )
        assertTrue(
            "預期安全權限缺失：${expectedSafePermissions - requestedPermissions}",
            requestedPermissions.containsAll(expectedSafePermissions),
        )
        val allowedFlavorExtras = buildSet {
            add("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            if (context.packageName.endsWith(".github")) {
                add("moe.shizuku.manager.permission.API_V23")
            }
        }
        assertEquals(
            expectedSafePermissions + allowedFlavorExtras,
            requestedPermissions,
        )
    }

    @Test
    fun mainActivity_stillResolvesAssistWithFlag() {
        // flag 是 extra，不影響 intent-filter 解析：ASSIST 仍命中 MainActivity。
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val probe = AssistantVoice.withVoiceInput(Intent(Intent.ACTION_ASSIST))
            .setPackage(context.packageName)
        val found = pm.queryIntentActivities(
            probe,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertEquals(
            dev.librepocket.agent.MainActivity::class.java.name,
            found.single().activityInfo.name,
        )
    }

    @Test
    fun isVoiceInputRequested_rejectsNonAssistActionsEvenWithFlag() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_MAIN, Intent.ACTION_VIEW)) {
            val intent = Intent(action).putExtra(AssistantVoice.EXTRA_VOICE_INPUT, true)
            assertFalse(
                "non-ASSIST action $action must be rejected",
                AssistantVoice.isVoiceInputRequested(intent),
            )
        }
    }

    @Test
    fun isVoiceInputRequested_rejectsAssistLaunchedFromHistory() {
        val intent = Intent(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            .putExtra(AssistantVoice.EXTRA_VOICE_INPUT, true)
        assertFalse(AssistantVoice.isVoiceInputRequested(intent))
    }

    @Test
    fun manifest_declaresVoiceHandoffProviderLockedDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val providerInfo = context.packageManager.resolveContentProvider(
            "${context.packageName}.voice.handoff",
            0,
        )
        assertNotNull("voice handoff provider must be declared in the merged manifest", providerInfo)
        assertFalse("provider must stay app-private", providerInfo!!.exported)
        assertEquals("${context.packageName}.voice.handoff", providerInfo.authority)
        assertEquals(
            "provider must run in the app (main) process, not :voice",
            context.packageName,
            providerInfo.processName,
        )
    }

    // ---- manifest textual guards ----

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

    @Test
    fun manifest_recordAudioNodeRemove_isPresent() {
        val manifest = findSource(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        ).readText()
        assertTrue("manifest must mention RECORD_AUDIO (remove directive)", manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(
            "RECORD_AUDIO must be declared with tools:node=\"remove\" (transitive AAR guard)",
            Regex("RECORD_AUDIO[^>]*tools:node=\"remove\"").containsMatchIn(manifest),
        )
    }

    @Test
    fun isVoiceInputRequested_acceptsFreshAssistWithFlag() {
        val intent = Intent(Intent.ACTION_ASSIST)
            .putExtra(AssistantVoice.EXTRA_VOICE_INPUT, true)
        assertTrue(AssistantVoice.isVoiceInputRequested(intent))
    }
}
