package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class AtomicityFakeProvider(
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

/** Throws [IOException] on exactly the Nth [appendEvent] call (mid-batch fault injection). */
private class FailNthAppendStore : FakeSessionStore() {
    var appendCalls = 0
    var failAt = -1

    override suspend fun appendEvent(event: TranscriptEvent): Long {
        appendCalls++
        if (appendCalls == failAt) throw IOException("injected snapshot fault")
        return super.appendEvent(event)
    }
}

/** Counts [appendEvents] entries: proves the snapshot goes through ONE atomic batch. */
private class BatchCountingStore : FakeSessionStore() {
    var batchCalls = 0
    val batchSizes = ArrayList<Int>()

    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        batchCalls++
        batchSizes.add(events.size)
        return super.appendEvents(events)
    }
}

/**
 * Fails only snapshot batches (marker-headed) BEFORE writing anything —
 * the faithful emulation of a Room transaction abort (zero rows, throw).
 * Live-turn single writes and the incomplete marker (single appendEvent)
 * are unaffected.
 */
private class SnapshotFailingStore : FakeSessionStore() {
    override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
        if (events.isNotEmpty() && events[0].kind == "system" && events[0].text.startsWith("model switched ")) {
            throw IOException("injected snapshot txn abort")
        }
        return super.appendEvents(events)
    }
}

/** Fails every snapshot-prefix read: the write phase must never start. */
private class ReadFailingStore : FakeSessionStore() {
    override suspend fun loadEventsBefore(sessionId: String, beforeSeq: Long, limit: Int): List<TranscriptEvent> =
        throw IOException("read down")
}

/**
 * R5-4 snapshot-atomicity regressions (pure JVM):
 * - the durable snapshot persists marker + prefix as ONE batch
 *   ([dev.librepocket.session.SessionStore.appendEvents]);
 * - a mid-copy failure leaves ZERO snapshot rows (never a silent
 *   marker + N-1 half prefix), so reopen never stamps a false INTERRUPTED;
 * - an [ensureSession][ChatViewModel] snapshot failure best-effort writes
 *   the system incomplete marker + HISTORY_SNAPSHOT_INCOMPLETE notice while
 *   the send still succeeds (fail-open), and a restart re-surfaces the
 *   notice via the marker scan.
 */
class ChatSnapshotAtomicityTest {

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

