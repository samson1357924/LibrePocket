package dev.librepocket.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 1 finding A：行程閘門純判定單測（plain JVM，無需 Robolectric）。
 *
 * [shouldRunSkillCleanup] 不依賴任何 Android API，故以普通本地單測鎖定
 * 真值表；`onCreate` 的接線（取 `Application.getProcessName()` 比對
 * `packageName`）另由 `LibrePocketAppStartupTest` 以 Robolectric
 * `ShadowApplication.setProcessName` 多行程模擬覆蓋。
 */
class LibrePocketAppProcessGuardTest {

    @Test fun mainProcess_runsCleanup() {
        assertTrue(shouldRunSkillCleanup("dev.librepocket.agent.foss", "dev.librepocket.agent.foss"))
    }

    @Test fun voiceProcess_skipsCleanup() {
        assertFalse(
            shouldRunSkillCleanup(
                "dev.librepocket.agent.foss:voice",
                "dev.librepocket.agent.foss",
            ),
        )
    }

    @Test fun unknownProcessName_skipsCleanup() {
        assertFalse(shouldRunSkillCleanup(null, "dev.librepocket.agent.foss"))
    }

    @Test fun otherProcess_skipsCleanup() {
        assertFalse(shouldRunSkillCleanup("com.other.app", "dev.librepocket.agent.foss"))
    }
}
