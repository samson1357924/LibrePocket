package dev.librepocket.session

import dev.librepocket.agent.ui.chat.ChatSessionFactory
import dev.librepocket.agent.ui.chat.SessionStoreSource
import dev.librepocket.agent.ui.chat.VaultSource
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.agent.ui.chat.loadModelHistory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase2 sequential-fallback regressions: an external [SessionStore] that keeps
 * the interface-default sequential [SessionStore.appendEvents] loop (NON-ATOMIC,
 * no snapshot) and fails on the 2nd batch row must still fail closed.
 *
 * Current (marker-first): reasoning batches persist as [`reasoning`?,
 * `assistant`], so the surviving partial is only an orphan `reasoning` marker
 * — harmless (no `user` family, never model context, ignored by
 * [findDanglingRunIds]) — and the family stays dangling instead of completing
 * as a plain-text result. Target (atomic Room/fake) still commits all-or-zero;
 * this file pins only the fallback shape. Proposed (assistant-first) would
 * leave an orphan final `assistant` here and resume fail-open; the assertions
 * below reject exactly that shape.
 *
 * Each case also walks the true reopen path
 * (`ChatSessionFactory.open(backfillInterrupted=true)`, reopen-twice): the
 * orphan marker is never a terminal, so the family still dangles pre-open and
 * the first open backfills exactly one INTERRUPTED marker; the second open
 * adds zero rows. No sleep: direct `open` calls only.
 */
class SessionSequentialFallbackTest {

    private class SequentialReopenProvider : LlmProvider {
        override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = emptyFlow()
        override suspend fun listModels(): List<String> = emptyList()
    }

    /**
     * Minimal in-memory store that deliberately does NOT override
     * [SessionStore.appendEvents]: it inherits the interface-default
     * sequential loop (non-atomic). Fault injection targets the `assistant`
     * row (2nd row of a marker-first batch) with [IOException].
     */
    private class SequentialStore : SessionStore {
        val metas = LinkedHashMap<String, SessionMeta>()
        val events = ArrayList<TranscriptEvent>()
        var now = 1_700_000_000_000L
        var failAssistantRunIds = HashSet<String>()

        override suspend fun createSession(title: String, model: String): String {
            val id = UUID.randomUUID().toString()
            metas[id] = SessionMeta(id, title, now, now, model)
            return id
        }

        override suspend fun listSessions(): List<SessionMeta> =
            metas.values.sortedByDescending { it.updatedAt }

        override suspend fun getSession(sessionId: String): SessionMeta? = metas[sessionId]

        override suspend fun appendEvent(event: TranscriptEvent): Long {
            require(event.kind in VALID_KINDS)
            if (event.kind == "assistant" && event.runId in failAssistantRunIds) {
                throw IOException("injected second-row fault")
            }
            val max = events.filter { it.sessionId == event.sessionId }.maxOfOrNull { it.seq } ?: 0L
            check(max < Long.MAX_VALUE) { "seq exhausted" }
            events.add(event.copy(seq = max + 1))
            return events.size.toLong()
        }

        // NOTE: no appendEvents override — inherits the default sequential loop.

        override suspend fun loadEvents(sessionId: String, afterSeq: Long, limit: Int): List<TranscriptEvent> =
            events.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)

