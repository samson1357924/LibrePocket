package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.chat.ChatSession
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.router.SafeToolSlice
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.tool.LspTools
import dev.librepocket.tool.ProjectionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * B2-phase1 (#19) acceptance via the REAL [ChatViewModelFactory] composition
 * (not a direct tool-function call): UI send → request tools → model ToolDone
 * → schema → latest projection + fresh `tool.exec` policy → StubBackend
 * executor → typed result → provider continuation → final UI/transcript.
 *
 * Scope: single read-only tool (`lsp.symbols`, StubBackend echo). No
 * FastRouter wiring (B3), no `executionReady` flips, no capability-matrix
 * change. Production default stays records-only (`toolSlice = null` unless a
 * test injects one through the factory seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class B2Phase1DispatcherGrantTest {

    private var db: LibrePocketDb? = null

    @After fun tearDown() {
        try { db?.close() } finally { db = null }
    }

    private class ScriptedProvider(
        var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
    ) : LlmProvider {
        override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow { emitAll(handler(request)) }
        override suspend fun listModels(): List<String> = emptyList()
    }

    private class CountingBackend : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            return listOf(LspTools.Symbol("$query:example", "function", "app/src/main/Example.kt", 1))
        }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private fun endpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProto.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun openDb(app: Application): SessionStore {
        val opened = LibrePocketDb.openInMemory(app)
        db = opened
        return RoomSessionStore(opened) { 1_700_000_000_000L }
    }

    private fun factory(
        app: Application,
        store: SessionStore,
        policy: dev.librepocket.policy.PolicyStore,
        provider: ScriptedProvider,
        slice: SafeToolSlice?,
    ) = ChatViewModelFactory(
        app = app,
        policy = policy,
        vaultSource = VaultSource {
            val v = EncryptedPrefsVault(InMemoryPrefs())
            v.putKey("preset:openai", "test-key-12345678".toCharArray())
            v
        },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = store
        },
        buildProvider = { _, _ -> provider },
        toolSlice = slice,
    )

    private fun slice(
        policy: dev.librepocket.policy.PolicyStore,
        backend: LspTools.Backend,
        switches: Map<String, Boolean> = mapOf("lsp" to true),
    ) = slice(policy, backend) { switches }

    private fun slice(
        policy: dev.librepocket.policy.PolicyStore,
        backend: LspTools.Backend,
        switchesSupplier: () -> Map<String, Boolean>,
    ) = SafeToolSlice(
        ctxSupplier = { ProjectionContext(userSwitches = switchesSupplier()) },
        policy = policy,
        backend = backend,
    )

    private suspend fun closeQuietly(session: ChatSession) {
        try {
            withContext(NonCancellable) {
                try { session.close() } finally { session.flush(8_000) }
            }
        } catch (_: Exception) {
        }
    }

    @Test fun realFactoryRoundTripCompletesFakeToolLoop() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val f = factory(app, store, policy, provider, slice(policy, backend))

        provider.handler = { request ->
            when (requests.incrementAndGet()) {
                1 -> {
                    assertEquals(listOf("lsp.symbols"), request.tools.map { it.name })
                    flow {
                        emit(StreamEvent.ToolDone(0, "call-b2-1", "lsp.symbols", """{"query":"Foo"}"""))
                        emit(StreamEvent.Done("tool_calls"))
                    }
                }
                else -> {
                    assertTrue("continuation must not offer another tool round", request.tools.isEmpty())
                    flow {
                        emit(StreamEvent.TextDelta(0, 0, "Foo 在 Example.kt 第一行"))
                        emit(StreamEvent.Done("stop"))
                    }
                }
            }
        }

        val created = f.sessions.create(endpoint(), "B2 查符號", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("幫我查 Foo 這個符號")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(1, backend.invocations.get())
            assertEquals(2, requests.get())
            val sid = created.sessionId!!
            val events = store.loadEvents(sid, 0, 2000)
            assertTrue("missing tool marker: $events", events.any { it.text.contains("[tool:lsp.symbols") })
            assertTrue("missing result marker: $events", events.any { it.text.contains("[tool-result:lsp.symbols") })
            assertTrue("missing final answer: $events", events.any { it.text.contains("Foo 在 Example.kt 第一行") })
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun realFactoryContinuationPairsCallAndTypedResult() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val seen = mutableListOf<ChatRequest>()
        val f = factory(app, store, policy, provider, slice(policy, backend))

        provider.handler = { request ->
            seen.add(request)
            if (requests.incrementAndGet() == 1) {
                flow {
                    emit(StreamEvent.ToolDone(0, "call-b2-pair", "lsp.symbols", """{"query":"Foo"}"""))
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "續聊回應"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val created = f.sessions.create(endpoint(), "B2 配對", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查 Foo")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(2, seen.size)
            assertTrue(seen[1].tools.isEmpty())
            val messages = seen[1].messages
            assertTrue(messages.size >= 2)
            val callMessage = messages[messages.size - 2]
            val result = messages.last()
            assertEquals("assistant", callMessage.role)
            val call = callMessage.toolCalls.single()
            assertEquals("call-b2-pair", call.id)
            assertEquals("lsp.symbols", call.name)
            assertEquals("tool", result.role)
            assertEquals(call.id, result.toolCallId)
            val body = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("false", body.getValue("denied").jsonPrimitive.content)
            assertEquals("call-b2-pair", body.getValue("callId").jsonPrimitive.content)
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun unregisteredExecutorStaysRecordsOnlyWithZeroBackendEffects() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val f = factory(app, store, policy, provider, slice(policy, backend))

        provider.handler = { _ ->
            requests.incrementAndGet()
            flow {
                emit(StreamEvent.ToolDone(0, "call-unreg-1", "lsp.diagnostics", """{"path":"a.kt"}"""))
                emit(StreamEvent.ToolDone(1, "call-unreg-2", "workspace.symbols", """{"query":"B"}"""))
                emit(StreamEvent.TextDelta(0, 0, "已記錄"))
                emit(StreamEvent.Done("stop"))
            }
        }

        val created = f.sessions.create(endpoint(), "B2 未註冊", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查東西")
            assertTrue(created.session.flushDurable(8_000))
            // Unwired tools never execute and never continue the turn.
            assertEquals(1, requests.get())
            assertEquals(0, backend.invocations.get())
            val events = store.loadEvents(created.sessionId!!, 0, 2000)
            assertTrue(events.any { it.text.contains("[tool:lsp.diagnostics") })
            assertTrue(events.any { it.text.contains("[tool:workspace.symbols") })
            assertFalse(events.any { it.text.contains("[tool-result:") })
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun schemaInvalidDeniesWithZeroBackendEffects() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val seen = mutableListOf<ChatRequest>()
        val f = factory(app, store, policy, provider, slice(policy, backend))

        provider.handler = { request ->
            seen.add(request)
            if (requests.incrementAndGet() == 1) {
                flow {
                    emit(StreamEvent.ToolDone(0, "call-bad", "lsp.symbols", "{oops"))
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "請給符號名"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val created = f.sessions.create(endpoint(), "B2 錯 schema", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查一下")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(0, backend.invocations.get())
            assertEquals(2, seen.size)
            val toolTexts = seen[1].messages.filter { it.role == "tool" }.map { it.text }
            assertEquals(1, toolTexts.size)
            assertTrue(toolTexts.single().contains(SafeToolSlice.DenyCodes.SCHEMA_INVALID))
            val events = store.loadEvents(created.sessionId!!, 0, 2000)
            assertFalse("schema denial must not execute backend", events.any { it.text.contains("Foo:example") })
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun scopeMismatchDeniesWithZeroBackendEffects() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val seen = mutableListOf<ChatRequest>()
        val switchOn = AtomicBoolean(true)
        // Switch ON at offer time: request offers tool. Switch flips to OFF between offer
        // and execution: fresh projection re-check must deny with SCOPE_DENIED.
        val f = factory(app, store, policy, provider, slice(policy, backend) { mapOf("lsp" to switchOn.get()) })

        provider.handler = { request ->
            seen.add(request)
            if (requests.incrementAndGet() == 1) {
                assertEquals("request must offer tool while switch is ON", 1, request.tools.size)
                assertEquals("lsp.symbols", request.tools.single().name)
                // Switch flips to OFF between offer and execution: fresh projection must deny.
                switchOn.set(false)
                flow {
                    emit(StreamEvent.ToolDone(0, "call-scope", "lsp.symbols", """{"query":"Foo"}"""))
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "開關未開"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val created = f.sessions.create(endpoint(), "B2 scope 不符", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查 Foo")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(0, backend.invocations.get())
            assertEquals(2, seen.size)
            val toolTexts = seen[1].messages.filter { it.role == "tool" }.map { it.text }
            assertEquals(1, toolTexts.size)
            assertTrue(toolTexts.single().contains(SafeToolSlice.DenyCodes.SCOPE_DENIED))
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun unsolicitedToolCallWhenSwitchFlipsOffToOnStaysRecordsOnly() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val seen = mutableListOf<ChatRequest>()
        val switchOn = AtomicBoolean(false)
        // Switch is OFF at offer time: request offers nothing. Switch flips ON mid-turn, but
        // unsolicited tool call must stay records-only and never execute or continue the turn.
        val f = factory(app, store, policy, provider, slice(policy, backend) { mapOf("lsp" to switchOn.get()) })

        provider.handler = { request ->
            seen.add(request)
            requests.incrementAndGet()
            assertTrue("switch-off request must offer no tools", request.tools.isEmpty())
            // Switch flips ON mid-turn after request was sent:
            switchOn.set(true)
            flow {
                emit(StreamEvent.ToolDone(0, "call-unsolicited", "lsp.symbols", """{"query":"Foo"}"""))
                emit(StreamEvent.TextDelta(0, 0, "未提供工具"))
                emit(StreamEvent.Done("stop"))
            }
        }

        val created = f.sessions.create(endpoint(), "B2 未主動提供工具", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查 Foo")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(0, backend.invocations.get())
            // Must stay records-only: never continues the turn
            assertEquals(1, seen.size)
            val events = store.loadEvents(created.sessionId!!, 0, 2000)
            assertTrue(events.any { it.text.contains("[tool:lsp.symbols") })
            assertFalse(events.any { it.text.contains("[tool-result:") })
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun mismatchedPolicyThrowsIllegalArgumentException() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val factoryPolicy = allowKeyReadPolicy()
        val slicePolicy = allowKeyReadPolicy()
        val backend = CountingBackend()
        val slice = slice(slicePolicy, backend)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            factory(app, store, factoryPolicy, provider, slice)
        }
        assertTrue(ex.message!!.contains("prevent policy split-brain"))

        val exSessions = assertThrows(IllegalArgumentException::class.java) {
            ChatSessionFactory(
                policy = factoryPolicy,
                vaultSource = VaultSource { EncryptedPrefsVault(InMemoryPrefs()) },
                toolSlice = slice,
            )
        }
        assertTrue(exSessions.message!!.contains("toolSlice.policy must be identical to ChatSessionFactory's policy"))
    }

    @Test fun latestPolicyDenyAfterOfferDeniesWithZeroBackendEffects() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val base = allowKeyReadPolicy()
        base.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        // Flip between offer and execution without a suspend call inside the
        // non-suspend provider handler: fresh evaluate must see the DENY.
        val flipped = AtomicBoolean(false)
        val policy = object : dev.librepocket.policy.PolicyStore by base {
            override suspend fun evaluateFresh(
                action: String,
                resource: String,
            ): dev.librepocket.policy.PolicyDecision {
                if (action == "tool.exec" && flipped.get()) {
                    return dev.librepocket.policy.PolicyDecision(
                        Verdict.DENY,
                        PolicyRule("tool.exec:lsp.symbols", Verdict.DENY, 20),
                        1_700_000_000_000L,
                    )
                }
                return base.evaluateFresh(action, resource)
            }
        }
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val seen = mutableListOf<ChatRequest>()
        val f = factory(app, store, policy, provider, slice(policy, backend))

        provider.handler = { request ->
            seen.add(request)
            if (requests.incrementAndGet() == 1) {
                // Flip to DENY between offer and execution: fresh evaluate must deny.
                flipped.set(true)
                flow {
                    emit(StreamEvent.ToolDone(0, "call-flip", "lsp.symbols", """{"query":"Foo"}"""))
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "目前無法執行工具"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val created = f.sessions.create(endpoint(), "B2 最新 DENY", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查 Foo")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(0, backend.invocations.get())
            assertEquals(2, seen.size)
            val toolTexts = seen[1].messages.filter { it.role == "tool" }.map { it.text }
            assertEquals(1, toolTexts.size)
            assertTrue(toolTexts.single().contains(SafeToolSlice.DenyCodes.POLICY_DENIED))
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun userCancelBeforeExecutionHasZeroSideEffects() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val toolSlice = slice(policy, backend)
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        toolSlice.beforeExecuteForTest = {
            entered.countDown()
            gate.await()
        }
        val requests = AtomicInteger(0)
        val f = factory(app, store, policy, provider, toolSlice)

        provider.handler = { _ ->
            requests.incrementAndGet()
            flow {
                emit(StreamEvent.ToolDone(0, "call-cancel", "lsp.symbols", """{"query":"Foo"}"""))
                emit(StreamEvent.Done("tool_calls"))
            }
        }

        val created = f.sessions.create(endpoint(), "B2 取消", allowAsk = false, keyIsCurrent = { true })
        try {
            val job = async(Dispatchers.Default) { created.session.send("查 Foo") }
            assertTrue("execute never entered", entered.await(8, TimeUnit.SECONDS))
            created.session.cancel()
            gate.complete(Unit)
            assertTrue(
                "send did not settle after cancel",
                withTimeoutOrNull(8_000) { job.join(); true } ?: false,
            )
            assertEquals(0, backend.invocations.get())
            assertEquals("cancel must not continue the turn", 1, requests.get())
            val events = store.loadEvents(created.sessionId!!, 0, 2000)
            assertFalse("cancel must leave no tool result", events.any { it.text.contains("[tool-result:") })
        } finally {
            closeQuietly(created.session)
        }
    }

    @Test fun auditAndWireCarryNoPlaintext() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = openDb(app)
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val backend = CountingBackend()
        val requests = AtomicInteger(0)
        val seen = mutableListOf<ChatRequest>()
        val f = factory(app, store, policy, provider, slice(policy, backend))
        val secretQuery = "contact user@example.com about Foo"

        provider.handler = { request ->
            seen.add(request)
            if (requests.incrementAndGet() == 1) {
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            0,
                            "call-secret",
                            "lsp.symbols",
                            """{"query":"$secretQuery"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "已查到"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val created = f.sessions.create(endpoint(), "B2 審計", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("查 Foo")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals(1, backend.invocations.get())
            assertEquals(2, seen.size)
            val wireToolTexts = seen[1].messages.filter { it.role == "tool" }.map { it.text } +
                seen[1].messages.flatMap { it.toolCalls }.map { it.argumentsJson }
            for (text in wireToolTexts) {
                assertFalse("wire must not carry plaintext email: $text", text.contains("user@example.com"))
            }
            val events = store.loadEvents(created.sessionId!!, 0, 2000)
            for (event in events) {
                assertFalse(
                    "audit must not store plaintext email: ${event.text}",
                    event.text.contains("user@example.com"),
                )
            }
        } finally {
            closeQuietly(created.session)
        }
    }
}
