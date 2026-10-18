package dev.librepocket.agent

import android.app.Application
import android.util.Log
import dev.librepocket.skill.SkillInstaller
import dev.librepocket.skill.SkillPaths

/**
 * Application entry point. Infrastructure skeleton only:
 * no agent loop, no tools, no business logic.
 *
 * #15 崩溃恢復：啟動時收斂上一次安裝被 kill 留下的 staging／.bak 殘留
 * （見 [SkillInstaller.cleanupStaleStaging]）。此為 best-effort：清理失敗
 * 不得妨礙 App 啟動，故吞掉例外；僅在實際恢復條目時記錄 warning。
 *
 * 契約：清理必須發生在「任何安裝之前」，且 [SkillInstaller] 的 per-skill 鎖
 * 只在單行程內有效——本 App 目前為單行程、安裝由 UI 觸發，故安全；未來若引入
 * 背景更新或第二行程，須先序列化（見 [SkillInstaller.cleanupStaleStaging] 契約）。
 */
class LibrePocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val recovered = SkillInstaller.cleanupStaleStaging(SkillPaths.skillRoot(this))
            if (recovered > 0) Log.w(TAG, "skill install recovery: $recovered entries")
        }
    }

    private companion object {
        const val TAG = "LibrePocketApp"
    }
}
