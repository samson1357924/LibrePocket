package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatImageRef
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatSessionImpl
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import dev.librepocket.chat.QueuedIntent
import dev.librepocket.chat.TranscriptDurabilityException
import dev.librepocket.chat.TurnController
import dev.librepocket.chat.TurnStart
import dev.librepocket.chat.UiMessage
import dev.librepocket.chat.stripTimeBlock
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private class StreamingStuckProvider(
    val streamGate: CompletableDeferred<Unit>,
    val cleanupGate: CompletableDeferred<Unit>,
) : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    val seen = CopyOnWriteArrayList<String>()
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val base = stripTimeBlock(request.messages.last { it.role == "user" }.text)
        seen.add(base)
        if (base == "q-cancel") {
            emit(StreamEvent.TextDelta(0, 0, "half-cancelled"))
            try {
                streamGate.await()
                emit(StreamEvent.Done("stop"))
            } finally {
                withContext(NonCancellable) {
                    cleanupGate.await()
                }
            }
        } else {
            emit(StreamEvent.TextDelta(0, 0, "a-next"))
            emit(StreamEvent.Done("stop"))
        }
    }

    override suspend fun listModels(): List<String> = emptyList()
}

private class AnsweringProvider : LlmProvider {
    override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        emit(StreamEvent.TextDelta(0, 0, "a-next"))
        emit(StreamEvent.Done("stop"))
    }

    override suspend fun listModels(): List<String> = emptyList()
}

/**
 * R6-3 streaming-switch regressions (same-origin fork while the old live turn
 * has not settled yet).
 *
 * - delayedCancelCommit: TextDelta streaming stuck, cancel terminal delayed in
 *   provider cleanup (delays admission), same-origin switch fences (close-then-
 *   flush on IO) before the snapshot read, so S2 copies the full family and
 *   reopen gains no false INTERRUPTED.
 * - timeoutFallback: fence timeout / flushDurable==false degrades to the
 *   incomplete path (marker + HISTORY_SNAPSHOT_INCOMPLETE) and skips the
 *   dangling family, so reopen still gains no false INTERRUPTED.
 */
