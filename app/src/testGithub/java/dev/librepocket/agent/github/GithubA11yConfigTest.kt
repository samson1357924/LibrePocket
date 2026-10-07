package dev.librepocket.agent.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 github a11y service capability contract（PR#1 review blocker P1）：
 * dispatchSwipe 用 dispatchGesture()，系統要求 service 宣告
 * canPerformGestures=true，否則手勢一律被取消、Swipe 自動化形同虛設。
 * JVM 單測讀 XML 原始檔斷言（同 VoiceSttTest.findSource 模式），
 * 不做 manifest merger 解析。
 */
class GithubA11yConfigTest {

    private fun findSource(vararg relPaths: String): java.io.File {
        val bases = generateSequence(java.io.File(System.getProperty("user.dir") ?: ".")) { it.parentFile }.take(6).toList()
        for (base in bases) {
            for (rel in relPaths) {
                val f = java.io.File(base, rel)
                if (f.isFile) return f
            }
        }
        error("not found: ${relPaths.toList()} under ${System.getProperty("user.dir")}")
    }

    private fun configText(): String = findSource(
        "src/github/res/xml/accessibility_config.xml",
        "app/src/github/res/xml/accessibility_config.xml",
    ).readText()

    /** 去 XML 註解後的 config（防 `<!-- ... -->` 內的字串干擾斷言）。 */
    private fun configWithoutComments(): String =
        configText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test fun gestureCapabilityIsDeclared() {
        assertTrue(configText().contains("android:canPerformGestures=\"true\""))
    }

    @Test fun windowContentRemainsRetrievable() {
        // 快照管線（A11yWalker.snapshot(rootInActiveWindow)）的前提，不可被精簡掉。
        assertTrue(configText().contains("android:canRetrieveWindowContent=\"true\""))
    }

    @Test fun eventTypesStayLeastPrivilege() {
        // 去註解後斷言：`typeViewClicked|...|typeAllMask` 附加回歸也能鎖住，
        // 註解提及 "(no typeAllMask)" 不影響。
        assertFalse(configWithoutComments().contains("typeAllMask"))
    }

    @Test fun swipeImplementationMatchesCapability() {
        // 反向一致性：只要 service 仍用 dispatchGesture，config 就必須保留手勢能力。
        val service = findSource(
            "src/github/java/dev/librepocket/agent/github/GithubAccessibilityService.kt",
            "app/src/github/java/dev/librepocket/agent/github/GithubAccessibilityService.kt",
        ).readText()
        if (service.contains("dispatchGesture(")) {
            assertTrue(configText().contains("android:canPerformGestures=\"true\""))
        }
    }
}
