package dev.librepocket.voice

import dev.librepocket.tool.DenyReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage2 語音揭露單測（純 JVM）：
 * - 引擎探測 [VoiceTts.hasEngine] fail-closed；
 * - 失敗話術 [VoiceTts.ttsFallbackMessage] 引用原因碼；
 * - 「本地引擎」已修正為系統引擎 + 引擎揭露文案一致；
 * - Stage4：ChatScreen/Settings 接線的原始碼字串釘選已逐個處置——有行為測試
 *   覆蓋者刪除（取代關係見下方「已刪除釘選」註記），僅無行為測試且難補者保留，
 *   且每處保留均有脆弱性說明；本檔不剩無說明的 `readText().contains`。
 *
 * 已刪除釘選（Stage4，皆有取代，行為測試或保留釘選，見下）：
 * - `chatScreen_noLongerClaimsLocalEngine` → 揭露文案語義由
 *   `speakButtonDescription_disclosesSystemEngine` 覆蓋，ChatScreen 渲染該常數
 *   的接線由保留的 `chatScreen_speakButtonUsesDisclosureCopy` 守住；
 *   另由 `chatScreen_hasNoLocalEngineClaim` 做檔級否定守衛；
 * - `speaker_failuresAreRoutedNotSilent` → 各 DETAIL 可觀察性由
 *   `VoiceSpeakerLifecycleTest` 的 `initFailure_` / `noEngine_` /
 *   `langUnavailable_` / `speakReturnError_` / `speakException_` /
 *   `utteranceError_` / `listenerReturnError_` / `listenerException_` 系列覆蓋，
 *   `AZURE_KEY_REF` 由 `AzureGateTest.azureKeyRef_isPinned` 覆蓋。
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

    @Test fun ttsLanguageWarning_isDowngradeNotFailure() {
        val msg = VoiceTts.ttsLanguageWarningMessage(DenyReason.NO_PRIVILEGE)
        assertTrue("msg=$msg", msg.contains("NO_PRIVILEGE"))
        assertTrue("msg=$msg", msg.contains(VoiceTts.DETAIL_LANG_UNAVAILABLE))
        assertTrue("msg=$msg", msg.contains(VoiceTts.LANG_FALLBACK_HINT))
        assertFalse("msg=$msg", msg.contains("做不到"))
    }

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

    // ---- 首次揭露確認（#2 行為，不釘原始碼字串） ----

    @Test fun unacknowledged_requiresInlineConfirmation() {
        assertTrue(VoiceTts.requiresTtsConfirmation(false))
    }

    @Test fun acknowledged_skipsConfirmation() {
        assertFalse(VoiceTts.requiresTtsConfirmation(true))
    }

    @Test fun confirmMessage_carriesEngineDisclosureSemantics() {
        val msg = VoiceTts.ttsConfirmMessage()
        assertTrue("msg=$msg", msg.contains(VoiceTts.ENGINE_DISCLOSURE))
        assertTrue("msg=$msg", msg.contains("朗讀"))
    }

    // ---- ChatScreen/Settings 接線（原始碼釘選，僅保留無行為測試且難補者） ----
    //
    // 脆弱性說明（以下各保留處皆同）：JVM 單測看不到 Compose 渲染結果，只能做字串
    // 包含檢查。重構（改名/搬檔/改寫法）易誤報，UI 斷線（如常數引用被手寫字串
    // 取代但恰好仍含關鍵字）易漏報。保留理由：補 Compose 行為測試成本超出
    // Stage4 範圍；刪除的釘選已在類註解列出取代關係。

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

    private fun manifest(): String = findSource(
        "src/main/AndroidManifest.xml",
        "app/src/main/AndroidManifest.xml",
    ).readText()

    @Test fun chatScreen_speakButtonUsesDisclosureCopy() {
        // 脆弱性：見本節說明。接線不斷言渲染，只確認朗讀鈕的 contentDescription
        // 引用共用常數而非手寫字串（釘完整賦值行，排除同名註解行的 nonce）；
        // 常數內容語義由純 formatter 測試 `speakButtonDescription_disclosesSystemEngine` 覆蓋。
        assertTrue(chatScreen().contains("contentDescription = VoiceTts.SPEAK_BUTTON_DESCRIPTION"))
    }

    @Test fun chatScreen_speakerFailureIsObservable() {
        // 脆弱性：見本節說明。ChatScreen 的 `onError → onTtsFailure` 接線無 Compose
        // 行為測試行使（狀態機與 speaker 發射各自由 VoiceErrorStateTest 與
        // VoiceSpeakerLifecycleTest 覆蓋），故保留此檔級守衛；刪掉接線時此測變紅。
        assertTrue(chatScreen().contains("onTtsFailure"))
    }

    @Test fun chatScreen_speakStaysSwitchGated() {
        // 脆弱性：見本節說明。ChatScreen 的開關門禁無 Compose 行為測試行使，
        // 故保留此檔級守衛（釘完整賦值行，單關鍵字守不住單點刪除）；刪掉門禁時此測變紅。
        assertTrue(chatScreen().contains("VoiceTools.outputEnabled"))
        assertTrue(chatScreen().contains("showSpeak = outputEnabled"))
    }

    @Test fun chatScreen_hasNoLocalEngineClaim() {
        // 脆弱性：見本節說明。檔級否定守衛：ChatScreen 不得出現「本地引擎」表述。
        assertFalse(chatScreen().contains("本地引擎"))
    }

    @Test fun chatScreen_addsNoAudioPermission() {
        // 脆弱性：見本節說明。否定式守衛（重構不易誤報）；肯定式權限不變量由
        // `AzureGateTest.voiceTools_haveNoPermissionLiterals` 在 ToolRegistry 側覆蓋，
        // 此處補 ChatScreen 檔級守衛（STT 走 RecognizerIntent 委託，不應出現錄音權限字樣）。
        assertFalse(chatScreen().contains("RECORD_AUDIO"))
    }

    @Test fun settings_voiceCardDisclosesEngineDependence() {
        // 脆弱性：見本節說明。設定頁語音卡為手寫文案（未引用 ENGINE_DISCLOSURE 常數），
        // 只能釘選關鍵字；文案語義變更需同步更新此測（屬已知脆弱，非行為保證）。
        val settings = findSource(
            "src/main/java/dev/librepocket/agent/ui/settings/SettingsScreen.kt",
            "app/src/main/java/dev/librepocket/agent/ui/settings/SettingsScreen.kt",
        ).readText()
        assertTrue(settings.contains("取決於"))
        assertFalse(settings.contains("本地引擎"))
    }

    @Test fun manifestDeclaresTtsServiceVisibility() {
        // Stage1 manifest 修復的對應測試：TTS_SERVICE 可見性供 getEngines() 引擎探測；
        // targetSdk 36 package-visibility 過濾下若缺此宣告，引擎表可能為空而被誤判 NO_ENGINE。
        // 脆弱性：JVM 單測看不到合併後 manifest 與 PackageManager 行為，只能釘選
        // 原始碼清單字串（與 `VoiceSttTest.manifestQueriesAction_isRecognizeSpeech` 同模式）；
        // 真正的可見性行為需裝置實證。
        assertTrue(
            manifest().contains("<action android:name=\"android.intent.action.TTS_SERVICE\" />"),
        )
    }
}
