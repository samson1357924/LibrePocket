package dev.librepocket.agent.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * C1 系統預設助理：RoleManager 流程純邏輯（Issue #137 C1b）。
 *
 * - 本檔含平台無關決策（快照 → 決策／原因碼）＋三態快照讀取
 *  （[snapshotOf] 直接讀 [android.app.role.RoleManager]，異常→ReadError），
 *   生產接線見 AssistantRoleUi，純邏輯 JVM 單測為主，
 *   intent 契約另見 Robolectric。
 * - C1 不新增任何權限：`isRoleAvailable`／`isRoleHeld`／
 *   `createRequestRoleIntent` 皆為同步呼叫；失敗一律 fallback
 *   [Settings.ACTION_VOICE_INPUT_SETTINGS]，fail-closed，不靜默退回。
 * - 原因碼（[assistantReasonText]）：被拒／撤銷／OEM 不支援／請求失敗
 *   皆有對應文案，UI 只顯示文案不做流程分支，色彩可按失敗/中性區分（見 SettingsScreen）。
 * - Stage 2 B＋C：快照三態（[AssistantSnapshot]）＋驗證中狀態
 *   （[AssistantReasonCode.VERIFYING]＋重試收斂 [consumePendingVerify]／
 *   [VERIFY_MAX_ATTEMPTS]）：讀取異常與真不支援不再混同；
 *   撤銷只在「曾確認持有」＋當前 Known(!held)＋非驗證中判定；驗證中一律
 *   不判撤銷、不宣稱授予（re-review #3 finding #1 [P2]：驗證代幣被消耗≠
 *   觀察到新系統真相；RESULT_OK 後未觀察 held 即判 REVOKED 是不誠實斷言）。
 */
data class AssistantRoleSnapshot(
    val roleAvailable: Boolean,
    val roleHeld: Boolean,
)

/**
 * 三態快照（Stage 2 finding C [P2]）。
 *
 * - [Known]：讀取成功，`available`／`held` 如實回報（舊
 *   [AssistantRoleSnapshot] 語義；決策／調和只在此態做持有／撤銷判定）。
 * - [Unsupported]：`RoleManager == null`（OEM 無此服務）。fail-closed：
 *   [decideAssistantRole] 回 Fallback，不發要求。
 * - [ReadError]：讀取拋異常（暫時性失敗，可能是系統抖動）。
 *   fail-closed：決策同 Fallback（不發要求），但 UI 頭部必須與
 *   「不支援」區分（「無法確認助理狀態，請稍後重試」），且調和一律
 *   passthrough（不判撤銷、不宣稱持有）。
 *
 * 舊 [AssistantRoleSnapshot] 保留相容：`readAssistantSnapshot`（見
 * AssistantRoleUi）仍回傳它（未知態一律折疊為全 false，
 * 與真不支援不可區分——遺留行為，新產線一律用 [snapshotOf]）。
 */
sealed interface AssistantSnapshot {
    data class Known(val available: Boolean, val held: Boolean) : AssistantSnapshot
    data object Unsupported : AssistantSnapshot
    data object ReadError : AssistantSnapshot
}

/**
 * 同步重讀三態快照。
 *
 * - `null` → [AssistantSnapshot.Unsupported]；拋異常 → [AssistantSnapshot.ReadError]
 *   （不折疊為全 false，與真不支援可區分；刻意不打 Log，保持本檔純邏輯
 *   可在 JVM 單測編譯，呼叫方需記錄時自行在 catch 處打點）。
 * - 本函數需 [RoleManager] 實例，純 JVM 單測不直接呼叫（以 fake
 *   [AssistantSnapshot.Known] 測決策／調和）；有機覆蓋另見 Robolectric。
 */
fun snapshotOf(roleManager: RoleManager?): AssistantSnapshot {
    if (roleManager == null) return AssistantSnapshot.Unsupported
    return try {
        AssistantSnapshot.Known(
            available = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT),
            held = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT),
        )
    } catch (_: Exception) {
        AssistantSnapshot.ReadError
    }
}