class ChatSessionFactorySwitchStreamingTest {

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
        val dir = Files.createTempDirectory("r63-switch-streaming").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r63.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(
        transcripts: FakeSessionStore,
        fake: LlmProvider,
    ) = ChatSessionFactory(
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

    private fun newVm(
        store: EndpointStore,
        transcripts: FakeSessionStore,
        fake: LlmProvider,
    ): ChatViewModel {
        val vault = EncryptedPrefsVault(InMemoryPrefs())
        runBlocking { vault.putKey("preset:openai", "test-key-12345678".toCharArray()) }
        val factory = factoryWith(transcripts, fake)
        return chatViewModels.own(ChatViewModel(store, factory, InMemoryPolicyStore()))
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

    @Test fun switchWhileStreaming_delayedCancelCommit_capturesTerminal() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val fake = StreamingStuckProvider(streamGate, cleanupGate)
        val factory = factoryWith(transcripts, fake)
        val vm = newVm(store, transcripts, fake)
        try {
            // 1. Streaming stuck: DB only user L, UI has partial.
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }
            awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
            awaitTrue { rows(transcripts, s1).any { it.kind == "user" && it.text.startsWith("q-cancel") } }
            assertTrue(
                "cancel terminal must not be durable yet",
                rows(transcripts, s1).none { it.kind == "assistant" },
            )

            // 2. Same-origin switch parks the old ledger (invalidate fence).
            runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4o")) }
            awaitTrue { vm.sessionState.value.messages.isEmpty() }

            // 3. Next send drives the fork; it must fence on the old settle
            // (still blocked in provider cleanup), so S2 is not attached yet.
            onMain { vm.sendDirect("q-next") }
            delay(500)
            assertEquals(
                "fork must fence on old settle (still S1 while cancel blocked)",
                s1,
                vm.currentSessionId.value,
            )

            // 4. Release: old terminal settles to S1 only, fork copies it to S2.
            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            awaitTrue(timeoutMs = 15000) {
                vm.currentSessionId.value != null && vm.currentSessionId.value != s1
            }
            val s2 = vm.currentSessionId.value!!
            awaitTrue(timeoutMs = 15000) { vm.messages.value.any { it.text == "a-next" } }
            awaitTrue(timeoutMs = 10000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            val s1rows = rows(transcripts, s1)
            assertEquals(listOf("user", "assistant"), s1rows.map { it.kind })
            assertTrue(s1rows[0].text.startsWith("q-cancel"))
            assertEquals("half-cancelled", s1rows[1].text)
            assertTrue(s1rows[1].isPartial && s1rows[1].isFinal)

            awaitTrue(timeoutMs = 10000) { rows(transcripts, s2).size >= 5 }
            val s2rows = rows(transcripts, s2)
            assertTrue(s2rows[0].kind == "system" && s2rows[0].text.startsWith("model switched "))
            assertTrue(
                "clean streaming switch must not warn",
                s2rows.none { it.text.startsWith("snapshot incomplete ") },
            )
            val copiedUser = s2rows.single { it.kind == "user" && it.text.startsWith("q-cancel") }
            assertEquals(s1rows[0].runId, copiedUser.runId)
            val copiedAsst = s2rows.single { it.kind == "assistant" && it.text == "half-cancelled" }
            assertEquals(s1rows[1].runId, copiedAsst.runId)
            assertTrue(copiedAsst.isPartial && copiedAsst.isFinal)
            assertTrue(findDanglingRunIds(s2rows).isEmpty())

            // Old terminal landed in S1 only (S2 holds copies under its own id).
            assertTrue(s1rows.all { it.sessionId == s1 })
            assertTrue(rows(transcripts, s2).all { it.sessionId == s2 })
            val s2size = rows(transcripts, s2).size
            delay(500)
            assertEquals(s2size, rows(transcripts, s2).size)

            // Reopen S2 twice: zero new INTERRUPTED markers, no dangling.
            val before = interruptedMarkers(transcripts, s2).size
            val opened1 = factory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
            try {
                assertEquals(before, interruptedMarkers(transcripts, s2).size)
                assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
            } finally {
                opened1.session.close()
            }
            val opened2 = factory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
            try {
                assertEquals(before, interruptedMarkers(transcripts, s2).size)
                assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
            } finally {
                opened2.session.close()
            }
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    // -- fallback C: fence failure degrades to incomplete + skips dangling ---

    private class TimeoutOldSession : ChatSession {
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
        @Volatile var closed = false

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
        override fun hasDurableFailures(): Boolean = true
        // Injected timeout/failure: fence joins but durability never holds.
        override suspend fun flushDurable(timeoutMs: Long): Boolean = false
        override fun hasPendingAdmission(): Boolean = false
        override fun pendingTranscriptRunIds(): List<String> = listOf("L")
        override fun steer(text: String) = Unit
        override fun close() {
            closed = true
            closeCalls.incrementAndGet()
        }
    }

    private class TimeoutNewSession : ChatSession {
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

    private class TimeoutSessions(
        val transcripts: FakeSessionStore,
        val oldId: String,
        val oldSession: ChatSession,
        val newSession: TimeoutNewSession,
    ) : ChatSessionProvider {
        val createdNewIds = CopyOnWriteArrayList<String>()
        override fun modelFor(endpoint: EndpointConfig): String = endpoint.model
        override suspend fun storeOrNull(): SessionStore? = transcripts
        override suspend fun create(
            endpoint: EndpointConfig,
            title: String,
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
    }

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

    @Test fun switchWhileStreaming_timeoutFallback_marksIncompleteAndSkipsDangling() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        // Dangling live family: user L without any terminal (streaming killed mid-turn).
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val oldSession = TimeoutOldSession()
        val newSession = TimeoutNewSession()
        val sessions = TimeoutSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        // Attach the old ledger.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }

        // Same-origin switch, then the fork-driving send.
        runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4o")) }
        awaitTrue { vm.sessionState.value.messages.isEmpty() || vm.currentSessionId.value == oldId }
        onMain { vm.sendDirect("q-next") }
        awaitTrue(timeoutMs = 15000) { sessions.createdNewIds.isNotEmpty() }
        val s2 = sessions.createdNewIds.first()
        awaitTrue(timeoutMs = 10000) { newSession.sent.isNotEmpty() }

        // Fallback: incomplete marker, dangling L skipped, completed prefix kept.
        val s2rows = rows(transcripts, s2)
        assertTrue(s2rows[0].kind == "system" && s2rows[0].text.startsWith("model switched "))
        assertTrue(s2rows.any { it.text.startsWith("snapshot incomplete ") })
        assertEquals(listOf("q0", "a0"), s2rows.filter { it.kind != "system" }.map { it.text })
        assertTrue(s2rows.none { it.text == "q-cancel" })
        assertTrue(findDanglingRunIds(s2rows).isEmpty())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)

        // Reopen twice: zero new INTERRUPTED markers, still no dangling.
        val reopenFactory = factoryWith(transcripts, AnsweringProvider())
        val before = interruptedMarkers(transcripts, s2).size
        val opened1 = reopenFactory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
        try {
            assertEquals(before, interruptedMarkers(transcripts, s2).size)
            assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
        } finally {
            opened1.session.close()
        }
        val opened2 = reopenFactory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
        try {
            assertEquals(before, interruptedMarkers(transcripts, s2).size)
            assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
        } finally {
            opened2.session.close()
        }
        assertNull(
            "reopen must not add a false INTERRUPTED for the skipped family",
            rows(transcripts, s2).singleOrNull { it.text == "turn L interrupted" },
        )
    }

    /**
     * R8-2 consecutive-switch fence: M1 streams (cancel terminal blocked in
     * provider cleanup), M1->M2 parks the S1 fence, the M2 fork blocks on it,
     * then M2->M3 invalidates again while still blocked. The M2 cancel must
     * not drop the S1 fence (generation bump is transport replacement, not
     * history abandonment): the M3 fork still joins it, so S3 copies the
     * settled terminal (or degrades to incomplete) and a double reopen of S3
     * gains zero false INTERRUPTED markers.
     */
    @Test fun doubleSwitchWhileFenceBlocked_noLostFence_noFalseInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val streamGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val fake = StreamingStuckProvider(streamGate, cleanupGate)
        val factory = factoryWith(transcripts, fake)
        val vm = newVm(store, transcripts, fake)
        val m3 = sampleEndpoint().copy(model = "gpt-4-turbo")
        try {
            // 1. M1 streaming stuck: DB only user L, UI has partial.
            onMain { vm.sendDirect("q-cancel") }
            awaitTrue { vm.currentSessionId.value != null }
            val s1 = vm.currentSessionId.value!!
            awaitTrue { vm.messages.value.any { it.text == "half-cancelled" } }
            awaitTrue { vm.sessionState.value.status == ChatStatus.STREAMING }
            awaitTrue { rows(transcripts, s1).any { it.kind == "user" && it.text.startsWith("q-cancel") } }
            assertTrue(
                "cancel terminal must not be durable yet",
                rows(transcripts, s1).none { it.kind == "assistant" },
            )

            // 2. M1->M2 parks the S1 fence (invalidate, still blocked).
            runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4o")) }
            awaitTrue { vm.sessionState.value.messages.isEmpty() }

            // 3. M2 fork starts and fences on the blocked settle.
            onMain { vm.sendDirect("q-next") }
            delay(500)
            assertEquals(
                "fork must fence on old settle (still S1 while cancel blocked)",
                s1,
                vm.currentSessionId.value,
            )

            // 4. M2->M3 consecutive switch while the fence is still blocked.
            // Same origin (copies), different binding (invalidates). This
            // cancels the M2 fork: with the R8-2 fix it re-parks the S1 fence
            // instead of dropping it as "gen-stale".
            runBlocking { store.save(m3) }
            // Let the M3 invalidation land and the M2 fork cancel+repark
            // before driving the M3 fork (a send racing the invalidation
            // would authorize on the dead M2 generation and die with it).
            delay(2000)
            onMain { vm.sendDirect("q-next") }
            delay(500)
            assertEquals(
                "M2 cancel must not lose the S1 fence (M3 fork still fenced)",
                s1,
                vm.currentSessionId.value,
            )

            // 5. Release: old terminal settles to S1 only, M3 fork copies it.
            cleanupGate.complete(Unit)
            streamGate.complete(Unit)
            // The M3 fork above is fenced on the released settle and attaches
            // S3. If the step-4 send raced the M3 invalidation (authorized on
            // the dead M2 generation, died with it), no fork is live after
            // the release — drive the M3 fork explicitly, once, mid-wait.
            // Idempotent when the fenced fork already won (S3 attached: the
            // resend just chats on S3).
            val attachEnd = System.currentTimeMillis() + 15000
            var resent = false
            while (vm.currentSessionId.value == s1) {
                if (System.currentTimeMillis() > attachEnd) {
                    error("timed out waiting for M3 fork to attach S3")
                }
                if (!resent && System.currentTimeMillis() > attachEnd - 9000) {
                    resent = true
                    onMain { vm.sendDirect("q-next") }
                }
                delay(50)
            }
            val s3 = vm.currentSessionId.value!!
            awaitTrue(timeoutMs = 15000) { vm.messages.value.any { it.text == "a-next" } }
            awaitTrue(timeoutMs = 10000) {
                rows(transcripts, s1).count { it.kind == "assistant" } == 1
            }
            val s1rows = rows(transcripts, s1)
            assertEquals(listOf("user", "assistant"), s1rows.map { it.kind })
            assertTrue(s1rows[0].text.startsWith("q-cancel"))
            assertEquals("half-cancelled", s1rows[1].text)
            assertTrue(s1rows[1].isPartial && s1rows[1].isFinal)

            // S3 holds the carried terminal (clean copy) or the degraded
            // incomplete marker — never a dangling family that would gain a
            // false INTERRUPTED on reopen.
            awaitTrue(timeoutMs = 10000) { rows(transcripts, s3).isNotEmpty() }
            val s3rows = rows(transcripts, s3)
            assertTrue(s3rows[0].kind == "system" && s3rows[0].text.startsWith("model switched "))
            if (s3rows.none { it.text.startsWith("snapshot incomplete ") }) {
                val copiedUser = s3rows.single { it.kind == "user" && it.text.startsWith("q-cancel") }
                assertEquals(s1rows[0].runId, copiedUser.runId)
                val copiedAsst = s3rows.single { it.kind == "assistant" && it.text == "half-cancelled" }
                assertEquals(s1rows[1].runId, copiedAsst.runId)
                assertTrue(copiedAsst.isPartial && copiedAsst.isFinal)
            } else {
                assertTrue(
                    "incomplete fallback must skip the dangling family",
                    s3rows.none { it.kind == "user" && it.text.startsWith("q-cancel") },
                )
            }
            assertTrue(findDanglingRunIds(s3rows).isEmpty())

            // Old terminal landed in S1 only (S3 holds copies under its own id).
            assertTrue(s1rows.all { it.sessionId == s1 })
            assertTrue(rows(transcripts, s3).all { it.sessionId == s3 })

            // Reopen S3 twice: zero new INTERRUPTED markers, no dangling.
            val before = interruptedMarkers(transcripts, s3).size
            val opened1 = factory.open(m3, s3)
            try {
                assertEquals(before, interruptedMarkers(transcripts, s3).size)
                assertTrue(findDanglingRunIds(rows(transcripts, s3)).isEmpty())
            } finally {
                opened1.session.close()
            }
            val opened2 = factory.open(m3, s3)
            try {
                assertEquals(before, interruptedMarkers(transcripts, s3).size)
                assertTrue(findDanglingRunIds(rows(transcripts, s3)).isEmpty())
            } finally {
                opened2.session.close()
            }
            assertNull(
                "reopen must not add a false INTERRUPTED for the carried family",
                rows(transcripts, s3).singleOrNull { it.text == "turn L interrupted" },
            )
        } finally {
            streamGate.complete(Unit)
            cleanupGate.complete(Unit)
        }
    }

    /**
     * R21-1 Phase 1 old session whose ONLY streaming evidence is the new
     * [ChatSession.hasUnsettledAdmission] seam: IDLE projection, no UNKNOWN
     * admission, no unsettled receipts, no unacked cores — but the direct
     * admission window is still open (user row durable, terminal missing),
     * so flushDurable is false via the new conjunct (production-consistent:
     * the controller conjoins the same seam).
     */
    private class UnsettledAdmissionOnlySession : ChatSession {
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
        override fun hasDurableFailures(): Boolean = false
        override suspend fun flushDurable(timeoutMs: Long): Boolean = false
        override fun hasPendingAdmission(): Boolean = false
        override fun hasUnsettledAdmission(): Boolean = true
        override fun pendingTranscriptRunIds(): List<String> = emptyList()
        override fun steer(text: String) = Unit
        override fun close() = Unit
    }

    private class CleanIdleSession : ChatSession {
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
        override fun hasDurableFailures(): Boolean = false
        override suspend fun flushDurable(timeoutMs: Long): Boolean = true
        override fun hasPendingAdmission(): Boolean = false
        override fun pendingTranscriptRunIds(): List<String> = emptyList()
        override fun steer(text: String) = Unit
        override fun close() = Unit
    }

    /**
     * R21-1 Phase 1 VM wiring pin: `isLedgerStreamingOrPending` must consult
     * [ChatSession.hasUnsettledAdmission]. The unsettled-only session trips
     * it with every other signal clean (isolates the new branch — without
     * the branch this returns false), while the fully clean session stays
     * false (no false positive from the new conjunct).
     *
     * Unit-semantics ONLY: [UnsettledAdmissionOnlySession] is a hand-written
     * fake whose `flushDurable == false` is hardcoded — it proves the VM
     * branch exists, never the production wiring (a fake `true` proves
     * nothing; see [ChatSession.flushDurable]). Production wiring is proven
     * by the real-controller tests below
     * ([switchWhileStreaming_realAdmissionBarrier_marksIncompleteAndSkipsDangling],
     * [realHostAlone_tripsLedgerStreamingEvidence_directWiring],
     * [realPromotionAwait_tripsLedgerStreamingEvidence_directWiring]), which
     * park a real [TurnController] behind a real [ChatSessionImpl].
     */
    @Test fun unsettledAdmissionAlone_tripsLedgerStreamingEvidence() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val vm = newVm(store, transcripts, AnsweringProvider())
        val method = ChatViewModel::class.java.getDeclaredMethod(
            "isLedgerStreamingOrPending",
            ChatSession::class.java,
        )
        method.isAccessible = true
        fun evidence(session: ChatSession): Boolean = method.invoke(vm, session) as Boolean
        assertTrue(
            "hasUnsettledAdmission alone must trip the streaming evidence",
            evidence(UnsettledAdmissionOnlySession()),
        )
        assertTrue(
            "fully clean session must not trip the streaming evidence",
            !evidence(CleanIdleSession()),
        )
    }

    /**
     * R21-1 Phase 1 VM fallback pin (mirrors
     * `switchWhileStreaming_timeoutFallback_marksIncompleteAndSkipsDangling`):
     * the old ledger's only live evidence is the new admission seam, so the
     * same-origin fork must still degrade — skip the dangling family, ride
     * the incomplete marker, and surface HISTORY_SNAPSHOT_INCOMPLETE.
     *
     * Unit-semantics ONLY (same fake caveat as
     * [unsettledAdmissionAlone_tripsLedgerStreamingEvidence]): the hardcoded
     * `flushDurable == false` cannot prove the production controller ever
     * reports this shape. The real-wiring counterpart is
     * [switchWhileStreaming_realAdmissionBarrier_marksIncompleteAndSkipsDangling].
     */
    @Test fun switchWhileStreaming_unsettledAdmissionOnly_marksIncompleteAndSkipsDangling() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))
        // Dangling live family: user L without any terminal (direct admission
        // still in its durable/finalize window).
        transcripts.appendEvent(event(oldId, "L", "user", "q-cancel"))

        val oldSession = UnsettledAdmissionOnlySession()
        val newSession = TimeoutNewSession()
        val sessions = TimeoutSessions(transcripts, oldId, oldSession, newSession)
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        // Attach the old ledger.
        onMain { vm.openSession(oldId) }
        awaitTrue { vm.currentSessionId.value == oldId }

        // Same-origin switch, then the fork-driving send.
        runBlocking { store.save(sampleEndpoint().copy(model = "gpt-4o")) }
        awaitTrue { vm.sessionState.value.messages.isEmpty() || vm.currentSessionId.value == oldId }
        onMain { vm.sendDirect("q-next") }
        awaitTrue(timeoutMs = 15000) { sessions.createdNewIds.isNotEmpty() }
        val s2 = sessions.createdNewIds.first()
        awaitTrue(timeoutMs = 10000) { newSession.sent.isNotEmpty() }

        // Fallback: incomplete marker, dangling L skipped, completed prefix kept.
        val s2rows = rows(transcripts, s2)
        assertTrue(s2rows[0].kind == "system" && s2rows[0].text.startsWith("model switched "))
        assertTrue(s2rows.any { it.text.startsWith("snapshot incomplete ") })
        assertEquals(listOf("q0", "a0"), s2rows.filter { it.kind != "system" }.map { it.text })
        assertTrue(s2rows.none { it.text == "q-cancel" })
        assertTrue(findDanglingRunIds(s2rows).isEmpty())
        assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)

        // Reopen twice: zero new INTERRUPTED markers, still no dangling.
        val reopenFactory = factoryWith(transcripts, AnsweringProvider())
        val before = interruptedMarkers(transcripts, s2).size
        val opened1 = reopenFactory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
        try {
            assertEquals(before, interruptedMarkers(transcripts, s2).size)
            assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
        } finally {
            opened1.session.close()
        }
        val opened2 = reopenFactory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
        try {
            assertEquals(before, interruptedMarkers(transcripts, s2).size)
            assertTrue(findDanglingRunIds(rows(transcripts, s2)).isEmpty())
        } finally {
            opened2.session.close()
        }
        assertNull(
            "reopen must not add a false INTERRUPTED for the skipped family",
            rows(transcripts, s2).singleOrNull { it.text == "turn L interrupted" },
        )
    }

    // -- R22 Phase 2: real-controller wiring (no hand-written session fakes) ---

    private fun controllerOf(impl: ChatSessionImpl): TurnController {
        val field = ChatSessionImpl::class.java.getDeclaredField("controller")
        field.isAccessible = true
        return field.get(impl) as TurnController
    }

    private fun vmEvidence(vm: ChatViewModel, method: String, session: ChatSession): Boolean {
        val m = ChatViewModel::class.java.getDeclaredMethod(method, ChatSession::class.java)
        m.isAccessible = true
        return m.invoke(vm, session) as Boolean
    }

    private fun realRows(store: FakeSessionStore, sid: String): List<TranscriptEvent> =
        synchronized(store.events) {
            ArrayList(store.events.filter { it.sessionId == sid }.sortedBy { it.seq })
        }

    private suspend fun awaitCond(timeoutMs: Long, what: String, cond: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!cond()) delay(15)
        }
    }

    /**
     * R22 Phase 2 end-to-end fork through a REAL [TurnController] behind a
     * real [ChatSessionImpl] (the VM's old session is production-built, never
     * a hand-written fake): the old turn parks on the finalize barrier
     * (`beforeAdmissionFinalizeForTest` — user row durable, no terminal yet),
     * then an optional barrier cancel lands, then a same-origin switch drives
     * the VM fork. The fork must go through the VM's own
     * `settleDetachedLedger → snapshotSwitchHistory(excludeDangling = true,
     * markIncomplete = true)` downgrade (this test never calls
     * `snapshotSwitchHistory` directly): skip the dangling family, ride the
     * incomplete marker, and surface HISTORY_SNAPSHOT_INCOMPLETE.
     *
     * Barrier shape (P1-1/P1-3) is pinned on the same real session AFTER the
     * VM settle sealed it: still-unsettled admission + sticky close debt with
     * seal held must read `isWriterSealedDead == false` and
     * `isWriterAliveEvidence == true`, so the expired-block promotion
     * (`tryPromoteExpiredBlock`, which short-circuits on the alive proof
     * first) keeps deferring — the block never promotes under a barrier.
     *
     * Deterministic: every park is a gate/latch (no wall-clock sleeps);
     * convergence waits are bounded `withTimeout + delay` polls.
     */
    private suspend fun realBarrierFork(cancelDuringBarrier: Boolean): Unit = supervisorScope {
        val store = newStore()
        store.save(sampleEndpoint())
        val transcripts = FakeSessionStore()
        val oldId = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        transcripts.appendEvent(event(oldId, "r0", "user", "q0"))
        transcripts.appendEvent(event(oldId, "r0", "assistant", "a0"))

        val ids = AtomicInteger(0)
        val oldSession = ChatSessionImpl(
            provider = AnsweringProvider(),
            policy = InMemoryPolicyStore(),
            transcript = SessionTranscriptSink(transcripts, oldId),
            dispatcher = Dispatchers.Default,
            newId = { "p2-real-${ids.incrementAndGet() - 1}" },
        )
        val controller = controllerOf(oldSession)
        val enteredBarrier = CountDownLatch(1)
        val barrierGate = CompletableDeferred<Unit>()
        controller.beforeAdmissionFinalizeForTest = {
            enteredBarrier.countDown()
            barrierGate.await()
        }
        try {
            val oldTurn = async(Dispatchers.Default) { oldSession.startOrEnqueue("q-cancel", opId = 901L) }
            check(enteredBarrier.await(5, TimeUnit.SECONDS)) {
                "old turn must park on the finalize barrier"
            }
            awaitCond(8_000, "barrier user row must be durable") {
                realRows(transcripts, oldId).any { it.kind == "user" && it.text == "q-cancel" }
            }
            assertTrue(
                "parked barrier must trip hasUnsettledAdmission",
                oldSession.hasUnsettledAdmission(),
            )

            val newSession = TimeoutNewSession()
            val sessions = TimeoutSessions(transcripts, oldId, oldSession, newSession)
            val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
            onMain { vm.openSession(oldId) }
            awaitCond(10_000, "VM must attach the old ledger") { vm.currentSessionId.value == oldId }

            // P1-1: the VM fence must observe the REAL admission seam.
            assertTrue(
                "VM must observe the real unsettled admission as streaming/pending evidence",
                vmEvidence(vm, "isLedgerStreamingOrPending", oldSession),
            )
            assertTrue(
                "pre-fork ledger must dangle, was ${realRows(transcripts, oldId).map { it.kind }}",
                findDanglingRunIds(realRows(transcripts, oldId)).isNotEmpty(),
            )

            if (cancelDuringBarrier) {
                oldSession.cancel()
                assertEquals(ChatStatus.CANCELLED, oldSession.uiState.value.status)
                assertTrue(
                    "cancelled barrier must still trip the guard",
                    oldSession.hasUnsettledAdmission(),
                )
            }

            // Same-origin switch; the fork-driving send goes through the VM.
            store.save(sampleEndpoint().copy(model = "gpt-4o"))
            awaitCond(10_000, "endpoint switch must invalidate the live view") {
                vm.sessionState.value.messages.isEmpty()
            }
            onMain { vm.sendDirect("q-next") }
            awaitCond(15_000, "VM fork must attach S2") { sessions.createdNewIds.isNotEmpty() }
            val s2 = sessions.createdNewIds.first()
            awaitCond(10_000, "forked turn must send") { newSession.sent.isNotEmpty() }

            // P1-3 barrier shape on the sealed real session: the VM settle
            // closed it (seal lands via the shutdown drain, which never waits
            // on the parked admission waiter), while the barrier still owns
            // the admission window plus the sticky close debt.
            awaitCond(8_000, "settled old writer must seal") { oldSession.isTranscriptSealed() }
            assertTrue(
                "sealed barrier writer must still trip the admission guard",
                oldSession.hasUnsettledAdmission(),
            )
            assertTrue(
                "sealed barrier writer must carry the sticky close debt",
                oldSession.hasClosedUnsettledAdmission(),
            )
            assertFalse(
                "sealed-but-obligated writer must not be dead",
                vmEvidence(vm, "isWriterSealedDead", oldSession),
            )
            assertTrue(
                "sealed-but-obligated writer must still read alive (block must not promote)",
                vmEvidence(vm, "isWriterAliveEvidence", oldSession),
            )
            assertTrue(
                "sticky close debt must trip the VM evidence directly",
                vmEvidence(vm, "isLedgerStreamingOrPending", oldSession),
            )
            val durableAfterSettle = withTimeout(8_000) { oldSession.flushDurable() }
            assertFalse(
                "barrier family without terminal must not flushDurable true",
                durableAfterSettle,
            )

            // Fork degraded through the VM: incomplete marker, dangling L
            // skipped, completed prefix kept.
            val s2rows = realRows(transcripts, s2)
            assertTrue(s2rows[0].kind == "system" && s2rows[0].text.startsWith("model switched "))
            assertTrue(s2rows.any { it.text.startsWith("snapshot incomplete ") })
            assertEquals(listOf("q0", "a0"), s2rows.filter { it.kind != "system" }.map { it.text })
            assertTrue(s2rows.none { it.text == "q-cancel" })
            assertTrue(findDanglingRunIds(s2rows).isEmpty())
            assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)

            // Reopen twice: zero new INTERRUPTED markers, still no dangling.
            val reopenFactory = factoryWith(transcripts, AnsweringProvider())
            val before = realRows(transcripts, s2).count {
                it.kind == "system" && it.text.endsWith("interrupted")
            }
            val opened1 = reopenFactory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
            try {
                assertEquals(before, realRows(transcripts, s2).count {
                    it.kind == "system" && it.text.endsWith("interrupted")
                })
                assertTrue(findDanglingRunIds(realRows(transcripts, s2)).isEmpty())
            } finally {
                opened1.session.close()
            }
            val opened2 = reopenFactory.open(sampleEndpoint().copy(model = "gpt-4o"), s2)
            try {
                assertEquals(before, realRows(transcripts, s2).count {
                    it.kind == "system" && it.text.endsWith("interrupted")
                })
                assertTrue(findDanglingRunIds(realRows(transcripts, s2)).isEmpty())
            } finally {
                opened2.session.close()
            }
            assertNull(
                "reopen must not add a false INTERRUPTED for the skipped family",
                realRows(transcripts, s2).singleOrNull { it.text == "turn L interrupted" },
            )

            // Release the barrier: the waiter observes the VM's close and
            // fails closed (rollback + TranscriptDurabilityException for retry).
            barrierGate.complete(Unit)
            try {
                withTimeout(5_000) { oldTurn.await() }
                fail("finalize-closed waiter must throw TranscriptDurabilityException")
            } catch (_: TranscriptDurabilityException) {
                // Expected fail-closed path (caller retains the text).
            }
            withTimeout(5_000) { oldSession.flush() }
        } finally {
            barrierGate.complete(Unit)
            runCatching { oldSession.close() }
        }
    }

    /**
     * R22 Phase 2 branch-level pins for every new obligation seam.
     *
     * Unit-semantics ONLY (same fake caveat as above): each [Phase2SeamSession]
     * trips exactly one seam with an IDLE projection and everything else
     * clean, so a `true` isolates the VM branch (without the new conjunct it
     * would read `false`). The sealed-shape negatives pin the expanded dead
     * proof conjunct-by-conjunct, and the sealed-clean positive control pins
     * that a truly settled writer still reads dead (the expansion must not
     * over-block the R10-2/R14-1 dead path). Production wiring for each seam
     * is proven by the real-controller tests above, not here.
     */
    private class Phase2SeamSession(
        val sealed: Boolean = false,
        val writerCompleted: Boolean = false,
        val unsettledAdmission: Boolean = false,
        val unsettledPromotion: Boolean = false,
        val activeHost: Boolean = false,
        val closedUnsettled: Boolean = false,
        val unsettledReceipts: Boolean = false,
        val sealedUncompensated: Boolean = false,
    ) : ChatSession {
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
        override fun hasDurableFailures(): Boolean = false
        override suspend fun flushDurable(timeoutMs: Long): Boolean = true
        override fun hasPendingAdmission(): Boolean = false
        override fun hasUnsettledAdmission(): Boolean = unsettledAdmission
        override fun hasUnsettledPromotion(): Boolean = unsettledPromotion
        override fun hasActiveHost(): Boolean = activeHost
        override fun hasClosedUnsettledAdmission(): Boolean = closedUnsettled
        override fun hasUnsettledCancelReceipts(): Boolean = unsettledReceipts
        override fun hasSealedUncompensatedReceipts(): Boolean = sealedUncompensated
        override fun pendingTranscriptRunIds(): List<String> = emptyList()
        override fun isTranscriptSealed(): Boolean = sealed
        override fun isTranscriptWriterCompleted(): Boolean = writerCompleted
        override fun steer(text: String) = Unit
        override fun close() = Unit
    }

    @Test fun phase2Seams_tripEvidenceAndBlockDead_unitPins() {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val transcripts = FakeSessionStore()
        val vm = newVm(store, transcripts, AnsweringProvider())
        fun evidence(session: ChatSession): Boolean = vmEvidence(vm, "isLedgerStreamingOrPending", session)
        fun dead(session: ChatSession): Boolean = vmEvidence(vm, "isWriterSealedDead", session)
        fun alive(session: ChatSession): Boolean = vmEvidence(vm, "isWriterAliveEvidence", session)

        // Direct wiring: each new seam alone trips the evidence with an IDLE
        // projection and every other signal clean.
        assertTrue(
            "hasUnsettledPromotion alone must trip the streaming evidence",
            evidence(Phase2SeamSession(unsettledPromotion = true)),
        )
        assertTrue(
            "hasActiveHost alone must trip the streaming evidence",
            evidence(Phase2SeamSession(activeHost = true)),
        )
        assertTrue(
            "hasClosedUnsettledAdmission alone must trip the streaming evidence",
            evidence(Phase2SeamSession(closedUnsettled = true)),
        )

        // Expanded dead proof: sealed + any single open obligation → not dead.
        assertFalse(
            "sealed + unsettled admission must not be dead",
            dead(Phase2SeamSession(sealed = true, writerCompleted = true, unsettledAdmission = true)),
        )
        assertFalse(
            "sealed + unsettled promotion must not be dead",
            dead(Phase2SeamSession(sealed = true, writerCompleted = true, unsettledPromotion = true)),
        )
        assertFalse(
            "sealed + active host must not be dead",
            dead(Phase2SeamSession(sealed = true, writerCompleted = true, activeHost = true)),
        )
        assertFalse(
            "sealed + sticky close debt must not be dead",
            dead(Phase2SeamSession(sealed = true, writerCompleted = true, closedUnsettled = true)),
        )
        assertFalse(
            "sealed + unsettled receipts must not be dead",
            dead(Phase2SeamSession(sealed = true, writerCompleted = true, unsettledReceipts = true)),
        )
        assertFalse(
            "sealed + uncompensated receipts must not be dead",
            dead(Phase2SeamSession(sealed = true, writerCompleted = true, sealedUncompensated = true)),
        )

        // Positive control: sealed + fully clean + completed still reads dead
        // (the expansion must not over-block the R10-2/R14-1 dead path), and
        // therefore not alive.
        val settled = Phase2SeamSession(sealed = true, writerCompleted = true)
        assertTrue("sealed-clean-completed writer must still be dead", dead(settled))
        assertFalse("sealed-clean-completed writer must not read alive", alive(settled))
        assertFalse(
            "sealed-clean-completed writer must not trip the evidence",
            evidence(settled),
        )

        // Barrier shape reads alive (so the expired block keeps deferring).
        val barrier = Phase2SeamSession(sealed = true, writerCompleted = true, unsettledAdmission = true)
        assertTrue("sealed-but-obligated writer must read alive", alive(barrier))
        assertTrue("sealed-but-obligated writer must trip the evidence", evidence(barrier))
    }

    @Test fun switchWhileStreaming_realAdmissionBarrier_marksIncompleteAndSkipsDangling() = runBlocking {
        realBarrierFork(cancelDuringBarrier = false)
    }

    @Test fun switchWhileStreaming_realCancelDuringBarrier_marksIncompleteAndSkipsDangling() = runBlocking {
        realBarrierFork(cancelDuringBarrier = true)
    }

    /**
     * R22 Phase 2 direct wiring pin for [ChatSession.hasActiveHost]: a REAL
     * hosted turn (provider-gated, behind a real [ChatSessionImpl]) must trip
     * the VM evidence while the admission guard stays clear (no false
     * positive from the admission branch), and must not read dead while
     * unsealed.
     */
    @Test fun realHostAlone_tripsLedgerStreamingEvidence_directWiring() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val providerGate = CompletableDeferred<Unit>()
        val enteredProvider = CountDownLatch(1)
        val provider = object : LlmProvider {
            override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
            override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
                enteredProvider.countDown()
                providerGate.await()
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }

            override suspend fun listModels(): List<String> = emptyList()
        }
        val ids = AtomicInteger(0)
        val session = ChatSessionImpl(
            provider = provider,
            policy = InMemoryPolicyStore(),
            transcript = SessionTranscriptSink(transcripts, sid),
            dispatcher = Dispatchers.Default,
            newId = { "p2-host-${ids.incrementAndGet() - 1}" },
        )
        val store = newStore()
        store.save(sampleEndpoint())
        val vm = newVm(store, transcripts, AnsweringProvider())
        try {
            supervisorScope {
                val started = withTimeout(8_000) { session.startOrEnqueue("p2-host-q", opId = 902L) }
                assertTrue("host admission must start, was $started", started is TurnStart.Started)
                check(enteredProvider.await(5, TimeUnit.SECONDS)) { "host must park on the provider gate" }
                awaitCond(8_000, "host entry must clear the admission guard") {
                    !session.hasUnsettledAdmission()
                }
                assertTrue("running host must trip hasActiveHost", session.hasActiveHost())
                assertFalse(
                    "running host must not trip the admission guard",
                    session.hasUnsettledAdmission(),
                )
                assertTrue(
                    "VM must observe the real live host directly",
                    vmEvidence(vm, "isLedgerStreamingOrPending", session),
                )
                assertFalse(
                    "running (unsealed) host must not be dead",
                    vmEvidence(vm, "isWriterSealedDead", session),
                )
                assertTrue(
                    "running host must read alive",
                    vmEvidence(vm, "isWriterAliveEvidence", session),
                )
                providerGate.complete(Unit)
                withTimeout(8_000) { (started as TurnStart.Started).host.join() }
                withTimeout(8_000) { session.flush() }
                awaitCond(8_000, "host must clear") { !session.hasActiveHost() }
                assertTrue(
                    "settled turn must flushDurable true",
                    withTimeout(8_000) { session.flushDurable() },
                )
            }
        } finally {
            providerGate.complete(Unit)
            runCatching { session.close() }
        }
    }

    /**
     * R22 Phase 2 direct wiring pin for [ChatSession.hasUnsettledPromotion]:
     * a REAL promotion await (`beforePromotionAwaitForTest` park behind a
     * real [ChatSessionImpl]) must trip the VM evidence explicitly — never
     * only via the admission branch — and must not read dead.
     */
    @Test fun realPromotionAwait_tripsLedgerStreamingEvidence_directWiring() = runBlocking {
        val transcripts = FakeSessionStore()
        val sid = transcripts.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val firstGate = CompletableDeferred<Unit>()
        val firstEntered = CountDownLatch(1)
        val promoParked = CountDownLatch(1)
        val releasePromo = CountDownLatch(1)
        val provider = object : LlmProvider {
            override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
            override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
                val last = request.messages.last { it.role == "user" }.text
                if (last.contains("p2-promofirst")) {
                    firstEntered.countDown()
                    firstGate.await()
                }
                emit(StreamEvent.Done("stop"))
            }

            override suspend fun listModels(): List<String> = emptyList()
        }
        val ids = AtomicInteger(0)
        val session = ChatSessionImpl(
            provider = provider,
            policy = InMemoryPolicyStore(),
            transcript = SessionTranscriptSink(transcripts, sid),
            dispatcher = Dispatchers.Default,
            newId = { "p2-promo-${ids.incrementAndGet() - 1}" },
        )
        controllerOf(session).beforePromotionAwaitForTest = {
            promoParked.countDown()
            check(releasePromo.await(30, TimeUnit.SECONDS)) { "promotion gate released on teardown" }
        }
        val store = newStore()
        store.save(sampleEndpoint())
        val vm = newVm(store, transcripts, AnsweringProvider())
        try {
            supervisorScope {
                val started = withTimeout(8_000) { session.startOrEnqueue("p2-promofirst", opId = 903L) }
                assertTrue("first admission must start, was $started", started is TurnStart.Started)
                check(firstEntered.await(5, TimeUnit.SECONDS)) { "first provider call must park" }
                assertEquals(
                    TurnStart.Queued,
                    withTimeout(8_000) { session.startOrEnqueue("p2-promofollow", opId = 904L) },
                )
                firstGate.complete(Unit)
                check(promoParked.await(5, TimeUnit.SECONDS)) { "promotion must park on its durable await" }
                assertTrue(
                    "promotion await must trip hasUnsettledPromotion",
                    session.hasUnsettledPromotion(),
                )
                assertTrue(
                    "VM must observe the real promotion reservation directly",
                    vmEvidence(vm, "isLedgerStreamingOrPending", session),
                )
                assertFalse(
                    "parked promotion must not be dead",
                    vmEvidence(vm, "isWriterSealedDead", session),
                )
                assertTrue(
                    "parked promotion must read alive",
                    vmEvidence(vm, "isWriterAliveEvidence", session),
                )
                releasePromo.countDown()
                withTimeout(8_000) { (started as TurnStart.Started).host.join() }
                awaitCond(8_000, "promotion must settle") {
                    !session.hasUnsettledPromotion() && !session.hasActiveHost()
                }
                withTimeout(8_000) { session.flush() }
                assertTrue(
                    "settled promotion family must flushDurable true",
                    withTimeout(8_000) { session.flushDurable() },
                )
            }
        } finally {
            releasePromo.countDown()
            firstGate.complete(Unit)
            runCatching { session.close() }
        }
    }
}
