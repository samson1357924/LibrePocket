package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
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

    /** Stage 2 finding C：RoleManager 缺失 → Unsupported（與讀取失敗可區分）。 */
    @Test
    fun snapshotOf_nullRoleManager_isUnsupported() {
        assertEquals(AssistantSnapshot.Unsupported, snapshotOf(null))
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

    /**
     * Stage 3 :voice process 斷言：三 service 皆跑在獨立 ":voice" process（見 main
     * manifest；manifest 寫相對名 ":voice"，讀出為 "<package>:voice"，故斷言
     * endWith ":voice"）。Robolectric 讀 serviceInfo.processName；若 Robolectric
     * 不解析該欄位（null），此測即紅，屆時改跳過並註明——先不斷言跳過，直接驗證。
     */
    @Test
    fun manifest_voiceServices_runInVoiceProcess() {
        val pm = ApplicationProvider.getApplicationContext<Context>().packageManager
        val actions = listOf(
            "android.service.voice.VoiceInteractionService",
            "android.service.voice.VoiceInteractionSessionService",
            "android.speech.RecognitionService",
        )
        actions.forEach { action ->
            val found = pm.queryIntentServices(
                Intent(action),
                PackageManager.MATCH_ALL,
            )
            assertEquals("$action 應恰解析一個 service", 1, found.size)
            val processName: String? = found.single().serviceInfo.processName
            assertTrue(
                "$action process 應為 :voice（實際=$processName）",
                processName == ":voice" || (processName != null && processName.endsWith(":voice")),
            )
        }
    }

    /**
     * S1 queries 可見性契約：主 manifest `<queries>` 必須宣告
     * `VOICE_INPUT_SETTINGS`，否則 `openFallback` 的
     * `queryIntentActivities` 在 targetSdk 36 下被 package-visibility 過濾，
     * 候選為空而永遠 fallbackFailed（見 AssistantRoleUi）。
     *
     * 測試策略註記：Robolectric 不強制 package-visibility 過濾
     * （`queryIntentActivities` 在單測環境不經 queries 白名單裁剪），故此測
     * 直接斷言原始碼 manifest 的 `<queries>` 區段內容（同 VoiceSttTest
     * `manifestQueriesAction_isRecognizeSpeech` 模式），以檔案內容＋程式碼
     * 審查為準，而非執行期可見性行為。
     */
    @Test
    fun manifestQueries_declaresVoiceInputSettings() {
        val manifest = findSourceManifest(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        )
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(
            "主 manifest <queries> 必須宣告 VOICE_INPUT_SETTINGS" +
                "（供 queryIntentActivities 可見性，非 resolveActivity）",
            queries.contains("<action android:name=\"android.settings.VOICE_INPUT_SETTINGS\" />"),
        )
    }

    /**
     * 生產探測契約：`openFallback` 必須走 `queryIntentActivities` 全枚舉＋
     * `setComponent` explicit（見 AssistantRoleUi）：`resolveActivity` 在
     * 多候選無預設時回傳系統 Resolver 本身，`FLAG_SYSTEM` 檢查會誤判放行
     * 第三方（同 ChatScreenVoiceLaunchTest
     * `recognizerWithoutDefaultCategory_degradesToNoPrivilege_initialVoice` 覆蓋的
     * MATCH_DEFAULT_ONLY 探測模式，以商品行為＋審查為準）。
     */
    @Test
    fun fallbackProbe_usesQueryIntentActivities() {
        val ui = findSourceManifest(
            "src/main/java/dev/librepocket/agent/assistant/AssistantRoleUi.kt",
            "app/src/main/java/dev/librepocket/agent/assistant/AssistantRoleUi.kt",
        )
        assertTrue(
            "openFallback 必須以 queryIntentActivities 全枚舉候選",
            ui.contains("queryIntentActivities"),
        )
    }

    /**
     * LibreRecognitionService stub 誠實性（fail-closed）釘住（誠實無能力、非待實作佔位）：
     * - [LibreRecognitionService.onStartListening] → listener 恰收到一次
     *   [SpeechRecognizer.ERROR_CLIENT]，其餘任何回呼（含 partial results）皆不得發生；
     * - [LibreRecognitionService.onCheckRecognitionSupport]（雙參與三參兩種 overload）→
     *   回報空語言清單（零支援），不偽造能力；error path 不得觸發；
     * - onCancel / onStopListening 為無狀態 no-op（不拋例外）。
     *
     * 說明：`RecognitionService.Callback` 與 `SupportCallback` 的建構子在 compile-time
     * stub android.jar 皆為 package-private，`object : Callback()` 無法編譯；故以
     * reflection 在 Robolectric sandbox 內以 private ctor 建立（instrumented 版提供
     * `(RecognitionService, IRecognitionListener, AttributionSource)` 私有 ctor；
     * `SupportCallback(IRecognitionSupportCallback)` 私有 ctor），並以 dynamic proxy
     * 實作 IRecognitionListener / IRecognitionSupportCallback 記錄呼叫；SDK 33 釘住
     * （與本類其他測試一致）。service 的 protected 覆寫方法經 reflection invoke。
     */
    @Test
    fun recognitionServiceStub_isHonestFailClosed() {
        val service = LibreRecognitionService()
        val loader = RecognitionService::class.java.classLoader!!
        val callbackClass = Class.forName("android.speech.RecognitionService${'$'}Callback", true, loader)
        val listenerInterface = Class.forName("android.speech.IRecognitionListener", true, loader)
        val supportCallbackClass = Class.forName("android.speech.RecognitionService${'$'}SupportCallback", true, loader)
        val supportInterface = Class.forName("android.speech.IRecognitionSupportCallback", true, loader)

        // --- onStartListening：listener 恰收到一次 ERROR_CLIENT，無 partial results ---
        val listenerCalls = mutableListOf<Pair<String, Int?>>()
        val listenerProxy = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(listenerInterface)) { _, method, args ->
            listenerCalls += method.name to (args?.getOrNull(0) as? Int)
            proxyDefault(method.returnType)
        }
        val callbackCtor = callbackClass
            .getDeclaredConstructor(RecognitionService::class.java, listenerInterface, android.content.AttributionSource::class.java)
            .also { it.isAccessible = true }
        val listenerCallback = callbackCtor.newInstance(service, listenerProxy, null) as RecognitionService.Callback
        invokeStub(service, "onStartListening", Intent(), listenerCallback)
        assertEquals(
            listOf("onError" to SpeechRecognizer.ERROR_CLIENT),
            listenerCalls,
        )

        // --- onCheckRecognitionSupport：空語言清單（零支援），雙參與三參皆然 ---
        val supportCalls = mutableListOf<Pair<String, Any?>>()
        val supportProxy = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(supportInterface)) { _, method, args ->
            supportCalls += method.name to args?.getOrNull(0)
            proxyDefault(method.returnType)
        }
        val supportCtor = supportCallbackClass
            .getDeclaredConstructor(supportInterface)
            .also { it.isAccessible = true }
        val supportCallback = supportCtor.newInstance(supportProxy)

        supportCalls.clear()
        invokeStub(service, "onCheckRecognitionSupport", Intent(), supportCallback)
        assertZeroSupport(supportCalls)

        supportCalls.clear()
        invokeStub(
            service,
            "onCheckRecognitionSupport",
            Intent(),
            android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("dev.librepocket.test").build(),
            supportCallback,
        )
        assertZeroSupport(supportCalls)

        // --- onCancel / onStopListening：無狀態 no-op（不拋例外，listener 零回呼） ---
        listenerCalls.clear() // onStartListening 已斷言過其單次 onError；此處歸零後驗證兩個 no-op 零回呼
        invokeStub(service, "onCancel", listenerCallback)
        invokeStub(service, "onStopListening", listenerCallback)
        assertTrue(listenerCalls.isEmpty())
    }

    /** 記錄型 SupportCallback 的零支援斷言：恰收到一次 onSupportResult、空語言清單、無 onError。 */
    private fun assertZeroSupport(supportCalls: List<Pair<String, Any?>>) {
        assertTrue("must report zero support, not an error: $supportCalls", supportCalls.none { it.first == "onError" })
        val call = supportCalls.singleOrNull { it.first == "onSupportResult" }
            ?: throw AssertionError("onCheckRecognitionSupport must invoke onSupportResult exactly once; calls=$supportCalls")
        val support = call.second as android.speech.RecognitionSupport
        assertEquals(emptyList<String>(), support.getInstalledOnDeviceLanguages())
        assertEquals(emptyList<String>(), support.getSupportedOnDeviceLanguages())
        assertEquals(emptyList<String>(), support.getPendingOnDeviceLanguages())
        assertEquals(emptyList<String>(), support.getOnlineLanguages())
    }

    /** proxy 回傳預設值（IRecognitionListener / IRecognitionSupportCallback 方法皆 void；其餘回傳 null/0）。 */
    private fun proxyDefault(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    /** 以 reflection invoke LibreRecognitionService 的 protected 覆寫方法（compile-time 不可直接存取）。 */
    private fun invokeStub(service: LibreRecognitionService, name: String, vararg args: Any?) {
        val params = args.map { requireNotNull(it) { "invokeStub args must be non-null" }::class.java }.toTypedArray()
        val m = LibreRecognitionService::class.java.getDeclaredMethod(name, *params)
        m.isAccessible = true
        m.invoke(service, *args)
    }

    /** 單測工作目錄（模組 dir）出發，向上走最多 5 層找原始碼/清單檔（同 VoiceSttTest 模式）。 */
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
