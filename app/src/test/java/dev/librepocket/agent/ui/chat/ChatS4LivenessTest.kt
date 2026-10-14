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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class S4IdleSession(
    val flushCalls: AtomicInteger = AtomicInteger(),
    @Volatile var flushGate: CompletableDeferred<Unit>? = null,
) : ChatSession {
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
    override suspend fun flushDurable(timeoutMs: Long): Boolean {
        flushCalls.incrementAndGet()
        flushGate?.await()
        return true
    }
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

private class S4SealedDeadSession : ChatSession {
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
    override suspend fun flushDurable(timeoutMs: Long): Boolean = false
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = emptyList()
    override fun isTranscriptSealed(): Boolean = true
    override fun isTranscriptWriterCompleted(): Boolean = true
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

private class S4MarkingSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val firstSession: ChatSession,
    val laterSession: ChatSession,
    @Volatile var openGate: CompletableDeferred<Unit>? = null,
    val openEntered: AtomicInteger = AtomicInteger(),
) : ChatSessionProvider {
    val backfillFlags = CopyOnWriteArrayList<Boolean>()
    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
    override suspend fun storeOrNull(): SessionStore? = transcripts
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
    // P0-1 open-full-union: explicit route to the open-backfill-canonical
    // below, honoring backfillInterrupted. allowAsk is intentionally
    // discarded: this is a policy-agnostic test double with no key.read gate.
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession = open(endpoint, sessionId, keyIsCurrent, backfillInterrupted)
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession {
        check(sessionId == oldId) { "only old resume is used" }
        openEntered.incrementAndGet()
        openGate?.await()
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
        val session: ChatSession = if (backfillFlags.size == 1) firstSession else laterSession
        return CreatedSession(oldId, session, endpoint.providerId, endpoint.model)
    }
}

/**
 * S4 P1 expiry/debt pins (VM+store, true ViewModel).
 * - F1: fence==null unexpired outstanding stays live (peek/purge share
 *   outstandingLiveness) – same-ID reopen defers, never promotes.
 * - F2 (S4-R2): openSession preamble tickets sync (generation bump + prev-open
 *   cancel for last-wins) + parks sync for pre-dispatch coverage (single
 *   launch, fence joins exactly once); the lock re-takes authoritatively
 *   (idempotent, never relaunches) so a double-tap while IO hangs never steals
 *   ownership.
 * - F5: UI loadHistory dropped>0 flags all kept assistants (matches Factory).
 */
class ChatS4LivenessTest {
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
        val dir = Files.createTempDirectory("s4-liveness").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "s4.preferences_pb") },
            ),
        )
    }

    private fun awaitTrue(timeoutMs: Long = 15000, cond: () -> Boolean) {
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

    private fun event(sid: String, runId: String, kind: String, text: String) = TranscriptEvent(
        sessionId = sid, runId = runId, kind = kind, text = text, createdAt = 1L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun outstandingSize(vm: ChatViewModel): Int {
        val f = ChatViewModel::class.java.getDeclaredField("outstandingTeardowns")
        f.isAccessible = true
        var out = -1
        runBlocking { chatMain.run { out = (f.get(vm) as HashMap<*, *>).size } }
        return out
    }

    /**
     * F1: fence==null + idle unsealed session (no completion proof) unexpired
     * must stay live. Block expired + ledger dangling + stored dead would
     * promote pre-fix (peek false) with a false INTERRUPTED; post-fix peek
     * shares outstandingLiveness (fence null => true) and defers. Injects the
     * fence-null entry WHILE the expiry settle hangs on flush (after take,
     * before tryPromote) so take cannot consume it – pure peek/purge unity pin.
     */
    @Test fun fenceNullUnexpiredOutstanding_defersNeverPromotes() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val deadSession = S4SealedDeadSession()
        val idleSession = S4IdleSession()
        val sessions = S4MarkingSessions(transcripts, oldId, deadSession, idleSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 9_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())
        transcripts.appendEvent(event(oldId, "L", "user", "q-dead"))
        // First same-ID reopen with sealed-dead writer (flush false) defers + parks block.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
        // Arm hang on the attached idle's flush for the expiry settle hang.
        // Attached after first defer is idleSession (laterSession).
        val hang = CompletableDeferred<Unit>()
        idleSession.flushGate = hang
        // Expire the block.
        fakeNow += 600L
        // Expiry reopen (same-ID, prevLive=idle with hang gate). Settle hangs on
        // T2 join (T2 suspends on flush gate) holding mutex, before tryPromote.
        onMain { vm.openSession(oldId) }
        // Wait until T2 entered flush gate (settle hanging, mutex held).
        awaitTrue(timeoutMs = 15000) { idleSession.flushCalls.get() >= 1 }
        // Inject UNEXPIRED fence-null outstanding while hanging (after take,
        // before tryPromote). Idle unsealed, no streaming – old peek false, new true.
        val injectedIdle = S4IdleSession()
        onMain {
            val f = ChatViewModel::class.java.getDeclaredField("outstandingTeardowns")
            f.isAccessible = true
            val ctor = Class.forName("dev.librepocket.agent.ui.chat.ChatViewModel\$OutstandingTeardown")
                .getDeclaredConstructor(Job::class.java, ChatSession::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            ctor.isAccessible = true
            val entry = ctor.newInstance(null, injectedIdle, false, fakeNow)
            @Suppress("UNCHECKED_CAST")
            (f.get(vm) as HashMap<String, Any>)[oldId] = entry as Any
        }
        assertEquals(1, outstandingSize(vm))
        // Release settle hang; expiry reopen must defer (new) not promote (old).
        hang.complete(Unit)
        idleSession.flushGate = null
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(
            "fence-null unexpired outstanding must defer, never promote",
            listOf(true, false, false),
            sessions.backfillFlags.toList(),
        )
        assertTrue(
            "promoted backfill would mis-mark while fence-null ownership holds",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )
    }

    /**
     * F2: double-tap same-ID while IO hangs. Second preamble must not steal
     * ownership (slot still holds first park); old fence settles exactly once
     * (prevLive flushDurable == 2: T2 background + settle re-check, no second
     * steal re-flush). Hangs on flush (settle hang holding mutex, before
     * tryPromote/open) so the inject/window is deterministic.
     */
    @Test fun doubleTapWhileIoHangs_doesNotSteal_fenceJoinsOnce() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val prevLive = S4IdleSession()
        val later = S4IdleSession()
        val sessions = S4MarkingSessions(transcripts, oldId, prevLive, later, openGate = null)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        // Attach (fast, no hang).
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())
        // Arm hang on the attached prevLive flush for the double-tap settle hang.
        val hang = CompletableDeferred<Unit>()
        prevLive.flushGate = hang
        // First same-ID reopen (slow: detaches prevLive, T2 hangs on flush gate
        // holding mutex via join, before tryPromote/open).
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { prevLive.flushCalls.get() >= 1 }
        // Second double-tap while first hangs on settle (mutex held).
        onMain { vm.openSession(oldId) }
        // Ownership must not be stolen synchronously: first park still holds
        // (pre-fix preamble cleared slot synchronously => null here).
        var slotNonNull = false
        runBlocking {
            chatMain.run {
                val sf = ChatViewModel::class.java.getDeclaredField("pendingLedgerFence")
                sf.isAccessible = true
                val ss = ChatViewModel::class.java.getDeclaredField("pendingLedgerSession")
                ss.isAccessible = true
                slotNonNull = sf.get(vm) != null || ss.get(vm) != null
            }
        }
        assertTrue("second preamble must not clear first park synchronously", slotNonNull)
        // Release: both complete sequentially, last wins. Old fence settles once
        // (T2 + re-check == 2; a steal re-flush would make >=3).
        hang.complete(Unit)
        prevLive.flushGate = null
        try {
            awaitTrue(timeoutMs = 20000) { vm.currentSessionId.value == oldId && sessions.backfillFlags.size >= 2 }
        } catch (e: Exception) {
            runBlocking {
                chatMain.run {
                    @Suppress("UNCHECKED_CAST")
                    val sf = ChatViewModel::class.java.getDeclaredField("pendingLedgerFence")
                    sf.isAccessible = true
                    val ss = ChatViewModel::class.java.getDeclaredField("pendingLedgerSession")
                    ss.isAccessible = true
                    val of = ChatViewModel::class.java.getDeclaredField("outstandingTeardowns")
                    of.isAccessible = true
                    val dbg = "FIRST-COMPLETE-TIMEOUT current=${vm.currentSessionId.value} notice=${vm.notice.value} hist=${vm.messages.value.size} flags=${sessions.backfillFlags.toList()} flush=${prevLive.flushCalls.get()} slotFence=${sf.get(vm) != null} slotSession=${ss.get(vm) != null} outstanding=${(of.get(vm) as HashMap<*, *>).size}"
                    assertTrue(dbg, false)
                }
            }
            throw e
        }
        runBlocking {
            chatMain.run {
                @Suppress("UNCHECKED_CAST")
                val of = ChatViewModel::class.java.getDeclaredField("outstandingTeardowns")
                of.isAccessible = true
                val dbg2 = "current=${vm.currentSessionId.value} notice=${vm.notice.value} hist=${vm.messages.value.size} flags=${sessions.backfillFlags.toList()} flush=${prevLive.flushCalls.get()} outstanding=${(of.get(vm) as HashMap<*, *>).size}"
                // Single settle (T2 + re-check == 2); steal re-flush makes >=3.
                // Second may fast re-attach (size==3) or abort (size==2) – either
                // way old flush stays 2 and current stays oldId (first attached).
                assertTrue(
                    "old detached fence must settle exactly once (no steal re-flush) [$dbg2]",
                    prevLive.flushCalls.get() == 2,
                )
            }
        }
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())
    }

    /**
     * F5: UI loadHistory dropped>0 flags all kept assistants (matches Factory).
     * 1001 pairs => 2002 rows, newest 2000 kept, dropped==2, no markers.
     */
    @Test fun uiHistoryDropped_flagsAllAssistants() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
            for (i in 0 until 1001) {
                transcripts.appendEvent(event(id, "r$i", "user", "u$i"))
                transcripts.appendEvent(event(id, "r$i", "assistant", "a$i"))
            }
            id
        }
        val vault = dev.librepocket.keystore.EncryptedPrefsVault(dev.librepocket.keystore.InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ ->
                object : dev.librepocket.provider.LlmProvider {
                    override val protocol = ProviderProtocol.CHAT_COMPLETIONS
                    override fun stream(request: dev.librepocket.provider.ChatRequest) =
                        kotlinx.coroutines.flow.flow {
                            emit(dev.librepocket.provider.StreamEvent.TextDelta(0, 0, "ok"))
                            emit(dev.librepocket.provider.StreamEvent.Done("stop"))
                        }
                    override suspend fun listModels(): List<String> = emptyList()
                }
            },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        val vm = chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
        onMain { vm.openSession(sid) }
        awaitTrue(timeoutMs = 20000) { vm.messages.value.size == 2000 }
        awaitTrue(timeoutMs = 10000) { vm.historyOmittedCount.value == 2 }
        val assistants = vm.messages.value.filter { it.role == "assistant" }
        assertEquals(1000, assistants.size)
        assertTrue(
            "dropped>0 UI replay must conservatively flag every kept assistant",
            assistants.all { it.hasReasoning },
        )
        assertNotNull(vm.currentSessionId.value)
    }
}