/** Settings 卡中立狀態（仿 AutomationSettingsState：null 即整卡隱藏）。 */
data class AssistantSettingsState(
    val roleHeld: Boolean,
    val roleAvailable: Boolean,
    val reasonCode: String? = null,
    /** fallback 設定頁打不開（resolve null／非系統／拋異常）：卡內紅字提示，不跳轉。 */
    val fallbackFailed: Boolean = false,
    /**
     * 快照讀取失敗（[AssistantSnapshot.ReadError]，Stage 2 finding C）。
     * true 時 `roleHeld`／`roleAvailable` 皆為 false（不宣稱任何狀態）；
     * SettingsScreen 頭部顯示「無法確認助理狀態，請稍後重試」、
     * 主按鈕 disabled（fail-closed），原因行照常 passthrough。
     * 與真不支援（false／「此裝置不支援…」）區分，預設 false 保舊構造相容。
     */
    val readError: Boolean = false,
)

sealed interface AssistantRoleDecision {
    /** 已持有：長按首頁／手勢可叫出，無需再要求。 */
    data object AlreadyHeld : AssistantRoleDecision

    /** 可要求：Settings 一鍵 `createRequestRoleIntent(ROLE_ASSISTANT)`。 */
    data object RequestRole : AssistantRoleDecision

    /** OEM 不支援：不發要求，直接 fallback 語音輸入設定。 */
    data object UnsupportedFallback : AssistantRoleDecision
}

fun decideAssistantRole(snapshot: AssistantRoleSnapshot): AssistantRoleDecision =
    when {
        snapshot.roleHeld -> AssistantRoleDecision.AlreadyHeld
        snapshot.roleAvailable -> AssistantRoleDecision.RequestRole
        else -> AssistantRoleDecision.UnsupportedFallback
    }

/**
 * 三態決策（Stage 2 finding C）。
 *
 * - [AssistantSnapshot.Known] → 轉調舊版（held／available 語義不變）。
 * - [AssistantSnapshot.Unsupported]／[AssistantSnapshot.ReadError] →
 *   [AssistantRoleDecision.UnsupportedFallback]（fail-closed，不發要求）。
 *   兩者決策同值但 UI 文案必須區分：Unsupported 頭部「此裝置不支援…」、
 *   ReadError 頭部「無法確認助理狀態，請稍後重試」（見 [AssistantSettingsState.readError]
 *   與 SettingsScreen），且 request() 入口對 ReadError 不設 UNSUPPORTED 碼、
 *   不開 fallback（純 no-op，按钮本就 disabled，防禦性 fail-closed）。
 */
fun decideAssistantRole(snapshot: AssistantSnapshot): AssistantRoleDecision =
    when (snapshot) {
        is AssistantSnapshot.Known ->
            decideAssistantRole(
                AssistantRoleSnapshot(
                    roleAvailable = snapshot.available,
                    roleHeld = snapshot.held,
                ),
            )
        AssistantSnapshot.Unsupported,
        AssistantSnapshot.ReadError,
        -> AssistantRoleDecision.UnsupportedFallback
    }

object AssistantReasonCode {
    const val GRANTED = "ASSISTANT_GRANTED"
    const val DENIED = "ASSISTANT_DENIED"
    const val REVOKED = "ASSISTANT_REVOKED"
    const val UNSUPPORTED = "ASSISTANT_UNSUPPORTED"
    const val REQUEST_FAILED = "ASSISTANT_REQUEST_FAILED"

    /**
     * 驗證中（re-review #3 finding #1 [P2]）。
     *
     * `RESULT_OK` 只代表要求被接受，不代表系統已落盤；此碼由
     * [effectiveAssistantReason] 在 `pendingVerify + Known(!held)` 時合成，
     * 永不寫入 `stored`（不落盤、不 sticky），UI 顯示中性文案
     * （見 [assistantReasonText]），色彩中性（見 SettingsScreen）。
     */
    const val VERIFYING = "ASSISTANT_VERIFYING"
}

