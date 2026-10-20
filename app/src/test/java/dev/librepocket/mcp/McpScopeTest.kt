package dev.librepocket.mcp

import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.SideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** 顯示面不得出現的原始控制/格式字元（與 [McpWire.escapeControlUnits] 政策對應）。 */
    private fun hasRawDisplayUnsafe(s: String): Boolean = s.any {
        it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F ||
            it.code == 0x061C || it.code == 0x200E || it.code == 0x200F ||
            it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 ||
            it == '\u200B' || it == '\u200C' || it == '\u200D' ||
            it == '\u2060' || it == '\uFEFF' || it == '\u2028' || it == '\u2029'
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
        // C-04：授權鍵是結構化 switchKey，不是展示名 `mcp.s1.search`。
        val ctx = ProjectionContext(userSwitches = mapOf(McpScope.switchKey("s1", "search") to false))
        val p = McpScope.project(open, "search", ctx)
        assertEquals(CapabilityLevel.UNAVAILABLE, p.level)
        assertEquals(DenyReason.USER_DISABLED, p.reason)
    }

    @Test fun writeNeedsConfirmByDefault() {
        val p = McpScope.project(open, "search")
        assertEquals(CapabilityLevel.NATIVE, p.level)
        val need = McpScope.check(open, "search", false)
        assertEquals(McpGate.NeedConfirm("mcp.s1.search", "mcp.2|s1|search"), need)
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
        assertEquals(McpGate.NeedConfirm("mcp.s1.lookup", "mcp.2|s1|lookup"), McpScope.check(open, "lookup", false))
        assertFalse("MCP 工具未確認不得執行（無 READ 免確認路徑）", McpScope.canExecute(open, "lookup", false))
        assertTrue(McpScope.canExecute(open, "lookup", true))
    }

    @Test fun noPrivilegedPathMcpToolsAlwaysWrite() {
        // S2-R02：MCP 無 PRIVILEGED 路徑（恆 WRITE）。舊測以 sideEffect=PRIVILEGED
        // 傳參驗證——該參數已移除（呼叫端不得選 sideEffect），改釘「連名字像
        // 執行類的工具也走同一條恆 WRITE 確認閘」：未確認 NeedConfirm、已確認 Allowed。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "exec").level)
        assertEquals(McpGate.NeedConfirm("mcp.s1.exec", "mcp.2|s1|exec"), McpScope.check(open, "exec", false))
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
        assertEquals(McpGate.NeedConfirm("mcp.s1.exec", "mcp.2|s1|exec"), McpScope.check(open, "exec", false))
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

    @Test fun callResultAuditEscapesServerIdControlChars() {
        // security P4：serverId 是本地配置值，可含 CR/LF／NUL——原樣進稽核可偽造
        // 日誌列。與 tool 欄同政策經 escapeControlUnits 只轉義 C0/DEL（一般字元
        // 原樣）；mutation（拿掉 serverId 的 escapeControlUnits）此測即紅。
        val r = McpCallResult("s\r\n\u0000id", "search", McpStatus.OK, 1L)
        val audit = r.auditMap()["serverId"] as String
        assertEquals("s\\u000d\\u000a\\u0000id", audit)
        assertTrue("稽核 serverId 不得含裸 C0/DEL", audit.none { it.code < 0x20 || it.code == 0x7F })
        // 正向：正常 serverId no-op 原樣（稽核語意不變）。
        assertEquals("s1", McpCallResult("s1", "search", McpStatus.OK, 1L).auditMap()["serverId"])
    }

    @Test fun listResultAuditEscapesServerIdControlChars() {
        // security P4：與 McpCallResult.auditMap 同政策；list 稽核列同樣不得被
        // serverId 的裸 CR/LF 偽造。mutation（拿掉 serverId 的 escapeControlUnits）
        // 此測即紅。
        val r = McpListResult("s\r\n\u0000id", emptyList(), McpStatus.OK, 1L)
        val audit = r.auditMap()["serverId"] as String
        assertEquals("s\\u000d\\u000a\\u0000id", audit)
        assertTrue("稽核 serverId 不得含裸 C0/DEL", audit.none { it.code < 0x20 || it.code == 0x7F })
        // 正向：正常 serverId no-op 原樣。
        assertEquals("s1", McpListResult("s1", emptyList(), McpStatus.OK, 1L).auditMap()["serverId"])
    }

    @Test fun switchKeyShape() {
        // C-04 正向 pin：保留 `mcp.` 前綴（與 requireBareToolName／wire 同源）＋
        // 長度前綴結構化鍵；展示名（ToolDef.name）仍是 `mcp.s1.search`，兩者分離。
        assertEquals("mcp.2|s1|search", McpScope.switchKey("s1", "search"))
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
        val ctx = ProjectionContext(userSwitches = mapOf(McpScope.switchKey("s1", "search") to false))
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
        // N-B5：除展示形狀外，結構化授權鍵字面 `mcp.2|s1|search` 同樣以 `mcp.`
        // 開頭——同一 startsWith 機制必須同樣拒（釘死結構化字面也不得當 bare 名）。
        val cfg = open.copy(enabledTools = mapOf("search" to false))
        for (bad in listOf("mcp.s1.search", "mcp.2|s1|search", "mcp.s1.mcp.s1.search", "mcp.", "mcp.x")) {
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
        // 終端與以名為鍵的比較；稽核列偽造雙端已收斂：入站拒名、出站 callBody
        // 對稱拒，被拒名在 auditMap/toString 只以 \uXXXX 轉義出現——Stage3 A-01）。
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
        assertEquals("mcp.2|s1|MCP.foo", McpScope.switchKey("s1", "MCP.foo"))
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
        // 展示面經 escapeControlUnits：孤立 surrogate 轉為字面 `\ud800`（R2-02 同政策）；
        // 授權鍵保留原文（`switchKey` 不轉義）。
        assertEquals(
            McpGate.NeedConfirm("mcp.s1.a\\ud800", "mcp.2|s1|a\uD800"),
            McpScope.check(open, lone, false),
        )
        assertEquals("mcp.2|s1|a\uD800", McpScope.switchKey("s1", lone))
        // 對照：成對 surrogate（emoji）同樣放行（非「凡 surrogate 皆拒」）。
        assertEquals(CapabilityLevel.NATIVE, McpScope.project(open, "t😀").level)
    }

    @Test fun switchKeyIsInjectiveAcrossServerIdsAndToolNames() {
        // C-04 翻轉（舊快照 pin 的已知碰撞）：字串拼接 `mcp.<id>.<tool>` 讓
        // `("a.b","c")` 與 `("a","b.c")` 撞同一把授權鍵（停用／確認一服誤傷
        // 另一服）。長度前綴結構化鍵對任意輸入單射——mutation（改回 "." 拼接）
        // 此測即紅。
        assertEquals("mcp.3|a.b|c", McpScope.switchKey("a.b", "c"))
        assertEquals("mcp.1|a|b.c", McpScope.switchKey("a", "b.c"))
        assertNotEquals(
            "跨服 dotted id 不得碰撞",
            McpScope.switchKey("a.b", "c"),
            McpScope.switchKey("a", "b.c"),
        )
        // `|` 是合法的遠端 tool 名字元，也不得成為新的歧義來源。
        assertEquals("mcp.3|a|b|c", McpScope.switchKey("a|b", "c"))
        assertEquals("mcp.1|a|b|c", McpScope.switchKey("a", "b|c"))
        assertNotEquals(
            "tool 名含 | 不得碰撞",
            McpScope.switchKey("a|b", "c"),
            McpScope.switchKey("a", "b|c"),
        )
        // 長度前綴本身防相鄰字元重切（("ab","c") vs ("a","bc")）。
        assertNotEquals(
            "id/tool 邊界重切不得碰撞",
            McpScope.switchKey("ab", "c"),
            McpScope.switchKey("a", "bc"),
        )

        // 使用者可見後果：關閉 A 服的鍵不得停用 B 服（授權與內建投影兩條路徑）。
        val cfgA = McpServerConfig(id = "a.b", baseUrl = "https://mcp.example.com")
        val cfgB = McpServerConfig(id = "a", baseUrl = "https://mcp.example.com")
        val ctxAOff = ProjectionContext(
            userSwitches = mapOf(McpScope.switchKey("a.b", "c") to false),
        )
        assertEquals(CapabilityLevel.UNAVAILABLE, McpScope.project(cfgA, "c", ctxAOff).level)
        assertEquals(
            "A 服的開關鍵不得誤傷 B 服（C-04 原始 bug）",
            CapabilityLevel.NATIVE,
            McpScope.project(cfgB, "b.c", ctxAOff).level,
        )
        val defA = McpScope.toToolDef(cfgA, "c")
        val defB = McpScope.toToolDef(cfgB, "b.c")
        assertNotEquals(
            "requiresSwitch 必須走結構化鍵",
            defA.annotations.requiresSwitch,
            defB.annotations.requiresSwitch,
        )
        assertEquals(
            "內建投影路徑同鍵同語義：A 關不影響 B",
            CapabilityLevel.NATIVE,
            defB.project(ctxAOff).level,
        )
        assertEquals(CapabilityLevel.UNAVAILABLE, defA.project(ctxAOff).level)

        // 誠實殘餘（文件化契約不改）：展示名仍可能同字串；不得以 name 反解授權對象。
        assertEquals("mcp.a.b.c", defA.name)
        assertEquals("mcp.a.b.c", defB.name)
    }

    // ---- Stage5 SF-A1：NeedConfirm 授權識別＝結構化 switchKey（非展示字串） ----

    @Test fun needConfirmIdentityIsSwitchKeyEvenWhenDisplayNamesCollide() {
        // C-04 在訊息邊界的殘餘：NeedConfirm 舊形狀只帶展示名 `mcp.<id>.<tool>`，
        // dotted serverId 下兩服可同字串——接線 dispatcher 若以 NeedConfirm.toolName
        // 回送 callTool 會錯位。現形狀 (toolName 展示, switchKey 授權識別)：
        // 展示名可相同，識別欄位必須不同。mutation（switchKey 改回展示名）此測即紅。
        val cfgA = McpServerConfig(id = "a.b", baseUrl = "https://mcp.example.com")
        val cfgB = McpServerConfig(id = "a", baseUrl = "https://mcp.example.com")
        val gateA = McpScope.check(cfgA, "c", confirmed = false) as McpGate.NeedConfirm
        val gateB = McpScope.check(cfgB, "b.c", confirmed = false) as McpGate.NeedConfirm

        assertEquals("展示名可相同（僅 UI 顯示用）", gateA.toolName, gateB.toolName)
        assertEquals("mcp.a.b.c", gateA.toolName)
        assertEquals("mcp.a.b.c", gateB.toolName)
        assertNotEquals(
            "授權識別不得靠展示字串：dotted-id 兩服的 switchKey 必須不同",
            gateA.switchKey,
            gateB.switchKey,
        )
        assertEquals("mcp.3|a.b|c", gateA.switchKey)
        assertEquals("mcp.1|a|b.c", gateB.switchKey)
        // 識別欄位本身就是結構化鍵，不是展示字串。
        assertNotEquals(gateA.switchKey, gateA.toolName)
    }

    // ---- Stage5 N-B6：舊 dotted 展示鍵不再是授權鍵（C-04 解耦核心） ----

    @Test fun legacyDottedDisplayKeyIsNotAnAuthorizationSwitch() {
        // 反向 pin：`userSwitches["mcp.s1.search"]`（舊 dotted 授權鍵形狀）**不再**
        // 參與授權查找——被忽略；只有結構化 `switchKey("s1","search")` 生效。
        // mutation（把 project/switchKey 查詢改回展示字串）此測即紅。
        val legacyKeyCtx = ProjectionContext(userSwitches = mapOf("mcp.s1.search" to false))
        assertEquals(
            "舊 dotted 鍵不得停用工具（被忽略）",
            CapabilityLevel.NATIVE,
            McpScope.project(open, "search", legacyKeyCtx).level,
        )
        assertEquals(
            McpGate.Allowed,
            McpScope.check(open, "search", confirmed = true, legacyKeyCtx),
        )
        // 內建投影路徑同語義：requiresSwitch 是結構化鍵，舊展示字串不參與查找。
        assertEquals(
            CapabilityLevel.NATIVE,
            McpScope.toToolDef(open, "search").project(legacyKeyCtx).level,
        )

        // 反向控制：結構化授權鍵關閉 → 生效（授權路徑＋內建投影路徑）。
        val structuredCtx = ProjectionContext(
            userSwitches = mapOf(McpScope.switchKey("s1", "search") to false),
        )
        assertEquals(
            CapabilityLevel.UNAVAILABLE,
            McpScope.project(open, "search", structuredCtx).level,
        )
        assertEquals(
            McpGate.Denied(DenyReason.USER_DISABLED),
            McpScope.check(open, "search", confirmed = true, structuredCtx),
        )
        assertEquals(
            CapabilityLevel.UNAVAILABLE,
            McpScope.toToolDef(open, "search").project(structuredCtx).level,
        )
    }

    @Test fun disabledServerToolDefUserSwitchOverrideSnapshotKnownLimitation() {
        // known limitation 快照（S2-D05 / CAPABILITY_MATRIX gate 清單）：內建投影路徑
        // 只看 ProjectionContext.userSwitches、看不到 McpServerConfig——ctx 明寫
        // `userSwitches[switchKey("s1","search")]=true` 會把**停用伺服器**的 ToolDef 翻回 NATIVE。
        // 此測驗證 KDoc/CAPABILITY_MATRIX 的揭露屬實。接線 PR 以 config 重驗或收斂
        // 此分歧（或未來 annotations 加入不可覆寫的停用位）時**應翻轉**（屆時改斷言
        // UNAVAILABLE）；見 CAPABILITY_MATRIX gate 清單「toToolDef 主開關在內建投影
        // 路徑的殘餘覆寫」。
        val off = McpServerConfig.disabled("s1", "https://mcp.example.com")
        val def = McpScope.toToolDef(off, "search")
        val p = def.project(
            ProjectionContext(userSwitches = mapOf(McpScope.switchKey("s1", "search") to true)),
        )
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
        assertEquals("mcp.2|s1|search", def.annotations.requiresSwitch)
        assertTrue(def.annotations.switchDefault)
        // fallbackHint 預設值（降級時對使用者的替代指引）釘住，避免靜默清空。
        assertEquals("ask the user to enable or confirm the MCP tool manually", def.annotations.fallbackHint)
    }

    @Test fun toToolDefInterpolatesServerAndToolName() {
        // 參數化盲點：前兩組測試都用 ("s1","search")（正是開關鍵字面），若 name/
        // requiresSwitch 被硬編碼成 "mcp.s1.search"／"mcp.2|s1|search" 而非插值，
        // 前測仍綠。改用不同組釘住插值正確。
        val other = McpServerConfig(id = "srv2", baseUrl = "https://mcp.example.com")
        val def = McpScope.toToolDef(other, "other")
        assertEquals("mcp.srv2.other", def.name)
        assertEquals("mcp.4|srv2|other", def.annotations.requiresSwitch)
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
        // 啟用時與 MCP 投影同鍵同語義：ctx 明關授權鍵（結構化 switchKey）→ 內建路徑亦 UNAVAILABLE。
        val def = McpScope.toToolDef(open, "search")
        val ctx = ProjectionContext(userSwitches = mapOf(McpScope.switchKey("s1", "search") to false))
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

    // ---- Stage3 C-05: McpTool.toString 覆寫（description/inputSchema 不進日誌） ----

    @Test fun toolToStringRedactsDescriptionAndInputSchema() {
        // C-05：result.tools 是公開的，直接 log McpTool 會輸出伺服器控制的
        // description（可含經 \uXXXX 解碼的 ESC——parseString 解碼後未過濾、
        // 只截斷到 500）與 ≤8192 字元 inputSchema。覆寫為 <N chars> 佔位
        //（比照 McpCallResult/McpListResult 既有風格）；name（入站已拒 C0/DEL，
        // 可合法含空白/非 ASCII）照常輸出。mutation（拿掉 McpTool.toString 覆寫）
        // 此測即紅（data class 預設 toString 會帶出原文與 "inputSchema=" 明細）。
        val schema = """{"type":"object","properties":{"x":{"type":"string"}}}"""
        val tool = McpTool(
            serverId = "s9",
            name = "read_é 文",
            description = "leaky\u001b[31m-description-token",
            inputSchema = schema,
        )
        val s = tool.toString()
        assertFalse("toString 不得外洩 description 原文", s.contains("leaky"))
        assertFalse("toString 不得含裸 ESC（伺服器控制字元）", s.contains('\u001b'))
        assertFalse("toString 不得外洩 inputSchema 明細", s.contains("properties"))
        // 保留識別／狀態欄位（serverId 本地配置、name 為合法識別欄位）。
        assertTrue("須保留 serverId", s.contains("s9"))
        assertTrue("name（合法識別欄位）照常輸出", s.contains("read_é 文"))
        assertTrue(s.contains("sideEffect="))
        // 佔位符格式釘住：<N chars>（與 McpCallResult/McpListResult 同風格）。
        assertTrue("description 佔位符須為 <N chars> 形",
            s.contains("description=<${tool.description.length} chars>"))
        assertTrue("inputSchema 佔位符須為 <N chars> 形",
            s.contains("inputSchema=<${schema.length} chars>"))
        // equals/copy 語意不動（data class 值相等仍看原文欄位）。
        assertEquals(tool, tool.copy())
        // 對照：McpListResult.auditMap 分工不變（仍記名與計數）。
        val list = McpListResult("s9", listOf(tool), McpStatus.OK, 1L)
        assertEquals("read_é 文", (list.auditMap()["tools"] as List<*>)[0])
        assertEquals(1, list.auditMap()["toolCount"])
    }

    @Test fun callResultAuditAndToStringEscapeRejectedToolNameControlChars() {
        // Stage3 A-01 稽核面 pin：被拒 toolName（呼叫端自備、含 CR/LF/ESC）進
        // auditMap()["tool"] 與 toString() 時只以 \uXXXX 轉義形式出現——裸 CR/LF
        // 偽造稽核列的通道關閉。合法名原樣（稽核語意不變）。
        // mutation（拿掉 escapeControlUnits 呼叫）此測即紅。
        val bad = McpCallResult("s1", "x\r\n[FAKE] status=OK", McpStatus.PROTOCOL, 1L)
        val auditTool = bad.auditMap()["tool"] as String
        assertEquals("x\\u000d\\u000a[FAKE] status=OK", auditTool)
        assertTrue(auditTool.none { it.code < 0x20 || it.code == 0x7F })
        assertTrue("toString 同為轉義形式",
            bad.toString().contains("tool=x\\u000d\\u000a[FAKE] status=OK"))
        // 正向：合法名（無 C0）原樣——既有稽核語意不變。
        val ok = McpCallResult("s1", "search", McpStatus.OK, 1L)
        assertEquals("search", ok.auditMap()["tool"])
        assertTrue(ok.toString().contains("tool=search"))
    }

    @Test fun loneSurrogateToolNameIsEscapedOnAuditFace() {
        // S3-D02 稽核面 pin：出站 callBody 對孤立 surrogate 是 fail-closed 拒——
        // 進入 McpCallResult.tool 的孤立 surrogate 在 auditMap()["tool"]／
        // toString() 不得原樣（日誌損壞編碼、政策不對稱），只以 \uXXXX 轉義
        // 出現；成對 surrogate 原樣。mutation（拿掉 escapeControlUnits 的孤立
        // surrogate 分支）此測即紅。
        val lone = McpCallResult("s1", "a\ud800b", McpStatus.PROTOCOL, 1L)
        val auditTool = lone.auditMap()["tool"] as String
        assertEquals("孤立 surrogate 轉義進稽核", "a\\ud800b", auditTool)
        assertFalse("auditMap tool 不得含孤立 surrogate", auditTool.any { it.isSurrogate() })
        assertTrue("toString 同為轉義形式", lone.toString().contains("tool=a\\ud800b"))
        assertFalse("toString 不得含孤立 surrogate", lone.toString().any { it.isSurrogate() })
        // 對照：成對 surrogate（emoji）名原樣（非「凡 surrogate 皆轉義」）。
        val pair = McpCallResult("s1", "😀", McpStatus.OK, 1L)
        assertEquals("😀", pair.auditMap()["tool"])
        // McpTool.toString 同政策（S3-D04）：name 的孤立 surrogate 亦轉義。
        val tool = McpTool(serverId = "s1", name = "t\ud800", description = "d")
        val ts = tool.toString()
        assertFalse("McpTool.toString 不得含孤立 surrogate", ts.any { it.isSurrogate() })
        assertTrue("McpTool.toString 的 name 同為轉義形式", ts.contains("name=t\\ud800"))
    }

    @Test fun toolToStringEscapesNameControlCharsButKeepsLegalNames() {
        // S3-D04：McpTool.toString 的 name 也套 escapeControlUnits——與
        // McpCallResult 稽核面政策對齊。name 含 C0（本地建構／被拒名場景）→
        // 轉義、無裸 C0/DEL；合法名（read_é文、含空白）no-op 原樣輸出。
        // mutation（拿掉 McpTool.toString name 的 escapeControlUnits）此測即紅。
        val bad = McpTool(serverId = "s1", name = "read\r\n_x", description = "d")
        val s = bad.toString()
        assertTrue("McpTool.toString 不得含裸 C0/DEL",
            s.none { it.code < 0x20 || it.code == 0x7F })
        assertTrue("C0 以 \\uXXXX 轉義出現", s.contains("name=read\\u000d\\u000a_x"))
        // 正向：合法名 no-op（非 ASCII、含空白皆原樣；既有 C-05 測不削弱）。
        val legal = McpTool(serverId = "s1", name = "read_é文", description = "d")
        assertTrue("合法名（read_é文）原樣輸出", legal.toString().contains("name=read_é文"))
        val spaced = McpTool(serverId = "s1", name = "read_é 文", description = "d")
        assertTrue("合法名（含空白）原樣輸出", spaced.toString().contains("name=read_é 文"))
    }

    @Test fun locallyConstructedToolNameFormatCharsEscapedInAuditAndToString() {
        // F-03/RT-03：McpTool 可由 wire 以外來源本地建構——name 含 CR/LF、C1、
        // bidi、零寬時，auditMap()["tools"] 與 toString() 只以 `\uXXXX` 出現，
        // 不得有裸控制/格式字元（稽核列偽造／視覺偽裝通道關閉）。
        val badName = "a\r\n\u0085\u202e\u200b_b"
        val tool = McpTool(serverId = "s1", name = badName, description = "d")
        val list = McpListResult("s1", listOf(tool), McpStatus.OK, 1L)
        val audited = (list.auditMap()["tools"] as List<*>)[0] as String
        assertEquals("a\\u000d\\u000a\\u0085\\u202e\\u200b_b", audited)
        assertFalse("audit tools 不得含裸控制/格式字元", hasRawDisplayUnsafe(audited))
        val ts = tool.toString()
        assertFalse("McpTool.toString 不得含裸控制/格式字元", hasRawDisplayUnsafe(ts))
        assertTrue("name 以 \\uXXXX 轉義", ts.contains("name=a\\u000d\\u000a\\u0085\\u202e\\u200b_b"))
        // 合法名 no-op（既有稽核語意不變）。
        val legal = McpTool(serverId = "s1", name = "read_é 文", description = "d")
        val legalAudit = (McpListResult("s1", listOf(legal), McpStatus.OK, 1L)
            .auditMap()["tools"] as List<*>)[0]
        assertEquals("read_é 文", legalAudit)
        assertTrue(legal.toString().contains("name=read_é 文"))
    }

    @Test fun serverIdControlCharsEscapedInAllThreeToStrings() {
        // F-03：三個 toString 的 serverId 一律 escapeControlUnits（本地配置值可含
        // CR/LF/C1），與 auditMap 的 serverId 政策一致；CR/LF 不得偽造日誌列。
        val id = "s\r\n\u0085id"
        val tool = McpTool(serverId = id, name = "search", description = "d")
        val list = McpListResult(id, listOf(tool), McpStatus.OK, 1L)
        val call = McpCallResult(id, "search", McpStatus.OK, 1L)
        for (s in listOf(tool.toString(), list.toString(), call.toString())) {
            assertFalse("toString 不得含裸控制/格式字元", hasRawDisplayUnsafe(s))
            assertTrue(
                "serverId 以 \\uXXXX 轉義",
                s.contains("serverId=s\\u000d\\u000a\\u0085id"),
            )
        }
        // auditMap 同政策（既有 C0 測已釘；此處補 C1）。
        assertEquals("s\\u000d\\u000a\\u0085id", list.auditMap()["serverId"])
        assertEquals("s\\u000d\\u000a\\u0085id", call.auditMap()["serverId"])
    }

    @Test fun configIdEscapedOnDisplayButRawInIdentityKey() {
        // RT-04：config.id 在模型可見展示面（ToolDef.name/description、
        // Projection.toolName）經 escapeControlUnits；授權身份（switchKey）用原文。
        val id = "s\r\n\u0085\u202e\u200b"
        val cfg = McpServerConfig(id = id, baseUrl = "https://mcp.example.com")
        val def = McpScope.toToolDef(cfg, "search")
        assertFalse("ToolDef.name 不得含裸控制/格式字元", hasRawDisplayUnsafe(def.name))
        assertFalse("description 不得含裸控制/格式字元", hasRawDisplayUnsafe(def.description))
        assertTrue(def.name.contains("s\\u000d\\u000a\\u0085\\u202e\\u200b"))
        assertTrue(def.description.contains("s\\u000d\\u000a\\u0085\\u202e\\u200b"))
        // Projection.toolName 同 display policy。
        val projection = McpScope.project(cfg, "search")
        assertFalse("Projection.toolName 不得含裸控制/格式字元",
            hasRawDisplayUnsafe(projection.toolName))
        assertEquals(def.name, projection.toolName)
        // 身份：switchKey 保留原文（不轉義）且仍對 (id, tool) 單射。
        val key = McpScope.switchKey(id, "search")
        assertEquals("mcp.${id.length}|$id|search", key)
        assertTrue("switchKey 必須含原始 id", key.contains(id))
        assertNotEquals("不同 id 仍不碰撞", McpScope.switchKey("s", "search"), key)
        assertNotEquals(
            "長度前綴維持單射（含格式字元 id）",
            McpScope.switchKey(id, "a|b"),
            McpScope.switchKey(id + "|a", "b"),
        )
        // 正常 id 完全不變。
        assertEquals("mcp.s1.search", McpScope.toToolDef(open, "search").name)
        assertEquals(
            "Third-party MCP tool search on server s1 (default WRITE).",
            McpScope.toToolDef(open, "search").description,
        )
        assertEquals("mcp.2|s1|search", McpScope.switchKey("s1", "search"))
    }

    @Test fun remoteToolNameEscapedOnDisplayButRawInIdentityKey() {
        // R2-02／S2-02：遠端 toolName 在模型可見展示面（ToolDef.name／description、
        // Projection.toolName）經 escapeControlUnits；授權身份（switchKey）用原文。
        val cfg = McpServerConfig(id = "s1", baseUrl = "https://mcp.example.com")
        for (tool in listOf(
            "a\u2028b", // LS（行分隔）
            "a\u2029b", // PS（段落分隔）
            "a\u202eb", // RLO（bidi 重排）
            "a\u0085b", // NEL（C1，部分工具視為換行）
            "a\u200bb", // ZWSP（零寬）
            "a\u2066b", // LRI（bidi isolate）
        )) {
            val def = McpScope.toToolDef(cfg, tool)
            assertFalse("ToolDef.name 不得含裸控制/格式字元（$tool）",
                hasRawDisplayUnsafe(def.name))
            assertFalse("description 不得含裸控制/格式字元（$tool）",
                hasRawDisplayUnsafe(def.description))
            val projection = McpScope.project(cfg, tool)
            assertFalse("Projection.toolName 不得含裸控制/格式字元（$tool）",
                hasRawDisplayUnsafe(projection.toolName))
            assertEquals(def.name, projection.toolName)
            // 身份仍用原文：結構化 switchKey 原樣含 remote toolName。
            val key = McpScope.switchKey("s1", tool)
            assertEquals("mcp.2|s1|$tool", key)
            assertTrue("switchKey 必須含原始 toolName", key.contains(tool))
            assertEquals("requiresSwitch 必須是原始結構化鍵", key, def.annotations.requiresSwitch)
            assertNotEquals("展示名不得當授權鍵", def.name, key)
            // 投影／開關查找以原文鍵為準（escaped display 不參與授權）。
            val off = McpScope.project(
                cfg,
                tool,
                ProjectionContext(userSwitches = mapOf(key to false)),
            )
            assertEquals(CapabilityLevel.UNAVAILABLE, off.level)
            assertEquals(DenyReason.USER_DISABLED, off.reason)
            assertFalse(
                "enabledTools 以原始 toolName 為鍵",
                cfg.copy(enabledTools = mapOf(tool to false)).isToolEnabled(tool),
            )
            // 顯示形狀：id 正常、toolName 轉義。
            assertTrue(def.name.startsWith("mcp.s1."))
            assertTrue(def.name.contains(McpWire.escapeControlUnits(tool)))
        }
        // 合法名完全不變（no-op）。
        assertEquals("mcp.s1.search", McpScope.toToolDef(open, "search").name)
        assertEquals(
            "Third-party MCP tool search on server s1 (default WRITE).",
            McpScope.toToolDef(open, "search").description,
        )
        assertEquals("mcp.2|s1|search", McpScope.switchKey("s1", "search"))
    }

    @Test fun escapedDisplayCollisionIsNonAuthoritative() {
        // R2-04／S2-04：escape 不單射——原始 U+2028 與字面六字元 `\u2028` 顯示
        // 相同；身份（switchKey，原文＋長度前綴）仍不同且為授權唯一真相。
        val cfg = McpServerConfig(id = "s1", baseUrl = "https://mcp.example.com")
        val rawTool = "a\u2028b"
        val literalTool = "a\\u2028b"
        assertEquals(
            "原始 U+2028 與字面 \\u2028 的展示名碰撞",
            McpScope.toToolDef(cfg, rawTool).name,
            McpScope.toToolDef(cfg, literalTool).name,
        )
        assertNotEquals(
            "toolName 碰撞不影響授權鍵",
            McpScope.switchKey("s1", rawTool),
            McpScope.switchKey("s1", literalTool),
        )
        val rawId = "s\u2028"
        val literalId = "s\\u2028"
        assertEquals(
            "原始 U+2028 與字面 \\u2028 的 id 展示名碰撞",
            McpScope.toToolDef(cfg.copy(id = rawId), "search").name,
            McpScope.toToolDef(cfg.copy(id = literalId), "search").name,
        )
        assertNotEquals(
            "id 碰撞不影響授權鍵",
            McpScope.switchKey(rawId, "search"),
            McpScope.switchKey(literalId, "search"),
        )
    }
}
