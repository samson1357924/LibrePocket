package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.tool.DenyReason
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import dev.librepocket.provider.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
        override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            lastLimit = limit
            return symbols.filter { query.isNotBlank() }.take(limit)
        }
        override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
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
    ) = SafeToolSlice(
        ctxSupplier = { ctx },
        policy = policy,
        backend = backend,
        clock = clock,
    )

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
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                throw IllegalStateException("boom")
            override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
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
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                throw IllegalStateException(marker)
            override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
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
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(LspTools.Symbol("N".repeat(2000), "K".repeat(2000), "P".repeat(2000), 7))
            override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
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

    @Test fun hookThrowMapsToTypedExecErrorWithoutLeak() = runBlocking {
        // Neutral marker (deliberately not a secret-shaped literal): the pin is
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
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                List(LspTools.MAX_LIMIT) {
                    LspTools.Symbol("N".repeat(400), "K".repeat(400), "P".repeat(400), it)
                }
            override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
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
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                listOf(
                    LspTools.Symbol(
                        "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                        "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                        "\"".repeat(SafeToolSlice.MAX_STRING_CHARS),
                        1,
                    ),
                )
            override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
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
        // Non-secret members still echo normally.
        assertTrue(echo.contains("plain note"))
        assertTrue(echo.contains("\"query\":\"q\""))
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
}
