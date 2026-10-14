package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatImageRef
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import dev.librepocket.chat.QueuedIntent
import dev.librepocket.chat.TurnStart
import dev.librepocket.chat.UiMessage
import dev.librepocket.chat.stripTimeBlock
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** R8-3 T1 stub: streaming stuck until both gates release (copies SwitchStreamingTest). */
private class SameIdStreamingProvider(
    val streamGate: CompletableDeferred<Unit>,
    val cleanupGate: CompletableDeferred<Unit>,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seen = CopyOnWriteArrayList<String>()
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val base = stripTimeBlock(request.messages.last { it.role == "user" }.text)
        seen.add(base)
        if (base == "q-cancel") {
            emit(StreamEvent.TextDelta(0, 0, "half-cancelled"))
            try {
                streamGate.await()
                emit(StreamEvent.Done("stop"))
            } finally {
                withContext(NonCancellable) {
                    cleanupGate.await()
                }
            }
        } else {
            emit(StreamEvent.TextDelta(0, 0, "a-next"))
            emit(StreamEvent.Done("stop"))
        }
    }

    override suspend fun listModels(): List<String> = emptyList()
}

private class SameIdAnsweringProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "a-next"))
        emit(StreamEvent.Done("stop"))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/** R8-3 T2 stub: durability never holds (copies SwitchStreamingTest.TimeoutOldSession). */
private class SameIdStuckSession : ChatSession {
    private val state = MutableStateFlow(
        ChatUiState(
            messages = listOf(UiMessage("u", "user", "q-cancel", false)),
            status = ChatStatus.STREAMING,
            pendingSteerCount = 0,
            error = null,
        ),
    )
    override val uiState: StateFlow<ChatUiState> = state
    val closeCalls = AtomicInteger()

    override suspend fun send(text: String, images: List<ChatImageRef>) = Unit
    override suspend fun startTurn(text: String, images: List<ChatImageRef>): Job =
        Job().apply { complete() }

    override suspend fun startOrEnqueue(
        text: String,
        images: List<ChatImageRef>,
        opId: Long?,
    ): TurnStart = TurnStart.Queued

    override fun drainQueued(): List<QueuedIntent> = emptyList()
    override fun cancel() = Unit
    override suspend fun flush(timeoutMs: Long): Boolean = true
    override fun hasDurableFailures(): Boolean = true
    override suspend fun flushDurable(timeoutMs: Long): Boolean = false
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = listOf("L")
    override fun steer(text: String) = Unit
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

private class SameIdIdleSession : ChatSession {
    private val state = MutableStateFlow(
        ChatUiState(emptyList(), ChatStatus.IDLE, 0, null),
    )
    override val uiState: StateFlow<ChatUiState> = state
    override suspend fun send(text: String, images: List<ChatImageRef>) = Unit
    override suspend fun startTurn(text: String, images: List<ChatImageRef>): Job =
        Job().apply { complete() }

    override suspend fun startOrEnqueue(
        text: String,
        images: List<ChatImageRef>,
        opId: Long?,
    ): TurnStart = TurnStart.Queued

    override fun drainQueued(): List<QueuedIntent> = emptyList()
    override fun cancel() = Unit
    override suspend fun flush(timeoutMs: Long): Boolean = true
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

/** R8-3 T2 fake: first open attaches the stuck session, later opens capture the backfill gate. */
private class SameIdDeferSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val oldSession: SameIdStuckSession,
    val newSession: SameIdIdleSession,
) : ChatSessionProvider {
    val backfillFlags = CopyOnWriteArrayList<Boolean>()
    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
    override suspend fun storeOrNull(): SessionStore? = transcripts
    // D2 canonical form: create is unused in this test; keep it unused.
    // The 3-arg shim rides the interface default into this body.
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        initialHistory: List<dev.librepocket.provider.ChatMessage>,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        check(initialHistory.isEmpty()) { "non-hydrating fake discards fork prefix" }
        error("unused")
    }

    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = open(endpoint, sessionId, keyIsCurrent, true)

    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession {
        check(sessionId == oldId) { "only old resume is used" }
        backfillFlags.add(backfillInterrupted)
        val session: ChatSession = if (backfillFlags.size == 1) oldSession else newSession
        return CreatedSession(oldId, session, endpoint.providerId, endpoint.model)
    }
}

