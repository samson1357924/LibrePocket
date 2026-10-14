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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R9-3 deferred-backfill sessionId block: flush-TIMEOUT (writer alive,
 * terminal lands late) vs flush-FAILURE (sealed, terminal never comes) were
 * conflated in `backfillNow = settleOk && durableOk` — the second same-ID
 * reopen settled its fresh idle live trivially and mis-marked the still
 * dangling turn INTERRUPTED, contradicting the late true terminal.
 *
 * - [DelayedOldSession] is the timeout class: `flushDurable` answers false
 *   immediately (no hang) with [hasDurableFailures] == false (writer alive,
 *   unlike the dead-writer stuck session) and `pendingTranscriptRunIds ==
 *   [L]` until the test releases the late terminal.
 * - [BlockMarkingSessions] mirrors [ChatSessionFactory.backfillInterrupted]
 *   exactly (same [BACKFILL_SCAN_LIMIT] window + [findDanglingRunIds] rule)
 *   when its gate is true, so a premature `true` observably mis-marks.
 */
private class DelayedOldSession(
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

    /** Timeout class: immediate false, never a durable failure — the writer is alive. */
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

private class BlockIdleSession : ChatSession {
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

/** R9-3 fake: first open attaches the delayed old live, later opens attach idle; marks like the factory when gated. */
private class BlockMarkingSessions(
    val transcripts: FakeSessionStore,
    val oldId: String,
    val oldSession: DelayedOldSession,
    val newSession: BlockIdleSession,
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
            // Same window + rule as ChatSessionFactory.backfillInterrupted:
            // a gated-true open on a dangling ledger mis-marks exactly once.
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
 * R9-3: attach (fast, backfill=true, ledger still empty) → user row lands →
 * 1st same-ID reopen defers (settle fails) → 2nd same-ID reopen is still
 * premature (fresh idle settles cleanly, old writer alive) and must defer
 * again (pre-fix: backfill=true + exactly one false INTERRUPTED) → late
 * terminal lands → 3rd reopen promotes (ledger proves no dangling) and
 * backfills clean: zero interrupted markers, exactly one final assistant row,
 * no dangling family, no contradiction.
 */
class ChatSameIdReopenBackfillBlockTest {

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
        val dir = Files.createTempDirectory("r93-backfill-block").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r93.preferences_pb") },
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
    private fun blockSize(vm: ChatViewModel): Int {
        val field = ChatViewModel::class.java.getDeclaredField("pendingBackfillBlock")
        field.isAccessible = true
        var out = -1
        runBlocking { chatMain.run { out = (field.get(vm) as HashMap<String, Long>).size } }
        return out
    }

    @Test fun secondPrematureReopen_defersAgain_thirdAfterTerminal_backfillsClean() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        // Ledger starts empty: the live turn's user row lands after attach
        // (so the attach-time backfill=true marks nothing).
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")

        val releaseGate = CompletableDeferred<Unit>()
        val oldSession = DelayedOldSession(releaseGate)
        val newSession = BlockIdleSession()
        val sessions = BlockMarkingSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))

        // #1 attach (fast path from null): ledger empty, nothing to mark.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true), sessions.backfillFlags.toList())
        assertTrue(interruptedMarkers(transcripts, oldId).isEmpty())

        // The live turn admits its user row; the old writer stays alive.
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        // #2 first same-ID reopen: settle fails (flush-timeout) → defer.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 2 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(listOf(true, false), sessions.backfillFlags.toList())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(
            "deferred backfill must not mark the still-unsettled turn",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )
        assertEquals(1, blockSize(vm))

        // #3 second same-ID reopen while still premature: the fresh idle live
        // settles trivially but the old writer is still alive (gate closed,
        // terminal not landed) → must defer again. Pre-fix this opened with
        // backfill=true and left exactly one false INTERRUPTED.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 3 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            "premature second reopen must defer while the block holds",
            listOf(true, false, false),
            sessions.backfillFlags.toList(),
        )
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
        assertTrue(
            "premature backfill would mis-mark the live turn",
            interruptedMarkers(transcripts, oldId).isEmpty(),
        )
        assertEquals(1, blockSize(vm))

        // The old writer lands its true terminal late.
        transcripts.appendEvent(event(oldId, "L", "assistant", "a-final"))
        releaseGate.complete(Unit)

        // #4 third reopen: settle succeeds AND the ledger proves its terminal
        // (no dangling family) → promotion lifts the block, backfill=true is
        // a no-op, and the ledger shows no contradiction.
        onMain { vm.openSession(oldId) }
        awaitTrue(timeoutMs = 15000) { sessions.backfillFlags.size >= 4 }
        awaitTrue(timeoutMs = 10000) { vm.currentSessionId.value == oldId }
        assertEquals(
            listOf(true, false, false, true),
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
        assertEquals(
            "promotion consumes the block (bounded, never a permanent ban)",
            0,
            blockSize(vm),
        )
    }
}
