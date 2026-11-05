package dev.librepocket.agent.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner // lifecycle-runtime-compose 2.8.7（見 gradle/libs.versions.toml）≥2.7.0 穩定，非實驗。
import kotlinx.coroutines.delay

/**
 * C1b RoleManager 生產接線（Issue #137：Settings 一鍵 `ROLE_ASSISTANT`）。
 *
 * - 流程：[decideAssistantRole]（Held→顯示狀態；Request→
 *   `createRequestRoleIntent(ROLE_ASSISTANT)`＋`StartActivityForResult`；
 *   Unsupported／ReadError→直接 fallback／no-op，fail-closed）＋失敗 fallback
 *   [assistantFallbackIntent]（`ACTION_VOICE_INPUT_SETTINGS`）。
 * - 結果：`RESULT_OK`→驗證中（`stored=null`＋`pendingVerify=true`＋
 *   `verifyAttempt=0`＋`refreshTick++`：OK 只代表要求被接受，不代表系統已落盤，
 *   故不寫 GRANTED——re-review #3 finding #1 [P2]；調和此時回 VERIFYING，
 *   既不宣稱授予亦不判撤銷）；非 OK→DENIED（只記原因碼，不自動跳轉——跳轉留給
 *   「語音輸入設定」按鈕 [AssistantRoleUi.openFallback]）。兩路徑皆 `refreshTick++`
 *   重讀快照，讓 VERIFYING／held 調和經 [effectiveAssistantReason] 生效。
 * - ON_RESUME 刷新：`LifecycleEventObserver(ON_RESUME)→refreshTick++`，從授權頁／
 *   系統設定返回即重讀快照（落盤延遲／外部撤銷皆被調和，不依賴回調單一路徑）。
 *   ON_RESUME 只 `tick++`、不清驗證旗、不計重試次數（Stage 2 finding B：結束點是
 *   重讀提交後的 `SideEffect`＋重試計數，與回調／resume 相對時序無關，
 *   詳見 [consumePendingVerify]）。
 * - 外部撤銷：`everHeld` sticky（曾確認 Known held 即置位，確認語義）＋
 *   SharedPreferences `"assistant_role"`／`"ever_held"` 持久化（process death
 *   後仍可判定）；調和經 [effectiveAssistantReason] 三態版
 *   (`stored`／`snapshot`／`everHeld`／`pendingVerify`)。
 * - RESULT_OK 驗證協議（re-review #3 finding #1：驗證必須觸發新的讀取，
 *   驗證代幣被消耗≠觀察到新系統真相）：
 *   回調設 `pendingVerify=true`＋`stored=null`＋`attempt=0`＋`refreshTick++`
 *   （首幀即 VERIFYING）；`LaunchedEffect(pendingVerify, verifyAttempt)` 排程
 *   後續重讀（`delay([VERIFY_RETRY_DELAY_MS])` 後 `attempt++`＋`refreshTick++`，
 *   上限 [VERIFY_MAX_ATTEMPTS] 次）；每幀提交後 `SideEffect` 判定結束
 *   （Known held 確認持有即結束；次數耗盡即超時終止收斂——re-review #4
 *   finding #1 [P2/Merge blocker]：`attempt >= MAX` 強制終止覆蓋所有快照類型，
 *   終止先按 `verificationTimeoutReason(snapshot, everHeld)` 重寫 `stored`
 *   （everHeld→REVOKED，其餘→UNCONFIRMED）再置 `pendingVerify=false`；
 *   超時不再回中性 idle；ReadError／Unsupported 次數未滿保留 pending 並照常
 *   重試，耗盡同樣終止為 UNCONFIRMED，pending 永不殘留）。新一輪 request
 *   發起清旗＋`attempt=0`（舊驗證不帶入新流程；驗證中 request 一律 [canStartRequest] 防禦性 no-op，不清旗不開頁——re-review #4 finding #2 [P3/UX]，UI 另以 `state.verifying` disabled 主按鈕）；ReadError 下 request 觸發一次
 *   重讀（`refreshTick++`，不設碼不開 fallback——「重新檢查」入口）；
 *   `pendingVerify`／`verifyAttempt` 僅記憶體態（remember，不跨 process death——
 *   丟失可接受，重進後按正常規則判定）。
 *   回調先於 ON_RESUME／後於 ON_RESUME 兩種順序皆收斂（至多上限次排程重讀
 *   ＋resume 額外讀，見 [consumePendingVerify] KDoc；`tick++` 不在 effect key
 *   內故不重啟 delay，無無限循環）。
 * - RoleManager 缺失（OEM 不支援）→ [AssistantSnapshot.Unsupported]＋對應原因碼＋
 *   fallback，fail-closed；讀取異常 → [AssistantSnapshot.ReadError]（與真不支援
 *   區分：頭部「無法確認助理狀態，請稍後重試」＋主按鈕 enabled「重新檢查」
 *   （re-review #4 finding #1：給稍後重試實際入口，request 觸發重讀）＋
 *   原因行 passthrough，不判撤銷；Stage 2 finding C）。
 */
