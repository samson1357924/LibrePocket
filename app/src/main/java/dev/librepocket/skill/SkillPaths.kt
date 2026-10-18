package dev.librepocket.skill

import android.content.Context
import java.io.File

/**
 * Skill 安裝根目錄慣例（#15）。
 *
 * 目前是單一約定點：`<filesDir>/skills`。安裝流程與啟動清理都必須改用此處，
 * 避免各呼叫方各自拼路徑而漂移。未来若安裝 UI 上線，installer 的 `destRoot`
 * 亦須來自本物件。
 *
 * 現況：目前尚無 production installer（尚無安裝 UI），故 [skillRoot] 目前只被
 * [dev.librepocket.agent.LibrePocketApp] 的啟動清理使用；本物件提前建立路徑
 * contract，供未來 installer 直接沿用。
 */
object SkillPaths {
    const val SKILLS_DIR_NAME = "skills"

    /** 已安裝 Skill 的根目錄（每個 skill 一個子目錄，見 [SkillInstaller]）。 */
    fun skillRoot(context: Context): File = File(context.filesDir, SKILLS_DIR_NAME)
}
