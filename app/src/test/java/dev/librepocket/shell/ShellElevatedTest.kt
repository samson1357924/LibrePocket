package dev.librepocket.shell

import dev.librepocket.router.GateResult
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
 * S3 `shell.elevated` 主線測試（BACKLOG D09，矩陣 §3/§4）：
 * 註冊（PRIVILEGED + FOSS/GITHUB + `privilege_bridge` 預設關 +
 * Schema 限 argv/reasonCode/timeoutMs、禁 `command` 字串）→
 * 提權校驗（白名單外放行、黑名單仍拒）→ 門禁分支 → 審計雜湊。
 */
class ShellElevatedTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    // ---- 註冊 ----

    @Test fun registry_shellElevatedIsPrivilegedBridgeDefaultOff() {
        val tool = ToolRegistry.find("shell.elevated")!!
        assertEquals(SideEffect.PRIVILEGED, tool.sideEffect)
        assertEquals(setOf(Flavor.FOSS, Flavor.GITHUB), tool.supportedFlavors)
        assertEquals("privilege_bridge", tool.annotations.requiresSwitch)
        assertEquals(false, tool.annotations.switchDefault)
    }

    @Test fun registry_shellElevatedHiddenOnPlay() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        val p = ToolRegistry.projectAll(ctx)["shell.elevated"]!!
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.FLAVOR_BLOCKED, p.reason)
    }

    @Test fun registry_shellElevatedNeedsBridgeSwitchOnSelfInstall() {
        for (flavor in listOf(Flavor.FOSS, Flavor.GITHUB)) {
            val off = ToolRegistry.projectAll(ProjectionContext(flavor = flavor))["shell.elevated"]!!
            assertEquals("$flavor", CapabilityLevel.UNAVAILABLE, off.level)
            assertEquals(DenyReason.USER_DISABLED, off.reason)
            val on = ToolRegistry.projectAll(
                ProjectionContext(flavor = flavor, userSwitches = mapOf("privilege_bridge" to true)),
            )["shell.elevated"]!!
            assertEquals("$flavor", CapabilityLevel.NATIVE, on.level)
        }
    }

    @Test fun schema_limitsToArgvReasonTimeoutWithoutCommandString() {
        val schema = ToolRegistry.find("shell.elevated")!!.jsonSchema
        assertTrue(schema.contains("argv"))
        assertTrue(schema.contains("reasonCode"))
        assertTrue(schema.contains("timeoutMs"))
        assertFalse("schema must not take a shell string: $schema", schema.contains("command"))
    }

    @Test fun schema_exactKeySetWithoutStringExecKeys() {
        // MINOR-12：schema 鍵集精確斷言 —— 只有 argv/reasonCode/timeoutMs，
        // 無任何字串形式的執行鍵（command/script/shell/cmd/sh）。
        val schema = ToolRegistry.find("shell.elevated")!!.jsonSchema
        val keys = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\\{\"type\"")
            .findAll(schema).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("argv", "reasonCode", "timeoutMs"), keys)
        assertTrue(schema.contains("\"argv\""))
        assertTrue(schema.contains("\"reasonCode\""))
        for (banned in listOf("command", "script", "cmd")) {
            assertFalse("schema must not contain exec key $banned: $schema", schema.contains("\"$banned\""))
        }
    }

    // ---- 提權校驗：白名單外放行、黑名單仍拒 ----

    @Test fun nonWhitelistedBinaryAllowedOnlyViaElevated() {
        // 直接 exec 拒絕（白名單門禁維持原判）。
        val direct = ShellPolicy.validate(listOf("dumpsys", "activity"), privateRoot)
        assertTrue("$direct", direct is Validation.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (direct as Validation.Denied).reason)
        // 提權通道放行（同 argv、同作用域）。
        val elevated = ShellPolicy.validateElevated(
            listOf("dumpsys", "activity"),
            privateRoot,
            flavor = Flavor.GITHUB,
            bridgeGranted = true,
        )
        assertTrue("$elevated", elevated is Validation.Allowed)
    }

    @Test fun blacklistStillDeniedViaElevated() {
        for (argv in listOf(
            listOf("rm", "-rf", "/"),
            listOf("rm", "-fr", "/"),
            listOf("su", "-c", "id"),
            listOf("sh", "-c", "echo hi"),
        )) {
            val v = ShellPolicy.validateElevated(argv, privateRoot, flavor = Flavor.GITHUB, bridgeGranted = true)
            assertTrue("$argv -> $v", v is Validation.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (v as Validation.Denied).reason)
        }
    }

    @Test fun elevatedCrossDomainNeedsBridgeGrant() {
        val cross = listOf("cat", "/sdcard/Download/other/x.txt")
        val denied = ShellPolicy.validateElevated(cross, privateRoot, flavor = Flavor.GITHUB, bridgeGranted = false)
        assertTrue("$denied", denied is Validation.Denied)
        val allowed = ShellPolicy.validateElevated(cross, privateRoot, flavor = Flavor.GITHUB, bridgeGranted = true)
        assertTrue("$allowed", allowed is Validation.Allowed)
    }

    // ---- 門禁 elevated 分支 ----

    @Test fun gate_elevatedNeedsConfirmWithoutConfirm() {
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        assertEquals(GateResult.NeedConfirm(tool.name), PrivilegeGate.check(tool, false, native))
        assertFalse(PrivilegeGate.canExecute(tool, false, native))
    }

    @Test fun gate_elevatedArgsWithoutReasonCodeStaysNeedConfirm() {
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        val verdict = PrivilegeGate.check(tool, true, native, mapOf("argv" to "[id]"))
        assertEquals(GateResult.NeedConfirm(tool.name), verdict)
        assertFalse(PrivilegeGate.isElevatedRequest(mapOf("argv" to "[id]")))
        assertTrue(PrivilegeGate.isElevatedRequest(mapOf("reasonCode" to "D09-bridge")))
    }

    @Test fun gate_elevatedAllowedWithReasonCodeAndConfirm() {
        val tool = ToolRegistry.find("shell.elevated")!!
        val native = Projection(tool.name, CapabilityLevel.NATIVE)
        val verdict = PrivilegeGate.check(tool, true, native, mapOf("reasonCode" to "D09-bridge"))
        assertEquals(GateResult.Allowed, verdict)
    }

    // ---- 審計：雜湊計數、禁明文、一鍵收回 ----

    @Test fun audit_executeElevatedWritesHashOnly() {
        val audit = PrivilegeAuditLog()
        val secret = "sk-secret-argv-payload"
        val request = ElevatedRequest(listOf("echo", secret), reasonCode = "D09-TEST")
        val result = ShellExecTool.executeElevated(request, DenyingElevatedRunner(), audit)
        assertTrue("$result", result is ShellResult.Denied)
        assertEquals(1, audit.size())
        val row = audit.snapshot().single()
        val dumped = audit.snapshot().toString()
        assertFalse("audit must not carry plaintext argv: $dumped", dumped.contains(secret))
        assertFalse("audit must not carry plaintext reason", dumped.contains("D09-TEST"))
        assertEquals(64, row.argvHash.length)
        assertEquals(64, row.reasonHash.length)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun audit_revokeAllClearsAndReturnsCount() {
        val audit = PrivilegeAuditLog()
        repeat(3) {
            audit.record(listOf("id"), "D09-$it", "DENY")
        }
        assertEquals(3, audit.revokeAll())
        assertEquals(0, audit.size())
        assertTrue(audit.countByVerdict().isEmpty())
    }
}
