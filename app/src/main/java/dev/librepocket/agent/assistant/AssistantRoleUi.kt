package dev.librepocket.agent.assistant

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    var refreshTick by remember { mutableIntStateOf(0) }
    // C: fallback 失敗回饋（rememberSaveable 撐過旋轉；成功開啟即清回 false）。
    var fallbackFailed by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snapshot = remember(roleManager, refreshTick) {
        readAssistantSnapshot(roleManager)
    }
    val openFallback: () -> Unit = remember(appContext) {
        {
            try {
                val intent = assistantFallbackIntent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // minSdk 33：用非棄用 ResolveInfoFlags.of，避免 int-flags 棄用警告。
                val resolved = appContext.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(
                        PackageManager.MATCH_DEFAULT_ONLY.toLong(),
                    ),
                )
                // 劫持 hardening：fallback 必須落在系統應用（OEM Settings 皆 system）。
                // 第三方應用若搶 ACTION_VOICE_INPUT_SETTINGS 會是非 system，直接擋下、
                // 不 startActivity。殘餘風險：OEM 自帶 Settings 皆為 system，本檢查
                // 無法區分「哪一個系統應用」，但第三方劫持已被擋。
                if (resolved?.activityInfo == null) {
                    Log.w(TAG, "VOICE_INPUT_SETTINGS not resolvable; skip fallback")
                    fallbackFailed = true
                } else if ((resolved.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                    Log.w(TAG, "fallback resolved to non-system app, skip")
                    fallbackFailed = true
                } else {
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
            fallbackFailed = false
        } else {
            storedReason = AssistantReasonCode.DENIED
        }
        // GRANTED／DENIED 皆重讀快照：REVOKED／held 調和經 effectiveAssistantReason 生效。
        refreshTick++
    }
    val request: () -> Unit = remember(snapshot, roleManager, launcher, openFallback) {
        {
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
    val state = remember(snapshot, storedReason, fallbackFailed) {
        AssistantSettingsState(
            roleHeld = snapshot.roleHeld,
            roleAvailable = snapshot.roleAvailable,
            reasonCode = effectiveAssistantReason(storedReason, snapshot),
            fallbackFailed = fallbackFailed,
        )
    }
    return AssistantRoleUi(
        state = state,
        request = request,
        openFallback = openFallback,
    )
}