/**
 * 雙向調和（純函數）：快照持有態優先於一切原因碼。
 *
 * 三態版（Stage 2 finding B＋C；本函數為主入口；re-review #3 finding #1 [P2]
 * 語義修正：驗證中 VERIFYING＋重試超時收斂）：
 * - 若 `snapshot` 是 [AssistantSnapshot.Known] 且 `held == true`，直接回 null
 *   （清除原因碼）：頭部已表達持有態，原因區不再重複顯示（避免 DENIED＋held
 *   矛盾、GRANTED＋held 重複文案）。held 確認同時是驗證結束信號
 *   （見 [consumePendingVerify]）。
 * - 否則（Known 且非持有）若 `pendingVerify == true` 且 `stored` 為 null 或
 *   [AssistantReasonCode.GRANTED]，回 [AssistantReasonCode.VERIFYING]：
 *   `RESULT_OK` 後系統落盤有延遲，重讀快照仍非持有是預期競態；驗證中此幀
 *   既不宣稱授予、亦不判撤銷（官方語義：RESULT_OK 代表已授予，不能用同一個
 *   早期 false 快照斷言撤銷）。其他新鮮碼（DENIED／UNSUPPORTED／
 *   REQUEST_FAILED／REVOKED）照常 passthrough——調和不掩蓋新鮮判定
 *   （新流程下驗證中 `stored` 恆為 null，此為防禦性分支）。
 * - 否則（Known 且非持有、非驗證中）若 `stored == GRANTED`，覆寫為
 *   [AssistantReasonCode.REVOKED]。新流程下 `RESULT_OK` 回調不再寫 GRANTED
 *   （只置 `stored=null`＋`pendingVerify=true`，見 AssistantRoleUi），GRANTED
 *   sticky 只源於 AlreadyHeld 路徑（快照已 held 前提下寫入，隨即被 held 規則
 *   清除）；故此處 GRANTED＋Known(!held) 意味「曾確認持有、現已非持有」，
 *   是 genuine 撤銷證據——舊「RESULT_OK 後從未觀察 held 即判撤銷」的不誠實
 *   分支已消除（該路徑超時收斂為 null，見下）。
 * - 否則（Known 且非持有、非驗證中）若 `stored == null && everHeld == true`，
 *   回 [AssistantReasonCode.REVOKED]：外部授予→撤銷鏈路（使用者曾在系統設定頁
 *   手動授予，未經本應用回調故 `stored` 仍 null，之後又在系統側被撤銷；須以
 *   `everHeld` 曾觀察 held 即 sticky 補上判定）。僅 stored null 時用 everHeld
 *   判撤銷；顯式新鮮碼（DENIED／UNSUPPORTED／REQUEST_FAILED／REVOKED）
 *   一律 passthrough，不被 everHeld 覆寫。
 * - 其餘（含 ReadError／Unsupported 任何 stored／everHeld／pendingVerify 組合）
 *   原樣 passthrough：讀不到快照時不判撤銷（誠實：未知≠已撤銷）、不顯示持有、
 *   驗證中亦 passthrough（未知態不憑空造 VERIFYING——Stage 2 #2 另有遮罩，
 *   本 stage 保持 passthrough）。副作用：Unsupported／ReadError 下既有 GRANTED
 *   sticky 會原樣保留顯示（passthrough），而非覆寫為 REVOKED——快照不可讀時
 *   不斷案。
 * - 超時收斂（重試耗盡，`pendingVerify` 被置 false 後按正常規則走）：
 *   `stored=null`＋`!everHeld`＋Known(!held) → null（中性 idle「尚未設為…」，
 *   誠實：系統從未確認持有，不斷言撤銷）；`stored=null`＋`everHeld` → REVOKED。
 *
 * `everHeld` 由 SharedPreferences（`"assistant_role"`／`"ever_held"`）持久化，
 * process death 後仍能判定外部撤銷；`pendingVerify` 實為 UI 層驗證中旗標
 * （remember 僅記憶體，不落盤，process death 丟失可接受，重進後按正常規則
 * 重讀判定，不殘留過期狀態）。
 *
 * 已知競態收斂：`RESULT_OK` 後系統未即時落盤時，重讀快照仍非持有會顯示
 * VERIFYING；重試排程（`LaunchedEffect(pendingVerify, verifyAttempt)`＋
 * `VERIFY_RETRY_DELAY_MS` 延遲＋`refreshTick++`，上限 [VERIFY_MAX_ATTEMPTS]
 * 次）驅動後續重讀，held 確認即結束（null）、超時即收斂（上條）；ON_RESUME
 * 重讀提供額外新鮮快照但不重啟排程。消耗點只與「可觀測的快照讀取＋重試計數」
 * 有關，與回調／resume 相對時序無關（見 [consumePendingVerify] KDoc 兩種時序）。
 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantSnapshot,
    everHeld: Boolean,
    pendingVerify: Boolean,
): String? {
    val known = snapshot as? AssistantSnapshot.Known ?: return stored
    return if (known.held) {
        null
    } else if (pendingVerify &&
        (stored == null || stored == AssistantReasonCode.GRANTED)
    ) {
        AssistantReasonCode.VERIFYING
    } else if (stored == AssistantReasonCode.GRANTED) {
        AssistantReasonCode.REVOKED
    } else if (stored == null && everHeld) {
        AssistantReasonCode.REVOKED
    } else {
        stored
    }
}

/**
 * 舊四參相容 overload：轉調三態版（`Known(available, held)`，語義不變；
 * 既有測試全數保留）。
 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantRoleSnapshot,
    everHeld: Boolean,
    pendingVerify: Boolean,
): String? =
    effectiveAssistantReason(
        stored,
        AssistantSnapshot.Known(
            available = snapshot.roleAvailable,
            held = snapshot.roleHeld,
        ),
        everHeld,
        pendingVerify,
    )

/** 雙參相容 overload：轉調四參版（`everHeld=false, pendingVerify=false`，舊測語義不變）。 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantRoleSnapshot,
): String? =
    effectiveAssistantReason(stored, snapshot, everHeld = false, pendingVerify = false)

/** 三態雙參便利 overload（`everHeld=false, pendingVerify=false`）。 */
fun effectiveAssistantReason(
    stored: String?,
    snapshot: AssistantSnapshot,
): String? =
    effectiveAssistantReason(stored, snapshot, everHeld = false, pendingVerify = false)

