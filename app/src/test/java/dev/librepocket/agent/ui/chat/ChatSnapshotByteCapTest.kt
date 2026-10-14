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
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ByteCapAnsweringProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
    override suspend fun listModels(): List<String> = emptyList()
}

private class ByteCapVmAnsweringProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "a-next"))
        emit(StreamEvent.Done("stop"))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R6-5 snapshot aggregate byte-cap regressions (pure JVM, [FakeSessionStore]):
 * - an over-budget prefix loads bounded (newest-first, char-capped) and the
 *   snapshot abandons it WHOLE (zero prefix rows + `byte budget exceeded`
 *   incomplete marker, never a truncated half prefix), with a clean reopen
 *   (no false INTERRUPTED);
 * - a mixed huge-old + small-new ledger still drops newest-first by the row
 *   cap with no byte marker;
 * - an all-small 2000-row ledger behaves exactly as before (row cap only).
 */
class ChatSnapshotByteCapTest {

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

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("r65-bytecap-vm").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r65.preferences_pb") },
            ),
        )
    }

    private fun newVm(
        store: EndpointStore,
        transcripts: FakeSessionStore,
        fake: LlmProvider,
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

    private fun endpoint(model: String) = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = model,
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun factoryWith(transcripts: FakeSessionStore) = ChatSessionFactory(
        policy = allowKeyReadPolicy(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> ByteCapAnsweringProvider() },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = transcripts
        },
    )

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

    private fun rows(store: FakeSessionStore, sid: String) =
        store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

    @Test fun overBudgetPrefix_loadBoundedAndSnapshotAbandonsWholeWithMarker() = runBlocking {
        val store = FakeSessionStore()
        val oldId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // Issue #86 Phase B (PR slim): 11 turns x 100k = 22 rows / 2.2M chars.
        // Rows stay far below SWITCH_SNAPSHOT_COPY_CAP (1999) while bytes blow
        // the 2M cap — same behavior as the original 100-turn / 200-row / 20M
        // version (full stress lives in ChatSnapshotByteCapFullStressTest,
        // NIGHTLY_STRESS=1). One shared 100k instance keeps the heap small.
        // Budget arithmetic pin stays identical: 2M / 100k = 20 rows fit, the
        // 21st overflows. Fixture "x".repeat is already redacted (identity),
        // so the pre-redacted fast path preserves bytes exactly while skipping
        // the O(n^2) EMAIL scan (appendEvent 本體不動，F8 parity 不受影響）.
        val huge = "x".repeat(100_000)
        repeat(11) { i ->
            store.appendPreRedactedForBudgetTest(event(oldId, "r$i", "user", huge))
            store.appendPreRedactedForBudgetTest(event(oldId, "r$i", "assistant", huge))
        }

        val loaded = loadSwitchSnapshotEvents(store, oldId)
        // Bounded: newest-first prefix within both budgets, oldest dropped.
        assertTrue(loaded.events.size < 22)
        assertTrue(loaded.events.sumOf { it.text.length } <= SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS)
        assertEquals(22 - loaded.events.size, loaded.droppedByBytes)
        assertTrue(loaded.droppedByBytes > 0)
        // Pin the arithmetic: 2M / 100k = 20 rows fit, the 21st overflows.
        assertEquals(20, loaded.events.size)
        assertEquals(2, loaded.droppedByBytes)

        val newId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(store, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        // All-or-nothing: zero prefix rows, never a truncated half.
        assertEquals(0, copied)
        val forked = rows(store, newId)
        assertEquals(2, forked.size)
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertTrue(isSnapshotIncompleteMarker(forked[1].text))
        assertTrue(forked[1].text.contains("byte budget exceeded"))
        assertTrue(forked.none { it.kind == "user" || it.kind == "assistant" })
        assertTrue(hasIncompleteSnapshotMarker(store, newId))
        assertTrue(findDanglingRunIds(forked).isEmpty())

        // Reopen stays clean: no false INTERRUPTED for the abandoned prefix.
        val factory = factoryWith(store)
        val opened = factory.open(endpoint("gpt-4o"), newId)
        try {
            assertTrue(rows(store, newId).none { it.kind == "system" && it.text.endsWith("interrupted") })
            assertTrue(findDanglingRunIds(rows(store, newId)).isEmpty())
        } finally {
            opened.session.close()
        }
    }

    @Test fun mixedHugeOldRowAndSmallPrefix_rowCapDropsOldestWithoutByteMarker() = runBlocking {
        val store = FakeSessionStore()
        val oldId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // Oldest: one max-size row; then 999 small turns. 2000 qualifying rows
        // exceed the SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP row cap (SnapshotBudget.HISTORY_LOAD_CAP - 1) while total chars
        // (~120k) stay far below the byte cap: newest-first must drop exactly
        // the oldest (huge) row, with no byte marker.
        // Issue #86 Phase B: the single huge fixture uses the pre-redacted
        // fast path (bytes identical, skips O(n^2) EMAIL scan); small rows keep
        // the true appendEvent shaping.
        store.appendPreRedactedForBudgetTest(event(oldId, "hold", "user", "x".repeat(100_000)))
        store.appendEvent(event(oldId, "hold", "assistant", "a-old"))
        repeat(999) { i ->
            store.appendEvent(event(oldId, "s$i", "user", "q-$i"))
            store.appendEvent(event(oldId, "s$i", "assistant", "a-$i"))
        }

        val loaded = loadSwitchSnapshotEvents(store, oldId)
        // R26-1 family-aware head trim (Current): the row-cap cut drops the
        // oldest user(hold), leaving assistant(hold,"a-old") a user-less head
        // orphan, so the whole hold family trims: 1999 - 1 = 1998 rows, new
        // head q-0 (s0 user). Row-cap drops stay silent (legacy): no byte
        // marker, no incomplete marker.
        assertEquals(SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP - 1, loaded.events.size)
        assertEquals(0, loaded.droppedByBytes)
        assertTrue(loaded.events.sumOf { it.text.length } <= SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS)
        assertTrue(loaded.events.none { it.text.length == 100_000 })
        assertEquals("q-0", loaded.events.first().text)
        assertEquals("a-998", loaded.events.last().text)

        val newId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(store, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP - 1, copied)
        assertTrue(!hasIncompleteSnapshotMarker(store, newId))
        val forked = rows(store, newId)
        assertEquals(SnapshotBudget.HISTORY_LOAD_CAP - 1, forked.size)
        assertTrue(forked[0].text.startsWith("model switched "))
        assertTrue(forked.none { it.text.length == 100_000 })
    }

    @Test fun allSmallTwoThousandRows_rowCapOnlyCopiesNewest() = runBlocking {
        val store = FakeSessionStore()
        val oldId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // 1000 small turns x 10 chars = 2000 rows / ~20k chars: row cap only.
        repeat(1000) { i ->
            store.appendEvent(event(oldId, "t$i", "user", "0123456789"))
            store.appendEvent(event(oldId, "t$i", "assistant", "abcdefghij"))
        }

        val loaded = loadSwitchSnapshotEvents(store, oldId)
        // R26-1 family-aware head trim (Current): the row-cap cut drops the
        // oldest user(t0), leaving assistant(t0) a user-less head orphan, so
        // the whole t0 family trims: 1999 - 1 = 1998 rows. Silent like all
        // row-cap drops (no incomplete marker).
        assertEquals(SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP - 1, loaded.events.size)
        assertEquals(0, loaded.droppedByBytes)

        val newId = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(store, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP - 1, copied)
        assertTrue(!hasIncompleteSnapshotMarker(store, newId))
        val forked = rows(store, newId)
        assertEquals(SnapshotBudget.HISTORY_LOAD_CAP - 1, forked.size)
        assertTrue(forked[0].text.startsWith("model switched "))
    }

    /**
     * R6-5 blocker #4: clean-fence + non-streaming over-budget fork must warn
     * live (never silent until restart). The snapshot byte-abandons
     * successfully (same atomic batch, no throw), so `markIncomplete=false`
     * and `excludeDangling=false` — the VM's clean-path bounded
     * [hasIncompleteSnapshotMarker] scan must still surface
     * HISTORY_SNAPSHOT_INCOMPLETE on the same send.
     */
    @Test fun cleanFenceOverBudgetFork_surfacesIncompleteNoticeImmediately() {
        val store = newStore()
        runBlocking { store.save(endpoint("gpt-4o-mini")) }
        val transcripts = FakeSessionStore()
        val fake = ByteCapVmAnsweringProvider()
        val vm = newVm(store, transcripts, fake)

        onMain { vm.onInputChange("QA") }
        onMain { vm.send() }
        awaitTrue { vm.messages.value.size == 2 }
        val firstId = vm.currentSessionId.value!!
        assertNull(vm.notice.value)

        // Blow the byte budget directly in the store: 12 turns x 100k x 2 =
        // 2.4M chars over the 2M cap, rows (26) far below the SnapshotBudget.SWITCH_SNAPSHOT_COPY_CAP row cap (1999).
        // The live turn stays IDLE (clean fence, non-streaming). One shared
        // instance keeps the heap small; the budget measures lengths.
        // Issue #86 Phase B: pre-redacted fast path (bytes identical).
        val huge = "x".repeat(100_000)
        runBlocking {
            repeat(12) { i ->
                transcripts.appendPreRedactedForBudgetTest(event(firstId, "huge-${i}u", "user", huge))
                transcripts.appendPreRedactedForBudgetTest(event(firstId, "huge-${i}a", "assistant", huge))
            }
        }

        // Same provider + origin, different model: the next send forks with a
        // clean fence (flushDurable holds on the fake) and no streaming
        // evidence. Wait for the endpoint observer to settle first, so the
        // send below cannot race it into a stale-generation no-op.
        runBlocking { store.save(endpoint("gpt-4o")) }
        awaitTrue { vm.sessionState.value.messages.isEmpty() }
        onMain { vm.onInputChange("QB") }
        onMain { vm.send() }

        // Fail-open + fail-loud: the send still completes on the in-memory
        // prefix, and the byte-abandon surfaces live — no restart needed.
        // Replay now holds the 26 stored rows (QA turn + 24 huge) plus the
        // 2 live rows of the QB turn.
        awaitTrue(timeoutMs = 12000) {
            vm.notice.value == "HISTORY_SNAPSHOT_INCOMPLETE" &&
                vm.messages.value.size == 28 &&
                vm.messages.value.any { it.role == "user" && it.text.startsWith("QB") } &&
                vm.sessionState.value.status == ChatStatus.IDLE
        }
        val forkId = vm.currentSessionId.value!!
        assertTrue(forkId != firstId)

        // Ledger: zero copied prefix rows (all-or-nothing) + the byte marker
        // in the same atomic batch + the new turn's rows.
        val forked = rows(transcripts, forkId)
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertTrue(isSnapshotIncompleteMarker(forked[1].text))
        assertTrue(forked[1].text.contains("byte budget exceeded"))
        assertTrue(forked.none { it.text.length == 100_000 })
        runBlocking { assertTrue(hasIncompleteSnapshotMarker(transcripts, forkId)) }
        assertTrue(forked.any { it.kind == "user" && it.text.startsWith("QB") })
        assertTrue(findDanglingRunIds(forked).isEmpty())
    }
}
