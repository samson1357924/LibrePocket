package dev.librepocket.skill

import android.content.Context
import java.io.File

/**
 * Skill 安裝根目錄慣例（#15）。
 *
 * 目前是單一約定點：`<filesDir>/skills`。安裝流程與啟動清理都必須改用此處，
 * 避免各呼叫方各自拼路徑而漂移（見下方現況）。
 *
 * 現況：[SkillInstaller] 已實作（含原子提交與安裝清理，均有測試覆蓋）但安裝
 * 入口（[SkillInstaller.installZip]/[SkillInstaller.installFiles]）尚未有
 * 生產呼叫方（尚無安裝 UI 接線；唯一已接線的生產 API 是啟動清理
 * [SkillInstaller.cleanupStaleStaging]，見 [dev.librepocket.agent.LibrePocketApp]），
 * 故 [skillRoot] 目前只被啟動清理使用；本物件提前建立路徑 contract，未來安裝
 * UI 接線時 installer 的 `destRoot` 直接取自 [skillRoot]，不再各自拼路徑。
 */
object SkillPaths {
    const val SKILLS_DIR_NAME = "skills"

    /** 已安裝 Skill 的根目錄（每個 skill 一個子目錄，見 [SkillInstaller]）。 */
    fun skillRoot(context: Context): File = File(context.filesDir, SKILLS_DIR_NAME)
}
