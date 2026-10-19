package dev.librepocket.mcp

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D03 範圍測試（BACKLOG D03）：第三方工具同走投影 + 確認，預設 WRITE。
 */
class McpScopeTest {

    private val open = McpServerConfig(id = "s1", baseUrl = "https://mcp.example.com")

    /** fail-closed 斷言：區塊必須拋 [IllegalArgumentException]（不得靜默放行）。 */
    private inline fun assertRejected(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what 必須拋 IllegalArgumentException（fail-closed）")
        } catch (_: IllegalArgumentException) {
            // 預期 fail-closed。
        }
    }

    @Test fun defaultSideEffectIsWrite() {
        val def = McpScope.toToolDef(open, "search")
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
        assertEquals(CapabilityLevel.NATIVE, p.level)
        val need = McpScope.check(open, "search", false)
        assertEquals(McpGate.NeedConfirm("mcp.s1.search"), need)
        assertFalse(McpScope.canExecute(open, "search", false))
        assertTrue(McpScope.canExecute(open, "search", true))
    }

    @Test fun readLikeNamesStillNeedConfirmMcpToolsAlwaysWrite() {
        // S2-R02（取代舊 readNeedsNoConfirm——舊測 pin「傳 sideEffect=READ 即免確認」，
        // 正是被移除的呼叫端降級路徑）：MCP 子集**沒有** sideEffect 宣告
        // （McpTool.sideEffect 恆 WRITE），check/canExecute 不收 sideEffect 參數、
        // 一律按 WRITE 對待——名字看起來像「讀取」的工具（lookup）未確認仍必須
        // NeedConfirm。mutation（把 check 改成一律 Allowed）此測即紅。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "lookup").level)
        assertEquals(McpGate.NeedConfirm("mcp.s1.lookup"), McpScope.check(open, "lookup", false))
        assertFalse("MCP 工具未確認不得執行（無 READ 免確認路徑）", McpScope.canExecute(open, "lookup", false))
        assertTrue(McpScope.canExecute(open, "lookup", true))
    }

    @Test fun noPrivilegedPathMcpToolsAlwaysWrite() {
        // S2-R02：MCP 無 PRIVILEGED 路徑（恆 WRITE）。舊測以 sideEffect=PRIVILEGED
        // 傳參驗證——該參數已移除（呼叫端不得選 sideEffect），改釘「連名字像
        // 執行類的工具也走同一條恆 WRITE 確認閘」：未確認 NeedConfirm、已確認 Allowed。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "exec").level)
        assertEquals(McpGate.NeedConfirm("mcp.s1.exec"), McpScope.check(open, "exec", false))
        assertTrue(McpScope.canExecute(open, "exec", true))
        // 對照：停用時即便已確認仍 Denied（恆 WRITE 不影響 C-01 重投影判定）。
        val off = open.copy(enabledTools = mapOf("exec" to false))
        assertEquals(McpGate.Denied(DenyReason.USER_DISABLED), McpScope.check(off, "exec", true))
    }

    @Test fun deniedOutranksNeedConfirmWhenToolDisabled() {
        // re-review F-S2-05：判定順序 pin——「停用」優先於「需確認」：停用 + 未確認
        // 必須回 Denied（非 NeedConfirm）。實作先 project() 後 confirmed；mutation
        // 把兩分支對調時，沒有此測就不會紅。
        val off = open.copy(enabledTools = mapOf("exec" to false))
        assertEquals(McpGate.Denied(DenyReason.USER_DISABLED), McpScope.check(off, "exec", false))
        assertFalse(McpScope.canExecute(off, "exec", false))
        // 對照：啟用 + 未確認 → NeedConfirm（確認順序未把啟用側誤判為 Denied）。
        assertEquals(McpGate.NeedConfirm("mcp.s1.exec"), McpScope.check(open, "exec", false))
    }

    @Test fun scopeApiSignaturesPinNoSideEffectParameter() {
        // S2-R02 API 形狀 pin（Java 反射鎖定 JVM 簽名）：check/canExecute/toToolDef
        // **不得**再長出 sideEffect 參數——即使帶預設值（呼叫端可傳 READ 降級），
        // JVM 方法參數表一變，下列 getMethod 即 NoSuchMethodException → 本測紅。
        val cls = McpScope::class.java
        cls.getMethod(
            "check",
            McpServerConfig::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            ProjectionContext::class.java,
        )
        cls.getMethod(
            "canExecute",
            McpServerConfig::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            ProjectionContext::class.java,
        )
        cls.getMethod(
            "toToolDef",
            McpServerConfig::class.java,
            String::class.java,
            String::class.java,
        )
        // 簽名存在即通過；明確失敗訊息由 NoSuchMethodException 提供（上方無 assert 亦可）。
    }

    @Test fun unavailableDeniedEvenWhenConfirmed() {
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        val p = McpScope.project(cfg, "search")
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(cfg, "search", true),
        )
        assertFalse(McpScope.canExecute(cfg, "search", true))
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

    // ---- Stage6 C-01：單一投影真相（check/canExecute 內部以傳入 config 重投影） ----

    @Test fun checkReprojectsFromPassedConfigNotCallerOwnedProjection() {
        // 最關鍵鑑別力（C-01 重現翻轉）：啟用 config 的 NATIVE 投影**不能**替停用
        // config 放行。舊 API 收呼叫端 Projection 時，
        // `check(off, "search", true, project(open, "search"))` 會 Allowed；
        // 新 API 不收 Projection（跨 config 舊投影無法再被帶入），以傳入 config
        // 重投影 → Denied。mutation（拿掉重投影）會讓本測試紅。
        val staleFromEnabledConfig = McpScope.project(open, "search")
        assertEquals("前提：啟用 config 投影為 NATIVE", CapabilityLevel.NATIVE, staleFromEnabledConfig.level)
        val off = open.copy(enabled = false)
        assertEquals(
            "伺服器已停用卻沿用啟用期投影放行＝C-01 雙真相 bug",
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(off, "search", confirmed = true),
        )
        val perToolOff = open.copy(enabledTools = mapOf("search" to false))
        assertEquals(
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(perToolOff, "search", confirmed = true),
        )
        // 對照：同參數在啟用 config 下仍 Allowed（若 mutation 變成一律拒，此行紅）。
        assertEquals(McpGate.Allowed, McpScope.check(open, "search", confirmed = true))
    }

    @Test fun canExecuteReprojectsFromPassedConfigNotCallerOwnedProjection() {
        val staleFromEnabledConfig = McpScope.project(open, "search")
        assertEquals("前提：啟用 config 投影為 NATIVE", CapabilityLevel.NATIVE, staleFromEnabledConfig.level)
        val off = open.copy(enabled = false)
        assertFalse("停用 config 必須不可執行", McpScope.canExecute(off, "search", confirmed = true))
        assertFalse(
            "逐項停用必須不可執行",
            McpScope.canExecute(open.copy(enabledTools = mapOf("search" to false)), "search", confirmed = true),
        )
        // 對照：啟用 config ＋ 已確認 → true（若 mutation 變成一律 false，此行紅）。
        assertTrue(McpScope.canExecute(open, "search", confirmed = true))
    }

    @Test fun checkAndCanExecuteHonorProjectionContextSwitches() {
        // ctx 是 check/canExecute 唯一的呼叫端投影輸入：該鍵在 ctx 明關 → Denied
        //（重投影吃 ctx，不吃任何跨輪快取的舊 Projection）。
        val ctx = ProjectionContext(userSwitches = mapOf("mcp.s1.search" to false))
        assertEquals(McpGate.Denied(DenyReason.USER_DISABLED), McpScope.check(open, "search", true, ctx))
        assertFalse(McpScope.canExecute(open, "search", true, ctx))
        // 對照：他工具不受影響。
        assertEquals(McpGate.Allowed, McpScope.check(open, "other", true, ctx))
    }

    // ---- Stage6 C-02：toolName 形狀 fail-closed（已限定名/空白/控制字元/超長） ----

    @Test fun qualifiedToolNameRejectedOnEveryScopeEntryPoint() {
        // C-02 重現翻轉：`enabledTools=mapOf("search" to false)` 下，舊碼
        // project(cfg, "mcp.s1.search") 以已限定名查鍵 →缺鍵預設啟用 → NATIVE
        //（停用清單被靜默繞過、全鏈綠燈無 require 報錯）。現在一律 IAE fail-closed。
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        for (bad in listOf("mcp.s1.search", "mcp.s1.mcp.s1.search", "mcp.", "mcp.x")) {
            assertRejected("project($bad)") { McpScope.project(cfg, bad) }
            assertRejected("check($bad)") { McpScope.check(cfg, bad, confirmed = true) }
            assertRejected("canExecute($bad)") { McpScope.canExecute(cfg, bad, confirmed = true) }
            assertRejected("switchKey($bad)") { McpScope.switchKey("s1", bad) }
            assertRejected("toToolDef($bad)") { McpScope.toToolDef(cfg, bad) }
        }
        // 對照：bare 名的停用清單正確生效、同服其他工具不受影響（不是全拒）。
        assertEquals(CapabilityLevel.UNAVAILABLE, McpScope.project(cfg, "search").level)
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(cfg, "other").level)
    }

    @Test fun blankToolNameRejectedOnEveryScopeEntryPoint() {
        // 含 Unicode 空白（全形空格 U+3000、NBSP U+00A0）——isNotBlank 語意把它們
        // 也算空白（S2-D03 邊界取樣）；toToolDef 入口一併取樣（S2-D06 覆蓋一致）。
        for (bad in listOf("", " ", "\t", "\u3000", "\u00a0")) {
            assertRejected("project($bad)") { McpScope.project(open, bad) }
            assertRejected("check($bad)") { McpScope.check(open, bad, confirmed = true) }
            assertRejected("canExecute($bad)") { McpScope.canExecute(open, bad, confirmed = true) }
            assertRejected("switchKey($bad)") { McpScope.switchKey("s1", bad) }
            assertRejected("toToolDef($bad)") { McpScope.toToolDef(open, bad) }
        }
    }

    @Test fun controlCharToolNameRejectedOnEveryScopeEntryPoint() {
        // C0（NUL/CR/LF/ESC）與 DEL——與入站 parseListPage 同政策（控制字元干擾
        // 終端與以名為鍵的比較；稽核列偽造目前由入站 parseListPage 拒控制字元名擋，
        // 被拒 toolName 仍進 auditMap()["tool"]，出站側同類防護屬下一階段）。
        for (bad in listOf("sea\nrch", "se\rch", "a\u001bb", "a\u0000b", "a\u007fb")) {
            assertRejected("project($bad)") { McpScope.project(open, bad) }
            assertRejected("check($bad)") { McpScope.check(open, bad, confirmed = true) }
            assertRejected("canExecute($bad)") { McpScope.canExecute(open, bad, confirmed = true) }
            assertRejected("switchKey($bad)") { McpScope.switchKey("s1", bad) }
            assertRejected("toToolDef($bad)") { McpScope.toToolDef(open, bad) }
        }
        // 邊界對照：可列印國際化名（é/中文/內嵌空白）不誤拒。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "read_é文").level)
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "my tool").level)
    }

    @Test fun oversizedToolNameRejectedWithWireAlignedLimit() {
        // 上限與 wire 同源：McpScope 不另立魔數（直接引用 McpWire.TOOL_NAME_MAX_CHARS）。
        assertEquals("MCP 形狀閘上限必須與 wire 層同一常數", 128, McpWire.TOOL_NAME_MAX_CHARS)
        val over = "t".repeat(McpWire.TOOL_NAME_MAX_CHARS + 1)
        assertRejected("project(129 units)") { McpScope.project(open, over) }
        assertRejected("check(129 units)") { McpScope.check(open, over, confirmed = true) }
        assertRejected("canExecute(129 units)") { McpScope.canExecute(open, over, confirmed = true) }
        assertRejected("switchKey(129 units)") { McpScope.switchKey("s1", over) }
        assertRejected("toToolDef(129 units)") { McpScope.toToolDef(open, over) }
        // units 計數（非 code point）：127 t + 😀 = 129 UTF-16 units → 拒；
        // 126 t + 😀 = 128 units → 邊界仍過（改用 code point 計數此二行必有一紅）。
        assertRejected("project(129 units with emoji)") {
            McpScope.project(open, "t".repeat(McpWire.TOOL_NAME_MAX_CHARS - 1) + "😀")
        }
        assertEquals(
            CapabilityLevel.NATIVE,
            McpScope.project(open, "t".repeat(McpWire.TOOL_NAME_MAX_CHARS - 2) + "😀").level,
        )
    }

    // ---- Stage6 S2-D03/S2-D04/S2-D05：邊界取樣＋已知限制行為快照 ----

    @Test fun uppercasePrefixVariantIsBareNameAccepted() {
        // S2-D03 正向 pin：保留前綴檢查大小寫敏感——只有小寫 `mcp.` 是保留前綴，
        // `MCP.foo`（大寫變體）屬合法 bare 名照收（scope 與 wire 入站同此語意）。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "MCP.foo").level)
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "MCP.search").level)
        assertEquals("mcp.s1.MCP.foo", McpScope.switchKey("s1", "MCP.foo"))
        // 對照：小寫保留前綴仍拒（非大小寫不敏感的 startsWith）。
        assertRejected("project(mcp.foo)") { McpScope.project(open, "mcp.foo") }
    }

    @Test fun loneSurrogateAllowedByScopeAsymmetryPin() {
        // S2-D03 不對稱交叉 pin（scope 側）：requireBareToolName 不檢查孤立 surrogate
        // ——授權／投影路徑放行 `a\uD800`（不出網路，只用來查鍵／建開關鍵）；
        // 出站 McpWire.callBody 一律拒，McpServer.callTool 回 PROTOCOL（出站半邊的
        // 斷言在 McpServerSemanticsTest.loneSurrogateAsymmetryScopeAllowsButCallToolRejects）。
        val lone = "a\uD800"
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, lone).level)
        assertEquals(McpGate.NeedConfirm("mcp.s1.a\uD800"), McpScope.check(open, lone, false))
        assertEquals("mcp.s1.a\uD800", McpScope.switchKey("s1", lone))
        // 對照：成對 surrogate（emoji）同樣放行（非「凡 surrogate 皆拒」）。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "t😀").level)
    }

    @Test fun switchKeyCrossServerCollisionSnapshotKnownLimitation() {
        // known limitation 快照（S2-D05 / C-04）：serverId 可含 `.`，跨服鍵碰撞
        // `switchKey("a.b","c") == switchKey("a","b.c")`（皆 mcp.a.b.c，授權對象可能
        // 錯位）。修 C-04（收斂 serverId 形狀或改分層鍵）／接線 PR 時**應翻轉**此快照
        //（屆時改斷言兩鍵不相等），見 CAPABILITY_MATRIX gate 清單。
        assertEquals(
            "C-04 已知碰撞（修 C-04 時翻轉此測）",
            McpScope.switchKey("a.b", "c"),
            McpScope.switchKey("a", "b.c"),
        )
        assertEquals("mcp.a.b.c", McpScope.switchKey("a.b", "c"))
    }

    @Test fun disabledServerToolDefUserSwitchOverrideSnapshotKnownLimitation() {
        // known limitation 快照（S2-D05 / CAPABILITY_MATRIX gate 清單）：內建投影路徑
        // 只看 ProjectionContext.userSwitches、看不到 McpServerConfig——ctx 明寫
        // `userSwitches[mcp.s1.search]=true` 會把**停用伺服器**的 ToolDef 翻回 NATIVE。
        // 此測驗證 KDoc/CAPABILITY_MATRIX 的揭露屬實。接線 PR 以 config 重驗或收斂
        // 此分歧（或未來 annotations 加入不可覆寫的停用位）時**應翻轉**（屆時改斷言
        // UNAVAILABLE）；見 CAPABILITY_MATRIX gate 清單「toToolDef 主開關在內建投影
        // 路徑的殘餘覆寫」。
        val off = McpServerConfig.disabled("s1", "https://mcp.example.com")
        val def = McpScope.toToolDef(off, "search")
        val p = def.project(ProjectionContext(userSwitches = mapOf("mcp.s1.search" to true)))
        assertEquals(
            "殘餘覆寫快照（修此分歧時翻轉為 UNAVAILABLE）",
            CapabilityLevel.NATIVE,
            p.level,
        )
        // 同一情況下 MCP 自己的授權路徑不吃此覆寫（兩道兜底之一，對照）。
        assertEquals(
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(off, "search", confirmed = true),
        )
        // 預設 context（無覆寫）仍 UNAVAILABLE（不是恒 NATIVE 的誤讀）。
        assertEquals(CapabilityLevel.UNAVAILABLE, def.project(ProjectionContext()).level)
    }

    @Test fun enabledToolsMatchesRemoteNameExactlyWithoutNormalization() {
        // S2-R04 精確鍵語意 pin（KDoc 同步）：requireBareToolName 不做正規化
        //（不 trim、大小寫敏感），enabledTools 以遠端名精確比對——變體名
        //（前後空白／大小寫）視為不同工具，停用 `search` 不連帶停用變體。
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        assertEquals(CapabilityLevel.UNAVAILABLE, McpScope.project(cfg, "search").level)
        assertEquals("前後空白變體是另一把鍵", CapabilityLevel.NATIVE, McpScope.project(cfg, " search").level)
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(cfg, "search ").level)
        assertEquals("大小寫變體是另一把鍵", CapabilityLevel.NATIVE, McpScope.project(cfg, "Search").level)
        assertFalse(McpScope.canExecute(cfg, "search", true))
        assertTrue(McpScope.canExecute(cfg, " search", true))
    }

    // ---- Stage4: toToolDef inputSchema 透傳 + executionReady fail-closed pins ----

    @Test fun toToolDefPassesInputSchemaThrough() {
        val schema = """{"type":"object","properties":{"q":{"type":"string"}}}"""
        val def = McpScope.toToolDef(open, "search", inputSchema = schema)
        assertEquals("遠端 inputSchema 必須原樣透傳給 ToolDef.jsonSchema", schema, def.jsonSchema)
    }

    @Test fun toToolDefDefaultsToMinimalObjectSchema() {
        // 本地建構預設：toToolDef 未傳 inputSchema 時退最小 {"type":"object"}。
        // 注意這**不是 wire fallback**——遠端缺 inputSchema 一律 PROTOCOL
        //（McpWire.parseListPage fail-closed，見 McpWireD1Test 的負向 pin），
        // 此預設值不會出現在任何解析結果。交叉斷言：兩個本地預設值（toToolDef
        // 參數預設與 McpTool 建構預設）須**逐字元相同**——若任一邊漂移（如加
        // 空白），本斷言即紅，避免兩個自釘字面值各自漂移卻無人發現。
        assertEquals("""{"type":"object"}""", McpScope.toToolDef(open, "search").jsonSchema)
        assertEquals(
            "toToolDef 預設 jsonSchema 必須等於 McpTool.inputSchema 預設",
            McpTool(serverId = "s1", name = "search").inputSchema,
            McpScope.toToolDef(open, "search").jsonSchema,
        )
    }

    @Test fun toToolDefIsNotExecutionReady() {
        // 顯式雙閘 fail-closed：MCP 工具未接線前一律不可被模型看見（visibleTools 需
        // executionReady=true）。若接線翻轉此旗標，本測試紅以提醒逐項審查。
        assertFalse("MCP toToolDef 必須 executionReady=false（未接線，模型不可見）",
            McpScope.toToolDef(open, "search").executionReady)
        assertFalse(McpScope.toToolDef(open, "search", inputSchema = """{"type":"object"}""").executionReady)
    }

    @Test fun toToolDefRequiresSwitchAndSideEffect() {
        val def = McpScope.toToolDef(open, "search")
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
        val other = McpServerConfig(id = "srv2", baseUrl = "https://mcp.example.com")
        val def = McpScope.toToolDef(other, "other")
        assertEquals("mcp.srv2.other", def.name)
        assertEquals("mcp.srv2.other", def.annotations.requiresSwitch)
    }

    // ---- Stage6 C-01：toToolDef 主開關（收 config，switchDefault 接 isToolEnabled） ----

    @Test fun toToolDefDisabledServerIsUnavailableOnBuiltinProjectionPath() {
        // 主開關可見性：停用伺服器產出的 ToolDef 在**內建投影路徑**
        //（ToolDef.project + 預設 context）必須 UNAVAILABLE + USER_DISABLED，
        // 且 executionReady 恆 false（visibleTools/FastRouter 雙閘，不可執行）。
        val off = McpServerConfig.disabled("s1", "https://mcp.example.com")
        val def = McpScope.toToolDef(off, "search")
        assertFalse("executionReady 恆 false（雙閘，模型不可見）", def.executionReady)
        assertFalse("停用伺服器 switchDefault 必須 false", def.annotations.switchDefault)
        val p = def.project(ProjectionContext())
        assertEquals("內建路徑對停用伺服器必須 UNAVAILABLE", CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
        // 對照：啟用 config 的同一支工具在內建路徑仍 NATIVE（不是一律不可用）。
        val on = McpScope.toToolDef(open, "search")
        assertTrue("啟用時 switchDefault 為 true", on.annotations.switchDefault)
        assertEquals(CapabilityLevel.NATIVE, on.project(ProjectionContext()).level)
    }

    @Test fun toToolDefDisabledPerToolIsUnavailableOnBuiltinProjectionPath() {
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        val def = McpScope.toToolDef(cfg, "search")
        assertFalse("逐項停用 switchDefault 必須 false", def.annotations.switchDefault)
        val p = def.project(ProjectionContext())
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
        // 同伺服器其他工具不受影響（對照）。
        assertEquals(
            CapabilityLevel.NATIVE,
            McpScope.toToolDef(cfg, "other").project(ProjectionContext()).level,
        )
    }

    @Test fun toToolDefEnabledServerHonorsUserSwitchesOnBuiltinProjectionPath() {
        // 啟用時與 MCP 投影同鍵同語義：ctx 明關 `mcp.<id>.<tool>` → 內建路徑亦 UNAVAILABLE。
        val def = McpScope.toToolDef(open, "search")
        val ctx = ProjectionContext(userSwitches = mapOf("mcp.s1.search" to false))
        assertEquals(CapabilityLevel.UNAVAILABLE, def.project(ctx).level)
        assertEquals(DenyReason.USER_DISABLED, def.project(ctx).reason)
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
