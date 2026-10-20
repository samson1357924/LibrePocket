package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import dev.librepocket.provider.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.AbstractList
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2 safe slice (#19) orchestrator tests: `lsp.symbols` closed loop stages.
 *
 * Pure JVM (fake backend/policy/clock, no Android, no provider). The
 * turn-engine half (request tools → continuation → UI/transcript) is pinned
 * by `TurnControllerToolSliceTest`; the durable factory half by
 * `ToolSliceFactoryDurabilityTest`.
 */
class SafeToolSliceTest {

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))
    private val fixedMillis: Long = fixedClock.millis()

    private class CountingBackend(
        val symbols: List<LspTools.Symbol> = listOf(
            LspTools.Symbol("Foo:example", "function", "app/src/main/Example.kt", 1),
        ),
    ) : LspTools.Backend {
        val invocations = AtomicInteger(0)
        var lastLimit: Int = -1
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            lastLimit = limit
            return symbols.filter { query.isNotBlank() }.take(limit)
        }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private fun allowPolicy(vararg extra: PolicyRule): InMemoryPolicyStore =
        InMemoryPolicyStore(
            listOf(
                PolicyRule("chat.send:*", Verdict.ALLOW, 10),
                PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 10),
            ) + extra.toList(),
        )

    private fun slice(
        ctx: ProjectionContext = ProjectionContext(userSwitches = mapOf("lsp" to true)),
        policy: InMemoryPolicyStore = allowPolicy(),
        backend: LspTools.Backend = CountingBackend(),
        clock: Clock = fixedClock,
        timeoutMillisForTest: Long? = null,
        maxCallsPerTurn: Int = 1,
    ) = SafeToolSlice(
        ctxSupplier = { ctx },
        policy = policy,
        backend = backend,
        clock = clock,
        maxCallsPerTurn = maxCallsPerTurn,
    ).also { it.backendTimeoutMillisForTest = timeoutMillisForTest }

    private fun call(name: String = "lsp.symbols", args: String = """{"query":"Foo"}""", id: String = "call-1") =
        ToolCall(id = id, name = name, argumentsJson = args)

    private fun mutableSlice(
        switches: MutableMap<String, Boolean> = mutableMapOf("lsp" to true),
        policy: InMemoryPolicyStore = allowPolicy(),
        backend: CountingBackend = CountingBackend(),
    ) = SafeToolSlice(
        ctxSupplier = { ProjectionContext(userSwitches = switches) },
        policy = policy,
        backend = backend,
        clock = fixedClock,
    )

    @Test fun registryCountsPin41_1_40() {
        assertEquals(41, ToolRegistry.ALL.size)
        assertEquals(1, ToolRegistry.ALL.count { it.executionReady })
        assertEquals("lsp.symbols", ToolRegistry.ALL.single { it.executionReady }.name)
        assertTrue(ToolRegistry.ALL.filter { it.name != "lsp.symbols" }.all { !it.executionReady })
    }

    @Test fun offeredToolsEmptyWhenSwitchOff() {
        val s = slice(ctx = ProjectionContext())
        assertTrue(s.offeredTools().isEmpty())
    }

    @Test fun offeredToolsSingleAcrossFlavors() {
        for (flavor in listOf(Flavor.PLAY, Flavor.FOSS, Flavor.GITHUB)) {
            val ctx = ProjectionContext(flavor = flavor, userSwitches = mapOf("lsp" to true))
            val offered = slice(ctx = ctx).offeredTools()
            assertEquals("$flavor", listOf("lsp.symbols"), offered.map { it.name })
            // Offered schema is the registry schema (JSON object, requires query).
            val schema = Json.parseToJsonElement(offered.single().jsonSchema).jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertTrue(schema["properties"]!!.jsonObject.containsKey("query"))
        }
    }

    @Test fun happyPathEchoesQueryWithFakeClock() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val results = s.execute(listOf(call(args = """{"query":"Foo","limit":10}""")))
        assertEquals(1, results.size)
        val r = results.single()
        assertFalse(r.denied)
        assertEquals("call-1", r.callId)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals("false", body["denied"]!!.jsonPrimitive.content)
        assertEquals("Foo", body["query"]!!.jsonPrimitive.content)
        assertEquals(fixedMillis, body["clockMillis"]!!.jsonPrimitive.content.toLong())
        assertEquals("false", body["fromStub"]!!.jsonPrimitive.content)
        assertEquals(1, backend.invocations.get())
        assertEquals(10, backend.lastLimit)
    }

    @Test fun stubBackendMarksFromStub() = runBlocking {
        val s = slice(backend = LspTools.StubBackend)
        val r = s.execute(listOf(call(args = """{"query":"Foo"}"""))).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals("true", body["fromStub"]!!.jsonPrimitive.content)
        val symbols = body["symbols"]!!.jsonArray
        assertEquals(1, symbols.size)
        assertEquals("Foo:example", symbols[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun limitClampedNotRejected() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val r = s.execute(listOf(call(args = """{"query":"Foo","limit":500}"""))).single()
        assertFalse("limit 500 must clamp to 200, was $r", r.denied)
        assertEquals(LspTools.MAX_LIMIT, backend.lastLimit)
        assertEquals(1, backend.invocations.get())
    }

    @Test fun schemaInvalidCasesDenyWithZeroSideEffects() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val bad = listOf(
            "{oops",
            "[]",
            "null",
            "{}",
            """{"query":""}""",
            """{"query":"   "}""",
            """{"query":123}""",
            """{"query":null}""",
            """{"query":"x","limit":"5"}""",
            """{"query":"x","limit":true}""",
            """{"query":"x","pathPrefix":5}""",
            """{"query":"x","limit":{}}""",
            // F3(b): ISO-control INSIDE the model-controlled `query` (the
            // query-layer check, distinct from the pathPrefix \u0000/\u0001
            // pins below in the traversal tests) denies schema-invalid.
            "{\"query\":\"a\\u0001b\"}",
        )
        for ((i, args) in bad.withIndex()) {
            val r = s.execute(listOf(call(args = args, id = "bad-$i"))).single()
            assertTrue(args, r.denied)
            assertEquals(args, SafeToolSlice.DenyCodes.SCHEMA_INVALID, r.denyCode)
            assertEquals(args, "bad-$i", r.callId)
            val body = Json.parseToJsonElement(r.resultJson).jsonObject
            assertEquals(args, "true", body["denied"]!!.jsonPrimitive.content)
            assertEquals(args, "bad-$i", body["callId"]!!.jsonPrimitive.content)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun unknownAndUnreadyToolsDenyWithZeroSideEffects() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val unknown = s.execute(listOf(call(name = "nope.tool"))).single()
        assertTrue(unknown.denied)
        assertEquals(SafeToolSlice.DenyCodes.UNKNOWN_TOOL, unknown.denyCode)
        // Projected-but-unwired tools are NOT_READY (never dispatched).
        val unready = s.execute(listOf(call(name = "shell.exec", args = """{"argv":["ls"]}"""))).single()
        assertTrue(unready.denied)
        assertEquals(SafeToolSlice.DenyCodes.NOT_READY, unready.denyCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun policyDenyAndAskDenyWithZeroSideEffects() = runBlocking {
        val backend = CountingBackend()
        val denyPolicy = InMemoryPolicyStore(
            listOf(
                PolicyRule("chat.send:*", Verdict.ALLOW, 10),
                PolicyRule("tool.exec:lsp.symbols", Verdict.DENY, 10),
            ),
        )
        val denied = slice(policy = denyPolicy, backend = backend).execute(listOf(call())).single()
        assertTrue(denied.denied)
        assertEquals(SafeToolSlice.DenyCodes.POLICY_DENIED, denied.denyCode)
        assertTrue(denied.resultJson.contains("DENY"))
        val askPolicy = InMemoryPolicyStore(
            listOf(PolicyRule("tool.exec:lsp.symbols", Verdict.ASK, 10)),
        )
        val asked = slice(policy = askPolicy, backend = backend).execute(listOf(call())).single()
        assertTrue(asked.denied)
        assertEquals(SafeToolSlice.DenyCodes.POLICY_DENIED, asked.denyCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun latestScopeRecheckDeniesAfterSwitchFlip() = runBlocking {
        val backend = CountingBackend()
        val switches = mutableMapOf("lsp" to true)
        val s = mutableSlice(switches = switches, backend = backend)
        assertEquals(listOf("lsp.symbols"), s.offeredTools().map { it.name })
        // Flip between offer and execution: execution re-reads the supplier.
        switches["lsp"] = false
        assertTrue(s.offeredTools().isEmpty())
        val r = s.execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.SCOPE_DENIED, r.denyCode)
        assertTrue(r.resultJson.contains(DenyReason.USER_DISABLED.name))
        assertEquals(0, backend.invocations.get())
    }

    @Test fun budgetExceededDeniesWholeRoundWithZeroSideEffects() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val calls = listOf(
            call(id = "call-1", args = """{"query":"A"}"""),
            call(id = "call-2", args = """{"query":"B"}"""),
        )
        val results = s.execute(calls)
        assertEquals(2, results.size)
        assertTrue(results.all { it.denied })
        assertTrue(results.all { it.denyCode == SafeToolSlice.DenyCodes.BUDGET_EXCEEDED })
        assertEquals(listOf("call-1", "call-2"), results.map { it.callId })
        // Pairing holds for budget denials too: continuation carries both.
        val msgs = s.continuationMessages(calls, results)
        assertEquals(3, msgs.size)
        assertEquals("call-1", msgs[1].toolCallId)
        assertEquals("call-2", msgs[2].toolCallId)
        assertTrue(msgs[1].text.contains(SafeToolSlice.DenyCodes.BUDGET_EXCEEDED))
        assertTrue(msgs[2].text.contains(SafeToolSlice.DenyCodes.BUDGET_EXCEEDED))
        assertEquals(0, backend.invocations.get())
    }

    @Test fun continuationMessagesPairCallsAndResults() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val calls = listOf(call(id = "call-1"), call(id = "call-2", args = "{bad"))
        // One ok + one schema-denied single-call round (budget is 1).
        val ok = s.execute(listOf(calls[0])).single()
        val denied = s.execute(listOf(calls[1])).single()
        assertTrue(denied.denied)
        val msgs = s.continuationMessages(calls, listOf(ok, denied))
        assertEquals(3, msgs.size)
        assertEquals("assistant", msgs[0].role)
        // Benign args keep their exact shape; UNPARSEABLE args are replaced
        // with the fixed bounded object — providers re-parse this echo
        // (validateToolPairing), so a raw `{bad` ride would fail the
        // continuation non-retryably.
        val echoed = msgs[0].toolCalls
        assertEquals(2, echoed.size)
        assertEquals("""{"query":"Foo"}""", echoed[0].argumentsJson)
        assertEquals("""{"echo":"args redacted/capped"}""", echoed[1].argumentsJson)
        assertEquals("tool", msgs[1].role)
        assertEquals("call-1", msgs[1].toolCallId)
        assertEquals(ok.resultJson, msgs[1].text)
        assertEquals("tool", msgs[2].role)
        assertEquals("call-2", msgs[2].toolCallId)
        assertTrue(msgs[2].text.contains(SafeToolSlice.DenyCodes.SCHEMA_INVALID))
        assertEquals(1, backend.invocations.get())
    }

    @Test fun wiredToolIsReadOnlyPin() {
        // Tripwire for the slice READ-only gate: if the registry re-grades
        // lsp.symbols, this fails. The SCOPE_DENIED/NEED_CONFIRM behavior
        // branch itself is not directly executable (static registry), the
        // defense-in-depth check in SafeToolSlice.executeOne covers it.
        val tool = ToolRegistry.find(SafeToolSlice.TOOL_NAME)
        assertNotNull(tool)
        assertEquals(dev.librepocket.tool.SideEffect.READ, tool!!.sideEffect)
        assertTrue(tool.executionReady)
    }

    @Test fun throwingBackendMapsToTypedExecError() = runBlocking {
        val throwing = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                throw IllegalStateException("boom")
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val s = slice(backend = throwing)
        val r = s.execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_ERROR, r.denyCode)
        assertEquals("call-1", r.callId)
        // Typed denial body carries the pairing (code + callId), not the throw.
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals("true", body["denied"]!!.jsonPrimitive.content)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_ERROR, body["code"]!!.jsonPrimitive.content)
        assertEquals("call-1", body["callId"]!!.jsonPrimitive.content)
    }

    @Test fun throwingBackendWithSecretMessageDoesNotLeak() = runBlocking {
        val marker = "backend-error-marker-42"
        val throwing = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                throw IllegalStateException(marker)
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = slice(backend = throwing).execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_ERROR, r.denyCode)
        // Fixed receipt: backend message never interpolates into the wire JSON.
        assertFalse(r.resultJson.contains(marker))
        assertFalse(r.resultJson.lowercase().contains("bearer"))
    }

    @Test fun pathPrefixTraversalDeniesWithZeroSideEffects() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val bad = listOf(
            """{"query":"Foo","pathPrefix":"../etc"}""",
            """{"query":"Foo","pathPrefix":"a/../b"}""",
            """{"query":"Foo","pathPrefix":"/etc/passwd"}""",
            """{"query":"Foo","pathPrefix":"a\\b"}""",
            "{\"query\":\"Foo\",\"pathPrefix\":\"a\\u0000b\"}",
        )
        for ((i, args) in bad.withIndex()) {
            val r = s.execute(listOf(call(args = args, id = "trav-$i"))).single()
            assertTrue(args, r.denied)
            assertEquals(args, SafeToolSlice.DenyCodes.SCHEMA_INVALID, r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun pathPrefixTrustedFormPinCoversFullPredicateSet() = runBlocking {
        // Coverage completion for the shared deny predicate: the traversal
        // block above pins `..`, leading `/`, backslash and NUL; these inputs
        // pin the remaining isSafeInput/NFKC elements — each denied by a
        // distinct condition that no other pin covers today.
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val bad = listOf(
            // `a%2e%2e`: NFKC-stable ASCII; no literal `..` anywhere, so the
            // percent branch of isSafeInput is the discriminating denial.
            """{"query":"Foo","pathPrefix":"a%2e%2e"}""",
            // `a\u0001b`: not NUL; the non-NUL ISO-control branch denies
            // (the NUL pin above does not reach this path).
            "{\"query\":\"Foo\",\"pathPrefix\":\"a\\u0001b\"}",
            // `a\uFF05b` (fullwidth `％` U+FF05): NFKC-normalizes to `a%b`,
            // so the NFKC-equality check denies before isSafeInput — no
            // literal `%`/ISO-control needed.
            "{\"query\":\"Foo\",\"pathPrefix\":\"a\\uFF05b\"}",
        )
        for ((i, args) in bad.withIndex()) {
            val r = s.execute(listOf(call(args = args, id = "trust-$i"))).single()
            assertTrue(args, r.denied)
            assertEquals(args, SafeToolSlice.DenyCodes.SCHEMA_INVALID, r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
        // Benign buffer: the plain relative scope still runs, so the pins
        // discriminate the predicate rather than a blanket deny.
        val ok = s.execute(
            listOf(call(args = """{"query":"Foo","pathPrefix":"a/b"}""", id = "trust-ok")),
        ).single()
        assertFalse(ok.denied)
        assertEquals(1, backend.invocations.get())
    }

    @Test fun pathPrefixBenignValuesPass() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        // Omitted, null-equivalent empty, and a relative sub-scope all pass.
        val okMissing = s.execute(listOf(call(args = """{"query":"Foo"}"""))).single()
        assertFalse(okMissing.denied)
        val okEmpty = s.execute(listOf(call(args = """{"query":"Foo","pathPrefix":""}""", id = "empty"))).single()
        assertFalse(okEmpty.denied)
        val okSub = s.execute(listOf(call(args = """{"query":"Foo","pathPrefix":"a/b"}""", id = "sub"))).single()
        assertFalse(okSub.denied)
        assertEquals(3, backend.invocations.get())
    }

    @Test fun policyFlipAfterOfferDenies() = runBlocking {
        val backend = CountingBackend()
        val policy = allowPolicy()
        val switches = mutableMapOf("lsp" to true)
        val s = SafeToolSlice(
            ctxSupplier = { ProjectionContext(userSwitches = switches) },
            policy = policy,
            backend = backend,
            clock = fixedClock,
        )
        assertEquals(listOf("lsp.symbols"), s.offeredTools().map { it.name })
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.DENY, 10))
        val r = s.execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.POLICY_DENIED, r.denyCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun denyJsonCarriesNoSecrets() = runBlocking {
        // 形狀抽查（sk-/bearer 子串）：精確的洩漏保證見
        // throwingBackendWithSecretMessageDoesNotLeak。
        val backend = CountingBackend()
        val denyPolicy = InMemoryPolicyStore(
            listOf(PolicyRule("tool.exec:lsp.symbols", Verdict.DENY, 10)),
        )
        val s = slice(policy = denyPolicy, backend = backend)
        val policyDenied = s.execute(listOf(call(id = "policy-1"))).single()
        assertEquals(SafeToolSlice.DenyCodes.POLICY_DENIED, policyDenied.denyCode)
        val schemaDenied = s.execute(listOf(call(args = "{bad", id = "bad-1"))).single()
        assertEquals(SafeToolSlice.DenyCodes.SCHEMA_INVALID, schemaDenied.denyCode)
        val unreadyDenied =
            s.execute(listOf(call(name = "shell.exec", args = """{"argv":["ls"]}""", id = "unready-1"))).single()
        assertEquals(SafeToolSlice.DenyCodes.NOT_READY, unreadyDenied.denyCode)
        val knownCodes = setOf(
            SafeToolSlice.DenyCodes.UNKNOWN_TOOL,
            SafeToolSlice.DenyCodes.NOT_READY,
            SafeToolSlice.DenyCodes.SCOPE_DENIED,
            SafeToolSlice.DenyCodes.SCHEMA_INVALID,
            SafeToolSlice.DenyCodes.POLICY_DENIED,
            SafeToolSlice.DenyCodes.NEED_CONFIRM,
            SafeToolSlice.DenyCodes.EXEC_DENIED,
            SafeToolSlice.DenyCodes.EXEC_ERROR,
            SafeToolSlice.DenyCodes.BUDGET_EXCEEDED,
        )
        val results = listOf(policyDenied, schemaDenied, unreadyDenied)
        assertTrue(results.all { it.denied })
        for (r in results) {
            assertTrue(r.denyCode in knownCodes)
            assertFalse(r.resultJson.lowercase().contains("sk-"))
            assertFalse(r.resultJson.lowercase().contains("bearer"))
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun longQueryAndSymbolsTruncateToCap() = runBlocking {
        val longBackend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol("N".repeat(2000), "K".repeat(2000), "P".repeat(2000), 7))
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val s = slice(backend = longBackend)
        val longQuery = "Q".repeat(2000)
        val r = s.execute(listOf(call(args = """{"query":"$longQuery"}"""))).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        val echoQuery = body["query"]!!.jsonPrimitive.content
        assertTrue(echoQuery.startsWith("Q".repeat(SafeToolSlice.MAX_STRING_CHARS)))
        assertTrue(echoQuery.contains("[truncated 2000→${SafeToolSlice.MAX_STRING_CHARS}]"))
        val sym = body["symbols"]!!.jsonArray.single().jsonObject
        for (field in listOf("name", "kind", "path")) {
            val v = sym[field]!!.jsonPrimitive.content
            assertTrue(field, v.contains("[truncated 2000→${SafeToolSlice.MAX_STRING_CHARS}]"))
        }
    }

    @Test fun limitLowClampsToOneAndExecutes() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        var n = 0
        for (args in listOf("""{"query":"x","limit":0}""", """{"query":"x","limit":-5}""")) {
            val r = s.execute(listOf(call(args = args, id = "low-$n"))).single()
            assertFalse(args, r.denied)
            assertEquals(args, 1, backend.lastLimit)
            n++
        }
        assertEquals(2, backend.invocations.get())
    }

    @Test fun limitDefaultsToFiftyWhenOmitted() = runBlocking {
        val backend = CountingBackend()
        val r = slice(backend = backend).execute(listOf(call())).single()
        assertFalse(r.denied)
        assertEquals(LspTools.DEFAULT_LIMIT, backend.lastLimit)
    }

    @Test fun limitFloatAndExplicitNullDeny() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        for ((i, args) in listOf(
            """{"query":"x","limit":1.5}""",
            """{"query":"x","limit":null}""",
        ).withIndex()) {
            val r = s.execute(listOf(call(args = args, id = "lim-$i"))).single()
            assertTrue(args, r.denied)
            assertEquals(args, SafeToolSlice.DenyCodes.SCHEMA_INVALID, r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun longCallIdCappedInOkReceipt() = runBlocking {
        val backend = CountingBackend()
        val longId = "I".repeat(2000)
        val r = slice(backend = backend).execute(listOf(call(id = longId))).single()
        assertFalse(r.denied)
        // Internal pairing keeps the full id; the wire receipt is capped.
        assertEquals(longId, r.callId)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals(SafeToolSlice.MAX_STRING_CHARS, body["callId"]!!.jsonPrimitive.content.length)
        assertEquals("lsp.symbols", body["tool"]!!.jsonPrimitive.content)
        assertEquals(1, backend.invocations.get())
    }

    @Test fun denyDetailTakesRedactionPipelineNotBareCap() = runBlocking {
        // The detail can embed a model-controlled string; it must be redacted
        // centrally like every other wire echo. The unknown-tool path is the
        // reachable shape, and its raw name is deliberately passed to deny().
        val backend = CountingBackend()
        val r = slice(backend = backend).execute(
            listOf(call(name = "tool.a.dev@corp.example.com")),
        ).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.UNKNOWN_TOOL, r.denyCode)
        val detail = Json.parseToJsonElement(r.resultJson).jsonObject["detail"]!!.jsonPrimitive.content
        assertFalse(detail.contains("dev@corp.example.com"))
        assertTrue(detail.contains("⟦REDACTED:EMAIL⟧"))
        assertEquals(0, backend.invocations.get())
    }

    @Test fun longRedactedCallIdsThatSharePrefixStayDistinct() = runBlocking {
        // Regression pin for capped pairing-id collisions: two long raw ids
        // sharing the whole cap-length prefix previously produced ONE
        // redacted pairing key, so the second execution candidate was dropped
        // as a duplicate. The deterministic hash suffix must keep both ids
        // distinct, bounded, and identical between the turn-engine seam and
        // the continuation wire form.
        val prefix = "I".repeat(SafeToolSlice.MAX_STRING_CHARS + 100)
        val rawA = "${prefix}A"
        val rawB = "${prefix}B"
        val idA = SafeToolSlice.redactedPairingId(rawA)
        val idB = SafeToolSlice.redactedPairingId(rawB)
        assertNotEquals(idA, idB)
        assertTrue(idA.length <= SafeToolSlice.MAX_STRING_CHARS)
        assertTrue(idB.length <= SafeToolSlice.MAX_STRING_CHARS)
        // Deterministic: the same raw id always maps to the same wire id.
        assertEquals(idA, SafeToolSlice.redactedPairingId(rawA))
        assertTrue("suffix identifies the full redacted id", Regex("#[0-9a-f]{8}$").containsMatchIn(idA))
        val s = slice()
        val calls = listOf(call(id = rawA, args = """{"query":"A"}"""), call(id = rawB, args = """{"query":"B"}"""))
        val results = listOf(
            SafeToolSlice.ToolResult(rawA, "lsp.symbols", "{}"),
            SafeToolSlice.ToolResult(rawB, "lsp.symbols", "{}"),
        )
        val msgs = s.continuationMessages(calls, results)
        assertEquals(idA, msgs[0].toolCalls[0].id)
        assertEquals(idB, msgs[0].toolCalls[1].id)
        assertEquals(idA, msgs[1].toolCallId)
        assertEquals(idB, msgs[2].toolCallId)
        // The ok receipt echoes the same bounded id, so audit and pairing agree.
        val receipt = s.execute(listOf(call(id = rawA))).single()
        assertEquals(idA, Json.parseToJsonElement(receipt.resultJson).jsonObject["callId"]!!.jsonPrimitive.content)
    }

    @Test fun throwingCtxSupplierOffersNoToolsAndDeniesTyped() = runBlocking {
        val backend = CountingBackend()
        val marker = "ctx-supplier-marker-42"
        val s = SafeToolSlice(
            ctxSupplier = { throw IllegalStateException(marker) },
            policy = allowPolicy(),
            backend = backend,
            clock = fixedClock,
        )
        assertTrue("projection failure means pure chat", s.offeredTools().isEmpty())
        val r = s.execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.SCOPE_DENIED, r.denyCode)
        assertFalse(r.resultJson.contains(marker))
        assertEquals("projection unavailable", Json.parseToJsonElement(r.resultJson).jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun backendTimeoutMapsToTypedExecDenied() = runBlocking {
        val started = AtomicInteger(0)
        val slow = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
                started.incrementAndGet()
                delay(60_000)
                return emptyList()
            }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = slice(backend = slow, timeoutMillisForTest = 50).execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_DENIED, r.denyCode)
        assertEquals("backend timed out", Json.parseToJsonElement(r.resultJson).jsonObject["detail"]!!.jsonPrimitive.content)
        assertEquals(1, started.get())
        // A timeout is a paired denial for the model call, not a lost pairing.
        assertEquals("call-1", r.callId)
        assertEquals("lsp.symbols", r.name)
    }

    @Test fun cancelInsideBackendPropagatesAsCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicInteger(0)
        val thrown = CompletableDeferred<Throwable?>()
        val gated = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val job = launch(Dispatchers.Default) {
            try {
                // A huge annotation override keeps the production timer from
                // masking a swallowed external cancellation.
                slice(backend = gated, timeoutMillisForTest = 300_000).execute(listOf(call()))
                thrown.complete(null)
            } catch (e: CancellationException) {
                thrown.complete(e)
                throw e
            }
        }
        withTimeout(5_000) { started.await() }
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals("backend observed cancellation", 1, cancelled.get())
        // The slice must RETHROW, not turn the cancel into a denial: observe
        // the exception the child received before it completed cancellation.
        val error = withTimeout(5_000) { thrown.await() }
        assertTrue("execute must rethrow cancellation, was $error", error is CancellationException)
    }

    @Test fun limitDropCountsInOmittedAndSymbolsSnapshotIsTakenOnce() = runBlocking {
        // A backend returning more than MAX_LIMIT violates its own limit
        // contract; the slice still bounds the wire copy, reports the cap drop
        // in omittedCount/truncated, and iterates the backend list exactly
        // once (the redaction + raw-field passes share one snapshot).
        val all = List(LspTools.MAX_LIMIT + 7) { LspTools.Symbol("S$it", "k", "p", it) }
        val iterations = AtomicInteger(0)
        val backing = object : AbstractList<LspTools.Symbol>() {
            override val size: Int get() = all.size
            // Backing random access must never be used after the snapshot; if
            // the raw-field pass re-reads `outcome.symbols[idx]`, this throws.
            override fun get(index: Int): LspTools.Symbol = error("backing get must not be used")
            override fun iterator(): MutableIterator<LspTools.Symbol> {
                iterations.incrementAndGet()
                return all.toMutableList().iterator()
            }
        }
        val backend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> = backing
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = slice(backend = backend).execute(
            listOf(call(args = """{"query":"Foo","limit":${LspTools.MAX_LIMIT}}""")),
        ).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals(LspTools.MAX_LIMIT, body["returnedCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, body["omittedCount"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["truncated"]!!.jsonPrimitive.content)
        assertEquals("backend list must be snapshotted once", 1, iterations.get())
        // The emitted lines come from the snapshot iterator, not a second
        // random-access read of the backend list.
        val lines = body["symbols"]!!.jsonArray.map { it.jsonObject["line"]!!.jsonPrimitive.content.toInt() }
        assertEquals((0 until LspTools.MAX_LIMIT).toList(), lines)
    }

    @Test fun throwingBackendListIteratorMapsToTypedExecError() = runBlocking {
        // Contract: non-cancel backend failures are typed EXEC_ERROR, never an
        // escaped turn crash. The bounded snapshot now happens inside ok()'s
        // typed boundary, so a lazy backend List that throws from size or
        // iterator() must not escape execute().
        val backend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                object : AbstractList<LspTools.Symbol>() {
                    override val size: Int get() = LspTools.MAX_LIMIT + 1
                    override fun get(index: Int): LspTools.Symbol = error("get must not be used")
                    override fun iterator(): MutableIterator<LspTools.Symbol> =
                        throw IllegalStateException("iterator boom")
                }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = slice(backend = backend).execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_ERROR, r.denyCode)
        assertFalse(r.resultJson.contains("iterator boom"))
    }

    @Test fun limitOmittedAndShrinkDropsAccumulateInOmittedCount() = runBlocking {
        // Both drop sources at once: 7 over MAX_LIMIT plus whatever the total
        // result budget drops. omittedCount must report the sum and the result
        // must stay under MAX_RESULT_CHARS.
        val fat = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                List(LspTools.MAX_LIMIT + 7) {
                    LspTools.Symbol("N".repeat(400), "K".repeat(400), "P".repeat(400), it)
                }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = slice(backend = fat).execute(
            listOf(call(args = """{"query":"Foo","limit":${LspTools.MAX_LIMIT}}""")),
        ).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        val returned = body["returnedCount"]!!.jsonPrimitive.content.toInt()
        val omitted = body["omittedCount"]!!.jsonPrimitive.content.toInt()
        assertTrue(returned in 1..LspTools.MAX_LIMIT)
        assertEquals(7 + (LspTools.MAX_LIMIT - returned), omitted)
        assertEquals("true", body["truncated"]!!.jsonPrimitive.content)
        assertTrue(r.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS)
    }

    @Test fun exactMaxLimitBoundaryReportsNoOmission() = runBlocking {
        val backend = CountingBackend(symbols = List(LspTools.MAX_LIMIT) { LspTools.Symbol("S$it", "k", "p", it) })
        val r = slice(backend = backend).execute(
            listOf(call(args = """{"query":"Foo","limit":${LspTools.MAX_LIMIT}}""")),
        ).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals(LspTools.MAX_LIMIT, body["returnedCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, body["omittedCount"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", body["truncated"]!!.jsonPrimitive.content)
    }

    @Test fun hookThrowMapsToTypedExecErrorWithoutLeak() = runBlocking {        // Neutral marker (deliberately not a secret-shaped literal): the pin is
        // the FIXED receipt — the hook's message never reaches the wire, same
        // rule as throwingBackendWithSecretMessageDoesNotLeak. Secret-shaped
        // sentinels trip the Gitleaks generic-api-key rule here, and the
        // assertion power does not depend on that shape.
        val marker = "hook-throw-marker-42"
        val backend = CountingBackend()
        val s = slice(backend = backend)
        s.beforeExecuteForTest = { throw IllegalStateException(marker) }
        val r = s.execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_ERROR, r.denyCode)
        assertEquals("call-1", r.callId)
        assertFalse(r.resultJson.contains(marker))
        assertEquals(0, backend.invocations.get())
    }

    @Test fun unknownToolDetailRedactsModelControlledName() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val r = s.execute(listOf(call(name = "tool.a.dev@corp.example.com"))).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.UNKNOWN_TOOL, r.denyCode)
        // The detail rides the wire: the model-controlled name is redacted
        // like every other echo (no raw pass-through past the pipeline).
        assertFalse(r.resultJson.contains("dev@corp.example.com"))
        assertTrue(r.resultJson.contains("⟦REDACTED:EMAIL⟧"))
        assertEquals(0, backend.invocations.get())
    }

    @Test fun pathPrefixColonFormsAreSchemaRejected() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        // Scheme/drive-absolute scopes must never name another root.
        for (bad in listOf("c:/Users/x", "file:/etc", "z:foo")) {
            val r = s.execute(
                listOf(call(args = """{"query":"Foo","pathPrefix":"$bad"}""")),
            ).single()
            assertTrue(bad, r.denied)
            assertEquals(bad, SafeToolSlice.DenyCodes.SCHEMA_INVALID, r.denyCode)
        }
        assertEquals(0, backend.invocations.get())
    }

    @Test fun resultBudgetDropsSymbolsToTotalCap() = runBlocking {
        // 200 × 400-char fields ⇒ ~250K serialized; the shrink loop must drop
        // symbols until the total fits (P2 result-total budget), keeping the
        // JSON paired, counted, and under MAX_RESULT_CHARS.
        val fat = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                List(LspTools.MAX_LIMIT) {
                    LspTools.Symbol("N".repeat(400), "K".repeat(400), "P".repeat(400), it)
                }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val s = slice(backend = fat)
        val r = s.execute(
            listOf(call(args = """{"query":"Foo","limit":${LspTools.MAX_LIMIT}}""")),
        ).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals(r.callId, body["callId"]!!.jsonPrimitive.content)
        assertEquals("true", body["truncated"]!!.jsonPrimitive.content)
        val returned = body["returnedCount"]!!.jsonPrimitive.content.toInt()
        val omitted = body["omittedCount"]!!.jsonPrimitive.content.toInt()
        assertTrue(returned in 1..LspTools.MAX_LIMIT)
        assertEquals(LspTools.MAX_LIMIT - returned, omitted)
        assertTrue("result JSON must stay under the total cap", r.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS)
        assertEquals(returned, body["symbols"]!!.jsonArray.size)
    }

    @Test fun resultBudgetSingleSymbolFloorStaysUnderTotalCap() = runBlocking {
        // Worst-case single-symbol floor: ALL three fields at MAX_STRING_CHARS
        // with full JSON-escape doubling (500 `"` chars each) — still ≪ 20K.
        // This pins the cross-constant coupling that makes the over-budget
        // guard unreachable; drift on either constant fails this pin, not
        // production silently.
        val cap = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(
                    LspTools.Symbol(
                        "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                        "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                        "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                        1,
                    ),
                )
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val s = slice(backend = cap)
        val r = s.execute(listOf(call(args = """{"query":"Foo","limit":1}"""))).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals("false", body["truncated"]!!.jsonPrimitive.content)
        assertTrue("single-symbol build must stay under the total cap", r.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS)
        assertTrue("floor must retain a ≥2× margin (cross-constant coupling)", r.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS / 2)
    }

    @Test fun postRedactionInflationMarksTruncatedField() = runBlocking {
        // Truncation is decided on POST-redaction lengths: a raw field ≤
        // MAX_STRING_CHARS can exceed the cap after redaction inflates each
        // match into a marker. `geo:1,2` (7 chars) redacts to
        // `⟦REDACTED:GEO⟧` (+7 per match), so 71 raw pairs = 497 chars stay
        // ≤ 500 RAW (a raw-length decision would NOT truncate) while
        // inflating to 71 × 14 = 994 post-redaction — the field must be
        // capped with the truncation suffix AND turn the `truncated` flag,
        // never silently inflate past the wire budget.
        val backend = CountingBackend(
            symbols = listOf(
                LspTools.Symbol("geo:1,2".repeat(71), "function", "app/src/main/Example.kt", 1),
            ),
        )
        val s = slice(backend = backend)
        val r = s.execute(listOf(call())).single()
        assertFalse(r.denied)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        assertEquals("true", body["truncated"]!!.jsonPrimitive.content)
        val symbols = body["symbols"]!!.jsonArray
        assertEquals(1, symbols.size)
        val name = symbols[0].jsonObject["name"]!!.jsonPrimitive.content
        // Redaction fired and the inflated value got the truncation suffix.
        assertTrue(name.contains("⟦REDACTED:GEO⟧"))
        assertTrue(name.contains("…[truncated "))
        assertTrue("capped field stays within MAX_STRING_CHARS", name.length <= SafeToolSlice.MAX_STRING_CHARS + 40)
    }

    @Test fun continuationEchoesRedactNameAndArguments() = runBlocking {
        val s = slice()
        val calls = listOf(
            ToolCall(
                id = "call-1",
                name = "tool.a.dev@corp.example.com",
                argumentsJson = """{"query":"mail me at who@corp.example.com"}""",
            ),
        )
        val results = listOf(SafeToolSlice.ToolResult("call-1", "n", "{}"))
        val msgs = s.continuationMessages(calls, results)
        assertEquals(2, msgs.size)
        val tc = msgs[0].toolCalls.single()
        // No raw model-controlled string rides the echo past the pipeline.
        assertFalse(tc.name.contains("dev@corp.example.com"))
        assertFalse(tc.argumentsJson.contains("who@corp.example.com"))
        assertTrue(tc.argumentsJson.contains("⟦REDACTED:EMAIL⟧"))
        // Benign calls keep their shape exactly (redaction/cap are identity).
        val benign = listOf(call(id = "call-2"))
        val benignMsgs = s.continuationMessages(benign, listOf(SafeToolSlice.ToolResult("call-2", "n", "{}")))
        assertEquals(benign, benignMsgs[0].toolCalls)
    }

    @Test fun continuationArgsEchoStaysWireValidWithSecretPatternsInside() = runBlocking {
        val s = slice()
        // Several redaction rules (URL token, geo coords, device ids) swallow
        // the terminating quote/brace when applied to RAW args text; the
        // structural (parse-then-redact) echo must keep the JSON object
        // valid — provider adapters re-parse it before serialize.
        // The fake token value is deliberately low-entropy: it must survive
        // CI secret scanners (gitleaks generic-api-key: stopword adjacency
        // + captured-value Shannon entropy ≥ ~3.5) while still exercising
        // the URL-token redaction rule, whose match needs only the `token=`
        // key, not the value's randomness.
        val calls = listOf(
            call(
                id = "call-1",
                args = """{"query":"check https://x.com?token=url-demo-00 and geo:47.6,?122.3;? imei=490154203237518"}""",
            ),
        )
        val msgs = s.continuationMessages(calls, listOf(SafeToolSlice.ToolResult("call-1", "n", "{}")))
        val echo = msgs[0].toolCalls.single().argumentsJson
        val echoObject = Json.parseToJsonElement(echo).jsonObject
        assertTrue(echoObject.containsKey("query"))
        assertFalse(echo.contains("url-demo-00"))
        assertFalse(echo.contains("490154203237518"))
    }

    @Test fun continuationArgsEchoIsBoundedForOversizedArgs() = runBlocking {
        val s = slice()
        // Legal input size for execution (parseArgs' gross cap is
        // MAX_INPUT_CHARS * 4 = 16K; the echo cap is tighter — the
        // post-redaction form must fit MAX_INPUT_CHARS) — the wire-safe echo
        // must stay a bounded, still-valid JSON object (fallback, never a
        // corrupted `take`-cut string), and the final echo must not exceed
        // the echo budget at all.
        val longArgs = """{"query":"${"N".repeat(SafeToolSlice.MAX_INPUT_CHARS)}"}"""
        val calls = listOf(call(id = "call-1", args = longArgs))
        val msgs = s.continuationMessages(calls, listOf(SafeToolSlice.ToolResult("call-1", "n", "{}")))
        val echo = msgs[0].toolCalls.single().argumentsJson
        val echoObject = Json.parseToJsonElement(echo).jsonObject
        assertTrue("echo must stay a bounded valid JSON object", echoObject.size == 1 && echoObject.containsKey("echo"))
        assertFalse(echo.contains("N".repeat(64)))
        assertTrue("echo must stay within MAX_INPUT_CHARS", echo.length <= SafeToolSlice.MAX_INPUT_CHARS)
    }

    @Test fun continuationArgsEchoFailsClosedOnDeepNesting() = runBlocking {
        // The echo parses the model-controlled argument string directly, so
        // embedded JSON is NOT bounded by the provider wire frame parser
        // (MiniJson.MAX_DEPTH applies to the wire frame, not to a JSON string
        // carried inside it). The pre-parse depth fan-close (same char-scan
        // bound as parseArgs: depth ≤ 32) must trip BEFORE any recursive
        // walk — otherwise a within-size, deeply nested argument would reach
        // the recursive redaction on a stack. Pinned with depth-1000 input
        // that is BOTH within the 16K gross size cap AND small enough that
        // its (valid but oversized-depth) echo would fit MAX_INPUT_CHARS, so
        // the ONLY possible gate is the depth guard: any regression that
        // removes the guard parses + redacts normally and fails this pin.
        val s = slice()
        val deep = "[".repeat(1000) + "1" + "]".repeat(1000)
        val calls = listOf(call(id = "call-1", args = deep))
        val msgs = s.continuationMessages(calls, listOf(SafeToolSlice.ToolResult("call-1", "n", "{}")))
        val echo = msgs[0].toolCalls.single().argumentsJson
        val echoObject = Json.parseToJsonElement(echo).jsonObject
        assertEquals(1, echoObject.size)
        assertEquals("args redacted/capped", echoObject["echo"]!!.jsonPrimitive.content)
        // No nested structure may survive the fan-close.
        assertFalse(echo.contains("["))
        assertFalse(echo.contains("]"))
    }

    @Test fun continuationArgsEchoRedactsSecretNamedMembers() = runBlocking {
        // Isolated key/value redaction cannot catch secret-keyed members: a
        // bare key name never matches the secret rules (they need a `:`/`=`)
        // and a plain value carries no secret shape — so the structural walk
        // must be KEY-AWARE and collapse their string values to the fixed
        // receipt, while non-secret members keep the normal (identity here)
        // walk. Values are gitleaks-safe low-entropy fixtures.
        val s = slice()
        val calls = listOf(
            call(
                id = "call-1",
                args = """{"query":"q","creds":{"password":"simplevalue2","api_key":"AlphaBetaGamma42","note":"plain note"}}""",
            ),
        )
        val msgs = s.continuationMessages(calls, listOf(SafeToolSlice.ToolResult("call-1", "n", "{}")))
        val echo = msgs[0].toolCalls.single().argumentsJson
        assertFalse(echo.contains("simplevalue2"))
        assertFalse(echo.contains("AlphaBetaGamma42"))
        assertTrue(echo.contains("⟦REDACTED⟧"))
        // `creds` itself is not a secret-family key, so its non-secret `note`
        // stays; the nested secret-keyed members were still collapsed.
        assertTrue(echo.contains("plain note"))
        assertTrue(echo.contains("\"query\":\"q\""))
    }

    @Test fun secretKeyedObjectCollapsesWholeValue() = runBlocking {
        // A secret-family key whose value is an OBJECT must collapse the whole
        // value: recursing would echo inner values under non-secret inner keys.
        val s = slice()
        val calls = listOf(
            call(
                id = "call-1",
                args = """{"query":"q","password":{"z":"nestedvalue9"},"token":[{"v":"arrayvalue8"}]}""",
            ),
        )
        val msgs = s.continuationMessages(calls, listOf(SafeToolSlice.ToolResult("call-1", "n", "{}")))
        val echo = msgs[0].toolCalls.single().argumentsJson
        assertFalse(echo.contains("nestedvalue9"))
        assertFalse(echo.contains("arrayvalue8"))
        val body = Json.parseToJsonElement(echo).jsonObject
        assertEquals("⟦REDACTED⟧", body["password"]!!.jsonPrimitive.content)
        assertEquals("⟦REDACTED⟧", body["token"]!!.jsonPrimitive.content)
    }

    @Test fun executeRejectsDuplicatePairingIdsBeforeBackend() = runBlocking {
        // Direct-API defense in depth: TurnController dedups first, but a
        // future caller must not execute two calls that re-bind to one wire id.
        val backend = CountingBackend()
        val s = slice(backend = backend, maxCallsPerTurn = 2)
        val results = s.execute(listOf(call(id = "dup"), call(id = "dup")))
        assertEquals(2, results.size)
        assertTrue(results.all { it.denied })
        assertTrue(results.all { it.denyCode == SafeToolSlice.DenyCodes.SCHEMA_INVALID })
        assertEquals("duplicate id must not touch the backend", 0, backend.invocations.get())
    }

    @Test fun hugeBackendFieldsAreOmittedBeforeRedaction() = runBlocking {
        // Backend fields are redacted before the 500-char echo cap, so an
        // unbounded future backend string must be pre-bounded or it can burn
        // full-pass redaction CPU/heap first. Over-bound values are omitted
        // whole, so no raw prefix survives.
        val huge = "S".repeat(1_000_000)
        val backend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol(huge, huge, huge, 1))
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val result = slice(backend = backend).execute(listOf(call())).single()
        assertFalse(result.denied)
        assertTrue(
            "result must stay within MAX_RESULT_CHARS, was ${result.resultJson.length}",
            result.resultJson.length <= SafeToolSlice.MAX_RESULT_CHARS,
        )
        assertFalse("raw megabyte field must not survive", result.resultJson.contains("S".repeat(10_000)))
        assertTrue(result.resultJson.contains("[backend field omitted: 1000000 chars]"))
        val body = Json.parseToJsonElement(result.resultJson).jsonObject
        assertEquals("true", body["truncated"]!!.jsonPrimitive.content)
    }

    @Test fun oversizedBackendFieldOmittedWholeAvoidsBoundaryRedactionMiss() = runBlocking {
        // A suffix-anchored rule (URL credentials) would miss its suffix past
        // a raw pre-cap; truncating the prefix would then echo the credential.
        // Omission must not retain any prefix.
        val overBoundary = "https://user:hunter2" + "a".repeat(3_000) + "@host"
        val backend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol("ok", "Function", overBoundary, 1))
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val result = slice(backend = backend).execute(listOf(call())).single()
        assertFalse(result.denied)
        assertFalse("credential prefix must not survive omission", result.resultJson.contains("hunter2"))
        assertTrue(result.resultJson.contains("[backend field omitted:"))
    }

    @Test fun continuationArgsEchoRedactsTopLevelSecretKey() = runBlocking {
        val s = slice()
        val calls = listOf(
            call(id = "call-1", args = """{"password":"hunter2","query":"A"}"""),
        )
        val msgs = s.continuationMessages(calls, listOf(SafeToolSlice.ToolResult("call-1", "n", "{}")))
        val echo = msgs[0].toolCalls.single().argumentsJson
        assertFalse(echo.contains("hunter2"))
        assertTrue(echo.contains("⟦REDACTED⟧"))
    }

    @Test fun everyRegisteredNonSliceToolDeniesNotReady() = runBlocking {
        // Systematic negative sweep (beyond the two sampled tools): every
        // registered name other than the slice tool must reach a typed
        // NOT_READY denial with ZERO backend invocations — never dispatched.
        // The count pin makes this sweep self-checking: it always covers
        // exactly every registered non-slice tool.
        val backend = CountingBackend()
        val s = slice(backend = backend)
        var swept = 0
        for (tool in ToolRegistry.ALL) {
            if (tool.name == SafeToolSlice.TOOL_NAME) continue
            val r = s.execute(listOf(call(name = tool.name))).single()
            assertTrue(tool.name, r.denied)
            assertEquals(tool.name, SafeToolSlice.DenyCodes.NOT_READY, r.denyCode)
            swept++
        }
        assertEquals(ToolRegistry.ALL.size - 1, swept)
        assertEquals(0, backend.invocations.get())
    }

    // ---- Phase-0 review bundle (D/F3) ----

    /**
     * F2/F3 pins helper: assert a string carries no LONE surrogate (a high
     * surrogate must be immediately followed by its low partner; a low
     * surrogate must be immediately preceded by its high partner). The
     * capped strings are UTF-16-unit-safe only when this holds — a lone
     * surrogate degrades to U+FFFD at UTF-8 encode.
     */
    private fun assertNoLoneSurrogate(s: String) {
        var i = 0
        while (i < s.length) {
            val u = s[i].code
            if (u in 0xD800..0xDBFF) {
                assertTrue(
                    "lone high surrogate at $i of: $s",
                    i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF,
                )
                i += 2
            } else {
                assertFalse("lone low surrogate at $i of: $s", u in 0xDC00..0xDFFF)
                i++
            }
        }
    }

    @Test fun surrogatePairsNotSplitAtQueryAndBackendFieldCaps() = runBlocking {
        // F2: cappedEcho's old `.take(MAX_STRING_CHARS)` could cut between the
        // two halves of a surrogate pair and emit a LONE high surrogate into
        // the result JSON. The capped helper steps back over the split pair —
        // pinned on the model-controlled `query` echo AND the
        // backend-controlled symbol fields: 499 ASCII units + one emoji
        // (501 UTF-16 units) puts the pair's high half exactly at the cap-1
        // cut → the capped portion is 499 units (pair dropped intact), the
        // last unit is not a high surrogate, and no lone surrogate rides
        // anywhere in the result JSON.
        val emoji = "\uD83D\uDE00"
        val longBackend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(
                    LspTools.Symbol(
                        "n".repeat(499) + emoji,
                        "k".repeat(499) + emoji,
                        "p".repeat(499) + emoji,
                        1,
                    ),
                )
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val s = slice(backend = longBackend)
        val query = "q".repeat(499) + emoji
        assertEquals(501, query.length)
        val r = s.execute(listOf(call(args = """{"query":"$query"}"""))).single()
        assertFalse(r.denied)
        assertNoLoneSurrogate(r.resultJson)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        val echo = body["query"]!!.jsonPrimitive.content
        val cut = echo.indexOf('…')
        assertTrue("expected capped marker in echo: $echo", echo.contains("[truncated 501→500]"))
        val capped = echo.substring(0, cut)
        // Split case: one unit below the cap (the pair stepped out intact).
        assertEquals(SafeToolSlice.MAX_STRING_CHARS - 1, capped.length)
        assertFalse("last unit must not be a high surrogate", capped.last().isHighSurrogate())
        val sym = body["symbols"]!!.jsonArray.single().jsonObject
        for (field in listOf("name", "kind", "path")) {
            val v = sym[field]!!.jsonPrimitive.content
            val fieldCut = v.indexOf('…')
            assertTrue("expected capped marker in $field: $v", fieldCut > 0)
            val cappedField = v.substring(0, fieldCut)
            assertEquals(field, SafeToolSlice.MAX_STRING_CHARS - 1, cappedField.length)
            assertFalse(field, cappedField.last().isHighSurrogate())
        }
    }

    @Test fun surrogatePairRightOfTheCutKeepsFullCapInEcho() = runBlocking {
        // The non-split boundary case: 500 ASCII + emoji (502 units) cuts at
        // exactly 500 BEFORE the pair starts — the capped portion is a plain
        // 500 ASCII units, no surrogate handling needed and none triggered.
        val emoji = "\uD83D\uDE00"
        val longBackend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol("m".repeat(2000), "k".repeat(2000), "p".repeat(2000), 1))
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val s = slice(backend = longBackend)
        val query = "q".repeat(500) + emoji
        val r = s.execute(listOf(call(args = """{"query":"$query"}"""))).single()
        assertFalse(r.denied)
        assertNoLoneSurrogate(r.resultJson)
        val body = Json.parseToJsonElement(r.resultJson).jsonObject
        val echo = body["query"]!!.jsonPrimitive.content
        val cut = echo.indexOf('…')
        assertTrue(echo.contains("[truncated 502→500]"))
        assertEquals(SafeToolSlice.MAX_STRING_CHARS, echo.substring(0, cut).length)
    }

    @Test fun surrogateCapAppliesToCallIdEchoesToo() = runBlocking {
        // The same bounded-id helper governs the call-id wire forms (deny/ok
        // receipt + continuation re-bind): an emoji near the cap is not split,
        // and the hash suffix keeps the capped id within the cap and distinct
        // (the capped prefix here ends before the pair, so the full cap is
        // usable).
        val emoji = "\uD83D\uDE00"
        // Place the pair exactly across the capped-prefix boundary: the cap
        // reserves 9 chars for the hash suffix, so the prefix cut is at 491
        // and the high surrogate sits at index 490. A plain take(491) would
        // split the pair and this test would emit a lone surrogate.
        val emojiId = "I".repeat(490) + emoji + "I".repeat(20)
        val s = slice(backend = CountingBackend())
        val unknown = s.execute(listOf(call(id = emojiId, name = "nope.tool"))).single()
        assertTrue(unknown.denied)
        assertNoLoneSurrogate(unknown.resultJson)
        val denyBody = Json.parseToJsonElement(unknown.resultJson).jsonObject
        assertEquals(SafeToolSlice.MAX_STRING_CHARS - 1, denyBody["callId"]!!.jsonPrimitive.content.length)
        assertNoLoneSurrogate(denyBody["callId"]!!.jsonPrimitive.content)
        // Internal pairing keeps the full id; the continuation re-bind uses
        // the same bounded, surrogate-safe form.
        assertEquals(emojiId, unknown.callId)
        val msgs = s.continuationMessages(listOf(call(id = emojiId)), listOf(unknown))
        val contId = msgs.first { it.role == "tool" }.toolCallId
        assertEquals(SafeToolSlice.MAX_STRING_CHARS - 1, contId!!.length)
        assertNoLoneSurrogate(contId)
        assertEquals(SafeToolSlice.redactedPairingId(emojiId), contId)
    }

    @Test fun queryTraversalAndEscapeShapesStayAllowed() = runBlocking {
        // F3(a): the documented (but previously untested) claim — the
        // model-controlled `query` keeps its own narrower check, so
        // traversal/colon/percent-marks in a SEARCH STRING stay legal; only
        // pathPrefix is scope-restricted.
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val allowed = listOf("a..b%3Ac", "c:/tmp", "../external-query", "%2e%2e", "file:/doc")
        for ((i, q) in allowed.withIndex()) {
            val r = s.execute(listOf(call(args = """{"query":"$q"}""", id = "q-ok-$i"))).single()
            assertFalse("query must stay allowed: $q", r.denied)
        }
        assertEquals(allowed.size, backend.invocations.get())
    }

    @Test fun overGrossArgsExecuteGateDeniesSchemaInvalidWithZeroSideEffects() = runBlocking {
        // F3(c): parseArgs' gross cap fans closed at the EXECUTE gate with
        // the same typed SCHEMA_INVALID denial (never a crash, never an
        // oversized parse): a well-shaped object whose raw argument text
        // exceeds MAX_GROSS_ARGS_CHARS is rejected before any parse.
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val args = """{"query":"""" + "x".repeat(SafeToolSlice.MAX_GROSS_ARGS_CHARS) + """"}"""
        assertTrue(args.length > SafeToolSlice.MAX_GROSS_ARGS_CHARS)
        val r = s.execute(listOf(call(args = args, id = "gross"))).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.SCHEMA_INVALID, r.denyCode)
        assertEquals(0, backend.invocations.get())
    }

    @Test fun throwingPolicyStoreStillFailsClosedWithZeroSideEffects() = runBlocking {
        // F3(d): the fresh `evaluateFresh` catch-all in executeOne — a store
        // that THROWS (storage/IO failure) denies POLICY_DENIED with the
        // fixed "ERROR" receipt and ZERO backend effects (fail-closed;
        // denial is a typed result, pairing preserved).
        val backend = CountingBackend()
        val throwing = object : PolicyStore {
            override fun evaluate(action: String, resource: String): PolicyDecision =
                PolicyDecision(Verdict.ALLOW, null, 0L)
            override suspend fun setRule(rule: PolicyRule) = Unit
            override suspend fun removeRule(pattern: String) = Unit
            override suspend fun listRules(): List<PolicyRule> = emptyList()
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                throw IllegalStateException("policy io exploded")
        }
        val s = SafeToolSlice(
            ctxSupplier = { ProjectionContext(userSwitches = mapOf("lsp" to true)) },
            policy = throwing,
            backend = backend,
            clock = fixedClock,
        )
        val r = s.execute(listOf(call(id = "err-1"))).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.POLICY_DENIED, r.denyCode)
        assertEquals(
            "ERROR",
            Json.parseToJsonElement(r.resultJson).jsonObject["detail"]!!.jsonPrimitive.content,
        )
        assertEquals(0, backend.invocations.get())
    }
}
