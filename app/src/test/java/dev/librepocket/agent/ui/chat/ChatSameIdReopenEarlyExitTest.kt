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
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** R9-2: counting session — close double-counts per single teardown job (launchCloseThenFlush closes twice). */
private class EarlyExitCountingSession : ChatSession {
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
    val flushCalls = AtomicInteger()

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
    override fun hasDurableFailures(): Boolean = false
    override suspend fun flushDurable(timeoutMs: Long): Boolean {
        flushCalls.incrementAndGet()
        return true
    }
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = emptyList()
    override fun steer(text: String) = Unit
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

private class EarlyExitIdleSession : ChatSession {
    private val state = MutableStateFlow(ChatUiState(emptyList(), ChatStatus.IDLE, 0, null))
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

/** R9-2 fake: first open attaches the counting session, later opens attach idle; records backfill gate + open count. */
private class EarlyExitSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val oldSession: EarlyExitCountingSession,
    val newSession: EarlyExitIdleSession,
) : ChatSessionProvider {
    val backfillFlags = CopyOnWriteArrayList<Boolean>()
    val openCalls = AtomicInteger()
    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
    override suspend fun storeOrNull(): SessionStore? = transcripts
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = error("unused")
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
        openCalls.incrementAndGet()
        val session: ChatSession = if (backfillFlags.size == 1) oldSession else newSession
        return CreatedSession(oldId, session, endpoint.providerId, endpoint.model)
    }
}

/**
 * R9-2 from-null fence probe: flushDurable blocks on [flushGate] so the eager
 * teardown stays unsettled until the test releases it. Close is immediate
 * (counts only); the background job is close → gated flush → close, so while
 * gated closeCalls==1, flushCalls==1, fence incomplete.
 */
private class FromNullBlockingSession(
    val flushGate: CompletableDeferred<Unit>,
) : ChatSession {
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
    val flushCalls = AtomicInteger()
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
    override fun hasDurableFailures(): Boolean = false
    override suspend fun flushDurable(timeoutMs: Long): Boolean {
        flushCalls.incrementAndGet()
        flushGate.await()
        return true
    }
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = emptyList()
    override fun steer(text: String) = Unit
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

/** R9-2 from-null fake: first open attaches the blocking session, later opens attach idle. */
private class FromNullSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val oldSession: FromNullBlockingSession,
    val newSession: EarlyExitIdleSession,
) : ChatSessionProvider {
    val backfillFlags = CopyOnWriteArrayList<Boolean>()
    val openCalls = AtomicInteger()
    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
    override suspend fun storeOrNull(): SessionStore? = transcripts
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession = error("unused")
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
        openCalls.incrementAndGet()
        val session: ChatSession = if (backfillFlags.size == 1) oldSession else newSession
        return CreatedSession(oldId, session, endpoint.providerId, endpoint.model)
    }
}

/** R9-2 streaming provider with gates (copies SameIdStreamingProvider). */
private class EarlyExitStreamingProvider(
    val streamGate: CompletableDeferred<Unit>,
    val cleanupGate: CompletableDeferred<Unit>,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val base = stripTimeBlock(request.messages.last { it.role == "user" }.text)
        if (base == "q-cancel") {
            emit(StreamEvent.TextDelta(0, 0, "half-cancelled"))
            try {
                streamGate.await()
                emit(StreamEvent.Done("stop"))
            } finally {
                withContext(NonCancellable) { cleanupGate.await() }
            }
        } else {
            emit(StreamEvent.TextDelta(0, 0, "a-next"))
            emit(StreamEvent.Done("stop"))
        }
    }
    override suspend fun listModels(): List<String> = emptyList()
}

