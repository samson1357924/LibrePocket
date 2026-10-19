package dev.librepocket.agent

import android.app.Application
import android.util.Log
import dev.librepocket.skill.SkillInstaller
import dev.librepocket.skill.SkillPaths

/**
 * Application entry point. Infrastructure skeleton only:
 * no agent loop, no tools, no business logic.
 *
 * #15 崩潰恢復：啟動時收斂上一次安裝被 kill 留下的 staging／.bak 殘留
 * （見 [SkillInstaller.cleanupStaleStaging]）。此為 best-effort：清理失敗
 * 不得妨礙 App 啟動，故僅捕 [Exception] 並記錄 warning（VM `[Error]` 一類致命
 * 例外不吞，避免隱藏 OOM 等根因）；刪除類工作量由
 * [SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN]、還原型由
 * [SkillInstaller.MAX_RESTORES_PER_RUN] 各自約束，避免 cold start 無界 I/O。
 *
 * 誠實等級註記（自動測試覆蓋面）：**`onCreate` 的 `catch (e: Exception)` 分支
 * 沒有自動測試縫**——[SkillInstaller.cleanupStaleStaging] 不是 hook 型 seam
 * （內部已 best-effort 吞錯，本檔刻意不新增注入點），「清理拋錯仍照常啟動、
 * 僅記 warning」的保真仰賴 code review；對應的 `LibrePocketAppStartupTest`
 * 只釘「清理確實被啟動路徑呼叫 + 殘留被收斂 + 無殘留為 no-op」的正向形狀，
 * 不覆蓋注入式失敗分岔。零殘留為靜默 fast-path：僅 recovery 計數 >0 時
 * `Log.w`（避免無殘留啟動的日誌噪音），計數為 combo（還原＋刪除合併，
 * 見 [SkillInstaller.cleanupStaleStaging] 的 @return）。
 *
 * 契約：清理必須發生在「任何安裝之前」，且 [SkillInstaller] 的 per-skill 鎖
 * 只在單行程內有效——本 App 目前為單行程、安裝由 UI 觸發，故安全；未來若引入
 * 背景更新或第二行程，須先序列化（見 [SkillInstaller.cleanupStaleStaging] 契約）。
 */
class LibrePocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val recovered = SkillInstaller.cleanupStaleStaging(SkillPaths.skillRoot(this))
            if (recovered > 0) Log.w(TAG, "skill install recovery: $recovered entries")
        } catch (e: Exception) {
            // 清理失敗不得妨礙 App 啟動：記錄可診斷的 warning（含堆疊）後繼續；
            // 空殘留（filesDir/skills 尚不存在）為 fast-path no-op，不會走到此處。
            Log.w(TAG, "skill install recovery skipped", e)
        }
    }

    private companion object {
        const val TAG = "LibrePocketApp"
    }
}
