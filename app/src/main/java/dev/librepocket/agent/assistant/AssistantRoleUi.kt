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
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * C1b RoleManager 生產接線（Issue #137：Settings 一鍵 `ROLE_ASSISTANT`）。
 *
 * - 流程：[decideAssistantRole]（Held→顯示狀態；Request→
 *   `createRequestRoleIntent(ROLE_ASSISTANT)`＋`StartActivityForResult`；
 *   Unsupported→直接 fallback）＋失敗 fallback [assistantFallbackIntent]
 *   （`ACTION_VOICE_INPUT_SETTINGS`）。
 * - 結果：`RESULT_OK`→GRANTED（重讀快照；若已非持有則 [effectiveAssistantReason]
 *   覆寫為 REVOKED）；非 OK→DENIED（只記原因碼，不自動跳轉——跳轉留給
 *   「語音輸入設定」按鈕 [AssistantRoleUi.openFallback]）。兩路徑皆 `refreshTick++`
 *   重讀快照，讓 REVOKED／held 調和經 [effectiveAssistantReason] 生效。
 * - ON_RESUME 刷新：`LifecycleEventObserver(ON_RESUME)→refreshTick++`，從授權頁／
 *   系統設定返回即重讀快照（GRANTED 落盤延遲／外部撤銷皆被調和，不依賴回調單一路徑）。
 *   ON_RESUME 同時清 `justGranted=false`（寬限只保回調後首幀），新一輪 request
 *   發起亦先清 `justGranted=false`。
 * - 外部撤銷：`everHeld` sticky（曾觀察 `roleHeld==true` 即置 true）＋
 *   SharedPreferences `"assistant_role"`／`"ever_held"` 持久化（process death
 *   後仍可判定）；調和經 [effectiveAssistantReason] 四參版
 *   (`stored`／`snapshot`／`everHeld`／`justGranted`)。
 * - RESULT_OK 競態寬限：回調設 `justGranted=true`＋`stored=GRANTED`，首幀保持
 *   GRANTED 而非立即誤判 REVOKED；詳見 [effectiveAssistantReason] KDoc。
 *   RoleManager 缺失（OEM 不支援）→對應原因碼＋fallback，fail-closed。
 * - 讀取失敗一律視為不支援（[AssistantRoleSnapshot] 全 false），不靜默放行。
 */
data class AssistantRoleUi(
    val state: AssistantSettingsState,
    val request: () -> Unit,
    val openFallback: () -> Unit,
)

private const val TAG = "AssistantRoleUi"

/** 同步重讀快照；RoleManager 缺失或讀取異常 → 全 false（不支援）。 */
fun readAssistantSnapshot(roleManager: RoleManager?): AssistantRoleSnapshot {
    if (roleManager == null) return AssistantRoleSnapshot(false, false)
    return try {
        AssistantRoleSnapshot(
            roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT),
            roleHeld = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT),
        )
    } catch (e: Exception) {
        Log.w(TAG, "readAssistantSnapshot failed; treat as unsupported", e)
        AssistantRoleSnapshot(roleAvailable = false, roleHeld = false)
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
    // Stage 2 review #2：RESULT_OK 競態寬限旗（回調置 true；ON_RESUME／下次重讀／
    // 新 request 發起清 false）。僅記憶體態（remember，不跨 process death），不落盤。
    // 寬限僅一幀：launcher 回調設 true 後 refreshTick++ 重讀當幀仍 true（寬限生效一次）；
    // 下次 ON_RESUME 先清 false 再 tick++ 重讀（同一幀生效），故不會掩蓋真實撤銷。
    var justGranted by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // C: fallback 失敗回饋（rememberSaveable 撐過旋轉；成功開啟即清回 false）。
    var fallbackFailed by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 寬限只保回調後首幀：下次重讀前先清，否則 GRANTED 常駐掩蓋真實撤銷。
                // 順序必須先清再 tick++（同一幀生效）：外部撤銷的 ON_RESUME 會先清
                // justGranted 再重讀，故不會掩蓋真實撤銷。
                justGranted = false
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snapshot = remember(roleManager, refreshTick) {
        readAssistantSnapshot(roleManager)
    }
    // 外部授予→撤銷鏈路：只要觀察到 held 即 sticky everHeld 並落盤（process death 後仍可判撤銷）。
    // composition body 禁止直接寫 state＋SP（重組循環風險），改用 SideEffect。
    SideEffect {
        if (snapshot.roleHeld && !everHeld) {
            everHeld = true
            prefs.edit().putBoolean("ever_held", true).apply()
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
            storedReason = AssistantReasonCode.GRANTED
            // RESULT_OK 落盤延遲寬限：首幀保持 GRANTED，不立即誤判 REVOKED。
            // 此處不立即清 justGranted，保留寬限一幀（refreshTick++ 重讀當幀仍 true）；
            // 寬限由下次 ON_RESUME／新 request 入口清 false，state 計算後不清。
            justGranted = true
            fallbackFailed = false
        } else {
            storedReason = AssistantReasonCode.DENIED
            justGranted = false
        }
        // GRANTED／DENIED 皆重讀快照：REVOKED／held 調和經 effectiveAssistantReason 生效。
        refreshTick++
    }
    val request: () -> Unit = remember(snapshot, roleManager, launcher, openFallback) {
        {
            // 新一輪要求開始即結束上一輪寬限（否則舊 justGranted 常駐掩蓋撤銷）。
            justGranted = false
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
    val state = remember(snapshot, storedReason, everHeld, justGranted, fallbackFailed) {
        AssistantSettingsState(
            roleHeld = snapshot.roleHeld,
            roleAvailable = snapshot.roleAvailable,
            reasonCode = effectiveAssistantReason(storedReason, snapshot, everHeld, justGranted),
            fallbackFailed = fallbackFailed,
        )
    }
    return AssistantRoleUi(
        state = state,
        request = request,
        openFallback = openFallback,
    )
}