/**
 * 單次驗證重讀上限（re-review #3 finding #1 [P2]）。
 *
 * `RESULT_OK` 回調 tick 觸發的即時重讀不計數；之後每次排程重讀計 1 次
 * （見 AssistantRoleUi `LaunchedEffect`）。耗盡仍 Known(!held) 即超時收斂
 * （`pendingVerify=false`，按正常規則走：`everHeld?REVOKED:中性 idle`）。
 */
const val VERIFY_MAX_ATTEMPTS = 3

/**
 * 驗證重讀間隔（毫秒）。
 *
 * 系統側 `ROLE_ASSISTANT` 落盤延遲通常遠小於此×上限（約 3.6s 窗口）；
 * 調大延長確認 latency、調小增加無謂重讀，改動需同步更新 KDoc 與測試。
 */
const val VERIFY_RETRY_DELAY_MS = 1200L

/**
 * 驗證結束判定（Stage 2 finding B [P2]，純函數；UI 在 `SideEffect` 內呼叫，
 * 重試排程見 AssistantRoleUi `LaunchedEffect(pendingVerify, verifyAttempt)`）。
 *
 * 語義（`pendingVerify == true`＝驗證中）：
 * - 本幀快照是 [AssistantSnapshot.Known] 且 `held` → 回 false（確認持有，
 *   驗證結束；調和同步清原因為 null）。
 * - Known 且 `!held` → 僅當 `verifyAttempt >= [VERIFY_MAX_ATTEMPTS]` 回 false
 *   （超時收斂：不再自稱驗證中，按正常規則走）；次數未滿回 true（保留，
 *   等待排程的下一次重讀——驗證中調和回 VERIFYING，不判撤銷）。
 * - ReadError／Unsupported → 回 true（保留到下一次 Known 讀取；調和在未知態
 *   本就 passthrough，無掩蓋風險；排程照常重試，系統服務恢復後仍可確認）。
 * - `pendingVerify == false` → 回 false（非驗證中無事可做）。
 *
 * 時序收斂（關鍵：結束點是「可觀測的快照讀取＋重試計數」，與回調／resume
 * 相對順序無關）：
 * - callback-after-resume：resume 先 `tick++` 重讀（非驗證中，無事發生）；
 *   回調後設 pending＋`attempt=0`＋`tick++`，首幀即 VERIFYING，隨後排程重讀
 *   （attempt 遞增）直至 held 確認或超時。
 * - callback-before-resume：回調先設 pending＋`tick++`（首幀 VERIFYING）；
 *   緊接 ON_RESUME 再 `tick++` 提供一次額外新鮮讀取（不計 attempt、不重啟
 *   排程 delay，只提前給出真相：若已 held，SideEffect 當幀即結束驗證）。
 *   兩種交錯皆收斂：至多 [VERIFY_MAX_ATTEMPTS] 次排程重讀（＋resume 額外讀）
 *   後必結束驗證（未知態更不掩蓋：直接 passthrough）。
 *
 * 呼叫方契約（見 AssistantRoleUi）：
 * `SideEffect { pendingVerify = consumePendingVerify(pendingVerify, snapshot,
 * verifyAttempt) }`；ON_RESUME 只 `tick++` 不清旗、不計數；request 入口
 * （新一輪開始）清旗＋`attempt=0`；`RESULT_OK` 置 true＋`attempt=0`、
 * 非 OK 置 false。`pendingVerify`／`verifyAttempt` 僅 `remember`（記憶體），
 * process death／旋轉丟失可接受（重進按正常規則判定，不殘留過期驗證中）。
 */
