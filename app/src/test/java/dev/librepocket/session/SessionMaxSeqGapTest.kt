package dev.librepocket.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.ChatTestMainDispatcher
import dev.librepocket.agent.ui.chat.ChatTestViewModelStore
import dev.librepocket.agent.ui.chat.ChatViewModel
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D15-5 gap coverage for the MAX-seq work: prune keeps the MAX tail,
 * partial-aware counts diverge with a MAX partial present, export/reimport
 * preserves the attempt-dimension fields, and the ChatViewModel replay sees
 * MAX rows (partial + reasoning marker) through the sentinel first page.
 *
 * Room halves run under Robolectric with an in-memory database; the replay
 * halves reuse the fake-store + openSession pattern from
 * ChatViewModelHistoryTailTest (no Room needed: the sentinel contract lives
 * in the shared backward readers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionMaxSeqGapRoomTest {

    private var now: Long = 1_700_000_000_000L
    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore
    private lateinit var tmpDir: File

    private fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now }
        tmpDir = Files.createTempDirectory("session-maxseq-gap-test").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
        if (this::tmpDir.isInitialized) {
            tmpDir.deleteRecursively()
        }
    }

    private fun importRows(rows: List<TranscriptEvent>): String {
        val src = File(tmpDir, "gap-${rows.hashCode()}.jsonl")
        src.writeText(
            rows.joinToString("\n") { JsonlCodec.encode(it) } + "\n",
            Charsets.UTF_8,
        )
        return runBlocking { store.importJsonl(src) }
    }

    private fun importSeqs(seqs: List<Long>): String {
        return importRows(
            seqs.map { seq ->
                TranscriptEvent(
                    seq = seq,
                    sessionId = "ignored-on-import",
                    runId = "imported-$seq",
                    kind = "user",
                    text = "event-$seq",
                    createdAt = now,
                )
            },
        )
    }

    @Test fun pruneKeepsMaxTail(): Unit = runBlocking {
        setUp()
        val sid = importSeqs(listOf(1L, 2L, 3L, Long.MAX_VALUE - 1, Long.MAX_VALUE))

        val result = store.prune(PrunePolicy(maxEventsPerSession = 2))

        assertEquals(3, result.deletedEvents)
        assertEquals(0, result.deletedSessions)
        // The MAX pair is the newest tail, so the keep-newest sweep must retain it.
        assertEquals(
            listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadEvents(sid).map { it.seq },
        )
        assertEquals(2, store.countEvents(sid))
        assertEquals(
            listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1),
            store.loadEventsBefore(sid, Long.MAX_VALUE, 10).map { it.seq },
        )
        assertEquals(
            listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE),
            store.loadTailEvents(sid, 10).map { it.seq },
        )
    }

    @Test fun partialMaxCountDivergence(): Unit = runBlocking {
        setUp()
        val sid = importRows(
            listOf(
                TranscriptEvent(
                    seq = 1L,
                    sessionId = "ignored-on-import",
                    runId = "r1",
                    kind = "user",
                    text = "q1",
                    createdAt = now,
                ),
                TranscriptEvent(
                    seq = 2L,
                    sessionId = "ignored-on-import",
                    runId = "r1",
                    kind = "assistant",
                    text = "half",
                    createdAt = now,
                    isPartial = true,
                    failureReason = "boom",
                ),
                TranscriptEvent(
                    seq = Long.MAX_VALUE - 1,
                    sessionId = "ignored-on-import",
                    runId = "r-max",
                    kind = "user",
                    text = "q-old",
                    createdAt = now,
                ),
                TranscriptEvent(
                    seq = Long.MAX_VALUE,
                    sessionId = "ignored-on-import",
                    runId = "r-max",
                    kind = "assistant",
                    text = "a-max-half",
                    createdAt = now,
                    isPartial = true,
                ),
            ),
        )

        assertEquals(4, store.countEvents(sid))
        // Replay rows include partials; model-context rows exclude them —
        // the MAX partial must count in the former but not the latter.
        assertEquals(4, store.countHistoryEvents(sid, includePartial = true))
        assertEquals(2, store.countHistoryEvents(sid, includePartial = false))
        // The sentinel first page still sees every row, MAX first.
        assertEquals(
            listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1, 2L, 1L),
            store.loadEventsBefore(sid, Long.MAX_VALUE, 10).map { it.seq },
        )
    }

    @Test fun exportReimportRoundTripsAttemptFields(): Unit = runBlocking {
        setUp()
        val sid = store.createSession("t", "p/m")
        store.appendEvent(
            TranscriptEvent(sessionId = sid, runId = "L", kind = "user", text = "q", createdAt = now),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = "L",
                kind = "assistant",
                text = "half",
                createdAt = now,
                isPartial = true,
                failureReason = "boom",
                attemptIndex = 0,
                isFinal = false,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = "L",
                kind = "retry",
                text = "attempt 1/1 after 0ms",
                createdAt = now,
            ),
        )
        store.appendEvent(
            TranscriptEvent(
                sessionId = sid,
                runId = "A2",
                kind = "assistant",
                text = "ok",
                createdAt = now,
                parentRunId = "L",
                attemptIndex = 1,
                isFinal = true,
            ),
        )

        val dest = File(tmpDir, "fields.jsonl")
        store.exportJsonl(sid, dest)
        val sid2 = store.importJsonl(dest)

        val before = store.loadEvents(sid)
        val after = store.loadEvents(sid2)
        assertEquals(before.size, after.size)
        for (i in before.indices) {
            assertEquals(before[i].seq, after[i].seq)
            assertEquals(before[i].runId, after[i].runId)
            assertEquals(before[i].kind, after[i].kind)
            assertEquals(before[i].text, after[i].text)
            assertEquals(before[i].isPartial, after[i].isPartial)
            assertEquals(before[i].isFinal, after[i].isFinal)
            assertEquals(before[i].parentRunId, after[i].parentRunId)
            assertEquals(before[i].attemptIndex, after[i].attemptIndex)
            assertEquals(before[i].failureReason, after[i].failureReason)
        }
        // Spot-check the interesting rows, not just pairwise equality.
        val partial = after.first { it.kind == "assistant" && it.isPartial }
        assertEquals("half", partial.text)
        assertEquals("boom", partial.failureReason)
        assertFalse(partial.isFinal)
        assertEquals(0, partial.attemptIndex)
        val terminal = after.last { it.kind == "assistant" && !it.isPartial }
        assertEquals("ok", terminal.text)
        assertEquals("L", terminal.parentRunId)
        assertEquals(1, terminal.attemptIndex)
        assertTrue(terminal.isFinal)
    }
}

