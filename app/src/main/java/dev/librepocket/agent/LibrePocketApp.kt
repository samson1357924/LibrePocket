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
 * 例外不吞，避免隱藏 OOM 等根因）；刪除類 attempt 工作量由
 * [SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN]、還原型 attempt 由
 * [SkillInstaller.MAX_RESTORES_PER_RUN] 各自約束（上限計 attempt——失敗亦計入；
 * 超出者本 run KEPT 且零車道 I/O、下次啟動收斂。「I/O＝車道工作量（tree walk／
 * unlink／rename／SKILL.md 讀取＋解析）」的單一定義見 SkillInstaller 清理
 * 預算 KDoc 的 I/O 定義段（經 [SkillInstaller.MAX_CLEANUP_ENTRIES_PER_RUN]
 * 契約註記引用；枚舉／分類探針／齡期 `lastModified()` stat 固定成本的列舉
 * 見該段，不在此重述）），避免 cold start 無界 I/O。
 *
 * 誠實等級註記（自動測試覆蓋面）：**`onCreate` 的 `catch (e: Exception)` 分支
 * 沒有自動測試縫**——[SkillInstaller.cleanupStaleStaging] 不是 hook 型 seam
 * （內部已 best-effort 吞錯，本檔刻意不新增注入點），「清理拋錯仍照常啟動、
 * 僅記 warning」的保真仰賴 code review；對應的 `LibrePocketAppStartupTest`
 * 只釘「清理確實被啟動路徑呼叫 + 殘留被收斂 + 無殘留為 no-op」的正向形狀，
 * 不覆蓋注入式失敗分岔。零殘留為靜默 fast-path：僅 recovery 計數 >0 時
 * `Log.w`（避免無殘留啟動的日誌噪音），計數為**成功**處置（還原＋刪除合併，
 * attempt 總數另有測試縫，見 [SkillInstaller.cleanupAttemptsForTest]；
 * 詳見 [SkillInstaller.cleanupStaleStaging] 的 @return）。
 * 行程閘門分支（main 才清理／`:voice` 跳過）由 [shouldRunSkillCleanup] 純函數
 * JVM 單測＋ Robolectric `ShadowApplication.setProcessName` 多行程模擬覆蓋
 *（見 `LibrePocketAppStartupTest`；Robolectric 預設 `getProcessName()` 即回
 * `packageName`，等同 main 行程）。
 *
 * 契約：清理必須發生在「任何安裝之前」。多行程現實（assistant `:voice`
 * 行程，見主 manifest）：三個 assistant service 跑在
 * `android:process=":voice"`，其行程啟動同樣建立 [LibrePocketApp] 實例並走
 * `onCreate`——故清理先過行程閘門：僅 main 行程
 * （`Application.getProcessName() == packageName`，API 28+，minSdk 33 可直接用）
 * 執行 [SkillInstaller.cleanupStaleStaging]；`:voice`（及任何非 main／未知
 * processName）行程跳過清理並記一行 log（fail-closed：`null` 亦跳過）。
 * 此閘門只防「`:voice` 啟動清理與主行程安裝 commit／rollback 窗口
 * （rename target→backup 到 rename staging→target 之間 target 缺席）併發」：
 * [SkillInstaller] 的 per-skill 鎖僅行程內有效，「單一合法 `.bak`＋target
 * 缺席不問齡期還原」規則在併發下可能與主行程交錯、導致覆蓋安裝失敗／回滾失真；
 * 跨行程與安裝的序列化仍由 [SkillInstaller.cleanupStaleStaging]
 * 呼叫方契約約束（不與 install 併發；備份規則本身 Stage 1 不動，留給 Stage 3）。
 * `onCreate` 目前除清理外無其他初始化（[SkillPaths.skillRoot] 僅拼路徑、
 * 零 I/O），故只需閘清理一路；`:voice` 下 `filesDir/skills` 不被觸碰。
 */
class LibrePocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val processName = Application.getProcessName()
        if (!shouldRunSkillCleanup(processName, packageName)) {
            Log.i(TAG, "skip skill cleanup in ${processName ?: "unknown"}")
            return
        }
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

/**
 * 行程閘門純判定（Stage 1 finding A）：僅 main 行程可跑啟動清理。
 *
 * `processName == packageName` 即 main 行程；`:voice` 後綴（及任何其他非 main
 * 名）→ false。`processName` 為 `null`（框架理論上可回 null：無 bound
 * application 時）→ false（fail-closed：未知行程永不清理）。
 * 跳過清理的行程只記一行 `"skip skill cleanup in <proc>"`（僅包名／進程後綴，
 * 無敏感資訊），不觸碰 `filesDir/skills`。
 */
internal fun shouldRunSkillCleanup(processName: String?, packageName: String): Boolean =
    processName == packageName
