package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests pinning production composition guard (#19):
 * - Production composition [ChatViewModelFactory.sessions.toolSlice] is strictly null.
 * - Sessions created from production composition send empty tools to model.
 * - For scripted [StreamEvent.ToolDone] responses, tests assert a [tool:...]
 *   marker, one provider request (no continuation), and no [tool-result:] marker.
 * - Production has no switch/backend injection seam, so its null slice implies
 *   no tool execution path; only the explicitly injected control counts executions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductionChatCompositionTest {

    private var db: LibrePocketDb? = null

    private suspend fun withSessionCleanup(session: ChatSession, block: suspend () -> Unit) {
        var failure: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            try {
                withContext(NonCancellable) {
                    session.close()
                    check(session.flush(8_000)) { "session shutdown timed out" }
                }
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    @After fun tearDown() {
        try { db?.close() } finally {
            db = null
        }
    }

    private class ScriptedProvider(
        var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
    ) : LlmProvider {
        override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow { emitAll(handler(request)) }
        override suspend fun listModels(): List<String> = emptyList()
    }

    private fun testEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProto.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    /**
     * Test policy store granting key.read ALLOW. Self-contained in this test file
     * (mirrors [ChatViewModelTestSupport.allowKeyReadPolicy]) to provide explicit
     * visibility for diff-only review context and avoid unresolved symbol findings.
     */
    private fun allowKeyReadPolicy(): InMemoryPolicyStore {
        val policy = InMemoryPolicyStore()
        runBlocking { policy.setRule(PolicyRule("key.read:*", Verdict.ALLOW, 20)) }
        return policy
    }

    @Test
    fun productionCompositionPinsToolSliceStrictlyNull() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = ChatViewModelFactory(app)
        assertNull("production composition must pin toolSlice to null", factory.sessions.toolSlice)
    }

    @Test
    fun factoryCreatesChatViewModelInstance() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = ChatViewModelFactory(app)
        val store = ViewModelStore()
        try {
            val vm = ViewModelProvider(store, factory)[ChatViewModel::class.java]
            assertNotNull("factory must instantiate ChatViewModel", vm)
        } finally {
            store.clear()
        }
    }

    @Test
    fun productionSessionSendsNoToolsToModel() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val opened = LibrePocketDb.openInMemory(app)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val provider = ScriptedProvider()
        val requests = AtomicInteger(0)
        val policy = allowKeyReadPolicy().apply {
            setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        }
        val capturedRequest = AtomicReference<ChatRequest?>()

        provider.handler = { request ->
            requests.incrementAndGet()
            capturedRequest.set(request)
            flow {
                emit(StreamEvent.TextDelta(0, 0, "回覆訊息"))
                emit(StreamEvent.Done("stop"))
            }
        }

        val factory = ChatViewModelFactory(
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
        )

        assertNull("production composition toolSlice must be null", factory.sessions.toolSlice)

        val created = factory.sessions.create(
            endpoint = testEndpoint(),
            title = "生產會話測試",
            allowAsk = false,
            keyIsCurrent = { true },
        )
        withSessionCleanup(created.session) {
            created.session.send("你好")
            assertTrue(created.session.flushDurable(8_000))

            assertEquals(1, requests.get())
            val req = checkNotNull(capturedRequest.get()) { "capturedRequest must not be null" }
            assertTrue("production session must send empty tools to model", req.tools.isEmpty())
        }
    }

    @Test
    fun productionSessionRecordsToolDoneWithoutExecutingAndNeverContinuesTurn() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val opened = LibrePocketDb.openInMemory(app)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val provider = ScriptedProvider()
        val requests = AtomicInteger(0)
        // Match the positive control's policy. Production provides no seam to
        // enable the lsp switch or inject its counting backend.
        val policy = allowKeyReadPolicy().apply {
            setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        }

        provider.handler = { request ->
            val reqIndex = requests.incrementAndGet()
            if (reqIndex == 1) {
                assertTrue("initial request tools must be empty", request.tools.isEmpty())
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-prod-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "不應到達的續聊回應"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val factory = ChatViewModelFactory(
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
        )

        assertNull(factory.sessions.toolSlice)

        val created = factory.sessions.create(
            endpoint = testEndpoint(),
            title = "工具記錄測試",
            allowAsk = false,
            keyIsCurrent = { true },
        )
        withSessionCleanup(created.session) {
            created.session.send("查符號 Foo")
            assertTrue(created.session.flushDurable(8_000))

            // 1. NEVER continues the turn (provider called exactly once)
            assertEquals("production composition must never continue turn on ToolDone", 1, requests.get())

            val sid = created.sessionId!!
            val events = store.loadEvents(sid, 0, 2000)
            val finals = events.filter { it.kind == "assistant" && it.isFinal }
            assertEquals("exactly one final assistant row: $events", 1, finals.size)
            val final = finals.single()

            // 2. Records ToolDone into transcript as [tool:...] only
            assertTrue("transcript must record tool call marker: $final", final.text.contains("[tool:lsp.symbols"))

            // 3. The final transcript has no [tool-result:] marker (not an execution count).
            assertFalse("production composition must never emit tool-result marker: $final", final.text.contains("[tool-result:"))

            // 4. No other events in store emit tool-result
            assertFalse("store must have no tool-result markers", events.any { it.text.contains("[tool-result:") })
        }
    }

    @Test
    fun explicitlyInjectedToolSliceExecutesAndContinuesAsPositiveControl() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val opened = LibrePocketDb.openInMemory(app)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val policy = allowKeyReadPolicy()
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val executions = AtomicInteger(0)
        val backend = object : LspTools.Backend {
            override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
                executions.incrementAndGet()
                assertEquals("Foo", query)
                return emptyList()
            }
            override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
        }
        val requests = AtomicInteger(0)
        val initialRequest = AtomicReference<ChatRequest?>()
        val continuationRequest = AtomicReference<ChatRequest?>()
        val provider = ScriptedProvider { request ->
            when (requests.incrementAndGet()) {
                1 -> {
                    initialRequest.set(request)
                    flow {
                        emit(StreamEvent.ToolDone(0, "call-prod-1", "lsp.symbols", """{"query":"Foo"}"""))
                        emit(StreamEvent.Done("tool_calls"))
                    }
                }
                else -> flow {
                    continuationRequest.set(request)
                    emit(StreamEvent.TextDelta(0, 0, "續聊回應"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        // This is deliberately NOT ChatViewModelFactory's production composition.
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource {
                EncryptedPrefsVault(InMemoryPrefs()).apply {
                    putKey("preset:openai", "test-key-12345678".toCharArray())
                }
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = store
            },
            buildProvider = { _, _ -> provider },
            toolSlice = SafeToolSlice(
                ctxSupplier = { ProjectionContext(userSwitches = mapOf("lsp" to true)) },
                policy = policy,
                backend = backend,
            ),
        )
        val created = factory.create(endpoint = testEndpoint(), title = "注入工具控制測試", allowAsk = false, keyIsCurrent = { true })
        withSessionCleanup(created.session) {
            created.session.send("查符號 Foo")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals("injected control must execute the backend once", 1, executions.get())
            assertEquals("injected control must continue the turn", 2, requests.get())
            val initial = checkNotNull(initialRequest.get())
            val continuation = checkNotNull(continuationRequest.get())
            assertEquals(listOf("lsp.symbols"), initial.tools.map { it.name })
            assertTrue("continuation must not offer another tool round", continuation.tools.isEmpty())
            val messages = continuation.messages
            assertTrue("continuation must end with the call and result", messages.size >= 2)
            val callMessage = messages[messages.size - 2]
            val result = messages.last()
            assertEquals("assistant", callMessage.role)
            assertEquals("", callMessage.text)
            val call = callMessage.toolCalls.single()
            assertEquals("call-prod-1", call.id)
            assertEquals("lsp.symbols", call.name)
            assertEquals("""{"query":"Foo"}""", call.argumentsJson)
            assertEquals("tool", result.role)
            assertEquals(call.id, result.toolCallId)
            assertTrue(result.toolCalls.isEmpty())
            assertTrue("no other structured calls or tool results", messages.dropLast(2).none { it.role == "tool" || it.toolCalls.isNotEmpty() })
            val body = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("false", body.getValue("denied").jsonPrimitive.content)
            assertEquals(call.id, body.getValue("callId").jsonPrimitive.content)
            assertEquals("lsp.symbols", body.getValue("tool").jsonPrimitive.content)
            val events = store.loadEvents(created.sessionId!!, 0, 2000)
            assertTrue("injected control must record a tool result: $events", events.any { it.text.contains("[tool-result:lsp.symbols:") })
            assertTrue("injected control must record continuation: $events", events.any { it.text.contains("續聊回應") })
        }
    }

    @Test
    fun productionSessionRecordsMultipleToolDoneCallsWithoutExecution() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val opened = LibrePocketDb.openInMemory(app)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val provider = ScriptedProvider()
        val requests = AtomicInteger(0)

        provider.handler = { _ ->
            requests.incrementAndGet()
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-prod-multi-1",
                        name = "lsp.symbols",
                        argumentsJson = """{"query":"Alpha"}""",
                    ),
                )
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 1,
                        id = "call-prod-multi-2",
                        name = "workspace.symbols",
                        argumentsJson = """{"query":"Beta"}""",
                    ),
                )
                emit(StreamEvent.Done("tool_calls"))
            }
        }

        val factory = ChatViewModelFactory(
            app = app,
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource {
                val v = EncryptedPrefsVault(InMemoryPrefs())
                v.putKey("preset:openai", "test-key-12345678".toCharArray())
                v
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = store
            },
            buildProvider = { _, _ -> provider },
        )

        val created = factory.sessions.create(
            endpoint = testEndpoint(),
            title = "多工具記錄測試",
            allowAsk = false,
            keyIsCurrent = { true },
        )
        withSessionCleanup(created.session) {
            created.session.send("查多個符號")
            assertTrue(created.session.flushDurable(8_000))

            assertEquals("must never continue turn on multiple ToolDone events", 1, requests.get())

            val sid = created.sessionId!!
            val events = store.loadEvents(sid, 0, 2000)
            val finals = events.filter { it.kind == "assistant" && it.isFinal }
            assertEquals(1, finals.size)
            val final = finals.single()

            assertTrue("must record first tool: $final", final.text.contains("[tool:lsp.symbols"))
            assertTrue("must record second tool: $final", final.text.contains("[tool:workspace.symbols"))
            assertFalse("must never emit tool-result: $final", final.text.contains("[tool-result:"))
            assertFalse("store must have no tool-result markers", events.any { it.text.contains("[tool-result:") })
        }
    }
}
