package dev.librepocket.router

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolDef
import dev.librepocket.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3 (#19) fast-lane dispatch tests: [FastRouter] intent routing →
 * [FastDispatcher] executor dispatch, JVM-only with fake backends.
 *
 * Scope: pure routing/decision/dispatch (no provider, no TurnController, no
 * factory). Production composition stays records-only (`toolSlice = null`
 * pinned elsewhere); the capability matrix is unchanged
 * (wired=false/verified=false). Device E2E is PENDING — no device in this
 * environment; the wired/verified flips wait for the E2E PR.
 *
 * Rule under test (fail-safe ordering): a new tool must gain its RULES
 * template AND its NEGATION verbs BEFORE its `executionReady` flips. A
 * RULES gap is fail-safe (no template ⇒ DENIED no-match, never executes);
 * a NEGATION gap is NOT (a complete-args negated intent would sail through
 * to FAST). `lsp.symbols` is the reference expansion.
 */
class FastDispatchTest {

    private val lspOn = ProjectionContext(userSwitches = mapOf("lsp" to true))
    private val lspOnCalendarGranted = lspOn.copy(
        grantedPermissions = setOf("android.permission.READ_CALENDAR"),
    )

    private class CountingBackend : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            return listOf(LspTools.Symbol("$query:example", "function", "app/src/main/Example.kt", 1))
        }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private class CountingFake(override val toolName: String) : FastToolExecutor {
        val invocations = AtomicInteger(0)
        override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
            invocations.incrementAndGet()
            return FastExecResult(toolName, call.callId, """{"ok":true}""")
        }
    }

    private fun decide(
        text: String,
        ctx: ProjectionContext = lspOn,
    ): RouteDecision = FastRouter().decide(text, ctx, sessionId = "s1", turnIndex = 1)

    private fun lspDispatcher(
        backend: LspTools.Backend,
        extra: Map<String, FastToolExecutor> = emptyMap(),
    ): FastDispatcher = FastDispatcher(
        mapOf(LspTools.SYMBOLS_NAME to LspSymbolsExecutor(backend)) + extra,
    )

    private fun skippedOf(out: FastDispatchOutcome, text: String): FastDispatchOutcome.Skipped {
        val skipped = out as? FastDispatchOutcome.Skipped
        assertNotNull("$text must not execute (expected Skipped, got $out)", skipped)
        return skipped!!
    }

    @Test fun routeToExecutionEndToEndWithFakeExecutor() = runBlocking {
        val backend = CountingBackend()
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        val call = d.toolCall
        assertNotNull(d)
        assertNotNull(call)
        assertEquals("lsp.symbols", call!!.toolName)
        assertTrue("template must extract the symbol name: ${call.argumentsJson}", call.argumentsJson.contains("Foo"))
        assertNull(d.reasonCode)

        val out = lspDispatcher(backend).dispatch(d, lspOn)
        val executed = out as? FastDispatchOutcome.Executed
        assertNotNull("FAST lsp.symbols must execute against the registered fake backend (got $out)", executed)
        assertEquals(1, backend.invocations.get())
        assertEquals(call.callId, executed!!.result.callId)
        assertFalse(executed.result.denied)
        assertTrue(executed.result.resultJson.contains("Foo"))
    }

    @Test fun decideAndDispatchEndToEndWithFakeExecutor() = runBlocking {
        val backend = CountingBackend()
        val r = lspDispatcher(backend).decideAndDispatch("查 Bar 符號", lspOn, sessionId = "s9", turnIndex = 2)
        assertEquals(RouteKind.FAST, r.decision.route)
        assertEquals("s9", r.decision.header.sessionId)
        assertEquals(2, r.decision.header.turnIndex)
        val executed = r.outcome as? FastDispatchOutcome.Executed
        assertNotNull("decideAndDispatch must execute FAST lsp.symbols (got ${r.outcome})", executed)
        assertEquals(1, backend.invocations.get())
        assertTrue(executed!!.result.resultJson.contains("Bar"))
    }

    @Test fun negatedIntentsNeverExecute() = runBlocking {
        val backend = CountingBackend()
        val phoneFake = CountingFake("phone.dial")
        val dispatcher = lspDispatcher(backend, mapOf("phone.dial" to phoneFake))
        for (text in listOf(
            "不要查 Foo 這個符號",
            "別查 Foo 這個符號",
            "别查 Foo 符號",
            "不用幫我查符號",
            "不要打電話給 0912345678",
            "別設鬧鐘，明天早上7點叫我",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            val out = dispatcher.dispatch(d, lspOn)
            val skipped = skippedOf(out, text)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, skipped.reasonCode)
        }
        assertEquals(0, backend.invocations.get())
        assertEquals("clarification path must not execute even with an executor registered", 0, phoneFake.invocations.get())
    }

    @Test fun incompleteArgsNeverExecute() = runBlocking {
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "幫我設個鬧鐘",
            "打電話給媽媽",
            "傳簡訊給媽媽說我晚點到",
            "查符號",
            "查一下符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun uncertainIntentNeverExecutes() = runBlocking {
        val backend = CountingBackend()
        val d = decide("今天心情不錯，隨便聊聊")
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.toolCall)
        assertNull(d.reasonCode)
        val skipped = skippedOf(lspDispatcher(backend).dispatch(d, lspOn), "uncertain intent")
        assertNull(skipped.reasonCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun unregisteredExecutorNeverExecutes() = runBlocking {
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        assertNotNull(d.toolCall)
        // No executor registered for the routed tool: fail-safe skip, zero effects.
        val out = FastDispatcher(emptyMap()).dispatch(d, lspOn)
        val skipped = skippedOf(out, "unregistered executor")
        assertEquals(DenyReason.USER_DISABLED, skipped.reasonCode)
        assertTrue(skipped.fallbackMessage!!.contains("lsp.symbols"))
    }

    @Test fun notReadyToolNeverExecutesEvenWithExecutor() = runBlocking {
        // `decide()` can never produce this (the wiring gate denies unready
        // tools), so the decision is synthetic on purpose: it proves the
        // dispatcher re-checks `executionReady` instead of trusting the call.
        val header = TranscriptHeader(
            sessionId = "s1",
            turnIndex = 1,
            route = RouteKind.FAST,
            toolName = "navigate",
            reasonCode = null,
            visibleTools = emptyList(),
        )
        val decision = RouteDecision(
            route = RouteKind.FAST,
            toolCall = ToolCall("navigate", "{\"destination\": \"台北車站\", \"mode\": \"driving\"}", "call-x"),
            reasonCode = null,
            fallbackMessage = null,
            header = header,
        )
        val fake = CountingFake("navigate")
        val out = FastDispatcher(mapOf("navigate" to fake)).dispatch(decision, lspOn)
        val skipped = skippedOf(out, "not-ready tool")
        assertEquals(DenyReason.USER_DISABLED, skipped.reasonCode)
        assertEquals(0, fake.invocations.get())
    }

    @Test fun unknownToolConceptsNeverRouteOrExecute() = runBlocking {
        // RULES gap is fail-safe: no template ⇒ DENIED no-match, and the
        // dispatcher has no ToolCall to act on even with an executor present.
        val fake = CountingFake("flight.book")
        val dispatcher = FastDispatcher(mapOf("flight.book" to fake))
        val d = decide("幫我訂一張下週去東京的機票")
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.toolCall)
        skippedOf(dispatcher.dispatch(d, lspOn), "untemplated tool")
        assertEquals(0, fake.invocations.get())
    }

    @Test fun newToolMustExpandVerbTableBeforeReady() {
        val ready = ToolRegistry.ALL.filter { it.executionReady }.map { it.name }
        assertEquals("B3 flips no executionReady: only lsp.symbols stays ready", listOf("lsp.symbols"), ready)
        val templates = FastRouter.templateToolNames()
        assertTrue("lsp.symbols must keep its RULES template: $templates", "lsp.symbols" in templates)
        for (name in ready) {
            assertTrue(
                "$name is executionReady but has no FastRouter template — " +
                    "expand RULES + NEGATION_HINTS BEFORE flipping ready (NEGATION gaps are not fail-safe)",
                name in templates,
            )
        }
    }

    @Test fun genericNegationClarifiesForEveryTemplatedTool() {
        val probes = listOf(
            "不要幫我截圖" to "screenshot.capture",
            "不要看未讀通知" to "notification.read",
            "不要設明天早上7點的鬧鐘" to "alarm.create",
            "不要打電話給 0912345678" to "phone.dial",
            "不要傳簡訊給 0912345678" to "sms.compose",
            "不要寫信給老闆" to "mail.compose",
            "不要加明天的行程" to "calendar.create",
            "不要播放周杰倫的歌" to "music.control",
            "不要把音量調大" to "volume.set",
            "不要導航到台北車站" to "navigate",
            "不要打開相機" to "open_app",
            "不要查 Foo 這個符號" to "lsp.symbols",
        )
        for ((text, tool) in probes) {
            val d = decide(text, lspOnCalendarGranted)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            assertEquals(text, tool, d.header.toolName)
        }
    }

    @Test fun bareBieVerbClarifiesForSymbolLookup() {
        for (text in listOf("別查 Foo 這個符號", "别查 Foo 符號")) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            assertEquals(text, "lsp.symbols", d.header.toolName)
        }
    }

    @Test fun switchFlipBetweenDecideAndDispatchDeniesFresh() = runBlocking {
        val backend = CountingBackend()
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        // Switch flips off between decide and dispatch: fresh projection re-check denies.
        val out = lspDispatcher(backend).dispatch(d, lspOn.copy(userSwitches = mapOf("lsp" to false)))
        val skipped = skippedOf(out, "switch flipped off")
        assertEquals(DenyReason.USER_DISABLED, skipped.reasonCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun privilegedReadyCopyStillRefusesWithoutConfirmation() = runBlocking {
        // Even a ready PRIVILEGED tool refuses: WRITE/PRIVILEGED execution
        // needs the confirmation pipeline (future wiring PR); the dispatcher
        // stays READ-only by construction.
        val readyCopy: (String) -> ToolDef? = { name ->
            val tool = ToolRegistry.find(name)
            if (name == "phone.dial") tool?.copy(executionReady = true) else tool
        }
        val header = TranscriptHeader(
            sessionId = "s1",
            turnIndex = 1,
            route = RouteKind.FAST,
            toolName = "phone.dial",
            reasonCode = null,
            visibleTools = emptyList(),
        )
        val decision = RouteDecision(
            route = RouteKind.FAST,
            toolCall = ToolCall("phone.dial", "{\"number\": \"0912345678\", \"raw\": \"x\"}", "call-p"),
            reasonCode = null,
            fallbackMessage = null,
            header = header,
        )
        val fake = CountingFake("phone.dial")
        val out = FastDispatcher(mapOf("phone.dial" to fake), lookup = readyCopy).dispatch(decision, lspOn)
        val skipped = skippedOf(out, "privileged tool")
        assertEquals(DenyReason.USER_DISABLED, skipped.reasonCode)
        assertEquals(0, fake.invocations.get())
    }

    @Test fun backendFailureSkipsWithoutLeaking() = runBlocking {
        val boom = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
                throw RuntimeException("boom-secret-marker")
            }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to LspSymbolsExecutor(boom))).dispatch(d, lspOn)
        val skipped = skippedOf(out, "backend failure")
        assertFalse(skipped.fallbackMessage!!.contains("boom-secret-marker"))
    }

    @Test fun lspExecutorDeniesBlankQueryWithZeroBackendEffects() = runBlocking {
        val backend = CountingBackend()
        val executor = LspSymbolsExecutor(backend)
        val denied = executor.execute(
            ToolCall("lsp.symbols", "{\"query\": \"   \"}", "c1"),
            lspOn,
        )
        assertTrue(denied.denied)
        assertEquals(0, backend.invocations.get())

        // The dispatcher maps a denied executor result to Skipped, never Executed.
        val header = TranscriptHeader(
            sessionId = "s1",
            turnIndex = 1,
            route = RouteKind.FAST,
            toolName = "lsp.symbols",
            reasonCode = null,
            visibleTools = emptyList(),
        )
        val decision = RouteDecision(
            route = RouteKind.FAST,
            toolCall = ToolCall("lsp.symbols", "{\"query\": \"\"}", "c2"),
            reasonCode = null,
            fallbackMessage = null,
            header = header,
        )
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to executor)).dispatch(decision, lspOn)
        skippedOf(out, "blank-query FAST")
        assertEquals(0, backend.invocations.get())
    }
}
