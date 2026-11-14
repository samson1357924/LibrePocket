package dev.librepocket.agent.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dev.librepocket.agent.MainActivity

private const val TAG = "LibreVoiceSession"

// TODO(C2-assistant, Issue #137): session 內 Compose／串流 STT 不做——直接
// SpeechRecognizer 需本 App 錄音權限，與 C1 零新增權限契約衝突；
// C2 以 RecognizerIntent 委派完成（見 AssistantVoice）。
// MainActivity 已強制執行 isVoiceInteractionRoot（isTrustedVoiceSession()）
// 區分系統語音互動工作階段與外部偽造 ACTION_ASSIST；
// 實機 E2E 驗證（手勢／鎖屏／返回／多次喚起＋Samsung／Xiaomi 實機）仍於 #137 追蹤。

/**
 * C1/C2 語音助理 session（Issue #137）。
 *
 * Session 本體 UI-less（[onPrepareShow] 關閉預設 window，避免空白閃爍）；實際 UI
 * 由 MainActivity 在獨立 voice task 中承載。
 *
 * `onShow` 以 [assistantVoiceIntent(android.content.Context)] 為基礎轉真 explicit
 * （掛 MainActivity component）並經 `startVoiceActivity` 發出
 * `ACTION_ASSIST`（沿用既有 ASSIST 入口）。C1/C2 皆不在 session 內做
 * UI／串流 STT（C2 改走 RecognizerIntent 委派）。
 *
 * 生命週期：`startVoiceActivity` 成功時 **不立即** `hide()`，避免 framework
 * `hide()` 提前結束 voice task、殺掉委派中的 C2 MainActivity/STT 流程；
 * session 需維持存活直到 voice task／activity 流程完成，由 [onTaskFinished]
 * （或已隱藏狀態）收尾。`startVoiceActivity` 失敗時 fail-closed 立即
 * `hide()`，不殘留空白 session。收尾路徑具 idempotent guard，避免重複
 * `hide()`／重入迴圈。
 *
 * C2 UX 設計決策（專用 voice task）：`startVoiceActivity(assist)` 依系統語音助理
 * 契約建立獨立的 voice task（與主 App 現有對話草稿相互獨立，不覆寫主任務草稿）；
 * MainActivity 收到 `AssistantVoice.EXTRA_VOICE_INPUT` 後自動走既有
 * RecognizerIntent 委派，結果只 prefill 不自動送出（[AssistantVoice.AUTO_SEND_AFTER_VOICE]）。
 * session 本體不做 Compose／串流 STT（直接 SpeechRecognizer 需本 App 錄音權限，
 * 與 C1 零新增權限契約衝突）。
 *
 * 信任邊界：`ACTION_ASSIST` 是既有隱式入口，第三方應用亦可偽造發起——
 * `MainActivity` 透過 `isVoiceInteractionRoot`（`isTrustedVoiceSession()`）強制
 * 區分由 `startVoiceActivity` 啟動的系統可信工作階段與外部偽造 intent：
 * 不可信喚起僅開啟 App（EntryGate：ASSIST 無文字即只開 App、不自動發送，
 * 亦不自動觸發 STT），不授予額外能力。
 */
open class LibreVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var sessionHidden = false
    private var launchPending = false
    private var activeTaskId: Int? = null

    /**
     * Activity-only 流程（人類 review #5 session UI）：關預設 voice UI window，
     * 防空白閃爍；實際 UI 由 MainActivity 承載，session 本體不留可見 window。
     */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Ignore duplicate onShow while a launch is pending or an active task is running,
        // so a stale/duplicate show cannot start or kill a newer request lifecycle.
        if (launchPending || activeTaskId != null) {
            return
        }
        // New show cycle may occur after a previous finish; allow one hide for this cycle.
        sessionHidden = false
        try {
            // C2：掛 voice 請求 flag（見 AssistantVoice：只是 UI 提示，
            // MainActivity 收到後自動走既有 RecognizerIntent 委派；零新權限）。
            val assist = AssistantVoice.withVoiceInput(assistantVoiceIntent(context)).setClass(
                context,
                MainActivity::class.java,
            )
            startVoiceActivity(assist)
            // Launch accepted but task not yet started; wait for onTaskStarted to bind taskId.
            launchPending = true
        } catch (e: Exception) {
            Log.w(TAG, "startVoiceActivity failed; hiding session", e)
            // Fail-closed: clear lifecycle tracking and do not leave an empty/inactive session around.
            clearTaskState()
            finishSession()
        }
    }

    override fun onTaskStarted(intent: Intent?, taskId: Int) {
        // Bind the started task only for the current pending launch with no active task yet.
        // Unexpected/extra task starts are ignored to preserve single-active-task semantics.
        if (launchPending && activeTaskId == null) {
            activeTaskId = taskId
            launchPending = false
            super.onTaskStarted(intent, taskId)
        }
    }

    override fun onTaskFinished(intent: Intent?, taskId: Int) {
        // End session only for the current request. If finish arrives while launch is still
        // pending (before onTaskStarted bound a taskId), treat it as completion of that request
        // to avoid a stuck session. Ignore finishes for non-active/unknown tasks.
        val shouldFinish = when {
            activeTaskId != null -> taskId == activeTaskId
            launchPending -> true
            else -> false
        }
        if (!shouldFinish) return

        clearTaskState()
        // Do not call super here: framework/default implementations may also hide(),
        // and our cleanup must remain idempotent and predictable in tests and production.
        finishSession()
    }

    override fun onHide() {
        clearTaskState()
        sessionHidden = true
        super.onHide()
    }

    private fun clearTaskState() {
        launchPending = false
        activeTaskId = null
    }

    /**
     * Ends this session once per lifecycle transition. Safe to call from failure and
     * task-completion paths without repeated hide/re-entrancy loops. Resets lifecycle
     * tracking so a subsequent onShow can start a fresh cycle after the session ends.
     */
    protected open fun finishSession() {
        clearTaskState()
        if (sessionHidden) return
        sessionHidden = true
        hide()
    }
}
