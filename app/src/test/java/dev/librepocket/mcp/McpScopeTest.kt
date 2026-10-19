package dev.librepocket.mcp

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03 範圍測試（BACKLOG D03）：第三方工具同走投影 + 確認，預設 WRITE。
 */
class McpScopeTest {

    private val open = McpServerConfig(id = "s1", baseUrl = "https://mcp.example.com")

    @Test fun defaultSideEffectIsWrite() {
        val def = McpScope.toToolDef("s1", "search")
        assertEquals(SideEffect.WRITE, def.sideEffect)
        assertEquals("mcp.s1.search", def.name)
    }

    @Test fun enabledToolProjectsNative() {
        val p = McpScope.project(open, "search")
        assertEquals(CapabilityLevel.NATIVE, p.level)
        assertEquals(null, p.reason)
    }

    @Test fun perToolDisableProjectsUserDisabled() {
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        val p = McpScope.project(cfg, "search")
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
        // 同伺服器其他工具不受影響。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(cfg, "other").level)
    }

    @Test fun serverDisabledProjectsUserDisabled() {
        val cfg = open.copy(enabled = false)
        val p = McpScope.project(cfg, "search")
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
    }

    @Test fun userSwitchOffProjectsUserDisabled() {
        val ctx = ProjectionContext(userSwitches = mapOf("mcp.s1.search" to false))
        val p = McpScope.project(open, "search", ctx)
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
    }

    @Test fun writeNeedsConfirmByDefault() {
        val p = McpScope.project(open, "search")
        val need = McpScope.check(open, "search", false, p)
        assertEquals(McpGate.NeedConfirm("mcp.s1.search"), need)
        assertFalse(McpScope.canExecute(open, "search", false, p))
        assertTrue(McpScope.canExecute(open, "search", true, p))
    }

    @Test fun readNeedsNoConfirm() {
        val p = McpScope.project(open, "lookup")
        assertTrue(McpScope.canExecute(open, "lookup", false, p, SideEffect.READ))
    }

    @Test fun privilegedNeedsConfirm() {
        val p = McpScope.project(open, "exec")
        assertEquals(
            McpGate.NeedConfirm("mcp.s1.exec"),
            McpScope.check(open, "exec", false, p, SideEffect.PRIVILEGED),
        )
        assertTrue(McpScope.canExecute(open, "exec", true, p, SideEffect.PRIVILEGED))
    }

    @Test fun unavailableDeniedEvenWhenConfirmed() {
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        val p = McpScope.project(cfg, "search")
        assertEquals(
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(cfg, "search", true, p),
        )
        assertFalse(McpScope.canExecute(cfg, "search", true, p))
    }

    @Test fun projectionIsPureFunction() {
        assertEquals(McpScope.project(open, "a"), McpScope.project(open, "a"))
    }

    @Test fun timeoutClampedTo15to30s() {
        assertEquals(15_000L, McpServerConfig(id = "x", baseUrl = "https://h", timeoutMs = 1_000L).effectiveTimeoutMs())
        assertEquals(30_000L, McpServerConfig(id = "x", baseUrl = "https://h", timeoutMs = 120_000L).effectiveTimeoutMs())
        assertEquals(20_000L, McpServerConfig(id = "x", baseUrl = "https://h").effectiveTimeoutMs())
        assertEquals(15_000L, McpTimeouts.MIN_MS)
        assertEquals(30_000L, McpTimeouts.MAX_MS)
    }

    @Test fun auditMapsContainNoArgsOrToken() {
        val call = McpCallResult("s1", "search", McpStatus.OK, 12L, """{"data":"s3cr3t"}""")
        val audit = call.auditMap().toString()
        assertTrue(audit.contains("s1") && audit.contains("search"))
        assertFalse(audit.contains("s3cr3t"))
        val list = McpListResult("s1", emptyList(), McpStatus.OK, 3L)
        assertTrue(list.auditMap().containsKey("serverId"))
    }

    @Test fun switchKeyShape() {
        assertEquals("mcp.s1.search", McpScope.switchKey("s1", "search"))
    }

    // ---- Stage4: toToolDef inputSchema 透傳 + executionReady fail-closed pins ----

    @Test fun toToolDefPassesInputSchemaThrough() {
        val schema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
        val def = McpScope.toToolDef("s1", "search", inputSchema = schema)
        assertEquals("遠端 inputSchema 必須原樣透傳給 ToolDef.jsonSchema", schema, def.jsonSchema)
    }

    @Test fun toToolDefDefaultsToMinimalObjectSchema() {
        // 伺服器未宣告 schema 時退最小 {"type":"object"}。交叉斷言：預設須與
        // McpTool.inputSchema 的預設**逐字元相同**——若任一邊漂移（如加空白），
        // 本斷言即紅，避免兩個自釘字面值各自漂移卻無人發現。
        assertEquals("""{"type":"object"}""", McpScope.toToolDef("s1", "search").jsonSchema)
        assertEquals(
            "toToolDef 預設 jsonSchema 必須等於 McpTool.inputSchema 預設",
            McpTool(serverId = "s1", name = "search").inputSchema,
            McpScope.toToolDef("s1", "search").jsonSchema,
        )
    }

