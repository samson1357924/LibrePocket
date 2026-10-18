package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSessionImpl
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.TurnController
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class TrimFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

private class TrimAllowPolicy : PolicyStore {
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * R5-5 request-level trim visibility (pure JVM, all fake data).
 *
 * - A large resumed history that exceeds MAX_REQUEST_CHARS is trimmed
 *   oldest-first per request; the provider sees the cropped request and the
 *   send-time count surfaces via [ChatViewModel.requestTrimmedCount] while
 *   the load-time [ChatViewModel.historyOmittedCount] stays 0 (orthogonal).
 * - A 2002-row small history omits 2 rows at load (tail cap) but trims
 *   nothing per request (control: omitted == 2, requestTrim == 0).
 * - [ChatSessionImpl.requestTrimmedCount] mirrors the controller flow.
 *
 * Fixed clock/zone pins the ephemeral time block so trim math is exact;
 * history rows stay under MODEL_HISTORY_MAX_CHARS_PER_MESSAGE (no read-path
 * truncation) and under HISTORY_CAP (no tail omission in the trim test).
 */
class ChatRequestTrimVisibilityTest {

    private fun openaiEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-01-15T12:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val zoneSupplier: () -> ZoneId = { ZoneId.of("UTC") }

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatViewModels = ChatTestViewModelStore()
    private val chatMain = ChatTestMainDispatcher()

    @Before
    fun setUpMain() {
        chatMain.install()
    }

    @After
    fun tearDownMain() {
        try {
            chatMain.clearViewModels(chatViewModels)
        } finally {
            try {
                scopes.forEach { it.cancel() }
                runBlocking { scopes.mapNotNull { it.coroutineContext[kotlinx.coroutines.Job] }.joinAll() }
            } finally {
                try {
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    chatMain.resetAndClose()
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("chat-trim-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "chat.preferences_pb") },
            ),
        )
    }

    private fun newFactory(fake: TrimFakeProvider, transcripts: FakeSessionStore): ChatSessionFactory {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        return ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
            clock = fixedClock,
            userTimezone = "UTC",
            systemZone = zoneSupplier,
        )
    }

    private fun okHandler(): (ChatRequest) -> Flow<StreamEvent> = {
        flow {
            emit(StreamEvent.TextDelta(0, 0, "ok"))
            emit(StreamEvent.Done("stop"))
        }
    }

    private fun awaitTrue(
        timeoutMs: Long = 30_000,
        diagnostics: () -> String = { "" },
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) {
                val detail = diagnostics().takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                error("timed out waiting for condition$detail")
            }
            Thread.sleep(10)
        }
    }

    /** Exact-length row (19_000 chars): under the 20k per-message read cap, over budget in aggregate. */
    private fun bigRow(prefix: String): String = prefix + "x".repeat(19_000 - prefix.length)

