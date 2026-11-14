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

    @Test fun negatedSymbolLookupVariantsNeverExecute() = runBlocking {
        // 繁簡對稱 `不/別/沒`×動詞 否定符號查詢意圖：一律 DENIED +
        // NEED_CLARIFICATION、toolCall == null；dispatch 亦必須 Skipped、
        // 後端零呼叫（不得走 FAST 執行）。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        // Symbol-naming negated intents (the 10 symbol probes).
        val symbolTexts = listOf(
            "不查 Foo 這個符號",
            "別找 Foo 符號",
            "别找 Foo 符號",
            "別尋 Foo 符號",
            "別搜 Foo 符號",
            "别搜 Foo 符號",
            "別詢 Foo 符號",
            "别询 Foo 符號",
            "沒查 Foo 符號",
            "没查 Foo 符號",
        )
        // Non-symbol negated intents (alarm ×2, open-app, sms): none may
        // execute either. `不開應用程式` matches no template at all (no-match
        // DENIED, reasonCode null); the rest clarify via NEGATION.
        val otherTexts = listOf(
            "不設明天早上7點的鬧鐘",
            "没设明天早上7点的闹钟",
            "不開應用程式",
            "不发消息给 0912345678",
        )
        for (text in symbolTexts) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
        }
        for (text in otherTexts) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.toolCall)
        }
        for (text in symbolTexts + otherTexts) {
            val d = decide(text)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun symbolNameAndMetaQuestionExtraction() {
        // Latin hint word-anchored: `SymbolTable` / `getSymbol` survive intact.
        val t1 = decide("查 SymbolTable 符號")
        assertEquals(RouteKind.FAST, t1.route)
        assertTrue(t1.toolCall!!.argumentsJson.contains("\"query\": \"SymbolTable\""))
        assertFalse(t1.toolCall!!.argumentsJson.contains("\"query\": \"Table\""))

        val t2 = decide("查 getSymbol 符號")
        assertEquals(RouteKind.FAST, t2.route)
        assertTrue(t2.toolCall!!.argumentsJson.contains("\"query\": \"getSymbol\""))

        // 已記錄並接受的 CJK 過度剝離殘餘：「查理」剝成「理」（fail-safe 方向）。
        // Documented CJK over-strip residual: 「查理」 strips to 「理」.
        val t3 = decide("查理 符號")
        assertEquals(RouteKind.FAST, t3.route)
        assertTrue(t3.toolCall!!.argumentsJson.contains("\"query\": \"理\""))

        // Meta-question tokens strip to empty ⇒ clarification, never executes.
        val t4 = decide("符號是什麼？")
        assertEquals(RouteKind.DENIED, t4.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, t4.reasonCode)
        assertNull(t4.toolCall)

        // Punctuation-only remainder ⇒ clarification (meaningful-remainder gate).
        val t5 = decide("查 - 符號")
        assertEquals(RouteKind.DENIED, t5.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, t5.reasonCode)

        // Simplified hint routes too.
        val t6 = decide("查 Foo 符号")
        assertEquals(RouteKind.FAST, t6.route)
        assertTrue(t6.toolCall!!.argumentsJson.contains("Foo"))

        // `symbolize` must NOT route (word-anchored latin hint).
        val t7 = decide("symbolize")
        assertEquals(RouteKind.DENIED, t7.route)
        assertNull(t7.toolCall)
    }

    @Test fun negatedSymbolVerbsNeverExecuteForDispatch() = runBlocking {
        // 代表性否定符號意圖（含 別問/别问）走 decide → dispatch，並註冊 executor：
        // 全部 Skipped、後端零呼叫。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "別問 Foo 符號",
            "别问 Foo 符號",
            "不查 Foo 這個符號",
            "別搜 Foo 符號",
            "沒查 Foo 符號",
            "没查 Foo 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
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
        // Behavioral NEGATION-closure probe. NEGATION_HINTS is private on the
        // router, so the table cannot be asserted member-wise — instead pin
        // the closure through its observable effect: every negated intent in
        // the local probe list (the same 14 texts as
        // negatedSymbolLookupVariantsNeverExecute) must deny the ready tool,
        // never route FAST. This makes the oracle check NEGATION behavior,
        // not merely RULES membership.
        val negatedProbes = listOf(
            // 10 symbol-naming negated probes
            "不查 Foo 這個符號",
            "別找 Foo 符號",
            "别找 Foo 符號",
            "別尋 Foo 符號",
            "別搜 Foo 符號",
            "别搜 Foo 符號",
            "別詢 Foo 符號",
            "别询 Foo 符號",
            "沒查 Foo 符號",
            "没查 Foo 符號",
            // 4 other negated probes (alarm ×2, open-app, sms)
            "不設明天早上7點的鬧鐘",
            "没设明天早上7点的闹钟",
            "不開應用程式",
            "不发消息给 0912345678",
        )
        for (text in negatedProbes) {
            val d = decide(text, lspOnCalendarGranted)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.toolCall)
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
        // Second pass (decision → dispatch): register a backend-independent
        // fake executor for every templated tool and dispatch the same
        // clarification decisions — none may invoke its fake.
        runBlocking {
            val fakes = FastRouter.templateToolNames().associateWith { CountingFake(it) }
            val dispatcher = FastDispatcher(fakes)
            for ((text, _) in probes) {
                val d = decide(text, lspOnCalendarGranted)
                dispatcher.dispatch(d, lspOnCalendarGranted)
            }
            for ((tool, fake) in fakes) {
                assertEquals(
                    "$tool clarification decision must never invoke its executor",
                    0,
                    fake.invocations.get(),
                )
            }
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

    @Test fun maxIntentInputIsDeniedBeforeRegex() {
        val gateMessage = "無法理解這個請求，請換個方式描述"
        val longText = "查 " + "a".repeat(5000) + " 符號"
        assertTrue("probe must exceed the gate: ${longText.length}", longText.length > 4096)
        val d = decide(longText)
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.toolCall)
        assertEquals(gateMessage, d.fallbackMessage)
        assertFalse("raw long input must never echo into the fallback", d.fallbackMessage!!.contains("a".repeat(100)))

        // Boundary: exactly 4096 chars must NOT be denied via the length gate
        // ("查 " 2 + "a"×4091 + " 符號" 3 = 4096) — and the complete-args symbol
        // intent still routes FAST (only the gate message is barred).
        val atLimit = "查 " + "a".repeat(4091) + " 符號"
        assertEquals(4096, atLimit.length)
        val d2 = decide(atLimit)
        assertFalse("4096-char intent must not return the length-gate message", d2.fallbackMessage == gateMessage)
        assertEquals(RouteKind.FAST, d2.route)
    }

    @Test fun separatorSplitNegationNeverExecutes() = runBlocking {
        // Negation hints split by whitespace / punctuation / Cf format chars
        // (zero-width space, variation selectors) must still clarify, never
        // execute — isNegated() re-checks on separator/Cf-normalized text.
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "別 查 Foo 符號",
            "別　查 Foo 符號",
            "別，查 Foo 符號",
            "不\u200B查 Foo 符號",
            "別\uFE0E查 Foo 符號",
            "不 要 查 Foo 符號",
            "別 找 Foo 符號",
            // Whitelist-normalizer coverage: separators of every shape must
            // rejoin into a contiguous hint and deny (never execute).
            "別\u30FB查 Foo 符號",   // U+30FB KATAKANA MIDDLE DOT
            "別\u0301查 Foo 符號",   // U+0301 combining acute accent (Mn)
            "別\u0903查 Foo 符號",   // U+0903 Devanagari sign visarga (Mc)
            "別\u20E3查 Foo 符號",   // U+20E3 combining enclosing keycap (Me)
            "別😀查 Foo 符號",        // U+1F600 emoji (So)
            "別\u0085查 Foo 符號",   // U+0085 NEL (Cc)
            "別\u007F查 Foo 符號",   // U+007F DEL (Cc)
            "別-查 Foo 符號",         // U+002D hyphen-minus (Pd)
            "別\u301C查 Foo 符號",   // U+301C wave dash (Pd)
            "別\uFE54查 Foo 符號",   // U+FE54 small semicolon (Po)
            "別_查 Foo 符號",         // U+005F low line (Pc)
            "別\u30FC查 Foo 符號",   // U+30FC prolonged sound mark (Lm)
            "別\u3164查 Foo 符號",   // U+3164 hangul filler (Lo; NFKC→U+1160)
            "not symbols",
            "NOT SYMBOLS",
            "no symbols",
            "ｎｅｖｅｒ symbols",     // fullwidth English (NFKC→never), ASCII 符號 hint 可命中模板
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun visibleSplittersStillDeny() = runBlocking {
        // ASCII-alphanumeric / symbol in-run splitters (visible chars, not
        // separators): NFKC + the removed pass strip them, rejoining the
        // negation hint — DENIED + NEED_CLARIFICATION, never FAST.
        // （`不一查` 類以可見 CJK 字母/數字夾在否定詞 run 中的情形為 NOT closed
        // 殘餘，刻意不在此釘住——見 FastRouter KDoc。）
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "不2查 Foo 符號",
            "別①查 Foo 符號",   // U+2460 circled digit one (NFKC→1)
            "不™查 Foo 符號",   // U+2122 trade mark sign (NFKC→TM)
            "別½查 Foo 符號",   // U+00BD vulgar fraction one half (NFKC→1⁄2)
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun mixedScriptNegationNeverExecutes() = runBlocking {
        // 繁簡交叉混排否定詞（traditional prefix × simplified verb and vice
        // versa）：一律 DENIED + NEED_CLARIFICATION + dispatch skipped + backend 0。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "別寻 Foo 符號",
            "別询 Foo 符號",
            "別问 Foo 符號",
            "別设 Foo 符號",
            "別调 Foo 符號",
            "别尋 Foo 符號",
            "没導 Foo 符號",
            "沒发 Foo 符號",
            "别寫 Foo 符號",
            "沒写 Foo 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun fullwidthEnglishNegationNeverExecutes() = runBlocking {
        // 全形英文否定（NFKC 後折為 ASCII never）：符號 hint 不命中全形文本，
        // 路由直接 no-match DENIED——絕不執行。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        val d = decide("ｎｅｖｅｒ ｆｉｎｄ ｓｙｍｂｏｌｓ")
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.toolCall)
        skippedOf(dispatcher.dispatch(d, lspOn), "fullwidth English negation")
        assertEquals(0, backend.invocations.get())
    }

    @Test fun acceptedResidualsArePinned() {
        // Pin accepted residuals so future changes notice drift.
        // `符號是Foo嗎` → FAST with query=Foo（過度剝離殘餘，已記錄、安全方向）。
        val residual = decide("符號是Foo嗎")
        assertEquals(RouteKind.FAST, residual.route)
        assertTrue(
            "query must survive as Foo: ${residual.toolCall!!.argumentsJson}",
            residual.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""),
        )
        // `意思是符號` → DENIED + NEED_CLARIFICATION（剝離後無有意義的殘餘）。
        val meta = decide("意思是符號")
        assertEquals(RouteKind.DENIED, meta.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, meta.reasonCode)
        assertNull(meta.toolCall)
        // `查 LSPClient 符號` → FAST with query=LSPClient（\blsp\b 有錨，LSPClient 完整保留）。
        val anchored = decide("查 LSPClient 符號")
        assertEquals(RouteKind.FAST, anchored.route)
        assertTrue(
            "LSPClient must survive the \\blsp\\b strip: ${anchored.toolCall!!.argumentsJson}",
            anchored.toolCall!!.argumentsJson.contains("\"query\": \"LSPClient\""),
        )
    }

    @Test fun englishNegationNeverExecutes() = runBlocking {
        // English negation verbs (best-effort): each of these either routes
        // via the symbol template into clarification, or misses the template
        // into the ordinary no-match denial — both are safe DENIED outcomes.
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "never find symbol Foo",
            "refuses symbols",
            "avoid symbols",
            "skip symbols",
            "without symbols",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.toolCall)
        }
        // Sanity: the case reaching the symbol template clarifies and the
        // dispatcher skips — the backend is never invoked.
        val d = decide("never find symbol Foo")
        val skipped = skippedOf(dispatcher.dispatch(d, lspOn), "never find symbol Foo")
        assertEquals(DenyReason.NEED_CLARIFICATION, skipped.reasonCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun metaQuestionVariantsAreDenied() = runBlocking {
        // Meta-question tokens (now including bare 是/的/要/用) strip to an
        // empty or meaningful-less remainder ⇒ clarification, never executes.
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "符號的意思",
            "什麼是符號",
            "為什麼要用符號",
            "查 是 符號",
            "查 要 用 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
        }
        // Dispatch sanity for at least one case: clarification never reaches the backend.
        skippedOf(dispatcher.dispatch(decide("符號的意思"), lspOn), "符號的意思")
        assertEquals(0, backend.invocations.get())
    }

    @Test fun specialFindIsOverClarified() {
        // Accepted over-clarify residual: 「特別找」含「別找」⇒ NEED_CLARIFICATION,
        // never FAST (message may be anything — the contract is DENIED + clarify).
        val d = decide("特別找 Foo 符號")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, d.reasonCode)
        assertNull(d.toolCall)
    }

    @Test fun escLoneSurrogateIsEscaped() {
        // Paired surrogate (𠮷 = U+20BB7, Lo) passes through esc unchanged — the
        // pair is NOT split. Note: the meaningful-remainder predicate iterates
        // UTF-16 Chars, so an astral letter alone cannot anchor a query — the
        // remainder keeps BMP letters alongside the pair. (U+1D11E 𝄞 is
        // category So, not a letter, so it cannot anchor one either.)
        val paired = decide("查 Foo 𠮷 符號")
        assertEquals(RouteKind.FAST, paired.route)
        assertTrue("paired surrogate must pass through esc unchanged", paired.toolCall!!.argumentsJson.contains("𠮷"))

        // Lone HIGH surrogate inside a letter-bearing remainder: esc must emit
        // the 4-lowercase-hex \uXXXX escape (String.format "%04x" convention),
        // never the raw lone unit.
        val lone = decide("查 Foo\uD800Bar 符號")
        assertEquals(RouteKind.FAST, lone.route)
        val json = lone.toolCall!!.argumentsJson
        assertTrue("lone surrogate must appear as a \\uXXXX escape: $json", json.contains("\\ud800"))
        assertFalse("raw lone surrogate must never appear in the JSON", json.contains('\uD800'))

        // Lone LOW surrogate: same rule — escaped as \uDC00, never raw.
        val loneLow = decide("查 Foo\uDC00Bar 符號")
        assertEquals(RouteKind.FAST, loneLow.route)
        val jsonLow = loneLow.toolCall!!.argumentsJson
        assertTrue("lone low surrogate must appear as a \\uXXXX escape: $jsonLow", jsonLow.contains("\\udc00"))
        assertFalse("raw lone low surrogate must never appear in the JSON", jsonLow.contains('\uDC00'))

        // A well-formed pair at the very END of the string is NOT split: esc
        // passes both units through unchanged.
        val endPair = decide("查 Foo 符號 😀")
        assertEquals(RouteKind.FAST, endPair.route)
        val jsonEnd = endPair.toolCall!!.argumentsJson
        assertTrue("paired surrogate at end must pass through esc unchanged: $jsonEnd", jsonEnd.contains("😀"))
        assertFalse("pair at end must not be escaped: $jsonEnd", jsonEnd.contains("\\ud83d"))
    }

    @Test fun mysymbolsDoesNotRoute() {
        // Leading word boundary on the latin symbol hint: `mysymbols` /
        // `foobarsymbols` must not route (no-match DENIED).
        val d1 = decide("mysymbols")
        assertEquals(RouteKind.DENIED, d1.route)
        assertNull(d1.toolCall)
        val d2 = decide("foobarsymbols")
        assertEquals(RouteKind.DENIED, d2.route)
        assertNull(d2.toolCall)
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
