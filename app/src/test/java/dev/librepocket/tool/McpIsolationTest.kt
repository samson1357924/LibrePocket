package dev.librepocket.tool

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP isolation pin (P2 safe slice scope, #96): the MCP subset
 * (`McpWire` tools/list+call, `McpServer`/`McpTransport`) is
 * declared+implemented but NOT wired. No `mcp.*` tool may be registered at
 * all, let alone execution-ready — the slice offer stays exactly the single
 * wired tool (`lsp.symbols`).
 *
 * If a wiring PR registers MCP tools, this tripwire fails loudly and forces
 * an explicit isolation review (dispatcher/production-reference half is
 * covered by review-time grep + Build & Policy Gates, not by this unit pin).
 */
class McpIsolationTest {

    @Test fun noMcpToolRegisteredOrWired() {
        // Logical implication kept as one pin: zero mcp.* tools registered
        // entails zero execution-ready. A wiring PR registering any mcp.*
        // tool fails here and forces an explicit isolation review.
        val names = ToolRegistry.ALL.map { it.name }
        assertTrue("mcp.* tools must not be registered: $names", names.none { it.startsWith("mcp.") })
    }
}
