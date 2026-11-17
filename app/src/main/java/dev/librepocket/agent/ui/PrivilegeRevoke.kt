package dev.librepocket.agent.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.PrivilegeBridgeStore

private const val TAG = "PrivilegeRevoke"

/**
 * android.util.Log 在非 Robolectric 的 JVM 單元測試中未 mock（呼叫即抛
 * RuntimeException）；日誌絕不得影響 fail-closed 控制流，故包一層容錯。
 */
private fun safeLogW(tag: String, msg: String, e: Throwable? = null) {
    try {
        if (e != null) {
            Log.w(tag, msg, e)
        } else {
            Log.w(tag, msg)
        }
    } catch (_: Throwable) {
        // 單元測試環境無 android.util.Log：吞掉
    }
}

/**
 * 「一鍵收回」核心（從 MainScreen composition handler 抽出，供 JVM 單測）。
 * 回傳值語義：true = 撤權未能完整落實／成敗未知 ⇒ 呼叫端必須顯示「收回失敗」
 * banner 並允許重試；false = 撤權目標已落實（呼叫端可隱藏 banner）。
 * library 原生 TimeoutCancellationException 若由 Store 自身丟出，會被 withTimeoutOrNull 折為 IllegalStateException(PRIVILEGE_REVOKE_STORE_TIMEOUT)（非 CE），照常折為失敗 ⇒ banner；若未來有人把它改回原生 CE 上拋，需重新評估此判別。
 *
 * 取消語義（CE-first 契約延續）：
 * - 只有「UI Job 仍 active 而存取層丟出的 CancellationException」（DataStore
 *   teardown、withdrawScope CE 等）才折為失敗（回傳 true）——此時 UI Job 並未
 *   被取消，結構化取消未發生。
 * - 真正的外部取消（[isScopeActive]() == false）：CE 原樣上拋（不回 banner）。
 * - isScopeActive()==false 時無法區分內部 CE 與外部取消，一律 CE-first 上拋（與 NonCancellable 對整個 revoke 本體的實際取消語義一致）。
 * - VirtualMachineError／ThreadDeath：原樣上拋（致命；與 MainScreen 兩個 rethrow 分支同集合）。
 * - 其餘 Throwable：Log.w（不含可識別秘密；例外訊息為第三方程式庫原文，僅含 I/O 細節，不含主金鑰／token）後回傳 true。
 */
internal suspend fun executePrivilegeRevoke(
    privilegeState: PrivilegeBridgeState,
    privilegeStore: PrivilegeBridgeStore,
    withdraw: suspend () -> Unit,
    isScopeActive: () -> Boolean,
): Boolean {
    return try {
        privilegeState.revoke(privilegeStore) { withdraw() }
        false
    } catch (t: Throwable) {
        if (t is VirtualMachineError || t is ThreadDeath) throw t
        // 判別器本身保守失敗（回退 true = 視為失敗 ⇒ banner）
        if (t is CancellationException && !runCatching { isScopeActive() }.getOrDefault(true)) throw t
        safeLogW(TAG, "privilege revoke failed", t)
        true
    }
}