        override suspend fun exportJsonl(sessionId: String, destFile: File) = Unit
        override suspend fun importJsonl(srcFile: File): String = error("unused")
        override suspend fun prune(policy: PrunePolicy): PruneResult = PruneResult(0, 0)
        override suspend fun deleteSession(sessionId: String) {
            metas.remove(sessionId)
        }
    }

    private fun sampleEndpoint() = EndpointConfig(
        providerId = "preset:openai",
        presetId = "openai",
        label = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        protocol = ProviderProtocol.CHAT_COMPLETIONS,
        model = "gpt-4o-mini",
        apiKeyRef = "provider_key/preset:openai",
    )

    private fun factoryWith(store: SequentialStore) = ChatSessionFactory(
        policy = InMemoryPolicyStore(),
        vaultSource = VaultSource {
            val vault = EncryptedPrefsVault(InMemoryPrefs())
            vault.putKey("preset:openai", "test-key-12345678".toCharArray())
            vault
        },
        buildProvider = { _, _ -> SequentialReopenProvider() },
        sessionStores = object : SessionStoreSource {
            override suspend fun store(): SessionStore = store
        },
    )

    private fun interruptedMarkers(store: SequentialStore, sid: String) =
        store.events.filter { it.sessionId == sid && it.kind == "system" && it.text.endsWith("interrupted") }

    private suspend fun rows(store: SequentialStore, sid: String): List<TranscriptEvent> =
        store.loadEvents(sid, 0, 200)

    /** Shared fail-closed assertions for one orphan-marker ledger. */
    private suspend fun assertOrphanMarkerFailClosed(store: SequentialStore, sid: String, family: String) {
        val ledger = rows(store, sid)
        // Only the marker survived: no assistant row to misread as plain text.
        assertTrue("orphan assistant must never persist", ledger.none { it.kind == "assistant" })
        assertEquals(listOf("user", "reasoning"), ledger.map { it.kind })
        // The family still dangles (fail-closed) instead of completing.
        assertEquals(listOf(family), findDanglingRunIds(ledger))
        // Resume carries no plain-text assistant for the family.
        val history = loadModelHistory(store, sid)
        assertEquals(0, history.droppedCount)
        assertTrue(
            "no resumed assistant may read back as complete plain text",
            history.messages.none { it.role == "assistant" },
        )
    }

    /**
     * True-reopen proof (reopen-twice, no sleep): the orphan `reasoning`
     * marker is never a terminal, so the family still dangles pre-open and
     * the first `open(backfillInterrupted=true)` backfills exactly one
     * `turn <family> interrupted` system marker; the second open adds zero
     * rows. Mirrors the `backoffGapCancelMark_reopenSeesTerminalAndSecondOpenAddsZero`
     * reopen-twice shape.
     */
    private suspend fun assertReopenBackfillsSingleInterrupted(
        store: SequentialStore,
        sid: String,
        family: String,
    ) {
        // The backfill writes a system marker (never an assistant row), so the
        // injected assistant fault cannot swallow it; clear anyway for intent.
        store.failAssistantRunIds.clear()
        val factory = factoryWith(store)
        val endpoint = sampleEndpoint()
        val first = factory.open(endpoint, sid, { true }, true)
        try {
            val after = rows(store, sid)
            val markers = interruptedMarkers(store, sid)
            assertEquals("orphan marker must not close the family: exactly one INTERRUPTED", 1, markers.size)
            assertEquals(family, markers.single().runId)
            assertEquals("turn $family interrupted", markers.single().text)
            assertTrue("family closes only via the backfilled marker", findDanglingRunIds(after).isEmpty())
            // The orphan marker row itself is untouched (still payload-free).
            val orphans = after.filter { it.kind == "reasoning" }
            assertEquals(1, orphans.size)
            assertTrue("orphan reasoning marker must stay payload-free", orphans.single().text.isEmpty())
            // Resume still carries no plain-text assistant for the family.
            val history = loadModelHistory(store, sid)
            assertTrue(
                "no resumed assistant may read back as complete plain text",
                history.messages.none { it.role == "assistant" },
            )
        } finally {
            first.session.close()
        }
        val sizeAfterFirst = store.events.count { it.sessionId == sid }
        val second = factory.open(endpoint, sid, { true }, true)
        try {
            assertEquals("second open must add zero rows (idempotent marker)", sizeAfterFirst, store.events.count { it.sessionId == sid })
            assertEquals(1, interruptedMarkers(store, sid).size)
            assertTrue(findDanglingRunIds(rows(store, sid)).isEmpty())
        } finally {
            second.session.close()
        }
    }

    @Test
    fun success_secondRowFails_leavesOrphanMarkerAndFamilyDangling() = runBlocking {
        val store = SequentialStore()
        val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        sink.onTurnStarted("r1", "hello")
        store.failAssistantRunIds.add("r1")
        try {
            sink.onTurnSucceeded("r1", "answer", hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
            // Expected: the sequential fallback aborts mid-batch.
        }
        assertOrphanMarkerFailClosed(store, sid, "r1")
        assertReopenBackfillsSingleInterrupted(store, sid, "r1")
    }

    @Test
    fun cancel_secondRowFails_leavesOrphanMarkerAndFamilyDangling() = runBlocking {
        val store = SequentialStore()
        val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        sink.onTurnStarted("r1", "hello")
        store.failAssistantRunIds.add("r1")
        try {
            sink.onTurnCancelled("r1", "partial", hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
        }
        assertOrphanMarkerFailClosed(store, sid, "r1")
        assertReopenBackfillsSingleInterrupted(store, sid, "r1")
    }

    @Test
    fun failed7Arg_secondRowFails_leavesOrphanMarkerAndFamilyDangling() = runBlocking {
        val store = SequentialStore()
        val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        sink.onTurnStarted("r1", "hello")
        store.failAssistantRunIds.add("r1")
        try {
            sink.onTurnFailed("r1", "half", "boom", null, 0, true, true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
        }
        assertOrphanMarkerFailClosed(store, sid, "r1")
        assertReopenBackfillsSingleInterrupted(store, sid, "r1")
    }

    @Test
    fun success5Arg_secondRowFails_leavesOrphanMarkerAndFamilyDangling() = runBlocking {
        val store = SequentialStore()
        val sid = store.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val sink = SessionTranscriptSink(store, sid) { 42L }
        sink.onTurnStarted("L", "hello")
        store.failAssistantRunIds.add("a2")
        try {
            sink.onTurnSucceeded("a2", "answer", "L", 1, hasReasoning = true)
            fail("second-row store failure must propagate")
        } catch (_: IOException) {
        }
        val ledger = rows(store, sid)
        assertTrue(ledger.none { it.kind == "assistant" })
        // Attempt marker binds to the logical family; the family still dangles.
        assertEquals(listOf("L"), findDanglingRunIds(ledger))
        val history = loadModelHistory(store, sid)
        assertTrue(history.messages.none { it.role == "assistant" })
        assertReopenBackfillsSingleInterrupted(store, sid, "L")
    }
}
