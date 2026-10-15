package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SuspectFakeStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class Stage3FakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seenRequests = java.util.Collections.synchronizedList(mutableListOf<ChatRequest>())
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        seenRequests.add(request)
        handler(request).collect { emit(it) }
    }
    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * Stage-3 residual pins (R26-2/resume/F-E/P2-1/P2-4/P2-10).
 *
 * Dispatcher note (P1-1): every park here is a gate/latch or a bounded
 * `withTimeout + delay` convergence poll — no fixed `Thread.sleep` guesses.
 * New cases never sleep; the one legacy fixed sleep in
 * [ChatViewModelHistoryTailTest] is documented there as deprecated.
 */
class Stage3ResidualPinTest {

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
                scopes.forEach { it.cancel() }
                runBlocking { scopes.mapNotNull { it.coroutineContext[kotlinx.coroutines.Job] }.joinAll() }
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

    private fun endpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("stage3-residual").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "stage3.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(transcripts: FakeSessionStore, provider: LlmProvider) = ChatSessionFactory(
        policy = allowKeyReadPolicy(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> provider },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = transcripts
        },
    )

    private suspend fun awaitCond(timeoutMs: Long, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(15) }
    }

    private fun ev(sid: String, runId: String, kind: String, text: String) =
        TranscriptEvent(sessionId = sid, runId = runId, kind = kind, text = text, createdAt = 1L)

    /**
     * R26-2 + resume F1/F4: R25 far-marker layout (seq=1 reasoning marker +
     * seq=2..201 200 non-model notices + seq=202 the marker's assistant +
     * 1999 filler = 2000 qualifying, dropped==0) with a full 2000-row window.
     * The model path probes hasReasoningBefore(extraFloor) boundedly and
     * flags every kept assistant (fail-closed); the UI replay stays
     * display-only (no extra probe — its tail loop exits at the 2000-row cap
     * before reaching seq=1) so the oldest assistant keeps
     * hasReasoning=false. Enforcement always uses the model-context flag —
     * the disagreement is honest and documented, never silent. COUNT basis
     * differs by design (model includePartial=false vs UI includePartial=true).
     */
    @Test fun farMarker_uiStaysDisplayOnlyWhileModelFlagsAll() = runBlocking {
        val store = newStore()
        store.save(endpoint())
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // seq=1: far reasoning marker for the oldest kept assistant.
        transcripts.appendEvent(ev(sid, "far-r", "reasoning", ""))
        // seq=2..201: 200 non-model notices (never qualifying, scan load only).
        for (i in 0 until 200) {
            transcripts.appendEvent(ev(sid, "n$i", "retry", "attempt ${i + 1}/3 after 0ms"))
        }
        // seq=202: the marker's assistant (oldest kept qualifying row).
        transcripts.appendEvent(ev(sid, "far-r", "assistant", "a-far"))
        // seq=203..2201: 999 pairs + one trailing user = 1999 filler rows.
        for (i in 0 until 999) {
            transcripts.appendEvent(ev(sid, "f$i", "user", "u$i"))
            transcripts.appendEvent(ev(sid, "f$i", "assistant", "a$i"))
        }
        transcripts.appendEvent(ev(sid, "tail", "user", "u-tail"))
        // Model path first (pure read, no VM): must flag all conservative.
        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(0, loaded.droppedCount)
        assertTrue("far probe must force incomplete, was $loaded", loaded.reasoningIncomplete)
        assertTrue(
            "model path must flag every kept assistant",
            loaded.messages.filter { it.role == "assistant" }.all { it.hasReasoning },
        )
        // True VM replay over the same ledger.
        val provider = Stage3FakeProvider()
        val vm = chatViewModels.own(ChatViewModel(store, factoryWith(transcripts, provider), allowKeyReadPolicy()))
        onMain { vm.openSession(sid) }
        awaitCond(15_000) { vm.messages.value.size == 2000 }
        assertEquals(0, vm.historyOmittedCount.value)
        val oldestAssistant = vm.messages.value.first { it.role == "assistant" }
        assertEquals("a-far", oldestAssistant.text)
        assertFalse(
            "UI replay stays display-only on the far-marker gap (R26-2 known gap); " +
                "enforcement uses the model flag above, never this bit",
            oldestAssistant.hasReasoning,
        )
    }

    /**
     * Resume F2: snapshotSwitchHistory drops the dangling family and rides
     * the incomplete marker in the same atomic batch. Unit-level pin only
     * (store + factory.open, no live VM turn): a VM-level
     * live-turn→fork→marker→reopen streaming-switch run stays uncovered
     * (known limitation, Target) — this pin must never be cited as
     * streaming-switch proof.
     * Current signature
     * returns only the copied-row count (prefix.size) — the internal
     * droppedDangling delta (carried.size - prefix.size) is NOT returned, so
     * the caller (`durableSnapshotOrWarn`) decides the DANGLING_WARNED notice
     * from the bounded marker rescan, not from the count. Returning a small
     * struct (copied + droppedDangling) and deciding directly from the count
     * (rescan kept only as defence in depth) stays Target, not claimed here.
     * This pin proves the durable effect instead: the dropped family rides
     * the incomplete marker and never gains a false INTERRUPTED on reopen.
     */
    @Test fun snapshotSwitch_danglingExcludedRidesIncompleteMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val from = transcripts.createSession("src", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(ev(from, "clean", "user", "q"))
        transcripts.appendEvent(ev(from, "clean", "assistant", "a"))
        transcripts.appendEvent(ev(from, "dangling", "user", "q-stranded"))
        val to = transcripts.createSession("fork", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val copied = snapshotSwitchHistory(
            transcripts, from, to, "gpt-4o-mini", "gpt-4o-mini",
            historyWithheld = false,
        )
        assertEquals("only the clean pair copies", 2, copied)
        assertTrue(
            "dropped-dangling copy must ride the incomplete marker",
            hasIncompleteSnapshotMarker(transcripts, to),
        )
        val forkRows = transcripts.loadEvents(to)
        assertTrue("fork must not carry the stranded user", forkRows.none { it.runId == "dangling" })
        // Reopen of the fork adds zero INTERRUPTED for the excluded family.
        val provider = Stage3FakeProvider()
        val opened = factoryWith(transcripts, provider).open(endpoint(), to)
        try {
            val marks = transcripts.loadEvents(to).filter { it.kind == "system" && it.text.endsWith("interrupted") }
            assertTrue("excluded family must never gain a false INTERRUPTED, was $marks", marks.isEmpty())
        } finally {
            opened.session.close()
        }
    }

    /**
     * Durability F-E: the three non-sticky obligation probes
     * (hasUnsettledAdmission / hasUnsettledPromotion / hasActiveHost) fail
     * closed — a throwing read reports streaming/pending (true), never clean.
     * CancellationException is always rethrown, never mapped to true (see
     * `isLedgerStreamingOrPending` KDoc), pinned by
     * `ledgerProbeFailClosed_cancellationRethrown` below.
     */
    @Test fun ledgerProbeFailClosed_throwingObligationsReadTrue() {
        val store = newStore()
        runBlocking { store.save(endpoint()) }
        val vm = chatViewModels.own(
            ChatViewModel(store, factoryWith(FakeSessionStore(), Stage3FakeProvider()), InMemoryPolicyStore()),
        )
        val m = ChatViewModel::class.java.getDeclaredMethod("isLedgerStreamingOrPending", ChatSession::class.java)
        m.isAccessible = true
        fun throwingSession(throwOn: String): ChatSession = object : ChatSession {
            override val uiState: kotlinx.coroutines.flow.StateFlow<ChatUiState> =
                kotlinx.coroutines.flow.MutableStateFlow(ChatUiState(emptyList(), ChatStatus.IDLE, 0, null))
            override suspend fun send(text: String, images: List<dev.librepocket.chat.ChatImageRef>) = Unit
            override suspend fun startTurn(text: String, images: List<dev.librepocket.chat.ChatImageRef>) =
                throw UnsupportedOperationException()
            override suspend fun startOrEnqueue(
                text: String,
                images: List<dev.librepocket.chat.ChatImageRef>,
                opId: Long?,
            ): dev.librepocket.chat.TurnStart = throw UnsupportedOperationException()
            override fun drainQueued(): List<dev.librepocket.chat.QueuedIntent> = emptyList()
            override fun cancel() = Unit
            override suspend fun flush(timeoutMs: Long): Boolean = true
            override fun hasDurableFailures(): Boolean = false
            override suspend fun flushDurable(timeoutMs: Long): Boolean = true
            override fun steer(text: String) = Unit
            override suspend fun settlePendingAdmission(timeoutMs: Long): Boolean = true
            override fun hasPendingAdmission(): Boolean = false
            override fun hasUnsettledAdmission(): Boolean =
                if (throwOn == "admission") throw RuntimeException("wedged") else false
            override fun hasUnsettledPromotion(): Boolean =
                if (throwOn == "promotion") throw RuntimeException("wedged") else false
            override fun hasActiveHost(): Boolean =
                if (throwOn == "host") throw RuntimeException("wedged") else false
            override fun hasClosedUnsettledAdmission(): Boolean = false
            override fun hasUnsettledCancelReceipts(): Boolean = false
            override fun hasSealedUncompensatedReceipts(): Boolean = false
            override fun pendingTranscriptRunIds(): List<String> = emptyList()
            override fun isTranscriptSealed(): Boolean = false
            override fun isTranscriptWriterCompleted(): Boolean = true
            override fun close() = Unit
        }
        for (name in listOf("admission", "promotion", "host")) {
            var out = false
            runBlocking { chatMain.run { out = m.invoke(vm, throwingSession(name)) as Boolean } }
            assertTrue("wedged $name probe must fail closed to true", out)
        }
    }

    /**
     * Durability F-E (CE rethrow pin): a CancellationException from any of
     * the three non-sticky obligation probes propagates out of
     * `isLedgerStreamingOrPending` — never mapped to fail-closed true (see
     * its KDoc: every catch rethrows CE before its branch contract).
     * Reflection wraps the escape in InvocationTargetException, so the pin
     * asserts on the cause.
     */
    @Test fun ledgerProbeFailClosed_cancellationRethrown() {
        val store = newStore()
        runBlocking { store.save(endpoint()) }
        val vm = chatViewModels.own(
            ChatViewModel(store, factoryWith(FakeSessionStore(), Stage3FakeProvider()), InMemoryPolicyStore()),
        )
        val m = ChatViewModel::class.java.getDeclaredMethod("isLedgerStreamingOrPending", ChatSession::class.java)
        m.isAccessible = true
        fun ceSession(throwOn: String): ChatSession = object : ChatSession {
            override val uiState: kotlinx.coroutines.flow.StateFlow<ChatUiState> =
                kotlinx.coroutines.flow.MutableStateFlow(ChatUiState(emptyList(), ChatStatus.IDLE, 0, null))
            override suspend fun send(text: String, images: List<dev.librepocket.chat.ChatImageRef>) = Unit
            override suspend fun startTurn(text: String, images: List<dev.librepocket.chat.ChatImageRef>) =
                throw UnsupportedOperationException()
            override suspend fun startOrEnqueue(
                text: String,
                images: List<dev.librepocket.chat.ChatImageRef>,
                opId: Long?,
            ): dev.librepocket.chat.TurnStart = throw UnsupportedOperationException()
            override fun drainQueued(): List<dev.librepocket.chat.QueuedIntent> = emptyList()
            override fun cancel() = Unit
            override suspend fun flush(timeoutMs: Long): Boolean = true
            override fun hasDurableFailures(): Boolean = false
            override suspend fun flushDurable(timeoutMs: Long): Boolean = true
            override fun steer(text: String) = Unit
            override suspend fun settlePendingAdmission(timeoutMs: Long): Boolean = true
            override fun hasPendingAdmission(): Boolean = false
            override fun hasUnsettledAdmission(): Boolean =
                if (throwOn == "admission") throw CancellationException("ce-admission") else false
            override fun hasUnsettledPromotion(): Boolean =
                if (throwOn == "promotion") throw CancellationException("ce-promotion") else false
            override fun hasActiveHost(): Boolean =
                if (throwOn == "host") throw CancellationException("ce-host") else false
            override fun hasClosedUnsettledAdmission(): Boolean = false
            override fun hasUnsettledCancelReceipts(): Boolean = false
            override fun hasSealedUncompensatedReceipts(): Boolean = false
            override fun pendingTranscriptRunIds(): List<String> = emptyList()
            override fun isTranscriptSealed(): Boolean = false
            override fun isTranscriptWriterCompleted(): Boolean = true
            override fun close() = Unit
        }
        for (name in listOf("admission", "promotion", "host")) {
            try {
                runBlocking { chatMain.run { m.invoke(vm, ceSession(name)) } }
                fail("CE from wedged $name probe must propagate, never map to true")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                assertTrue("CE cause expected for $name, was ${e.cause}", e.cause is CancellationException)
            }
        }
    }

    /**
     * Tests P2-1: exact-cap history through the FULL factory.open (not just
     * loadModelHistory): 1000 pairs = 2000 qualifying rows, dropped==0, and
     * the first live send keeps the combined window honest.
     */
    @Test fun exactCap2000ViaFactoryOpen_reportsZeroDropped() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        for (i in 0 until 1000) {
            transcripts.appendEvent(ev(sid, "r$i", "user", "u$i"))
            transcripts.appendEvent(ev(sid, "r$i", "assistant", "a$i"))
        }
        val provider = Stage3FakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "fresh"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val opened = factoryWith(transcripts, provider).open(endpoint(), sid)
        try {
            assertEquals(0, opened.historyDroppedCount)
            opened.session.send("q-new")
            val req = provider.seenRequests.single()
            assertTrue("full prefix must reach the request, got ${req.messages.size}", req.messages.size >= 2000)
        } finally {
            opened.session.close()
        }
    }

    /**
     * Tests P2-4 (prune x dangling): a prune cut that drops the stranded
     * user row leaves no dangling family — reopen adds zero INTERRUPTED
     * (no false backfill for a pruned-away family).
     */
    @Test fun pruneCutDanglingUser_reopenAddsNoFalseInterrupted() = runBlocking {
        val transcripts = SuspectFakeStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(ev(sid, "orphan", "user", "q-stranded"))
        for (i in 0 until 10) {
            transcripts.appendEvent(ev(sid, "r$i", "user", "u$i"))
            transcripts.appendEvent(ev(sid, "r$i", "assistant", "a$i"))
        }
        val total = transcripts.loadEvents(sid, 0, 100).size
        val dropped = transcripts.simulatePruneCut(sid, total - 1)
        assertEquals(1, dropped)
        assertTrue(transcripts.isPruneSuspect(sid))
        val provider = Stage3FakeProvider()
        val opened = factoryWith(transcripts, provider).open(endpoint(), sid)
        try {
            val marks = transcripts.loadEvents(sid, 0, 100)
                .filter { it.kind == "system" && it.text.endsWith("interrupted") }
            assertTrue("pruned-away family must not gain INTERRUPTED, was $marks", marks.isEmpty())
        } finally {
            opened.session.close()
        }
    }

    /**
     * Tests P2-4 (prune x far-marker): the R25 bounded probe still fires
     * after a clean prune sweep (suspect cleared, marker still below the
     * extra-page floor) — all kept assistants stay conservative.
     */
    @Test fun pruneAfterFarMarker_probeStillFlagsAll() = runBlocking {
        val transcripts = SuspectFakeStore()
        val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // Same R25 far-marker layout as above (seq=1 marker, 200 notices,
        // assistant at seq=202, 1999 filler).
        transcripts.appendEvent(ev(sid, "far-r", "reasoning", ""))
        for (i in 0 until 200) {
            transcripts.appendEvent(ev(sid, "n$i", "retry", "attempt ${i + 1}/3 after 0ms"))
        }
        transcripts.appendEvent(ev(sid, "far-r", "assistant", "a-far"))
        for (i in 0 until 999) {
            transcripts.appendEvent(ev(sid, "f$i", "user", "u$i"))
            transcripts.appendEvent(ev(sid, "f$i", "assistant", "a$i"))
        }
        transcripts.appendEvent(ev(sid, "tail", "user", "u-tail"))
        val total = transcripts.loadEvents(sid, 0, 100000).size
        // Clean sweep (drops nothing): clears suspect without moving the cut.
        val dropped = transcripts.simulatePruneCut(sid, total)
        assertEquals(0, dropped)
        assertFalse(transcripts.isPruneSuspect(sid))
        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(0, loaded.droppedCount)
        assertTrue("far probe must survive a clean prune sweep", loaded.reasoningIncomplete)
        assertTrue(
            loaded.messages.filter { it.role == "assistant" }.all { it.hasReasoning },
        )
    }

    /**
     * Tests P2-10: a terminal-only ledger (system marks only, no
     * user/assistant rows) opens with zero INTERRUPTED markers.
     */
    @Test fun terminalOnlyLedger_openAddsZeroMarkers() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("marks", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(ev(sid, "m1", "system", "model switched a -> b"))
        transcripts.appendEvent(ev(sid, "m2", "system", "usage input=1 output=1"))
        val provider = Stage3FakeProvider()
        val opened = factoryWith(transcripts, provider).open(endpoint(), sid)
        try {
            assertEquals(0, opened.historyDroppedCount)
            val marks = transcripts.loadEvents(sid, 0, 100)
                .filter { it.kind == "system" && it.text.endsWith("interrupted") }
            assertTrue(marks.isEmpty())
        } finally {
            opened.session.close()
        }
    }
}