    @Test fun toToolDefIsNotExecutionReady() {
        // 顯式雙閘 fail-closed：MCP 工具未接線前一律不可被模型看見（visibleTools 需
        // executionReady=true）。若接線翻轉此旗標，本測試紅以提醒逐項審查。
        assertFalse("MCP toToolDef 必須 executionReady=false（未接線，模型不可見）",
            McpScope.toToolDef("s1", "search").executionReady)
        assertFalse(McpScope.toToolDef("s1", "search", inputSchema = """{"type":"object"}""").executionReady)
    }

    @Test fun toToolDefRequiresSwitchAndSideEffect() {
        val def = McpScope.toToolDef("s1", "search")
        assertEquals(SideEffect.WRITE, def.sideEffect)
        assertEquals("mcp.s1.search", def.annotations.requiresSwitch)
        assertTrue(def.annotations.switchDefault)
        // fallbackHint 預設值（降級時對使用者的替代指引）釘住，避免靜默清空。
        assertEquals("ask the user to enable or confirm the MCP tool manually", def.annotations.fallbackHint)
    }

    @Test fun toToolDefInterpolatesServerAndToolName() {
        // 參數化盲點：前兩組測試都用 ("s1","search")（正是開關鍵字面），若 name/
        // requiresSwitch 被硬編碼成 "mcp.s1.search" 而非插值，前測仍綠。改用不同組
        // 釘住插值正確。
        val def = McpScope.toToolDef("srv2", "other")
        assertEquals("mcp.srv2.other", def.name)
        assertEquals("mcp.srv2.other", def.annotations.requiresSwitch)
    }

    // ---- Stage4: toString 隱私覆寫（resultJson/errorMessage 不進日誌） ----

    @Test fun callResultToStringRedactsResultAndErrorMessage() {
        val r = McpCallResult(
            serverId = "s1",
            tool = "search",
            status = McpStatus.OK,
            latencyMs = 12L,
            resultJson = """{"data":"s3cr3t-result"}""",
            isError = true,
            errorMessage = "s3cr3t-error-detail",
        )
        val s = r.toString()
        assertFalse("toString 不得外洩 resultJson 原文", s.contains("s3cr3t-result"))
        assertFalse("toString 不得外洩 errorMessage 原文", s.contains("s3cr3t-error-detail"))
        // 仍保留判錯所需狀態欄位。
        assertTrue(s.contains("s1") && s.contains("search") && s.contains("OK"))
        assertTrue("latencyMs 必須保留", s.contains("latencyMs=12"))
        assertTrue("isError 必須保留", s.contains("isError=true"))
        // 佔位符格式釘住：<N chars>（非 "<redacted>"——保留觀測性，僅去除原文）。
        assertTrue("resultJson 佔位符須為 <N chars> 形", s.contains("resultJson=<24 chars>"))
        assertTrue("errorMessage 佔位符須為 <N chars> 形", s.contains("errorMessage=<19 chars>"))
        // null resultJson 亦不得變成 "null" 字樣外洩結構（維持 <0 chars>）。
        val nullResult = McpCallResult("s1", "t", McpStatus.OK, 1L)
        assertFalse(nullResult.toString().contains("resultJson=null"))
        assertTrue(nullResult.toString().contains("resultJson=<0 chars>"))
    }

    // ---- Stage5: McpListResult.toString 覆寫（description/inputSchema 不進日誌） ----

    @Test fun listResultToStringSummarizesToolsWithoutDetail() {
        // 外部 review P3-3：data class 預設 toString 會把每支工具的 description/
        // inputSchema（伺服器控制、可極大、可含控制字元）整串帶進日誌/crash report。
        // 覆寫為計數佔位（<N tools>）；auditMap 才是稽核來源（只記名與計數）。
        val tools = listOf(
            McpTool(
                serverId = "s9",
                name = "t1",
                description = "leaky-description-token",
                inputSchema = """{"type":"object","properties":{"x":{"type":"string"}}}""",
            ),
        )
        val r = McpListResult("s9", tools, McpStatus.OK, 7L)
        val s = r.toString()
        assertFalse("toString 不得外洩 description 原文", s.contains("leaky-description-token"))
        assertFalse("toString 不得外洩 inputSchema 明細", s.contains("inputSchema"))
        assertFalse("toString 不得外洩每支工具名明細", s.contains("t1"))
        // 仍保留觀測所需狀態欄位。
        assertTrue("須保留 serverId", s.contains("s9"))
        assertTrue("須保留 status", s.contains("OK"))
        assertTrue("latencyMs 須保留", s.contains("latencyMs=7"))
        // 佔位符格式釘住：<N tools>。
        assertTrue("tools 佔位符須為 <N tools> 形", s.contains("tools=<1 tools>"))
        // auditMap 仍記名（稽核合法欄位），與 toString 分工不變。
        assertEquals("t1", (r.auditMap()["tools"] as List<*>)[0])
        assertEquals(1, r.auditMap()["toolCount"])
    }
}
