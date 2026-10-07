package dev.librepocket.clipboard

import dev.librepocket.router.PrivilegeGate
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.Projection
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import dev.librepocket.tool.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-A 剪貼簿測試：投影（前台免權限 / 背景 UNAVAILABLE+NO_PRIVILEGE /
 * 開關 `clipboard` 預設開）+ 執行器（假橋往返 / 上限 / 話術模板）。
 */
class ClipboardTest {

    private class FakeBridge(var text: CharSequence? = null) : ClipboardBridge {
        var writes = 0
        override fun readText(): CharSequence? = text
        override fun writeText(label: String, text: CharSequence) {
            writes++
            this.text = text.toString()
        }
    }

    private val foreground = ProjectionContext(flavor = Flavor.PLAY)
    private val background = foreground.copy(isForeground = false)

    private fun projectionOf(name: String, level: CapabilityLevel, reason: DenyReason? = null) =
        Projection(name, level, reason)

    // ---- 註冊與副作用 ----

    @Test fun toolsRegisteredWithCorrectSideEffects() {
        val read = ToolRegistry.find(ClipboardTools.READ_NAME)!!
        val write = ToolRegistry.find(ClipboardTools.WRITE_NAME)!!
        assertEquals(SideEffect.READ, read.sideEffect)
        assertEquals(SideEffect.WRITE, write.sideEffect)
        assertEquals(ClipboardTools.SWITCH, read.annotations.requiresSwitch)
        assertEquals(ClipboardTools.SWITCH, write.annotations.requiresSwitch)
        assertEquals(true, read.annotations.switchDefault)
        assertEquals(true, read.annotations.foregroundOnly)
        assertEquals(true, write.annotations.foregroundOnly)
        // 免權限：前台讀寫不需任何 runtime 授權。
        assertEquals(null, read.annotations.requiresPermission)
        assertEquals(null, write.annotations.requiresPermission)
        assertTrue(ToolRegistry.S1A_TOOLS.map { it.name }.containsAll(listOf(read.name, write.name)))
    }

    // ---- 投影 ----

    @Test fun foregroundNativeWithoutAnyGrant() {
        val projected = ToolRegistry.projectAll(foreground)
        assertEquals(CapabilityLevel.NATIVE, projected[ClipboardTools.READ_NAME]!!.level)
        assertEquals(CapabilityLevel.NATIVE, projected[ClipboardTools.WRITE_NAME]!!.level)
    }

    @Test fun backgroundAlwaysUnavailableNoPrivilege() {
        for (flavor in Flavor.values()) {
            val ctx = background.copy(flavor = flavor)
            val projected = ToolRegistry.projectAll(ctx)
            for (name in listOf(ClipboardTools.READ_NAME, ClipboardTools.WRITE_NAME)) {
                assertEquals(name, CapabilityLevel.UNAVAILABLE, projected[name]!!.level)
                assertEquals(name, DenyReason.NO_PRIVILEGE, projected[name]!!.reason)
            }
        }
        assertTrue(ToolRegistry.projectedTools(background).none { it.name.startsWith("clipboard.") })
    }

    @Test fun switchOffYieldsUserDisabled() {
        val ctx = foreground.copy(userSwitches = mapOf(ClipboardTools.SWITCH to false))
        val projected = ToolRegistry.projectAll(ctx)
        for (name in listOf(ClipboardTools.READ_NAME, ClipboardTools.WRITE_NAME)) {
            assertEquals(name, CapabilityLevel.UNAVAILABLE, projected[name]!!.level)
            assertEquals(name, DenyReason.USER_DISABLED, projected[name]!!.reason)
        }
    }

    @Test fun privilegeGateNeedsNoConfirm() {
        // 非 PRIVILEGED：PrivilegeGate 不需動，無確認即可執行。
        for (name in listOf(ClipboardTools.READ_NAME, ClipboardTools.WRITE_NAME)) {
            val tool = ToolRegistry.find(name)!!
            assertTrue(name, PrivilegeGate.canExecute(tool, false, projectionOf(name, CapabilityLevel.NATIVE)))
        }
    }

    // ---- 執行器 ----

    @Test fun writeThenReadRoundTrip() {
        val executor = ClipboardExecutor(FakeBridge())
        val written = executor.write("你好剪貼簿", isForeground = true)
        assertTrue(written.ok)
        val read = executor.read(isForeground = true)
        assertTrue(read.ok)
        assertEquals("你好剪貼簿", read.text)
        assertFalse(read.truncated)
    }

    @Test fun readEmptyClipboardIsOkWithEmptyText() {
        val read = ClipboardExecutor(FakeBridge(null)).read(isForeground = true)
        assertTrue(read.ok)
        assertEquals("", read.text)
    }

    @Test fun backgroundReadWriteDeniedNoPrivilege() {
        val executor = ClipboardExecutor(FakeBridge("x"))
        val read = executor.read(isForeground = false)
        assertFalse(read.ok)
        assertEquals(DenyReason.NO_PRIVILEGE, read.reason)
        assertEquals("BACKGROUND", read.detail)
        assertTrue(read.message.contains("NO_PRIVILEGE"))
        assertTrue(read.message.contains("→"))
        val write = executor.write("y", isForeground = false)
        assertFalse(write.ok)
        assertEquals(DenyReason.NO_PRIVILEGE, write.reason)
    }

    @Test fun switchOffDeniedUserDisabled() {
        val executor = ClipboardExecutor(FakeBridge("x"))
        assertEquals(DenyReason.USER_DISABLED, executor.read(true, enabled = false).reason)
        assertEquals(DenyReason.USER_DISABLED, executor.write("y", true, enabled = false).reason)
    }

    @Test fun blankWriteRejected() {
        val bridge = FakeBridge("keep")
        val result = ClipboardExecutor(bridge).write("   ", isForeground = true)
        assertFalse(result.ok)
        assertEquals("EMPTY_TEXT", result.detail)
        assertEquals("keep", bridge.text.toString())
    }

    @Test fun oversizeWriteRejected() {
        val result = ClipboardExecutor(FakeBridge()).write("a".repeat(ClipboardTools.MAX_CHARS + 1), true)
        assertFalse(result.ok)
        assertEquals("TEXT_TOO_LONG", result.detail)
    }

    @Test fun oversizeReadTruncated() {
        val big = "b".repeat(ClipboardTools.MAX_CHARS + 10)
        val read = ClipboardExecutor(FakeBridge(big)).read(isForeground = true)
        assertTrue(read.ok)
        assertTrue(read.truncated)
        assertEquals(ClipboardTools.MAX_CHARS, read.text!!.length)
    }

    @Test fun bridgeFailureSurfacedAsNotOk() {
        val broken = object : ClipboardBridge {
            override fun readText(): CharSequence? = throw IllegalStateException("binder dead")
            override fun writeText(label: String, text: CharSequence) = throw IllegalStateException("binder dead")
        }
        val executor = ClipboardExecutor(broken)
        assertFalse(executor.read(true).ok)
        assertFalse(executor.write("x", true).ok)
    }
}
