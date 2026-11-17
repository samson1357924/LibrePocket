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

    private class ThrowingIterationBackend : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            // okResult projection 時（take/size/get/iterator）即爆炸的 List：
            // okResult 內部邊界須將其映射為 typed denial（EXEC_ERROR），不外拋。
            return object : AbstractList<LspTools.Symbol>() {
                override val size: Int
                    get() = throw RuntimeException("backend-iteration-marker")

                override fun get(index: Int): LspTools.Symbol =
                    throw RuntimeException("backend-get-marker")
            }
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
        assertEquals("Foo", Json.parseToJsonElement(call.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)
        assertNull(d.reasonCode)

        val out = lspDispatcher(backend).dispatch(d, lspOn)
        val executed = out as? FastDispatchOutcome.Executed
        assertNotNull("FAST lsp.symbols must execute against the registered fake backend (got $out)", executed)
        assertEquals(1, backend.invocations.get())
        // decide() 產生的 benign callId 為 redact-stable，bounded == raw；
        // Executed outcome 現在攜帶有界形態（見「executedCallIdCarriesBoundedForm」）。
        assertEquals(call.callId, executed!!.result.callId)
        assertFalse(executed.result.denied)
        assertTrue(executed.result.resultJson.contains("\"name\":\"Foo:example\""))
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
        assertTrue(executed!!.result.resultJson.contains("\"name\":\"Bar:example\""))
    }

    @Test fun codeIdentifierSymbolNamesRouteToLspSymbols() = runBlocking {
        // 識別字含其他工具的（無錨點）hint 子串：AlarmManager⊃alarm、
        // NotificationManager⊃notification、CalendarProvider⊃calendar、
        // ScreenshotController⊃screenshot、EmailClient⊃e-?mail、
        // VolumeController⊃volume——舊 RULES 順序會被這些工具搶走；
        // symbol 前閘（stage-8.5 最先：SLOW_HINTS 之前、RULES 之前）獨占 symbol 匹配，
        // `查 X 符號` 必綁 lsp.symbols 而非 keyword 工具。
        // 每個 case 各自 decide → dispatch（[lspDispatcher] + [CountingBackend]
        // 計數），逐 case 斷言 invocations +1，最終累計恰 6。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (identifier in listOf(
            "AlarmManager",
            "NotificationManager",
            "CalendarProvider",
            "ScreenshotController",
            "EmailClient",
            "VolumeController",
        )) {
            val text = "查 $identifier 符號"
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertTrue(
                "query must isolate the identifier: ${d.toolCall!!.argumentsJson}",
                d.toolCall!!.argumentsJson.contains("\"query\": \"$identifier\""),
            )
            val before = backend.invocations.get()
            val out = dispatcher.dispatch(d, lspOn)
            val executed = out as? FastDispatchOutcome.Executed
            assertNotNull("$text must execute against the fake backend (got $out)", executed)
            assertFalse(executed!!.result.denied)
            assertTrue(
                "executed body must echo the queried identifier: ${executed.result.resultJson}",
                executed.result.resultJson.contains(identifier),
            )
            assertEquals(text, before + 1, backend.invocations.get())
        }
        assertEquals("backend must be invoked exactly once per case", 6, backend.invocations.get())
    }

    @Test fun symbolQueryBeatsSlowHints() = runBlocking {
        // stage-8.5（Re-review #8 A4）：SLOW_HINTS 以 bare substring 命中 `自動操作`／
        // `自動填表` 不得搶先——`查 X 符號` 是 explicit code-symbol 查詢；
        // symbol 前閘（stage-8.5 最先）命中 ⇒ lsp.symbols；SLOW 僅在前閘不成立時先領。
        // 逐 case decide → decideAndDispatch（同一 CountingBackend），invocations +1，
        // receipt body 原樣回顯識別字（"name":"$expectedQuery:example"）。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for ((text, expectedQuery) in listOf(
            "查 自動操作 符號" to "自動操作",
            "查 自動填表 符號" to "自動填表",
            // 識別字本身即中文自動化片語（記錄已接受殘餘：內容而非權限；fast lane 不執行自動化）：
            "symbols 幫我自動填表" to "幫我自動填表",
            "查 幫我點 符號" to "幫我點",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s-slow", turnIndex = 6)
            assertEquals(text, RouteKind.FAST, result.decision.route)
            assertEquals(text, "lsp.symbols", result.decision.toolCall?.toolName)
            val executed = result.outcome as? FastDispatchOutcome.Executed
            assertNotNull("$text must execute against the fake backend (got ${result.outcome})", executed)
            assertEquals(text, before + 1, backend.invocations.get())
            assertTrue(
                "executed body must echo the identifier verbatim: ${executed!!.result.resultJson}",
                executed.result.resultJson.contains("\"name\":\"$expectedQuery:example\""),
            )
        }
        assertEquals("backend must be invoked exactly once per case", 4, backend.invocations.get())
    }

    @Test fun symbolQueryBeatsKeywordHints() {
        // 前閘必須帶 lookup intent 才生效 ⇒ 非 symbol 意圖照常落 keyword 工具，
        // 且 SYMBOLS 自 RULES 移除後 volume 規則依然有效。
        // 採用不含任何工具 hint 子串的 Widget（`AlarmManager` ⊃ 無錨點 `alarm`
        // 會命中 alarm.create，不適合作 volume.set 的探針）：
        // `音量` 命中 VOLUME_HINTS → volume.set（未 ready ⇒ DENIED USER_DISABLED，
        // header.toolName ＝ volume.set）——前閘未吞、volume 規則完好。
        val d1 = decide("查 Widget 音量")
        assertEquals(RouteKind.DENIED, d1.route)
        assertEquals("volume.set", d1.header.toolName)

        // 禮貌填充語（幫我…這個）下 symbol 查詢仍綁 lsp.symbols，query 剝離正確。
        val d2 = decide("幫我查 AlarmManager 這個符號")
        assertEquals(RouteKind.FAST, d2.route)
        assertEquals("lsp.symbols", d2.toolCall?.toolName)
        assertNotNull(d2.toolCall)
        assertTrue(d2.toolCall!!.argumentsJson.contains("\"query\": \"AlarmManager\""))

        // A2 閘在前閘重排後依然存活：symbol hint 出現但無 lookup marker ⇒
        // 前閘不生效、其餘 RULES 亦無命中 ⇒ no-match DENIED
        // （reasonCode null、toolCall null）——絕不綁 lsp.symbols。
        // 採用不含其他工具 hint 的 SymbolTable（`符號 AlarmManager` 會命中
        // 無錨點 `alarm` → alarm.create，不是 no-match）。
        val d3 = decide("符號 SymbolTable")
        assertEquals(RouteKind.DENIED, d3.route)
        assertNull(d3.reasonCode)
        assertNull(d3.toolCall)
    }

    @Test fun interrogativeKeywordFallbackClarifiesButNeverFast() = runBlocking {
        // 問句豁免只關 symbol 前閘：marker+問句形式仍要符合 keyword 規則的
        // fail-closed 語意——絕不 FAST。`查 AlarmManager 符號呢` → alarm.create
        // 缺時間澄清（keyword 規則命中），後端零呼叫；`查 Foo 符號呢` → 無
        // keyword 規則命中 ⇒ no-match DENIED（reasonCode null）。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        val d = decide("查 AlarmManager 符號呢")
        assertEquals(RouteKind.DENIED, d.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, d.reasonCode)
        assertEquals("alarm.create", d.header.toolName)
        assertNull(d.toolCall)
        skippedOf(dispatcher.dispatch(d, lspOn), "interrogative keyword fallback")
        assertEquals(0, backend.invocations.get())

        val d2 = decide("查 Foo 符號呢")
        assertEquals(RouteKind.DENIED, d2.route)
        assertNull(d2.reasonCode)
        assertNull(d2.toolCall)
        skippedOf(dispatcher.dispatch(d2, lspOn), "interrogative no-match fallback")
        assertEquals(0, backend.invocations.get())
    }

    @Test fun negatedIntentsNeverExecute() = runBlocking {
        val backend = CountingBackend()
        val phoneFake = CountingFake("phone.dial")
        val dispatcher = lspDispatcher(backend, mapOf("phone.dial" to phoneFake))
        // #19 B3 stage-1: symbol 否定前置句（starts with 不/別/没）不再滿足
        // symbol 前閘的命令式准入（start-anchored opener 不命中）⇒ 落入 RULES
        // 無命中 ⇒ no-match DENIED（reasonCode null）；其餘 keyword 探針仍照常
        // 綁模板 + 否定閘轉 NEED_CLARIFICATION。兩群組皆為 DENIED、toolCall null、
        // dispatch Skipped、後端零呼叫——絕不執行。
        val clarifyTexts = listOf(
            "不要打電話給 0912345678",
            "別設鬧鐘，明天早上7點叫我",
        )
        val noMatchTexts = listOf(
            "不要查 Foo 這個符號",
            "別查 Foo 這個符號",
            "别查 Foo 符號",
            "不用幫我查符號",
        )
        for (text in clarifyTexts) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            val out = dispatcher.dispatch(d, lspOn)
            val skipped = skippedOf(out, text)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, skipped.reasonCode)
        }
        for (text in noMatchTexts) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            val out = dispatcher.dispatch(d, lspOn)
            val skipped = skippedOf(out, text)
            assertNull(text, skipped.reasonCode)
        }
        assertEquals(0, backend.invocations.get())
        assertEquals("clarification path must not execute even with an executor registered", 0, phoneFake.invocations.get())
    }

    @Test fun negatedSymbolLookupVariantsNeverExecute() = runBlocking {
        // 繁簡對稱 `不/別/沒`×動詞 否定符號查詢意圖：一律 DENIED、toolCall == null；
        // dispatch 亦必須 Skipped、後端零呼叫（不得走 FAST 執行）。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        // #19 B3 stage-1: symbol 否定前置句皆不滿足命令式准入（start-anchored
        // opener 不命中）⇒ 落入 RULES 無命中 ⇒ no-match DENIED（reasonCode null）——
        // DENIED、toolCall null、後端零呼叫，絕不執行。其中與 keyword 規則相關者
        // 才會命中 RULES（見 otherTexts）。
        // （`問`/`設` 等非 lookup 動詞的陰性探針見 mixedScriptNegationNeverExecutes。）
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
            // #19 B3 stage-1: 不再澄清（no-match DENIED，reasonCode null）。
            assertNull(text, d.reasonCode)
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

        // 已記錄並接受：空格分隔的動詞 + 識別字。「查 查理 符號」剝成「查理」
        // （stage-6.3 位置語法：動詞 cue 在句首，`查理` 為象徵識別字名，完整保留
        // ——不再全域刪填充詞）。
        // Documented (stage-6.3): the spaced verb cue no longer eats `查理`;
        // previously the global filler delete rewrote `查 查理 符號` to `理`.
        // (The UNspaced `查理 符號` remains a positional-opener strip to `理` —
        // pinned separately in `positionalCueGrammarPins`.)
        val t3 = decide("查 查理 符號")
        assertEquals(RouteKind.FAST, t3.route)
        assertEquals("查理", Json.parseToJsonElement(t3.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)

        // Meta-question token-only utterances now route no-match (A2: no
        // lookup marker ⇒ symbol template does not match) — DENIED, no
        // reasonCode, never executes.
        val t4 = decide("符號是什麼？")
        assertEquals(RouteKind.DENIED, t4.route)
        assertNull(t4.reasonCode)
        assertNull(t4.toolCall)

        // Punctuation-only remainder ⇒ clarification (meaningful-remainder gate).
        val t5 = decide("查 - 符號")
        assertEquals(RouteKind.DENIED, t5.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, t5.reasonCode)

        // Simplified hint routes too.
        val t6 = decide("查 Foo 符号")
        assertEquals(RouteKind.FAST, t6.route)
        assertEquals("Foo", Json.parseToJsonElement(t6.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)

        // `symbolize` must NOT route (word-anchored latin hint).
        val t7 = decide("symbolize")
        assertEquals(RouteKind.DENIED, t7.route)
        assertNull(t7.toolCall)

        // EN lookup verbs strip from the query (word-anchored, case-insensitive).
        val t8 = decide("Search SYMBOL Foo")
        assertEquals(RouteKind.FAST, t8.route)
        assertTrue("query must be Foo: ${t8.toolCall!!.argumentsJson}", t8.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""))

        // Marker-only remainder clarifies (empty query after the EN verb strip).
        val t9 = decide("find symbol")
        assertEquals(RouteKind.DENIED, t9.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, t9.reasonCode)
    }

    @Test fun englishLookupMarkerStripped() {
        // EN lookup verbs strip from the query (word-anchored, case-insensitive);
        // the bare marker with an empty remainder clarifies, and the command
        // form without a lookup verb still routes FAST.
        for (text in listOf(
            "find symbol Foo",
            "Search SYMBOL Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertTrue("query must be Foo: ${d.toolCall!!.argumentsJson}", d.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""))
        }
        // 內嵌存活：theSymbol/SymbolOfFoo（`the`/`of` 無邊界不切）不得被剝出。
        for (text in listOf("查 theSymbol 符號", "查 SymbolOfFoo 符號")) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            val ident = text.removePrefix("查 ").removeSuffix(" 符號")
            assertTrue(
                "query must be $ident: ${d.toolCall!!.argumentsJson}",
                d.toolCall!!.argumentsJson.contains("\"query\": \"$ident\""),
            )
        }
        // 單複數 `find symbols` 剝空 ⇒ 澄清（且不會 Fast）。
        // #19 B3 stage-8.3（Re-review #8 A2）：`查 find 符號` 的 EN-delimited 殘餘
        // `符號` 原樣保留 ⇒ FAST `符號`（原 NEED_CLARIFICATION pin 翻轉）。
        // #19 B3 stage-9.2（Re-review #9 A2, [P2]）：兩階段 CJK-preferred-fallback 後，
        // CJK-leading 語句先讀 CJK 語法——尾綴 cue 剝去後 `find` 為合法 verbatim
        // 識別字 ⇒ FAST `find`（stage-8.3 pin 再度翻轉；EN-started 之 `find symbol symbol`
        // ⇒ FAST `symbol` 見 enCueBudgetAndCjkPreferredExtractionPinned）。
        val bareSymbol = decide("查 find 符號")
        assertEquals(RouteKind.FAST, bareSymbol.route)
        assertEquals("find", Json.parseToJsonElement(bareSymbol.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)
        val bare = decide("find symbols")
        assertEquals(RouteKind.DENIED, bare.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, bare.reasonCode)
        val cmd = decide("Symbols Foo")
        assertEquals(RouteKind.FAST, cmd.route)
        assertTrue("query must be Foo: ${cmd.toolCall!!.argumentsJson}", cmd.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""))
    }

    @Test fun englishLookupMarkersRouteFast() {
        // EN word-anchored lookup verbs (find/search/lookup/locate) are lookup
        // markers: symbol template matches ⇒ FAST with the stripped query.
        for (text in listOf(
            "find symbols Foo",
            "search symbols Foo",
            "lookup symbols Foo",
            "locate symbols Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertTrue("query must be Foo: ${d.toolCall!!.argumentsJson}", d.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""))
        }
        // `where` is deliberately NOT a lookup marker (A2): no-match
        // DENIED, reasonCode null, toolCall null.
        val d = decide("where is symbol Foo")
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.reasonCode)
        assertNull(d.toolCall)
    }

    @Test fun englishConnectiveTokensStripped() {
        // #5（B3 re-review）EN connective tokens（for/the/a/an/named/called/of/to，
        // word-anchored、IGNORE_CASE）自 query 剝離——`search for symbol Foo` 不得
        // 剝出 `forFoo`、`find the symbol Foo` 不得剝出 `theFoo`、
        // `locate symbol named Foo` 不得剝出 `namedFoo`。
        for (text in listOf(
            "search for symbol Foo",
            "find the symbol Foo",
            "lookup the symbol Foo",
            "locate symbol named Foo",
            "locate symbol called Foo",
            "find a symbol Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertTrue(
                "query must be Foo: ${d.toolCall!!.argumentsJson}",
                d.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""),
            )
            val json = d.toolCall!!.argumentsJson
            assertFalse("forFoo must be stripped: $json", json.contains("forFoo"))
            assertFalse("theFoo must be stripped: $json", json.contains("theFoo"))
            assertFalse("namedFoo must be stripped: $json", json.contains("namedFoo"))
            assertFalse("calledFoo must be stripped: $json", json.contains("calledFoo"))
            assertFalse("aFoo must be stripped: $json", json.contains("aFoo"))
        }
        // 內嵌名不被誤剝：`theSymbol` 的 `the` 無 word boundary ⇒ 完整保留。
        val embedded = decide("查 theSymbol 符號")
        assertEquals(RouteKind.FAST, embedded.route)
        assertTrue(
            "embedded `the` in theSymbol must survive the strip: ${embedded.toolCall!!.argumentsJson}",
            embedded.toolCall!!.argumentsJson.contains("\"query\": \"theSymbol\""),
        )
    }

    @Test fun qualifiedNamesPreservedVerbatim() = runBlocking {
        // #19 stage-2（A4 修復）：the old SYMBOL_QUERY_STRIP globally deleted
        // `. , ; : ( ) @ # / …` before the executor ran — `查 Foo.bar 符號` routed
        // FAST with query `Foobar` (wrong symbol, silently). The new pipeline is
        // positional and lossless: intra-token punctuation survives verbatim.
        // Full-args exact pin per case (verbatim query + DEFAULT_LIMIT).
        for ((text, expectedQuery) in listOf(
            "查 Foo.bar 符號" to "Foo.bar",
            "查 UserService.get 符號" to "UserService.get",
            "查 com.example.Foo 符號" to "com.example.Foo",
            "查 std::vector 符號" to "std::vector",
            "查 Foo::Bar 符號" to "Foo::Bar",
            "查 foo/bar.kt 符號" to "foo/bar.kt",
            "查 get(id) 符號" to "get(id)",
            "查 3.14 符號" to "3.14",
            "查 3,14 符號" to "3,14",
            "查 SymbolTable 符號" to "SymbolTable",
            "查 中文變數 符號" to "中文變數",
            // Edge `-` is NOT edge-stripped (Clojure-family identifiers).
            "查 -main 符號" to "-main",
            // `lsp` carries no `\blsp\b` strip any more — verbatim.
            "查 lsp 符號" to "lsp",
            // Digit-only remainders anchor the meaningful-remainder gate.
            "查 42 符號" to "42",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                """{"query": "$expectedQuery", "limit": ${LspTools.DEFAULT_LIMIT}}""",
                d.toolCall!!.argumentsJson,
            )
            val obj = Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject
            assertEquals(text, expectedQuery, obj["query"]!!.jsonPrimitive.content)
            assertEquals(text, LspTools.DEFAULT_LIMIT, obj["limit"]!!.jsonPrimitive.content.toInt())
        }
        // Dispatch variant: `查 com.example.Foo 符號` executes against the fake
        // backend exactly once and the executor receives the exact qualified
        // query (surfaced in the synthetic symbol name the fake returns).
        val backend = CountingBackend()
        val r = lspDispatcher(backend).decideAndDispatch("查 com.example.Foo 符號", lspOn, sessionId = "s-qn", turnIndex = 3)
        assertEquals(RouteKind.FAST, r.decision.route)
        assertEquals(1, backend.invocations.get())
        val executed = r.outcome as? FastDispatchOutcome.Executed
        assertNotNull("com.example.Foo must execute: ${r.outcome}", executed)
        assertTrue(
            "executor must receive the exact qualified query: ${executed!!.result.resultJson}",
            executed.result.resultJson.contains("com.example.Foo:example"),
        )
        // Dispatch variant for the dropped \blsp\b strip: `查 lsp 符號` executes
        // with query `lsp` (lsp is a legitimate identifier — parity with the
        // decide-level pin above).
        val backend2 = CountingBackend()
        val r2 = lspDispatcher(backend2).decideAndDispatch("查 lsp 符號", lspOn, sessionId = "s-qn2", turnIndex = 4)
        assertEquals(RouteKind.FAST, r2.decision.route)
        assertEquals(1, backend2.invocations.get())
        val executed2 = r2.outcome as? FastDispatchOutcome.Executed
        assertNotNull("lsp must execute: ${r2.outcome}", executed2)
        assertTrue(
            "executor must receive the exact query `lsp`: ${executed2!!.result.resultJson}",
            executed2.result.resultJson.contains("lsp:example"),
        )
    }

    @Test fun positionalCueGrammarPins() {
        // #19 B3 stage-6.3：positional cue grammar — every pin asserts the
        // parsed query character-exactly (jsonPrimitive content), not a
        // substring match. Identifier content is never rewritten: intra-
        // token punctuation (`.`, `::`, `/`, `-`) and CJK identifiers survive
        // verbatim; cue strips happen only at position 0 / at the trailing
        // suffix edge.
        for ((text, expectedQuery) in listOf(
            // The four reviewer-reported silent rewrites, now verbatim:
            "查 Foo.symbol.bar 符號" to "Foo.symbol.bar",
            "查 foo/symbol.kt 符號" to "foo/symbol.kt",
            "查 Foo::symbol 符號" to "Foo::symbol",
            // More qualified-name round-trips:
            "查 Foo.symbol 符號" to "Foo.symbol",
            "查 Foo.symbols 符號" to "Foo.symbols",
            "查 foo.symbols 符號" to "foo.symbols",
            // CJK filler-substring deletion is gone (identifiers survive):
            "查 使用者 符號" to "使用者",
            "查 符號表 符號" to "符號表",
            "查 查理 符號" to "查理",
            "查 用例 符號" to "用例",
            "查 中文變數 符號" to "中文變數",
            "查 AlarmManager 這個符號" to "AlarmManager",
            // Simplified 这个 suffix cue (stage-6.3 suffix-cue table fix,
            // canonical U+8FD9 U+4E2A):
            "查 AlarmManager 这个符號" to "AlarmManager",
            // Stage-6.3 reviewer pins (F6): a halfwidth/fullwidth `,` glued to
            // the identifier at cue position is absorbed by the step-C suffix
            // cue (the edge class has no halfwidth comma; see KDoc):
            "查 Foo, 符號" to "Foo",
            "查 Foo， 符號" to "Foo",
            "查 Foo，符號" to "Foo",
            // EN keyword-inline comma (A4, stage-7.4 — CLOSED; was the F7
            // recorded residual): step A's command-form opener
            // (`^symbols?\b[\s,，]*`) now eats the keyword AND a glued or
            // following inline comma (READ-only):
            "find symbol,Foo" to "Foo",
            "find symbol, Foo" to "Foo",
            // 記錄殘餘（stage-7.4 收尾）：opener 分隔類吸收前置 `,`/`，`/` `（Cue 位置）：
            "查 ,Foo 符號" to "Foo",
            "查 ，Foo 符號" to "Foo",
            "search symbol ,Foo" to "Foo",
            // 記錄殘餘（stage-2）：開頭 `.` 不在 deny 尾端類（`.bar` 為合法識別字開頭）：
            "查 .bar 符號" to ".bar",
            // Unspaced 這個符號 glued to the identifier (recorded residual):
            "查 AlarmManager這個符號" to "AlarmManager",
            // #19 B3 stage-7.3 (A3)：CJK 識別字以 `符號` 結尾、無空白黏合的單一 cue
            // ——尾綴單 cue 消費，殘餘不再匹配尾綴 cue ⇒ FAST（僅雙 cue 黏合形態澄清）：
            "查 使用者符號" to "使用者",
            // `函式`/`函数` are identifiers, never strip words:
            "查 函式 符號" to "函式",
            // Single-letter-token shapes stand (was: CJK filler delete):
            "查 是 符號" to "是",
            // #19 B3 stage-7.2 (A2)：CJK 複合查詢動詞 longest-match-first——
            // SYMBOL_CJK_COMMAND_TOKEN 整體消費複合動詞，不再把第二字形
            // （詢/尋/索/找…）誤留為識別字前綴：
            "查詢Foo符號" to "Foo", // 查（U+67E5）詢（U+8A62）複合動詞
            "搜尋Foo符號" to "Foo",
            "搜索Foo符號" to "Foo",
            "查询Foo符号" to "Foo",
            "查找Foo符號" to "Foo",
            "尋找Foo符號" to "Foo",
            "找尋Foo符號" to "Foo",
            "搜寻Foo符號" to "Foo",
            "寻找Foo符號" to "Foo",
            "找寻Foo符號" to "Foo",
            // 帶空白複合動詞：`查詢 Foo 符號` 先前（stage-7.2 前）殘餘 `詢 Foo` 含空白 ⇒ 澄清；
            // 現 `查詢` 整體消費 ⇒ FAST `Foo`（clarify→FAST 翻轉，記錄並釘 FAST）：
            "查詢 Foo 符號" to "Foo",
            // 單字開頭＋空白：複合不成立，`查` 為位置 cue ⇒ `詢Foo` 為合法識別字，原樣保留：
            "查 詢Foo 符號" to "詢Foo",
            // 記錄殘餘（Re-review #7 複核）：首字為單字 opener 的兩字動詞視為剩餘原樣識別字（READ-only）：
            "詢問Foo符號" to "問Foo", // 首字「詢」∈ opener
            "查看Foo符號" to "看Foo", // 首字「查」∈ opener
            // 禮貌前綴＋複合動詞：
            "請查詢Foo符號" to "Foo",
            // 複合動詞＋一下：
            "查詢一下Foo符號" to "Foo",
            // 單字＋空白（行為不變，釘住）：
            "找 Foo 符號" to "Foo",
            // 記錄殘餘（stage-7.3 A3 複核裁決）：前綴為 cue 詞但有內容識別字 → verbatim 承認：
            "查 符號Extra 符號" to "符號Extra", // 與 `符號表` 同語義——殘餘 verbatim
            // 記錄殘餘（stage-2/F1 邊析裁決）：step D 邊緣類透明度——glued `。`＋cue 可整段剝：
            "查 Foo 。符號" to "Foo",          // U+3002 邊緣類透明度（step D 剝 `。`）→ FAST Foo
            // 記錄殘餘（stage-7.4A4 複核裁決）：suffix cue 分隔類吸收 U+FF0C（AlarmManager 為合法識別字）：
            "查 AlarmManager，這個符號" to "AlarmManager",
            // #19 stage-7.2 收尾：簡體複合與 disclaimer `質詢`（首字 `質`∉ opener）：
            // 保留：Implicit plain identifier still routes FAST.
            "查 Foo 符號" to "Foo",
            // 無空格形態：句首 lookup 動詞「查」仍是位置 cue（positional opener，
            // spec Step A）——殘餘 `理` 乃位置剝離，非全域填充詞刪除。
            "查理 符號" to "理",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertNotNull(text, d.toolCall)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertEquals(
                text,
                """{"query": "$expectedQuery", "limit": ${LspTools.DEFAULT_LIMIT}}""",
                d.toolCall!!.argumentsJson,
            )
            val obj = Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject
            assertEquals(text, expectedQuery, obj["query"]!!.jsonPrimitive.content)
            assertEquals(text, LspTools.DEFAULT_LIMIT, obj["limit"]!!.jsonPrimitive.content.toInt())
        }
    }

    @Test fun cueFinalNaturalPunctuationClarifies() = runBlocking {
        // #19 B3 stage-6.3：殘餘以 `. , ; :` 結尾 ⇒ 句末自然標點 hazard ⇒
        // NEED_CLARIFICATION（絕不執行）——`.` `,` `;` 不在 edge 類，`:` 雖在
        // edge（舊語義保留），半形 `.` `,` `;` 殘留即被 step E 拒絕。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            // `查 Foo. 符號`：舊版 FAST query=`Foo.`（已退役 residual）。
            "查 Foo. 符號",
            "search symbol Foo.",
            "search symbol Foo,",
            "search symbol Foo;",
            // `函式` 已非剝離詞：多 token 殘餘 ⇒ 澄清（絕不接合）。
            "查 Foo 函式 符號",
            // Chimera cue（簡化「这」U+8FD9 + 繁體「個」U+500B）：既非 `這個` 也非
            // `这个`，匹配不到 step C 的任何後綴 cue alternative ⇒ 殘餘仍帶空白
            // ⇒ step E 拒絕 ⇒ NEED_CLARIFICATION（絕不執行）。
            // 非句末自然標點情形，per stage-6.3 reviewer pin 放本 clarify 測試記錄。
            "查 AlarmManager 这個符號",
            // Stage-7.4（A4）：殘餘以半形/全形 `;` 開頭 ⇒ step E 首標點拒絕 ⇒
            // NEED_CLARIFICATION（絕不執行）——任何 opener 都不吃 `;`，前導分號
            // 直達 step E。（`,` 前導的形態——`查 ,Foo 符號`／`查 ，Foo 符號`／
            // `search symbol ,Foo`——被 opener separator 類吸收 ⇒ FAST `Foo`，
            // 非澄清；見 positionalCueGrammarPins 與提取 KDoc。）
            "查 ;Foo 符號",
            "查 ；Foo 符號",
            "search symbol ;Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun ambiguousTrailingCueClarifiesAndNeverExecutes() = runBlocking {
        // #19 B3 stage-7.3 (A3)：尾綴 CJK cue 至多剝一次；剝完殘餘仍以 cue 結尾
        // （或就是 cue 本身）⇒ 雙 cue 形態結構性歧義 ⇒ NEED_CLARIFICATION（絕不執行）。
        // 舊（stage-6.3）注記：`查 Foo符号 符號`／`查 Foo 符号 符號` 等因尾綴 cue
        // 循環剝離而誤判 FAST（`Foo`）——已退役殘餘。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            // 雙 cue 黏合／空白分隔（繁／簡混用）：單一步驟-C 剝離後殘餘仍尾綴 cue。
            "查 Foo符號 符號",
            "查 Foo符号 符號",
            "查 Foo 符号 符號",
            "查 Foo 符號 符號",
            "查 Foo符号符號",
            // 殘餘恰為 cue 本身：`符號 符號` 剝一個後剩 `符號`，歧義守衛拒絕。
            "查 符號 符號",
            // 句末終止符包住單一 cue：step C/D 剝離曝露後恰為 cue ⇒ 守衛拒絕（審計 F1）。
            "查 符號。",
            "查 符號：",
            "查 符號。符號",
            "查 「符號」",
            "查 「符號」符號",
            "查 （符號）",
            "查 符號。 符號",
            // 單 cue 即全殘餘：剝完為空 ⇒ step E 拒絕。
            "查 符號",
            "查 符号。",
            "查 Fog符号符號",
            "查 Foo 符號Extra 符號",
            "查 符號符號",
            "查 Foo符號　符號",
            "查 (這個符號)",   // 包夾黏著複合 cue 的全匹配守衛（審計 F4 收斂）
            "查 .符號.",
            "查 ( 符號 )",
            // 包夾於識別字標點的單一 cue（全匹配守衛；審計 B1）——與全形 `（符號）` 對稱：
            "查 (符號)",
            "查 (符号)",
            "查 -符號-",
            "查 符號+",
            "查 _符號_",
            "查 #符號#",
            "查 \$符號\$",
            "查 ~符號~",
            "查 =符號=",
            "查 符號/",
            "查 符號-",
            "查 符號)",
            "查 符號(",
            // 句末終止符＋ cue₂ 前的單 cue（fail-closed flip，使翻轉刻意化）：
            "查 Foo 符號。",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun dispatchQualifiedDottedNameExecutesVerbatim() = runBlocking {
        // #19 B3 stage-6.3：dispatch-level pin — `查 Foo.symbol.bar 符號`
        // executes exactly once and the executor receives exactly
        // `Foo.symbol.bar` as the query (surfaced via the fake backend's
        // `${query}:example` symbol name).
        val backend = CountingBackend()
        val r = lspDispatcher(backend).decideAndDispatch(
            "查 Foo.symbol.bar 符號", lspOn, sessionId = "s-dotted", turnIndex = 5,
        )
        assertEquals(RouteKind.FAST, r.decision.route)
        assertEquals(1, backend.invocations.get())
        assertEquals(
            """{"query": "Foo.symbol.bar", "limit": ${LspTools.DEFAULT_LIMIT}}""",
            r.decision.toolCall!!.argumentsJson,
        )
        val executed = r.outcome as? FastDispatchOutcome.Executed
        assertNotNull("Foo.symbol.bar must execute: ${r.outcome}", executed)
        assertTrue(
            "executor must receive the exact dotted query: ${executed!!.result.resultJson}",
            executed.result.resultJson.contains("Foo.symbol.bar:example"),
        )
    }

    @Test fun dispatchGluedSingleCueExecutesVerbatim() = runBlocking {
        // #19 B3 stage-7.3 (A3)：dispatch-level pin — `查 AlarmManager這個符號`
        // (glued single cue) executes exactly once and the executor receives exactly
        // `AlarmManager` as the query (surfaced via the fake backend's
        // `${query}:example` symbol name).
        val backend = CountingBackend()
        val r = lspDispatcher(backend).decideAndDispatch(
            "查 AlarmManager這個符號", lspOn, sessionId = "s-glued", turnIndex = 6,
        )
        assertEquals(RouteKind.FAST, r.decision.route)
        assertEquals(1, backend.invocations.get())
        assertEquals(
            """{"query": "AlarmManager", "limit": ${LspTools.DEFAULT_LIMIT}}""",
            r.decision.toolCall!!.argumentsJson,
        )
        val executed = r.outcome as? FastDispatchOutcome.Executed
        assertNotNull("AlarmManager must execute: ${r.outcome}", executed)
        assertTrue(
            "executor must receive the exact glued-cue query: ${executed!!.result.resultJson}",
            executed.result.resultJson.contains("AlarmManager:example"),
        )
    }

    @Test fun singleLetterIdentifiersQueryable() {
        // #19 stage-2（A3 修復）：EN connectives/單字母識別字不再被誤剝——
        // `查 a 符號`/`查 to 符號`/`查 for 符號` 都是合法 FAST 查詢（`find symbol a`
        // 同）。舊全域 `\b(for|the|a|an|named|called|of|to)\b` 刪除會把這些吃空
        // → NEED_CLARIFICATION（或誤改寫）。
        for ((text, expectedQuery) in listOf(
            "查 a 符號" to "a",
            // 審計 B1 的正向邊界：cue 兩側皆有識別字內容 ⇒ verbatim FAST（全匹配錨）：
            "查 get(符號)" to "get(符號)",
            "查 使用者(符號)" to "使用者(符號)",
            "查 to 符號" to "to",
            "查 for 符號" to "for",
            "find symbol a" to "a",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test fun connectiveTokensStillStripArticle() {
        // #19 stage-2：EN connectives 只在 cue 位置剝離（Step A post-verb
        // connective slot；Step D leading connective with another token after）
        // ——article 語境仍剝成 Foo。
        for (text in listOf(
            "find a symbol Foo",
            "find the symbol Foo",
            "search for symbol Foo",
            "locate symbol named Foo",
            "locate symbol called Foo",
            "find symbols Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(
                text,
                "Foo",
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test fun multiTokenRemainderClarifies() = runBlocking {
        // #19 stage-2：single-identifier tool——任何殘餘仍帶空白一律
        // NEED_CLARIFICATION（絕不再靜默接合成 `SymbolTableFoo`，也絕不執行）。
        // 含 #19 B3 final polish 曾釘成 FAST（`Fooonbutton` 類）的三例。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "查 SymbolTable Foo 符號",
            "查 中文 變數 符號",
            "search symbol Foo on button",
            "search symbol Foo on Foo",
            "查 Foo 符號 on 按鈕 符號",
            "查 Foo 𠮷 符號",
            "查 Foo 符號 😀",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun extractionSeparatorBlowupPinsDenyZeroBackend() = runBlocking {
        // #19 B3 stage-8.2：超線性多項式（實測 ~O(n²)）分割枚舉修復之回歸釘——
        // `SYMBOL_CJK_KEYWORD_SUFFIX`／`BARE_SYMBOL_CUE_WRAP` 的平坦貪婪分隔符槽
        // 改為原子群組（同集合、僅原子性；成功路徑匹配逐字相同）。修前此輸入經
        // Step C 的 replaceFirst／兩處模糊守衛 containsMatchIn 觸發分割枚舉
        // （實測 n=400→~267 ms 且倍增，4096 字元上限下推定數十秒至數分鐘）；
        // 修後為逐位置掃描。無 wall-clock 斷言（不釘時鐘）；長度恰為
        // MAX_INTENT_CHARS＝4096（再多會撞輸入長度閘，到不了 extraction）。
        // 方向恆 fail-closed：step E 拒絕 U+FF0C（非 L/N、非 allow-list、cp>0x7E）
        // ⇒ extraction 失敗 ⇒ missingQuestionFor ⇒ DENIED＋NEED_CLARIFICATION；
        // 絕不執行（reasonCode 以實際決策為準：NEED_CLARIFICATION）。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        val text = "查Foo符號" + "，".repeat(4089) + "X"
        assertEquals(4096, text.length)
        for (t in listOf(text)) {
            // decide()：DENIED＋NEED_CLARIFICATION、toolCall == null。
            val d = decide(t)
            assertEquals(t, RouteKind.DENIED, d.route)
            assertEquals(t, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(t, d.toolCall)
            // decideAndDispatch：outcome 非 Executed、backend.invocations 不增。
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(t, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(t, RouteKind.DENIED, result.decision.route)
            assertEquals(t, DenyReason.NEED_CLARIFICATION, result.decision.reasonCode)
            assertNull(t, result.decision.toolCall)
            assertFalse(t, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(t, before, backend.invocations.get())
        }
        // 整個 loop 後端零呼叫（ZeroBackend）。
        assertEquals(0, backend.invocations.get())
    }

    @Test fun emptyRemainderClarifiesAndInvalidCharsetClarifies() {
        // #19 stage-2：空殘餘 / 帶空白殘餘 / 非 allow-list 字元（`!`）一律
        // NEED_CLARIFICATION——絕不改寫成另一個 query 執行。
        for (text in listOf(
            "查 - - 符號",
            "查 ! Foo 符號",
            "查 !Foo 符號",
            "查 Foo! 符號",
            "查 Foo - 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
        }
    }

    @Test fun letterNumberSplittersStillDeny() = runBlocking {
        // Ⅱ（U+2161，ROMAN NUMERAL TWO，Nl 字母數字）作為否定詞拆分器：
        // #19 B3 stage-1: `別Ⅱ查…` 非命令式句首 ⇒ 不進 symbol 模板 ⇒
        // no-match DENIED（reasonCode null）——仍 DENIED、絕不執行、後端零呼叫。
        // （否定閘僅作用於模板成立後——命令式句首帶否定顆粒者才會轉 NEED_CLARIFICATION。）
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        val text = "別Ⅱ查 Foo 符號"
        val d = decide(text)
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(text, d.reasonCode)
        assertNull(text, d.toolCall)
        skippedOf(dispatcher.dispatch(d, lspOn), text)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun negatedSymbolVerbsNeverExecuteForDispatch() = runBlocking {
        // 代表性否定符號意圖（含 別問/别問）走 decide → dispatch，並註冊 executor：
        // 全部 Skipped、後端零呼叫。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        // #19 B3 stage-1: symbol 否定前置句皆不滿足命令式准入（start-anchored
        // opener 不命中）⇒ 兩群組皆 no-match DENIED（reasonCode null）而非澄清。
        // （含否定顆粒的命令式句首，例 `查 Foo 符號 不要`，仍會命中模板後閘轉
        // NEED_CLARIFICATION——見 commandFormWithNegationClarifies。）
        val markerProbes = listOf(
            "不查 Foo 這個符號",
            "別搜 Foo 符號",
            "沒查 Foo 符號",
            "没查 Foo 符號",
        )
        val noMarkerProbes = listOf(
            "別問 Foo 符號",
            "别问 Foo 符號",
        )
        for (text in markerProbes) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            // #19 B3 stage-1: 非命令式句首 ⇒ no-match DENIED（reasonCode null）。
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        for (text in noMarkerProbes) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
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
            // #19 B3 stage-7.2（A2）：複合動詞整體消費後無有意義殘餘
            // （先前會以 `詢` 為 query 執行——identifier-less / verb-second-glyph 錯流）：
            "查詢符號",
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
        assertFalse("no executor registered — nothing was entered", skipped.attempted)
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
        assertFalse("not-ready tool fails at the projection gate — executor never entered", skipped.attempted)
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
            // #19 B3 stage-1: `不要查…` 非命令式句首 ⇒ 不進 symbol 模板 ⇒
            // no-match DENIED（reasonCode null、header toolName 不綁 lsp.symbols）；
            // 其餘 keyword 模板探針仍照常綁模板 + 否定閘轉 NEED_CLARIFICATION。
            if (tool == "lsp.symbols") {
                assertNull(text, d.reasonCode)
                assertNull(text, d.header.toolName)
            } else {
                assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
                assertEquals(text, tool, d.header.toolName)
            }
            assertNull(text, d.toolCall)
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

    @Test fun bareBieVerbSymbolQueriesNoMatch() {
        // #19 B3 stage-1: `別查…`/`别查…` 非命令式句首 ⇒ 不進 symbol 模板 ⇒
        // no-match DENIED（reasonCode null、header toolName 不綁 lsp.symbols）——
        // DENIED、toolCall null、後端零呼叫，絕不執行。
        // （舊行為：marker 命中模板後否定閘轉 NEED_CLARIFICATION——本 stage 之
        // affirmative-command 准入取代之，pinned per #19 B3 stage-1。）
        for (text in listOf("別查 Foo 這個符號", "别查 Foo 符號")) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            assertNull(text, d.header.toolName)
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
        // #19 B3 stage-1: symbol 否定前置探針皆非命令式句首（start-anchored opener
        // 不命中）⇒ 不進 symbol 模板 ⇒ no-match DENIED（reasonCode null）——
        // DENIED、toolCall null、dispatch Skipped、後端零呼叫，絕不執行。
        // isNegated() 僅作用於模板成立後——命令式句首帶否定顆粒者才會轉
        // NEED_CLARIFICATION（見 commandFormWithNegationClarifies）。
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
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            // #19 B3 stage-1: 非命令式句首 ⇒ no-match DENIED（reasonCode null）。
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        // English negations with no affirmative-command opener likewise no-match
        // at the template stage (reasonCode null), not clarification — still
        // DENIED, never FAST. (`never find symbol Foo` — stage-1: non-command
        // opener, also no-match — covered by englishNegationNeverExecutes.)
        for (text in listOf(
            "not symbols",
            "NOT SYMBOLS",
            "no symbols",
            "ｎｅｖｅｒ symbols",     // fullwidth never + ASCII symbols: hint matches, no affirmative command opener (stage-1)
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun visibleSplittersStillDeny() = runBlocking {
        // ASCII-alphanumeric / symbol in-run splitters (visible chars, not
        // separators): NFKC + the removed pass strip them, rejoining the
        // negation hint — DENIED + no-match（stage-1，非命令式句首），never FAST.
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
            // #19 B3 stage-1: 非命令式句首 ⇒ no-match DENIED（reasonCode null）。
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun modifierAndDigitSplittersStillDeny() = runBlocking {
        // Lm modifier letters（ˇ U+02C7／ˉ U+02C9／ـ U+0640／々 U+3005）與非 ASCII
        // digit（¹ U+00B9 No；U+09E8 Bengali digit two Nd——稽核所指碼位；U+0662
        // Arabic-Indic digit two Nd——稽核逐字字元）in-run 拆開否定詞：
        // #19 B3 stage-1: 皆非命令式句首 ⇒ 不進 symbol 模板 ⇒ no-match DENIED
        // （reasonCode null）+ backend 零呼叫。
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
            // #19 B3 stage-1: 非命令式句首 ⇒ no-match DENIED（reasonCode null）。
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun mixedScriptNegationNeverExecutes() = runBlocking {
        // 繁簡交叉混排否定詞（traditional prefix × simplified verb and vice
        // versa）：一律 DENIED + 絕不執行 + backend 0。#19 B3 stage-1: 全部探針
        // 皆非命令式句首（start-anchored opener 不命中）⇒ no-match DENIED
        // （reasonCode null）——皆為 DENIED、絕不執行。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        // （舊 A2 分流：marker 有則 NEED_CLARIFICATION——本 stage 之命令式准入取代之。）
        val markerProbes = listOf(
            "别尋 Foo 符號",
            "别寻 Foo 符號",
            "別寻 Foo 符號",
            "别询 Foo 符號",
            "別询 Foo 符號",
        )
        val noMarkerProbes = listOf(
            "別问 Foo 符號",
            "別设 Foo 符號",
            "別调 Foo 符號",
            "没導 Foo 符號",
            "沒发 Foo 符號",
            "别寫 Foo 符號",
            "沒写 Foo 符號",
        )
        for (text in markerProbes) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            // #19 B3 stage-1: 非命令式句首 ⇒ no-match DENIED（reasonCode null）。
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        for (text in noMarkerProbes) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
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
        // `符號是Foo嗎` → no-match DENIED（A2 起：無 lookup marker，不進模板）。
        val residual = decide("符號是Foo嗎")
        assertEquals(RouteKind.DENIED, residual.route)
        assertNull("symbol template must not match without a lookup marker: ${residual.toolCall}", residual.toolCall)
        assertNull("no-match denial carries no reasonCode: ${residual.reasonCode}", residual.reasonCode)
        // `意思是符號` → no-match DENIED（A2 起：無 lookup marker，不進模板）。
        val meta = decide("意思是符號")
        assertEquals(RouteKind.DENIED, meta.route)
        assertNull(meta.toolCall)
        assertNull("no-match denial carries no reasonCode: ${meta.reasonCode}", meta.reasonCode)
        // `查 LSPClient 符號` → FAST with query=LSPClient（positional extraction：
        // `LSPClient` 無 hint 詞邊界，完整保留；`\blsp\b` 剝離已不存在）。
        val anchored = decide("查 LSPClient 符號")
        assertEquals(RouteKind.FAST, anchored.route)
        assertTrue(
            "LSPClient must survive positional extraction: ${anchored.toolCall!!.argumentsJson}",
            anchored.toolCall!!.argumentsJson.contains("\"query\": \"LSPClient\""),
        )
        // `查 a b 符號` 剝除 cue 位置的 leading connective `a `（後方仍有 token），
        // 殘餘 `b`——與 `a Foo` → `Foo` 相同的 article-stripping 機制；兩個 token
        // 在此位置不可區分，改寫是確定的（已記錄 deterministic cue-strip residual）。
        val cueStrip = decide("查 a b 符號")
        assertEquals(RouteKind.FAST, cueStrip.route)
        assertEquals(
            "b",
            Json.parseToJsonElement(cueStrip.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
        )
        // `査 中文變數名 符號`（U+67FB 異體初字）→ no-match DENIED——査 不是 opener
        // （deviation (a)：異體碼點未涵蓋），pin 住以防回歸。
        val variantInitial = decide("査 中文變數名 符號")
        assertEquals(RouteKind.DENIED, variantInitial.route)
        assertNull(variantInitial.reasonCode)
        assertNull(variantInitial.toolCall)
    }

    @Test fun englishNegationNeverExecutes() = runBlocking {
        // English negation verbs (best-effort): each of these misses the
        // symbol template (no affirmative-command opener) into the ordinary
        // no-match denial — all are safe DENIED outcomes.
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
        // Sanity: `never find symbol Foo` misses the symbol template entirely
        // under the affirmative-command model (non-command opener) — the
        // dispatcher skips without invoking the backend.
        // #19 B3 stage-1: no-match DENIED（reasonCode null），不再進否定閘。
        val d = decide("never find symbol Foo")
        assertNull(d.reasonCode)
        assertNull(d.toolCall)
        val skipped = skippedOf(dispatcher.dispatch(d, lspOn), "never find symbol Foo")
        assertNull(skipped.reasonCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun englishDenyVerbDeniesAndClarifies() = runBlocking {
        // #3 B3 re-review: `den[iy]\w*` covers every deny/denies/denied/denying inflection
        // — `查 deny 符號 Foo` must hit the negation gate and clarify.
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "查 deny 符號 Foo",
            "查 denies 符號 Foo",
            "查 denied 符號 Foo",
            "查 denying 符號 Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun metaQuestionVariantsAreDenied() = runBlocking {
        // A2: utterances without a lookup marker no-match at the template
        // stage (reasonCode null, not clarification); utterances with a
        // marker but no meaningful remainder clarify (NEED_CLARIFICATION).
        // Both DENIED, never execute.
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "符號的意思",
            "什麼是符號",
            "為什麼要用符號",
            // #19 B3 stage-7.2（A2）：`查詢的` 是陳述句殘餘——無 symbol hint（符號/符号/
            // symbols? 皆缺）⇒ symbol 前閘不成立、RULES 無命中 ⇒ no-match DENIED
            // （reasonCode null，**不是** NEED_CLARIFICATION）——DENIED + toolCall null、
            // 絕不執行；此為 check-outcome 後如實釘錄（見 PR #171 stage-7.2）。
            "查詢的",
            // #19 B3 stage-7.4（A4 收尾）：command-form opener 分隔類不含前置標點 `,` ⇒
            // `symbols,Foo` 不符正向命令形（^symbols?\\s+\\S 不匹配）、RULES 無命中 ⇒
            // no-match DENIED（reasonCode null），絕不執行（見 positionalCueGrammarPins
            // 記錄的吸收類 pin 與提取 KDoc 的 stage-7.4 收尾 entry）：
            "symbols,Foo",
            "symbols，Foo",
            // #19 B3 stage-7.2 收尾：`質詢Foo符號`——首字 `質`∉ opener ⇒ no-match DENIED
            // （reasonCode null，§A2 記錄殘餘對應的「不收複合」邊界；見本 KDoc 第 III 點）
            "質詢Foo符號",
        )) {
            // A2: 無 lookup marker ⇒ 模板不成立 ⇒ no-match DENIED（reasonCode null），
            // 不是澄清。
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
        }
        for (text in listOf(
            // stage-6.3：CJK 填充詞（是/要/用…）不再全域剝離——「查 是 符號」
            // 殘餘 `是` 為合法單一 L* token ⇒ FAST query=`是`（不是澄清）；
            // 見 positionalCueGrammarPins。「查 要 用 符號」殘餘 `要 用` 含空白 ⇒ 澄清。
            "查 要 用 符號",
            // #19 B3 stage-8.3（Re-review #8 A2）：`查 Symbol 符號`／`查 symbols 符號`
            // 曾翻轉為 FAST `符號`（EN command-form 劃定 verbatim 殘餘）；
            // #19 B3 stage-9.2（Re-review #9 A2, [P2]）兩階段 CJK-preferred-fallback 後——
            // CJK-leading 語句先讀 CJK 語法，尾綴 cue 剝去後 `Symbol`/`symbols` 為合法
            // verbatim 識別字 ⇒ FAST `Symbol`／FAST `symbols`／`find`/`lookup`/`locate`
            // （五支 stage-8.3 pin 再度翻轉——三支規格明列、lookup/locate 為同族同根機制，
            // 見 enCueBudgetAndCjkPreferredExtractionPinned；皆為 READ-only 有界 token）。
            // 不再在此釘為澄清——亦不再同樣釘於 enCommandDelimitedIdentifiersAreVerbatim。
            // astral-only remainder：hasSymbolQuery 以 BMP char 錨定，𠮷 的兩個
            // surrogate units 皆非 letter/digit ⇒ 澄清（fail-safe，已記錄；
            // hasSymbolQuery 保持 BMP-based，不改動該 predicate）。
            "查 𠮷 符號",
        )) {
            // marker（查）命中 ⇒ 模板成立；剝離後無有意義殘餘 ⇒ 澄清，絕不執行。
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
        }
        // Dispatch sanity for at least one case: clarification never reaches the backend.
        skippedOf(dispatcher.dispatch(decide("符號的意思"), lspOn), "符號的意思")
        skippedOf(dispatcher.dispatch(decide("查詢的"), lspOn), "查詢的")
        assertEquals(0, backend.invocations.get())
    }

    @Test fun specialFindSymbolIsNoMatchDenied() {
        // #19 B3 stage-1: 「特別找」非命令式句首（ starts with 特，非 lookup 動詞）
        // ⇒ 不進 symbol 模板 ⇒ no-match DENIED（reasonCode null）——
        // DENIED、toolCall null、絕不執行。
        // （舊行為：`特別找` ⊃ `別找` 過度澄清成 NEED_CLARIFICATION——本 stage
        // 之 affirmative-command 准入取代之，pinned per #19 B3 stage-1。）
        val d = decide("特別找 Foo 符號")
        assertEquals(RouteKind.DENIED, d.route)
        assertNull(d.reasonCode)
        assertNull(d.toolCall)
    }

    @Test fun unicodeSurrogateQueriesPinned() {
        // #19 stage-2（A3/A4）：whitespace-bearing remainders now clarify
        // （single-identifier tool）⇒ `查 Foo 𠮷 符號` 不再 FAST。Paired astral
        // letter embedded in a BMP-letter token is a single token and passes
        // esc unchanged — the pair is NOT split. (U+1D11E 𝄞 is category So,
        // not a letter, so it cannot anchor one either.)
        val paired = decide("查 Foo𠮷Bar 符號")
        assertEquals(RouteKind.FAST, paired.route)
        assertTrue("paired surrogate must pass through esc unchanged", paired.toolCall!!.argumentsJson.contains("𠮷"))

        // Unpaired surrogates can never route FAST any more: the charset
        // allow-list rejects them (clarification, not escaped output).
        val lone = decide("查 Foo\uD800Bar 符號")
        assertEquals(RouteKind.DENIED, lone.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, lone.reasonCode)
        assertNull(lone.toolCall)

        val loneLow = decide("查 Foo\uDC00Bar 符號")
        assertEquals(RouteKind.DENIED, loneLow.route)
        assertEquals(DenyReason.NEED_CLARIFICATION, loneLow.reasonCode)
        assertNull(loneLow.toolCall)

        // A well-formed pair at the very END of the query token is NOT split:
        // esc passes both units through unchanged.
        val endPair = decide("查 Foo𠮷 符號")
        assertEquals(RouteKind.FAST, endPair.route)
        val jsonEnd = endPair.toolCall!!.argumentsJson
        assertTrue("paired surrogate at end must pass through esc unchanged: $jsonEnd", jsonEnd.contains("𠮷"))
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

        // dispatch 端：ready-copy 也必須在 gate 4（NATIVE-only）fail-closed。
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
        // gate 6（READ-only）：WRITE ready-copy 也絕不執行。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let { if (name == "alarm.create") it.copy(executionReady = true) else it }
        }
        val fake = CountingFake("alarm.create")
        val synthetic = fastDecision("alarm.create", "{\"hour\": 7, \"minute\": 30}", "call-write")
        val out = FastDispatcher(mapOf("alarm.create" to fake), lookup = readyCopy).dispatch(synthetic, lspOn)
        val skipped = skippedOf(out, "write tool")
        assertEquals(DenyReason.USER_DISABLED, skipped.reasonCode)
        assertFalse("WRITE-ready copy fails at the READ-only gate — executor never entered", skipped.attempted)
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
        assertTrue("executor was entered before its forged callId was rejected", skipped2.attempted)
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

    @Test fun lookupIdentityMismatchFailsClosed() = runBlocking {
        // 注入的 lookup 不回音要求：call 要的是 alarm.create（WRITE、未 ready），
        // lookup 卻回傳 lsp.symbols（ready + READ + NATIVE）——gate 3 身分比對
        // 在任何投影／執行之前即 fail-closed；執行器與 backend 零觸碰；
        // 話術為不回音的固定字串，不含任何一方的工具名。
        val backend = CountingBackend()
        val lspLookup: (String) -> ToolDef? = { ToolRegistry.find(LspTools.SYMBOLS_NAME) }
        val fake = CountingFake("alarm.create")
        val synthetic = fastDecision("alarm.create", "{\"hour\": 7, \"minute\": 30}", "call-mismatch")
        val out = FastDispatcher(mapOf("alarm.create" to fake), lookup = lspLookup).dispatch(synthetic, lspOn)
        val skipped = skippedOf(out, "lookup identity mismatch")
        assertEquals("LOOKUP_IDENTITY_MISMATCH", skipped.denyCode)
        assertFalse("lookup identity mismatch fails before any executor is entered", skipped.attempted)
        assertNull(skipped.reasonCode)
        assertEquals(0, fake.invocations.get())
        assertEquals("backend must never be invoked", 0, backend.invocations.get())
        assertEquals("執行工具失敗，請重試或手動操作", skipped.fallbackMessage)
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
        assertFalse("zero budget fails before the executor is entered", skipped.attempted)
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
        assertTrue("self-thrown timeout comes from the entered executor", skipped.attempted)
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
        assertTrue("oversized result comes from the entered executor", skipped.attempted)
    }

    @Test fun executedStructurallyInvalidResultIsRejected() = runBlocking {
        // gate 11 結構檢查：resultJson 非 JSON／root 非 JsonObject 一律映射至
        // 與超預算相同的 backstop（Skipped EXEC_ERROR，attempted=true）；
        // backend／executor 話術永不騎 outcome。
        val marker = "backend-marker-MUST-NOT-RIDE-結構案例"
        val d = decide("幫我查 Foo 這個符號")
        val call = d.toolCall!!
        for ((label, resultJson) in listOf(
            "unparseable" to "not json $marker",
            "json array" to """[1,2,"$marker"]""",
            "json primitive" to "\"backend-marker-MUST-NOT-RIDE-結構案例\"",
        )) {
            val bad = executorReturning(FastExecResult(LspTools.SYMBOLS_NAME, call.callId, resultJson))
            val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to bad)).dispatch(d, lspOn)
            val skipped = skippedOf(out, "$label result")
            assertEquals("$label denyCode", "EXEC_ERROR", skipped.denyCode)
            assertTrue("$label must carry attempted=true", skipped.attempted)
            assertFalse("$label must not echo backend text", (skipped.fallbackMessage ?: "").contains(marker))
            assertFalse("$label outcome must not carry backend text", skipped.toString().contains(marker))
        }
        // 合法 JsonObject 仍放行（Executed 不受結構檢查誤傷）。
        val good = executorReturning(FastExecResult(LspTools.SYMBOLS_NAME, call.callId, """{"ok":true}"""))
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to good)).dispatch(d, lspOn)
        assertTrue("valid JsonObject must execute (got $out)", out is FastDispatchOutcome.Executed)
    }

    @Test fun executedCallIdCarriesBoundedFormNotRaw() = runBlocking {
        // Executed outcome 攜帶有界 callId（redact → ≤500 pass → cap+#sha256），
        // 與 receipt channel 一致；raw（敏感形）callId 永不騎 Executed outcome。
        // gate 9 的 identity 比對在 outcome 之前以 raw 對 raw 完成，故 executor
        // 回傳 raw callId 仍能正常 Executed——只是 outcome 的 callId 已有界。
        val d = decide("幫我查 Foo 這個符號")
        val call = d.toolCall!!
        // (a) secret-shaped、≤500：redacted（含 marker）後與原值不同。
        val secretId = "fast-call-sk-SECRETMARKER1234567890abcd"
        val secretCall = call.copy(callId = secretId)
        val secretDecision = d.copy(toolCall = secretCall)
        val execA = executorReturning(FastExecResult(LspTools.SYMBOLS_NAME, secretId, """{"ok":true}"""))
        val outA = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to execA)).dispatch(secretDecision, lspOn)
        val executedA = outA as? FastDispatchOutcome.Executed
        assertNotNull("sensitive callId must still execute with the same raw id at gate 9 (got $outA)", executedA)
        assertEquals(
            "outcome callId must equal the bounded form the receipt channel would emit",
            dev.librepocket.redact.Redactor.redact(secretId).text,
            executedA!!.result.callId,
        )
        assertFalse(
            "raw secret-shaped callId must never ride the outcome",
            executedA.result.callId.contains("sk-SECRETMARKER1234567890abcd"),
        )
        // (b) 超長（redacted 後仍 >500）：cap + # + 32-hex suffix。
        // 單一 sk- run 會被整段 redact 成短 marker，故用空白切斷 secret run、
        // 尾綴 600 個無害字元確保 post-redaction 長度 >500 觸發 cap 路徑。
        val longId = "fast-sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 " + "x".repeat(600)
        val longCall = call.copy(callId = longId)
        val longDecision = d.copy(toolCall = longCall)
        val execB = executorReturning(FastExecResult(LspTools.SYMBOLS_NAME, longId, """{"ok":true}"""))
        val outB = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to execB)).dispatch(longDecision, lspOn)
        val executedB = outB as? FastDispatchOutcome.Executed
        assertNotNull("over-long callId must still execute (got $outB)", executedB)
        val boundedB = executedB!!.result.callId
        assertTrue("bounded callId must be ≤500 chars: ${boundedB.length}", boundedB.length <= 500)
        assertTrue(
            "bounded callId must end with # + 32 lowercase hex: $boundedB",
            Regex("#[0-9a-f]{32}$").containsMatchIn(boundedB),
        )
        assertFalse(
            "raw secret material must never ride the outcome",
            boundedB.contains("sk-ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
        )
    }

    @Test fun executedOutcomeCarriesNoUnboundedDenyCode() = runBlocking {
        // Executed outcome 永不攜帶 executor 提供的 denyCode：outcome 的
        // denyCode 一律為 null（有界 callId 有界、denyCode 無界一併禁止出站）。
        // 偽造的超長/含換行 denyCode 不得騎 outcome 的任何欄位或 toString。
        val d = decide("幫我查 Foo 這個符號")
        val call = d.toolCall!!
        val forged = "fake-forged\n" + "A".repeat(200_000)
        val exec = executorReturning(
            FastExecResult(
                LspTools.SYMBOLS_NAME,
                call.callId,
                """{"ok": true}""",
                denied = false,
                denyCode = forged,
            ),
        )
        val out = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to exec)).dispatch(d, lspOn)
        val executed = out as? FastDispatchOutcome.Executed
        assertNotNull("forged denyCode must not block a valid Ok execution (got $out)", executed)
        assertNull("Executed outcome must carry NO executor denyCode", executed!!.result.denyCode)
        assertEquals("non-denied Ok must report denied=false", false, executed.result.denied)
        assertFalse(
            "forged denyCode marker must never ride the outcome",
            executed.toString().contains("fake-forged"),
        )
        assertFalse(
            "forged denyCode bulk must never ride the outcome",
            executed.toString().contains("A".repeat(1_000)),
        )
        assertTrue(
            "every string field of the outcome must stay length-bounded: callId=${executed.result.callId.length}, resultJson=${executed.result.resultJson.length}",
            executed.result.callId.length <= SafeToolSlice.MAX_STRING_CHARS &&
                executed.result.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS,
        )
    }

    @Test fun okResultInternalBoundaryMapsIteratorThrowToExecError() = runBlocking {
        // 縱深防禦：後端 symbols List 在 okResult projection（take/size/get）
        // 時爆炸 → okResult 內部邊界映射為 typed denial（EXEC_ERROR），
        // 不外拋、不退化、不觸發 call-site catch 才有的路徑；backend 話術永不放行。
        val backend = ThrowingIterationBackend()
        val d = decide("幫我查 Foo 這個符號")
        val out = lspDispatcher(backend).dispatch(d, lspOn)
        val skipped = skippedOf(out, "iterator-throwing backend")
        assertEquals("EXEC_ERROR", skipped.denyCode)
        assertTrue("executor was entered before the backend list blew up", skipped.attempted)
        assertFalse(
            "backend text must never ride the outcome",
            (skipped.fallbackMessage ?: "").contains("backend-iteration-marker"),
        )
        assertFalse(skipped.toString().contains("backend-iteration-marker"))
        assertFalse(skipped.toString().contains("backend-get-marker"))
        assertEquals(1, backend.invocations.get())
    }

    @Test fun fastDecisionWithNullToolCallFailsClosedWithFixedMessage() = runBlocking {
        // 手建 FAST + null toolCall 的 decision：fail-closed 為 Skipped，
        // fallbackMessage 非 null（固定不回音話術），attempted=false；
        // decision 自帶的 message 永不騎 outcome。
        val decision = RouteDecision(
            route = RouteKind.FAST,
            toolCall = null,
            reasonCode = null,
            fallbackMessage = "handbuilt-marker-should-not-ride",
            header = TranscriptHeader(
                sessionId = "s1",
                turnIndex = 1,
                route = RouteKind.FAST,
                toolName = null,
                reasonCode = null,
                visibleTools = emptyList(),
            ),
        )
        val out = FastDispatcher().dispatch(decision, lspOn)
        val skipped = skippedOf(out, "FAST with null toolCall")
        assertNotNull("must carry a fixed non-echoing message", skipped.fallbackMessage)
        assertTrue(
            "message must be the fixed no-match receipt, not anything else",
            skipped.fallbackMessage!!.contains("NO_MATCH"),
        )
        assertFalse(
            "the hand-built decision's own message must never ride the outcome",
            skipped.fallbackMessage!!.contains("handbuilt-marker-should-not-ride"),
        )
        assertFalse("nothing was executed", skipped.attempted)
        assertNull(skipped.denyCode)
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
        assertFalse("unknown tool fails before any executor is entered", skipped.attempted)
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
        assertTrue("executor was entered before it threw", skipped.attempted)
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

    @Test fun naturalNegationNeverReachesExecutor() = runBlocking {
        // A1 保守任意否定閘：自然否定句（任意助詞隔開之 不/沒/別/… 或英文否定詞）
        // 一律 DENIED、toolCall null、dispatch Skipped、後端零呼叫。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        // 群組一（命令式句首）：含否定顆粒之命令式句首 ⇒ 模板成立後
        // 否定閘轉 NEED_CLARIFICATION；絕不 FAST、絕不進 executor。
        // #3（B3 re-review）明確禁止/順延句族（natural-language 等價於否定）
        // 皆以命令式句首 + 否定顆粒成立（command form 或句首 lookup 動詞）。
        val commandLedProbes = listOf(
            "symbols Foo banana",
            "查 prohibit 符號 Foo",
            "查 defer 符號 Foo",
            "查 延期 符號 Foo",
            "查 推後 符號 Foo",
            "查 寬限 符號 Foo",
            "查 延宕 符號 Foo",
        )
        // #19 B3 stage-1: 非命令式句首之否定句——不滿足 symbol 前閘的命令式准入
        // （start-anchored opener 不命中）⇒ 落入 RULES 無命中 ⇒ no-match DENIED
        // （reasonCode null）；仍 DENIED、toolCall null、Skipped、後端零呼叫。
        // 涵蓋 無/无（無法＝unable）與 免（免查＝refrain/avoid）；
        // 未（尚未／未曾＝not yet）、毋（毋查＝do not）、休（休查＝desist）同組。
        val nonCommandLedProbes = listOf(
            "我不想查 Foo 符號",
            "先別再查 Foo 符號",
            "我沒有要查 Foo 符號",
            "請別去找 Foo 符號",
            "不願意查 Foo 符號",
            "我不要查 Foo 符號",
            "別再幫我查 Foo 符號",
            "沒有要找 Foo 符號",
            "别想查 Foo 符号",
            "不 想 查 Foo 符號",
            "先别再找 Foo 符号",
            "没办法查 Foo 符號",
            "無法查 Foo 符號",
            "无法查 Foo 符号",
            "免查 Foo 符號",
            "未查 Foo 符號",
            "尚未查 Foo 符號",
            "未曾查 Foo 符號",
            "毋查 Foo 符號",
            "休查 Foo 符號",
            "我 沒 辦 法 查 Foo 符號",
            "我拒絕查 Foo 符號",
            "拒绝查 Foo 符号",
            "我拒绝查 Foo 符号",
            "先暫緩查 Foo 符號",
            "暫緩查 Foo 符號",
            "延緩查 Foo 符號",
            "延遲查 Foo 符號",
            "延後查 Foo 符號",
            "推遲查 Foo 符號",
            "I forbid you to search symbol Foo",
            "suspend lookup of symbol Foo",
        )
        for (text in commandLedProbes) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        for (text in nonCommandLedProbes) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            // #19 B3 stage-1: 非命令式句首 ⇒ no-match DENIED（reasonCode null）。
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        // 群組二：EN-mix `我不想 symbol Foo` 非命令式句首 ⇒ 模板不成立
        // ⇒ no-match DENIED（reasonCode null，不進否定閘）。
        // #3（B3 re-review）：`searching`/`finding`/`hold off` 皆不匹配
        // word-anchored marker `\b(find|search|lookup|locate)\b` ⇒ 同樣無 marker，
        // 直接 no-match DENIED——DENIED、絕不執行、後端零呼叫。
        for (text in listOf(
            "我不想 symbol Foo",
            "I forbid searching for symbol Foo",
            "hold off on symbols",
            "postpone finding symbols",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun commandFormWithNegationClarifies() = runBlocking {
        // 命令式模板命中（command form `^symbols?\s+\S` 或 lookup marker `查`）＋否定詞
        // ⇒ 模板成立後否定閘轉 NEED_CLARIFICATION，絕不 FAST、絕不進 executor。
        // 釘住否定閘不是「只有自然語句」的濾網：命令式模板命中同樣不得繞過否定閘。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "symbols Foo 不要",
            "查 Foo 符號 不要",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun nonLookupSymbolUtterancesAreDenied() = runBlocking {
        // A2: 質問/知識性語句與 bare `symbols` 無 lookup intent ⇒ 模板不成立
        // ⇒ no-match DENIED（reasonCode null），絕不 clarify、絕不執行。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "What is a symbol?",
            "Explain symbols",
            "請解釋符號",
            "符號有哪些種類",
            "symbols",
            "符號是Foo嗎",
            "how do symbols work",
            // A2 全形 pin：fullwidth ｓｅａｒｃｈ 不匹配 EN lookup marker（raw 文本檢測，
            // 非 NFKC 折疊後）⇒ 無 lookup intent ⇒ no-match DENIED（reasonCode null），絕不 clarify。
            "ｓｅａｒｃｈ Foo 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun interrogativeSymbolUtterancesDenyZeroBackend() = runBlocking {
        // #4（B3 re-review）：問句/知識/教學（how-to）語句即使帶 lookup verb
        // 也絕不得進 symbol 模板 ⇒ no-match DENIED（reasonCode null）、
        // toolCall null、dispatch Skipped、後端零呼叫——問句不是查詢命令。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "Why does search symbol Foo work?",
            "How to find symbol Foo?",
            "How to find symbol Foo",
            "Explain how to search symbols",
            "Explain how to search symbols Foo",
            "教我如何查 Foo 符號",
            "請教我如何查 Foo 符號",
            "請問怎麼查 Foo 符號",
            "如何查 Foo 符號",
            "怎麼查 Foo 符號",
            "為什麼查 Foo 符號",
            // 以疑問尾標 嗎 收尾 ⇒ 問句，即使含 marker 亦不執行。
            "查 Foo 符號嗎？",
            // #4（B3 re-review）追加：show-me/describe 族 + 中文教學起始語族
            "Show me how to find symbol Foo",
            "Describe how to search symbols",
            "Demonstrate lookup of symbol Foo",
            "Walk me through finding symbols",
            "告訴我如何查 Foo 符號",
            "請告訴我如何查 Foo 符號",
            "我想知道如何查 Foo 符號",
            "講解如何查 Foo 符號",
            "說明如何查 Foo 符號",
            "介紹如何查 Foo 符號",
            "示範如何查 Foo 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.reasonCode)
            assertNull(text, d.toolCall)
            skippedOf(dispatcher.dispatch(d, lspOn), text)
        }
        assertEquals(0, backend.invocations.get())
        // Positive guard：基本查詢路徑不受豁免影響——`查 Foo 符號` 仍 FAST。
        val control = decide("查 Foo 符號")
        assertEquals(RouteKind.FAST, control.route)
        assertEquals("lsp.symbols", control.toolCall?.toolName)
    }

    @Test fun cjkMixedMarkerSymbolLookupRoutesFast() {
        // A2 正向 pin：CJK marker（查）+ symbols 字 ⇒ 模板成立 ⇒ FAST；
        // query 為提示詞剝離後的殘餘（一下/symbols 皆被剝離）。
        val d = decide("查一下 symbols Foo")
        assertEquals(RouteKind.FAST, d.route)
        assertTrue(
            "query must be Foo: ${d.toolCall!!.argumentsJson}",
            d.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""),
        )
        // Plain canonical form pins the same path end to end: `查 Foo 符號`.
        val plain = decide("查 Foo 符號")
        assertEquals(RouteKind.FAST, plain.route)
        assertTrue(
            "query must be Foo: ${plain.toolCall!!.argumentsJson}",
            plain.toolCall!!.argumentsJson.contains("\"query\": \"Foo\""),
        )
    }

    // A3 回歸：okResult 必須以「請求的 limit」截斷（MAX_LIMIT 僅絕對上限）。
    private class ListBackend(private val count: Int) : LspTools.Backend {
        val invocations = AtomicInteger(0)
        var lastLimit: Int = -1
            private set
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            lastLimit = limit
            return (1..count).map { i ->
                LspTools.Symbol("sym$i", "function", "app/src/main/F$i.kt", i)
            }
        }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    @Test fun requestedLimitIsEnforced() = runBlocking {
        // 以 Json 解析 receipt 取 counts（與檔案內 Json.parseToJsonElement 風格一致）。
        fun counts(json: String): Triple<Int, Int, Boolean> {
            val o = Json.parseToJsonElement(json).jsonObject
            return Triple(
                o["returnedCount"]!!.jsonPrimitive.content.toInt(),
                o["omittedCount"]!!.jsonPrimitive.content.toInt(),
                o["truncated"]!!.jsonPrimitive.content == "true",
            )
        }

        // limit=1、後端回 3 個 → returnedCount=1、omittedCount=2、truncated=true；後端收到 limit==1。
        val b1 = ListBackend(3)
        val r1 = LspSymbolsExecutor(b1)
            .execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query":"Foo","limit":1}""", "c-lim1"), lspOn)
        assertFalse(r1.denied)
        assertEquals(Triple(1, 2, true), counts(r1.resultJson))
        assertEquals(1, b1.lastLimit)
        assertEquals(1, b1.invocations.get())

        // limit=2、後端回 5 個 → returnedCount=2、omittedCount=3、truncated=true。
        val b2 = ListBackend(5)
        val r2 = LspSymbolsExecutor(b2)
            .execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query":"Foo","limit":2}""", "c-lim2"), lspOn)
        assertFalse(r2.denied)
        assertEquals(Triple(2, 3, true), counts(r2.resultJson))
        assertEquals(2, b2.lastLimit)

        // 無 limit（預設 DEFAULT_LIMIT=50）、後端回 60 個 → returnedCount=50、omittedCount=10、truncated=true。
        val b3 = ListBackend(60)
        val r3 = LspSymbolsExecutor(b3)
            .execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query":"Foo"}""", "c-lim3"), lspOn)
        assertFalse(r3.denied)
        assertEquals(Triple(50, 10, true), counts(r3.resultJson))
        assertEquals(LspTools.DEFAULT_LIMIT, b3.lastLimit)

        // 無 limit、後端回 10 個 → returnedCount=10、omittedCount=0、truncated=false；後端收到 DEFAULT_LIMIT(50)。
        val b4 = ListBackend(10)
        val r4 = LspSymbolsExecutor(b4)
            .execute(ToolCall(LspTools.SYMBOLS_NAME, """{"query":"Foo"}""", "c-lim4"), lspOn)
        assertFalse(r4.denied)
        assertEquals(Triple(10, 0, false), counts(r4.resultJson))
        assertEquals(LspTools.DEFAULT_LIMIT, b4.lastLimit)
    }

    @Test fun skippedAttemptedReflectsExecutionBoundary() = runBlocking {
        // A4 回歸：attempted=false ⇔ dispatcher 於進入 executor.execute 前即 fail-closed；
        // attempted=true ⇔ executor 已被進入（逾時中途／例外／denied／post-execute 檢查）。
        val d = decide("幫我查 Foo 這個符號")
        assertEquals(RouteKind.FAST, d.route)

        // (1) blank-query schema-invalid：executor 進入但後端零呼叫 → attempted=true。
        val schemaBackend = CountingBackend()
        val schemaDecision = fastDecision(LspTools.SYMBOLS_NAME, """{"query":"   "}""", "call-att-schema")
        val outSchema = lspDispatcher(schemaBackend).dispatch(schemaDecision, lspOn)
        val skippedSchema = skippedOf(outSchema, "schema-invalid blank query")
        assertTrue(skippedSchema.attempted)
        assertEquals("SCHEMA_INVALID", skippedSchema.denyCode)
        assertEquals(0, schemaBackend.invocations.get())

        // (2) backend 拋 RuntimeException：executor 進入、backend 一次 → attempted=true、EXEC_ERROR。
        val boom = object : LspTools.Backend {
            val invocations = AtomicInteger(0)
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
                invocations.incrementAndGet()
                throw RuntimeException("boom-secret-marker")
            }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val outBoom = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to LspSymbolsExecutor(boom))).dispatch(d, lspOn)
        val skippedBoom = skippedOf(outBoom, "backend throws")
        assertTrue(skippedBoom.attempted)
        assertEquals("EXEC_ERROR", skippedBoom.denyCode)
        assertEquals(1, boom.invocations.get())
        assertFalse(skippedBoom.fallbackMessage!!.contains("boom-secret-marker"))

        // (3) fake executor 跑 10s、ready-copy timeoutMs=50 → TIMEOUT、attempted=true。
        val readyCopy: (String) -> ToolDef? = { name ->
            ToolRegistry.find(name)?.let {
                if (name == LspTools.SYMBOLS_NAME) it.copy(annotations = it.annotations.copy(timeoutMs = 50)) else it
            }
        }
        val slow = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult {
                delay(10_000)
                return FastExecResult(toolName, call.callId, """{"ok":true}""")
            }
        }
        val outSlow = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to slow), lookup = readyCopy).dispatch(d, lspOn)
        val skippedSlow = skippedOf(outSlow, "timeout")
        assertTrue(skippedSlow.attempted)
        assertEquals("TIMEOUT", skippedSlow.denyCode)

        // (4) executorToolNameMismatch → attempted=false（executor 零呼叫）。
        val mismatch = CountingFake("phone.dial")
        val outMismatch = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to mismatch)).dispatch(d, lspOn)
        val skippedMismatch = skippedOf(outMismatch, "executor identity mismatch")
        assertFalse(skippedMismatch.attempted)
        assertEquals("EXECUTOR_IDENTITY_MISMATCH", skippedMismatch.denyCode)
        assertEquals(0, mismatch.invocations.get())

        // (5) lookup 拋出 → attempted=false。
        val outLookup = FastDispatcher(lookup = { throw IllegalStateException("lookup-boom") }).dispatch(d, lspOn)
        val skippedLookup = skippedOf(outLookup, "lookup throwing")
        assertFalse(skippedLookup.attempted)
        assertEquals("LOOKUP_ERROR", skippedLookup.denyCode)

        // (6) decide 後、dispatch 前切換 switch off → attempted=false（executor 從未被觸碰）。
        val flipBackend = CountingBackend()
        val dFlip = decide("幫我查 Foo 這個符號")
        val outFlip = lspDispatcher(flipBackend).dispatch(dFlip, lspOn.copy(userSwitches = mapOf("lsp" to false)))
        val skippedFlip = skippedOf(outFlip, "switch flipped off")
        assertFalse(skippedFlip.attempted)
        assertEquals(0, flipBackend.invocations.get())

        // (7) executor 回 denied → attempted=true。
        val denied = executorReturning(
            FastExecResult(LspTools.SYMBOLS_NAME, d.toolCall!!.callId, """{"denied":true}""", denied = true, denyCode = "SCHEMA_INVALID"),
        )
        val outDenied = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to denied)).dispatch(d, lspOn)
        val skippedDenied = skippedOf(outDenied, "executor denied")
        assertTrue(skippedDenied.attempted)
        assertEquals("SCHEMA_INVALID", skippedDenied.denyCode)

        // (8) result toolName mismatch → attempted=true。
        val wrongTool = object : FastToolExecutor {
            override val toolName: String = LspTools.SYMBOLS_NAME
            override suspend fun execute(call: ToolCall, ctx: ProjectionContext): FastExecResult =
                FastExecResult("other.tool", call.callId, "{}")
        }
        val outWrong = FastDispatcher(mapOf(LspTools.SYMBOLS_NAME to wrongTool)).dispatch(d, lspOn)
        val skippedWrong = skippedOf(outWrong, "result toolName mismatch")
        assertTrue(skippedWrong.attempted)
        assertEquals("RESULT_IDENTITY_MISMATCH", skippedWrong.denyCode)
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

    // ---- #19 B3 stage-1：symbol 前閘僅收命令式肯定語句（affirmative-command admission）----
    // symbol 前閘僅准許**命令式肯定語句**（explicit lookup command）：句首（可選 EN
    // `please`／CJK 請|麻煩|帮我|幫我|给我|給我|请 前綴）必須是查詢動詞
    // （find/search/lookup/locate，或 CJK 命令式查詢動詞符元
    // 查詢|查询|搜尋|搜寻|搜索|查找|尋找|寻找|找尋|找寻 複合 longest-match-first 排前、
    // 單字 查/找/尋/搜/詢/寻/询 殿後）或
    // `symbol(s) <name>` 命令形；句中出現授權/條件授權子句（命令式 opener + 結構性
    // 授權/條件子句否決：only after/when/if/until/once/before/with/upon …、
    // after|until|unless|once|when|while|if|before|since|whenever|as|because|
    // provided|upon|following|pending|with|subject to|prior to|given|on 後接可選受詞/限定詞（0–3 個）再接詞幹
    // （approve/agree/consent/confirm/say/allow/ok/okay/release/permit/permission/
    // review 等；gerund 與過去式一併涵蓋）、wait for/until/till、hold on/off、not yet、
    // CJK 結構性條件句（等…同意、同意後（再/才）、只有/只許/只允…才有界框架），
    // 皆對 raw 文本與兩個正規化探針（NFKC + Cf 摺空白／NFKC + Cf 刪除）聯集測試），
    // 或任意教學/說明標記（命令式 opener + 教學標記否決：explain|teach|tutorial|
    // how-to|howto|mean|describe|demonstrate 等屈折形式，或 CJK 說明|説明|講解|讲解|
    // 介紹|介绍|討論|讨论|教程|方法（是）?|意思|名稱|名称|如何|怎麼|怎么|怎样|怎樣），
    // 或疑問尾標（? ？ 嗎 吗 呢）
    // ⇒ 不滿足前閘。該類語句不成立 symbol 模板——即使句中含 lookup 動詞也絕不准許
    // 執行；它們落入 RULES 迴圈（仍無命中者 ⇒ no-match DENIED）。
    // 釘住：implement per review findings on PR #171 / #19 B3 stage-1。
    @Test fun nonAffirmativeSymbolCommandsDenyZeroBackend() = runBlocking {
        // 每個 utterance：decide() ⇒ DENIED + toolCall == null；
        // decideAndDispatch 帶 counting fake backend ⇒ 後端零呼叫、outcome 非 Executed。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "I won't allow you to search symbol Foo",
            "I decline to search symbol Foo",
            "Search symbol Foo only after I approve",
            "Wait until I agree, then search symbol Foo",
            "Can you search symbol Foo",
            "Could you search symbol Foo",
            "I want to find symbol Foo",
            "I need to find symbol Foo",
            "Please search symbol Foo only after approval",
            "Search symbol Foo only when I say so",
            "Can you explain how to find symbol Foo",
            "Could you show me how to search symbol Foo",
            "Please explain how to find symbol Foo",
            "The symbol is find",
            "符號名稱叫 find",
            "請先說明如何查 Foo 符號",
            "請說明如何查 Foo 符號",
            "麻煩說明怎麼查 Foo 符號",
            "Tutorial: 如何查 Foo 符號",
            "教程：如何查 Foo 符號",
            "可以幫我查 Foo 符號",
            "查 Foo 符號的方法是",
            "我查 Foo 符號的結果",
            // #19 B3 stage-1 fix：noun-form / inflected consent clauses（結構性錨定）。
            "search symbol Foo after approval",
            "search symbol Foo after the approval",
            "search symbol Foo after my approval",
            "search symbol Foo after their consent",
            "search symbol Foo until consent",
            "search symbol Foo before approval",
            "search symbol Foo once approved",
            "search symbol Foo when approved",
            "search symbol Foo upon approval",
            "search symbol Foo upon consent",
            "search symbol Foo upon prior approval",
            "search symbol Foo following approval",
            "search symbol Foo following consent",
            "search symbol Foo pending approval",
            "search symbol Foo pending my approval",
            "search symbol Foo with approval",
            "search symbol Foo with their consent",
            "search symbol Foo only with approval",
            "find symbol FOO after approval",
            "search symbol Foo since I approved",
            "search symbol Foo whenever approved",
            "search symbol Foo as approved",
            "search symbol Foo because approved",
            "search symbol Foo provided approval",
            "search symbol Foo while approved",
            "search symbol Foo after I approved",
            "search symbol Foo after I consented",
            "search symbol Foo after I confirmed",
            "search symbol Foo after I agreed",
            "search symbol Foo after I allowed",
            "search symbol Foo after I permitted",
            "search symbol Foo after I said",
            "search symbol Foo after she approves",
            "search symbol Foo after anyone approves",
            "search symbol Foo once I approved",
            "search symbol Foo when she approved",
            "search symbol Foo after I had approved",
            "search symbol Foo after approving",
            "search symbol Foo after agreeing",
            "search symbol Foo after consenting",
            "search symbol Foo after my approval",
            "search symbol Foo once it is approved",
            // #19 B3 stage-1 fix：Cf/格式符拆分（NFKC + Cf 剝離/刪除探針）。
            "search symbol Foo if\u200BI approve",
            "search symbol Foo after\u200BI approve",
            "search symbol Foo after I\u200Bapprove",
            "search symbol Foo after I ap\u200Bprove",
            "search symbol Foo only after\u200BI approve",
            "search symbol Foo wait\u200Bfor",
            "search symbol Foo hold\u200Boff",
            "search symbol Foo after\u00ADI approve",
            "search symbol Foo after\u2060I approve",
            "search symbol Foo after\uFEFFI approve",
            "how\u200Bto search symbols",
            "howto search symbol Foo",
            "how\u00A0to search symbol Foo",
            "how\u3000to search symbol Foo",
            "how\u2011to search symbol Foo",
            "how\u00ADTo search symbol Foo",
            "how-to search symbol Foo",
            "search symbol Foo ｉｆ Ｉ ａｐｐｒｏｖｅ",
            // CJK 結構性條件句框架（所列框架外不宣稱完備）。
            "查 Foo 符號 等我同意",
            "查 Foo 符號 同意後再查",
            "查 Foo 符號 核準後再查",
            "查 Foo 符號 只许我核准才查",
            // #19 B3 re-review round-3：conjunction 擴充（subject to／prior to／given／on）。
            "search symbol Foo subject to approval",
            "search symbol Foo on the approval",
            "search symbol Foo on my approval",
            "search symbol Foo given approval",
            "search symbol Foo prior to consent",
            "search symbol Foo prior to approval",
            // #19 B3 final polish：EN 連接詞後錨定 CJK 許可詞幹餘例（繁簡對稱）。
            "search symbol Foo given 同意",
            "search symbol Foo on 同意",
            "search symbol Foo prior to 核准",
            "search symbol Foo prior to 许可",
            // #19 B3 final polish（security audit L-1）：疑問尾標探針亦剝 Hangul filler／combining marks。
            "查 Foo 符號呢\u3164",
            "查 Foo 符號呢\u0301",
            // #19 B3 final polish（security audit L-2）：EN 連接詞後錨定 CJK 許可詞幹。
            "search symbol Foo subject to 許可",
            // #19 B3 re-review round-3：疑問尾標跑在 Cf 剝離後的視圖上。
            "查 Foo 符號呢\u200B",
            "查 Foo 符號?\u200B",
            "search symbol Foo?\u200B",
            // #19 B3 re-review round-3：CJK 結構性條件句框架（只有…才／等…／X後（再）×繁簡）。
            // 註：獨立 CJK pins 於 opener/hint 閘即否決（所斷言契約＝DENIED）；
            // 框架正則本體由 查-前綴 probes（如 `查 Foo 符號 同意後再查`）演練。
            "只有你同意我才查",
            "等我們同意",
            "批准後再查",
            "只有我核准才查",
            "只許我確認才查",
            "只允他確認才查",
            // #19 B3 stage-6.2（F1 修正後語意）：CJK possessor 槽（stage-8.1 起放寬為平坦 {0,64} 類：
            // \p{L}/\p{N} ＋ ASCII 識別字標點 `._-$#@/():,;+=~` ＋ \s ＋ 半/全形逗號 ＋ CJK 句讀
            // （`。、！？!?；;：:．‥…`）＋ `／` U+FF0F／`＿` U+FF3F；不限封閉代名詞表——
            // 主管/老闆/經理/董事會/工廠 等未列 possessor、長組織名與數字 possessor 皆收；possessor 內部斜線/標點/空白亦收）＋雙臂 consent 框架：
            // A＝動詞＋可選分隔符（半/全形空白、`,` `，` `.` `。` `;` `；` `:` `：` `、` `！` `？` `!` `?`、助詞 `了` `過`）
            //   ＋必填後-family（再|才 可選、其前亦可夾分隔符，`同意後`／`批准後`／`同意後，再查` 皆否決）；
            // B＝動詞＋可選分隔符（同上）＋必填再|才（`批准再做`／`批准才做`／`批准,再做`／`批准了再做` 皆否決）；
            // 無 `再|才` 亦無後-family ⇒ 不匹配（裸 `批准` 仍 FAST 見下方正向測試）。
            "查 Foo 符號等主管批准再做",
            "查 Foo 符號等老闆同意",
            "查 Foo 符號等經理核准",
            "查 Foo 符號等董事會點頭",
            "查 Foo 符號等工廠批准",
            "查 Foo 符號批准再做",
            "查 Foo 符號批准才做",
            // #19 B3 stage-6.2 fix（F1）：arm A 還原——動詞＋必填後-family（無再|才）亦否決。
            "查 Foo 符號同意後",
            "查Foo符號批准後",
            // #19 B3 stage-6.2 fix（F3）：raw 文本帶 zero-width space（U+200B）的 CJK 條件框架——
            // spaced/joined 探針還原後命中框架（unicode escape 寫法，見既有 `呢\u200B` 先例）。
            "查Foo符號等\u200B主管同意",
            "查 Foo 符號批准\u200B再做",
            "查 Foo 符號等主管\u200B同意",
            // 精確 report 字串（無空白形式）：
            "查Foo符號等主管批准再做",
            // #19 B3 stage-7.1（A1）：CJK possessor 槽放寬至 [\p{L}\p{N}]{0,64}（平坦（最壞多項式有界）；stage-8.1 再放寬為上述平坦類），
            // consent 動詞與 後-family/再|才 之間夾半/全形分隔符與助詞 `了` 亦否決。
            // 原 `positionalCueGrammarPins` 之 FAST pins 翻轉為 DENIED：
            "查Foo符號等123456789批准",
            "查Foo符號批准,再做",
            // #19 B3 stage-7.2（A2）×stage-7.1：複合符元整體消費 `查詢` 後，
            // arm B（`批准,再做`）於同一 raw 文本上命中 ⇒ 准入否決（對 raw/spaced/joined
            // 三探針聯集測試）：
            "查詢Foo符號批准,再做",
            // 新增 pins：
            "查Foo符號等123456789012批准",
            "查Foo符號等安全審核委員會主席批准",
            "查Foo符號等國家安全委員會批准",
            "查Foo符號批准，再做",
            "查Foo符號批准;再做",
            "查Foo符號批准:再做",
            "查Foo符號批准了再做",
            "查Foo符號批准。再做",
            "查Foo符號同意後，再查",
            "查Foo符號批准，才做",
            // 七個 U+FF0E/U+2025/U+2026/下劃線 U+005F（審計 finding：同類 bypass）——arm A/B 分隔符涵蓋：
            "查Foo符號批准_再做",
            "查Foo符號同意_後再做",
            "查Foo符號點頭．再做", // U+FF0E fullwidth full stop
            "查Foo符號批准‥再做", // U+2025 two-dot leader
            "查Foo符號批准…再做", // U+2026 horizontal ellipsis
            "查Foo符號等\u200B主管批准",
            // arm D′：CJK 插入語蓋 verb 與 再|才 之間（含全形字母/助詞）：
            "查Foo符號批准Ｄ再做",
            "查Foo符號同意白拿了再做",
            "查Foo符號批准主張再做",
            "查Foo符號批准五再說",
            "查Foo符號核准Ｔ別了再做",
            "查Foo符號批准Ｄ_Ｄ再做",
            "查Foo符號批准a-b再做",
            "查Foo符號批准Ｄ;Ｄ再做",
            "查Foo符號批准Ｄ,Ｄ再做",
            "查Foo符號批准Ｄ.Ｄ再做",
            "查Foo符號批准Ｄ/Ｄ再做",
            "查Foo符號批准Ｄ#Ｄ再做",
            "查Foo符號批准Ｄ\$Ｄ再做",
            "查Foo符號批准Ｄ(Ｄ再做",
            "查Foo符號批准Ｄ)Ｄ再做",
            "查Foo符號批准Ｄ:Ｄ再做",
            "查Foo符號核准_甲乙丙再做",
            "查Foo符號同意甲-乙再做",
            "查Foo符號許可_甲再做",
            // FLEX 動詞內插入（stage-8.1 鎳封域；審計 finding F4）：FLEX 於動詞內允許 ≤16 插入亦否決：
            "查Foo符號等主管核1準再做",
            // spaced backstop：possessor 兩端單空白落在框架兩個分隔槽內，
            // arm D 仍匹配 ⇒ decide() DENIED、reasonCode=null（no-match fallback，verified；
            // 絕不以 NEED_CLARIFICATION 過度斷言）——絕不執行（never FAST）。
            "查Foo符號等 安全審核委員會主席 批准",
            // #19 B3 stage-8.1（A1 修正）：「尚待批准／回覆」條件句形態——等-arm possessor
            // 槽放寬為平坦 {0,64} 類（\p{L}/\p{N} ＋ ASCII 識別字標點 ＋ \s ＋ 半/全形逗號
            // ＋ CJK 句讀 ＋ `／` U+FF0F／`＿` U+FF3F），consent 動詞集新增 回覆|回复|答覆|答复（繁簡對稱）：
            "查Foo符號等資安/法務批准",
            "查Foo符號等CISO/CEO同意",
            "查Foo符號等資安／法務批准", // U+FF0F fullwidth solidus
            "查Foo符號等資安_法務批准",
            "查Foo符號等主管-法務批准",
            "查Foo符號等資安 / 法務批准", // 斜線兩側夾空白
            "查Foo符號等我回覆再做",
            "查Foo符號等你回复",
            "查Foo符號等經理答覆後再做",
            "查Foo符號等經理答复再做",
            "查Foo符號等董事會回覆",
            // #19 B3 stage-8.1（security-audit O2/O3；M1/M2 修正之回歸釘）：
            // arm A 回覆/答覆族（bare 後-family 亦否決）／arm B 簡體答复：
            "查Foo符號回覆後再做",
            "查Foo符號答覆後", // arm A bare 後-family，無再|才
            "查Foo符號答复再做", // arm B（簡體答复）
            // Cf-split possessor-with-slash（unicode escape 寫法）：
            "查Foo符號等\u200B資安/法務批准",
            // U+3000 全形空白——NFKC 探針摺疊為 ASCII 空白 ⇒ spaced/joined 探針否決：
            "查Foo符號等資安\u3000/\u3000法務批准",
            // 動詞內 ≤16 插入（CONSENT_VERBS_CJK_FLEX）仍否決：
            "查Foo符號等主管批A准",
            "查Foo符號等主管批_准",
            "查Foo符號等主管批ㅤ准", // U+3164 Hangul filler（Lo ⊆ \p{L} ⊆ INS）
            "查Foo符號等主管同A意",
            "查Foo符號批A准再做", // arm B（相容 D′）+ FLEX：FLEX 動詞 `批A准` 後空插入 + 再
            "查Foo符號等主管回A覆",
            "查Foo符號等主管答A复",
            // M1 回歸釘：等-arm delimiter 為原子群組 ⇒ 大量連續 `，` 不得造成
            // possessor/delimiter 反覆回溯（原 O(N²·64) 否決失敗）；assert DENIED + toolCall==null。
            // 長度恰為 MAX_INTENT_CHARS＝4096（再多字元會撞輸入長度閘——該閘先於任何 regex，
            // 否決毫無回歸意義）。
            // 結尾 `呢` 使 admission 於疑問尾標探針否決 ⇒ decide() 落 RULES 無命中 ⇒ no-match DENIED，
            // 不進 extraction 路徑（此釘刻意隔離條件句探針，與 extraction 解耦）。
            // 背景：stage-8.2 已將 extraction 的 flat 分隔符槽（`SYMBOL_CJK_KEYWORD_SUFFIX`／
            // `BARE_SYMBOL_CUE_WRAP`）改為原子群組，同一裸逗號串經 extraction 亦不再分割枚舉
            // （修前為超線性多項式 O(n²)，長逗號串可致掛起/超時）；此處保留 `呢` 尾標，
            // 使本釘持續單獨演練 PERMISSION_CONDITIONAL_CLAUSE raw 探針（M1 回歸），
            // 不依賴 extraction 的時序特性；若有人把 atomic delimiter 退回非原子，
            // 本測試會永久掛住（＝回歸被抓到）。extraction 裸逗號路徑的回歸釘另見
            // [extractionSeparatorBlowupPinsDenyZeroBackend]。
            "查Foo符號等" + "，".repeat(4087) + "X呢",
        )) {
            // decide()：decision is DENIED, toolCall == null。
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.toolCall)
            // decideAndDispatch：decision DENIED、backend 零呼叫、outcome 非 Executed。
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun affirmativeSymbolCommandsStayFast() {
        // #19 B3 stage-1 positive guards：命令式肯定語句仍成立 symbol 前閘 ⇒ FAST；
        // query 以 parsed argumentsJson 的 query 欄位精確等值斷言（與
        // requestedLimitIsEnforced 同一 Json.parseToJsonElement 風格）。
        for ((text, expectedQuery) in listOf(
            "search symbol Foo" to "Foo",
            "find symbol Foo" to "Foo",
            "查 Foo 符號" to "Foo",
            // #19 B3 stage-8.1（A1）：無 possessor／無 consent 動詞形態之精確 query 釘住——
            //  possessor 槽放寬／consent 動詞集擴充後，`查Foo符號` 仍須 FAST 且 query 恰為 `Foo`
            //  （extraction Step C 剝一個尾綴 `符號`——`Foo符號` 非本輸入之殘餘，見 extractSymbolQuery）。
            "查Foo符號" to "Foo",
            "查 SymbolTable 符號" to "SymbolTable",
            "find symbols Foo" to "Foo",
            // #5（B3 re-review）EN connective strip 仍生效（legacy compatibility）。
            "search for symbol Foo" to "Foo",
            "查一下 symbols Foo" to "Foo",
            "symbols Foo" to "Foo",
            // (#19 stage-2：`search symbol Foo on button` 等 multi-token 殘餘
            //  改判 NEED_CLARIFICATION——見 multiTokenRemainderClarifies。)
            // #19 B3 stage-1 fix flip side：結構性錨定不得誤傷識別字/普通詞。
            "search symbol Approved" to "Approved",
            "search symbol approval" to "approval",
            "search symbol consent" to "consent",
            "查 ApprovedWidget 符號" to "ApprovedWidget",
            "查 Approved 符號" to "Approved",
            "查 同意 符號" to "同意",
            "查 批准 符號" to "批准",
            "查 許可 符號" to "許可",
            // #19 B3 stage-9.1（Re-review #9 A1 [P1]）：識別字含 授權/審批（未列示 consent 動詞）
            // 作為普通識別字內容時仍 FAST——arm H/I 否決的是 deferred action／conditional framing
            // 顆粒，非識別字子串本身：
            "查 授權 符號" to "授權",
            "查 授權政策 符號" to "授權政策",
            "查 審批流程 符號" to "審批流程",
            "search symbol afterApproval" to "afterApproval",
            "search symbol after_approval" to "after_approval",
            "查 ConsentFlow 符號" to "ConsentFlow",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test fun interrogativePunctuatedEndingsDenyZeroBackend() = runBlocking {
        // #19 B3 stage-6.1（interrogative-tail hardening）：疑問顆粒後接中性
        // 句末終止符 run 時，symbol 前閘仍不得成立 ⇒ DENIED、toolCall == null、
        // decideAndDispatch 不執行、backend.invocations == 0（問句絕不執行）。
        // 先前的 narrowTailAcceptedResidualStaysFast 曾釘住 FAST（narrow-tail
        // limitation）的錯誤契約——已刪除並翻轉為此測試。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "查 Foo 符號呢。",
            "查 Foo 符號嗎.",
            "查 Foo 符號呢！",
            "查 Foo 符號呢!!!",
            "查 Foo 符號呢…",
            "查 Foo 符號呢 。",
            "查 Foo 符號 呢 。",
            "查 Foo 符號嗎。",
            "查 Foo 符號呢。！",
            "查 Foo 符號嗎？。",
        )) {
            // decide()：decision is DENIED, toolCall == null。
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.toolCall)
            // decideAndDispatch：decision DENIED、outcome 非 Executed、backend.invocations 不增。
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
        assertEquals(0, backend.invocations.get())
        // Positive guards：命令式肯定語句仍 FAST，query 精確等值（與
        // affirmativeSymbolCommandsStayFast 同一 Json.parseToJsonElement 風格）。
        for ((text, expectedQuery) in listOf(
            "查 Foo 符號" to "Foo",
            "find the symbol Foo" to "Foo",
            "search for symbol Foo" to "Foo",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test fun particlesInRemainderClarifyAndNeverExecute() = runBlocking {
        // #19 B3 stage-6.1（particle rule）：殘餘中含疑問顆粒（嗎|吗|呢）⇒
        // extractSymbolQuery 硬失敗 ⇒ NEED_CLARIFICATION（fail-closed；顆粒絕不
        // 剝離、絕不進 query）——包含無空格黏合形態 `查 Foo呢符號`（step C 後綴
        // cue 剝離 `符號` 後殘餘 `Foo呢` 仍含顆粒）。絕不執行。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for (text in listOf(
            "查 Foo呢 符號",
            "查 Foo嗎 符號",
            "查 Foo吗 符號",
            "查 Foo呢符號",
        )) {
            // decide()：DENIED + toolCall == null + reasonCode NEED_CLARIFICATION。
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            // decideAndDispatch：decision DENIED、outcome 非 Executed、backend.invocations 不增。
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun enCommandDelimitedIdentifiersAreVerbatim() = runBlocking {
        // #19 B3 stage-8.3（Re-review #8 A2, P2 BLOCKER）：EN 命令形劃定的殘餘為
        // verbatim identifier——Step C 尾綴剝離與 pre-Step-B′／post-Step-D 兩道歧義守衛
        // 一律不適用（enDelimited 旗標：SYMBOL_OPENER_EN／SYMBOL_OPENER_CMD／
        // SYMBOL_LEADING_SYMBOLS 任一消費即設；CJK opener 消費不設）。
        // 每對（text → expectedQuery）：decide() FAST + toolName lsp.symbols + query 精確等值
        // （argumentsJson parseToJsonElement 風格，與 affirmativeSymbolCommandsStayFast 一致）＋
        // decideAndDispatch（同一 fresh CountingBackend）執行且 invocations +1、
        // receipt body 原樣回顯識別字（"name":"$expectedQuery:example"）。
        // 多 token 殘餘（空白）⇒ Step E 拒絕 ⇒ NEED_CLARIFICATION（絕不執行）——
        // EN-delimited 分支僅限單一 token；CJK 分支行為逐位元不變
        // （`查 Foo 符號` → FAST `Foo` 等既有 pin 維持綠燈）。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for ((text, expectedQuery) in listOf(
            "symbols Foo符號" to "Foo符號",
            "find symbol Foo符號" to "Foo符號",
            "find symbols Foo符號" to "Foo符號",
            "search symbol Foo符號" to "Foo符號",
            "search symbols Foo符號" to "Foo符號",
            "symbols 符號" to "符號",
            // 混合 CJK opener + EN cue：CJK opener 不設 enDelimited，但 EN 命令形消費即設
            // ⇒ 同一 EN-delimited 規則適用。
            "查 symbols Foo符號" to "Foo符號",
            // 混合 opener＋黏 cue 殘餘（stage-8.3；記錄已接受 verbatim 殘餘：
            // EN grammar 劃定 ⇒ 歧義守衛不適用、不剝離）：
            "symbols 符號符號" to "符號符號",
            "查 symbols 符號符號" to "符號符號",
            "symbols Foo符號xyz" to "Foo符號xyz",
            "查 symbols Foo符號xyz" to "Foo符號xyz",
            // stage-12.1 名前置殘餘（EN-delimited 判讀，READ-only）：CJK tail 但被 EN cue 消費 ⇒ verbatim 保留：
            "查 symbols Foo符號等法務簽核" to "Foo符號等法務簽核",
            // Step D（CJK/EN 共用之邊緣類）剝離尾端 `。`；en-delimited 分支同樣適用。
            "symbols Foo符號。" to "Foo符號",
            // #19 B3 stage-8.3 翻轉 pins（原 NEED_CLARIFICATION——EN 劃定語意校正）：
            // #19 B3 stage-9.2（Re-review #9 A2, [P2]）再度翻轉——CJK-leading 語句先讀
            // CJK 語法：尾綴 cue 剝去後 `find`/`Symbol`/`symbols`/`lookup`/`locate` 皆為
            // 合法 verbatim 識別字（READ-only 查詢、不改寫；CJK-leading 一律先讀 CJK 語法）。
            "查 find 符號" to "find",
            "查 Symbol 符號" to "Symbol",
            "查 symbols 符號" to "symbols",
            // 對稱翻轉 pins（與 find/Symbol 同族——stage-9.2 同一根機制）：
            "查 lookup 符號" to "lookup",
            "查 locate 符號" to "locate",
            // 前緣剝離殘餘（記錄已接受殘餘：Step D 去全形 `？` ⇒ verbatim `符號`）：
            "symbols ？符號" to "符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.FAST, result.decision.route)
            val executed = result.outcome as? FastDispatchOutcome.Executed
            assertNotNull("$text must execute against the fake backend (got ${result.outcome})", executed)
            assertEquals(text, before + 1, backend.invocations.get())
            assertTrue(
                "executed body must echo the identifier verbatim: ${executed!!.result.resultJson}",
                executed.result.resultJson.contains("\"name\":\"$expectedQuery:example\""),
            )
        }
        // 澄清 pins：多 token 殘餘（空白）⇒ Step E 拒絕；EN-delimited 分支亦然（絕不執行）。
        for (text in listOf(
            // 尾綴 cue 後夾額外 token——空白 ⇒ Step E 澄清。
            "symbols Foo符號 extra",
            // 雙 cue 空白分隔——Step E 空白拒絕。
            "symbols 符號 符號",
            "symbols Foo 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            // decideAndDispatch：decision DENIED、outcome 非 Executed、backend.invocations 不增。
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
    }

    // #19 B3 stage-9.2（Re-review #9 A2, [P2]）：EN `symbols?` cue 的兩次消費疊加導致
    // 合法 `Symbol(s)` 識別字被摧毀（`find symbol symbol` → 空 ⇒ 澄清、`symbols symbols`
    // → 空 ⇒ 澄清）。stage-9.2 修復：(a) cue budget——Step A CMD 消費一個 `symbols?`
    // cue 即記帳，Step B（另一 cue 消費者）同一 utterance 最多執行一次（CMD 耗 budget
    // ⇒ Step B 整體跳過；CMD 自身仍只消費一次）；(b) 兩階段 CJK-preferred-fallback——
    // CJK opener 引領的語句先由 CJK 分支解析（尾綴 cue 剝離＋兩道歧義守衛＋Step-B′
    // EN connectives＋Step D 邊緣剝離，無 EN cue machine），非空結果即返回；CJK 階段
    // 失敗（空白/守衛拒絕）才落入 EN pipeline。CJK opener 消費本身不設 enDelimited、
    // 不耗 cue budget；EN cue/verb/CMD 消費才設。CJK 階段與 EN pipeline 共用 Steps C–E
    // （finishSymbolQueryExtraction），兩分支不可能漂移。
    // 驗證期望（decide() 端到端，與 enCommandDelimitedIdentifiersAreVerbatim 同一
    // double-assert 風格——decide FAST + toolName + query 精確等值 + decideAndDispatch
    // 執行且 invocations +1、receipt body 回顯 `"name":"$query:example"`）：
    //   `find symbol symbol` → FAST `symbol`（CMD 耗 budget，Step B 跳過）；
    //   `symbols symbols`    → FAST `symbols`（同上）；
    //   `find symbol Foo`    → FAST `Foo`；
    //   `查 Foo符號`         → FAST `Foo`（CJK-preferred 尾綴 cue 剝離，不變保證）；
    //   `查 Symbol Foo`      → FAST `Foo`（CJK 階段 Step E 拒空白 ⇒ EN fallback：
    //                           CMD `Symbol ` 耗 budget ⇒ Step B 跳過 ⇒ `Foo`）。
    // CJK 雙 cue 仍澄清（fail-closed）：`查 Foo符號 符號` — CJK ambiguity guard
    //   （peel 後殘餘仍匹配尾綴 cue）拒絕 ⇒ CJK 階段空 ⇒ EN pipeline 同守衛再度拒絕
    //   ⇒ NEED_CLARIFICATION（絕不執行）。
    @Test fun enCueBudgetAndCjkPreferredExtractionPinned() = runBlocking {
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for ((text, expectedQuery) in listOf(
            "find symbol symbol" to "symbol",
            "symbols symbols" to "symbols",
            "find symbol Foo" to "Foo",
            // stage-9.2 不變保證 pin：CJK-preferred 分支 peel 尾綴 cue ⇒ `Foo`。
            "查 Foo符號" to "Foo",
            // CJK 階段失敗 ⇒ EN fallback 路徑 ⇒ `Foo`（記錄已接受殘餘：
            // `Symbol` 與 `Foo` 間空白被 EN grammar 劃定）。
            "查 Symbol Foo" to "Foo",
            // stage-9.4（release-gate 鎳）：CJK-leading single-token CJK-preferred-fallback 代表例——
            // 裸 EN cue 字面 token 為 READ-only 識別字 binding（esc() JSON-escape、L/N 錨點仍由 Step E 把關）：
            "查 search 符號" to "search",
            "查 locate 符號" to "locate",
            "查 symbol" to "symbol",
            "查 Symbols 符號" to "Symbols",
            // 雙 cue 混合形態（cue budget 1：CMD 消費 `symbols ` ⇒ Step B 跳過）：
            "查 symbols symbols" to "symbols",
            "find symbol symbols" to "symbols",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.FAST, result.decision.route)
            val executed = result.outcome as? FastDispatchOutcome.Executed
            assertNotNull("$text must execute against the fake backend (got ${result.outcome})", executed)
            assertEquals(text, before + 1, backend.invocations.get())
            assertTrue(
                "executed body must echo the identifier verbatim: ${executed!!.result.resultJson}",
                executed.result.resultJson.contains("\"name\":\"$expectedQuery:example\""),
            )
        }
        // CJK 雙 cue 仍澄清（fail-closed，絕不執行）：CJK ambiguity guard 先拒，
        // EN pipeline 同守衛再度拒 ⇒ NEED_CLARIFICATION。
        for (text in listOf(
            "查 Foo符號 符號",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
        assertEquals(11, backend.invocations.get())
    }

    // #19 B3 stage-8.4（Re-review #8 A3）：C++ global-namespace 前綴 `::Foo` 是
    // 不同 scope——SYMBOL_EDGE 前導分支不再剝 `:`（U+003A）／`：`（U+FF1A），
    // leading colon 原樣保留 verbatim（leading colons 一律不剝/不改寫）；
    // `:Foo`（單冒號）為記錄之 verbatim
    // 殘餘（READ-only）；全形 `：`（U+FF1A）Step E 是 ASCII-punct-only ⇒
    // NEED_CLARIFICATION（fail-closed，絕不 rewrite 成 `Foo`）；
    // #19 B3 stage-9.3（Re-review #9 A3, [P2]）：尾端分支亦不再剝 `:`（U+003A）／`：`（U+FF1A）——
    // C++ scoped qualified identifier 之尾端 `::` 為合法 token，原樣保留 verbatim（`Foo::` ≠ `Foo`；
    // 與 stage-8.3/8.4 verbatim-preservation 一致；COI token `::Foo` 兩分支對齊：前導 stage-8.4／尾端 stage-9.3）；
    // `查 Foo: 符號` ⇒ verbatim `Foo:`（stage-8.2/8.4 時釘為 `Foo`——stage-9.3 翻轉）；
    // `查 ::std::vector:: 符號` ⇒ FAST `::std::vector::`；`查 Foo。 符號` ⇒ FAST `Foo`（`。` 尾仍剝）；
    // 尾端全形 `：`（U+FF1A）不再剝除 ⇒ Step E 拒絕 ⇒ NEED_CLARIFICATION（fail-closed，
    // 與 FF1A leading reject 對稱）；`查 ::符號 符號` 歧義守衛照舊澄清。
    // possessive `++$`（stage-8.2）保留無誤。
    // （argumentsJson parseToJsonElement 風格，與 enCommandDelimitedIdentifiersAreVerbatim 一致）
    @Test fun leadingColonPrefixesPreserved() = runBlocking {
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        for ((text, expectedQuery) in listOf(
            "查 ::Foo 符號" to "::Foo",
            "查 ::std::vector 符號" to "::std::vector",
            "查 :Foo 符號" to ":Foo",
            "查 Foo: 符號" to "Foo:",
            // stage-9.3：尾端 `::` verbatim 保留——C++ scoped qualified identifier 合法 token：
            "查 Foo:: 符號" to "Foo::",
            "查 ::std::vector:: 符號" to "::std::vector::",
            // stage-9.3：`。` 尾仍剝（`。` 不變）：
            "查 Foo。 符號" to "Foo",
            // Leading-colon token-body residual（記錄已接受殘餘：冒號開頭入 Step E）：
            "查 ::,Foo 符號" to "::,Foo",
            "查 :;Foo 符號" to ":;Foo",
            // EN-delimited 尾端 `::` verbatim 保留；全形 `：` 拒 ⇒ clarify（見下方澄清 pins）：
            "find symbol Foo::" to "Foo::",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.FAST, result.decision.route)
            val executed = result.outcome as? FastDispatchOutcome.Executed
            assertNotNull("$text must execute against the fake backend (got ${result.outcome})", executed)
            assertEquals(text, before + 1, backend.invocations.get())
            assertTrue(
                "executed body must echo the identifier verbatim: ${executed!!.result.resultJson}",
                executed.result.resultJson.contains("\"name\":\"$expectedQuery:example\""),
            )
        }
        // 澄清 pins：全形 `：`（U+FF1A）Step E 拒絕（fail-closed）——包括尾端 `：`
        // （stage-9.3：尾端分支不再剝 `：`，Step E 拒絕 U+FF1A ⇒ NEED_CLARIFICATION，
        // 與 FF1A leading reject 對稱；≥ stage-8.2 pin 改釘澄清）；
        // `::符號 符號` 歧義守衛照舊澄清（pre-Step-B′ 守衛先行；post-Step-D 守衛為後備）。
        for (text in listOf(
            "查 ：Foo 符號",
            "查 Foo： 符號",
            "symbols Foo：",
            "查 ::符號 符號",
            // 裸 cue＋尾端冒號：BARE_SYMBOL_CUE_WRAP（包夾守衛）夾尾拒 ⇒ 澄清（不受 stage-9.3 尾端 verbatim 放行影響）：
            "查 符號:",
            "查 符號::",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertEquals(text, DenyReason.NEED_CLARIFICATION, d.reasonCode)
            assertNull(text, d.toolCall)
            // decideAndDispatch：decision DENIED、outcome 非 Executed、backend.invocations 不增。
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
    }

    @Test fun enDelimitedClauseAndColonInteractionsArePinned() = runBlocking {
        // #19 B3 stage-8.6（交互 pins；Re-review #8 release-gate required）＋stage-9.1（Re-review #9 A1 [P1]）＋stage-10.1（Re-review #10 A1 [P1]）＋stage-11.1（Re-review #11 A1 [P1]）：
        // 釘住 EN-delimited verbatim carve-out（stage-8.3）× 尾綴/前導條件子句閘（stage-8.1）
        // × leading colon（stage-8.4）× SLOW 後備（stage-8.5）的跨階段交互——皆已實測無誤，
        // 僅缺 pin。准入判定在 raw 文本上跑 ⇒ enDelimited 不救 `…批准再做` 形態的子句 veto；
        // EN-delimited 之 verbatim identifier 僅可能落成有界單 token LSP 查詢（READ-only、
        // esc() JSON-escape、AFFIRM_…_gate 語義不變）。
        // stage-9.1：CJK arm H（deferred-action nucleus `再|才`＋有界動作動詞核）／arm I
        // （conditional-framing nucleus 如果|假如|倘若|要是|的話）於准入檢查（同一 PERMISSION_CONDITIONAL_CLAUSE
        // clause gate，raw/spaced/joined 三探針聯集）落地、任意位置即否決——不依賴 consent 動詞識別；
        // 未列示 consent 動詞（`審批`／`授權`…）不再是 fail-OPEN 捷徑（`…審批再做` 等已 flip 為 DENIED）。
        // EN 側（`after i approve` 等）不變、not 觸動——本 stage 僅 CJK arm H/I；stage-10.1 僅 CJK arm J/K 與 arm H 動詞核擴充。
        // stage-11.1（Re-review #11 A1 [P1]）：arm L（person-anchored wait nucleus 等|待|直到|直至×人稱字，31 個）／arm M（cardinal-time-expression nucleus 基數詞＋時間單位＋後|后），
        // 皆任意位置、同一 PERMISSION_CONDITIONAL_CLAUSE clause gate；不再依賴 consent 動詞識別、不枚舉端點/延後動詞。arm K over-deny 之 P2 acknowledgment 見 KDoc。
        // stage-12.1（Re-review #12 A1 [P1/BLOCKER]）：terminal-position rule——最後一個 `符號|符号` 之後仍含 \p{L}|\p{N} 碼點 ⇒ extraction 硬失敗
        // （[finishSymbolQueryExtraction] 非 EN-delimited 相；加性 deny-only；CJK 分支及 EN fallback 之非-enDelimited 相皆生效；
        // 識別字含 CJK 者必須止於 cue；suffix 僅非 L/N（標點／空白／emoji）仍 FAST by design；suffix 含 L/N（如 `，一下`，`一` `下` 皆 \p{L}）⇒ 澄清）。
        // stage-10.1（Re-review #10 A1 [P1]）：arm J（wait/until＋completion nucleus 等|待|直到|直至×放行|結束|完成|完畢|通過|核准|批准|確認|同意|回覆|通知）／
        // arm K（bare temporal nucleus 明早|明晚|明天|明日|後天|后天|大後天|大后天|下週|下星期|下周|下下週|下下周|下個月|下个月|月底|年底|日後|日后|幾天後|几天后|過幾天|过几天|稍後|稍后|晚點|晚点|等下|待會|待会|待會兒|待会儿|回頭|回头|隔天|隔日|次日|翌日|翌週|翌周|翌年，41 顆粒）／
        // arm H 動詞核擴充至 20 個 Lo 動詞（＋通知|告訴|通報|報告|匯報|彙報|回信|提醒），皆任意位置、同一 PERMISSION_CONDITIONAL_CLAUSE clause gate。
        val backend = CountingBackend()
        val dispatcher = lspDispatcher(backend)
        // FAST 交互 pins（EN-delimited × 前導冒號；及識別字含 consent/授權 相鄰詞之既 FAST pins）：
        // EN 消費（opener 或 `symbols?` cue）⇒ verbatim（Step A/C/B 發生順序與 raw 一致）；
        // 識別字含 consent 顆粒者（如 `symbols 同意`／`symbols 批准`）仍 FAST——consent 顆粒本身
        // 非否決依據（無 `再|才`＋動作動詞核、無 如果|假如|倘若|要是|的話、無 等|待|直到|直至×completion-endpoint、無裸時間顆粒、
        // 無 等|待|直到|直至×人稱字（arm L）、無基數時間表達式×後|后（arm M））。
        // stage-8.7 之 FAST pins `查 Foo符號等審批再做`／`查Foo符號等授權再做` 自 stage-9.1 起 flip 為 DENIED
        // （arm H：`再做`——deferred action；見下方 DENIED 交互 pins）。
        // stage-11.1 FAST 正向控制 4 筆（查 簽核/核對/允許/啟動 符號）→ FAST pins 計 13 筆（assertEquals(13)）。
        // stage-9.3 之 FAST pins `symbols Foo::`／`查 symbols Foo::` 自「Foo」flip 為 `Foo::`
        // （Re-review #9 A3, [P2]：尾端 `::` 為 C++ scoped qualified identifier 之合法 token，verbatim 保留）。
        for ((text, expectedQuery) in listOf(
            "symbols ::Foo" to "::Foo",
            "find symbol ::Foo" to "::Foo",
            "查 symbols ::Foo" to "::Foo",
            "symbols Foo::" to "Foo::",
            "查 symbols Foo::" to "Foo::",
            "symbols 同意" to "同意",
            "symbols 批准" to "批准",
            // #19 B3 stage-10.1（Re-review #10 A1 [P1]）正向控制：識別字僅「長得像」延後/通知顆粒者仍 FAST——
            // 無 再|才＋動作動詞核、無 等|待|直到|直至×completion-endpoint、無裸時間顆粒：
            "查 通知 符號" to "通知",
            "查 報告 符號" to "報告",
            // #19 B3 stage-11.1（Re-review #11 A1 [P1]）正向控制：延後形識別字但「無」等|待|直到|直至×人稱字、無基數時間表達式者仍 FAST——
            // 證明 arm L/M 不枚舉端點動詞（簽核/核對/允許/啟動 為識別字時仍可查）：
            "查 簽核 符號" to "簽核",
            "查 核對 符號" to "核對",
            "查 允許 符號" to "允許",
            "查 啟動 符號" to "啟動",
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.FAST, d.route)
            assertEquals(text, "lsp.symbols", d.toolCall?.toolName)
            assertNotNull(text, d.toolCall)
            assertEquals(
                text,
                expectedQuery,
                Json.parseToJsonElement(d.toolCall!!.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content,
            )
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.FAST, result.decision.route)
            val executed = result.outcome as? FastDispatchOutcome.Executed
            assertNotNull("$text must execute against the fake backend (got ${result.outcome})", executed)
            assertEquals(text, before + 1, backend.invocations.get())
            assertTrue(
                "executed body must echo the identifier verbatim: ${executed!!.result.resultJson}",
                executed.result.resultJson.contains("\"name\":\"$expectedQuery:example\""),
            )
        }
        // DENIED 交互 pins：raw text 命中條件子句閘（arm A/B/等/D′/H/I/J/K/L/M）⇒ 准入否決
        // （no-match DENIED；enDelimited/verbatim carve-out 救不了子句 veto）。
        // stage-9.1 翻轉釘（Re-review #9 A1 [P1]，fail-open 修復）：`查 Foo符號等審批再做`／
        // `查Foo符號等授權再做` 自 stage-8.7 FAST 移至 DENIED（arm H：`再|才`＋動作動詞核
        // `做`——deferred action，clause gate 落地，never FAST）；新增 arm H/I 負向釘
        // `查Foo符號如果我同意`／`查Foo符號明天再做`／`查Foo符號等審批後再做`。
        // `…符號呢` 尾疑問顆粒 ⇒ 准入否決 ⇒ 落 SLOW_HINTS（自動操作）→ FLAVOR_BLOCKED
        // （PLAY flavor；絕不執行）。route DENIED + toolCall==null + backend 零增。
        for (text in listOf(
            "查 symbols Foo符號等批准再做",
            "symbols Foo符號等批准再做",
            "symbols Foo符號等資安/法務批准",
            "查 symbols Foo符號等資安/法務批准",
            "查 symbols 批准再做",
            "查 symbols 批准 再做",
            "查 自動操作 符號呢",
            // #19 B3 stage-9.1 翻轉釘（由 FAST flip 為 DENIED；arm H）：
            "查 Foo符號等審批再做",
            "查Foo符號等授權再做",
            // #19 B3 stage-9.1 新增 arm H/I 負向釘：
            "查Foo符號如果我同意",
            "查Foo符號再ㅤ做",   // arm H with U+3164 Hangul filler inside the nucleus
            "查Foo符號明天再做",
            // stage-9.4（release-gate 鎳）：arm H 代表攻擊形（未列舉亦必否決）：
            "查Foo再做符號",
            "symbols 再佔再做",
            "查Foo符號等審批後再做",
            // #19 B3 stage-10.1 新增 arm J/K 負向釘（Re-review #10 A1 [P1]，deny-by-admission）：
            // arm J（wait/until＋completion-endpoint）／arm K（bare temporal nucleus）／arm H 動詞核擴充至 20 個：
            "查Foo符號等我放行",
            "查Foo符號等審核結束",
            "查Foo符號明天再通知我",
            "查Foo符號直到審核結束",
            "查Foo符號等我回覆",
            "查Foo符號稍後通知",
            "查Foo符號明天再通ㅤ知我",   // arm H with U+3164 Hangul filler inside the nucleus（stage-9.5 探針剝除 filler 後 `再通知` 命中；亦命中 arm K `明天`）
            // #19 B3 stage-11.1 新增 arm L/M 負向釘（Re-review #11 A1 [P1]，deny-by-admission）：
            // arm L（person-anchored wait nucleus 等|待|直到|直至＋人稱字）／arm M（cardinal-time-expression nucleus 基數詞＋時間單位＋後|后）：
            "查Foo符號等我簽核",
            "查Foo符號等我核對",
            "查Foo符號等我允許",
            "查Foo符號三天後再啟動",
            // stage-11.1 追加（尊稱與 4096-char deny-direction 探針；MAX_INTENT_CHARS=4096）：
            "查Foo符號等您核對",
            "查Foo符號等您們簽核",
            "查Foo符號等妳簽核",
            "查Foo符號等全员核對",
            "查Foo符號三ㅤ天後",   // arm M: U+3164 Hangul filler between 三 and 天（joined probe 剝除⇒ `三天後` 命中）

            "查Foo符號一千天後再跑",
            "查Foo符號三个月后再查",
            "查Foo符號" + "a".repeat(4000) + "等我放行",   // 4010 chars, arm L at tail

            "查Foo符號等他簽核",
            "查Foo符號等主管核對",
            "查Foo符號兩天後再測試",
            "查Foo符號30天後再跑",
            "查Foo符號半小時後移交",
            "查Foo符號十天後再查",
            // stage-11.1 追加（Re-review #11 A1，arms L/M/K 擴充顆粒；隔天系列與 之|以 後綴 infix）：
            "查Foo符號十一點後再看",
            "查Foo符號一個小時後再做",
            "查Foo符號两天后再跑",
            "查Foo符號三天以後再做",
            "查Foo符號一年之後再查",
            "查Foo符號幾個月後再看",
            "查Foo符號隔天再跑",
            "查Foo符號次日再跑",
            "查Foo符號翌日再檢",
            // #19 B3 stage-12.1 新增 terminal-position rule 負向釘（Re-review #12 A1 [P1/BLOCKER]，deny-by-fail-closed extraction）：
            // 最後一個 `符號|符号` 之後綴仍含 \p{L}|\p{N} 碼點 ⇒ 一律 DENIED/澄清（非 EN-delimited 相；additive deny-only）。
            // 未列舉 consent 名詞／星期／工作天偏移／隨時／以後 等 CJK 延後／同意／條件／後續子句，由此一條規則閉合（不再封閉枚舉身分／日期）：
            "查Foo符號等法務簽核",
            "查Foo符號等同事核對",
            "查Foo符號週五啟動",
            "查Foo符號三個工作天後啟動",
            "查Foo符號隨時可以",   // L-suffix；stage-12.1 之前為 FAST 級（隨時 非 arm K 顆粒）
            "查Foo符號以後再做",   // L-suffix；裸 `以後` 尾帶其他文字（arm H `再做` 亦命中，雙臂）
            "查Foo符號，一下",       // `一`/`下` 皆 \p{L}（Lo） ⇒ DENIED（stage-12.1 KDoc 所記 accepted over-deny，固化 pin）
        )) {
            val d = decide(text)
            assertEquals(text, RouteKind.DENIED, d.route)
            assertNull(text, d.toolCall)
            val before = backend.invocations.get()
            val result = dispatcher.decideAndDispatch(text, lspOn, sessionId = "s1", turnIndex = 1)
            assertEquals(text, RouteKind.DENIED, result.decision.route)
            assertNull(text, result.decision.toolCall)
            assertFalse(text, result.outcome is FastDispatchOutcome.Executed)
            assertEquals(text, before, backend.invocations.get())
        }
        assertEquals(13, backend.invocations.get())
    }
}

