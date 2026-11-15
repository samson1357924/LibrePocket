package dev.librepocket.router

import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolDef
import dev.librepocket.tool.ToolRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    // listener:notification 授權讓 notification.read 投影 NATIVE（無授權時 DEGRADED，
    // Phase 2 起非 NATIVE 投影在 projection gate 即 DENIED + NO_PRIVILEGE，
    // 否定句探針測不到 NEGATION closure）；calendar probe 用 READ_CALENDAR。
    // 明確聲明：listener:notification 授權存在的唯一目的，是讓
    // genericNegationClarifiesForEveryTemplatedTool 的 notification.read 探針在
    // NATIVE-only router gate 下維持在 clarification 路徑（其餘探針原本即為
    // NATIVE；calendar.create 探針由 READ_CALENDAR 負責）。
    private val lspOnCalendarGranted = lspOn.copy(
        grantedPermissions = setOf("android.permission.READ_CALENDAR", "listener:notification"),
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

    @Test fun modifierAndDigitSplittersStillDeny() = runBlocking {
        // Lm modifier letters（ˇ U+02C7／ˉ U+02C9／ـ U+0640／々 U+3005）與非 ASCII
        // digit（¹ U+00B9 No；U+09E8 Bengali digit two Nd——稽核所指碼位；U+0662
        // Arabic-Indic digit two Nd——稽核逐字字元）in-run 拆開否定詞：removed pass
        // 剝離後接合回連續 hint ⇒ DENIED + NEED_CLARIFICATION + backend 零呼叫。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "別ˇ查 Foo 符號",   // U+02C7 CARON (Lm)
            "別ˉ查 Foo 符號",   // U+02C9 MODIFIER LETTER MACRON (Lm)
            "別ـ查 Foo 符號",   // U+0640 ARABIC TATWEEL (Lm)
            "不ˇ查 Foo 符號",   // U+02C7 CARON (Lm)，不+動詞
            "別々查 Foo 符號",  // U+3005 KATAKANA ITERATION MARK (Lm)
            "別¹查 Foo 符號",   // U+00B9 SUPERSCRIPT ONE (No)
            "不\u09E8查 Foo 符號",  // U+09E8 BENGALI DIGIT TWO (Nd)——稽核所指碼位
            "不٢查 Foo 符號",  // U+0662 ARABIC-INDIC DIGIT TWO (Nd)——稽核逐字字元
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

    // ---- Phase 2 (#19 B3)：FastDispatch 硬化驗證 ----

    @Test fun degradedProjectionDeniedBeforeToolsRun() = runBlocking {
        // notification.read：開關開著、無 listener grant → DEGRADED + NO_PRIVILEGE。
        val ctx = ProjectionContext(userSwitches = mapOf("notification" to true))
        val d = decide("未讀通知有哪些", ctx)
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NO_PRIVILEGE, d.reasonCode)

        // dispatch 端：ready-copy 也必須在 gate 3（NATIVE-only）fail-closed。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let { if (name == "notification.read") it.copy(executionReady = true) else it }
        }
        val fake = CountingFake("notification.read")
        val synthetic = fastDecision("notification.read", "{\"limit\": 10}", "call-deg")
        val out = FastDispatcher(mapOf("notification.read" to fake), lookup = readyCopy).dispatch(synthetic, ctx)
        val skipped = skippedOf(out, "degraded projection")
        assertEquals(DenyReason.NO_PRIVILEGE, skipped.reasonCode)
        assertEquals(0, fake.invocations.get())
    }

    // 替代測試（誠實標註）：registry 無 PLAY-only 工具（僅 FOSS/GITHUB-only 或預設三味皆有），
    // 原規劃的 PLAY-only ready-copy 案例不成立、依指示略過；改以 gui.automate
    // （FOSS/GITHUB-only 慢速通道描述符）在 PLAY flavor 上驗證 FLAVOR_BLOCKED 同一 gate。
    @Test fun flavorBlockedProjectionDenied() = runBlocking {
        val ctx = ProjectionContext(flavor = dev.librepocket.tool.Flavor.PLAY)
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let { if (name == ToolRegistry.SLOW_TOOL_NAME) it.copy(executionReady = true) else it }
        }
        val fake = CountingFake(ToolRegistry.SLOW_TOOL_NAME)
        val synthetic = fastDecision(ToolRegistry.SLOW_TOOL_NAME, "{\"goal\": \"x\"}", "call-flavor")
        val out = FastDispatcher(mapOf(ToolRegistry.SLOW_TOOL_NAME to fake), lookup = readyCopy).dispatch(synthetic, ctx)
        val skipped = skippedOf(out, "flavor blocked projection")
        assertEquals(DenyReason.FLAVOR_BLOCKED, skipped.reasonCode)
        assertEquals(0, fake.invocations.get())
    }

    @Test fun writeReadyCopyRefusesExecute() = runBlocking {
        // gate 5（READ-only）：WRITE ready-copy 也絕不執行。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let { if (name == "alarm.create") it.copy(executionReady = true) else it }
        }
        val fake = CountingFake("alarm.create")
        val synthetic = fastDecision("alarm.create", "{\"hour\": 7, \"minute\": 30}", "call-write")
        val out = FastDispatcher(mapOf("alarm.create" to fake), lookup = readyCopy).dispatch(synthetic, lspOn)
        val skipped = skippedOf(out, "write tool")
        assertEquals(DenyReason.USER_DISABLED, skipped.reasonCode)
        assertEquals(0, fake.invocations.get())
    }

    @Test fun executorToolNameMismatchNeverExecutes() = runBlocking {
        // 執行器自述身分（"phone.dial"）與註冊的 key（"lsp.symbols"）不符：
        // 先於任何執行 fail-closed，fake 零呼叫。
        val fake = CountingFake("phone.dial")
        val dispatcher = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to fake))
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        val out = dispatcher.dispatch(d, lspOn)
        val skipped = skippedOf(out, "executor identity mismatch")
        assertEquals("EXECUTOR_IDENTITY_MISMATCH", skipped.denyCode)
        assertEquals(0, fake.invocations.get())
    }

    @Test fun resultToolNameMismatchFailsClosed() = runBlocking {
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        val call = d.toolCall!!
        // result.toolName 與 call 不符 → 零傳播。
        val wrongTool = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult =
                FastExecResult("other.tool", call.callId, "{}")
        }
        val out1 = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to wrongTool)).dispatch(d, lspOn)
        val skipped1 = skippedOf(out1, "result toolName mismatch")
        assertEquals("RESULT_IDENTITY_MISMATCH", skipped1.denyCode)
        // result.callId 與 call 不符（偽造）→ 零傳播。
        val wrongCallId = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult =
                FastExecResult(LspTools.SYMBOLS_NAME, "forged", "{}")
        }
        val out2 = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to wrongCallId)).dispatch(d, lspOn)
        val skipped2 = skippedOf(out2, "result callId mismatch")
        assertEquals("RESULT_IDENTITY_MISMATCH", skipped2.denyCode)
    }

    @Test fun lookupThrowingFailsClosed() = runBlocking {
        // lookup 自身拋出（非取消）→ typed skip，永不拋、永不回音例外訊息。
        val dispatcher = FastDispatcher(lookup = { throw IllegalStateException("lookup-boom") })
        val d = decide("幫我查 Foo 這個符號")
        val out = dispatcher.dispatch(d, lspOn)
        val skipped = skippedOf(out, "lookup throwing")
        assertEquals("LOOKUP_ERROR", skipped.denyCode)
        assertFalse(skipped.fallbackMessage!!.contains("lookup-boom"))
    }

    @Test fun executorDeniedMapsTypedOutcome() = runBlocking {
        val d = decide("幫我查 Foo 這個符號")
        // SCHEMA_INVALID → NEED_CLARIFICATION；resultJson 永不放行。
        val schemaDenied = executorReturning(
            FastExecResult(LspTools.SYMBOLS_NAME, d.toolCall!!.callId, """{"secret":"marker-should-not-ride"}""", denied = true, denyCode = "SCHEMA_INVALID"),
        )
        val out1 = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to schemaDenied)).dispatch(d, lspOn)
        val skipped1 = skippedOf(out1, "schema denied")
        assertEquals(DenyReason.NEED_CLARIFICATION, skipped1.reasonCode)
        assertEquals("SCHEMA_INVALID", skipped1.denyCode)
        assertFalse(skipped1.fallbackMessage!!.contains("marker-should-not-ride"))
        // UNAVAILABLE（及其他任何 denyCode）→ USER_DISABLED；resultJson 永不放行。
        val unavailable = executorReturning(
            FastExecResult(LspTools.SYMBOLS_NAME, d.toolCall!!.callId, """{"secret":"marker-should-not-ride"}""", denied = true, denyCode = "UNAVAILABLE"),
        )
        val out2 = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to unavailable)).dispatch(d, lspOn)
        val skipped2 = skippedOf(out2, "unavailable denied")
        assertEquals(DenyReason.USER_DISABLED, skipped2.reasonCode)
        assertEquals("UNAVAILABLE", skipped2.denyCode)
        assertFalse(skipped2.fallbackMessage!!.contains("marker-should-not-ride"))
    }

    @Test fun timeoutFailsClosed() = runBlocking {
        // per-call budget：ready-copy 把 timeoutMs 壓到 50ms；fake executor 跑 10s
        // → 型別化 TIMEOUT skip（非取消外拋）。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let {
                if (name == LspTools.SYMBOLS_NAME) it.copy(annotations = it.annotations.copy(timeoutMs = 50)) else it
            }
        }
        val entered = AtomicInteger(0)
        val slow = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
                entered.incrementAndGet()
                delay(10_000)
                return FastExecResult(toolName, call.callId, """{"ok":true}""")
            }
        }
        val d = decide("幫我查 Foo 這個符號")
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to slow), lookup = readyCopy).dispatch(d, lspOn)
        val skipped = skippedOf(out, "timeout")
        assertEquals("TIMEOUT", skipped.denyCode)
        assertNull(skipped.reasonCode)
        assertEquals("executor entered once but must be timed out", 1, entered.get())
    }

    @Test fun timeoutZeroBudgetIsFailClosed() = runBlocking {
        // 非正 budget（0）直接 fail-closed：typed TIMEOUT skip，零 backend 呼叫、
        // executor 零呼叫（即使同名 fake 註冊於 dispatcher 下）。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let {
                if (name == LspTools.SYMBOLS_NAME) it.copy(annotations = it.annotations.copy(timeoutMs = 0)) else it
            }
        }
        val backend = CountingBackend()
        val fake = CountingFake(LspTools.SYMBOLS_NAME)
        // 同名 fake 覆蓋 LspSymbolsExecutor：零 budget 必須在觸碰任何 executor/backend 前 fail-closed。
        val dispatcher = FastDispatcher(
            mapOf(LspTools.SYMBOLS_NAME to LspSymbolsExecutor(backend)) + mapOf(LspTools.SYMBOLS_NAME to fake),
            lookup = readyCopy,
        )
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        val out = dispatcher.dispatch(d, lspOn)
        val skipped = skippedOf(out, "zero timeout budget")
        assertEquals("TIMEOUT", skipped.denyCode)
        assertEquals("fake executor must never be entered", 0, fake.invocations.get())
        assertEquals("backend must never be invoked", 0, backend.invocations.get())
    }

    @Test fun externalCancelRacingTimeoutWins() = runBlocking {
        // 外部取消與 withTimeout 競爭：呼叫端先取消 → CancellationException 外拋
        // （不得映射為 TIMEOUT Skipped，亦不得是 TimeoutCancellationException——
        // 父 scope 無 deadline，withTimeout 不會自行到期）。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let {
                if (name == LspTools.SYMBOLS_NAME) it.copy(annotations = it.annotations.copy(timeoutMs = 10_000)) else it
            }
        }
        val slow = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
                delay(10_000)
                return FastExecResult(toolName, call.callId, """{"ok":true}""")
            }
        }
        val d = decide("幫我查 Foo 這個符號")
        val dispatcher = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to slow), lookup = readyCopy)
        val deferred = async { dispatcher.dispatch(d, lspOn) }
        delay(50)
        deferred.cancel()
        val thrown = try {
            withTimeout(5_000) { deferred.await() }
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("external cancel must propagate as CancellationException, not a skipped outcome", thrown)
        assertTrue(
            "must be a plain external cancellation, not a withTimeout expiry: $thrown",
            thrown !is TimeoutCancellationException,
        )
    }

    @Test fun executorSelfThrownTimeoutMapsToTimeoutSkip() = runBlocking {
        // misbehaving executor 自拋 TimeoutCancellationException：刻意映射為
        // TIMEOUT typed skip（fail-closed、typed），不外拋。
        // （public API 不允許直接 new：此處以 withTimeout 到期自拋真實
        // TimeoutCancellationException，與 slice 測試同一手法。）
        val selfThrown = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
                withTimeout(1) { delay(60_000) }
                return FastExecResult(toolName, call.callId, """{"ok":true}""")
            }
        }
        val d = decide("幫我查 Foo 這個符號")
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to selfThrown)).dispatch(d, lspOn)
        val skipped = skippedOf(out, "self-thrown timeout")
        assertEquals("TIMEOUT", skipped.denyCode)
    }

    @Test fun executorDeniedWeirdDenyCodeIsMappedToExecError() = runBlocking {
        val d = decide("幫我查 Foo 這個符號")
        val call = d.toolCall!!
        // 不符 allowlist（^[A-Z0-9_]{1,64}$）的 denyCode → EXEC_ERROR。
        val weird = executorReturning(
            FastExecResult(LspTools.SYMBOLS_NAME, call.callId, """{"denied":true}""", denied = true, denyCode = "weird code\n!!"),
        )
        val out1 = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to weird)).dispatch(d, lspOn)
        val skipped1 = skippedOf(out1, "weird denyCode")
        assertEquals("EXEC_ERROR", skipped1.denyCode)
        assertEquals(DenyReason.USER_DISABLED, skipped1.reasonCode)
        // 合法 allowlist 碼（SCHEMA_INVALID）仍原樣放行 → NEED_CLARIFICATION。
        val schema = executorReturning(
            FastExecResult(LspTools.SYMBOLS_NAME, call.callId, """{"denied":true}""", denied = true, denyCode = "SCHEMA_INVALID"),
        )
        val out2 = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to schema)).dispatch(d, lspOn)
        val skipped2 = skippedOf(out2, "schema denyCode passthrough")
        assertEquals("SCHEMA_INVALID", skipped2.denyCode)
        assertEquals(DenyReason.NEED_CLARIFICATION, skipped2.reasonCode)
    }

    @Test fun executedOversizeResultIsRejected() = runBlocking {
        // misbehaving executor 回傳超預算 resultJson（21_000 > MAX_RESULT_CHARS=20_000）：
        // 縱深防禦 → Skipped EXEC_ERROR；Executed 永不放行。
        val d = decide("幫我查 Foo 這個符號")
        val call = d.toolCall!!
        val oversized = executorReturning(
            FastExecResult(LspTools.SYMBOLS_NAME, call.callId, "x".repeat(21_000)),
        )
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to oversized)).dispatch(d, lspOn)
        val skipped = skippedOf(out, "oversized result")
        assertEquals("EXEC_ERROR", skipped.denyCode)
    }

    @Test fun schemaInvalidReceiptCallIdIsBoundedWithHash() = runBlocking {
        // 超長 callId（>500 chars）→ 有界 deny receipt：callId 長度 ≤500、結尾為
        // `#` + 32 位小寫 hex（full redacted string 的 SHA-256-128）。
        val backend = CountingBackend()
        val executor = LspSymbolsExecutor(backend)
        val longId = "c1-" + "a".repeat(2000)
        val r = executor.execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query": "   "}""", longId), lspOn)
        assertTrue(r.denied)
        assertEquals("SCHEMA_INVALID", r.denyCode)
        assertEquals(0, backend.invocations.get())
        val receipt = Json.parseToJsonElement(r.resultJson).jsonObject["callId"]!!.jsonPrimitive.content
        assertTrue("receipt callId must be ≤500 chars: ${receipt.length}", receipt.length <= 500)
        assertTrue(
            "receipt callId must end with # + 32 lowercase hex: $receipt",
            Regex("#[0-9a-f]{32}$").containsMatchIn(receipt),
        )
    }

    @Test fun cancellationPropagates() {
        runBlocking {
        val d = decide("幫我查 Foo 這個符號")
        // (a) pre-cancelled coroutine：外拋 CancellationException，executor 零呼叫。
        val fakeA = CountingFake(LspTools.SYMBOLS_NAME)
        val dispatcherA = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to fakeA))
        val cancelledJob = Job().apply { cancel() }
        val deferred = async(cancelledJob) { dispatcherA.dispatch(d, lspOn) }
        assertThrows(CancellationException::class.java) { runBlocking { deferred.await() } }
        assertEquals(0, fakeA.invocations.get())

        // (b) executor 拋 CancellationException → 外拋（不得映射為 Skipped）。
        val cancelB = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult =
                throw CancellationException("executor-cancel-marker")
        }
        val dispatcherB = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to cancelB))
        assertThrows(CancellationException::class.java) { runBlocking { dispatcherB.dispatch(d, lspOn) } }
        }
    }

    @Test fun schemaInvalidMatrixDeniesWithZeroBackend() = runBlocking {
        val backend = CountingBackend()
        val executor = LspSymbolsExecutor(backend)
        val payloads = listOf(
            """{"query":123}""",
            """{"query":["Foo"]}""",
            """{"query":{"n":"x"}}""",
            """{"query":""}""",
            """{"query":"   "}""",
            """{"query":"Foo","limit":"5"}""",
            """{"query":"Foo","limit":3.9}""",
            """not-json""",
            """[1,2,3]""",
            """{"query":"Foo","pathPrefix":123}""",
        )
        for (payload in payloads) {
            val r = executor.execute(ToolCall(LspTools.SYMBOLS_NAME, payload, "c-schema"), lspOn)
            assertTrue("payload must be denied: $payload", r.denied)
            assertEquals("SCHEMA_INVALID", r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun pathPrefixTraversalMatrix() = runBlocking {
        val backend = CountingBackend()
        val executor = LspSymbolsExecutor(backend)
        val payloads = listOf(
            """{"query":"Foo","pathPrefix":"../x"}""",
            """{"query":"Foo","pathPrefix":"/etc"}""",
            """{"query":"Foo","pathPrefix":"file:/etc"}""",
            """{"query":"Foo","pathPrefix":"%2e%2e"}""",
            """{"query":"Foo","pathPrefix":"C:\\Users"}""",
            // U+0007（JSON escape 形式）
            "{\"query\":\"Foo\",\"pathPrefix\":\"a\\u0007b\"}",
            // FORMAT 碼點 U+200B（raw）
            "{\"query\":\"Foo\",\"pathPrefix\":\"a\u200Bb\"}",
            // NFKC 不穩定："／"（U+FF0F FULLWIDTH SOLIDUS）
            "{\"query\":\"Foo\",\"pathPrefix\":\"\uFF0Fetc\"}",
        )
        for (payload in payloads) {
            val r = executor.execute(ToolCall(LspTools.SYMBOLS_NAME, payload, "c-path"), lspOn)
            assertTrue("payload must be denied: $payload", r.denied)
            assertEquals("SCHEMA_INVALID", r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun grossOversizeArgsDenied() = runBlocking {
        val backend = CountingBackend()
        val executor = LspSymbolsExecutor(backend)
        // query 5000 字元（> MAX_INPUT_CHARS）
        val bigQuery = "{\"query\":\"" + "a".repeat(5000) + "\"}"
        // argumentsJson 20000 字元（> MAX_GROSS_ARGS_CHARS=16384；塞進未知 pad 欄位，
        // 逐項 query/limit/pathPrefix 檢查皆會通過，僅 gross gate 擋下）
        val padded = "{\"query\":\"ok\",\"pad\":\"" + "a".repeat(20_000) + "\"}"
        // 深巢狀：40 層 array 包一個 object（> MAX_NESTING_DEPTH=32）
        val deep = "[".repeat(40) + "{\"query\":\"Foo\"}" + "]".repeat(40)
        for (payload in listOf(bigQuery, padded, deep)) {
            val r = executor.execute(ToolCall(LspTools.SYMBOLS_NAME, payload, "c-gross"), lspOn)
            assertTrue("payload length=${payload.length} must be denied", r.denied)
            assertEquals("SCHEMA_INVALID", r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun nulQueryDenied() = runBlocking {
        val backend = CountingBackend()
        val executor = LspSymbolsExecutor(backend)
        val r = executor.execute(
            ToolCall(LspTools.SYMBOLS_NAME, "{\"query\":\"Foo\\u0000Bar\"}", "c-nul"),
            lspOn,
        )
        assertTrue(r.denied)
        assertEquals("SCHEMA_INVALID", r.denyCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun backendBloatedOutputIsCapped() = runBlocking {
        // 後端超給 300 symbols（超過 MAX_LIMIT=200）：輸出必須 ≤ 20000 字元、
        // 含 returnedCount/omittedCount、truncated=true。
        val bloat = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                (1..300).map { i ->
                    LspTools.Symbol(
                        "name-" + "x".repeat(100) + i,
                        "function",
                        "app/src/" + "y".repeat(60) + "/F" + i + ".kt",
                        i,
                    )
                }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = LspSymbolsExecutor(bloat)
            .execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query":"Foo"}""", "c-bloat"), lspOn)
        assertFalse(r.denied)
        assertTrue("resultJson must fit budget: ${r.resultJson.length}", r.resultJson.length <= 20_000)
        assertTrue(r.resultJson.contains("\"returnedCount\":"))
        assertTrue(r.resultJson.contains("\"omittedCount\":"))
        assertTrue(r.resultJson.contains("\"truncated\":true"))

        // 後端回傳含 email 的 symbol：redaction 後不得出現明文。
        val emailBackend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol("alice@example.com", "function", "app/Example.kt", 1))
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r2 = LspSymbolsExecutor(emailBackend)
            .execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query":"Foo"}""", "c-email"), lspOn)
        assertFalse(r2.denied)
        assertFalse(r2.resultJson.contains("alice@example.com"))
        assertTrue(r2.resultJson.contains("REDACTED"))
    }

    @Test fun executorsDefensiveCopyPinned() = runBlocking {
        // construction-time snapshot：建構後呼叫端移除 executor，dispatcher 仍看得到。
        val fake = CountingFake(LspTools.SYMBOLS_NAME)
        val mutable: MutableMap<String, FastToolExecutor> =
            mutableMapOf(LspTools.SYMBOLS_NAME to fake)
        val dispatcher = FastDispatcher(mutable)
        mutable.remove(LspTools.SYMBOLS_NAME)
        val d = decide("幫我查 Foo 這個符號")
        val out = dispatcher.dispatch(d, lspOn)
        val executed = out as? FastDispatchOutcome.Executed
        assertNotNull("construction-time snapshot must survive caller mutation (got $out)", executed)
        assertEquals(1, fake.invocations.get())
    }

    @Test fun gate2UnknownToolFixedMessage() = runBlocking {
        // 未知工具：UNKNOWN_TOOL；fallbackMessage 為固定話術，不回音工具名。
        val synthetic = fastDecision("flight.book", "{}", "call-unk")
        val out = FastDispatcher(lookup = { null }).dispatch(synthetic, lspOn)
        val skipped = skippedOf(out, "unknown tool")
        assertEquals("UNKNOWN_TOOL", skipped.denyCode)
        assertNull(skipped.reasonCode)
        assertNotNull(skipped.fallbackMessage)
        assertFalse(skipped.fallbackMessage!!.contains("flight.book"))
    }

    // ---- Phase 3 (#19 B3)：fail-closed / 正向路由追加測試 ----

    @Test fun executorThrowsUnexpectedExceptionFailsClosed() = runBlocking {
        // Dispatcher catch-all：非取消的 RuntimeException 映射為型別化 EXEC_ERROR
        // skip——永不外拋、永不回音 executor 訊息（test 目標是 dispatcher 的
        // catch-all，故直接構造 throwing executor，不經 LspSymbolsExecutor/backend）。
        val entered = AtomicInteger(0)
        val thrower = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
                entered.incrementAndGet()
                throw RuntimeException("marker-should-not-ride")
            }
        }
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to thrower)).dispatch(d, lspOn)
        val skipped = skippedOf(out, "executor threw")
        assertEquals("EXEC_ERROR", skipped.denyCode)
        assertFalse(skipped.fallbackMessage!!.contains("marker-should-not-ride"))
        assertEquals("executor must be entered exactly once", 1, entered.get())
    }

    @Test fun lookupCancellationPropagates() {
        runBlocking {
        // lookup 拋 CancellationException → 外拋（不得映射為 LOOKUP_ERROR Skipped）；
        // executor / backend 零接觸（lookup 先於 executor）。
        val backend = CountingBackend()
        val dispatcher = FastDispatcher(
            mapOf(LspTools.SYMBOLS_NAME to LspSymbolsExecutor(backend)),
            lookup = { throw CancellationException("lookup-cancel-marker") },
        )
        val d = decide("幫我查 Foo 這個符號")
        assertThrows(CancellationException::class.java) { runBlocking { dispatcher.dispatch(d, lspOn) } }
        assertEquals(0, backend.invocations.get())
        }
    }

    @Test fun fastCallIdsAreUniqueAcrossRouterInstances() {
        // Process-wide AtomicLong：callId 跨 FastRouter instance 亦唯一。
        val d1 = FastRouter().decide("幫我查 Foo 這個符號", lspOn)
        val d2 = FastRouter().decide("查 Bar 符號", lspOn)
        val d3 = FastRouter().decide("幫我查 Baz 這個符號", lspOn)
        assertEquals(RouteKind.FAST, d1.route)
        assertEquals(RouteKind.FAST, d2.route)
        assertEquals(RouteKind.FAST, d3.route)
        val id1 = d1.toolCall!!.callId
        val id2 = d2.toolCall!!.callId
        val id3 = d3.toolCall!!.callId
        assertNotEquals("callIds must be unique across FastRouter instances", id1, id2)
        assertEquals("callIds must be all-distinct across instances", 3, setOf(id1, id2, id3).size)
        for (id in listOf(id1, id2, id3)) {
            assertTrue("callId must carry the fast- prefix: $id", id.startsWith("fast-"))
        }
    }

    @Test fun executorDeniedAllowlistBoundaryIsEnforced() = runBlocking {
        // boundedDenyCode 的 allowlist ^[A-Z0-9_]{1,64}$ 邊界：null／""／65 字 →
        // EXEC_ERROR；64 字合法碼原樣放行；僅 "SCHEMA_INVALID" 映射
        // NEED_CLARIFICATION——其餘（含 allowlist 放行碼）皆 USER_DISABLED。
        val d = decide("幫我查 Foo 這個符號")
        val call = d.toolCall!!
        val cases = listOf(
            null to "EXEC_ERROR",
            "" to "EXEC_ERROR",
            "A".repeat(64) to "A".repeat(64),
            "A".repeat(65) to "EXEC_ERROR",
        )
        for ((code, expected) in cases) {
            val executor = executorReturning(
                FastExecResult(
                    LspTools.SYMBOLS_NAME,
                    call.callId,
                    """{"denied":true}""",
                    denied = true,
                    denyCode = code,
                ),
            )
            val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to executor)).dispatch(d, lspOn)
            val skipped = skippedOf(out, "denyCode=<$code>")
            assertEquals("denyCode=<$code> must map to $expected", expected, skipped.denyCode)
            assertEquals(
                "denyCode=<$code> (not SCHEMA_INVALID) must map to USER_DISABLED",
                DenyReason.USER_DISABLED,
                skipped.reasonCode,
            )
        }
    }

    @Test fun symbolEnglishHintRoutesPositively() {
        // Pin the \bsymbols?\b positive routing: 英文符號意圖必須 FAST。
        // 若日後停止路由，本測試 MUST 轉紅（不得靜默地 strand 為 no-match DENIED）。
        val d1 = decide("symbols Foo")
        assertEquals(RouteKind.FAST, d1.route)
        assertTrue(d1.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""))
        val d2 = decide("symbol Foo")
        assertEquals(RouteKind.FAST, d2.route)
        assertTrue(d2.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""))
    }

    private fun fastDecision(toolName: String, argumentsJson: String, callId: String): RouteDecision =
        RouteDecision(
            route = RouteKind.FAST,
            toolCall = ToolCall(toolName, argumentsJson, callId),
            reasonCode = null,
            fallbackMessage = null,
            header = TranscriptHeader(
                sessionId = "s1",
                turnIndex = 1,
                route = RouteKind.FAST,
                toolName = toolName,
                reasonCode = null,
                visibleTools = emptyList(),
            ),
        )

    private fun executorReturning(result: FastExecResult): FastToolExecutor =
        object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult = result
        }
}
