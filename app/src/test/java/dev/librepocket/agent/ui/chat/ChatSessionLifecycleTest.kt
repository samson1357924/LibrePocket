package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import dev.librepocket.chat.UiMessage
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.session.PrunePolicy
import dev.librepocket.session.PruneResult
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatSessionLifecycleTest {
    private val chatMain = ChatTestMainDispatcher()
    private val chatViewModels = ChatTestViewModelStore()
    private val tmpDirs = ArrayList<File>()
    private val dataScopes = ArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        chatMain.install()
    }

    @After
    fun tearDown() {
        try {
            chatMain.clearViewModels(chatViewModels)
        } finally {
            try {
                cancelAndJoinChatTestScopes(dataScopes)
            } finally {
                try {
                    tmpDirs.forEach { it.deleteRecursively() }
                } finally {
                    chatMain.resetAndClose()
                }
            }
        }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("session-lifecycle-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataScopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "endpoint.preferences_pb") },
            ),
        )
    }

    private fun endpoint() = EndpointConfig(
        providerId = "preset:custom",
        presetId = "custom",
        label = "custom",
        baseUrl = "http://127.0.0.1:4141/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "test-model",
        apiKeyRef = "provider_key/lifecycle-test",
    )

    private fun newViewModel(
        store: EndpointStore,
        sessions: ControlledSessions,
        policy: PolicyStore = InMemoryPolicyStore(),
    ): ChatViewModel = chatViewModels.own(ChatViewModel(store, sessions, policy))

    @Test
    fun newChatCancelsAndFencesCreationAndFreshSendStillWorks() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val createEntered = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val creationCancelled = CompletableDeferred<Unit>()
        sessions.createGate = { ordinal ->
            if (ordinal == 1) {
                createEntered.complete(Unit)
                try {
                    releaseCreate.await()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    creationCancelled.complete(Unit)
                    throw cancelled
                }
            }
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("must not escape old generation") }
            createEntered.await()
            chatMain.run { vm.newChat() }
            creationCancelled.await()
            assertEquals(listOf("session-1"), sessions.createAttemptIds.toList())
            assertTrue(sessions.created.none { it.sessionId == "session-1" })
            assertNull(vm.currentSessionId.value)
            assertEquals(ChatStatus.IDLE, vm.sessionState.value.status)

            chatMain.run { vm.sendDirect("positive control") }
            val fresh = sessions.awaitCreated(2)
            fresh.sendFinished.await()
            assertEquals(listOf("positive control"), fresh.sent.toList())
            assertEquals("session-2", vm.currentSessionId.value)
        } finally {
            releaseCreate.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    @Test
    fun cancelledDeniedPolicyResultCannotOverwriteOrCancelTheNewGeneration() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val policy = DelayedFirstDenyPolicy()
        val vm = newViewModel(store, sessions, policy)
        try {
            chatMain.run { vm.sendDirect("old denied generation") }
            policy.firstEvaluationEntered.await()

            chatMain.run { vm.newChat() }
            chatMain.run { vm.sendDirect("new generation positive control") }
            val fresh = sessions.awaitCreated(1)
            fresh.sendFinished.await()
            assertEquals("session-1", vm.currentSessionId.value)

            policy.releaseFirstEvaluation.complete(Unit)
            policy.firstEvaluationFinished.await()
            assertNull(vm.notice.value)
            assertEquals("session-1", vm.currentSessionId.value)
        } finally {
            policy.releaseFirstEvaluation.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    @Test
    fun concurrentFirstSendsShareCreationAndSecondInputIsSteeredNotDropped() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val createEntered = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val releaseFirstTurn = CompletableDeferred<Unit>()
        sessions.createGate = { ordinal ->
            if (ordinal == 1) {
                createEntered.complete(Unit)
                releaseCreate.await()
            }
        }
        sessions.sendGate = { text ->
            if (text == "first" || text == "second") releaseFirstTurn.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            chatMain.run { vm.sendDirect("second") }
            createEntered.await()
            releaseCreate.complete(Unit)

            val session = sessions.awaitCreated(1)
            session.firstSendEntered.await()
            val steered = withTimeout(5_000) { session.steerReceived.await() }
            assertTrue(steered == "first" || steered == "second")
            assertEquals(1, sessions.createCalls)
            assertEquals(1, sessions.created.size)
            assertEquals(1, session.sent.size)
            assertEquals(setOf("first", "second"), (session.sent + steered).toSet())

            releaseFirstTurn.complete(Unit)
            session.sendFinished.await()
        } finally {
            releaseFirstTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    @Test
    fun endpointMetadataClearClosesAttachedSession() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("establish session") }
            val session = sessions.awaitCreated(1)
            session.sendFinished.await()
            assertEquals("session-1", vm.currentSessionId.value)

            store.clear()
            session.closed.await()
            withTimeout(5_000) {
                vm.currentSessionId.first { it == null }
            }
            assertNull(vm.currentSessionId.value)
            assertTrue(session.closed.isCompleted)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    @Test
    fun openSessionBThenAStaysAtMostRecentGeneration() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val oldAEntered = CompletableDeferred<Unit>()
        val oldACancelled = CompletableDeferred<Unit>()
        sessions.openGate = { id, ordinal ->
            if (id == "A" && ordinal == 1) {
                oldAEntered.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    oldACancelled.complete(Unit)
                    throw cancelled
                }
            }
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.openSession("A") }
            oldAEntered.await()
            chatMain.run { vm.openSession("B") } // cancels/fences the delayed A open
            oldACancelled.await()
            val b = sessions.awaitOpened("B")
            withTimeout(5_000) { vm.currentSessionId.first { it == "B" } }

            chatMain.run { vm.openSession("A") }
            val newestA = sessions.awaitOpened("A")
            withTimeout(5_000) { vm.currentSessionId.first { it == "A" } }
            assertEquals("A", vm.currentSessionId.value)
            assertTrue(b.closed.isCompleted)
            assertTrue(newestA.closed.isCompleted.not())
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // F1: openSession() blocks in loadHistory() before taking sessionMutex.
    // A send() in that window attaches a live same-generation session (N);
    // mounting history must close N (hosted turn + binding), not orphan it.
    // Without the attach-time close, N has closeCalls=0 and keeps its hosted
    // turn running past cancel()/newChat()/clear.
    @Test
    fun openSessionClosesLiveSessionRacingHistoryLoadThenNewChatLeavesNothing() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val history = GatedHistoryStore()
        sessions.backingStore = history
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.openSession("H") }
            history.entered.await()
            chatMain.run { vm.sendDirect("typed while history loads") }
            val live = sessions.awaitCreated(1)
            live.firstSendEntered.await()
            // Wait for the hosted block itself (not just admission): without
            // this, attach()->close() can win before launch runs, the
            // cancelled launch never clears hostedTurnActive, and the later
            // !hostedTurnActive assertion flakes under load (Github-only CI).
            withTimeout(5_000) { live.hostEntered.await() }
            assertTrue(live.hostedTurnActive)

            history.release.complete(Unit)
            val historySession = sessions.awaitOpened("H")
            withTimeout(5_000) { vm.currentSessionId.first { it == "H" } }
            withTimeout(5_000) { live.closed.await() }
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(1, live.closeCalls.get())
            assertTrue(!live.hostedTurnActive)
            assertEquals("H", vm.currentSessionId.value)

            chatMain.run { vm.newChat() }
            assertTrue(historySession.closed.isCompleted)
            assertTrue(!historySession.hostedTurnActive)
            assertTrue(!live.hostedTurnActive)
        } finally {
            history.release.complete(Unit)
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    @Test
    fun cancelAfterRacingOpenLeavesNoHostedTurn() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val history = GatedHistoryStore()
        sessions.backingStore = history
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.openSession("H") }
            history.entered.await()
            chatMain.run { vm.sendDirect("typed while history loads") }
            val live = sessions.awaitCreated(1)
            live.firstSendEntered.await()
            // Same hosted-block barrier as above: close() is fenced after
            // hostEntered, so the cancelled-never-started launch cannot leak
            // hostedTurnActive past close().
            withTimeout(5_000) { live.hostEntered.await() }

            history.release.complete(Unit)
            val historySession = sessions.awaitOpened("H")
            withTimeout(5_000) { vm.currentSessionId.first { it == "H" } }
            withTimeout(5_000) { live.closed.await() }

            chatMain.run { vm.cancel() }
            assertEquals(1, historySession.cancelCalls.get())
            assertEquals(1, live.closeCalls.get())
            withTimeout(5_000) { live.sendFinished.await() }
            assertTrue(!live.hostedTurnActive)
        } finally {
            history.release.complete(Unit)
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    @Test
    fun viewModelClearAfterRacingOpenLeavesNoHostedTurn() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val history = GatedHistoryStore()
        sessions.backingStore = history
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.openSession("H") }
            history.entered.await()
            chatMain.run { vm.sendDirect("typed while history loads") }
            val live = sessions.awaitCreated(1)
            live.firstSendEntered.await()
            // Same hosted-block barrier: ViewModel clear closes the live
            // session, so fence close after the hosted block has started.
            withTimeout(5_000) { live.hostEntered.await() }

            history.release.complete(Unit)
            val historySession = sessions.awaitOpened("H")
            withTimeout(5_000) { vm.currentSessionId.first { it == "H" } }
            withTimeout(5_000) { live.closed.await() }

            chatMain.clearViewModels(chatViewModels)
            assertTrue(live.closed.isCompleted)
            assertTrue(historySession.closed.isCompleted)
            withTimeout(5_000) { live.sendFinished.await() }
            assertTrue(!live.hostedTurnActive)
            assertTrue(!historySession.hostedTurnActive)
        } finally {
            history.release.complete(Unit)
            releaseTurn.complete(Unit)
        }
    }

    // F2: valid endpoint/model change must not blank visible transcript.
    // Completes one turn, switches model, waits for invalidation WITHOUT
    // another send. Before fix visibleMessages=0 (red); after fix 2 (green).
    @Test
    fun endpointChangeKeepsVisibleTranscriptWithoutAnotherSend() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("hello") }
            val live = sessions.awaitCreated(1)
            live.sendFinished.await()
            withTimeout(5_000) { vm.messages.first { it.size == 2 } }
            assertEquals("session-1", vm.currentSessionId.value)

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { live.closed.await() }
            withTimeout(5_000) { vm.sessionState.first { it.messages.isEmpty() } }
            // Allow combine(history+live) to propagate; snapshot keeps 2 visible.
            delay(300)
            assertEquals(2, vm.messages.value.size)
            assertEquals(listOf("hello", "ok"), vm.messages.value.map { it.text })
            assertEquals("session-1", vm.currentSessionId.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // F3 observer-first: send started AFTER the new binding was observed
    // must succeed on the new binding (no silent drop, no stale restore).
    @Test
    fun sendAfterObservedEndpointChangeSucceedsOnNewBinding() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("one") }
            val first = sessions.awaitCreated(1)
            first.sendFinished.await()
            withTimeout(5_000) { vm.messages.first { it.size == 2 } }

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { first.closed.await() }
            withTimeout(5_000) { vm.sessionState.first { it.messages.isEmpty() } }
            delay(300)
            assertEquals(2, vm.messages.value.size)

            chatMain.run { vm.sendDirect("two") }
            val second = sessions.awaitCreated(2)
            second.sendFinished.await()
            assertEquals(listOf("two"), second.sent.toList())
            assertEquals("session-2", vm.currentSessionId.value)
            assertEquals("", vm.input.value)
            assertNull(vm.notice.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // F3 fresh-read-first: send started on old generation, endpoint B saved
    // while authorize suspends. Must fail closed (zero sessions) but NOT
    // silently drop user text: input restored + visible cancellation.
    // Before fix inputEmpty=true, notice=null (red); after fix restored (green).
    @Test
    fun sendRacingEndpointSaveIsNotSilentlyDropped() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val policy = GatedFirstAllowPolicy()
        val vm = newViewModel(store, sessions, policy)
        try {
            chatMain.run {
                vm.onInputChange("fresh text")
                vm.send()
            }
            withTimeout(5_000) { policy.entered.await() }
            withTimeout(5_000) { vm.input.first { it.isEmpty() } }

            store.save(endpoint().copy(model = "other-model"))

            withTimeout(5_000) { vm.input.first { it == "fresh text" } }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            assertEquals(0, sessions.createCalls)
            assertEquals(0, sessions.created.size)
            assertEquals("fresh text", vm.input.value)

            policy.release.complete(Unit)
            chatMain.run { vm.send() }
            val created = sessions.awaitCreated(1)
            created.sendFinished.await()
            assertEquals(listOf("fresh text"), created.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            policy.release.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    private class GatedFirstAllowPolicy : PolicyStore {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val count = AtomicInteger()
        private val delegate = InMemoryPolicyStore()

        override fun evaluate(action: String, resource: String): PolicyDecision =
            delegate.evaluate(action, resource)

        override suspend fun setRule(rule: PolicyRule) = delegate.setRule(rule)
        override suspend fun removeRule(pattern: String) = delegate.removeRule(pattern)
        override suspend fun listRules(): List<PolicyRule> = delegate.listRules()

        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            if (count.incrementAndGet() != 1) return delegate.evaluateFresh(action, resource)
            entered.complete(Unit)
            release.await()
            return delegate.evaluateFresh(action, resource)
        }
    }

    /** Blocks loadHistory() itself (the pre-mutex window), not sessions.open(). */
    private class GatedHistoryStore : SessionStore {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun createSession(title: String, model: String): String = "gated-unused"

        override suspend fun listSessions(): List<SessionMeta> = emptyList()

        override suspend fun getSession(sessionId: String): SessionMeta? =
            SessionMeta(sessionId, "history", 0L, 0L, "test-model")

        override suspend fun appendEvent(event: TranscriptEvent): Long = 0L

        override suspend fun loadEvents(
            sessionId: String,
            afterSeq: Long,
            limit: Int,
        ): List<TranscriptEvent> {
            entered.complete(Unit)
            release.await()
            return emptyList()
        }

        override suspend fun exportJsonl(sessionId: String, destFile: File) = Unit

        override suspend fun importJsonl(srcFile: File): String = "gated-imported"

        override suspend fun prune(policy: PrunePolicy): PruneResult = PruneResult(0, 0)

        override suspend fun deleteSession(sessionId: String) = Unit
    }

    private class DelayedFirstDenyPolicy : PolicyStore {
        val firstEvaluationEntered = CompletableDeferred<Unit>()
        val releaseFirstEvaluation = CompletableDeferred<Unit>()
        val firstEvaluationFinished = CompletableDeferred<Unit>()
        private val evaluationCount = AtomicInteger()
        private val delegate = InMemoryPolicyStore()

        override fun evaluate(action: String, resource: String): PolicyDecision =
            delegate.evaluate(action, resource)

        override suspend fun setRule(rule: PolicyRule) = delegate.setRule(rule)
        override suspend fun removeRule(pattern: String) = delegate.removeRule(pattern)
        override suspend fun listRules(): List<PolicyRule> = delegate.listRules()

        override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
            if (evaluationCount.incrementAndGet() != 1) return delegate.evaluateFresh(action, resource)
            firstEvaluationEntered.complete(Unit)
            try {
                withContext(NonCancellable) { releaseFirstEvaluation.await() }
                return PolicyDecision(Verdict.DENY, null, 0L)
            } finally {
                firstEvaluationFinished.complete(Unit)
            }
        }
    }

    private class ControlledSessions : ChatSessionProvider {
        val created = CopyOnWriteArrayList<RecordingSession>()
        val opened = CopyOnWriteArrayList<RecordingSession>()
        private val createCounter = AtomicInteger()
        private val openCounter = AtomicInteger()
        val createAttemptIds = CopyOnWriteArrayList<String>()
        val createCalls: Int get() = createCounter.get()
        val openCalls: Int get() = openCounter.get()
        var createGate: suspend (Int) -> Unit = {}
        var openGate: suspend (String, Int) -> Unit = { _, _ -> }
        var sendGate: suspend (String) -> Unit = {}
        // F1: non-null only for tests that must block loadHistory() itself
        // (the pre-mutex window), rather than sessions.open() in the mutex.
        var backingStore: SessionStore? = null

        override fun modelFor(endpoint: EndpointConfig): String = endpoint.model

        override suspend fun create(
            endpoint: EndpointConfig,
            title: String,
            keyIsCurrent: suspend () -> Boolean,
        ): CreatedSession {
            val ordinal = createCounter.incrementAndGet()
            val sessionId = "session-$ordinal"
            createAttemptIds.add(sessionId)
            createGate(ordinal)
            val session = RecordingSession(sessionId, sendGate)
            created.add(session)
            return CreatedSession(sessionId, session, endpoint.providerId, modelFor(endpoint))
        }

        override suspend fun open(
            endpoint: EndpointConfig,
            sessionId: String,
            keyIsCurrent: suspend () -> Boolean,
        ): CreatedSession {
            val ordinal = openCounter.incrementAndGet()
            openGate(sessionId, ordinal)
            val session = RecordingSession(sessionId, sendGate)
            opened.add(session)
            return CreatedSession(sessionId, session, endpoint.providerId, modelFor(endpoint))
        }

        override suspend fun storeOrNull(): SessionStore? = backingStore

        suspend fun awaitCreated(ordinal: Int): RecordingSession = withTimeout(5_000) {
            val expectedId = "session-$ordinal"
            while (created.none { it.sessionId == expectedId }) delay(1)
            created.first { it.sessionId == expectedId }
        }

        suspend fun awaitOpened(sessionId: String, ordinal: Int = 1): RecordingSession = withTimeout(5_000) {
            while (opened.count { it.sessionId == sessionId } < ordinal) delay(1)
            opened.filter { it.sessionId == sessionId }[ordinal - 1]
        }
    }

    private class RecordingSession(
        val sessionId: String,
        private val sendGate: suspend (String) -> Unit,
    ) : ChatSession {
        private val mutableState = MutableStateFlow(
            ChatUiState(emptyList(), ChatStatus.IDLE, 0, null),
        )
        override val uiState = mutableState
        val sent = CopyOnWriteArrayList<String>()
        val closed = CompletableDeferred<Unit>()
        val firstSendEntered = CompletableDeferred<Unit>()
        val hostEntered = CompletableDeferred<Unit>()
        val sendFinished = CompletableDeferred<Unit>()
        val steerReceived = CompletableDeferred<String>()
        val closeCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        @Volatile
        var hostedTurnActive = false
            private set
        // Mirrors production ownership (TurnController): the hosted turn runs
        // in a session-owned scope while send() only joins it, so close() and
        // cancel() must cancel the host; cancelling the join caller does not
        // stop the turn, and close() also wipes the session binding.
        private val sessionScope = CoroutineScope(SupervisorJob())
        @Volatile
        private var hosted: Job? = null
        private var busy = false
        private var closedFlag = false

        override suspend fun send(text: String, images: List<dev.librepocket.chat.ChatImageRef>) {
            synchronized(this) {
                check(!closedFlag) { "closed" }
                if (busy) throw IllegalStateException("already in flight")
                busy = true
                sent.add(text)
                firstSendEntered.complete(Unit)
                hostedTurnActive = true
                mutableState.value = ChatUiState(
                    messages = listOf(UiMessage("u", "user", text, false)),
                    status = ChatStatus.STREAMING,
                    pendingSteerCount = 0,
                    error = null,
                )
            }
            val host = sessionScope.launch {
                hostEntered.complete(Unit)
                try {
                    sendGate(text)
                } finally {
                    hostedTurnActive = false
                }
            }
            hosted = host
            // Close() may win between firstSendEntered and launch when the
            // scope is already cancelled: the block above never runs and its
            // finally never clears the flag set before launch. Fail closed
            // here so hostedTurnActive cannot leak true past close().
            if (host.isCancelled) hostedTurnActive = false
            try {
                host.join()
            } catch (cancelled: CancellationException) {
                if (coroutineContext[Job]?.isCancelled == true) throw cancelled
            } finally {
                synchronized(this) {
                    busy = false
                    hosted = null
                    if (!closedFlag) {
                        mutableState.value = ChatUiState(
                            messages = listOf(
                                UiMessage("u", "user", text, false),
                                UiMessage("a", "assistant", "ok", false),
                            ),
                            status = ChatStatus.IDLE,
                            pendingSteerCount = 0,
                            error = null,
                        )
                    }
                    sendFinished.complete(Unit)
                }
            }
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
            hosted?.cancel()
        }

        override fun steer(text: String) {
            steerReceived.complete(text)
        }

        override fun close() {
            synchronized(this) {
                if (closedFlag) return
                closedFlag = true
                closeCalls.incrementAndGet()
                closed.complete(Unit)
            }
            hosted?.cancel()
            sessionScope.cancel()
            // Close means the turn is dead: the flag must read false even if
            // close() lands after hosted assignment but before the hosted
            // block dispatches (its finally then never runs). Idempotent with
            // the hosted finally; no live turn survives scope.cancel().
            hostedTurnActive = false
        }
    }
}