/**
 * R8-3 same-ID reopen fence.
 *
 * - T1: streaming S1 reopens S1 — the slow path fences the settle, so within
 *   500ms there is zero marker and no stale attach; after release exactly one
 *   terminal lands, no false INTERRUPTED, and the replay hydrates it.
 * - T2: flushDurable==false defers (open called with backfill=false, warn
 *   notice, zero markers); the factory gate itself is pinned directly
 *   (false skips markers but still hydrates, a later true retries the
 *   defer; true still marks a dangling ledger: fast path unchanged). The
 *   snapshot-failure half of the shared HISTORY_SNAPSHOT_INCOMPLETE notice
 *   (marker persists, re-warns without re-copying) is pinned in
 *   ChatSnapshotAtomicityTest, not here.
 * - T3: a settled ledger reopens twice with zero new markers.
 */
class ChatSameIdReopenFenceTest {

    private val tmpDirs = ArrayList<File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatViewModels = ChatTestViewModelStore()
    private val chatMain = ChatTestMainDispatcher()

    @Before fun setUp() = chatMain.install()

    @After fun tearDown() {
        try {
            chatMain.clearViewModels(chatViewModels)
        } finally {
            try {
                cancelAndJoinChatTestScopes(scopes)
            } finally {
                try {
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    chatMain.resetAndClose()
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) = runBlocking { chatMain.run(block) }

    private fun sampleEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("r83-same-id").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r83.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(
        transcripts: FakeSessionStore,
        fake: LlmProvider,
    ) = ChatSessionFactory(
        policy = InMemoryPolicyStore(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> fake },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = transcripts
        },
    )

    private fun newVm(
        store: EndpointStore,
        transcripts: FakeSessionStore,
        fake: LlmProvider,
    ): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "test-key-12345678".toCharArray()) }
        val factory = factoryWith(transcripts, fake)
        return chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
    }

    private fun awaitTrue(timeoutMs: Long = 10000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun rows(store: FakeSessionStore, sid: String) =
        ArrayList(store.events).filter { it.sessionId == sid }.sortedBy { it.seq }

    private fun interruptedMarkers(store: FakeSessionStore, sid: String) =
        rows(store, sid).filter { it.kind == "system" && it.text.endsWith("interrupted") }

    private fun event(
        sid: String,
        runId: String,
        kind: String,
        text: String,
        isPartial: Boolean = false,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
        isPartial = isPartial,
    )

    @Test fun sameIdReopenWhileStreaming_fencesSettle_noFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val fake = SameIdStreamingProvider(streamGate, cleanupGate)
        val vm = newVm(store, transcripts, fake)
        try {
            // 1. S1 streaming stuck: DB only user L, UI has partial.
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }
            awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
            awaitTrue { rows(transcripts, s1).any { it.kind == "user" && it.text.startsWith("q-cancel") } }
            assertTrue(
                "cancel terminal must not be durable yet",
                rows(transcripts, s1).none { it.kind == "assistant" },
            )

            // 2. Same-ID reopen: slow path must fence before read/backfill.
            // P0-1 fence: gate-latch (gates still held) + ledger fence
            // (no assistant, no interrupted) + fenced-detach (null id, empty
            // UI). Replaces delay 500. The detach is awaited (bounded) — the
            // reopen parks asynchronously after onMain returns, so an
            // immediate assert would race it.
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 10000) {
                vm.currentSessionId.value == null && vm.messages.value.isEmpty()
            }
            assertTrue("stream gate must still be held", !streamGate.isCompleted)
            assertTrue("cleanup gate must still be held", !cleanupGate.isCompleted)
            assertTrue(
                "slow path must not backfill a false INTERRUPTED while unsettled",
                interruptedMarkers(transcripts, s1).isEmpty(),
            )
            assertTrue(
                "fenced ledger must stay user-only while gates held",
                rows(transcripts, s1).none { it.kind == "assistant" },
            )
            assertNull(
                "slow path must not attach a stale orphan-only hydrate while fenced",
                vm.currentSessionId.value,
            )
            assertTrue(vm.messages.value.isEmpty())

            // 3. Release: terminal settles once, reopen attaches S1 with it.
            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            awaitTrue(timeoutMs = 15000) { vm.messages.value.any { it.text == "half-cancelled" } }
            awaitTrue(timeoutMs = 10000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())
            // Single terminal only (the late true terminal, never a false+true pair).
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    @Test fun sameIdReopenWhenFlushFails_defersBackfillAndWarns() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val oldSession = SameIdStuckSession()
        val newSession = SameIdIdleSession()
        val sessions = SameIdDeferSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))

        // Attach the stuck ledger (fast path: different from null).
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())

        // Same-ID reopen: settle fails (flushDurable==false) → defer.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            "unsettled same-ID reopen must skip this backfill",
            listOf(true, false),
            sessions.backfillFlags.toList(),
        )
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)

        // Factory gate pin (D2, real factory): false skips the backfill but
        // still hydrates, and the next gated open retries the defer; true
        // still marks a dangling ledger.
        val seenGate = CopyOnWriteArrayList<ChatRequest>()
        val gateProvider = object : LlmProvider {
            override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
            override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
                seenGate.add(request)
                emit(StreamEvent.TextDelta(0, 0, "a-next"))
                emit(StreamEvent.Done("stop"))
            }

            override suspend fun listModels(): List<String> = emptyList()
        }
        val gateFactory = factoryWith(transcripts, gateProvider)
        val d1 = transcripts.createSession("g1", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(d1, "r0", "user", "q0"))
        transcripts.appendEvent(event(d1, "r0", "assistant", "a0"))
        transcripts.appendEvent(event(d1, "L", "user", "q-cancel"))
        val skipped = gateFactory.open(sampleEndpoint(), d1, { true }, false)
        try {
            assertTrue(
                "gated open must skip the backfill",
                interruptedMarkers(transcripts, d1).isEmpty(),
            )
            // Hydration still proceeds despite the skipped backfill.
            assertFalse(skipped.historyWithheld)
            assertEquals(0, skipped.historyDroppedCount)
            skipped.session.send("q-new")
            val req = seenGate.single()
            assertEquals(listOf("user", "assistant", "user", "user"), req.messages.map { it.role })
            assertEquals("q0", req.messages[0].text)
            assertEquals("a0", req.messages[1].text)
            assertEquals("q-cancel", req.messages[2].text)
            assertTrue(req.messages[3].text.startsWith("q-new"))
        } finally {
            skipped.session.close()
        }
        // Deferred, not dropped: the next gated open retries and marks exactly L.
        val retried = gateFactory.open(sampleEndpoint(), d1, { true }, true)
        try {
            assertEquals(1, interruptedMarkers(transcripts, d1).size)
            assertEquals("turn L interrupted", interruptedMarkers(transcripts, d1).single().text)
            assertTrue(findDanglingRunIds(rows(transcripts, d1)).isEmpty())
        } finally {
            retried.session.close()
        }
        val d2 = transcripts.createSession("g2", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(d2, "L", "user", "q-cancel"))
        val marked = gateFactory.open(sampleEndpoint(), d2, { true }, true)
        try {
            assertEquals(1, interruptedMarkers(transcripts, d2).size)
        } finally {
            marked.session.close()
        }
    }

    @Test fun settledLedger_doubleReopen_addsZeroMarkers() = runBlocking {
        val transcripts = FakeSessionStore()
        val gateFactory = factoryWith(transcripts, SameIdAnsweringProvider())
        val sid = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "L", "user", "q0"))
        transcripts.appendEvent(event(sid, "L", "assistant", "a0"))

        val before = interruptedMarkers(transcripts, sid).size
        val opened1 = gateFactory.open(sampleEndpoint(), sid)
        try {
            assertEquals(before, interruptedMarkers(transcripts, sid).size)
            assertTrue(findDanglingRunIds(rows(transcripts, sid)).isEmpty())
        } finally {
            opened1.session.close()
        }
        val opened2 = gateFactory.open(sampleEndpoint(), sid)
        try {
            assertEquals(before, interruptedMarkers(transcripts, sid).size)
            assertTrue(findDanglingRunIds(rows(transcripts, sid)).isEmpty())
        } finally {
            opened2.session.close()
        }
        assertNull(
            "settled ledger must never gain an INTERRUPTED marker",
            rows(transcripts, sid).singleOrNull { it.text == "turn L interrupted" },
        )
    }
}
