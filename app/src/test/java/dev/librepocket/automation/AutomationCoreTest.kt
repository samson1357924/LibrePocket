package dev.librepocket.automation

import dev.librepocket.guard.SlowActionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 無障礙共用核心測試（BACKLOG B5/B8）：脫敏語義壓縮 + 緊湊 JSON +
 * 仲裁前攔截（支付/刪除/發送強制 CONFIRM、越界攔截、良性放行）。
 */
class AutomationCoreTest {

    // ---- 脫敏語義壓縮 ----

    @Test fun compress_redactsSensitiveText() {
        val nodes = listOf(
            A11yNode("n0", "button", text = "聯絡 test@example.com", clickable = true, centerX = 10, centerY = 20),
            A11yNode("n1", "text", text = "電話 0912-345-678"),
        )
        val compressed = AutomationCore.compressNodes(nodes)
        val dumped = compressed.lines.joinToString("\n")
        assertFalse(dumped.contains("test@example.com"))
        assertFalse(dumped.contains("0912-345-678"))
        assertTrue(dumped.contains("⟦REDACTED:EMAIL⟧"))
        assertTrue(dumped.contains("⟦REDACTED:PHONE⟧"))
        assertTrue(compressed.lines.any { it.startsWith("[tap] n0") })
    }

    @Test fun compress_dropsLayoutContainersAndCaps() {
        val nodes = listOf(
            A11yNode("n0", "container"),
            A11yNode("n1", "button", text = "確定", clickable = true),
        )
        val compressed = AutomationCore.compressNodes(nodes, maxNodes = 1)
        assertEquals(1, compressed.lines.size)
        // 容器 + 超上限各丟一個。
        assertEquals(1, compressed.droppedNodes)
        assertTrue(compressed.truncated)
    }

    @Test fun compress_keepsCenterCoordinates() {
        val nodes = listOf(
            A11yNode("n0", "button", text = "好", clickable = true, centerX = 100, centerY = 200),
        )
        val line = AutomationCore.compressNodes(nodes).lines.single()
        assertTrue("line=$line", line.contains("@(100,200)"))
    }

    // ---- 緊湊 JSON（無空白） ----

    @Test fun compactJsonShapes() {
        assertEquals("{\"tap\":\"n3\"}", AutomationCore.tapJson("n3"))
        assertEquals("{\"swipe\":[0,0,100,200]}", AutomationCore.swipeJson(0, 0, 100, 200))
        assertEquals("{\"back\":true}", AutomationCore.backJson())
        assertFalse(AutomationCore.tapJson("n3").contains(" "))
    }

    @Test fun inputJson_redactsBody() {
        val json = AutomationCore.inputJson("n5", "我的電話 0912-345-678")
        assertFalse("json=$json", json.contains("0912-345-678"))
        assertTrue(json.contains("⟦REDACTED:PHONE⟧"))
        assertTrue(json.startsWith("{\"input\":{\"node\":\"n5\",\"text\":\""))
    }

    // ---- 仲裁前攔截 ----

    @Test fun paymentDeleteSendForceConfirm() {
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "幫我轉帳 500 元給張三"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "把和他的對話刪除"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Input("n2", "hi"), "幫我把這條消息發送出去"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "用支付寶支付這單"))
    }

    @Test fun benignTapIsAllowed() {
        assertFalse(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "點一下返回"))
        assertFalse(AutomationCore.needsConfirm(A11yAction.Back, "看看當前頁面有什麼"))
    }

    @Test fun mergedSlowOnlyHintsForceConfirm() {
        // S3 镜像统一：原 github 慢核独有关鍵詞已併入守衛，foss/github 同語義。
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "幫我下單買一杯咖啡"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "去結帳"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "checkout now"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "把這個帳號註銷"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Tap("n1"), "remove account now"))
        assertTrue(AutomationCore.needsConfirm(A11yAction.Input("n2", "hi"), "送出表單"))
    }

    @Test fun outOfScopeIsIntercepted() {
        // BACK 不在允許集內：越界拒絕同樣視為攔截。
        assertTrue(
            AutomationCore.needsConfirm(
                A11yAction.Back,
                "點一下確定",
                allowedKinds = setOf(SlowActionKind.TAP),
            ),
        )
        val codes = AutomationCore.interceptCodes(
            A11yAction.Back,
            "點一下確定",
            allowedKinds = setOf(SlowActionKind.TAP),
        )
        assertTrue(codes.isNotEmpty())
    }

    @Test fun interceptCodesCarryNoPlaintext() {
        val codes = AutomationCore.interceptCodes(A11yAction.Tap("n1"), "幫我轉帳 500 元")
        assertFalse(codes.isEmpty())
        assertFalse(codes.toString().contains("轉帳"))
    }
}