    private fun endpoint(model: String) = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("r54-atomic-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r54.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(
        transcripts: FakeSessionStore,
        fake: AtomicityFakeProvider,
    ) = ChatSessionFactory(
        policy = allowKeyReadPolicy(),
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

    private fun newVm(
        store: EndpointStore,
        transcripts: FakeSessionStore,
        fake: AtomicityFakeProvider,
    ): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "test-key-12345678".toCharArray()) }
        val factory = ChatSessionFactory(
            policy = allowKeyReadPolicy(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        return chatViewModels.own(ChatViewModel(store, factory, allowKeyReadPolicy()))
    }

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun event(
        sid: String,
        runId: String,
        kind: String,
        text: String,
        createdAt: Long = 1L,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = createdAt,
    )

    private fun rows(store: FakeSessionStore, sid: String) =
        store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

    private fun interruptedMarkers(store: FakeSessionStore, sid: String) =
        rows(store, sid).filter { it.kind == "system" && it.text.endsWith("interrupted") }

    private fun answeringFake() = AtomicityFakeProvider {
        flow {
            emit(StreamEvent.TextDelta(0, 0, "a-next"))
            emit(StreamEvent.Done("stop"))
        }
    }

    private fun seedTwoTurns(store: FakeSessionStore, sid: String) = runBlocking {
        store.appendEvent(event(sid, "r0", "user", "q0"))
        store.appendEvent(event(sid, "r0", "assistant", "a0"))
        store.appendEvent(event(sid, "L", "user", "q1"))
        store.appendEvent(event(sid, "L", "assistant", "a1"))
    }

    // -- batch shape -------------------------------------------------------

    @Test fun snapshotUsesSingleAtomicBatch() = runBlocking {
        val transcripts = BatchCountingStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedTwoTurns(transcripts, oldId)
        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")

        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)

        assertEquals(4, copied)
        // Room-decorator layer: exactly one appendEvents entry carrying
        // marker + all copies (one transaction on Room).
        assertEquals(1, transcripts.batchCalls)
        assertEquals(listOf(5), transcripts.batchSizes)
        val forked = rows(transcripts, newId)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), forked.map { it.kind })
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
    }

    // -- Fake-layer atomicity -----------------------------------------------

    private fun assertMidCopyFailureLeavesZeroRows(failAt: Int) = runBlocking {
        val transcripts = FailNthAppendStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedTwoTurns(transcripts, oldId)
        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        transcripts.appendCalls = 0
        transcripts.failAt = failAt

        try {
            snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
            fail("snapshot must throw on a mid-copy store failure")
        } catch (_: IOException) {
            // Expected: the batch aborts.
        }

        // No silent partial: marker + N-1 rows must be impossible.
        assertTrue(rows(transcripts, newId).isEmpty())
        assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())

        // Reopen stays clean: no false INTERRUPTED for the missing prefix.
        val factory = factoryWith(transcripts, answeringFake())
        val opened = factory.open(endpoint("gpt-4o-mini"), newId)
        try {
            assertTrue(interruptedMarkers(transcripts, newId).isEmpty())
            assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())
        } finally {
            opened.session.close()
        }
    }

    @Test fun midCopyFailureOnFirstCopy_leavesZeroRows() = assertMidCopyFailureLeavesZeroRows(failAt = 2)

    @Test fun midCopyFailureOnMarker_leavesZeroRows() = assertMidCopyFailureLeavesZeroRows(failAt = 1)

    @Test fun midCopyFailureOnLastCopy_leavesZeroRows() = assertMidCopyFailureLeavesZeroRows(failAt = 5)

    @Test fun readFailure_writesNothing() = runBlocking {
        val transcripts = ReadFailingStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        seedTwoTurns(transcripts, oldId)
        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")

        try {
            snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
            fail("snapshot must throw when the prefix read fails")
        } catch (_: IOException) {
            // Expected: the write phase never starts.
        }
        assertTrue(rows(transcripts, newId).isEmpty())
    }

    // -- ensureSession failure: incomplete marker + notice, fail-open --------

    @Test fun ensureSessionSnapshotFailure_marksIncompleteNoticesAndStaysFailOpen() {
        val store = newStore()
        runBlocking { store.save(endpoint("gpt-4o-mini")) }
        val transcripts = SnapshotFailingStore()
        val fake = answeringFake()
        val vm = newVm(store, transcripts, fake)

        onMain { vm.onInputChange("QA") }
        onMain { vm.send() }
        awaitTrue { vm.messages.value.size == 2 }
        val firstId = vm.currentSessionId.value!!
        assertNull(vm.notice.value)

        // Same provider + origin, different model: the next send forks with
        // a same-origin snapshot, whose batch aborts (injected). Wait for the
        // endpoint observer to settle the invalidation first, so the send
        // below cannot race it into a stale-generation no-op.
        runBlocking { store.save(endpoint("gpt-4o")) }
        awaitTrue { vm.sessionState.value.messages.isEmpty() }
        onMain { vm.onInputChange("QB") }
        onMain { vm.send() }

        // Fail-open: the send still completes on the in-memory prefix, and
        // the failure surfaces as the incomplete notice (not silent).
        awaitTrue(timeoutMs = 12000) {
            vm.notice.value == "HISTORY_SNAPSHOT_INCOMPLETE" &&
                vm.messages.value.size == 4 &&
                vm.sessionState.value.status == ChatStatus.IDLE
        }
        assertEquals(listOf("QA", "a-next", "QB", "a-next"), vm.messages.value.map { it.text })
        val forkId = vm.currentSessionId.value!!
        assertTrue(forkId != firstId)
        // Live prefix survived the durable failure: the provider still saw QA.
        assertTrue(fake.seenRequests.last().messages.map { it.text }.contains("QA"))

        // Ledger: zero copied rows (atomic) + exactly one incomplete marker
        // + the new turn's rows (no silent half prefix).
        val forked = rows(transcripts, forkId)
        assertTrue(forked.none { it.text == "QA" || it.text == "q0" })
        runBlocking { assertTrue(hasIncompleteSnapshotMarker(transcripts, forkId)) }
        assertEquals(1, forked.count { it.kind == "system" && isSnapshotIncompleteMarker(it.text) })
        assertTrue(forked.any { it.kind == "user" && it.text.startsWith("QB") })
        assertTrue(interruptedMarkers(transcripts, forkId).isEmpty())

        // Restart: reopening the fork re-warns via the marker scan, with no
        // false INTERRUPTED backfill.
        val vm2 = newVm(store, transcripts, answeringFake())
        onMain { vm2.openSession(forkId) }
        awaitTrue { vm2.currentSessionId.value == forkId && vm2.messages.value.isNotEmpty() }
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm2.notice.value)
        assertEquals(listOf("QB", "a-next"), vm2.messages.value.map { it.text })
        assertTrue(interruptedMarkers(transcripts, forkId).isEmpty())
        assertTrue(findDanglingRunIds(rows(transcripts, forkId)).isEmpty())
        // D5: the restart re-warns without re-copying — the incomplete marker
        // count stays 1 and the fork row total is unchanged (no row growth).
        assertEquals(1, rows(transcripts, forkId).count { it.kind == "system" && isSnapshotIncompleteMarker(it.text) })
        assertEquals(forked.size, rows(transcripts, forkId).size)
    }

    // -- openSession decision chain ------------------------------------------

    @Test fun openSession_resurfacesIncompleteNoticeFromMarker() {
        val store = newStore()
        runBlocking { store.save(endpoint("gpt-4o")) }
        val transcripts = FakeSessionStore()
        runBlocking {
            val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
            transcripts.appendEvent(
                TranscriptEvent(
                    sessionId = newId,
                    runId = "snapshot-incomplete-x",
                    kind = "system",
                    text = snapshotIncompleteMarkerText("gpt-4o-mini", "gpt-4o"),
                    createdAt = 1L,
                ),
            )
            transcripts.appendEvent(event(newId, "rQ", "user", "QB", createdAt = 2L))
            transcripts.appendEvent(event(newId, "rQ", "assistant", "AB", createdAt = 3L))

            val vm = newVm(store, transcripts, answeringFake())
            onMain { vm.openSession(newId) }
            awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.isNotEmpty() }
            assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
            assertEquals(listOf("QB", "AB"), vm.messages.value.map { it.text })
            assertTrue(interruptedMarkers(transcripts, newId).isEmpty())
        }
    }

    @Test fun incompleteNoticeText_isPinnedTraditionalChinese() {
        assertEquals(
            "切換模型時歷史快照寫入失敗，舊對話未能完整帶入；本次仍可繼續對話，重啟後僅保留新對話紀錄。",
            chatNoticeText("HISTORY_SNAPSHOT_INCOMPLETE"),
        )
    }
}
