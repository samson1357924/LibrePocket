package dev.librepocket.agent.assistant

import android.app.Activity
import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * C1b RoleManager 生產接線（Issue #137：Settings 一鍵 `ROLE_ASSISTANT`）。
 *
 * - 流程：[decideAssistantRole]（Held→顯示狀態；Request→
 *   `createRequestRoleIntent(ROLE_ASSISTANT)`＋`StartActivityForResult`；
 *   Unsupported→直接 fallback）＋失敗 fallback [assistantFallbackIntent]
 *   （`ACTION_VOICE_INPUT_SETTINGS`）。
 * - 結果：`RESULT_OK`→GRANTED（重讀快照；若已非持有則 [effectiveAssistantReason]
 *   覆寫為 REVOKED）；非 OK→DENIED＋fallback；要求拋異常／intent 為 null／
 *   RoleManager 缺失（OEM 不支援）→對應原因碼＋fallback，fail-closed。
 * - 讀取失敗一律視為不支援（[AssistantRoleSnapshot] 全 false），不靜默放行。
 */
data class AssistantRoleUi(
    val state: AssistantSettingsState,
    val request: () -> Unit,
    val openFallback: () -> Unit,
)

/** 同步重讀快照；RoleManager 缺失或讀取異常 → 全 false（不支援）。 */
fun readAssistantSnapshot(roleManager: RoleManager?): AssistantRoleSnapshot {
    if (roleManager == null) return AssistantRoleSnapshot(false, false)
    return try {
        AssistantRoleSnapshot(
            roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT),
            roleHeld = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT),
        )
    } catch (_: Exception) {
        AssistantRoleSnapshot(roleAvailable = false, roleHeld = false)
    }
}

@Composable
fun rememberAssistantRole(): AssistantRoleUi {
    val context = LocalContext.current
    val roleManager = remember(context) {
        try {
            context.getSystemService(RoleManager::class.java)
        } catch (_: Exception) {
            null
        }
    }
    var storedReason by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val snapshot = remember(roleManager, refreshTick) {
        readAssistantSnapshot(roleManager)
    }
    val openFallback: () -> Unit = {
        try {
            context.startActivity(assistantFallbackIntent())
        } catch (_: Exception) {
            // Fallback 設定頁亦不可開：保留原因碼，UI 只顯示、不再跳轉。
        }
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        storedReason = if (result.resultCode == Activity.RESULT_OK) {
            AssistantReasonCode.GRANTED
        } else {
            AssistantReasonCode.DENIED
        }
        if (storedReason == AssistantReasonCode.DENIED) openFallback()
        refreshTick++
    }
    val request: () -> Unit = {
        when (decideAssistantRole(snapshot)) {
            AssistantRoleDecision.AlreadyHeld ->
                storedReason = AssistantReasonCode.GRANTED
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
    val state = AssistantSettingsState(
        roleHeld = snapshot.roleHeld,
        roleAvailable = snapshot.roleAvailable,
        reasonCode = effectiveAssistantReason(storedReason, snapshot),
    )
    return AssistantRoleUi(state = state, request = request, openFallback = openFallback)
}
