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
        val results = s.execute(
            listOf(
                call(id = "call-1", args = """{"query":"A"}"""),
                call(id = "call-2", args = """{"query":"B"}"""),
            ),
        )
        assertEquals(2, results.size)
        assertTrue(results.all { it.denied })
        assertTrue(results.all { it.denyCode == SafeToolSlice.DenyCodes.BUDGET_EXCEEDED })
        assertEquals(listOf("call-1", "call-2"), results.map { it.callId })
        assertEquals(0, backend.invocations.get())
    }

    @Test fun continuationMessagesPairCallsAndResults() = runBlocking {
        val backend = CountingBackend()
        val s = slice(backend = backend)
        val calls = listOf(call(id = "call-1"), call(id = "call-2", args = "{bad"))
        // Budget is 1: run them as two single-call rounds to get one ok + one denied.
        val ok = s.execute(listOf(calls[0])).single()
        val over = SafeToolSlice(
            ctxSupplier = { ProjectionContext(userSwitches = mapOf("lsp" to true)) },
            policy = allowPolicy(),
            backend = backend,
            clock = fixedClock,
            maxCallsPerTurn = 0,
        ).execute(listOf(calls[1])).single()
        assertTrue(over.denied)
        val msgs = s.continuationMessages(calls, listOf(ok, over))
        assertEquals(3, msgs.size)
        assertEquals("assistant", msgs[0].role)
        assertEquals(calls, msgs[0].toolCalls)
        assertEquals("tool", msgs[1].role)
        assertEquals("call-1", msgs[1].toolCallId)
        assertEquals(ok.resultJson, msgs[1].text)
        assertEquals("tool", msgs[2].role)
        assertEquals("call-2", msgs[2].toolCallId)
        assertTrue(msgs[2].text.contains(SafeToolSlice.DenyCodes.BUDGET_EXCEEDED))
        assertEquals(1, backend.invocations.get())
    }
}
