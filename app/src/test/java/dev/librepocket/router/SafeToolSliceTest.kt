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

    private fun allowPolicy(vararg extra: PolicyRule) = runBlocking {
        val s = InMemoryPolicyStore(
            listOf(
                PolicyRule("chat.send:*", Verdict.ALLOW, 10),
                PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 10),
            ) + extra.toList(),
        )
        s
    }

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
        assertEquals(calls, msgs[0].toolCalls)
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
        assertTrue(tool != null)
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
        val r = s.execute(listOf(call())) .single()
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
        val secret = "sk-live-SECRET123 Bearer abc"
        val throwing = object : LspTools.Backend {
            override fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> =
                throw IllegalStateException(secret)
            override fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val r = slice(backend = throwing).execute(listOf(call())).single()
        assertTrue(r.denied)
        assertEquals(SafeToolSlice.DenyCodes.EXEC_ERROR, r.denyCode)
        // Fixed receipt: backend message never interpolates into the wire JSON.
        assertFalse(r.resultJson.contains(secret))
        assertFalse(r.resultJson.contains("sk-live-SECRET123"))
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
        val results = listOf(policyDenied, schemaDenied, unreadyDenied)
        assertTrue(results.all { it.denied })
        for (r in results) {
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
}
