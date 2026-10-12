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
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import dev.librepocket.chat.stripTimeBlock
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.StreamEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class RaceStreamingProvider(
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

/**
 * R10-1 newChat/open-other → immediate reopen race.
 *
 * Pre-fix: S1 streaming → newChat (invalidate drops the fence, nulls
 * _currentSessionId) → immediate openSession(S1) takes the different-ID fast
 * path (prevId==null, no fence) with backfill=true while the old writer is
 * still alive → factory marks turn L INTERRUPTED, then the late true terminal
 * lands → interrupted + cancelled contradiction.
 *
 * Fixed: the newChat/open-other teardown is retained per source sessionId in
 * outstandingTeardowns; the immediate reopen consults it and takes the
 * slow-path settle (fence join + flushDurable re-check) with R9-3 gated
 * backfill. Unrelated ids never wait.
 */
class ChatNewChatReopenRaceTest {

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
        val dir = Files.createTempDirectory("r10-race").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r10.preferences_pb") },
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
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
    )

    @Test fun newChat_thenImmediateReopen_noFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val vm = chatViewModels.own(
            ChatViewModel(store, factoryWith(transcripts, RaceStreamingProvider(streamGate, cleanupGate)), InMemoryPolicyStore()),
        )
        try {
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }

            // Streaming S1 → newChat → immediate reopen while the old writer is gated.
            onMain { vm.newChat() }
            assertNull(vm.currentSessionId.value)
            onMain { vm.openSession(s1) }
            delay(500)
            assertTrue(
                "immediate reopen while unsettled must not backfill a false INTERRUPTED",
                interruptedMarkers(transcripts, s1).isEmpty(),
            )

            // Release: the old terminal lands exactly once, the fenced reopen attaches it.
            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            awaitTrue(timeoutMs = 15000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })

            // Two more reopens stay clean (settled ledger double-reopen).
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    @Test fun openOther_thenImmediateReopenS1_noFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val vm = chatViewModels.own(
            ChatViewModel(store, factoryWith(transcripts, RaceStreamingProvider(streamGate, cleanupGate)), InMemoryPolicyStore()),
        )
        try {
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }

            val otherId = transcripts.createSession("other", "preset:openai@https://api.openai.com/gpt-4o-mini")
            transcripts.appendEvent(event(otherId, "L2", "user", "hello-other"))
            transcripts.appendEvent(event(otherId, "L2", "assistant", "a-other"))

            // Unrelated open must not wait for S1's live writer: attaches while gated.
            onMain { vm.openSession(otherId) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == otherId }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())

            // Immediate reopen of S1 while its old writer is still gated: no false mark.
            onMain { vm.openSession(s1) }
            delay(500)
            assertTrue(
                "immediate S1 reopen while unsettled must not backfill a false INTERRUPTED",
                interruptedMarkers(transcripts, s1).isEmpty(),
            )

            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            awaitTrue(timeoutMs = 15000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())

            // Second reopen stays clean; the unrelated ledger is untouched.
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })
            assertTrue(interruptedMarkers(transcripts, otherId).isEmpty())
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    /**
     * R11-1: newChat parks the live writer, then the clock advances past the
     * horizon BEFORE the first reopen. Pre-fix [purgeExpiredOutstanding]
     * erased the entry on elapsed time alone → the first reopen lost its
     * outstanding ownership, took the fast path with backfill=true, and marked
     * a false INTERRUPTED while the old writer was still alive; the late true
     * terminal then contradicted it. Post-fix an overdue-but-live entry slides
     * its deadline (never erased on clock alone), so the first reopen still
     * fences via the slow path and defers — no marker before the late terminal.
     */
    @Test fun newChat_thenExpiredFirstReopen_noFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val vm = chatViewModels.own(
            ChatViewModel(store, factoryWith(transcripts, RaceStreamingProvider(streamGate, cleanupGate)), InMemoryPolicyStore()),
        )
        try {
            var fakeNow = 1_000_000L
            onMain {
                vm.backfillClockMs = { fakeNow }
                vm.backfillBlockHorizonMs = 500L
            }
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }

            // Park the live writer via newChat (no backfill block yet — the
            // block is only installed by a failed settle).
            onMain { vm.newChat() }
            assertNull(vm.currentSessionId.value)

            // Advance past the horizon WITHOUT reopening: the parked teardown
            // is still alive (old writer gated). Pre-fix this purge erased it.
            fakeNow += 600L

            // First reopen after expiry: must still fence (slow path) and defer,
            // never fast-path a false INTERRUPTED.
            onMain { vm.openSession(s1) }
            delay(800)
            assertTrue(
                "expired-but-live outstanding must not fast-path a false INTERRUPTED",
                interruptedMarkers(transcripts, s1).isEmpty(),
            )

            // Late true terminal lands exactly once; a second reopen attaches it.
            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            awaitTrue(timeoutMs = 15000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })

            // Double reopen stays clean.
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            onMain { vm.openSession(s1) }
            awaitTrue(timeoutMs = 15000) { vm.currentSessionId.value == s1 }
            assertTrue(interruptedMarkers(transcripts, s1).isEmpty())
            assertEquals(1, rows(transcripts, s1).count { it.kind == "assistant" })
            assertTrue(findDanglingRunIds(rows(transcripts, s1)).isEmpty())
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    /**
     * R14-1 residual shape: shutdown already finished (channel closed, pending
     * drained) while the writer is still inside its blocking store block.
     * The fake models exactly that observable state — sealed + pending empty
     * + IDLE (no streaming evidence, so the fallback branch decides) + writer
     * incomplete — with an instantly-completed fence (bounded teardown ran to
     * completion, proving fence completion alone is not writer death).
     *
     * Fidelity note: durability and completion are tracked SEPARATELY. A
     * timed-out shutdown poisoned durability via interrupt marks
     * ([hasDurableFailures] stays true, [flushDurable] stays false) while the
     * writer itself is still unresolved; only the late COMMIT/rollback flips
     * [writerDone]. Binding them to one flag would walk an unreal
     * settleOk&&durableOk path and hide the divergence.
     */
    private class R14SealedUnfinishedSession(
        val writerDone: AtomicBoolean,
    ) : ChatSession {
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
        /** Timeout-poisoned: the interrupt mark never clears on this object. */
        override fun hasDurableFailures(): Boolean = true
        override suspend fun flushDurable(timeoutMs: Long): Boolean = false
        override fun hasPendingAdmission(): Boolean = false
        override fun pendingTranscriptRunIds(): List<String> = emptyList()
        override fun isTranscriptSealed(): Boolean = true
        override fun isTranscriptWriterCompleted(): Boolean = writerDone.get()
        override fun steer(text: String) = Unit
        override fun close() = Unit
    }

    private class R14IdleSession : ChatSession {
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
        override fun hasDurableFailures(): Boolean = false
        override fun steer(text: String) = Unit
        override fun close() = Unit
    }

    /** Fake provider: marks the ledger like the factory when gated, else attaches fakes. */
    private class R14MarkingSessions(
        val transcripts: FakeSessionStore,
        val oldId: String,
        val oldSession: ChatSession,
        val newSession: ChatSession,
    ) : ChatSessionProvider {
        val backfillFlags = CopyOnWriteArrayList<Boolean>()
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

    /**
     * R14-1 positive: newChat parks the sealed-but-unfinished writer, the
     * clock advances past the horizon with the fence already completed, and
     * the FIRST reopen must still fence (slow path) and defer — never purge
     * on sealed + pending-empty alone and fast-path a false INTERRUPTED.
     * The late COMMIT then lands exactly once; double reopen stays clean.
     */
    @Test fun newChat_thenExpiredReopenSealedUnfinishedWriter_noFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val writerDone = AtomicBoolean(false)
        val oldSession = R14SealedUnfinishedSession(writerDone)
        val sessions = R14MarkingSessions(transcripts, oldId, oldSession, R14IdleSession())
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 5_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }

        // #1 attach: ledger empty, nothing to mark.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())

        // The user row lands; the writer sits in the R14 shape (sealed +
        // pending empty + IDLE + inside its blocking store block).
        transcripts.appendEvent(event(oldId, "L", "user", "q-r14"))

        // Park via newChat. The fake fence completes instantly (bounded
        // teardown ran to completion) — pre-fix the expired entry was still
        // purged on seal + empty alone.
        onMain { vm.newChat() }
        assertNull(vm.currentSessionId.value)

        // Expire WITHOUT reopening: fence completed, writer unfinished.
        fakeNow += 600L

        // FIRST reopen after expiry: must fence (slow path) and defer, never
        // fast-path a false INTERRUPTED while the COMMIT is still possible.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertTrue(
            "sealed-but-unfinished outstanding must not fast-path a false INTERRUPTED",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )

        // Late COMMIT: the true terminal lands; the writer resolves.
        transcripts.appendEvent(event(oldId, "L", "assistant", "a-final"))
        writerDone.set(true)

        // Second reopen: settled ledger attaches clean — no marker, exactly
        // one terminal, no contradiction on double reopen.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false, true), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        assertEquals(1, rows(transcripts, oldId).count { it.kind == "assistant" })
        assertTrue(findDanglingRunIds(rows(transcripts, oldId)).isEmpty())
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 4 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false, true, true), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        assertEquals(1, rows(transcripts, oldId).count { it.kind == "assistant" })
        assertTrue(findDanglingRunIds(rows(transcripts, oldId)).isEmpty())
    }

    /**
     * R14-1 negative control: the same sealed-but-unfinished shape, but the
     * writer resolves WITHOUT a terminal (genuine rollback). Expiry must then
     * promote with exactly one correct INTERRUPTED — the fix retains
     * ownership while unresolved but never over-retains a provably-dead turn.
     */
    @Test fun expiredSealedWriterResolvingWithoutTerminal_marksSingleInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val writerDone = AtomicBoolean(false)
        val oldSession = R14SealedUnfinishedSession(writerDone)
        val sessions = R14MarkingSessions(transcripts, oldId, oldSession, R14IdleSession())
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 6_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }

        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        transcripts.appendEvent(event(oldId, "L", "user", "q-r14-rollback"))
        onMain { vm.newChat() }
        assertNull(vm.currentSessionId.value)
        fakeNow += 600L

        // FIRST reopen while unresolved: defer, no mark.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())

        // Rollback: the writer resolves with no terminal ever landing.
        writerDone.set(true)

        // Expire the renewed block, then reopen: the genuinely stranded turn
        // promotes with exactly one correct INTERRUPTED mark.
        fakeNow += 600L
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false, true), sessions.backfillFlags.toList())
        assertEquals(
            "rolled-back turn must backfill exactly one correct INTERRUPTED",
            1,
            interruptedMarkers(transcripts, oldId).size,
        )

        // Idempotence: a further reopen must not mark twice.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 4 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false, true, true), sessions.backfillFlags.toList())
        assertEquals(
            "the INTERRUPTED mark must stay exactly one",
            1,
            interruptedMarkers(transcripts, oldId).size,
        )
    }
}
