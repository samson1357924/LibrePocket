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
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ProviderProtocol
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R10-2: the ~30s [PENDING_BACKFILL_BLOCK_MS] deadline is not proof the old
 * writer is dead. Expiry must never promote an unsettled ledger on clock
 * alone — it needs verifiable proof (ledger terminal or writer-dead via seal
 * + empty pending), otherwise it renews the block and keeps deferring with
 * HISTORY_SNAPSHOT_INCOMPLETE. Deadlines run on a monotonic clock seam, so a
 * wall-clock jump cannot prematurely promote.
 *
 * - [ExpiryOldSession] models the timeout class (writer alive, terminal lands
 *   late): `flushDurable` is false until [releaseGate] completes, pending is
 *   `[L]` until released, then it settles to IDLE/empty like a closed writer.
 * - [ExpiryDeadSession] models a provably-dead writer (sealed, never pending,
 *   settle fails): expiry may promote even with a dangling ledger, because its
 *   terminal will never land and the INTERRUPTED mark is then correct.
 */
private class ExpiryOldSession(
    val releaseGate: CompletableDeferred<Unit>,
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

    /** Called by the test after the late terminal lands: the writer is settled. */
    fun markSettled() {
        state.value = state.value.copy(status = ChatStatus.IDLE)
    }

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
    override suspend fun flushDurable(timeoutMs: Long): Boolean = releaseGate.isCompleted
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> =
        if (releaseGate.isCompleted) emptyList() else listOf("L")

    override fun steer(text: String) = Unit
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

private class ExpiryDeadSession : ChatSession {
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
    override fun hasDurableFailures(): Boolean = false
    /** Settle always fails (sealed writer that never lands its terminal). */
    override suspend fun flushDurable(timeoutMs: Long): Boolean = false
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = emptyList()
    override fun isTranscriptSealed(): Boolean = true
    /** Truly-dead writer model: its block resolved, so the R14-1 completion conjunct holds. */
    override fun isTranscriptWriterCompleted(): Boolean = true
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

private class ExpiryIdleSession : ChatSession {
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
    override suspend fun flushDurable(timeoutMs: Long): Boolean = true
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

/**
 * B2 stuck-status sealed writer: sealed + pending empty (dead proof holds)
 * but uiState still sticks at STREAMING after the seal. Expiry must still
 * promote via the dead proof — the stuck status must not shadow it.
 */
private class ExpiryStuckSealedSession : ChatSession {
    private val state = MutableStateFlow(
        ChatUiState(emptyList(), ChatStatus.STREAMING, 0, null),
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
    override suspend fun flushDurable(timeoutMs: Long): Boolean = false
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = emptyList()
    override fun isTranscriptSealed(): Boolean = true
    /** Stuck-status but truly-dead writer model: the R14-1 completion conjunct holds. */
    override fun isTranscriptWriterCompleted(): Boolean = true
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

/**
 * B1 flip session: fresh idle that turns STREAMING while the promotion probe
 * suspends on IO. Models a new dangling landing in the probe window.
 */
private class FlipToStreamingSession : ChatSession {
    private val state = MutableStateFlow(
        ChatUiState(emptyList(), ChatStatus.IDLE, 0, null),
    )
    override val uiState: StateFlow<ChatUiState> = state
    fun flip() {
        state.value = state.value.copy(status = ChatStatus.STREAMING)
    }

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
    override suspend fun flushDurable(timeoutMs: Long): Boolean = true
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> =
        if (state.value.status == ChatStatus.STREAMING) listOf("fresh-dangling") else emptyList()
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

/** B1 flip store: flips the fresh live exactly when the probe reads the tail. */
private class FlipOnProbeStore(
    val inner: FakeSessionStore,
    val flip: () -> Unit,
) : SessionStore by inner {
    /** Test enables only for the expiry probe: earlier markings must not flip early. */
    var enabled = false
    override suspend fun loadTailEvents(sessionId: String, limit: Int): List<TranscriptEvent> {
        if (enabled) flip()
        return inner.loadTailEvents(sessionId, limit)
    }
}

/** B1 flip sessions: first open attaches old, later opens attach the flip live. */
private class FlipMarkingSessions(
    val transcripts: SessionStore,
    val oldId: String,
    val oldSession: ChatSession,
    val flipSession: FlipToStreamingSession,
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
        if (backfillInterrupted) {
            val tail = transcripts.loadTailEvents(sessionId, BACKFILL_SCAN_LIMIT)
            for (runId in findDanglingRunIds(tail)) {
                transcripts.appendEvent(
                    TranscriptEvent(
                        sessionId = sessionId,
                        runId = runId,
                        kind = INTERRUPTED_MARKER_KIND,
                        text = interruptedMarkerText(runId),
                        createdAt = 1L,
                    ),
                )
            }
        }
        val session: ChatSession = if (backfillFlags.size == 1) oldSession else flipSession
        return CreatedSession(oldId, session, endpoint.providerId, endpoint.model)
    }
}

/** Fake: first open attaches the old live, later opens attach idle; marks like the factory when gated. */
private class ExpiryMarkingSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val oldSession: ChatSession,
    val newSession: ExpiryIdleSession,
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
        if (backfillInterrupted) {
            val tail = transcripts.loadTailEvents(sessionId, BACKFILL_SCAN_LIMIT)
            for (runId in findDanglingRunIds(tail)) {
                transcripts.appendEvent(
                    TranscriptEvent(
                        sessionId = sessionId,
                        runId = runId,
                        kind = INTERRUPTED_MARKER_KIND,
                        text = interruptedMarkerText(runId),
                        createdAt = 1L,
                    ),
                )
            }
        }
        val session: ChatSession = if (backfillFlags.size == 1) oldSession else newSession
        return CreatedSession(oldId, session, endpoint.providerId, endpoint.model)
    }
}

class ChatSameIdReopenBackfillExpiryTest {

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
        val dir = Files.createTempDirectory("r102-backfill-expiry").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r102.preferences_pb") },
            ),
        )
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
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun blockSize(vm: ChatViewModel): Int {
        val field = ChatViewModel::class.java.getDeclaredField("pendingBackfillBlock")
        field.isAccessible = true
        var out = -1
        runBlocking { chatMain.run { out = (field.get(vm) as HashMap<String, Long>).size } }
        return out
    }

    /**
     * R10-2 expiry-while-writer-alive: a parked block whose old writer is
     * still alive must renew its deadline and keep deferring — never promote
     * on the clock alone. Covers the wall-clock-jump class (real time passes
     * beyond the horizon while the monotonic seam is frozen: still deferred),
     * the horizon-expiry class (seam advanced past the deadline while the
     * writer still dangles: still deferred + renewed), and the late-terminal
     * class (terminal lands afterwards: the next expiry promotes cleanly with
     * no contradiction).
     */
    @Test fun expiryWhileWriterAlive_renewsDefer_thenLateTerminalPromotesViaExpiry() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")

        val releaseGate = CompletableDeferred<Unit>()
        val oldSession = ExpiryOldSession(releaseGate)
        val newSession = ExpiryIdleSession()
        val sessions = ExpiryMarkingSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        // Short horizon + manual monotonic clock: shrinking the seam must not
        // change the fail-closed behavior, and freezing it must pin the block
        // no matter how much wall-clock passes (jump immunity).
        var fakeNow = 1_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }

        // #1 attach (fast path from null): ledger empty, nothing to mark.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())

        // The live turn admits its user row; the old writer stays alive.
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        // #2 first same-ID reopen: settle fails (flush-timeout) → defer + park.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        assertEquals(1, blockSize(vm))

        // #3 wall-clock jump proxy: real time passes beyond the 500ms horizon
        // while the monotonic seam is frozen. Pre-fix (wall-clock deadline)
        // this reopen would expire the block and mis-mark; post-fix it defers.
        Thread.sleep(800)
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            "wall-clock passage must not expire a monotonic block while the writer lives",
            listOf(true, false, false),
            sessions.backfillFlags.toList(),
        )
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(
            "jump-promoted backfill would mis-mark the live turn",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )
        assertEquals(1, blockSize(vm))

        // #4 horizon expiry while the writer is still alive (seam advanced past
        // the renewed deadline, ledger still dangling): must renew + defer,
        // never promote on the clock alone.
        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 4 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            "expiry while the old writer still dangles must renew the block, not promote",
            listOf(true, false, false, false),
            sessions.backfillFlags.toList(),
        )
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(
            "expiry-promoted backfill would contradict the late terminal",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )
        assertEquals("renewed block must persist (bounded, never dropped unverified)", 1, blockSize(vm))

        // The old writer lands its true terminal late.
        transcripts.appendEvent(event(oldId, "L", "assistant", "a-final"))
        releaseGate.complete(Unit)
        oldSession.markSettled()

        // #5 next expiry with a settled ledger: promotion proves the terminal
        // and backfills clean — no marker, no contradiction, no warning.
        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 5 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            listOf(true, false, false, false, true),
            sessions.backfillFlags.toList(),
        )
        assertTrue(
            "promoted backfill must not add a marker to a settled ledger",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )
        assertEquals(
            "exactly one true terminal (never a false+true pair)",
            1,
            rows(transcripts, oldId).count { it.kind == "assistant" },
        )
        assertTrue(findDanglingRunIds(rows(transcripts, oldId)).isEmpty())
        assertNull(
            "promoted reopen hydrates the settled ledger with no stale warning",
            vm.notice.value,
        )
        assertEquals(0, blockSize(vm))
    }

    /**
     * R10-2 writer-dead proof: a sealed old writer with an empty pending set
     * can never land its terminal late, so expiry promotes even though the
     * ledger still dangles — the single INTERRUPTED mark is correct (never a
     * mis-mark), and the block lifts.
     */
    @Test fun expiryWithSealedDeadWriter_promotesAndMarksInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")

        val deadSession = ExpiryDeadSession()
        val newSession = ExpiryIdleSession()
        val sessions = ExpiryMarkingSessions(transcripts, oldId, deadSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 2_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }

        // #1 attach: ledger empty, nothing to mark.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())

        // The turn's user row lands; its writer is already sealed and will
        // never land a terminal.
        transcripts.appendEvent(event(oldId, "L", "user", "q-dead"))

        // #2 first same-ID reopen: settle fails → defer + park.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        assertEquals(1, blockSize(vm))

        // #3 expiry with writer-dead proof (sealed + pending empty): promotes
        // even though the ledger still dangles. The single mark is correct.
        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            listOf(true, false, true),
            sessions.backfillFlags.toList(),
        )
        assertEquals(
            "dead-writer expiry must mark the never-settling turn exactly once",
            1,
            interruptedMarkers(transcripts, oldId).size,
        )
        assertNull(
            "promoted reopen resolves the dead ledger with no stale warning",
            vm.notice.value,
        )
        assertEquals(0, blockSize(vm))
    }

    /**
     * B2 stuck-status sealed writer: the stored old writer is sealed + pending
     * empty (dead proof holds) but its uiState still sticks at STREAMING. The
     * pre-fix streaming check returned false before the dead proof was ever
     * reached, so expiry never promoted; post-fix dead is decided first and
     * expiry promotes with the single correct INTERRUPTED mark.
     */
    @Test fun expiryWithStuckStreamingSealedWriter_promotesViaSealedDead() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")

        val stuckSealed = ExpiryStuckSealedSession()
        val newSession = ExpiryIdleSession()
        val sessions = ExpiryMarkingSessions(transcripts, oldId, stuckSealed, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 3_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }

        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())

        transcripts.appendEvent(event(oldId, "L", "user", "q-stuck-sealed"))

        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        assertEquals(1, blockSize(vm))

        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            "stuck STREAMING status must not shadow the sealed-dead proof",
            listOf(true, false, true),
            sessions.backfillFlags.toList(),
        )
        assertEquals(
            1,
            interruptedMarkers(transcripts, oldId).size,
        )
        assertNull(vm.notice.value)
        assertEquals(0, blockSize(vm))
    }

    /**
     * B1 probe-window race: the ledger is clean at probe time, but a fresh
     * idle→streaming transition lands while the probe suspends on IO. The
     * pre-fix order (liveness before probe only) promoted on the stale clean
     * snapshot; post-fix re-verifies liveness after the probe and defers.
     */
    @Test fun probeWindowNewDangling_doesNotPromote() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val inner = FakeSessionStore()
        val oldId = inner.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")

        val releaseGate = CompletableDeferred<Unit>()
        val oldSession = ExpiryOldSession(releaseGate)
        val flipSession = FlipToStreamingSession()
        // Flip exactly when the promotion probe reads the tail: pre-probe the
        // fresh live is IDLE, post-probe it is STREAMING with pending evidence.
        val transcripts = FlipOnProbeStore(inner) { flipSession.flip() }
        val sessions = FlipMarkingSessions(transcripts, oldId, oldSession, flipSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 4_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }

        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())

        inner.appendEvent(event(oldId, "L", "user", "q-cancel"))

        // #2 first same-ID reopen: settle fails → defer + park (stored = old).
        // No probe runs here (no block yet), so no flip yet.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertEquals(1, blockSize(vm))

        // The old writer lands its terminal late: ledger clean, stored settled.
        inner.appendEvent(event(oldId, "L", "assistant", "a-final"))
        releaseGate.complete(Unit)
        oldSession.markSettled()

        // Enable the probe-window flip only now: earlier markings must not
        // flip early, or the pre-probe check would already see STREAMING.
        transcripts.enabled = true
        // #3 expiry with a clean ledger BUT a fresh dangling landing inside the
        // probe window: must renew + defer, never promote on the stale snapshot.
        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            "probe-window dangling must fail closed, not promote",
            listOf(true, false, false),
            sessions.backfillFlags.toList(),
        )
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertEquals("renewed block must persist", 1, blockSize(vm))
    }

    /**
     * B3 writer-slot filter: a clean fresh-idle detached (unsealed + no
     * streaming/pending evidence) proves nothing about the old writer and must
     * not occupy the slot ahead of the true culprit; a live or sealed writer
     * is kept, and later cleans never overwrite the first culprit.
     */
    @Test fun cleanFreshIdleDoesNotOccupyWriterSlot() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val sessions = ExpiryMarkingSessions(
            transcripts,
            "unused",
            ExpiryIdleSession(),
            ExpiryIdleSession(),
        )
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        val writerField = ChatViewModel::class.java.getDeclaredField("pendingBackfillWriter")
        writerField.isAccessible = true
        val remember = ChatViewModel::class.java.getDeclaredMethod(
            "rememberBlockWriter",
            String::class.java,
            ChatSession::class.java,
        )
        remember.isAccessible = true
        runBlocking {
            chatMain.run {
                @Suppress("UNCHECKED_CAST")
                val slot = writerField.get(vm) as HashMap<String, ChatSession?>
                slot.clear()
                // Clean fresh-idle (unsealed, IDLE, empty) must not occupy.
                remember.invoke(vm, "s1", ExpiryIdleSession())
                assertEquals(null, slot["s1"])
                // Live evidence occupies.
                val live = ExpiryOldSession(CompletableDeferred<Unit>())
                remember.invoke(vm, "s1", live)
                assertTrue(slot["s1"] === live)
                // Later clean never overwrites the true culprit.
                remember.invoke(vm, "s1", ExpiryIdleSession())
                assertTrue(slot["s1"] === live)
                // Sealed writer occupies an empty slot (dead proof needs it).
                remember.invoke(vm, "s2", ExpiryDeadSession())
                assertTrue(slot["s2"] is ExpiryDeadSession)
                slot.clear()
            }
        }
    }
}