private class GapFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seenRequests: MutableList<ChatRequest> = Collections.synchronizedList(mutableListOf())

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        seenRequests.add(request)
        emitAll(handler(request))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * D15-5 ChatViewModel replay over MAX seqs (fake store + openSession, same
 * shape as ChatViewModelHistoryTailTest): the sentinel first page must
 * terminate with the MAX row visible, a MAX partial replays flagged, and a
 * MAX reasoning marker restores hasReasoning without rendering.
 */
class SessionMaxSeqGapViewModelTest {

    private fun openaiEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private val tmpDirs = ArrayList<java.io.File>()
    private val scopes = ArrayList<CoroutineScope>()
    private val chatViewModels = ChatTestViewModelStore()
    private val chatMain = ChatTestMainDispatcher()

    @Before
    fun setUpMain() {
        chatMain.install()
    }

    @After
    fun tearDownMain() {
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

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("chat-maxgap-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { java.io.File(dir, "chat.preferences_pb") },
            ),
        )
    }

    private fun awaitTrue(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun viewModelFor(transcripts: FakeSessionStore): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "sk-test-key-123".toCharArray()) }
        val fake = GapFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        return chatViewModels.own(ChatViewModel(newStore().also { runBlocking { it.save(openaiEndpoint()) } }, factory, InMemoryPolicyStore()))
    }

    @Test
    fun openSession_maxPartialReplayTerminates() {
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
            transcripts.appendEvent(
                TranscriptEvent(sessionId = id, runId = "r0", kind = "user", text = "u0", createdAt = 1L),
            )
            transcripts.appendEvent(
                TranscriptEvent(sessionId = id, runId = "r0", kind = "assistant", text = "a0", createdAt = 1L),
            )
            // Legally imported MAX tail: the sentinel first page must expose
            // it and the backward walk must terminate (no infinite paging).
            transcripts.events.add(
                TranscriptEvent(
                    seq = Long.MAX_VALUE - 1,
                    sessionId = id,
                    runId = "r-max",
                    kind = "user",
                    text = "u-old",
                    createdAt = 1L,
                ),
            )
            transcripts.events.add(
                TranscriptEvent(
                    seq = Long.MAX_VALUE,
                    sessionId = id,
                    runId = "r-max",
                    kind = "assistant",
                    text = "a-max",
                    createdAt = 1L,
                    isPartial = true,
                ),
            )
            id
        }
        val vm = viewModelFor(transcripts)
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 4 }
        assertEquals(listOf("u0", "a0", "u-old", "a-max"), vm.messages.value.map { it.text })
        assertFalse(vm.messages.value[2].isPartial)
        assertTrue(vm.messages.value[3].isPartial)
        assertEquals(0, vm.historyOmittedCount.value)
        assertNull(vm.notice.value)
    }

    @Test
    fun openSession_maxReasoningMarkerRestoresFlag() {
        val transcripts = FakeSessionStore()
        val sid = runBlocking {
            val id = transcripts.createSession("old chat", "preset:openai/gpt-4o-mini")
            transcripts.appendEvent(
                TranscriptEvent(sessionId = id, runId = "r1", kind = "user", text = "q1", createdAt = 1L),
            )
            transcripts.appendEvent(
                TranscriptEvent(sessionId = id, runId = "r1", kind = "assistant", text = "a1", createdAt = 1L),
            )
            // Reasoning marker at MAX: seen before its assistant in backward
            // order, buffered via pendingMarkers, never rendered.
            transcripts.events.add(
                TranscriptEvent(
                    seq = Long.MAX_VALUE,
                    sessionId = id,
                    runId = "r1",
                    kind = "reasoning",
                    text = "",
                    createdAt = 1L,
                ),
            )
            id
        }
        val vm = viewModelFor(transcripts)
        onMain { vm.openSession(sid) }
        awaitTrue { vm.messages.value.size == 2 }
        val replayed = vm.messages.value
        assertEquals(listOf("q1", "a1"), replayed.map { it.text })
        assertFalse(replayed[0].hasReasoning)
        assertTrue(replayed[1].hasReasoning)
        assertEquals(0, vm.historyOmittedCount.value)
        assertNull(vm.notice.value)
    }
}
