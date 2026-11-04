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

/**
 * C1b RoleManager 生產接線（Issue #137：Settings 一鍵 `ROLE_ASSISTANT`）。
 *
 * - 流程：[decideAssistantRole]（Held→顯示狀態；Request→
 *   `createRequestRoleIntent(ROLE_ASSISTANT)`＋`StartActivityForResult`；
 *   Unsupported／ReadError→直接 fallback／no-op，fail-closed）＋失敗 fallback
 *   [assistantFallbackIntent]（`ACTION_VOICE_INPUT_SETTINGS`）。
 * - 結果：`RESULT_OK`→GRANTED（重讀快照；若已非持有則 [effectiveAssistantReason]
 *   覆寫為 REVOKED）；非 OK→DENIED（只記原因碼，不自動跳轉——跳轉留給
 *   「語音輸入設定」按鈕 [AssistantRoleUi.openFallback]）。兩路徑皆 `refreshTick++`
 *   重讀快照，讓 REVOKED／held 調和經 [effectiveAssistantReason] 生效。
 * - ON_RESUME 刷新：`LifecycleEventObserver(ON_RESUME)→refreshTick++`，從授權頁／
 *   系統設定返回即重讀快照（GRANTED 落盤延遲／外部撤銷皆被調和，不依賴回調單一路徑）。
 *   ON_RESUME 只 `tick++`、不清驗證旗（Stage 2 finding B：消耗點是重讀提交後的
 *   `SideEffect`，與回調／resume 相對時序無關，詳見 [consumePendingVerify]）。
 * - 外部撤銷：`everHeld` sticky（曾觀察 Known held 即置 true）＋
 *   SharedPreferences `"assistant_role"`／`"ever_held"` 持久化（process death
 *   後仍可判定）；調和經 [effectiveAssistantReason] 三態版
 *   (`stored`／`snapshot`／`everHeld`／`justGranted=pendingVerify`)。
 * - RESULT_OK 競態寬限（Stage 2 finding B：驗證一次即消耗）：
 *   回調設 `pendingVerify=true`＋`stored=GRANTED`，下一次快照重讀幀仍以
 *   true 判定（首幀保持 GRANTED 而非立即誤判 REVOKED），該幀提交後
 *   `SideEffect { pendingVerify = consumePendingVerify(pendingVerify, snapshot) }`
 *   即消耗（僅 Known 消耗；ReadError／Unsupported 不消耗亦無寬限效果）。
 *   新一輪 request 發起清旗（舊寬限不帶入新流程）；`pendingVerify` 僅記憶體態
 *   （remember，不跨 process death——丟失可接受，重進按正常規則判定）。
 *   回調先於 ON_RESUME／後於 ON_RESUME 兩種順序皆收斂到「下一次快照讀取後消耗」
 *   （至多寬限兩幀，見 [consumePendingVerify] KDoc）。
 * - RoleManager 缺失（OEM 不支援）→ [AssistantSnapshot.Unsupported]＋對應原因碼＋
 *   fallback，fail-closed；讀取異常 → [AssistantSnapshot.ReadError]（與真不支援
 *   區分：頭部「無法確認助理狀態，請稍後重試」＋主按鈕 disabled＋原因行 passthrough，
 *   不判撤銷；Stage 2 finding C）。
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
    // Stage 2 finding B：RESULT_OK 競態寬限改為驗證代幣（驗證一次即消耗）。
    // 回調置 true；消耗點是下一次快照重讀幀提交後的 SideEffect（僅 Known 消耗，
    // 見 consumePendingVerify），與回調／ON_RESUME 相對時序無關。
    // 僅記憶體態（remember，不跨 process death——丟失可接受，重進按正常規則判定），不落盤。
    // 新一輪 request 發起清旗（舊寬限不帶入新流程）；ON_RESUME 只 tick++不清旗。
    var pendingVerify by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // C: fallback 失敗回饋（rememberSaveable 撐過旋轉；成功開啟即清回 false）。
    var fallbackFailed by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Stage 2 finding B：只 tick++ 觸發重讀，不清 pendingVerify。
                // 消耗由重讀提交後的 SideEffect 執行（consumePendingVerify），故
                // callback-before-resume（回調先設旗，resume 再重讀）與
                // callback-after-resume（resume 先重讀，回調後再設旗重讀）皆收斂到
                // 「下一次快照讀取後消耗」，不會因清旗時序提早／延後寬限。
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
    // 驗證代幣消耗同在此：本幀以 pendingVerify 判定後即消耗（僅 Known），
    // 下次重讀按正常規則走——兩語句皆放 SideEffect（提交後執行），互不干擾。
    SideEffect {
        if (snapshot is AssistantSnapshot.Known && snapshot.held && !everHeld) {
            everHeld = true
            prefs.edit().putBoolean("ever_held", true).apply()
        }
        pendingVerify = consumePendingVerify(pendingVerify, snapshot)
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
            storedReason = AssistantReasonCode.GRANTED
            // RESULT_OK 落盤延遲寬限（Stage 2 finding B：驗證代幣）：
            // 置 true＋refreshTick++，下一次快照重讀幀以 true 判定（首幀 GRANTED），
            // 該幀提交後 SideEffect 即消耗（僅 Known）。此處不清，ON_RESUME 亦不清。
            pendingVerify = true
            fallbackFailed = false
        } else {
            storedReason = AssistantReasonCode.DENIED
            pendingVerify = false
        }
        // GRANTED／DENIED 皆重讀快照：REVOKED／held 調和經 effectiveAssistantReason 生效。
        refreshTick++
    }
    val request: () -> Unit = remember(snapshot, roleManager, launcher, openFallback) {
        {
            // 新一輪要求開始即結束上一輪寬限（否則舊 pendingVerify 常駐掩蓋撤銷）。
            pendingVerify = false
            // Stage 2 finding C：ReadError fail-closed——不發要求、不設
            // UNSUPPORTED 碼（誠實：未知≠不支援）、不開 fallback，純 no-op
            //（主按鈕本就 disabled，此為防禦性分支）。
            if (snapshot is AssistantSnapshot.ReadError) {
                Log.w(TAG, "request skipped: snapshot read error (fail-closed)")
            } else {
                when (decideAssistantRole(snapshot)) {
                    AssistantRoleDecision.AlreadyHeld -> {
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
    val state = remember(snapshot, storedReason, everHeld, pendingVerify, fallbackFailed) {
        val known = snapshot as? AssistantSnapshot.Known
        AssistantSettingsState(
            // 未知態不宣稱任何狀態（皆 false，見 AssistantSettingsState.readError）。
            roleHeld = known?.held ?: false,
            roleAvailable = known?.available ?: false,
            reasonCode = effectiveAssistantReason(storedReason, snapshot, everHeld, pendingVerify),
            fallbackFailed = fallbackFailed,
            readError = snapshot is AssistantSnapshot.ReadError,
        )
    }
    return AssistantRoleUi(
        state = state,
        request = request,
        openFallback = openFallback,
    )
}
