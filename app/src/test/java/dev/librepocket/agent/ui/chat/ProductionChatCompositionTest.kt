package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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
 * - When model returns [StreamEvent.ToolDone], production composition records it
 *   as [tool:...] only, NEVER executes the tool, NEVER continues the turn, and
 *   NEVER emits [tool-result:].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductionChatCompositionTest {

    private var db: LibrePocketDb? = null

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

    @Test
    fun productionCompositionPinsToolSliceStrictlyNull() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val factory = ChatViewModelFactory(app)
        assertNull("production composition must pin toolSlice to null", factory.sessions.toolSlice)
    }

    @Test
    fun productionSessionSendsNoToolsToModel() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val opened = LibrePocketDb.openInMemory(app)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val provider = ScriptedProvider()
        val requests = AtomicInteger(0)
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

        assertNull("production composition toolSlice must be null", factory.sessions.toolSlice)

        val created = factory.sessions.create(
            endpoint = testEndpoint(),
            title = "生產會話測試",
            allowAsk = false,
            keyIsCurrent = { true },
        )
        try {
            created.session.send("你好")
            assertTrue(created.session.flushDurable(8_000))

            assertEquals(1, requests.get())
            val req = capturedRequest.get()
            assertNotNull(req)
            assertTrue("production session must send empty tools to model", req!!.tools.isEmpty())
        } finally {
            created.session.close()
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

        assertNull(factory.sessions.toolSlice)

        val created = factory.sessions.create(
            endpoint = testEndpoint(),
            title = "工具記錄測試",
            allowAsk = false,
            keyIsCurrent = { true },
        )
        try {
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

            // 3. NEVER executes tool, NEVER emits [tool-result:]
            assertFalse("production composition must never emit tool-result marker: $final", final.text.contains("[tool-result:"))

            // 4. No other events in store emit tool-result
            assertFalse("store must have no tool-result markers", events.any { it.text.contains("[tool-result:") })
        } finally {
            created.session.close()
        }
    }
}