    @Test
    fun largeHistoryTrim_surfacesRequestTrimmedCountAndKeepsOmissionZero() {
        val store = newStore()
        runBlocking { store.save(openaiEndpoint()) }
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("big chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
            // 3 complete turns x 19k chars = 114k > MAX_REQUEST_CHARS (100k);
            // 6 rows < HISTORY_CAP so load-time omission stays 0.
            for (i in 0 until 3) {
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "user", text = bigRow("u$i:"), createdAt = 1L),
                )
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "assistant", text = bigRow("a$i:"), createdAt = 1L),
                )
            }
            id
        }
        val fake = TrimFakeProvider(okHandler())
        val vm = chatViewModels.own(ChatViewModel(store, newFactory(fake, transcripts), allowKeyReadPolicy()))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 6 }
        assertEquals(0, vm.historyOmittedCount.value)
        assertEquals(0, vm.requestTrimmedCount.value)

        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 8 }

        // Provider sees the cropped request: 114k + "hi" + time block drops
        // exactly the oldest 19k row (95k remainder fits 100k).
        val req = fake.seenRequests.single()
        val totalChars = req.messages.sumOf { it.text.length }
        assertTrue(
            "total request chars must be <= MAX_REQUEST_CHARS, was $totalChars",
            totalChars <= TurnController.MAX_REQUEST_CHARS,
        )
        assertEquals(6, req.messages.size)
        assertTrue(req.messages.first().text.startsWith("a0:"))
        assertTrue(req.messages.last().text.startsWith("hi"))

        // Send-time trim is observable via the VM and orthogonal to omission.
        awaitTrue { vm.requestTrimmedCount.value == 1 }
        assertEquals(0, vm.historyOmittedCount.value)
    }

    @Test
    fun smallHistoryControl_omissionWithoutRequestTrim() {
        val store = newStore()
        runBlocking { store.save(openaiEndpoint()) }
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("long chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
            // 1001 complete turns: 2002 replay rows, oldest pair (u0,a0) omitted.
            for (i in 0 until 1001) {
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "user", text = "u$i", createdAt = 1L),
                )
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "assistant", text = "a$i", createdAt = 1L),
                )
            }
            id
        }
        val fake = TrimFakeProvider(okHandler())
        val vm = chatViewModels.own(ChatViewModel(store, newFactory(fake, transcripts), allowKeyReadPolicy()))
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 2000 }
        awaitTrue { vm.historyOmittedCount.value == 2 }
        assertEquals(0, vm.requestTrimmedCount.value)

        // Small rows: the send fits the request budget, so nothing trims.
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 2002 }
        awaitTrue { fake.seenRequests.isNotEmpty() }
        val req = fake.seenRequests.single()
        assertTrue(
            "small request must fit uncropped, was ${req.messages.sumOf { it.text.length }}",
            req.messages.sumOf { it.text.length } <= TurnController.MAX_REQUEST_CHARS,
        )
        assertEquals(0, vm.requestTrimmedCount.value)
        assertEquals(2, vm.historyOmittedCount.value)
    }

    @Test
    fun trimClearsOnFreshSessionAndUntrimmedSend() {
        val store = newStore()
        runBlocking { store.save(openaiEndpoint()) }
        val transcripts = FakeSessionStore()
        val big = runBlocking {
            val id = transcripts.createSession("big chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
            for (i in 0 until 3) {
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "user", text = bigRow("u$i:"), createdAt = 1L),
                )
                transcripts.appendEvent(
                    TranscriptEvent(sessionId = id, runId = "r$i", kind = "assistant", text = bigRow("a$i:"), createdAt = 1L),
                )
            }
            id
        }
        val fresh = runBlocking { transcripts.createSession("fresh", "preset:openai@https://api.openai.com/gpt-4o-mini") }
        val fake = TrimFakeProvider(okHandler())
        val vm = chatViewModels.own(ChatViewModel(store, newFactory(fake, transcripts), allowKeyReadPolicy()))
        onMain { vm.openSession(big) }
        awaitTrue { vm.messages.value.size == 6 }
        onMain { vm.onInputChange("hi") }
        onMain { vm.send() }
        awaitTrue { vm.requestTrimmedCount.value == 1 }

        // Fence baseline while still on the big session (post-trim): the big
        // session's projections are never empty, so under the quiet premise
        // (nothing else mutates history/session state between this capture and
        // the fence) the first newer empty generation is the fresh attach's
        // reprojection, never a conflated stale snapshot.
        val preAttachGeneration = vm.messagesProjection.value.generation

        // Attach resets (no stale badge on the new session)...
        onMain { vm.openSession(fresh) }
        // Sync point: the attach itself must be observable before fencing, so
        // the generation wait below can never be satisfied pre-attach.
        awaitTrue(
            diagnostics = {
                "pre-attach wait: currentSessionId=${vm.currentSessionId.value} (want $fresh)" +
                    " status=${vm.sessionState.value.status} trimmed=${vm.requestTrimmedCount.value}" +
                    " messages=${vm.messages.value.size} gen=${vm.messagesProjection.value.generation}"
            },
        ) { vm.currentSessionId.value == fresh }
        // Raw `messages.value.isEmpty()` may catch a conflated stale value;
        // the projection fence requires a strictly newer generation carrying
        // the empty list. 30s is only a safety bound — the wait is
        // event-driven.
        runBlocking {
            withTimeout(30_000) {
                vm.messagesProjection.first {
                    it.generation > preAttachGeneration && it.messages.isEmpty()
                }
            }
        }
        assertEquals(0, vm.requestTrimmedCount.value)
        // ...and an untrimmed send emits 0 through the same path.
        onMain { vm.onInputChange("hello") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 2 }
        assertEquals(0, vm.requestTrimmedCount.value)
    }

    @Test
    fun sessionImpl_mirrorsControllerTrimFlow() {
        val provider = TrimFakeProvider(okHandler())
        var n = 0
        val history = (0 until 6).map { i ->
            val role = if (i % 2 == 0) "user" else "assistant"
            val prefix = if (role == "user") "u$i:" else "a$i:"
            ChatMessage(role = role, text = bigRow(prefix))
        }
        val session = ChatSessionImpl(
            provider = provider,
            policy = TrimAllowPolicy(),
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "trim-${n++}" },
            clock = fixedClock,
            userTimezone = "UTC",
            sessionStart = null,
            systemZone = zoneSupplier,
            initialHistory = history,
        )
        try {
            assertEquals(0, session.requestTrimmedCount.value)
            runBlocking { session.send("hi") }
            assertEquals(1, session.requestTrimmedCount.value)
        } finally {
            session.close()
        }
    }
}
