package dev.librepocket.foss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 foss a11y service capability contract（PR#1 review blocker P1，
 * 與 github 鏡像同語義，純 OSS 路徑）：
 * dispatchSwipe 用 dispatchGesture()，系統要求 service 宣告
 * canPerformGestures=true，否則手勢一律被取消。
 */
class FossA11yConfigTest {

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
        "src/foss/res/xml/accessibility_config.xml",
        "app/src/foss/res/xml/accessibility_config.xml",
    ).readText()

    /** 去 XML 註解後的 config（防 `<!-- ... -->` 內的字串干擾斷言）。 */
    private fun configWithoutComments(): String =
        configText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    @Test fun gestureCapabilityIsDeclared() {
        assertTrue(configText().contains("android:canPerformGestures=\"true\""))
    }

    @Test fun windowContentRemainsRetrievable() {
        assertTrue(configText().contains("android:canRetrieveWindowContent=\"true\""))
    }

    @Test fun eventTypesStayLeastPrivilege() {
        // 去註解後斷言：`typeViewClicked|...|typeAllMask` 附加回歸也能鎖住，
        // 註解提及 "(no typeAllMask)" 不影響。
        assertFalse(configWithoutComments().contains("typeAllMask"))
    }

    @Test fun swipeImplementationMatchesCapability() {
        val service = findSource(
            "src/foss/java/dev/librepocket/agent/foss/FossAccessibilityService.kt",
            "app/src/foss/java/dev/librepocket/agent/foss/FossAccessibilityService.kt",
        ).readText()
        if (service.contains("dispatchGesture(")) {
            assertTrue(configText().contains("android:canPerformGestures=\"true\""))
        }
    }

    @Test fun accessibilityDescriptionLocalizedInChinese() {
        val zhStrings = findSource(
            "src/foss/res/values-zh/strings.xml",
            "app/src/foss/res/values-zh/strings.xml",
        ).readText()
        assertTrue(zhStrings.contains("name=\"accessibility_description\""))
        assertTrue(zhStrings.contains("LibrePocket 小幫手：螢幕逐步協助（預設關閉；付款／刪除／發送步驟一律先詢問）。"))
    }

    @Test fun chatSharedStringsPresentInBothLocales() {
        // PR 128 Phase 4: thinking state + streaming indicator strings live in the shared
        // main source set. The shared ChatMarkdownUiTest locale tests run in all three
        // flavor unit-test tasks (testPlay/testFoss/testGithub); this file-content check
        // pins the main values/values-zh presence from this flavor's task as well.
        fun mainRes(dir: String): String = findSource(
            "src/main/res/$dir/strings.xml",
            "app/src/main/res/$dir/strings.xml",
        ).readText()
        val en = mainRes("values")
        val zh = mainRes("values-zh")
        for (name in listOf(
            "chat_thinking_state_expanded",
            "chat_thinking_state_collapsed",
            "chat_streaming_status",
            "chat_streaming_label",
        )) {
            assertTrue(en.contains("name=\"$name\""))
            assertTrue(zh.contains("name=\"$name\""))
        }
    }
}