fun consumePendingVerify(
    pendingVerify: Boolean,
    snapshot: AssistantSnapshot,
    verifyAttempt: Int,
): Boolean {
    if (!pendingVerify) return false
    val known = snapshot as? AssistantSnapshot.Known ?: return true
    if (known.held) return false
    return verifyAttempt < VERIFY_MAX_ATTEMPTS
}

/** 原因碼 → 顯示文案（UI 只顯示文案不做流程分支，色彩可按失敗/中性區分（見 SettingsScreen）；未知碼原樣回報，不偽造語義）。
 *
 * 硬編碼說明（PocketGuard 建議）：本 PR 不做全量 strings.xml 遷移（SettingsScreen
 * 全檔既有硬編碼＋strings.xml 僅 app_name，專案級重構不宜在本 PR）。
 * TODO(i18n-followup): 集中遷移＋純函數改 mapper 注入（原因碼→文案改由呼叫方注入 mapper，本檔保持純邏輯可測）。
 */
fun assistantReasonText(code: String): String =
    when (code) {
        AssistantReasonCode.GRANTED ->
            "已設為預設助理：長按首頁鍵或系統手勢可叫出 LibrePocket。"
        AssistantReasonCode.DENIED ->
            "要求被拒：維持現狀，可改用語音輸入設定或稍後重試。"
        AssistantReasonCode.REVOKED ->
            "預設助理角色已被撤銷：可重新要求，或改用語音輸入設定。"
        AssistantReasonCode.UNSUPPORTED ->
            "此裝置不支援系統助理角色：已改用語音輸入設定。"
        AssistantReasonCode.REQUEST_FAILED ->
            "無法開啟系統授權頁：已改用語音輸入設定。"
        AssistantReasonCode.VERIFYING ->
            "要求已送出，正在向系統確認角色狀態…"
        else -> "未知狀態（$code）。"
    }

