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
import org.junit.Assert.assertFalse
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

    // N1: a second send while the first turn runs gets one atomic Queued
    // verdict (never a stale idle fire-and-forget). Queued is NOT acceptance,
    // so an endpoint switch reclaims the queued text into the recoverable
    // outbox instead of dropping it with the session FIFO (never auto-sent
    // to the new endpoint).
    @Test
    fun busySecondSendQueuedTextIsRecoverableAfterEndpointSwitch() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }

            chatMain.run { vm.sendDirect("second") }
            withTimeout(5_000) { live.steerReceived.await() }
            assertEquals(listOf("second"), live.steered.toList())
            assertEquals(listOf("first"), live.sent.toList())

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { live.closed.await() }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            // Queued-but-unstarted "second" never ran and never reached the
            // new endpoint: it fills the blank box for explicit recovery.
            assertEquals("second", vm.input.value)
            assertEquals(listOf("first"), live.sent.toList())
            assertEquals(1, sessions.createCalls)
        } finally {
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // N1 negative control: newer typing is never overwritten by reclaimed
    // queued text; the queued intent waits in the outbox instead.
    @Test
    fun queuedTextReclaimedToOutboxWhenInputBusyAfterEndpointSwitch() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }

            chatMain.run { vm.sendDirect("second") }
            withTimeout(5_000) { live.steerReceived.await() }
            chatMain.run { vm.onInputChange("new typing") }

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { live.closed.await() }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            assertEquals("new typing", vm.input.value)
            assertEquals(1, vm.pendingRecoveryCount.value)
            // Explicit restore path still returns the queued text untouched.
            chatMain.run { vm.onInputChange("") }
            var restored = false
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertTrue(restored)
            assertEquals("second", vm.input.value)
            assertEquals(listOf("first"), live.sent.toList())
        } finally {
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // N1 opId isolation: two queued sends with identical text stay two
    // distinct recoverable entries (no string-equality merge, no cross-clear);
    // nothing is auto-sent to the new endpoint.
    @Test
    fun multipleQueuedSameTextStayIsolatedAfterSwitch() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }

            chatMain.run { vm.sendDirect("same") }
            chatMain.run { vm.sendDirect("same") }
            // steerReceived is single-shot; poll the FIFO record for both.
            withTimeout(5_000) { while (live.steered.size < 2) delay(1) }
            assertEquals(listOf("same", "same"), live.steered.toList())

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { live.closed.await() }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            assertEquals("same", vm.input.value)
            assertEquals(1, vm.pendingRecoveryCount.value)
            // The second identical text surfaces next: two opIds, not one.
            chatMain.run { vm.onInputChange("") }
            var restored = false
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertTrue(restored)
            assertEquals("same", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
            assertEquals(listOf("first"), live.sent.toList())
            assertEquals(1, sessions.createCalls)
        } finally {
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // N1 FIFO order: distinct queued texts surface in queue order after the
    // switch (oldest fills the box, the rest wait in the outbox).
    @Test
    fun multipleQueuedTextsSurfaceInQueueOrderAfterSwitch() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }

            chatMain.run { vm.sendDirect("second") }
            chatMain.run { vm.sendDirect("third") }
            withTimeout(5_000) { while (live.steered.size < 2) delay(1) }
            assertEquals(listOf("second", "third"), live.steered.toList())

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { live.closed.await() }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            assertEquals("second", vm.input.value)
            assertEquals(1, vm.pendingRecoveryCount.value)
            chatMain.run { vm.onInputChange("") }
            var restored = false
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertTrue(restored)
            assertEquals("third", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
            assertEquals(listOf("first"), live.sent.toList())
            assertEquals(1, sessions.createCalls)
        } finally {
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // N1 explicit-discard control: newChat (no endpoint switch) drops the
    // queued FIFO with the session instead of reclaiming it.
    @Test
    fun queuedTextDroppedOnExplicitNewChat() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }

            chatMain.run { vm.sendDirect("second") }
            withTimeout(5_000) { live.steerReceived.await() }

            chatMain.run { vm.newChat() }
            withTimeout(5_000) { live.closed.await() }
            assertEquals("", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
            assertNull(vm.notice.value)
            assertEquals(listOf("first"), live.sent.toList())
        } finally {
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // Q1: the post-gate busy race queues instead of throwing. B passes the
    // idle pre-check and suspends in the chat.send gate; C admits meanwhile;
    // B resumes into a busy session and must get Queued (never a stale idle
    // fire-and-forget, never lost).
    @Test
    fun postGateBusyRaceQueuesInsteadOfThrowing() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val bArrived = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val releaseC = CompletableDeferred<Unit>()
        sessions.acceptGate = { text ->
            if (text == "B") {
                bArrived.complete(Unit)
                releaseB.await()
            }
        }
        sessions.sendGate = { text ->
            if (text == "C") releaseC.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("B") }
            withTimeout(5_000) { bArrived.await() }

            chatMain.run { vm.sendDirect("C") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) {
                while (live.sent.isEmpty()) delay(1)
            }
            assertEquals(listOf("C"), live.sent.toList())

            releaseB.complete(Unit)
            withTimeout(5_000) { live.steerReceived.await() }
            assertEquals(listOf("B"), live.steered.toList())
            assertEquals(listOf("C"), live.sent.toList())

            releaseC.complete(Unit)
            withTimeout(5_000) { live.sendFinished.await() }
        } finally {
            releaseB.complete(Unit)
            releaseC.complete(Unit)
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

    // F3 observer-first: send started on the old generation while authorize
    // suspends in key.read; endpoint B is saved and the Flow observer
    // invalidates before the policy gate is released. Must fail closed (zero
    // sessions) but NOT silently drop user text: input restored + visible
    // cancellation. (The gate stays closed until the observer has restored
    // input/notice, so this covers the observer-wins ordering; the
    // fresh-read-wins ordering leads to the same invalidate drain and is
    // covered by the R1/R2 pre-accept cancellation tests below. A truly
    // delayed-observer variant is not deterministically constructible with a
    // real DataStore: the observer and the op's fresh read share the same
    // store.observe() flow, so neither ordering can be forced.)
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

    // R1: send suspends in the controller's fresh chat.send gate (AFTER the
    // session handle is acquired, BEFORE the text is appended). An endpoint
    // change in that window must fail closed (zero sends) but keep the
    // unaccepted text recoverable with a visible notice. Before the fix the
    // draft was cleared before session.send(), so this ended with
    // inputEmpty=true, notice=null (red); after the fix the draft survives
    // until startTurn returns (green).
    @Test
    fun sendSuspendedInChatSendGateThenEndpointSwitchRestoresDraft() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptEntered = CompletableDeferred<Unit>()
        val releaseAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            acceptEntered.complete(Unit)
            releaseAccept.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run {
                vm.onInputChange("fresh text")
                vm.send()
            }
            withTimeout(5_000) { acceptEntered.await() }
            withTimeout(5_000) { vm.input.first { it.isEmpty() } }
            assertEquals(1, sessions.createCalls)

            store.save(endpoint().copy(model = "other-model"))

            withTimeout(5_000) { vm.input.first { it == "fresh text" } }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            val cancelled = sessions.awaitCreated(1)
            assertTrue(cancelled.sent.isEmpty())
            assertEquals("fresh text", vm.input.value)

            // The cancelled op must never resume into the new endpoint, even
            // once its gate is released: its session was closed, so startTurn
            // fails closed on the closed check.
            releaseAccept.complete(Unit)
            withTimeout(5_000) { cancelled.closed.await() }
            assertTrue(cancelled.sent.isEmpty())
            assertEquals(1, sessions.createCalls)

            // Explicit resend on the new binding succeeds exactly once.
            chatMain.run { vm.send() }
            val resent = sessions.awaitCreated(2)
            withTimeout(5_000) { resent.sendFinished.await() }
            assertEquals(listOf("fresh text"), resent.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            releaseAccept.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // R1 positive control (post-accept): the text was already appended when
    // the endpoint changes, so the transcript snapshot keeps it and the draft
    // must NOT be restored as unsent input.
    @Test
    fun endpointSwitchDuringRunningTurnKeepsAcceptedTextWithoutDraftRestore() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.sendGate = { releaseTurn.await() }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("accepted text") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }
            withTimeout(5_000) { vm.messages.first { list -> list.any { it.text == "accepted text" } } }

            store.save(endpoint().copy(model = "other-model"))

            withTimeout(5_000) { live.closed.await() }
            assertEquals("", vm.input.value)
            assertNull(vm.notice.value)
            assertTrue(vm.messages.value.any { it.text == "accepted text" })
            assertEquals(listOf("accepted text"), live.sent.toList())
            assertEquals(1, sessions.createCalls)
        } finally {
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // R1 deny control: fresh chat.send DENY is projected by the controller;
    // the unaccepted text stays recoverable with CHAT_SEND_DENIED (key.read
    // passed, so POLICY_DENIED would misdiagnose).
    @Test
    fun chatSendDenyKeepsTextRecoverableWithPolicyNotice() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        sessions.acceptGate = { throw SecurityException("chat.send denied by policy") }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run {
                vm.onInputChange("denied text")
                vm.send()
            }
            withTimeout(5_000) { vm.input.first { it == "denied text" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            // ERROR projection travels via the session collector; input/notice
            // awaits alone give no happens-before edge for _sessionState.
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }
            assertEquals(1, sessions.createCalls)
            val created = sessions.awaitCreated(1)
            assertTrue(created.sent.isEmpty())
            assertEquals("denied text", vm.input.value)
            assertTrue(vm.canRetry)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // N2: sendDirect(B) must not wipe a restored denied draft A. A is stashed
    // to the outbox before the box is cleared; B goes out as a fresh op and
    // B's completion surfaces A back into the box (never auto-sent).
    @Test
    fun deniedDraftSurvivesDirectSendOfNewText() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val denyA = java.util.concurrent.atomic.AtomicBoolean(true)
        sessions.acceptGate = { text ->
            if (text == "A" && denyA.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("A") }
            withTimeout(5_000) { vm.input.first { it == "A" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }

            chatMain.run { vm.sendDirect("B") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("B"), live.sent.toList())
            withTimeout(5_000) { vm.input.first { it == "A" } }
            assertEquals("A", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)

            // A is still a live intent: adopting the box sends it exactly once.
            denyA.set(false)
            chatMain.run { vm.send() }
            withTimeout(5_000) { while (live.sent.size < 2) delay(1) }
            assertEquals(listOf("B", "A"), live.sent.toList())
            assertEquals("", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // N2 retry link: after stashing A and a failed direct send B, retry()
    // still resends the denied A (newer typing untouched) instead of
    // fresh-resending the failed B.
    @Test
    fun stashedDeniedDraftRetriedAfterFailedDirectSend() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val denyA = java.util.concurrent.atomic.AtomicBoolean(true)
        sessions.acceptGate = { text ->
            if (text == "A" && denyA.get()) throw SecurityException("chat.send denied by policy")
        }
        sessions.sendGate = { text ->
            if (text == "B") throw RuntimeException("boom")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("A") }
            withTimeout(5_000) { vm.input.first { it == "A" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }

            // B goes out fresh; A waits stashed. B fails post-accept, so A
            // refills the box via the completion drain.
            chatMain.run { vm.sendDirect("B") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.sendFinished.await() }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }
            assertEquals(listOf("B"), live.sent.toList())
            withTimeout(5_000) { vm.input.first { it == "A" } }

            // Newer typing never blocks the retry link — and is never touched.
            denyA.set(false)
            chatMain.run { vm.onInputChange("C") }
            chatMain.run { vm.retry() }
            withTimeout(5_000) { while (live.sent.size < 2) delay(1) }
            assertEquals(listOf("B", "A"), live.sent.toList())
            assertEquals("C", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // N2: an endpoint-cancelled draft restored to the box also survives a
    // direct send on the new binding (same stash path, different restore
    // origin). A is never sent to either endpoint without explicit action.
    @Test
    fun endpointCancelledDraftSurvivesDirectSendOnNewBinding() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptEntered = CompletableDeferred<Unit>()
        val releaseAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = { text ->
            if (text == "A") {
                acceptEntered.complete(Unit)
                releaseAccept.await()
            }
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("A") }
            withTimeout(5_000) { acceptEntered.await() }
            assertEquals(1, sessions.createCalls)

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { vm.input.first { it == "A" } }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            val old = sessions.awaitCreated(1)
            withTimeout(5_000) { old.closed.await() }

            chatMain.run { vm.sendDirect("B") }
            val live = sessions.awaitCreated(2)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("B"), live.sent.toList())
            assertTrue(old.sent.isEmpty())
            withTimeout(5_000) { vm.input.first { it == "A" } }
            assertEquals("A", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
            assertEquals(2, sessions.createCalls)
        } finally {
            releaseAccept.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // N2 opId isolation: a denied "same" and a later direct "same" are two
    // intents. While B runs, A waits as its own outbox entry (count 1, blank
    // box — not merged into B, not lost); B's completion restores A.
    @Test
    fun deniedDraftWithSameTextSurvivesDirectSend() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val admissions = AtomicInteger()
        val releaseB = CompletableDeferred<Unit>()
        sessions.sendGate = {
            if (admissions.incrementAndGet() == 1) releaseB.await()
        }
        val denySame = java.util.concurrent.atomic.AtomicBoolean(true)
        sessions.acceptGate = { text ->
            if (text == "same" && denySame.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("same") }
            withTimeout(5_000) { vm.input.first { it == "same" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }

            denySame.set(false)
            chatMain.run { vm.sendDirect("same") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.hostEntered.await() }
            assertEquals("", vm.input.value)
            assertEquals(1, vm.pendingRecoveryCount.value)

            releaseB.complete(Unit)
            withTimeout(5_000) { live.sendFinished.await() }
            withTimeout(5_000) { vm.input.first { it == "same" } }
            assertEquals(listOf("same"), live.sent.toList())
            assertEquals(0, vm.pendingRecoveryCount.value)

            // The box holds A (never sent); adopting it sends exactly once.
            chatMain.run { vm.send() }
            withTimeout(5_000) { while (live.sent.size < 2) delay(1) }
            assertEquals(listOf("same", "same"), live.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            releaseB.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // N2 steer path: a fresh steer must not sever the retry link of a visible
    // restored draft. After the steered turn fails, retry() still resends the
    // denied A (adopted, box consumed) instead of fresh-resending the failed Q.
    @Test
    fun deniedDraftRetrySurvivesFreshSteerAndFailedTurn() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val denyCount = AtomicInteger()
        sessions.acceptGate = { text ->
            if (text == "A") {
                denyCount.incrementAndGet()
                throw SecurityException("chat.send denied by policy")
            }
        }
        sessions.sendGate = { text ->
            if (text == "Q") throw RuntimeException("boom")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("A") }
            withTimeout(5_000) { vm.input.first { it == "A" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }
            assertEquals(1, denyCount.get())

            chatMain.run { vm.steer("Q") }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { while (live.sent.isEmpty()) delay(1) }
            // Q's host ends (failed); its completion also frees the fake.
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("Q"), live.sent.toList())
            assertEquals("A", vm.input.value)

            // Retry resends the preserved denied intent A (denied again —
            // still unaccepted, still recoverable), never a fresh Q.
            // Poll: retry() no-ops until the ERROR projection lands.
            withTimeout(5_000) {
                while (denyCount.get() < 2) {
                    chatMain.run { vm.retry() }
                    delay(10)
                }
            }
            assertEquals(listOf("Q"), live.sent.toList())
            withTimeout(5_000) { vm.input.first { it == "A" } }
            assertEquals("A", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // Q2: DENY→retry adopts the failed intent. A successful retry consumes
    // the restored box (no stale draft left); the old code left the sent
    // text sitting in the input.
    @Test
    fun denyRetrySuccessLeavesNoStaleDraft() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val deny = java.util.concurrent.atomic.AtomicBoolean(true)
        sessions.acceptGate = {
            if (deny.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run {
                vm.onInputChange("Q")
                vm.send()
            }
            withTimeout(5_000) { vm.input.first { it == "Q" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }
            assertTrue(vm.canRetry)

            deny.set(false)
            chatMain.run { vm.retry() }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("Q"), live.sent.toList())
            assertEquals("", vm.input.value)
            assertEquals(1, sessions.createCalls)
            // sendFinished fires before the session collector projects IDLE;
            // canRetry needs the IDLE edge.
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.IDLE } }
            assertFalse(vm.canRetry)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // Q2: repeated DENY→retry reuses one opId, so one intent never grows
    // outbox copies. Proved via public API: after allow+retry succeeds, an
    // empty box over a quiet window means nothing drained back.
    @Test
    fun repeatedDenyRetryDoesNotGrowOutbox() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val deny = java.util.concurrent.atomic.AtomicBoolean(true)
        sessions.acceptGate = {
            if (deny.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run {
                vm.onInputChange("Q")
                vm.send()
            }
            withTimeout(5_000) { vm.input.first { it == "Q" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }

            repeat(3) {
                chatMain.run { vm.retry() }
                withTimeout(5_000) { vm.input.first { it == "Q" } }
            }
            assertEquals("Q", vm.input.value)

            deny.set(false)
            chatMain.run { vm.retry() }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("Q"), live.sent.toList())
            // sendFinished fires before the VM join→drain resumes: assert
            // emptiness over a quiet window so a leaked copy would repopulate.
            repeat(10) {
                delay(50)
                assertEquals("", vm.input.value)
            }
            assertEquals(1, sessions.createCalls)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // Q2: retry never clobbers newer typing, and a denied retry stashes the
    // intent instead of deleting it. Denied Q is restored, the user types
    // "newer", and a still-denied retry resends nothing and keeps "newer";
    // the stash is then proved by a manual send whose completion drains Q.
    @Test
    fun retryWithNewerInputPreservesIt() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val deny = java.util.concurrent.atomic.AtomicBoolean(true)
        val denyCalls = AtomicInteger()
        sessions.acceptGate = {
            denyCalls.incrementAndGet()
            if (deny.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("Q") }
            withTimeout(5_000) { vm.input.first { it == "Q" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            chatMain.run { vm.onInputChange("newer") }

            // Still denied: retry resends nothing, stashes Q, keeps "newer".
            // denyCalls proves the retry's op actually ran (not dropped).
            chatMain.run { vm.retry() }
            withTimeout(5_000) {
                while (denyCalls.get() < 2) delay(1)
            }
            val live = sessions.awaitCreated(1)
            assertTrue(live.sent.isEmpty())
            assertEquals("newer", vm.input.value)

            // Allow and send "newer" manually: its completion drains the
            // stashed Q into the box, proving the denied retry kept it.
            deny.set(false)
            chatMain.run { vm.send() }
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("newer"), live.sent.toList())
            withTimeout(5_000) { vm.input.first { it == "Q" } }
            // The drained box is adopted by an explicit send: both delivered.
            chatMain.run { vm.send() }
            withTimeout(5_000) {
                while (live.sent.size < 2) delay(1)
            }
            assertEquals(listOf("newer", "Q"), live.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // Q2: an allowed retry sends the denied intent without touching newer
    // typing. (Companion to the denied variant above.)
    @Test
    fun allowedRetrySendsWithoutTouchingNewerInput() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val deny = java.util.concurrent.atomic.AtomicBoolean(true)
        sessions.acceptGate = {
            if (deny.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("Q") }
            withTimeout(5_000) { vm.input.first { it == "Q" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.ERROR } }
            chatMain.run { vm.onInputChange("newer") }

            deny.set(false)
            chatMain.run { vm.retry() }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) {
                while (live.sent.isEmpty()) delay(1)
            }
            assertEquals(listOf("Q"), live.sent.toList())
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals("newer", vm.input.value)
        } finally {
            chatMain.run { vm.newChat() }
        }
    }

    // Q2: two independent identical texts stay separate through deny+retry.
    // A string-equality ownership check would cross-consume the box; opIds
    // keep each intent distinct.
    @Test
    fun identicalTextsStaySeparateThroughDenyRetry() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val calls = AtomicInteger()
        val deny = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstArrived = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            if (calls.incrementAndGet() == 1) {
                firstArrived.complete(Unit)
                releaseFirst.await()
            }
            if (deny.get()) throw SecurityException("chat.send denied by policy")
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("same") }
            withTimeout(5_000) { firstArrived.await() }
            chatMain.run { vm.sendDirect("same") }
            // Second op denies into the blank box; first still gated.
            withTimeout(5_000) { vm.input.first { it == "same" } }
            withTimeout(5_000) { vm.notice.first { it == "CHAT_SEND_DENIED" } }
            releaseFirst.complete(Unit)
            val live = sessions.awaitCreated(1)
            // Flush Main: op1's deny→stash continuation was queued before
            // this barrier, so after it the outbox deterministically holds
            // op1 and retryableOp is op1 (not op2). The box await below would
            // otherwise pass vacuously on op2's restore alone.
            chatMain.run { }
            // First op denies into the occupied box: stashed, not deleted.
            withTimeout(5_000) { vm.input.first { it == "same" } }
            assertTrue(live.sent.isEmpty())

            deny.set(false)
            // Retry sends one intent; the box keeps the other identical text.
            chatMain.run { vm.retry() }
            withTimeout(5_000) {
                while (live.sent.isEmpty()) delay(1)
            }
            assertEquals(listOf("same"), live.sent.toList())
            assertEquals("same", vm.input.value)
            withTimeout(5_000) { live.sendFinished.await() }
            withTimeout(5_000) { vm.sessionState.first { it.status == ChatStatus.IDLE } }
            // Explicit send delivers the remaining identical text (adopts the
            // box tag by opId, never by string equality); nothing is lost,
            // duplicated, or crossed. IDLE is required: a running turn would
            // steer instead of sending.
            chatMain.run { vm.send() }
            withTimeout(5_000) {
                while (live.sent.size < 2) delay(1)
            }
            assertEquals(listOf("same", "same"), live.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            releaseFirst.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // R2: same generation, two different texts, both suspended pre-accept.
    // An endpoint change must keep BOTH recoverable (single-slot code only
    // restored the second). The oldest fills the box; each explicit send
    // surfaces the next; nothing is auto-sent to the new binding.
    @Test
    fun sameGenerationTwoPendingSendsBothRecoverableAfterEndpointSwitch() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptArrived = AtomicInteger()
        val releaseAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            acceptArrived.incrementAndGet()
            releaseAccept.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            chatMain.run { vm.sendDirect("second") }
            withTimeout(5_000) {
                while (acceptArrived.get() < 2) delay(1)
            }
            assertEquals(1, sessions.createCalls)

            store.save(endpoint().copy(model = "other-model"))

            withTimeout(5_000) { vm.input.first { it == "first" } }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            val cancelled = sessions.awaitCreated(1)
            assertTrue(cancelled.sent.isEmpty())

            releaseAccept.complete(Unit)
            withTimeout(5_000) { cancelled.closed.await() }
            assertTrue(cancelled.sent.isEmpty())
            assertEquals(1, sessions.createCalls)

            chatMain.run { vm.send() }
            val live = sessions.awaitCreated(2)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("first"), live.sent.toList())
            withTimeout(5_000) { vm.input.first { it == "second" } }
            chatMain.run { vm.send() }
            withTimeout(5_000) {
                while (live.sent.size < 2) delay(1)
            }
            assertEquals(listOf("first", "second"), live.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            releaseAccept.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // R2: two identical texts must not cross-clear. The second op admits
    // while the first is still gated; the switch then still recovers the
    // first (single-slot code cleared it via string equality: silent loss).
    @Test
    fun identicalPendingSendsStayIndependentlyRecoverable() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptArrived = AtomicInteger()
        val releaseFirstAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            if (acceptArrived.incrementAndGet() == 1) releaseFirstAccept.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("same") }
            chatMain.run { vm.sendDirect("same") }
            withTimeout(5_000) {
                while (acceptArrived.get() < 2) delay(1)
            }
            val live = sessions.awaitCreated(1)
            withTimeout(5_000) {
                while (live.sent.isEmpty()) delay(1)
            }
            assertEquals(listOf("same"), live.sent.toList())

            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { vm.input.first { it == "same" } }
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }

            releaseFirstAccept.complete(Unit)
            withTimeout(5_000) { live.closed.await() }
            assertEquals(listOf("same"), live.sent.toList())

            chatMain.run { vm.send() }
            val resent = sessions.awaitCreated(2)
            withTimeout(5_000) { resent.sendFinished.await() }
            assertEquals(listOf("same"), resent.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            releaseFirstAccept.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // R2: typing during the wait wins the box, but the cancelled op is kept
    // in the outbox instead of deleted (single-slot code dropped it while
    // sparing the new input).
    @Test
    fun newInputDuringWaitIsPreservedWhileCancelledOpStaysRecoverable() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptEntered = CompletableDeferred<Unit>()
        val releaseAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            acceptEntered.complete(Unit)
            releaseAccept.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            withTimeout(5_000) { acceptEntered.await() }
            chatMain.run { vm.onInputChange("typed-later") }

            store.save(endpoint().copy(model = "other-model"))

            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            assertEquals("typed-later", vm.input.value)
            val cancelled = sessions.awaitCreated(1)
            assertTrue(cancelled.sent.isEmpty())

            releaseAccept.complete(Unit)
            withTimeout(5_000) { cancelled.closed.await() }
            assertTrue(cancelled.sent.isEmpty())
            assertEquals("typed-later", vm.input.value)

            chatMain.run { vm.send() }
            val live = sessions.awaitCreated(2)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("typed-later"), live.sent.toList())
            withTimeout(5_000) { vm.input.first { it == "first" } }
            chatMain.run { vm.send() }
            withTimeout(5_000) {
                while (live.sent.size < 2) delay(1)
            }
            assertEquals(listOf("typed-later", "first"), live.sent.toList())
            assertEquals("", vm.input.value)
        } finally {
            releaseAccept.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // R2 discard semantics: explicit newChat drops pending + recoverable ops;
    // a later send starts clean with nothing draining back into the box.
    @Test
    fun newChatDiscardsPendingAndRecoverableOps() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptArrived = AtomicInteger()
        val releaseAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            acceptArrived.incrementAndGet()
            releaseAccept.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            chatMain.run { vm.sendDirect("second") }
            withTimeout(5_000) {
                while (acceptArrived.get() < 2) delay(1)
            }
            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { vm.input.first { it == "first" } }

            // Explicit newChat drops the outbox (notice cleared). The input
            // box itself is left untouched by newChat (pre-existing box
            // semantics, out of R2 scope): clear it like a user would, then
            // prove nothing drains back after a clean send.
            chatMain.run { vm.newChat() }
            assertNull(vm.notice.value)
            assertEquals("first", vm.input.value)
            chatMain.run { vm.onInputChange("") }

            releaseAccept.complete(Unit)
            assertEquals(1, sessions.createCalls)
            chatMain.run { vm.sendDirect("fresh") }
            val live = sessions.awaitCreated(2)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("fresh"), live.sent.toList())
            // sendFinished fires in the session completion handler BEFORE
            // the VM op's host.join()->drain resumes, so assert emptiness
            // over a quiet window: a missed newChat clear would repopulate
            // the blank box via drain within milliseconds.
            repeat(10) {
                delay(50)
                assertEquals("", vm.input.value)
            }
        } finally {
            releaseAccept.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // Q3: a hidden outbox entry is unreachable until an explicit restore —
    // no new provider request may be required to get it back. first/second/
    // third are cancelled pre-accept; first fills the box, the rest wait.
    // Resending first while typing temp skips the drain; clearing temp still
    // leaves the outbox hidden; only restoreNextRecovered() surfaces it, with
    // zero provider calls; discard drops the head without touching the box.
    @Test
    fun hiddenOutboxSurfacedViaExplicitRestoreWithoutNewRequest() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptArrived = AtomicInteger()
        val releaseAccept = CompletableDeferred<Unit>()
        val releaseTurn = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            acceptArrived.incrementAndGet()
            releaseAccept.await()
        }
        sessions.sendGate = { text ->
            if (text == "first") releaseTurn.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            chatMain.run { vm.sendDirect("second") }
            chatMain.run { vm.sendDirect("third") }
            withTimeout(5_000) {
                while (acceptArrived.get() < 3) delay(1)
            }
            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { vm.input.first { it == "first" } }
            withTimeout(5_000) { vm.pendingRecoveryCount.first { it == 2 } }
            val cancelled = sessions.awaitCreated(1)
            releaseAccept.complete(Unit)
            withTimeout(5_000) { cancelled.closed.await() }
            assertTrue(cancelled.sent.isEmpty())

            // Resend first; type temp during its turn; drain skips non-blank.
            chatMain.run { vm.send() }
            val live = sessions.awaitCreated(2)
            chatMain.run { vm.onInputChange("temp") }
            releaseTurn.complete(Unit)
            withTimeout(5_000) { live.sendFinished.await() }
            assertEquals(listOf("first"), live.sent.toList())
            assertEquals("temp", vm.input.value)
            assertEquals(2, vm.pendingRecoveryCount.value)

            // Clear temp: still idle with no entry — the outbox stays hidden.
            chatMain.run { vm.onInputChange("") }
            assertEquals("", vm.input.value)
            assertEquals(2, vm.pendingRecoveryCount.value)
            assertFalse(vm.canRetry)

            // Explicit restore: oldest first, zero provider calls.
            var restored = false
            val callsBefore = sessions.createCalls
            val sentBefore = live.sent.size
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertTrue(restored)
            assertEquals("second", vm.input.value)
            assertEquals(1, vm.pendingRecoveryCount.value)
            assertEquals(callsBefore, sessions.createCalls)
            assertEquals(sentBefore, live.sent.size)

            // Non-blank restore never overwrites.
            chatMain.run { vm.onInputChange("keep") }
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertFalse(restored)
            assertEquals("keep", vm.input.value)
            chatMain.run { vm.onInputChange("second") }

            // Discard drops the head (third) without touching the box.
            var discarded = false
            chatMain.run { discarded = vm.discardNextRecovered() }
            assertTrue(discarded)
            assertEquals(0, vm.pendingRecoveryCount.value)
            assertEquals("second", vm.input.value)

            // Send the restored text; nothing resurrects afterwards.
            chatMain.run { vm.send() }
            withTimeout(5_000) {
                while (live.sent.size < 2) delay(1)
            }
            assertEquals(listOf("first", "second"), live.sent.toList())
            assertEquals("", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
        } finally {
            releaseAccept.complete(Unit)
            releaseTurn.complete(Unit)
            chatMain.run { vm.newChat() }
        }
    }

    // Q3: a stashed op (cancelled while the box was occupied) is restorable
    // after the box is cleared, with zero provider calls and zero sends.
    @Test
    fun stashedOpRestorableWithoutNetworkAfterBoxCleared() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val sessions = ControlledSessions()
        val acceptEntered = CompletableDeferred<Unit>()
        val releaseAccept = CompletableDeferred<Unit>()
        sessions.acceptGate = {
            acceptEntered.complete(Unit)
            releaseAccept.await()
        }
        val vm = newViewModel(store, sessions)
        try {
            chatMain.run { vm.sendDirect("first") }
            withTimeout(5_000) { acceptEntered.await() }
            chatMain.run { vm.onInputChange("typed-later") }
            store.save(endpoint().copy(model = "other-model"))
            withTimeout(5_000) { vm.notice.first { it == "SEND_CANCELLED_ENDPOINT_CHANGED" } }
            withTimeout(5_000) { vm.pendingRecoveryCount.first { it == 1 } }
            assertEquals("typed-later", vm.input.value)
            val cancelled = sessions.awaitCreated(1)
            releaseAccept.complete(Unit)
            withTimeout(5_000) { cancelled.closed.await() }
            assertTrue(cancelled.sent.isEmpty())
            val callsBefore = sessions.createCalls

            // Occupied box: restore refuses without overwriting.
            var restored = true
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertFalse(restored)
            assertEquals("typed-later", vm.input.value)

            // Clear and restore: the text comes back with no network effect.
            chatMain.run { vm.onInputChange("") }
            chatMain.run { restored = vm.restoreNextRecovered() }
            assertTrue(restored)
            assertEquals("first", vm.input.value)
            assertEquals(0, vm.pendingRecoveryCount.value)
            assertEquals(callsBefore, sessions.createCalls)
            assertTrue(cancelled.sent.isEmpty())
        } finally {
            releaseAccept.complete(Unit)
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
        // R1: suspends/throws BEFORE the fake records the text (production
        // chat.send gate order). Defaults to no-op so older tests keep the
        // previous admit-then-suspend shape via sendGate.
        var acceptGate: suspend (String) -> Unit = {}
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
            val session = RecordingSession(sessionId, sendGate, acceptGate)
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
            val session = RecordingSession(sessionId, sendGate, acceptGate)
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
        private val acceptGate: suspend (String) -> Unit = {},
    ) : ChatSession {
        private val mutableState = MutableStateFlow(
            ChatUiState(emptyList(), ChatStatus.IDLE, 0, null),
        )
        override val uiState = mutableState
        val sent = CopyOnWriteArrayList<String>()
        val steered = CopyOnWriteArrayList<String>()
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

        /**
         * Production order (TurnController.startTurn): fresh chat.send gate
         * BEFORE append. acceptGate suspends/throws before anything is
         * recorded, so pre-accept cancellation denies are observable as zero
         * sends; turn completion is driven by invokeOnCompletion so it fires
         * even when the hosted block never dispatches.
         */
        override suspend fun startTurn(text: String, images: List<dev.librepocket.chat.ChatImageRef>): Job {
            // Production pre-gate admission (TurnController.startTurn): a
            // closed/busy session rejects before the chat.send gate.
            synchronized(this) {
                check(!closedFlag) { "closed" }
                if (busy) throw IllegalStateException("already in flight")
            }
            return admitAfterGate(text, images)
        }

        /**
         * Mirrors TurnController.startOrEnqueue: the busy check and the FIFO
         * record share one lock per check, and a post-gate busy race queues
         * instead of throwing (never fire-and-forget). N1: the FIFO keeps the
         * caller opId so endpoint teardown can reclaim queued text via
         * drainQueued(); close() drops the remainder like production.
         */
        override suspend fun startOrEnqueue(
            text: String,
            images: List<dev.librepocket.chat.ChatImageRef>,
            opId: Long?,
        ): dev.librepocket.chat.TurnStart {
            synchronized(this) {
                check(!closedFlag) { "closed" }
                if (busy) {
                    fakeQueue.addLast(FakeQueued(opId, text))
                    recordQueuedSteer(text)
                    return dev.librepocket.chat.TurnStart.Queued
                }
            }
            try {
                return dev.librepocket.chat.TurnStart.Started(admitAfterGate(text, images))
            } catch (_: IllegalStateException) {
                synchronized(this) {
                    check(!closedFlag) { "closed" }
                    fakeQueue.addLast(FakeQueued(opId, text))
                    recordQueuedSteer(text)
                    return dev.librepocket.chat.TurnStart.Queued
                }
            }
        }

        override fun drainQueued(): List<dev.librepocket.chat.QueuedIntent> = synchronized(this) {
            val out = fakeQueue.map { dev.librepocket.chat.QueuedIntent(it.opId, it.text) }
            fakeQueue.clear()
            out
        }

        private data class FakeQueued(val opId: Long?, val text: String)
        private val fakeQueue = ArrayDeque<FakeQueued>()

        private fun recordQueuedSteer(text: String) {
            steered.add(text)
            steerReceived.complete(text)
        }

        private suspend fun admitAfterGate(text: String, images: List<dev.librepocket.chat.ChatImageRef>): Job {
            try {
                acceptGate(text)
            } catch (denied: SecurityException) {
                synchronized(this) {
                    mutableState.value = mutableState.value.copy(
                        status = ChatStatus.ERROR,
                        error = "denied by policy",
                    )
                }
                throw denied
            }
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
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val host = sessionScope.launch {
                hostEntered.complete(Unit)
                try {
                    sendGate(text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // N2: mirror production provider failure (TurnController
                    // maps a failed turn to ERROR, never silent IDLE): the
                    // completion handler below must not overwrite this.
                    failed.set(true)
                    synchronized(this@RecordingSession) {
                        mutableState.value = mutableState.value.copy(
                            status = ChatStatus.ERROR,
                            error = "provider error",
                        )
                    }
                } finally {
                    hostedTurnActive = false
                }
            }
            hosted = host
            // Close() may win between admission and launch dispatch when the
            // scope is already cancelled: the block above never runs and its
            // finally never clears the flag set at admission.
            if (host.isCancelled) hostedTurnActive = false
            host.invokeOnCompletion {
                synchronized(this) {
                    busy = false
                    if (hosted === host) hosted = null
                    if (!closedFlag && !failed.get()) {
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
            return host
        }

        override suspend fun send(text: String, images: List<dev.librepocket.chat.ChatImageRef>) {
            val host = startTurn(text, images)
            try {
                host.join()
            } catch (cancelled: CancellationException) {
                if (coroutineContext[Job]?.isCancelled == true) throw cancelled
            }
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
            hosted?.cancel()
        }

        // Post-Q1 the VM never calls session.steer(): admission goes through
        // startOrEnqueue only. Kept for the ChatSession interface; records
        // receipt without modeling the controller FIFO.
        override fun steer(text: String) {
            steerReceived.complete(text)
        }

        override fun close() {
            synchronized(this) {
                if (closedFlag) return
                closedFlag = true
                closeCalls.incrementAndGet()
                fakeQueue.clear()
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
