package dev.librepocket.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-B `lsp.symbols` / `lsp.diagnostics` 測試：本地樁確定性、MCP 橋接可替換、
 * 開關預設關。樁無外部依賴。
 */
class LspStubTest {

    @Test fun stubSymbolsAreDeterministic() {
        val first = LspTools.symbols("Foo", backend = LspTools.StubBackend)
        val second = LspTools.symbols("Foo", backend = LspTools.StubBackend)
        assertTrue(first is LspTools.SymbolsOutcome.Ok)
        assertTrue(second is LspTools.SymbolsOutcome.Ok)
        val a = (first as LspTools.SymbolsOutcome.Ok).symbols
        val b = (second as LspTools.SymbolsOutcome.Ok).symbols
        assertEquals(a, b)
        assertTrue(a.isNotEmpty())
        assertTrue(first.fromStub)
    }

    @Test fun stubDiagnosticsAreEmptyButWellFormed() {
        val outcome = LspTools.diagnostics("app/src/main/Example.kt", backend = LspTools.StubBackend)
        assertTrue(outcome is LspTools.DiagnosticsOutcome.Ok)
        val ok = outcome as LspTools.DiagnosticsOutcome.Ok
        assertTrue(ok.diagnostics.isEmpty())
        assertTrue(ok.fromStub)
    }

    @Test fun mcpBridgeOverridesStub() {
        val bridge = object : LspTools.Backend {
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol(query, "class", "mcp://srv/Main.kt", 42))

            override fun diagnostics(path: String): List<LspTools.Diagnostic> =
                listOf(LspTools.Diagnostic(path, 7, "error", "mcp: unresolved reference"))
        }
        val symbols = LspTools.symbols("Bar", backend = bridge)
        assertTrue(symbols is LspTools.SymbolsOutcome.Ok)
        val ok = symbols as LspTools.SymbolsOutcome.Ok
        assertFalse(ok.fromStub)
        assertEquals(listOf(LspTools.Symbol("Bar", "class", "mcp://srv/Main.kt", 42)), ok.symbols)
        val diags = LspTools.diagnostics("mcp://srv/Main.kt", backend = bridge)
        assertTrue(diags is LspTools.DiagnosticsOutcome.Ok)
        assertEquals(1, (diags as LspTools.DiagnosticsOutcome.Ok).diagnostics.size)
    }

    @Test fun blankInputIsUnavailableWithMatrixTemplate() {
        val symbols = LspTools.symbols("  ")
        assertTrue(symbols is LspTools.SymbolsOutcome.Unavailable)
        val u = symbols as LspTools.SymbolsOutcome.Unavailable
        assertEquals("EMPTY_QUERY", u.detail)
        assertTrue(u.message.contains("做不到") && u.message.contains("→"))
        val diags = LspTools.diagnostics("")
        assertTrue(diags is LspTools.DiagnosticsOutcome.Unavailable)
        assertEquals("EMPTY_PATH", (diags as LspTools.DiagnosticsOutcome.Unavailable).detail)
    }

    @Test fun lspSwitchDefaultsOff() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        for (name in listOf(LspTools.SYMBOLS_NAME, LspTools.DIAGNOSTICS_NAME)) {
            val projected = ToolRegistry.projectAll(ctx)[name]!!
            assertEquals(name, CapabilityLevel.UNAVAILABLE, projected.level)
            assertEquals(DenyReason.USER_DISABLED, projected.reason)
        }
    }

    @Test fun lspOptInBecomesNative() {
        val ctx = ProjectionContext(
            flavor = Flavor.GITHUB,
            userSwitches = mapOf(LspTools.SWITCH to true),
        )
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(ctx)[LspTools.SYMBOLS_NAME]!!.level)
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(ctx)[LspTools.DIAGNOSTICS_NAME]!!.level)
    }

    @Test fun lspToolsAreReadTier() {
        assertEquals(SideEffect.READ, ToolRegistry.find(LspTools.SYMBOLS_NAME)!!.sideEffect)
        assertEquals(SideEffect.READ, ToolRegistry.find(LspTools.DIAGNOSTICS_NAME)!!.sideEffect)
    }
}
