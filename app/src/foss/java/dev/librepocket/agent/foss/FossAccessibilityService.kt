package dev.librepocket.agent.foss

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.librepocket.automation.A11yAction
import dev.librepocket.automation.A11yWalker
import dev.librepocket.automation.AutomationCore
import dev.librepocket.guard.ArbitrationCode

/**
 * Foss 風味無障礙自動化真實現（S3，BACKLOG B5/B8，純 OSS）。
 *
 * 與 `src/github …GithubAccessibilityService` 同語義的鏡像，差異僅在
 * 接線對象（[FossAutomationGate]/[FossA11yState]）：
 * - 本類只存在於 `src/foss`（play 物理缺失），且只引用 framework +
 *   main 純 OSS 核心（[A11yWalker]/[AutomationCore]/門禁），不引用
 *   ML Kit / GMS / Azure / Shizuku 任一專有依賴（foss dex 門自證）；
 * - 三同意門禁（App 內開關 + 系統服務授權 + 當輪二次確認）缺一即
 *   [onAccessibilityEvent] 直接返回，不讀窗、不點擊；
 * - 管線：節點快照 → 脫敏語義壓縮（郵箱/電話先遮罩）→ 仲裁前攔截
 *   （支付/刪除/發送類強制 CONFIRM，未確認不執行）→ 緊湊 JSON
 *   序列化（無空白）供轉錄消費。
 */
class FossAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        FossA11yState.serviceGranted = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!FossA11yState.effective()) return
        // 已武裝時也只做快照 + 壓縮，絕不自動點擊；
        // 任何執行必須經 executeConfirmed（當輪二次確認後）。
        val nodes = A11yWalker.snapshot(rootInActiveWindow)
        FossA11yState.lastCompressed = AutomationCore.compressNodes(nodes).lines
    }

    override fun onInterrupt() = Unit

    /**
     * 當輪確認後的唯一執行動作入口。呼叫前必須已通過 [gateAction] 且
     * 使用者已二次確認（[FossA11yState.userConfirmed]）；否則回 false。
     *
     * 執行緒約束：必須在背景執行緒呼叫（手勢分派見 [dispatchSwipe]
     * 的 ANR 防護，主執行緒呼叫一律回 false）。
     */
    fun executeConfirmed(action: A11yAction, goalText: String, targetText: String = ""): Boolean {
        if (!FossA11yState.effective()) return false
        if (gateAction(goalText, action, targetText) !is FossGateDecision.Allow) return false
        return perform(action)
    }

    private fun perform(action: A11yAction): Boolean {
        return when (action) {
            is A11yAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is A11yAction.Swipe -> dispatchSwipe(action)
            is A11yAction.Tap -> findNode(action.nodeId)?.let { node ->
                try {
                    runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                } finally {
                    runCatching { node.recycle() }
                }
            } ?: false
            is A11yAction.Input -> findNode(action.nodeId)?.let { node ->
                try {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            action.text,
                        )
                    }
                    runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
                        .getOrDefault(false)
                } finally {
                    runCatching { node.recycle() }
                }
            } ?: false
        }
    }

    /**
     * 與 A11yWalker 同序 BFS（n&lt;index&gt;）。
     * 命中節點的所有權移交呼叫方（呼叫方 perform 後必須 recycle，
     * 見 [perform]）；非命中節點展開後即時回收，殘留佇列在命中返回前
     * 清空回收，避免 AccessibilityNodeInfo 洩漏。
     */
    private fun findNode(nodeId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val index = nodeId.removePrefix("n").toIntOrNull()
        if (index == null || index < 0) {
            runCatching { root.recycle() }
            return null
        }
        // 與 A11yWalker 同序 BFS（n<index>），命中即回（呼叫方持有，
        // perform 後 recycle；非命中節點沿途回收）。
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var i = 0
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (i == index) {
                    while (queue.isNotEmpty()) {
                        runCatching { queue.removeFirst().recycle() }
                    }
                    return node
                }
                i++
                try {
                    for (c in 0 until node.childCount) {
                        node.getChild(c)?.let { queue.add(it) }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } catch (_: Exception) {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
            return null
        }
        return null
    }

    /**
     * 手勢分派（ANR 防護）：絕不在主執行緒上 `await` 阻塞。
     * 主執行緒呼叫直接回 false（呼叫方參見 [executeConfirmed] 的執行緒約束）；
     * 背景執行緒上以 5 秒為上限等待手勢回調，超時回 false。
     */
    private fun dispatchSwipe(action: A11yAction.Swipe): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return false
        val path = Path().apply {
            moveTo(action.fromX.toFloat(), action.fromY.toFloat())
            lineTo(action.toX.toFloat(), action.toY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var ok = false
        val done = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    done.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            },
            null,
        )
        runCatching { done.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return ok
    }

    companion object {
        /** 與總開關同源的預設關常量，供設定頁 dump 舉證。 */
        const val DEFAULT_ENABLED: Boolean = FossAutomationGate.DEFAULT_ENABLED

        /**
         * 仲裁前攔截（純 OSS 路徑）：經 [AutomationCore.needsConfirm]
         *（支付/刪除/發送關鍵詞或種類命中 → 強制 CONFIRM；越界種類拒絕，
         * 同樣視為攔截）；只有放行回 [FossGateDecision.Allow]。
         */
        fun gateAction(
            goalText: String,
            action: A11yAction,
            targetText: String = "",
        ): FossGateDecision {
            if (AutomationCore.needsConfirm(action, goalText, targetText)) {
                return FossGateDecision.NeedConfirm(
                    AutomationCore.interceptCodes(action, goalText, targetText),
                )
            }
            return FossGateDecision.Allow
        }
    }
}

/** 仲裁前攔截結論：放行 / 需二次確認（附理由碼，轉錄/審計用）。 */
sealed interface FossGateDecision {
    data object Allow : FossGateDecision
    data class NeedConfirm(val codes: List<ArbitrationCode>) : FossGateDecision
}

/**
 * 服務側三同意狀態（記憶體態；App 內開關由設定頁寫入）。
 * effective = [FossAutomationGate.effectiveAutomation]
 *（App 開關 + 系統授權 + 當輪確認，play 側無此類故永遠缺席）。
 */
object FossA11yState {
    @Volatile var switchOn: Boolean = FossAutomationGate.DEFAULT_ENABLED
    @Volatile var serviceGranted: Boolean = false
    @Volatile var userConfirmed: Boolean = false
    @Volatile var lastCompressed: List<String> = emptyList()

    fun effective(): Boolean = FossAutomationGate.effectiveAutomation(
        switchOn = switchOn,
        serviceGranted = serviceGranted,
        userConfirmed = userConfirmed,
    )
}
