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
import dev.librepocket.guard.ArbitrationVerdict
import dev.librepocket.guard.SlowActionKind
import dev.librepocket.guard.SlowArbitrator

/**
 * Foss 風味無障礙自動化真實現（S3，BACKLOG B5/B8，純 OSS）。
 *
 * 與 `src/github …GithubAccessibilityService` 同語義的鏡像，差異僅在
 * 接線對象（[FossAutomationGate]/[FossA11yState]）：
 * - 本類只存在於 `src/foss`（play 物理缺失），且只引用 framework +
 *   main 純 OSS 核心（[A11yWalker]/[AutomationCore]/門禁），不引用
 *   ML Kit / GMS / Azure / Shizuku 任一專有依賴（foss dex 門自證）；
 * - 門禁分層（[FossA11yState.isArmed]/[FossA11yState.effective]，與 github 鏡像同語義）：
 *   快照與良性 Allow 只查 armed（App 開關 + 系統授權）；僅敏感 NeedConfirm
 *   要求 effective（armed + 未過期確認指紋）。缺 armed 即
 *   [onAccessibilityEvent] 直接返回，不讀窗、不點擊；
 * - 管線：節點快照 → 脫敏語義壓縮（郵箱/電話先遮罩）→ 仲裁前攔截
 *   （支付/刪除/發送類強制 CONFIRM，未確認不執行）→ 緊湊 JSON
 *   序列化（無空白）供轉錄消費。
 */
class FossAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        FossA11yState.serviceGranted = true
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        FossA11yState.serviceGranted = false
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        FossA11yState.serviceGranted = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Round authorization 只查 armed（開關 + 系統授權）：已武裝即快照供
        // 觀察，未確認時仍不自動點擊；敏感確認只在 executeConfirmed NeedConfirm
        // 分支要求（見 executeConfirmed）。
        if (!FossA11yState.isArmed()) return
        // 已武裝時也只做快照 + 壓縮，絕不自動點擊；
        // 任何執行必須經 executeConfirmed（當輪二次確認後）。
        val nodes = A11yWalker.snapshot(rootInActiveWindow)
        FossA11yState.lastCompressed = AutomationCore.compressNodes(nodes).lines
    }

    override fun onInterrupt() = Unit

    /**
     * 當輪確認後的唯一執行動作入口（P1 one-shot 確認，PR#1 review blocker，
     * 與 github 鏡像同語義）。
     *
     * - Round authorization（[FossA11yState.isArmed]）缺一回 false；
     * - Allow 直接 perform（不要求確認指紋）；Deny 永拒；
     * - NeedConfirm 要求 effective + exact-match + 原子消耗才 perform。
     * TOCTOU：nodeId 為 BFS 序號，以指紋 + 60s TTL 縮窗；完整 window/bounds /
     * snapshot-generation 綁定待 StepExecutor 接線（見 github 鏡像註解）；
     * 目前無生產 caller，不經產品路徑可達。
     *
     * P1 re-review 綁定確認：必須透傳 UI 指紋並 exact-match 才消耗執行，
     * 詳見 github 鏡像 [dev.librepocket.agent.github.GithubAccessibilityService.executeConfirmed]。
     */
    fun executeConfirmed(
        action: A11yAction,
        goalText: String,
        targetText: String = "",
        confirmedFingerprint: String = "",
        allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
    ): Boolean {
        if (!FossA11yState.isArmed()) return false
        when (AutomationCore.verdictFor(action, goalText, targetText, allowedKinds)) {
            is ArbitrationVerdict.Allow -> return perform(action)
            is ArbitrationVerdict.Deny -> return false
            is ArbitrationVerdict.NeedConfirm -> {
                if (!FossA11yState.effective()) return false
                val recomputed = AutomationCore.fingerprintFor(action, goalText, targetText, allowedKinds)
                if (confirmedFingerprint.isBlank() || recomputed != confirmedFingerprint) return false
                if (!FossA11yState.consumeConfirmation(recomputed)) return false
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
        const val DEFAULT_ENABLED: Boolean = FossAutomationGate.DEFAULT_ENABLED

        /** 服務實例持有（與 github 鏡像同語義，僅 foss 源集訪問）。 */
        @Volatile var instance: FossAccessibilityService? = null
            private set

        /**
         * 仲裁前攔截（純 OSS 路徑）：經 [AutomationCore.verdictFor]
         * 三態映射 —— Allow → [FossGateDecision.Allow]；支付/刪除/發送
         * 關鍵詞或種類命中 → [FossGateDecision.NeedConfirm]；越界種類拒絕 →
         * [FossGateDecision.Denied]（確認不可覆寫）。
         */
        fun gateAction(
            goalText: String,
            action: A11yAction,
            targetText: String = "",
            allowedKinds: Set<SlowActionKind> = SlowArbitrator.DEFAULT_ALLOWED,
        ): FossGateDecision {
            return when (val verdict = AutomationCore.verdictFor(action, goalText, targetText, allowedKinds)) {
                is ArbitrationVerdict.Allow -> FossGateDecision.Allow
                is ArbitrationVerdict.NeedConfirm -> FossGateDecision.NeedConfirm(
                    verdict.codes,
                    AutomationCore.fingerprintFor(action, goalText, targetText, allowedKinds),
                )
                is ArbitrationVerdict.Deny -> FossGateDecision.Denied(verdict.codes)
            }
        }
    }
}

/** 仲裁前攔截結論：放行 / 需二次確認（含指紋） / 拒絕。 */
sealed interface FossGateDecision {
    data object Allow : FossGateDecision
    data class NeedConfirm(val codes: List<ArbitrationCode>, val fingerprint: String) : FossGateDecision
    /** 越界種類：即使已確認亦不可執行（確認不可覆寫拒絕）。 */
    data class Denied(val codes: List<ArbitrationCode>) : FossGateDecision
}

/**
 * 服務側三同意狀態（記憶體態；App 內開關由設定頁寫入）。
 * effective = [FossAutomationGate.effectiveAutomation]
 *（App 開關 + 系統授權 + 當輪確認，play 側無此類故永遠缺席）。
 *
 * P1 re-review：與 github 鏡像同語義，確認綁定指紋（單槽 one-shot +
 * 60s TTL）；[isArmed] 為 round authorization（開關 + 授權，不含確認）。
 */
object FossA11yState {
    const val CONFIRM_TTL_MS: Long = 60_000L

    @Volatile var switchOn: Boolean = FossAutomationGate.DEFAULT_ENABLED
    @Volatile var serviceGranted: Boolean = false
    @Volatile private var confirmedFingerprint: String? = null
    @Volatile private var grantedAtMs: Long = 0L
    @Volatile var lastCompressed: List<String> = emptyList()

    @Volatile var clockMs: () -> Long = System::currentTimeMillis

    fun isArmed(): Boolean = switchOn && serviceGranted

    var userConfirmed: Boolean
        get() = confirmedFingerprint != null && !isExpired()
        set(value) {
            if (!value) {
                confirmedFingerprint = null
                grantedAtMs = 0L
            }
            // true 不直接賦值：必須經 grantConfirmation 綁定指紋，避免 ambient。
        }

    fun effective(): Boolean = FossAutomationGate.effectiveAutomation(
        switchOn = switchOn,
        serviceGranted = serviceGranted,
        userConfirmed = confirmedFingerprint != null && !isExpired(),
    )

    private fun isExpired(now: Long = clockMs()): Boolean {
        val fp = confirmedFingerprint ?: return true
        if (fp.isBlank()) return true
        return now - grantedAtMs > CONFIRM_TTL_MS
    }

    @Synchronized
    fun grantConfirmation(fingerprint: String) {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        confirmedFingerprint = fingerprint
        grantedAtMs = clockMs()
    }

    @Synchronized
    fun clearConfirmation() {
        confirmedFingerprint = null
        grantedAtMs = 0L
    }

    @Synchronized
    fun consumeConfirmation(expected: String): Boolean {
        val cur = confirmedFingerprint ?: return false
        if (isExpired()) {
            confirmedFingerprint = null
            grantedAtMs = 0L
            return false
        }
        if (cur != expected) return false
        confirmedFingerprint = null
        grantedAtMs = 0L
        return true
    }

    @Deprecated("必須傳 expected 指紋 exact-match，避免 ambient 確認", ReplaceWith("consumeConfirmation(expected)"))
    @Synchronized
    fun consumeConfirmation(): Boolean = false
}