private class EarlyExitAnsweringProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "a-next"))
        emit(StreamEvent.Done("stop"))
    }
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R9-2 same-ID reopen early-exit / cancel ownership.
 *
 * - DENY: streaming S1 reopens S1 with key.read DENY — eager teardown owns the
 *   ledger (close==2 from the single job's double-close, flush==1, exactly one
 *   teardown, open never called twice), notice POLICY_DENIED, no leak, no
 *   false INTERRUPTED; slot re-parked with owner==S1; next ALLOW reopen
 *   attaches via the owner-matched slow path (already-durable-only scope;
 *   see inline note).
 * - FromNullOwnerMatched: DENY parks an UNSETTLED teardown (flush gated),
 *   then an immediate ALLOW reopen of the same S1 from null must retain the
 *   fence and take the slow path (no fast open while gated, flush re-check
 *   runs, no false INTERRUPTED); after the gate releases it attaches clean.
 * - Missing: endpoint cleared after attach — invalidation teardown owns the
 *   ledger (close==2/flush==1), reopen sees NO_ENDPOINT, no markers, no leak;
 *   endpoint restore + reopen attaches.
 * - CancelNewChat: streaming reopen cancelled by immediate newChat — teardown
 *   survives (terminal lands after release), no false INTERRUPTED, next reopen
 *   attaches.
 * - CancelOpenOther: streaming reopen cancelled by immediate open-other —
 *   teardown survives, other attaches, S1 terminal lands, reopen S1 attaches.
 */
class ChatSameIdReopenEarlyExitTest {

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
        val dir = Files.createTempDirectory("r92-early-exit").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r92.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(transcripts: FakeSessionStore, fake: LlmProvider) = ChatSessionFactory(
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

    @Suppress("UNCHECKED_CAST")
    private fun pendingSlot(vm: ChatViewModel): Triple<Job?, ChatSession?, String?> {
        val fenceField = ChatViewModel::class.java.getDeclaredField("pendingLedgerFence")
        fenceField.isAccessible = true
        val sessionField = ChatViewModel::class.java.getDeclaredField("pendingLedgerSession")
        sessionField.isAccessible = true
        val ownerField = ChatViewModel::class.java.getDeclaredField("pendingLedgerOwnerSessionId")
        ownerField.isAccessible = true
        // Read on Main (slot is Main-confined); tests call from background, but
        // fields are plain vars — single volatile-ish read is best-effort. To
        // stay deterministic, join via chatMain for the read.
        var out: Triple<Job?, ChatSession?, String?>? = null
        runBlocking { chatMain.run { out = Triple(fenceField.get(vm) as Job?, sessionField.get(vm) as ChatSession?, ownerField.get(vm) as String?) } }
        return checkNotNull(out)
    }

    @Test fun denyEarlyExit_ownsTeardown_andNextOpenAttaches() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val oldSession = EarlyExitCountingSession()
        val newSession = EarlyExitIdleSession()
        val sessions = EarlyExitSessions(transcripts, oldId, oldSession, newSession)
        val policy = InMemoryPolicyStore()
        val vm = chatViewModels.own(ChatViewModel(store, sessions, policy))

        // Attach the streaming ledger (fast path).
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())
        assertEquals(1, sessions.openCalls.get())

