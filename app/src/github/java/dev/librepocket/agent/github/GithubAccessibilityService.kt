package dev.librepocket.agent.github

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
import dev.librepocket.guard.ArbitrationVerdict
import dev.librepocket.guard.SlowActionKind
import dev.librepocket.guard.SlowArbitrator

/**
 * GitHub 風味無障礙自動化真實現（S3，BACKLOG B5/B8，矩陣 §1/§2）。
 *
 * - 本類只存在於 `src/github`（play 物理缺失；foss 鏡像見
 *   `src/foss …FossAccessibilityService`，純 OSS 同語義）；
 * - 三同意門禁（[GithubA11yState.effective] → [GithubAutomationGate]）：
 *   App 內開關 + 系統服務授權 + 當輪二次確認，三者缺一即
 *   [onAccessibilityEvent] 直接返回，不讀窗、不點擊；
 * - 管線：節點快照（[A11yWalker]）→ 脫敏語義壓縮
 *  （[AutomationCore.compressNodes]，郵箱/電話等先遮罩）→
 *   執行前經 [gateAction]（[AutomationCore.verdictFor] 仲裁前攔截，
 *   與 foss 鏡像同語義）：支付/刪除/發送類強制
 *   CONFIRM（[GateDecision.NeedConfirm]），未確認不執行；
 *   已確認消耗一次 one-shot 確認後執行（[executeConfirmed]），
 *   越界種類（[GateDecision.Denied]）即使已確認亦不執行；
 * - 動作序列化為緊湊 JSON（[AutomationCore.tapJson]/[swipeJson]/
 *   [inputJson]/[backJson]，無空白），供轉錄與 SlowRouter 消費。
 */
class GithubAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        GithubA11yState.serviceGranted = true
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        GithubA11yState.serviceGranted = false
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GithubA11yState.serviceGranted = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!GithubA11yState.effective()) return
        // 已武裝時也只做快照 + 壓縮（供 SlowRouter 觀察用），絕不自動點擊；
        // 任何執行必須經 executeConfirmed（當輪二次確認後）。
        val nodes = A11yWalker.snapshot(rootInActiveWindow)
        GithubA11yState.lastCompressed = AutomationCore.compressNodes(nodes).lines
    }

    override fun onInterrupt() = Unit

    /**
     * 當輪確認後的唯一執行動作入口（P1 one-shot 確認，PR#1 review blocker）。
     *
     * P1 re-review 綁定確認：呼叫方必須透傳 UI 展示給使用者的指紋
     *（[gateAction] 回的 `NeedConfirm.fingerprint`，經 [AutomationCore.fingerprintFor]
     * 重算交叉驗證），服務端重算當前 action/goal/target/allowedKinds 指紋，
     * 三者 exact-match 才原子消耗（[GithubA11yState.consumeConfirmation]），
     * 否則回 false 且不消耗額度（防掃描耗損與 TOCTOU 替換）。
     *
     * - [GithubA11yState.effective] 為 false（一輪三同意缺一）→ 回 false；
     * - 裁決 Allow → 直接 [perform]（不消耗確認，良性動作不吃掉確認額度）；
     * - 裁決 NeedConfirm → 指紋一致 + 原子消耗一次當輪確認才 [perform]；
     * - 裁決 Deny（越界種類）→ 即使已確認仍回 false（確認不可覆寫拒絕）。
     *
     * 執行緒約束：必須在背景執行緒呼叫（手勢分派見 [dispatchSwipe]
     * 的 ANR 防護，主執行緒呼叫一律回 false）。
     */
    fun executeConfirmed(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        confirmedFingerprint: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): Boolean {
        if (!GithubA11yState.effective()) return false
        when (AutomationCore.verdictFor(action, goalText, targetText, allowedKinds)) {
            is ArbitrationVerdict.Allow -> return perform(action)
            is ArbitrationVerdict.Deny -> return false
            is ArbitrationVerdict.NeedConfirm -> {
                val recomputed = AutomationCore.fingerprintFor(action, goalText, targetText, allowedKinds)
                if (confirmedFingerprint.isBlank() || recomputed != confirmedFingerprint) return false
                if (!GithubA11yState.consumeConfirmation(recomputed)) return false
                return perform(action)
            }
        }
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
        const val DEFAULT_ENABLED: Boolean = GithubAutomationGate.DEFAULT_ENABLED

        /**
         * 服務實例持有（產品接線用，P1 inert 修正）：
         * flavor 層 StepExecutor 經此拿到 service 執行 [executeConfirmed]，
         * null 表示系統未授權/未綁定。僅 flavor 源集訪問，main 不直接引用。
         */
        @Volatile var instance: GithubAccessibilityService? = null
            private set

        /**
         * 仲裁前攔截（與 foss 鏡像同語義的守衛路徑，S3 镜像统一）：
         * 經 [AutomationCore.verdictFor] 三態映射 —— Allow → [GateDecision.Allow]；
         * NeedConfirm（支付/刪除/發送關鍵詞或種類命中）→ [GateDecision.NeedConfirm]；
         * Deny（越界種類）→ [GateDecision.Denied]（確認不可覆寫）。
         * 原 github 慢核（dev.librepocket.slow SlowArbitrator.assess）已移除，
         * 其獨有關鍵詞已併入守衛（見 dev.librepocket.guard.SlowArbitrator），
         * 避免雙風味分叉。
         */
        fun gateAction(
            goalText: String,
            action: A11yAction,
            targetText: String = "",
            allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
        ): GateDecision {
            return when (val verdict = AutomationCore.verdictFor(action, goalText, targetText, allowedKinds)) {
                is ArbitrationVerdict.Allow -> GateDecision.Allow
                is ArbitrationVerdict.NeedConfirm -> GateDecision.NeedConfirm(
                    verdict.codes,
                    AutomationCore.fingerprintFor(action, goalText, targetText, allowedKinds),
                )
                is ArbitrationVerdict.Deny -> GateDecision.Denied(verdict.codes)
            }
        }
    }
}

