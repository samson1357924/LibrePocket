package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.JsonlCodec
import dev.librepocket.session.JsonlLine
import dev.librepocket.session.TranscriptEvent
import java.io.File
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class SwitchSnapshotFakeProvider(
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

/** Fake with a real JSONL export (mirrors Room: every row, JsonlCodec lines). */
private class ExportingFakeStore : FakeSessionStore() {
    override suspend fun exportJsonl(sessionId: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.parentFile, destFile.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { out ->
            for (e in events.filter { it.sessionId == sessionId }.sortedBy { it.seq }) {
                out.write(JsonlCodec.encode(e))
                out.newLine()
            }
        }
        check(tmp.renameTo(destFile)) { "export failed" }
    }
}

/**
 * R5-3 same-origin fork-snapshot regressions: the durable snapshot now stores
 * the UI replay/audit projection (every user/assistant row incl. partials +
 * terminal system interrupted/cancelled/legacy-failure marks), while model
 * hydration keeps the `!isPartial` filter so the provider payload is
 * unchanged.
 *
 * Each path drives the production durable step directly
 * ([snapshotSwitchHistory], the exact call `ensureSession` makes) and then
 * the production reopen path (`factory.open` backfill + `send`, and
 * `ChatViewModel.openSession` UI replay), asserting UI + transcript + export
 * + [findDanglingRunIds] + provider request together. Seeded rows are
 * byte-identical to what `TurnController`/`SessionTranscriptSink` write
 * (first attempts reuse the logical id with null parent; retryable
 * intermediate partials are `isFinal=false`; backoff-gap cancels are a
 * system `turn <L> cancelled` mark).
 */
class ChatSessionFactorySwitchSnapshotTest {

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
        val dir = Files.createTempDirectory("r53-switch-test").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r53.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(
        transcripts: FakeSessionStore,
        fake: SwitchSnapshotFakeProvider,
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
        fake: SwitchSnapshotFakeProvider,
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
        isPartial: Boolean = false,
        failureReason: String? = null,
        parentRunId: String? = null,
        attemptIndex: Int? = null,
        isFinal: Boolean = true,
        createdAt: Long = 1L,
    ) = TranscriptEvent(
        sessionId = sid,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = createdAt,
        isPartial = isPartial,
        failureReason = failureReason,
        parentRunId = parentRunId,
        attemptIndex = attemptIndex,
        isFinal = isFinal,
    )

    private fun rows(store: FakeSessionStore, sid: String) =
        store.events.filter { it.sessionId == sid }.sortedBy { it.seq }

    private fun exportLines(store: FakeSessionStore, sid: String): List<JsonlLine> {
        val f = Files.createTempFile("r53-export", ".jsonl").toFile()
        try {
            runBlocking { store.exportJsonl(sid, f) }
            return f.readLines().filter { it.isNotBlank() }.mapIndexed { i, line ->
                JsonlCodec.decode(i + 1, line)
            }
        } finally {
            f.delete()
        }
    }

    private fun interruptedMarkers(store: FakeSessionStore, sid: String) =
        rows(store, sid).filter { it.kind == "system" && it.text.endsWith("interrupted") }

    private fun answeringFake() = SwitchSnapshotFakeProvider {
        flow {
            emit(StreamEvent.TextDelta(0, 0, "a-next"))
            emit(StreamEvent.Done("stop"))
        }
    }

    // -- shared assertions -------------------------------------------------

    private fun assertCopiedVerbatim(source: List<TranscriptEvent>, forked: List<TranscriptEvent>) {
        assertEquals(source.size, forked.size)
        for ((s, c) in source.zip(forked)) {
            assertEquals(s.runId, c.runId)
            assertEquals(s.kind, c.kind)
            assertEquals(s.text, c.text)
            assertEquals(s.isPartial, c.isPartial)
            assertEquals(s.failureReason, c.failureReason)
            assertEquals(s.parentRunId, c.parentRunId)
            assertEquals(s.attemptIndex, c.attemptIndex)
            assertEquals(s.isFinal, c.isFinal)
            assertEquals(s.createdAt, c.createdAt)
        }
    }

    private fun assertExportMatchesSource(source: List<TranscriptEvent>, lines: List<JsonlLine>) {
        assertEquals(source.size, lines.size)
        for ((s, l) in source.zip(lines)) {
            assertEquals(s.runId, l.runId)
            assertEquals(s.kind, l.kind)
            assertEquals(s.text, l.text)
            assertEquals(s.imagesOmitted, l.imagesOmitted)
            assertEquals(s.createdAt, l.createdAt)
            assertEquals(s.isPartial, l.isPartial)
            assertEquals(s.failureReason, l.failureReason)
            assertEquals(s.parentRunId, l.parentRunId)
            assertEquals(s.attemptIndex, l.attemptIndex)
            assertEquals(s.isFinal, l.isFinal)
        }
    }

    // -- path 1: intentional cancel -----------------------------------------

    @Test fun cancelSwitchReopen_keepsPartialWithNoFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = ExportingFakeStore()
        val fake = answeringFake()
        val factory = factoryWith(transcripts, fake)

        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))
        transcripts.appendEvent(
            event(oldId, "L", "assistant", "half-cancelled", isPartial = true, attemptIndex = 0),
        )

        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(4, copied)

        // Transcript: marker row 1, then the full UI/audit prefix verbatim.
        val forked = rows(transcripts, newId)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), forked.map { it.kind })
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertFalse(forked[0].text.contains("withheld"))
        assertCopiedVerbatim(rows(transcripts, oldId), forked.drop(1))
        val partial = forked.single { it.text == "half-cancelled" }
        assertTrue(partial.isPartial)
        assertTrue(partial.isFinal)
        assertTrue(findDanglingRunIds(forked).isEmpty())

        // Export: full old export vs new export (marker + same prefix modulo seq).
        val oldLines = exportLines(transcripts, oldId)
        assertEquals(4, oldLines.size)
        val newLines = exportLines(transcripts, newId)
        assertEquals(5, newLines.size)
        assertTrue(newLines[0].text.startsWith("model switched "))
        assertExportMatchesSource(rows(transcripts, oldId), newLines.drop(1))

        // UI: reopen replays the half fragment flagged, with no notice.
        val vm = newVm(store, transcripts, fake)
        onMain { vm.openSession(newId) }
        awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.size == 4 }
        assertEquals(listOf("q0", "a0", "q-cancel", "half-cancelled"), vm.messages.value.map { it.text })
        assertEquals(listOf(false, false, false, true), vm.messages.value.map { it.isPartial })
        assertNull(vm.notice.value)

        // Reopen backfill: no false INTERRUPTED for the copied cancel family.
        val before = interruptedMarkers(transcripts, newId).size
        val opened = factory.open(sampleEndpoint(), newId)
        try {
            assertEquals(before, interruptedMarkers(transcripts, newId).size)
            assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())
            // Provider: the next request carries completed history + the live
            // user row, but never the partial fragment.
            opened.session.send("q-next")
            val req = fake.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user", "user"), req.messages.map { it.role })
            assertEquals("q0", req.messages[0].text)
            assertEquals("a0", req.messages[1].text)
            assertEquals("q-cancel", req.messages[2].text)
            assertTrue(req.messages[3].text.startsWith("q-next"))
            assertTrue(req.messages.none { it.text.contains("half-cancelled") })
        } finally {
            opened.session.close()
        }
    }

    // -- path 2: terminal failure --------------------------------------------

    @Test fun terminalFailSwitchReopen_keepsFailureReasonInThreePlaces() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = ExportingFakeStore()
        val fake = answeringFake()
        val factory = factoryWith(transcripts, fake)

        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        transcripts.appendEvent(event(oldId, "L", "user", "q-fail"))
        transcripts.appendEvent(
            event(
                oldId, "L", "assistant", "frag-fail",
                isPartial = true, failureReason = "HTTP 503", attemptIndex = 0,
            ),
        )

        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(4, copied)

        // Transcript + failureReason preserved verbatim on the copy.
        val forked = rows(transcripts, newId)
        assertEquals(listOf("system", "user", "assistant", "user", "assistant"), forked.map { it.kind })
        val sourcePartial = rows(transcripts, oldId).single { it.text == "frag-fail" }
        val copiedPartial = forked.single { it.text == "frag-fail" }
        assertEquals("HTTP 503", sourcePartial.failureReason)
        assertEquals(sourcePartial.failureReason, copiedPartial.failureReason)
        assertTrue(copiedPartial.isPartial && copiedPartial.isFinal)
        assertTrue(findDanglingRunIds(forked).isEmpty())

        // Export: the reason survives in the JSONL line too (third place).
        val newLines = exportLines(transcripts, newId)
        assertEquals(5, newLines.size)
        val exportedPartial = newLines.single { it.text == "frag-fail" }
        assertEquals("HTTP 503", exportedPartial.failureReason)
        assertTrue(exportedPartial.isPartial && exportedPartial.isFinal)
        assertExportMatchesSource(rows(transcripts, oldId), newLines.drop(1))

        // UI: failed fragment replays flagged.
        val vm = newVm(store, transcripts, fake)
        onMain { vm.openSession(newId) }
        awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.size == 4 }
        assertEquals(listOf("q0", "a0", "q-fail", "frag-fail"), vm.messages.value.map { it.text })
        assertEquals(listOf(false, false, false, true), vm.messages.value.map { it.isPartial })
        assertNull(vm.notice.value)

        // Reopen backfill + provider (partial excluded from model context).
        val before = interruptedMarkers(transcripts, newId).size
        val opened = factory.open(sampleEndpoint(), newId)
        try {
            assertEquals(before, interruptedMarkers(transcripts, newId).size)
            assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())
            opened.session.send("q-next")
            val req = fake.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user", "user"), req.messages.map { it.role })
            assertEquals("q0", req.messages[0].text)
            assertEquals("a0", req.messages[1].text)
            assertEquals("q-fail", req.messages[2].text)
            assertTrue(req.messages[3].text.startsWith("q-next"))
            assertTrue(req.messages.none { it.text.contains("frag-fail") })
        } finally {
            opened.session.close()
        }
    }

    // -- path 3: retry backoff-gap cancel --------------------------------------

    @Test fun backoffCancelSwitchReopen_keepsPartialAndCancelMark() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = ExportingFakeStore()
        val fake = answeringFake()
        val factory = factoryWith(transcripts, fake)

        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        transcripts.appendEvent(event(oldId, "L", "user", "q-retry"))
        transcripts.appendEvent(
            event(
                oldId, "L", "assistant", "half-retry",
                isPartial = true, failureReason = "boom", attemptIndex = 0, isFinal = false,
            ),
        )
        transcripts.appendEvent(event(oldId, "L", "retry", "attempt 1/3 after 2000ms"))
        transcripts.appendEvent(event(oldId, "L", "system", "turn L cancelled"))

        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        // user/assistant (4) + terminal system cancel mark (1); the retry notice stays behind.
        assertEquals(5, copied)

        val forked = rows(transcripts, newId)
        assertEquals(
            listOf("system", "user", "assistant", "user", "assistant", "system"),
            forked.map { it.kind },
        )
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertFalse(forked.any { it.kind == "retry" })
        val keptPartial = forked.single { it.text == "half-retry" }
        assertTrue(keptPartial.isPartial)
        assertFalse(keptPartial.isFinal)
        assertEquals("boom", keptPartial.failureReason)
        assertEquals("turn L cancelled", forked.last().text)
        assertEquals("L", forked.last().runId)
        assertTrue(findDanglingRunIds(forked).isEmpty())

        // Export: marker + projected prefix (retry excluded by design).
        val newLines = exportLines(transcripts, newId)
        assertEquals(6, newLines.size)
        assertTrue(newLines[0].text.startsWith("model switched "))
        assertTrue(newLines.none { it.kind == "retry" })
        assertTrue(newLines.any { it.text == "turn L cancelled" })
        val expectedSource = rows(transcripts, oldId).filter {
            it.kind == "user" || it.kind == "assistant" || it.text == "turn L cancelled"
        }
        assertExportMatchesSource(expectedSource, newLines.drop(1))

        // UI: user + intermediate partial replay; system cancel mark stays hidden.
        val vm = newVm(store, transcripts, fake)
        onMain { vm.openSession(newId) }
        awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.size == 4 }
        assertEquals(listOf("q0", "a0", "q-retry", "half-retry"), vm.messages.value.map { it.text })
        assertEquals(listOf(false, false, false, true), vm.messages.value.map { it.isPartial })
        assertNull(vm.notice.value)

        // Reopen backfill: the copied cancel mark still closes the family.
        val before = interruptedMarkers(transcripts, newId).size
        val opened = factory.open(sampleEndpoint(), newId)
        try {
            assertEquals(before, interruptedMarkers(transcripts, newId).size)
            assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())
            opened.session.send("q-next")
            val req = fake.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user", "user"), req.messages.map { it.role })
            assertEquals("q0", req.messages[0].text)
            assertEquals("a0", req.messages[1].text)
            assertEquals("q-retry", req.messages[2].text)
            assertTrue(req.messages[3].text.startsWith("q-next"))
            assertTrue(req.messages.none { it.text.contains("half-retry") })
        } finally {
            opened.session.close()
        }
    }

    // -- exclusion: usage/tool/steer/retry/old-marker never cross -------------

    @Test fun switchSnapshot_excludesUsageToolSteerRetryAndOldMarker() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = ExportingFakeStore()
        val fake = answeringFake()
        val factory = factoryWith(transcripts, fake)

        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        transcripts.appendEvent(event(oldId, "r0", "system", "usage input=1 output=2"))
        transcripts.appendEvent(event(oldId, "r0", "tool", "[tool:search {\"q\":\"x\"}]"))
        transcripts.appendEvent(event(oldId, "steer-x", "steer", "be brief"))
        transcripts.appendEvent(event(oldId, "r0", "retry", "attempt 1/3 after 2000ms"))
        transcripts.appendEvent(event(oldId, "switch-old", "system", "model switched m0 -> m1"))
        // Stranded user: Phase2 drops it at fork time (unconditional filter)
        // with the "dangling excluded" incomplete marker instead of copying
        // it for a later backfill — see cleanForkWithDanglingPrefix below.
        transcripts.appendEvent(event(oldId, "Lstrand", "user", "q-strand"))

        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(2, copied)

        val forked = rows(transcripts, newId)
        assertEquals(listOf("system", "system", "user", "assistant"), forked.map { it.kind })
        assertEquals(listOf("q0", "a0"), forked.drop(2).map { it.text })
        assertTrue(forked[1].text.startsWith("snapshot incomplete "))
        assertTrue(forked[1].text.contains("dangling excluded"))
        assertTrue("orphan user(Lstrand) must never copy", forked.none { it.runId == "Lstrand" && it.kind == "user" })
        // Each fork owns exactly one switch marker: the old one never copies.
        assertEquals(1, forked.count { it.kind == "system" && it.text.startsWith("model switched ") })
        assertTrue(forked.none { it.text.startsWith("usage ") })
        assertTrue(forked.none { it.kind == "tool" || it.kind == "steer" || it.kind == "retry" })

        // Reopen: the dropped strand stays dropped (no false INTERRUPTED);
        // completed families gain none.
        val opened = factory.open(sampleEndpoint(), newId)
        try {
            val markers = interruptedMarkers(transcripts, newId)
            assertEquals(0, markers.size)
            assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())

            opened.session.send("q-next")
            val req = fake.seenRequests.single()
            assertTrue(req.messages.none { it.text.startsWith("usage ") })
            assertTrue(req.messages.none { it.text.startsWith("model switched ") })
        } finally {
            opened.session.close()
        }

        // UI replays only the carried user/assistant rows (plus the new turn's
        // live rows once sent); notices/markers stay hidden.
        val vm = newVm(store, transcripts, fake)
        onMain { vm.openSession(newId) }
        awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.isNotEmpty() }
        assertTrue(vm.messages.value.any { it.text == "q0" })
        assertTrue(vm.messages.value.none { it.text.startsWith("usage ") })
        assertTrue(vm.messages.value.none { it.text.startsWith("model switched ") })
    }

    // -- R8-4: reasoning markers ride the fork + hydrate the request flag ---

    @Test fun reasoningMarkersSwitchReopen_restoreUiAndRequestFlags() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = ExportingFakeStore()
        val fake = answeringFake()
        val factory = factoryWith(transcripts, fake)

        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        // Success reasoning turn: assistant + payload-free marker, same runId.
        transcripts.appendEvent(event(oldId, "R1", "user", "q-reason"))
        transcripts.appendEvent(event(oldId, "R1", "assistant", "a-reason"))
        transcripts.appendEvent(event(oldId, "R1", "reasoning", ""))
        // Cancel reasoning turn: partial assistant + marker, same runId.
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))
        transcripts.appendEvent(event(oldId, "L", "assistant", "half-cancel", isPartial = true))
        transcripts.appendEvent(event(oldId, "L", "reasoning", ""))

        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false)
        assertEquals(8, copied)

        // Ledger: marker row 1, then the full prefix verbatim incl. markers.
        val forked = rows(transcripts, newId)
        assertEquals(
            listOf("system", "user", "assistant", "user", "assistant", "reasoning", "user", "assistant", "reasoning"),
            forked.map { it.kind },
        )
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertCopiedVerbatim(rows(transcripts, oldId), forked.drop(1))
        // Reasoning markers are never dangling terminals.
        assertTrue(findDanglingRunIds(forked).isEmpty())
        assertTrue(findDanglingRunIds(forked.filter { it.kind == "reasoning" }).isEmpty())

        // UI: reopen replays user/assistant only, restoring hasReasoning.
        val vm = newVm(store, transcripts, fake)
        onMain { vm.openSession(newId) }
        awaitTrue { vm.currentSessionId.value == newId && vm.messages.value.size == 6 }
        assertEquals(
            listOf("q0", "a0", "q-reason", "a-reason", "q-cancel", "half-cancel"),
            vm.messages.value.map { it.text },
        )
        assertEquals(
            listOf(false, false, false, true, false, true),
            vm.messages.value.map { it.hasReasoning },
        )
        assertEquals(listOf(false, false, false, false, false, true), vm.messages.value.map { it.isPartial })
        assertNull(vm.notice.value)

        // Model hydration: the next request carries the flag on the
        // reasoning-origin assistant only; the partial never enters context.
        val before = interruptedMarkers(transcripts, newId).size
        val opened = factory.open(sampleEndpoint(), newId)
        try {
            assertEquals(before, interruptedMarkers(transcripts, newId).size)
            opened.session.send("q-next")
            val req = fake.seenRequests.single()
            assertEquals(
                listOf("user", "assistant", "user", "assistant", "user", "user"),
                req.messages.map { it.role },
            )
            assertEquals("q0", req.messages[0].text)
            assertEquals("a0", req.messages[1].text)
            assertEquals("q-reason", req.messages[2].text)
            assertEquals("a-reason", req.messages[3].text)
            assertEquals("q-cancel", req.messages[4].text)
            assertTrue(req.messages[5].text.startsWith("q-next"))
            assertEquals(listOf(false, false, false, true, false, false), req.messages.map { it.hasReasoning })
        } finally {
            opened.session.close()
        }
    }

    @Test fun reasoningMarkerHydration_exactRunIdOnly() = runBlocking {
        val transcripts = ExportingFakeStore()
        val sid = transcripts.createSession("t", "m")
        // Plain turn must not gain the flag from a neighboring marker
        // (exact-runId pairing, never family): R-other marker shares no
        // runId with R-plain.
        transcripts.appendEvent(event(sid, "R-plain", "user", "q1"))
        transcripts.appendEvent(event(sid, "R-plain", "assistant", "a1"))
        transcripts.appendEvent(event(sid, "R-other", "reasoning", ""))
        transcripts.appendEvent(event(sid, "R-flagged", "user", "q2"))
        transcripts.appendEvent(event(sid, "R-flagged", "assistant", "a2"))
        transcripts.appendEvent(event(sid, "R-flagged", "reasoning", ""))

        val loaded = loadModelHistory(transcripts, sid)
        assertEquals(4, loaded.messages.size)
        assertEquals(listOf("q1", "a1", "q2", "a2"), loaded.messages.map { it.text })
        assertEquals(listOf(false, false, false, true), loaded.messages.map { it.hasReasoning })
        // Markers never count as model rows.
        assertEquals(0, loaded.droppedCount)
    }

    /**
     * Phase2 clean-fork fail-closed: a settled-fence fork (the exact
     * `durableSnapshotOrWarn` clean-path call: `wasStreaming=false`,
     * `settleOk=durableOk=true` → `excludeDanglingFamilies=false`,
     * `markIncomplete=false`) whose prefix still dangles must NOT copy the
     * orphan `user(L)` — the unconditional in-memory filter drops it and the
     * batch rides the "dangling excluded" incomplete marker instead. Existing
     * SwitchSnapshot/SwitchStreaming tests only cover the streaming-triggered
     * (flag-true) shape, never this clean-flag shape.
     */
    @Test fun cleanForkWithDanglingPrefix_dropsOrphanAndMarksIncomplete() = runBlocking {
        val transcripts = ExportingFakeStore()
        val fake = answeringFake()
        val factory = factoryWith(transcripts, fake)
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        // Stranded turn: user(L) with no terminal (kill before any terminal
        // record, fence already settled clean).
        transcripts.appendEvent(event(oldId, "L", "user", "q-strand"))

        val newId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o")
        // Clean-path call shape (wasStreaming=false, settleOk=durableOk=true).
        val copied = snapshotSwitchHistory(
            transcripts, oldId, newId, "gpt-4o-mini", "gpt-4o", false,
            excludeDanglingFamilies = false, markIncomplete = false,
        )
        assertEquals(2, copied)

        val forked = rows(transcripts, newId)
        // Marker row 1, incomplete row 2, then the completed family only.
        assertEquals(listOf("system", "system", "user", "assistant"), forked.map { it.kind })
        assertTrue(forked[0].text.startsWith("model switched gpt-4o-mini -> gpt-4o"))
        assertTrue(forked[1].text.startsWith("snapshot incomplete "))
        assertTrue("dangling drop must ride the dangling-excluded cause", forked[1].text.contains("dangling excluded"))
        // The orphan user row never crosses: no false INTERRUPTED can follow.
        assertTrue("orphan user(L) must never copy", forked.none { it.kind == "user" && it.runId == "L" })
        assertTrue(findDanglingRunIds(forked).isEmpty())

        // Export: marker + incomplete mark + the completed family only.
        val newLines = exportLines(transcripts, newId)
        assertEquals(4, newLines.size)
        assertTrue(newLines[0].text.startsWith("model switched "))
        assertTrue(newLines[1].text.startsWith("snapshot incomplete "))
        assertTrue("dangling drop must ride the dangling-excluded cause", newLines[1].text.contains("dangling excluded"))
        assertExportMatchesSource(rows(transcripts, oldId).take(2), newLines.drop(2))

        // Reopen: the dropped strand stays dropped (no false INTERRUPTED);
        // the clean fork ledger stays clean (mirrors the
        // switchSnapshot_excludes reopen section).
        val opened = factory.open(sampleEndpoint(), newId)
        try {
            assertEquals(0, interruptedMarkers(transcripts, newId).size)
            assertTrue(findDanglingRunIds(rows(transcripts, newId)).isEmpty())

            opened.session.send("q-next")
            val req = fake.seenRequests.single()
            assertEquals(listOf("user", "assistant", "user"), req.messages.map { it.role })
            assertEquals("q0", req.messages[0].text)
            assertEquals("a0", req.messages[1].text)
            assertTrue(req.messages[2].text.startsWith("q-next"))
            assertEquals(0, interruptedMarkers(transcripts, newId).size)
        } finally {
            opened.session.close()
        }
    }

    // -- D3: cross-origin withheld snapshot carries zero old rows ----------

    @Test fun withheldSnapshot_writesMarkerOnly_zeroOldRows() = runBlocking {
        val transcripts = ExportingFakeStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))
        transcripts.appendEvent(event(oldId, "L", "assistant", "half-cancel", isPartial = true))
        transcripts.appendEvent(event(oldId, "L", "reasoning", ""))
        transcripts.appendEvent(event(oldId, "L", "system", "turn L cancelled"))
        transcripts.appendEvent(event(oldId, "r0", "system", "usage input=1 output=2"))

        val newId = transcripts.createSession("t", "preset:anthropic@https://api.anthropic.com/claude")
        val copied = snapshotSwitchHistory(transcripts, oldId, newId, "gpt-4o-mini", "claude", true)
        assertEquals(0, copied)

        // Copy gate is origin-only: apart from the withheld switch marker
        // (no incomplete marker rides a clean withheld write), the fork holds
        // zero old rows — no user/assistant, no reasoning marker, no terminal
        // mark, no usage row.
        val forked = rows(transcripts, newId)
        assertEquals(1, forked.size)
        assertEquals("system", forked.single().kind)
        assertTrue(modelSwitchMarkerWithheld(forked.single().text))
        assertTrue(forked.none { it.kind == "user" || it.kind == "assistant" || it.kind == "reasoning" })
        assertTrue(forked.none { it.text == "q0" || it.text == "a0" || it.text == "half-cancel" })
        assertTrue(forked.none { it.text == "turn L cancelled" })
        assertTrue(forked.none { it.text.startsWith("usage ") })
        assertTrue(forked.none { isSnapshotIncompleteMarker(it.text) })
        assertTrue(findDanglingRunIds(forked).isEmpty())
    }
}
