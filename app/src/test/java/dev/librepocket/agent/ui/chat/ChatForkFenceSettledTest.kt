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
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** R10: old ledger whose durability is injected (false = settle fails fast). */
private class FenceSettledOldSession(val durable: Boolean) : ChatSession {
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
    override fun hasDurableFailures(): Boolean = !durable
    override suspend fun flushDurable(timeoutMs: Long): Boolean {
        flushCalls.incrementAndGet()
        return durable
    }
    override fun hasPendingAdmission(): Boolean = false
    override fun pendingTranscriptRunIds(): List<String> = listOf("L")
    override fun steer(text: String) = Unit
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

private class FenceSettledNewSession : ChatSession {
    private val state = MutableStateFlow(
        ChatUiState(emptyList(), ChatStatus.IDLE, 0, null),
    )
    override val uiState: StateFlow<ChatUiState> = state
    val sent = CopyOnWriteArrayList<String>()
    override suspend fun send(text: String, images: List<ChatImageRef>) {
        sent.add(text)
    }
    override suspend fun startTurn(text: String, images: List<ChatImageRef>): Job =
        Job().apply { complete() }
    override suspend fun startOrEnqueue(
        text: String,
        images: List<ChatImageRef>,
        opId: Long?,
    ): TurnStart {
        sent.add(text)
        state.value = ChatUiState(
            messages = listOf(
                UiMessage("u", "user", text, false),
                UiMessage("a", "assistant", "ok", false),
            ),
            status = ChatStatus.IDLE,
            pendingSteerCount = 0,
            error = null,
        )
        return TurnStart.Started(Job().apply { complete() })
    }
    override fun drainQueued(): List<QueuedIntent> = emptyList()
    override fun cancel() = Unit
    override suspend fun flush(timeoutMs: Long): Boolean = true
    override fun steer(text: String) = Unit
    override fun close() = Unit
}

/** R10 fake: open attaches the old ledger, create mints the fork target. */
private class FenceSettledSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val oldSession: ChatSession,
    val newSession: FenceSettledNewSession,
) : ChatSessionProvider {
    val createdNewIds = CopyOnWriteArrayList<String>()
    override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
    override suspend fun storeOrNull(): SessionStore? = transcripts
    // D2 canonical form: this fake mints bare fork targets without hydrating.
    // The VM legitimately passes a non-empty fork prefix here (same-origin
    // switch carries the model prefix in memory); this canned stub session
    // never consumes it — ledger coverage comes from the durable snapshot,
    // pinned by the S2 row assertions below — so the prefix stays explicitly
    // ignored, and the lossy canonical-to-compat delegate is deleted (the
    // 3-arg shim rides the interface default into this body).
    override suspend fun create(
        endpoint: EndpointConfig,
        title: String,
        initialHistory: List<dev.librepocket.provider.ChatMessage>,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        val newId = transcripts.createSession(
            title.take(30),
            "${endpoint.providerId}@https://api.openai.com/${endpoint.model}",
        )
        createdNewIds.add(newId)
        return CreatedSession(newId, newSession, endpoint.providerId, endpoint.model)
    }
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
    ): CreatedSession {
        check(sessionId == oldId) { "only old resume is used" }
        return CreatedSession(oldId, oldSession, endpoint.providerId, endpoint.model)
    }
    // P0-1 open-full-union: explicit route to the open-backfill-canonical
    // below, which keeps this fake's documented gate no-op (pre-seeded
    // sessions, no backfill of its own). allowAsk is intentionally discarded:
    // this is a policy-agnostic test double with no key.read gate.
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        allowAsk: Boolean,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession = open(endpoint, sessionId, keyIsCurrent, backfillInterrupted)
    // D2 canonical form: this fake attaches pre-seeded sessions with no
    // backfill of its own; the gate is intentionally a no-op here (same
    // as the old interface default). Backfill behavior is pinned against
    // the real factory in ChatSameIdReopenFenceTest, not here.
    override suspend fun open(
        endpoint: EndpointConfig,
        sessionId: String,
        keyIsCurrent: suspend () -> Boolean,
        backfillInterrupted: Boolean,
    ): CreatedSession = open(endpoint, sessionId, keyIsCurrent)
}

/**
 * R10: transcript store whose ledger read stalls on demand. Only
 * [getSession] is gated — it is the first store op of the fork's read
 * section, so a gated fork is provably past its (failed) settle and
 * blocked in its read when [getSessionCalls] advances.
 */
private class GatedTranscriptStore : FakeSessionStore() {
    val readGate = CompletableDeferred<Unit>()
    val gateReads = AtomicBoolean(false)
    val getSessionCalls = AtomicInteger()
    override suspend fun getSession(sessionId: String): SessionMeta? {
        if (gateReads.get()) {
            getSessionCalls.incrementAndGet()
            readGate.await()
        }
        return super.getSession(sessionId)
    }
}

