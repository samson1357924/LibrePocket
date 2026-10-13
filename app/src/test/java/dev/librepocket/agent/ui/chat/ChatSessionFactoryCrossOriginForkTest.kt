package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatStatus
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.TranscriptEvent
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class ForkFakeProvider(
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
 * Stage 5 (R4-1) cross-provider open() fork regressions (all fake data).
 *
 * The old transcript session is a read-only archive: a cross-provider/origin
 * open() never reuses its id. It forks a fresh provider-owned session (title
 * inherited, provenance re-stamped), persists only the withheld marker (never
 * a copy), and binds backfill + sink to the new id. One ledger never mixes
 * two recipients' rows.
 */
class ChatSessionFactoryCrossOriginForkTest {

    private fun endpointA() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun endpointB() = EndpointConfig(
        providerId = "preset:anthropic",
        presetId = "anthropic",
        label = "Anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "claude-3-5-sonnet",
        apiKeyRef = "provider_key/preset:anthropic",
    )

    private fun factoryWith(
        transcripts: FakeSessionStore,
        provider: ForkFakeProvider,
    ) = ChatSessionFactory(
        policy = InMemoryPolicyStore(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault.putKey("preset:anthropic", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> provider },
        sessionStores = object : SessionStoreSource {
            override suspend fun store() = transcripts
        },
    )

    private fun answerProvider() = ForkFakeProvider { req ->
        flow {
            val userMsg = req.messages.last { it.role == "user" }.text.substringBefore("\n\n")
            val reply = when {
                userMsg.startsWith("QB") -> "AB"
                userMsg.startsWith("QA") -> "AA"
                userMsg.startsWith("QNEW") -> "ANEW"
                userMsg.startsWith("Q3") -> "A3"
                else -> "reply"
            }
            emit(StreamEvent.TextDelta(0, 0, reply))
            emit(StreamEvent.Done("stop"))
        }
    }

    private fun event(sid: String, runId: String, kind: String, text: String) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = 1L,
    )

    /**
     * Phase 3 P1-1: fault-injecting fake proving cancellation propagates
     * instead of degrading (swallowed backfill → silent success, swallowed
     * meta read → UNKNOWN_SESSION). Each flag throws CancellationException
     * from exactly one store call; the test toggles it after seeding so the
     * seed writes stay clean. Deterministic: direct factory open() on
     * runBlocking, no timing involved.
     */
    private class CancelBackfillStore : FakeSessionStore() {
        var failTailCancel = false
        var failAppendCancel = false
        var failGetCancelFor: (String) -> Boolean = { false }

        override suspend fun loadTailEvents(sessionId: String, limit: Int): List<TranscriptEvent> {
            if (failTailCancel) throw CancellationException("lifecycle cancel")
            return super.loadTailEvents(sessionId, limit)
        }

        override suspend fun appendEvent(event: TranscriptEvent): Long {
            if (failAppendCancel) throw CancellationException("lifecycle cancel")
            return super.appendEvent(event)
        }

        override suspend fun getSession(sessionId: String): dev.librepocket.session.SessionMeta? {
            if (failGetCancelFor(sessionId)) throw CancellationException("ENDPOINT_BINDING_STALE")
            return super.getSession(sessionId)
        }
    }

    private fun seedQA(transcripts: FakeSessionStore, title: String = "my title here"): String = runBlocking {
        val sid = transcripts.createSession(title, "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(sid, "r1", "user", "QA"))
        transcripts.appendEvent(event(sid, "r1", "assistant", "AA"))
        sid
    }

    @Test fun open_crossOrigin_forksNewSessionWithWithheldMarker() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        val oldMeta = transcripts.metas[oldId]!!
        val oldEventCount = transcripts.events.size

        val opened = factoryWith(transcripts, answerProvider()).open(endpointB(), oldId)
        try {
            assertTrue(opened.historyWithheld)
            val newId = checkNotNull(opened.sessionId)
            assertNotEquals("cross-origin open must fork a new id", oldId, newId)
            assertEquals(0, opened.historyDroppedCount)

            // New header: title inherited, provenance re-stamped to B.
            val newMeta = checkNotNull(transcripts.metas[newId])
            assertEquals(oldMeta.title, newMeta.title)
            assertEquals("preset:anthropic@https://api.anthropic.com/claude-3-5-sonnet", newMeta.model)
            // Session list grows by one (title inheritance is visible there).
            assertEquals(2, transcripts.listSessions().size)

            // Old ledger untouched (read-only archive: no backfill, no copy-out).
            assertEquals(oldEventCount, transcripts.events.count { it.sessionId == oldId })
            assertTrue(transcripts.events.filter { it.sessionId == oldId }.all { it.text == "QA" || it.text == "AA" })

            // New ledger holds only the withheld marker (never a copy of QA/AA).
            val forkedRows = transcripts.events.filter { it.sessionId == newId }
            assertEquals(listOf("system"), forkedRows.map { it.kind })
            assertTrue(forkedRows.single().text.startsWith("model switched "))
            assertTrue(modelSwitchMarkerWithheld(forkedRows.single().text))
            assertTrue(forkedRows.none { it.text == "QA" || it.text == "AA" })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_crossOrigin_writeAfterWithhold_landsInForkOnly() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        val provider = answerProvider()
        val opened = factoryWith(transcripts, provider).open(endpointB(), oldId)
        try {
            assertTrue(opened.historyWithheld)
            val newId = checkNotNull(opened.sessionId)
            assertNotEquals(oldId, newId)

            opened.session.send("QB")
            val req = provider.seenRequests.single()
            // Fail closed: empty model context, only the new turn reaches B.
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.single().text.startsWith("QB"))
            assertTrue(req.messages.none { it.text.contains("QA") || it.text.contains("AA") })

            // Ledger isolation: old keeps QA/AA only, fork keeps marker + QB/AB only.
            val oldRows = transcripts.events.filter { it.sessionId == oldId }
            assertEquals(listOf("user", "assistant"), oldRows.map { it.kind })
            assertTrue(oldRows.none { it.text.startsWith("QB") || it.text == "AB" })
            val newRows = transcripts.events.filter { it.sessionId == newId }
            assertEquals(listOf("system", "user", "assistant"), newRows.map { it.kind })
            assertTrue(newRows[1].text.startsWith("QB"))
            assertEquals("AB", newRows[2].text)
            assertTrue(newRows.none { it.text == "QA" || it.text == "AA" })
            // Every row is source-consistent with its own ledger id.
            assertTrue(transcripts.events.all { it.sessionId == oldId || it.sessionId == newId })
        } finally {
            opened.session.close()
        }
    }

    @Test fun open_crossOrigin_ABA_noLeak() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        val providerB = answerProvider()
        val forked = factoryWith(transcripts, providerB).open(endpointB(), oldId)
        val forkId = checkNotNull(forked.sessionId)
        forked.session.send("QB")
        forked.session.close()

        // A reopens its own archive: must not see B's rows.
        val providerA = answerProvider()
        val reopened = factoryWith(transcripts, providerA).open(endpointA(), oldId)
        try {
            assertEquals(oldId, reopened.sessionId)
            assertFalse(reopened.historyWithheld)
            reopened.session.send("QNEW")
            val req = providerA.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("QA", req.messages[0].text)
            assertEquals("AA", req.messages[1].text)
            assertTrue(req.messages[2].text.startsWith("QNEW"))
            assertTrue(req.messages.none { it.text.startsWith("QB") || it.text == "AB" })
            // Old ledger never absorbed the fork's rows.
            assertTrue(transcripts.events.filter { it.sessionId == oldId }.none { it.text.startsWith("QB") || it.text == "AB" })
            // Fork ledger still owns B's rows exclusively.
            assertTrue(transcripts.events.filter { it.sessionId == forkId }.any { it.text.startsWith("QB") })
        } finally {
            reopened.session.close()
        }
    }

    @Test fun open_crossOrigin_ABB_selfConsistent() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        val providerB1 = answerProvider()
        val forked = factoryWith(transcripts, providerB1).open(endpointB(), oldId)
        val forkId = checkNotNull(forked.sessionId)
        forked.session.send("QB")
        forked.session.close()

        // B reopens its own fork: hydrates QB/AB, never QA/AA.
        val providerB2 = answerProvider()
        val reopened = factoryWith(transcripts, providerB2).open(endpointB(), forkId)
        try {
            assertEquals(forkId, reopened.sessionId)
            assertFalse("same-origin fork reopen must hydrate", reopened.historyWithheld)
            reopened.session.send("QNEW")
            val req = providerB2.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertTrue(req.messages[0].text.startsWith("QB"))
            assertEquals("AB", req.messages[1].text)
            assertTrue(req.messages[2].text.startsWith("QNEW"))
            assertTrue(req.messages.none { it.text == "QA" || it.text == "AA" })
        } finally {
            reopened.session.close()
        }
    }

    @Test fun open_sameOrigin_reusesSessionId() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        val opened = factoryWith(transcripts, answerProvider()).open(endpointA(), oldId)
        try {
            assertEquals(oldId, opened.sessionId)
            assertFalse(opened.historyWithheld)
            // No fork marker, no extra session.
            assertEquals(1, transcripts.metas.size)
            assertTrue(transcripts.events.none { isModelSwitchMarker(it.text) })
        } finally {
            opened.session.close()
        }
    }

    @Test fun staleForkDecision_forkDeletesSameOriginRetains() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        val factory = factoryWith(transcripts, answerProvider())

        // Cross-origin fork mints a new id: a stale discard must delete it.
        val forked = factory.open(endpointB(), oldId)
        val forkId = checkNotNull(forked.sessionId)
        assertNotEquals(oldId, forkId)
        assertTrue(shouldDeleteStaleFork(oldId, forked))
        // Storeless (null id) never deletes.
        assertFalse(shouldDeleteStaleFork(oldId, forked.copy(sessionId = null)))

        // Same-origin reopen reuses the id: a stale discard must retain it.
        val same = factory.open(endpointA(), oldId)
        try {
            assertEquals(oldId, same.sessionId)
            assertFalse(shouldDeleteStaleFork(oldId, same))
        } finally {
            same.session.close()
        }

        // Exercising the decision through discardUnattached: the fork header
        // goes away (Room cascades its marker rows; the fake only drops the
        // header), the archived header survives.
        forked.session.close()
        factory.discardUnattached(forked, deleteTranscriptRow = shouldDeleteStaleFork(oldId, forked))
        assertNull(transcripts.getSession(forkId))
        assertTrue(checkNotNull(transcripts.getSession(oldId)).sessionId == oldId)

        // Same-id stale discard retains the archive.
        val reopened = factory.open(endpointA(), oldId)
        try {
            factory.discardUnattached(reopened, deleteTranscriptRow = shouldDeleteStaleFork(oldId, reopened))
            assertTrue(checkNotNull(transcripts.getSession(oldId)).sessionId == oldId)
        } finally {
            try {
                reopened.session.close()
            } catch (_: Exception) {
                // Already closed by the discard above; idempotent teardown.
            }
        }
    }

    @Test fun open_crossOrigin_strandedOldStaysArchive_onlySameOriginReopenBackfills() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "done", "user", "QA"))
        transcripts.appendEvent(event(oldId, "done", "assistant", "AA"))
        // Kill-stranded turn: user row acked, no terminal row follows.
        transcripts.appendEvent(event(oldId, "stranded", "user", "QSTRANDED"))
        val oldCount = transcripts.events.count { it.sessionId == oldId }

        val forked = factoryWith(transcripts, answerProvider()).open(endpointB(), oldId)
        try {
            val forkId = checkNotNull(forked.sessionId)
            assertNotEquals(oldId, forkId)
            // Old ledger is a read-only archive: no INTERRUPTED marker added.
            val oldRows = transcripts.events.filter { it.sessionId == oldId }
            assertEquals(oldCount, oldRows.size)
            assertTrue(oldRows.none { it.kind == "system" && it.text.endsWith("interrupted") })
            // New ledger holds only the withheld switch marker (backfill finds
            // no user family there, so no INTERRUPTED marker either).
            val newRows = transcripts.events.filter { it.sessionId == forkId }
            assertEquals(1, newRows.size)
            assertEquals("system", newRows.single().kind)
            assertTrue(isModelSwitchMarker(newRows.single().text))
            assertTrue(newRows.none { it.text.endsWith("interrupted") })
        } finally {
            forked.session.close()
        }

        // Same-origin reopen of the old archive backfills exactly one marker.
        val reopened = factoryWith(transcripts, answerProvider()).open(endpointA(), oldId)
        try {
            assertEquals(oldId, reopened.sessionId)
            val markers = transcripts.events.filter {
                it.sessionId == oldId && it.kind == "system" && it.text == "turn stranded interrupted"
            }
            assertEquals(1, markers.size)
        } finally {
            reopened.session.close()
        }
    }

    @Test fun open_legacyBareHeader_forksInsteadOfReusing() = runBlocking {
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("old chat", "m")
        transcripts.appendEvent(event(oldId, "r1", "user", "QA"))
        transcripts.appendEvent(event(oldId, "r1", "assistant", "AA"))
        val provider = answerProvider()
        val opened = factoryWith(transcripts, provider).open(endpointA(), oldId)
        try {
            val newId = checkNotNull(opened.sessionId)
            assertNotEquals("bare-header resume must fork, not reuse the id", oldId, newId)
            assertTrue(opened.historyWithheld)
            // Old ledger untouched: no in-place marker, no copy-out.
            val oldRows = transcripts.events.filter { it.sessionId == oldId }
            assertEquals(listOf("user", "assistant"), oldRows.map { it.kind })
            // New ledger holds only the withheld marker before any send.
            val newRows = transcripts.events.filter { it.sessionId == newId }
            assertEquals(listOf("system"), newRows.map { it.kind })
            assertTrue(modelSwitchMarkerWithheld(newRows.single().text))
            // Fail closed: the first request after the fork carries no old prefix.
            opened.session.send("QNEW")
            val req = provider.seenRequests.single()
            assertEquals(listOf("user"), req.messages.map { it.role })
            assertTrue(req.messages.none { it.text == "QA" || it.text == "AA" })
        } finally {
            opened.session.close()
        }
    }

    // --- ViewModel wiring: fork id surfaces via currentSessionId + notice. ---

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

    private fun onMain(block: () -> Unit) {
        runBlocking { chatMain.run(block) }
    }

    private fun newStore(): EndpointStore {
        val dir = Files.createTempDirectory("chat-fork-test").toFile()
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

    private fun awaitTrue(timeoutMs: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for condition")
            Thread.sleep(10)
        }
    }

    private fun newVm(store: EndpointStore, transcripts: FakeSessionStore, fake: ForkFakeProvider): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking {
            vault.putKey("preset:openai", "sk-test-key-123".toCharArray())
            vault.putKey("preset:anthropic", "sk-ant-test-key".toCharArray())
        }
        val factory = ChatSessionFactory(
            policy = InMemoryPolicyStore(),
            vaultSource = VaultSource { vault },
            buildProvider = { _, _ -> fake },
            sessionStores = object : SessionStoreSource {
                override suspend fun store() = transcripts
            },
        )
        return chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
    }

    @Test
    fun openSession_crossOrigin_forksIdNoticesAndKeepsLedgersApart() {
        val store = newStore()
        val transcripts = FakeSessionStore()
        val oldId = seedQA(transcripts)
        runBlocking { store.save(endpointB()) }
        val fake = ForkFakeProvider { req ->
            flow {
                val userMsg = req.messages.last { it.role == "user" }.text.substringBefore("\n\n")
                emit(StreamEvent.TextDelta(0, 0, if (userMsg.startsWith("QB")) "AB" else "reply"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, transcripts, fake)
        onMain { vm.openSession(oldId) }
        // Display replay still shows the archived rows; the ledger already forked.
        awaitTrue { vm.messages.value.size == 2 }
        assertEquals(listOf("QA", "AA"), vm.messages.value.map { it.text })
        awaitTrue { vm.notice.value == "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD" }
        val forkId = vm.currentSessionId.value
        assertTrue(forkId != null && forkId != oldId)

        onMain { vm.onInputChange("QB") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 4 }
        val req = fake.seenRequests.single()
        assertEquals(listOf("user"), req.messages.map { it.role })
        assertTrue(transcripts.events.filter { it.sessionId == oldId }.none { it.text.startsWith("QB") })
        assertTrue(transcripts.events.filter { it.sessionId == forkId }.any { it.text.startsWith("QB") })

        // A→B→B: a fresh VM reopening the fork hydrates only B's rows + notice.
        val vmB = newVm(store, transcripts, ForkFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "reply"))
                emit(StreamEvent.Done("stop"))
            }
        })
        onMain { vmB.openSession(checkNotNull(forkId)) }
        awaitTrue { vmB.currentSessionId.value == forkId && vmB.messages.value.size == 2 }
        assertTrue(vmB.messages.value[0].text.startsWith("QB"))
        assertEquals("AB", vmB.messages.value[1].text)
        assertEquals("RESUME_CROSS_PROVIDER_HISTORY_WITHHELD", vmB.notice.value)

        // A→B→A: the archived session under A still shows only QA/AA, no notice.
        runBlocking { store.save(endpointA()) }
        val vmA = newVm(store, transcripts, ForkFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "reply"))
                emit(StreamEvent.Done("stop"))
            }
        })
        onMain { vmA.openSession(oldId) }
        awaitTrue { vmA.currentSessionId.value == oldId && vmA.messages.value.size == 2 }
        assertEquals(listOf("QA", "AA"), vmA.messages.value.map { it.text })
        assertNull(vmA.notice.value)
    }

    @Test fun open_backfillScanCancel_propagatesInsteadOfSwallowing() = runBlocking {
        val transcripts = CancelBackfillStore()
        val oldId = seedQA(transcripts)
        transcripts.failTailCancel = true
        try {
            factoryWith(transcripts, answerProvider()).open(endpointA(), oldId, { true }, true).session.close()
            fail("backfill scan cancel must propagate, not resume silently without markers")
        } catch (_: CancellationException) {
            // Expected: lifecycle cancel releases the open instead of degrading.
        }
        assertTrue(transcripts.events.none { it.kind == "system" })
    }

    @Test fun open_backfillMarkerCancel_propagatesInsteadOfSwallowing() = runBlocking {
        val transcripts = CancelBackfillStore()
        val oldId = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
        // Kill-stranded turn so the backfill reaches the per-marker write.
        transcripts.appendEvent(event(oldId, "stranded", "user", "QSTRANDED"))
        transcripts.failAppendCancel = true
        try {
            factoryWith(transcripts, answerProvider()).open(endpointA(), oldId, { true }, true).session.close()
            fail("backfill marker cancel must propagate, not resume silently without markers")
        } catch (_: CancellationException) {
            // Expected.
        }
        assertTrue(transcripts.events.none { it.kind == "system" })
    }

    @Test fun open_metaReadCancel_propagatesInsteadOfUnknownSession() = runBlocking {
        val transcripts = CancelBackfillStore()
        val oldId = seedQA(transcripts)
        transcripts.failGetCancelFor = { true }
        try {
            factoryWith(transcripts, answerProvider()).open(endpointA(), oldId, { true }, true).session.close()
            fail("meta-read cancel must propagate, not degrade to UNKNOWN_SESSION")
        } catch (e: CancellationException) {
            assertEquals("ENDPOINT_BINDING_STALE", e.message)
        } catch (e: IllegalArgumentException) {
            fail("cancel must not degrade to ${e.message}")
        }
    }

    @Test fun openForked_metaReadCancel_propagates() = runBlocking {
        val transcripts = CancelBackfillStore()
        val oldId = seedQA(transcripts)
        // Initial meta read succeeds; only the fork's own header re-read cancels.
        transcripts.failGetCancelFor = { sid -> sid != oldId }
        try {
            factoryWith(transcripts, answerProvider()).open(endpointB(), oldId, { true }, true).session.close()
            fail("fork meta-read cancel must propagate")
        } catch (_: CancellationException) {
            // Expected: the fork header read is not silently nulled.
        }
    }

    /**
     * Phase 3 P1-2: cross-origin fork of a terminal-only old ledger (system
     * INTERRUPTED/cancel marks + one orphan reasoning marker, no
     * user/assistant left) persists only the withheld marker — never a copy
     * of the old terminal/reasoning rows. True Factory + ViewModel path over
     * FakeSessionStore; reopen is idempotent.
     */
    @Test
    fun ensureSession_crossOrigin_terminalOnlyLedger_forksMarkerOnly() {
        val store = newStore()
        val transcripts = FakeSessionStore()
        val oldId = runBlocking {
            val sid = transcripts.createSession("old chat", "preset:openai@https://api.openai.com/gpt-4o-mini")
            transcripts.appendEvent(event(sid, "r1", "system", "turn r1 interrupted"))
            transcripts.appendEvent(event(sid, "r2", "system", "turn r2 cancelled"))
            transcripts.appendEvent(event(sid, "r9", "reasoning", ""))
            sid
        }
        runBlocking { store.save(endpointA()) }
        val fake = ForkFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "AB"))
                emit(StreamEvent.Done("stop"))
            }
        }
        val vm = newVm(store, transcripts, fake)
        // Attach the terminal-only archive on the same origin first.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }
        assertTrue(vm.messages.value.isEmpty())
        assertNull(vm.notice.value)

        // Switch provider, then send: ensureSession must fork marker-only even
        // though the old ledger holds no user/assistant history.
        runBlocking { store.save(endpointB()) }
        onMain { vm.onInputChange("QB") }
        onMain { vm.send() }
        awaitTrue { vm.sessionState.value.status == ChatStatus.IDLE && vm.messages.value.size == 2 }
        val forkId = checkNotNull(vm.currentSessionId.value)
        assertNotEquals(oldId, forkId)
        // Empty old ledger withholds nothing visible: no switch-time notice.
        assertNull(vm.notice.value)
        // Fail closed: the forked request carries only the new turn.
        val req = fake.seenRequests.single()
        assertEquals(listOf("user"), req.messages.map { it.role })
        assertTrue(req.messages.single().text.startsWith("QB"))

        // Old ledger untouched (read-only archive).
        assertEquals(3, transcripts.events.count { it.sessionId == oldId })

        // Fork ledger: withheld marker + new turn only — no copied old
        // terminal/reasoning rows.
        val forkRows = transcripts.events.filter { it.sessionId == forkId }
        assertEquals(listOf("system", "user", "assistant"), forkRows.map { it.kind })
        assertTrue(modelSwitchMarkerWithheld(forkRows[0].text))
        assertTrue(forkRows.none { it.text == "turn r1 interrupted" || it.text == "turn r2 cancelled" })
        assertTrue(forkRows.none { it.kind == "reasoning" })
        assertTrue(forkRows[1].text.startsWith("QB"))
        assertEquals("AB", forkRows[2].text)

        // Reopen idempotent: restart hydrates only the fork's own rows, the
        // marker scan re-surfaces the withhold notice, and a second reopen
        // adds no rows.
        val vmB = newVm(store, transcripts, ForkFakeProvider { _ ->
            flow {
                emit(StreamEvent.TextDelta(0, 0, "reply"))
                emit(StreamEvent.Done("stop"))
            }
        })
        onMain { vmB.openSession(forkId) }
        awaitTrue { vmB.currentSessionId.value == forkId && vmB.messages.value.size == 2 }
        assertTrue(vmB.messages.value[0].text.startsWith("QB"))
        assertEquals("AB", vmB.messages.value[1].text)
        assertEquals("RESUME_CROSS_PROVIDER_HISTORY_WITHHELD", vmB.notice.value)
        val rowsAfterFirstReopen = transcripts.events.count { it.sessionId == forkId }
        onMain { vmB.openSession(forkId) }
        awaitTrue { vmB.currentSessionId.value == forkId && vmB.messages.value.size == 2 }
        assertEquals(rowsAfterFirstReopen, transcripts.events.count { it.sessionId == forkId })
    }
}
