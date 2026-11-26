package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
// This file lives in dev.librepocket.router, which already has its own ToolCall
// (RouteDecision.kt: toolName/argumentsJson/callId). Alias the provider type so
// FastToolExecutor.execute's unqualified ToolCall resolves to the router one.
import dev.librepocket.provider.ToolCall as ProviderToolCall
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.web.WebExtract
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool-result contract at the TEST SEAM only (issue #19 B4 pin).
 *
 * These tests deliberately attach a [SafeToolSlice] / [FastDispatcher] in the
 * test process; they are NOT a production continuation path. Production
 * composition keeps `toolSlice = null`, sends empty `ChatRequest.tools`, and
 * records `ToolDone` only (pinned by `ProductionChatCompositionTest`); the
 * fast lane has no production caller (`FastDispatchSurfaceArchitectureTest`).
 * Nothing here may be read as wired/verified — see CAPABILITY_MATRIX §逐工具四態表.
 * The matrix's wired/verified cells are conservative constants (false for all
 * 43 tools), not derived from this seam's runtime behaviour: a passing seam
 * test never promotes either state.
 *
 * The individual behaviours are also covered in SafeToolSliceTest /
 * FastDispatchTest; this class pins the COMBINED contract in one place:
 * call-id pairing + message role + typed error code + result-identity
 * mismatch producing zero propagation.
 */
class ToolResultContractSeamTest {

    private val lspCtx = ProjectionContext(userSwitches = mapOf("lsp" to true))

    @Test
    fun seamPinsCallIdRoleTypedErrorAndZeroPropagationOnMismatch() = runBlocking {
        var backendCalls = 0
        val backend = object : LspTools.Backend {
            override suspend fun symbols(
                query: String,
                pathPrefix: String?,
                limit: Int,
            ): List<LspTools.Symbol> {
                backendCalls++
                return listOf(LspTools.Symbol("Foo:symbol", "function", "app/Main.kt", 1))
            }

            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val policy = InMemoryPolicyStore(listOf(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 10)))
        val slice = SafeToolSlice(ctxSupplier = { lspCtx }, policy = policy, backend = backend)

        val okCall = ProviderToolCall("call-seam-ok", LspTools.SYMBOLS_NAME, """{"query":"Foo"}""")
        // A registry-unready tool is a typed denial, never dispatched.
        val unreadyCall = ProviderToolCall("call-seam-deny", WebExtract.TOOL_NAME, """{"url":"https://example.invalid"}""")
        val ok = slice.execute(listOf(okCall)).single()
        val denied = slice.execute(listOf(unreadyCall)).single()

        assertFalse(ok.denied)
        assertNull(ok.denyCode)
        assertEquals("call-seam-ok", ok.callId)
        assertTrue(denied.denied)
        assertEquals(SafeToolSlice.DenyCodes.NOT_READY, denied.denyCode)
        assertEquals("call-seam-deny", denied.callId)
        assertEquals("unready tool must never reach the backend", 1, backendCalls)

        // TEST SEAM continuation shape: assistant call block + one tool role per result.
        val messages = slice.continuationMessages(listOf(okCall, unreadyCall), listOf(ok, denied))
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals(listOf("call-seam-ok", "call-seam-deny"), messages[0].toolCalls.map { it.id })
        assertEquals("tool", messages[1].role)
        assertEquals(messages[0].toolCalls[0].id, messages[1].toolCallId)
        assertEquals("tool", messages[2].role)
        assertEquals(messages[0].toolCalls[1].id, messages[2].toolCallId)
        val receipt = Json.parseToJsonElement(messages[2].text).jsonObject
        assertEquals("true", receipt.getValue("denied").jsonPrimitive.content)
        assertEquals(SafeToolSlice.DenyCodes.NOT_READY, receipt.getValue("code").jsonPrimitive.content)

        // Result identity mismatch: the seam refuses to build a continuation at
        // all (zero propagation) — no orphan tool result can ride a later wire.
        assertThrows(IllegalArgumentException::class.java) {
            slice.continuationMessages(listOf(okCall), listOf(denied))
        }
        assertEquals("mismatch must not touch the backend", 1, backendCalls)
    }

    @Test
    fun seamPinsForgedResultIdentityAsTypedSkipWithNoExecutedOutcome() = runBlocking {
        val decision = FastRouter().decide("幫我查 Foo 這個符號", lspCtx, sessionId = "seam", turnIndex = 1)
        assertEquals(RouteKind.FAST, decision.route)
        val forged = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult =
                FastExecResult("other.tool", call.callId, """{"leak":"marker-must-not-propagate"}""")
        }
        val outcome = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to forged)).dispatch(decision, lspCtx)
        val skipped = outcome as? FastDispatchOutcome.Skipped
            ?: throw AssertionError("forged result identity must not produce Executed: $outcome")
        assertEquals("RESULT_IDENTITY_MISMATCH", skipped.denyCode)
        assertFalse("forged result text must not ride the fallback", skipped.fallbackMessage!!.contains("marker-must-not-propagate"))
        // The Skipped cast above already rules out Executed; no duplicate assert.
    }
}