/**
 * C1 session 喚起入口 Intent（Issue #137：保留現有 ASSIST／SEND，不新增）。
 *
 * `LibreVoiceInteractionSession.onShow` 在此基礎上掛 MainActivity component
 * 後經 `startVoiceActivity` 發出；onShow 本身即使用者手勢（長按首頁／手勢），
 * EntryGate 以手勢放行，無背景啟動濫用。Robolectric 測 action 斷言。
 *
 * 警告：此無參 overload 為隱式 Intent，不可直接 `startActivity`（會劫持到
 * 任意可處理 `ACTION_ASSIST` 的應用）。僅供 session 接線與測試斷言；
 * 生產喚起請用有參 overload [assistantVoiceIntent(android.content.Context)]
 *（限包 implicit，非真 explicit，見該 overload KDoc）。
 */
internal fun assistantVoiceIntent(): Intent = Intent(Intent.ACTION_ASSIST)

/**
 * 限包 implicit 喚起入口（非真 explicit）：以呼叫方包名限定收件者，降低隱式
 * `ACTION_ASSIST` 被第三方助理應用劫持的風險；但 `setPackage` 仍屬 implicit
 * 解析，並非 `setClass`／`setComponent` 的真 explicit。Session 已在
 * 此基礎上再 `setClass` 轉真 explicit（見 LibreVoiceInteractionSession）。
 *
 * 有參版契約由 Phase 4 Robolectric 覆蓋（純 JVM 無 context，本 phase 不強加測試）。
 */
fun assistantVoiceIntent(context: Context): Intent =
    Intent(Intent.ACTION_ASSIST).setPackage(context.packageName)

/**
 * C1b fallback：要求失敗／被拒／OEM 不支援時改開系統語音輸入設定。
 *
 * 不加 [Intent.FLAG_ACTIVITY_NEW_TASK]：本 Intent 自身不帶 task flag，
 * 調用方用 applicationContext＋無條件 NEW_TASK（見 AssistantRoleUi.openFallback）。
 * Flag 斷言留給 Phase 4 Robolectric。
 */
fun assistantFallbackIntent(): Intent =
    Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

/**
 * Fallback 安全啟動候選（純資料，無 Android 依賴，由呼叫方映射）。
 *
 * @param isSystem 是否為系統應用（呼叫方以
 *   `activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0` 判定）。
 * @param isResolver 是否為系統選單本身（包名 `android` 且類名含
 *   `ResolverActivity`／`ChooserActivity`）；此旗標由呼叫方在映射期計算，
 *   本函數僅做過濾，不做字串判斷。
 */
data class FallbackCandidate(
    val packageName: String,
    val className: String,
    val isSystem: Boolean,
    val isResolver: Boolean,
)

/**
 * 從 fallback 候選中選出唯一可安全 explicit 啟動的系統目標。
 *
 * Resolver 繞過原理：[android.content.pm.PackageManager.resolveActivity]
 * 在「多候選且無預設」時回傳的是系統自身的 ResolverActivity／ChooserActivity
 * （包名 `android` 的系統組件），而非最終會被啟動的應用。因此僅對
 * `resolveActivity` 結果做 `FLAG_SYSTEM` 檢查會誤判為安全——檢查通過後仍以
 * implicit intent `startActivity`，系統會彈出選單讓使用者選到第三方應用，
 * 第三方即拿到 `ACTION_VOICE_INPUT_SETTINGS` 的啟動，原有的「只放行系統」
 * 保證不成立（官方文件：`resolveActivity` 回傳的是將處理 intent 的 activity，
 * 無預設且多候選時即為 resolver 本身；實際目標要經由選單才能確定）。
 *
 * 規則（fail-closed）：
 * 1. 先排除 [FallbackCandidate.isResolver] 為 true 者（系統選單本身）；
 * 2. 再排除 [FallbackCandidate.isSystem] 為 false 者（第三方劫持）；
 * 3. 剩餘恰 1 個 → 回傳它（呼叫方以 `setComponent` explicit 啟動，不再經選單）；
 *    剩餘為 0 或 ≥2（多系統候選無法安全決定唯一目標）→ 回傳 null，
 *    呼叫方顯示失敗，不放行。
 */
fun selectFallbackTarget(candidates: List<FallbackCandidate>): FallbackCandidate? {
    val systemOnly = candidates.filter { !it.isResolver && it.isSystem }
    return if (systemOnly.size == 1) systemOnly[0] else null
}