/** 仲裁前攔截結論：放行 / 需二次確認（含指紋，UI 原樣回傳） / 拒絕。 */
sealed interface GateDecision {
    data object Allow : GateDecision
    data class NeedConfirm(val codes: List<ArbitrationCode>, val fingerprint: String) : GateDecision
    /** 越界種類：即使已確認亦不可執行（確認不可覆寫拒絕）。 */
    data class Denied(val codes: List<ArbitrationCode>) : GateDecision
}

/**
 * 服務側三同意狀態（記憶體態；App 內開關由設定頁寫入）。
 * effective = [GithubAutomationGate.effectiveAutomation]
 *（App 開關 + 系統授權 + 當輪確認，play 側無此類故永遠缺席）。
 *
 * P1 re-review：確認即 capability，真相來源為 `confirmedFingerprint`
 *（單槽 one-shot），`userConfirmed` 僅為相容派生視圖（只讀）。
 */
object GithubA11yState {
    @Volatile var switchOn: Boolean = GithubAutomationGate.DEFAULT_ENABLED
    @Volatile var serviceGranted: Boolean = false
    @Volatile private var confirmedFingerprint: String? = null
    @Volatile var lastCompressed: List<String> = emptyList()

    /** 相容視圖：有未消耗指紋即視為已確認（只讀用途；寫入請走 grant）。 */
    var userConfirmed: Boolean
        get() = confirmedFingerprint != null
        set(value) {
            if (!value) confirmedFingerprint = null
            // true 不直接賦值：必須經 grantConfirmation 綁定指紋，避免 ambient。
        }

    fun effective(): Boolean = GithubAutomationGate.effectiveAutomation(
        switchOn = switchOn,
        serviceGranted = serviceGranted,
        userConfirmed = confirmedFingerprint != null,
    )

    /** 授權一次確認：覆寫單槽（後授權覆蓋前者，需 UI 展示與指紋一致）。 */
    @Synchronized
    fun grantConfirmation(fingerprint: String) {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        confirmedFingerprint = fingerprint
    }

    /** 測試/設定頁相容：清空確認（等同拒絕/超時）。 */
    @Synchronized
    fun clearConfirmation() {
        confirmedFingerprint = null
    }

    /**
     * 原子消耗一次當輪確認（P1 one-shot + 指紋綁定）：
     * 僅當 stored 非 null 且與 expected exact-match 才清零回 true；
     * 否則不清除、回 false（防掃描耗損額度與 TOCTOU 替換）。
     * 良性 Allow 路徑不呼叫此函數，不吃掉確認額度。
     */
    @Synchronized
    fun consumeConfirmation(expected: String): Boolean {
        val cur = confirmedFingerprint ?: return false
        if (cur != expected) return false
        confirmedFingerprint = null
        return true
    }

    /** 舊無參過載：已廢止，一律回 false，避免 ambient 回退。 */
    @Deprecated("必須傳 expected 指紋 exact-match，避免 ambient 確認", ReplaceWith("consumeConfirmation(expected)"))
    @Synchronized
    fun consumeConfirmation(): Boolean = false
}
