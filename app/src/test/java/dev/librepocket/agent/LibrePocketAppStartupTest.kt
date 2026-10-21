package dev.librepocket.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.skill.SkillPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #15 啟動接線測試（Robolectric）：證明 production 啟動路徑真的收斂崩潰殘留。
 *
 * Robolectric 會在測試環境建立 [LibrePocketApp] 並呼叫一次 `onCreate`；本測試
 * 在 `filesDir/skills` 植入崩潰窗口殘留後再次走 `onCreate`，模擬 process restart，
 * 驗證 [dev.librepocket.skill.SkillInstaller.cleanupStaleStaging] 確實被
 * production 入口呼叫並恢復。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibrePocketAppStartupTest {

    private fun skillMd(name: String, body: String = "正文"): String =
        "---\nname: $name\ndescription: 測試片段\nallowed-tools: [alarm.create]\n---\n$body"

    @Test fun skillPaths_rootIsFilesDirSkills() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(File(app.filesDir, SkillPaths.SKILLS_DIR_NAME), SkillPaths.skillRoot(app))
    }

    @Test fun onCreate_recoversCrashWindowResidual() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = SkillPaths.skillRoot(app)
        root.deleteRecursively()
        try {
            val tid = Thread.currentThread().threadId()
            val bak = File(root, ".boot-skill.bak-${Math.abs(System.nanoTime())}-$tid").apply {
                mkdirs()
                File(this, "SKILL.md").writeText(skillMd("boot-skill", "舊版正文"), Charsets.UTF_8)
            }
            assertTrue(bak.exists())
            assertFalse(File(root, "boot-skill").exists())

            // 模擬 process restart：再次走 production 啟動路徑（onCreate）。
            val appInstance = app as LibrePocketApp
            appInstance.onCreate()

            assertTrue("啟動應恢復崩潰窗口殘留", File(root, "boot-skill/SKILL.md").isFile)
            assertFalse("恢復後 .bak 應被消費", bak.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun onCreate_isNoopWhenNoResidual() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val root = SkillPaths.skillRoot(app)
        root.deleteRecursively()
        try {
            // 無殘留時啟動不得拋錯，也不得建立 skills 目錄。
            (app as LibrePocketApp).onCreate()
            assertFalse("onCreate 不得因無殘留而建立 skills 目錄", root.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