        // DENY key.read, then same-ID reopen → auth early-exit before slow settle.
        policy.setRule(PolicyRule("key.read:*", Verdict.DENY, 100))
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 10000) { vm.notice.value == "POLICY_DENIED" }
        // Eager teardown: exactly one job → double-close counts 2, single flush.
        awaitTrue(timeoutMs = 10000) { oldSession.closeCalls.get() == 2 }
        awaitTrue(timeoutMs = 10000) { oldSession.flushCalls.get() == 1 }
        assertEquals(2, oldSession.closeCalls.get())
        assertEquals(1, oldSession.flushCalls.get())
        // Early-exit never reaches sessions.open again (no leak, no second attach).
        assertEquals(listOf(true), sessions.backfillFlags.toList())
        assertEquals(1, sessions.openCalls.get())
        assertNull(vm.currentSessionId.value)
        assertTrue(vm.messages.value.isEmpty())
        // EarlyExitCountingSession never writes transcript rows, so marker
        // assertions here would be vacuous (the store holds only the seeded
        // user row by construction): no dangling-family or interrupted
        // coverage — the backfillFlags/openCalls + slot-owner assertions
        // carry this phase instead.
        // Slot re-parked with owner == prevId (pre-dispatch + auth early-exit ownership).
        awaitTrue(timeoutMs = 10000) {
            val (_, slotSession, owner) = pendingSlot(vm)
            owner == oldId && slotSession === oldSession
        }
        val (fence, _, owner) = pendingSlot(vm)
        assertEquals(oldId, owner)
        assertTrue(fence == null || fence.isCompleted)

        // ALLOW again → next open attaches via the owner-matched slow path
        // (this test awaited teardown completion above, so the retained park
        // is already durable here: fence join + re-check succeed immediately,
        // backfill=true). Scope is intentionally already-durable-only; the
        // unsettled from-null window (teardown incomplete at reopen time) is
        // covered by fromNullOwnerMatched_retainsFence_takesSlowPath below.
        // Known limits: a slow-path defer still clears the fence (next open
        // retries via a fresh read), and a pre-fix fast-path from-null drop
        // would have hard-backfilled unfenced — both now fenced via slow.
        policy.removeRule("key.read:*")
        // Restore default ASK for key.read (removal falls back to *:* ASK → not DENY → allows).
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, true), sessions.backfillFlags.toList())
        // Owner-matched slow path: no second teardown for the same ledger
        // (close stays 2 — single job's double-close), but the parked
        // re-check runs (flush 1→2). A pre-fix fast path would have kept
        // flush==1 with no re-check.
        assertEquals(2, oldSession.closeCalls.get())
        awaitTrue(timeoutMs = 10000) { oldSession.flushCalls.get() == 2 }
        assertEquals(2, oldSession.flushCalls.get())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        // Success consumes the slot.
        awaitTrue(timeoutMs = 10000) {
            val (f2, s2, o2) = pendingSlot(vm)
            f2 == null && s2 == null && o2 == null
        }
    }

    @Test fun missingEndpoint_reopen_warnsAndTeardownSurvives() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val oldSession = EarlyExitCountingSession()
        val newSession = EarlyExitIdleSession()
        val sessions = EarlyExitSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))

        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(1, sessions.openCalls.get())

        // Drop the endpoint: invalidation tears down live (owner job), reopen warns.
        runBlocking { store.clear() }
        awaitTrue(timeoutMs = 10000) { vm.notice.value == "NO_ENDPOINT" }
        awaitTrue(timeoutMs = 10000) { oldSession.closeCalls.get() == 2 }
        awaitTrue(timeoutMs = 10000) { oldSession.flushCalls.get() >= 1 }
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 10000) { vm.notice.value == "NO_ENDPOINT" }
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        // No sessions.open leak beyond the first attach (reopen never reaches open).
        assertEquals(1, sessions.openCalls.get())

        // Restore endpoint → reopen attaches (fast path from null, backfill true).
        runBlocking { store.save(sampleEndpoint()) }
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == oldId }
        assertEquals(2, sessions.openCalls.get())
        assertEquals(listOf(true, true), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
    }

    @Test fun cancelViaNewChat_teardownSurvives_nextReopenAttaches() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val fake = EarlyExitStreamingProvider(streamGate, cleanupGate)
        val factory = factoryWith(transcripts, fake)
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        try {
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }

            // Same-ID reopen fences (blocked on gates), then immediate newChat cancels it.
            onMain { vm.openSession(s1) }
            delay(300)
            onMain { vm.newChat() }
            assertNull(vm.currentSessionId.value)
            assertTrue(vm.messages.value.isEmpty())

            // Teardown survives the lifecycle cancel: release lands the terminal in S1 only.
            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())

            // Next reopen attaches S1 with its terminal (fenceable state, no leak).
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            awaitTrue(timeoutMs = 15000) { vm.messages.value.any { it.text == "half-cancelled" } }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    @Test fun cancelViaOpenOther_teardownSurvives_bothLedgersClean() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val fake = EarlyExitStreamingProvider(streamGate, cleanupGate)
        val factory = factoryWith(transcripts, fake)
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        try {
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }

            // Seed a second transcript row for the immediate open-other.
            val otherId = transcripts.createSession("other", "preset:openai@https://api.openai.com/gpt-4o-mini")
            transcripts.appendEvent(event(otherId, "L2", "user", "hello-other"))
            transcripts.appendEvent(event(otherId, "L2", "assistant", "a-other"))

            onMain { vm.openSession(s1) }
            delay(300)
            onMain { vm.openSession(otherId) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == otherId }

            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            // S1 terminal lands exactly once (teardown survived the cancel), no false pair.
            awaitTrue(timeoutMs = 15000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())

            // Reopen S1 attaches cleanly (next open fenceable, no leak).
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            awaitTrue(timeoutMs = 15000) { vm.messages.value.any { it.text == "half-cancelled" } }
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    /**
     * R9-2 from-null owner-match: DENY parks an UNSETTLED teardown, then an
     * immediate ALLOW reopen of the same S1 from null must retain the fence
     * and take the slow path (fence join + parked re-check), never the fast
     * unfenced open(...,true) hard backfill.
     *
     * - Phase 1 attaches S1 (fast, backfill=true).
     * - Phase 2 DENYs + reopens S1: eager teardown starts but its flush
     *   blocks on flushGate (close==1/flush==1, fence incomplete, notice
     *   POLICY_DENIED, slot owner==S1).
     * - Phase 3 ALLOWs + reopens S1 WITHOUT releasing the gate: a fast path
     *   would open immediately (openCalls 1→2 within 500ms); the fixed slow
     *   path stays fenced (openCalls stays 1, no attach, no INTERRUPTED).
     * - Phase 4 releases the gate: fence join + re-check succeed, open
     *   attaches with backfill=true (flush re-check ran, so flush>=2 while
     *   close stays 2 — exactly one teardown job), no false INTERRUPTED,
     *   slot consumed.
     */
    @Test fun fromNullOwnerMatched_retainsFence_takesSlowPath() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val flushGate = CompletableDeferred<Unit>()
        val oldSession = FromNullBlockingSession(flushGate)
        val newSession = EarlyExitIdleSession()
        val sessions = FromNullSessions(transcripts, oldId, oldSession, newSession)
        val policy = InMemoryPolicyStore()
        val vm = chatViewModels.own(ChatViewModel(store, sessions, policy))
        try {
            // Phase 1: attach S1 (fast path from null, no fence yet).
            onMain { vm.openSession(oldId) }
            awaitTrue { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true), sessions.backfillFlags.toList())
            assertEquals(1, sessions.openCalls.get())

            // Phase 2: DENY + same-ID reopen → eager teardown gated on flushGate.
            policy.setRule(PolicyRule("key.read:*", Verdict.DENY, 100))
            onMain { vm.openSession(oldId) }
            awaitTrue(timeoutMs = 10000) { vm.notice.value == "POLICY_DENIED" }
            awaitTrue(timeoutMs = 10000) { oldSession.flushCalls.get() == 1 }
            awaitTrue(timeoutMs = 10000) { oldSession.closeCalls.get() == 1 }
            assertNull(vm.currentSessionId.value)
            assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
            awaitTrue(timeoutMs = 10000) {
                val (_, slotSession, owner) = pendingSlot(vm)
                owner == oldId && slotSession === oldSession
            }
            val (fenceUnsettled, _, _) = pendingSlot(vm)
            assertTrue(
                "teardown must still be unsettled (flush gated)",
                fenceUnsettled != null && !fenceUnsettled.isCompleted,
            )

            // Phase 3: ALLOW + immediate reopen from null WITHOUT releasing
            // the gate. Slow path must fence (no fast open, no attach).
            policy.removeRule("key.read:*")
            onMain { vm.openSession(oldId) }
            delay(500)
            assertEquals(
                "owner-matched reopen must not fast-open while the fence is unsettled",
                1,
                sessions.openCalls.get(),
            )
            assertNull(vm.currentSessionId.value)
            assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
            awaitTrue(timeoutMs = 10000) {
                val (_, slotSession, owner) = pendingSlot(vm)
                owner == oldId && slotSession === oldSession
            }

            // Phase 4: release → fence join + re-check succeed, attach clean.
            flushGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == oldId }
            awaitTrue(timeoutMs = 15000) { sessions.openCalls.get() == 2 }
            assertEquals(listOf(true, true), sessions.backfillFlags.toList())
            // Exactly one teardown job (double-close==2); the slow-path
            // parked re-check ran (flush>=2), which a fast path never does.
            awaitTrue(timeoutMs = 10000) { oldSession.closeCalls.get() == 2 }
            awaitTrue(timeoutMs = 10000) { oldSession.flushCalls.get() >= 2 }
            assertEquals(2, oldSession.closeCalls.get())
            assertTrue(oldSession.flushCalls.get() >= 2)
            assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
            awaitTrue(timeoutMs = 10000) {
                val (f2, s2, o2) = pendingSlot(vm)
                f2 == null && s2 == null && o2 == null
            }
        } finally {
            flushGate.complete(Unit)
        }
    }
}
