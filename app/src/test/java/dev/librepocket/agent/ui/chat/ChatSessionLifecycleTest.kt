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
import dev.librepocket.session.SessionStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

        override suspend fun storeOrNull(): SessionStore? = null

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
        val sendFinished = CompletableDeferred<Unit>()
        val steerReceived = CompletableDeferred<String>()
        private var busy = false
        private var closedFlag = false

        override suspend fun send(text: String, images: List<dev.librepocket.chat.ChatImageRef>) {
            synchronized(this) {
                check(!closedFlag) { "closed" }
                if (busy) throw IllegalStateException("already in flight")
                busy = true
                sent.add(text)
                firstSendEntered.complete(Unit)
                mutableState.value = ChatUiState(
                    messages = listOf(UiMessage("u", "user", text, false)),
                    status = ChatStatus.STREAMING,
                    pendingSteerCount = 0,
                    error = null,
                )
            }
            try {
                sendGate(text)
            } finally {
                synchronized(this) {
                    busy = false
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

        override fun cancel() = Unit

        override fun steer(text: String) {
            steerReceived.complete(text)
        }

        override fun close() {
            synchronized(this) {
                if (closedFlag) return
                closedFlag = true
                closed.complete(Unit)
            }
        }
    }
}
