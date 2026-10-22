package dev.librepocket.chat

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.allowKeyReadPolicy
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2 safe slice (#19) factory durability: a REAL [ChatSessionFactory]-built
 * session (real Room, real provider-loop) completes the fake-tool round-trip.
 *
 * This is the acceptance shape from #19 (factory-built session, not a direct
 * tool-function call): UI send → request tools → model ToolDone → slice
 * execute (StubBackend + fixed clock) → continuation → final UI + durable
 * Room transcript, then reopen reload equivalence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolSliceFactoryDurabilityTest {

    private var db: LibrePocketDb? = null

    @After fun tearDown() {
        try { db?.close() } finally {
            db = null
        }
    }

    private class CountingStubBackend : LspTools.Backend {
        val invocations = AtomicInteger(0)
        override suspend fun symbols(query: String, pathPrefix: String?, limit: Int): List<LspTools.Symbol> {
            invocations.incrementAndGet()
            return listOf(LspTools.Symbol("$query:example", "function", "app/src/main/Example.kt", 1))
        }
        override suspend fun diagnostics(path: String): List<LspTools.Diagnostic> = emptyList()
    }

    private class ScriptedProvider(
        var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
    ) : LlmProvider {
        override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow { emitAll(handler(request)) }
        override suspend fun listModels(): List<String> = emptyList()
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

    @Test fun factoryBuiltSessionCompletesSliceRoundTripDurably() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val opened = LibrePocketDb.openInMemory(context)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        val builds = AtomicInteger(0)
        policy.setRule(PolicyRule("tool.exec:lsp.symbols", Verdict.ALLOW, 20))
        val fixedClock = Clock.fixed(Instant.parse("2026-10-09T01:41:22Z"), ZoneId.of("UTC"))
        val backend = CountingStubBackend()
        val toolSlice = SafeToolSlice(
            ctxSupplier = { ProjectionContext(userSwitches = mapOf("lsp" to true)) },
            policy = policy,
            backend = backend,
            clock = fixedClock,
        )
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource {
                val v = EncryptedPrefsVault(InMemoryPrefs())
                v.putKey("preset:openai", "test-key-12345678".toCharArray())
                v
            },
            buildProvider = { _, _ ->
                builds.incrementAndGet()
                provider
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = store
            },
            toolSlice = toolSlice,
        )
        provider.handler = { request ->
            if (request.messages.none { it.role == "tool" }) {
                // Slice offer must be exactly the single wired tool.
                check(request.tools.map { it.name } == listOf("lsp.symbols")) {
                    "slice offer must be [lsp.symbols], was ${request.tools.map { it.name }}"
                }
                flow {
                    emit(
                        StreamEvent.ToolDone(
                            toolIndex = 0,
                            id = "call-room-1",
                            name = "lsp.symbols",
                            argumentsJson = """{"query":"Foo"}""",
                        ),
                    )
                    emit(StreamEvent.Done("tool_calls"))
                }
            } else {
                flow {
                    emit(StreamEvent.TextDelta(0, 0, "Foo 在 Example.kt 第一行"))
                    emit(StreamEvent.Done("stop"))
                }
            }
        }

        val created = factory.create(endpoint(), "查符號", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("幫我查 Foo 這個符號")
            assertTrue(created.session.flushDurable(8_000))
            assertEquals("exactly one backend execution", 1, backend.invocations.get())
            assertEquals("create builds exactly one provider", 1, builds.get())

            val sid = created.sessionId!!
            val events = store.loadEvents(sid, 0, 2000)
            assertTrue("user row missing: $events", events.any { it.kind == "user" })
            val finals = events.filter { it.kind == "assistant" && it.isFinal }
            assertEquals("exactly one final assistant row: $events", 1, finals.size)
            val final = finals.single()
            assertTrue("missing tool marker: $final", final.text.contains("[tool:lsp.symbols"))
            assertTrue("missing result marker: $final", final.text.contains("[tool-result:lsp.symbols"))
            assertTrue("missing stub echo: $final", final.text.contains("Foo:example"))
            assertTrue("missing final answer: $final", final.text.contains("Foo 在 Example.kt 第一行"))

            // Reopen reload equivalence: no new rows, same family content.
            val before = events.map { Triple(it.runId, it.kind, it.text) }
            val reopened = factory.open(endpoint(), sid, allowAsk = false, keyIsCurrent = { true })
            try {
                assertFalse(reopened.historyWithheld)
                assertEquals("reopen must not re-execute the durable round", 1, backend.invocations.get())
                assertEquals("open builds exactly one provider", 2, builds.get())
                val after = store.loadEvents(sid, 0, 2000).map { Triple(it.runId, it.kind, it.text) }
                assertEquals(before, after)
            } finally {
                reopened.session.close()
            }
        } finally {
            created.session.close()
        }
    }

    @Test fun defaultFactoryStaysRecordsOnlyAndPureChat() = runBlocking {
        // Production default: no toolSlice attached — the request carries no
        // tools, the model ToolDone is recorded only, and no continuation
        // runs (single provider round-trip, no [tool-result:] marker).
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val opened = LibrePocketDb.openInMemory(context)
        db = opened
        val store: SessionStore = RoomSessionStore(opened) { 1_700_000_000_000L }
        val provider = ScriptedProvider()
        val policy = allowKeyReadPolicy()
        val requests = AtomicInteger(0)
        val builds = AtomicInteger(0)
        val factory = ChatSessionFactory(
            policy = policy,
            vaultSource = VaultSource {
                val v = EncryptedPrefsVault(InMemoryPrefs())
                v.putKey("preset:openai", "test-key-12345678".toCharArray())
                v
            },
            buildProvider = { _, _ ->
                builds.incrementAndGet()
                provider
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = store
            },
        )
        provider.handler = { request ->
            requests.incrementAndGet()
            check(request.tools.isEmpty()) {
                "default factory must attach no tools, was ${request.tools.map { it.name }}"
            }
            flow {
                emit(
                    StreamEvent.ToolDone(
                        toolIndex = 0,
                        id = "call-def-1",
                        name = "lsp.symbols",
                        argumentsJson = """{"query":"Foo"}""",
                    ),
                )
                emit(StreamEvent.Done("tool_calls"))
            }
        }

        val created = factory.create(endpoint(), "查符號", allowAsk = false, keyIsCurrent = { true })
        try {
            created.session.send("幫我查 Foo 這個符號")
            assertTrue(created.session.flushDurable(8_000))

            assertEquals(1, requests.get())
            assertEquals("default factory still builds exactly one provider", 1, builds.get())
            val sid = created.sessionId!!
            val events = store.loadEvents(sid, 0, 2000)
            val finals = events.filter { it.kind == "assistant" && it.isFinal }
            assertEquals("exactly one final assistant row: $events", 1, finals.size)
            val final = finals.single()
            assertTrue("missing tool marker: $final", final.text.contains("[tool:lsp.symbols"))
            assertFalse("must not execute without a loop: $final", final.text.contains("[tool-result:"))
        } finally {
            created.session.close()
        }
    }
}