private class FenceSettledAnsweringProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "a-next"))
        emit(StreamEvent.Done("stop"))
    }
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R10 fork-fence regression: settle fails fast (flushDurable=false), then
 * the ledger read stalls and a second transport switch lifecycle-cancels
 * the fork mid-read. The fence must still re-park (slot keeps owner +
 * session + wasStreaming) so the next fork re-joins + re-checks and still
 * degrades (dangling skipped, incomplete marker, notice). Pre-fix the
 * unconditional fenceSettled dropped the locals: the next fork copied the
 * orphan row and a reopen stamped a false INTERRUPTED.
 *
 * (No test for the Low "fence CE confusion" item: verified during this round
 * that a target-cancel join stays silent for unrelated joiners — joining an
 * already-dead job never throws (probed for bare and launch jobs), and a
 * mid-join target death still attaches fully degraded on unpatched code
 * (degrade signature identical), so the `catch (CE) { throw }` sites only
 * ever observe genuine caller cancellation. No behavior change was needed.)
 */
class ChatForkFenceSettledTest {

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
        val dir = Files.createTempDirectory("r10-fence-settled").toFile()
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
        var out: Triple<Job?, ChatSession?, String?>? = null
        runBlocking {
            chatMain.run {
                out = Triple(
                    fenceField.get(vm) as Job?,
                    sessionField.get(vm) as ChatSession?,
                    ownerField.get(vm) as String?,
                )
            }
        }
        return checkNotNull(out)
    }

    private fun pendingWasStreaming(vm: ChatViewModel): Boolean {
        var out = false
        runBlocking {
            chatMain.run {
                val field = ChatViewModel::class.java.getDeclaredField("pendingLedgerWasStreaming")
                field.isAccessible = true
                out = field.getBoolean(vm)
            }
        }
        return out
    }

    @Test fun failedSettleThenReadCancel_reparksAndNextForkDegrades() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = GatedTranscriptStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        // Dangling live family: user L without any terminal.
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val oldSession = FenceSettledOldSession(durable = false)
        val newSession = FenceSettledNewSession()
        val sessions = FenceSettledSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))

        // Attach the old ledger.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }

        // M1->M2 parks the fence; its teardown runs fast (flushDurable=false).
        runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4o")) }
        awaitTrue { vm.sessionState.value.messages.isEmpty() }
        awaitTrue {
            val (_, slotSession, owner) = pendingSlot(vm)
            owner == oldId && slotSession === oldSession
        }
        awaitTrue { oldSession.flushCalls.get() >= 1 }
        val flushBase = oldSession.flushCalls.get()

        // Stall the ledger read, then drive the fork: it settles (fails fast)
        // and blocks in getSession — provably past settle, inside read.
        transcripts.gateReads.set(true)
        onMain { vm.sendDirect("q-next") }
        awaitTrue(timeoutMs = 15000) {
            oldSession.flushCalls.get() >= flushBase + 1 && transcripts.getSessionCalls.get() >= 1
        }

        // M2->M3 cancels the fork mid-read (transport replacement, not an
        // explicit discard, so the fence must be re-parked, not dropped).
        runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4-turbo")) }
        awaitTrue(timeoutMs = 10000) {
            val (_, slotSession, owner) = pendingSlot(vm)
            owner == oldId && slotSession === oldSession
        }
        assertTrue(
            "failed-settle cancel must preserve the streaming evidence",
            pendingWasStreaming(vm),
        )

        // Release the read; the next fork must re-join + re-check the
        // re-parked ledger (flush runs again) and still degrade.
        transcripts.readGate.complete(Unit)
        onMain { vm.sendDirect("q-next2") }
        awaitTrue(timeoutMs = 15000) { sessions.createdNewIds.isNotEmpty() }
        val s2 = sessions.createdNewIds.first()
        awaitTrue(timeoutMs = 10000) { newSession.sent.isNotEmpty() }
        awaitTrue(timeoutMs = 10000) { oldSession.flushCalls.get() >= flushBase + 2 }

        val s2rows = rows(transcripts, s2)
        assertTrue(s2rows[0].kind == "system" && s2rows[0].text.startsWith("model switched "))
        assertTrue(s2rows.any { it.text.startsWith("snapshot incomplete ") })
        assertEquals(listOf("q0", "a0"), s2rows.filter { it.kind != "system" }.map { it.text })
        assertTrue(s2rows.none { it.text == "q-cancel" })
        assertTrue(findDanglingRunIds(s2rows).isEmpty())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)

        // Reopen twice: zero new INTERRUPTED markers, still no dangling,
        // and no row growth (no re-copy).
        val reopenFactory = factoryWith(transcripts, FenceSettledAnsweringProvider())
        val m3 = sampleEndpoint().copy(model = "gpt-4-turbo")
        val before = interruptedMarkers(transcripts, s2).size
        val rowsBefore = rows(transcripts, s2).size
        val opened1 = reopenFactory.open(m3, s2)
        try {
            assertEquals(before, interruptedMarkers(transcripts, s2).size)
            assertEquals(rowsBefore, rows(transcripts, s2).size)
            assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
        } finally {
            opened1.session.close()
        }
        val opened2 = reopenFactory.open(m3, s2)
        try {
            assertEquals(before, interruptedMarkers(transcripts, s2).size)
            assertEquals(rowsBefore, rows(transcripts, s2).size)
            assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
        } finally {
            opened2.session.close()
        }
        assertNullSkippedFamily(transcripts, s2)
    }

    private fun assertNullSkippedFamily(store: FakeSessionStore, sid: String) {
        assertTrue(
            "reopen must not add a false INTERRUPTED for the skipped family",
            rows(store, sid).singleOrNull { it.text == "turn L interrupted" } == null,
        )
    }
}