data class AssistantRoleUi(
    val state: AssistantSettingsState,
    val request: () -> Unit,
    val openFallback: () -> Unit,
)

private const val TAG = "AssistantRoleUi"

/**
 * 舊同步快照（遺留相容）。
 *
 * 未知態（null／異常）一律折疊為全 false，與真不支援不可區分（Stage 2 finding C
 * 指出此為誤報根源）。保留僅供舊呼叫方編譯；新產線一律用 [snapshotOf]
 * （三態：Unsupported／ReadError 可區分）。
 */
fun readAssistantSnapshot(roleManager: RoleManager?): AssistantRoleSnapshot {
    return when (val s = snapshotOf(roleManager)) {
        is AssistantSnapshot.Known ->
            AssistantRoleSnapshot(roleAvailable = s.available, roleHeld = s.held)

        AssistantSnapshot.Unsupported,
        AssistantSnapshot.ReadError,
        -> {
            Log.w(TAG, "readAssistantSnapshot: non-known ($s); legacy fold to (false, false)")
            AssistantRoleSnapshot(false, false)
        }
    }
}

@Composable
fun rememberAssistantRole(): AssistantRoleUi {
    val context = LocalContext.current
    // openFallback 以 applicationContext 啟動（不持有 Activity）→ 必須帶 NEW_TASK。
    val appContext = remember(context) { context.applicationContext }
    val roleManager = remember(context) {
        try {
            context.getSystemService(RoleManager::class.java)
        } catch (_: Exception) {
            null
        }
    }
    var storedReason by rememberSaveable { mutableStateOf<String?>(null) }
    // Stage 2 review #2：everHeld sticky＋SharedPreferences 持久化（process death
    // 後仍判外部撤銷）；初始值讀盤，之後 snapshot.roleHeld==true 即置 true 並寫盤。
    val prefs = remember(appContext) {
        appContext.getSharedPreferences("assistant_role", Context.MODE_PRIVATE)
    }
    var everHeld by rememberSaveable { mutableStateOf(prefs.getBoolean("ever_held", false)) }
    // Stage 2 finding B（re-review #3 finding #1 語義修正：驗證中＋重試收斂）：
    // 回調置 true（＋stored=null＋attempt=0，見 launcher）；結束點是重讀提交後的
    // SideEffect（consumePendingVerify：Known held 確認即結束、Known(!held) 僅
    // 次數耗盡超時結束、未知態保留），與回調／ON_RESUME 相對時序無關。
    // 僅記憶體態（remember，不跨 process death——丟失可接受，重進按正常規則判定），不落盤。
    // 新一輪 request 發起清旗＋attempt=0（舊驗證不帶入新流程）；ON_RESUME 亦不清旗、不計數。
    var pendingVerify by remember { mutableStateOf(false) }
    // 驗證重試計數器（re-review #3 finding #1）：排程端遞增（LaunchedEffect delay
    // 後 attempt++＋tick++），上限 VERIFY_MAX_ATTEMPTS；SideEffect 只讀它判定
    // 超時（consumePendingVerify），不自行遞增（避免無關重組誤計數）。
    // 僅記憶體態（remember）；request 入口／RESULT_OK 重置為 0。
    var verifyAttempt by remember { mutableIntStateOf(0) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // C: fallback 失敗回饋（rememberSaveable 撐過旋轉；成功開啟即清回 false）。
    var fallbackFailed by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // re-review #3 finding #1：只 tick++ 觸發重讀，不清 pendingVerify、
                // 不計 verifyAttempt（排程 delay 不重啟，見下 LaunchedEffect）。
                // 結束由重讀提交後的 SideEffect 執行（consumePendingVerify），故
                // callback-before-resume（回調先設旗，resume 提供額外新鮮讀取）與
                // callback-after-resume（resume 先重讀，回調後再設旗重讀）皆收斂，
                // 不會因清旗時序提早／延後驗證。
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Stage 2 finding C：三態快照（Known／Unsupported／ReadError 可區分）。
    val snapshot: AssistantSnapshot = remember(roleManager, refreshTick) {
        snapshotOf(roleManager)
    }
    // 外部授予→撤銷鏈路：只在 Known held 才 sticky everHeld 並落盤（未知態不置位，
    // 避免把讀取失敗誤記為持有痕跡）；process death 後仍可判撤銷。
    // composition body 禁止直接寫 state＋SP（重組循環風險），改用 SideEffect。
    // 驗證結束判定同在此：以 consumePendingVerify(pendingVerify, snapshot,
    // verifyAttempt) 決策（held 確認／次數耗盡強制終止置 false——re-review #4
    // finding #1：attempt>=MAX 覆蓋所有快照類型，pending 永不殘留；未知態次數
    // 未滿保留），終止收斂同時按 verificationTimeoutReason(snapshot, everHeld)
    // 重寫 storedReason（everHeld→REVOKED，其餘→UNCONFIRMED；held 確認不寫，
    // 保持 null），再清 pending（見下）。
    // 次數遞增由排程端 LaunchedEffect 執行（見下），此處不計數。
    SideEffect {
        if (snapshot is AssistantSnapshot.Known && snapshot.held && !everHeld) {
            everHeld = true
            prefs.edit().putBoolean("ever_held", true).apply()
        }
        if (pendingVerify) {
            // re-review #4 finding #1 [P2/Merge blocker] 終止收斂：重試耗盡且
            // 當前非 held 即驗證超時，先按快照／everHeld 重寫 stored（終端狀態），
            // 再由 consumePendingVerify 置 pending=false（強制終止覆蓋所有快照
            // 類型；held 確認不寫 stored，保持 null）。
            val heldNow = (snapshot as? AssistantSnapshot.Known)?.held == true
            if (!heldNow && verifyAttempt >= VERIFY_MAX_ATTEMPTS) {
                storedReason = verificationTimeoutReason(snapshot, everHeld)
            }
            pendingVerify = consumePendingVerify(pendingVerify, snapshot, verifyAttempt)
        } else {
            pendingVerify = consumePendingVerify(pendingVerify, snapshot, verifyAttempt)
        }
    }
    // 驗證重試排程（re-review #3 finding #1：驗證必須觸發新的讀取；
    // re-review #4 finding #1：attempt>=MAX 終止覆蓋所有快照類型，pending 永不殘留）。
    // key 含 verifyAttempt：每次排程重讀提交後 SideEffect 若保留 pending
    // （次數未滿），attempt 已＋1 使 key 變化→重排下一次；
    // 達上限（SideEffect 終止收斂：重寫 stored＋置 pending=false）或 held 確認
    // （同置 false）即停。
    // tick++ 不在 key 內：ON_RESUME／回調的即時重讀不重啟 delay、不多計次數，
    // 只提供額外新鮮快照；guard（pending＋attempt 上限）保證終止，無無限循環。
    LaunchedEffect(pendingVerify, verifyAttempt) {
        if (pendingVerify && verifyAttempt < VERIFY_MAX_ATTEMPTS) {
            delay(VERIFY_RETRY_DELAY_MS)
            verifyAttempt++
            refreshTick++
        }
    }
    val openFallback: () -> Unit = remember(appContext) {
        // fallbackFailed 經 MutableState 引用捕獲，非值捕獲，故無需入 remember key。
        {
            try {
                val intent = assistantFallbackIntent()
                // minSdk 33：用非棄用 ResolveInfoFlags.of，避免 int-flags 棄用警告。
                // 不用 resolveActivity：多候選無預設時它回傳系統 Resolver 本身
                // （FLAG_SYSTEM 檢查會誤判通過，後續 implicit 啟動仍彈選單放行
                // 第三方），故用 queryIntentActivities 全枚舉＋setComponent explicit。
                val candidates = appContext.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(
                        PackageManager.MATCH_DEFAULT_ONLY.toLong(),
                    ),
                ).mapNotNull { resolved ->
                    val activityInfo = resolved.activityInfo ?: return@mapNotNull null
                    val pkg = activityInfo.packageName ?: return@mapNotNull null
                    val cls = activityInfo.name ?: return@mapNotNull null
                    FallbackCandidate(
                        packageName = pkg,
                        className = cls,
                        isSystem = ((activityInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isResolver = pkg == "android" &&
                            (cls.contains("ResolverActivity") || cls.contains("ChooserActivity") || cls.contains("Resolver")),
                    )
                }
                val target = selectFallbackTarget(candidates)
                if (target == null) {
                    Log.w(TAG, "fallback has no unique system target; skip")
                    fallbackFailed = true
                } else {
                    intent.setComponent(ComponentName(target.packageName, target.className))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                    fallbackFailed = false
                }
            } catch (e: Exception) {
                // Fallback 設定頁亦不可開：保留原因碼，UI 只顯示、不再跳轉。
                Log.w(TAG, "openFallback failed", e)
                fallbackFailed = true
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // RESULT_OK 只代表要求被接受，不代表系統已落盤
            // （re-review #3 finding #1 [P2]）：不寫 GRANTED（否則下次重讀
            // Known(!held) 會用早期 false 快照斷言撤銷），stored 清 null＋
            // pendingVerify=true 進入驗證中（調和回 VERIFYING）＋attempt=0，
            // refreshTick++ 立即重讀第一幀；後續重讀由重試排程驅動，超時終止收斂
            // （re-review #4 finding #1：everHeld→REVOKED，其餘→UNCONFIRMED，
            // 見 SideEffect＋verificationTimeoutReason）。此處不清，ON_RESUME 亦不清。
            storedReason = null
            pendingVerify = true
            verifyAttempt = 0
            fallbackFailed = false
        } else {
            storedReason = AssistantReasonCode.DENIED
            pendingVerify = false
            verifyAttempt = 0
        }
        // 驗證中／DENIED 皆重讀快照：VERIFYING／held／REVOKED 調和經 effectiveAssistantReason 生效。
        refreshTick++
    }
    /**
     * 閉包快照語義（PocketGuard S2）：`snap` 是本次 `remember(snapshot, ...)`
     * 計算當下捕獲的快照值——顯式本地 val，閉包內快照只讀 `snap`。
     * （另讀寫的 pendingVerify／verifyAttempt／storedReason 等 MutableState
     * 不受此約束，見各分支。）`AssistantSnapshot` 為不可變 sealed（`Known` data class／
     * `Unsupported`、`ReadError` data object，無可變欄位），故無需
     * `.copy()`；若未來出現可變欄位須改為快照拷貝。`remember` key 含
     * `snapshot`，快照變化必重算 `request`，不會有時序 stale。
     */
    val request: () -> Unit = remember(snapshot, roleManager, launcher, openFallback) {
        // 顯式本地 val：閉包捕獲當次 remember 計算的快照值（見上 KDoc）。
        val snap = snapshot
        {
            // re-review #4 finding #2 [P3/UX]：驗證中 request 防禦性 no-op
            // （UI 已以 state.verifying disabled 主按鈕，此為第二層：即使競態
            // 穿透亦不再清驗證重開授權頁，避免重複流程）。
            if (!canStartRequest(pendingVerify)) {
                Log.w(TAG, "request skipped: verification in progress (fail-closed)")
            } else {
            // 新一輪要求開始即結束上一輪驗證（否則舊 pendingVerify 常駐掩蓋撤銷）＋attempt=0。
            pendingVerify = false
            verifyAttempt = 0
            // Stage 2 finding C：ReadError fail-closed——不發要求、不設
            // UNSUPPORTED 碼（誠實：未知≠不支援）、不開 fallback；但觸發一次
            // 重讀（refreshTick++，re-review #4 finding #1 [P2/Merge blocker]：
            // ReadError 超時／平時皆需實際重試入口，主按鈕改為 enabled
            // 「重新檢查」，見 SettingsScreen；不設碼、不開 fallback、不動
            // pendingVerify／verifyAttempt——request 入口已清旗＋attempt=0）。
            if (snap is AssistantSnapshot.ReadError) {
                Log.w(TAG, "request skipped: snapshot read error (fail-closed); trigger re-read")
                refreshTick++
            } else {
                when (decideAssistantRole(snap)) {
                    AssistantRoleDecision.AlreadyHeld -> {
                        // 已確認 held 前提下寫 GRANTED（隨即被 held 規則清除為 null）：
                        // 此為新流程下 GRANTED sticky 唯一合法來源，具確認語義
                        // （見 effectiveAssistantReason GRANTED→REVOKED 分支）。
                        storedReason = AssistantReasonCode.GRANTED
                        fallbackFailed = false
                    }
                    AssistantRoleDecision.UnsupportedFallback -> {
                        storedReason = AssistantReasonCode.UNSUPPORTED
                        openFallback()
                    }
                    AssistantRoleDecision.RequestRole -> {
                        val intent = try {
                            roleManager?.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                        } catch (_: Exception) {
                            null
                        }
                        if (intent == null) {
                            storedReason = AssistantReasonCode.REQUEST_FAILED
                            openFallback()
                        } else {
                            // 新一輪要求開始：清除舊的 fallback 失敗旗，避免等待 RESULT 期間殘留紅字。
                            fallbackFailed = false
                            try {
                                launcher.launch(intent)
                            } catch (_: Exception) {
                                storedReason = AssistantReasonCode.REQUEST_FAILED
                                openFallback()
                            }
                        }
                    }
                }
            }
            }
        }
    }
    val state = remember(snapshot, storedReason, everHeld, pendingVerify, fallbackFailed) {
        val known = snapshot as? AssistantSnapshot.Known
        AssistantSettingsState(
            // 未知態不宣稱任何狀態（皆 false，見 AssistantSettingsState.readError）。
            roleHeld = known?.held ?: false,
            roleAvailable = known?.available ?: false,
            reasonCode = effectiveAssistantReason(storedReason, snapshot, everHeld, pendingVerify),
            fallbackFailed = fallbackFailed,
            readError = snapshot is AssistantSnapshot.ReadError,
            verifying = pendingVerify,
        )
    }
    return AssistantRoleUi(
        state = state,
        request = request,
        openFallback = openFallback,
    )
}
