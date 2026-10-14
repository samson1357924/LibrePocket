package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.TranscriptPendingException
import dev.librepocket.chat.TurnStart
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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

/**
 * R23-1: permanent sticky debt is split from the writer-liveness proof,
 * end-to-end through the REAL [ChatViewModel] same-ID reopen path with REAL
 * sessions (production [ChatSessionFactory] → real [dev.librepocket.chat.ChatSessionImpl]
 * → real TurnController → real OrderedTranscriptSink over a [FakeSessionStore]).
 *
 * - Ghost group: a post-commit UNKNOWN ghost (admission timed out after send;
 *   the user row commits late via the independent writer) is dropped by close
 *   into the permanent sticky tier. The first same-ID reopen must defer
 *   (`backfill=false`, HISTORY_SNAPSHOT_INCOMPLETE); while the old writer is
 *   still inside its blocking store block the expired block must keep
 *   deferring (negative control: no early backfill before writer completion);
 *   once the writer completes, the next expiry promotes exactly one
 *   `turn <family> interrupted` even though the sticky debt never lifts, and a
 *   further reopen adds zero duplicates while the block lifts.
 * - Sealed group: a non-final SEALED-uncompensated receipt (cancel lands
 *   mid-stream with no terminal; the close seal starves the waiter) redoes
 *   the same defer → promote-single → no-dup shape.
 *
 * Every assert reads the values the VM actually passed to
 * `Factory.open(..., backfillInterrupted=...)` (recorded by
 * [RecordingFactorySessions]) plus the ledger — never a direct factory open
 * or a manual `snapshotSwitchHistory`. Every park is a gate/latch; convergence
 * waits are bounded `withTimeout + delay` polls, never fixed sleeps.
 */
class ChatSameIdReopenStickyDebtTest {

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
        val dir = Files.createTempDirectory("r23-sticky-debt").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r23.preferences_pb") },
            ),
        )
    }

    private fun factoryWith(
        transcripts: SessionStore,
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

    private suspend fun awaitCond(timeoutMs: Long, what: String, cond: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!cond()) delay(15)
        }
    }

    private fun driveScope(): CoroutineScope {
        // Drives a real session directly (startTurn/startOrEnqueue) outside
        // the test's runBlocking: a turn that ends in TranscriptPendingException
        // (the ghost UNKNOWN) must stay contained in this scope — as a plain
        // runBlocking child it would cancel the test scope itself. Owned +
        // joined by the shared teardown list.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopes.add(scope)
        return scope
    }

    private fun rows(store: FakeSessionStore, sid: String): List<TranscriptEvent> =
        synchronized(store.events) {
            ArrayList(store.events.filter { it.sessionId == sid }.sortedBy { it.seq })
        }

    private fun interruptedMarkers(store: FakeSessionStore, sid: String) =
        rows(store, sid).filter { it.kind == "system" && it.text.endsWith("interrupted") }

    @Suppress("UNCHECKED_CAST")
    private fun blockSize(vm: ChatViewModel): Int {
        val field = ChatViewModel::class.java.getDeclaredField("pendingBackfillBlock")
        field.isAccessible = true
        var out = -1
        runBlocking { chatMain.run { out = (field.get(vm) as HashMap<String, Long>).size } }
        return out
    }

    private fun vmEvidence(vm: ChatViewModel, method: String, session: ChatSession): Boolean {
        val m = ChatViewModel::class.java.getDeclaredMethod(method, ChatSession::class.java)
        m.isAccessible = true
        return m.invoke(vm, session) as Boolean
    }

    /**
     * Provider wrapper: delegates every open/create to the REAL factory and
     * records the exact `backfillInterrupted` value the VM passed, plus every
     * session the factory built (so the test can drive the live old writer).
     */
    private class RecordingFactorySessions(
        val inner: ChatSessionFactory,
    ) : ChatSessionProvider {
        val backfillFlags = CopyOnWriteArrayList<Boolean>()
        val attached = CopyOnWriteArrayList<ChatSession>()
        override fun modelFor(endpoint: EndpointConfig): String = inner.modelFor(endpoint)
        override suspend fun storeOrNull(): SessionStore? = inner.storeOrNull()
        // D2: canonical create is the single source of truth (lossless into
        // the real factory); the 3-arg shim rides the interface default here.
        override suspend fun create(
            endpoint: EndpointConfig,
            title: String,
            initialHistory: List<dev.librepocket.provider.ChatMessage>,
            keyIsCurrent: suspend () -> Boolean,
        ): CreatedSession =
            inner.create(endpoint, title, initialHistory, keyIsCurrent).also { attached.add(it.session) }

        override suspend fun open(
            endpoint: EndpointConfig,
            sessionId: String,
            keyIsCurrent: suspend () -> Boolean,
        ): CreatedSession =
            inner.open(endpoint, sessionId, keyIsCurrent).also { attached.add(it.session) }

        override suspend fun open(
            endpoint: EndpointConfig,
            sessionId: String,
            keyIsCurrent: suspend () -> Boolean,
            backfillInterrupted: Boolean,
        ): CreatedSession {
            backfillFlags.add(backfillInterrupted)
            return inner.open(endpoint, sessionId, keyIsCurrent, backfillInterrupted)
                .also { attached.add(it.session) }
        }
    }

    /**
     * Store gate for the ghost group: parks EVERY appendEvent inside
     * NonCancellable until released, so the session writer can sit inside its
     * blocking store block across the close seal (the exact R14-1 shape: seal
     * + empty pending WITHOUT writer completion) without the teardown cancel
     * killing the parked block. The late release lets the ghost user row
     * commit post-seal via the independent writer.
     */
    private class GhostParkingStore(
        val inner: FakeSessionStore,
    ) : SessionStore by inner {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        @Volatile var released = false
        private suspend fun park() {
            entered.countDown()
            if (!released) withContext(NonCancellable) { release.await() }
        }
        override suspend fun appendEvent(event: TranscriptEvent): Long {
            park()
            return inner.appendEvent(event)
        }
        override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
            if (events.isNotEmpty()) park()
            return inner.appendEvents(events)
        }
    }

    /** Answering provider that records every stream call (ghost group: must stay empty). */
    private class RecordingAnswerProvider : LlmProvider {
        override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
        val seen = CopyOnWriteArrayList<String>()
        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            seen.add(request.messages.last { it.role == "user" }.text)
            emit(StreamEvent.TextDelta(0, 0, "a-ghost"))
            emit(StreamEvent.Done("stop"))
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    /**
     * Store gate for the sealed group (R192手法): parks only `assistant`
     * appends (the cancel-pending terminal), so the waiter stays PENDING
     * through the close seal and degrades to SEALED. Plain cancellable park
     * (contrast the ghost NonCancellable park): the close teardown dooms the
     * parked write — writer completion below is via cancel-death teardown,
     * never a post-seal commit. The test release only unparks a still-gated
     * delegate so the teardown finishes deterministically.
     */
    private class AssistantParkingStore(
        val inner: FakeSessionStore,
    ) : SessionStore by inner {
        val entered = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        private suspend fun parkIfAssistant(events: List<TranscriptEvent>) {
            if (events.any { it.kind == "assistant" }) {
                entered.countDown()
                gate.await()
            }
        }
        override suspend fun appendEvent(event: TranscriptEvent): Long {
            parkIfAssistant(listOf(event))
            return inner.appendEvent(event)
        }
        override suspend fun appendEvents(events: List<TranscriptEvent>): List<Long> {
            if (events.isNotEmpty()) parkIfAssistant(events)
            return inner.appendEvents(events)
        }
    }

    /** Mid-stream retryable failure: partial + long backoff, so the waiter stays PENDING. */
    private class SealedPartialProvider : LlmProvider {
        override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextDelta(0, 0, "half-sealed"))
            emit(StreamEvent.Failed("boom", retryable = true))
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    /**
     * Ghost group: post-commit UNKNOWN ghost + permanent sticky close debt.
     *
     * - #1 attaches the real session; its admission times out after send
     *   (writer parked in the store gate) → UNKNOWN ownership, no host.
     * - #2 (same ID) closes it (permanent sticky), seals, defers + parks.
     * - #3 (past horizon, writer still in its store block) must still defer:
     *   seal + empty pending WITHOUT completion is not dead (negative control).
     * - Release → the ghost user row commits post-seal; the drain-timeout mark
     *   clears (no durable failure left) while the sticky debt stays.
     * - #4 promotes exactly one INTERRUPTED via the dead proof despite the
     *   sticky debt; #5 adds zero duplicates and the block lifts.
     */
    @Test fun ghostStickyDebt_defersThenPromotesSingleInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val inner = FakeSessionStore()
        val oldId = inner.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val gate = GhostParkingStore(inner)
        val provider = RecordingAnswerProvider()
        val sessions = RecordingFactorySessions(factoryWith(gate, provider))
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 10_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }
        try {
            // #1 attach: empty ledger, backfill trivially true.
            onMain { vm.openSession(oldId) }
            awaitCond(10_000, "VM must attach the old ledger") { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true), sessions.backfillFlags.toList())
            val s1 = sessions.attached.single()
            assertTrue(rows(inner, oldId).isEmpty())

            // Admission after send, writer parked: accept-timeout → UNKNOWN.
            // No host may start (the ghost is never claimed while we hold it).
            // Driven on a detached supervisor scope: the UNKNOWN failure must
            // not cancel the test's own runBlocking scope.
            val drive = driveScope()
            val ghost = drive.async { s1.startTurn("q-ghost") }
            check(gate.entered.await(5, TimeUnit.SECONDS)) { "writer must take the ghost entry" }
            try {
                withTimeout(20_000) { ghost.await() }
                fail("parked admission must time out to UNKNOWN")
            } catch (_: TranscriptPendingException) {
                // Expected: timeout-after-send keeps UNKNOWN ownership.
            }
            assertTrue("ghost must own the slot", s1.hasPendingAdmission())
            assertTrue(
                "user row must not commit while the store block is held",
                rows(inner, oldId).none { it.kind == "user" },
            )

            // #2 first same-ID reopen: close drops the UNKNOWN into the
            // permanent sticky tier; teardown seals; flushDurable stays false
            // → defer + park (safety control: backfill=false).
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "second open must record its backfill flag") {
                sessions.backfillFlags.size >= 2
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true, false), sessions.backfillFlags.toList())
            assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
            assertTrue(interruptedMarkers(inner, oldId).isEmpty())
            assertEquals(1, blockSize(vm))
            assertTrue(
                "close must keep the permanent UNKNOWN-ghost debt",
                s1.hasClosedUnsettledAdmission(),
            )
            awaitCond(20_000, "teardown must seal the old writer") { s1.isTranscriptSealed() }
            assertTrue(
                "drain-timeout mark must have cleared pending",
                s1.pendingTranscriptRunIds().isEmpty(),
            )
            assertFalse(
                "writer still inside the blocking store block must not read completed",
                s1.isTranscriptWriterCompleted(),
            )
            // R23-1 split on the REAL session: sticky debt still trips the
            // evidence and still blocks flushDurable. R14-1: seal + empty
            // pending while the writer is still inside its store block is NOT
            // dead yet (its COMMIT may still land) — the dead proof must stay
            // false here so the expiry below defers.
            assertTrue(vmEvidence(vm, "isLedgerStreamingOrPending", s1))
            assertFalse("sticky debt must keep flushDurable false", withTimeout(10_000) { s1.flushDurable() })
            assertFalse(
                "writer inside its store block must not read dead even with only sticky debt",
                vmEvidence(vm, "isWriterSealedDead", s1),
            )
            assertTrue(
                "incomplete sticky writer must still read alive (block must not promote)",
                vmEvidence(vm, "isWriterAliveEvidence", s1),
            )

            // #3 negative control: past the horizon while the writer is still
            // inside its store block (COMMIT may still land) → must renew +
            // defer, never promote on the clock alone.
            fakeNow += 600L
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "third open must record its backfill flag") {
                sessions.backfillFlags.size >= 3
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(
                "expiry while the writer may still land its terminal must renew, not promote",
                listOf(true, false, false),
                sessions.backfillFlags.toList(),
            )
            assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
            assertTrue(
                "early backfill would contradict the late commit",
                interruptedMarkers(inner, oldId).isEmpty(),
            )
            assertEquals(1, blockSize(vm))
            assertFalse(
                "negative control needs the writer provably incomplete",
                s1.isTranscriptWriterCompleted(),
            )

            // Release the store block: the ghost user row commits post-seal
            // via the independent writer. The drain-timeout mark must clear
            // (the row IS durable now) while the permanent sticky debt stays.
            gate.released = true
            gate.release.complete(Unit)
            awaitCond(20_000, "released writer must complete") { s1.isTranscriptWriterCompleted() }
            assertFalse(
                "late commit must clear the drain-timeout mark",
                s1.hasDurableFailures(),
            )
            assertTrue(
                "ghost user row must have committed",
                rows(inner, oldId).any { it.kind == "user" },
            )
            assertTrue(
                "ghost must never have been claimed (no host, no provider call)",
                provider.seen.isEmpty(),
            )
            val dangling = findDanglingRunIds(rows(inner, oldId))
            assertEquals("exactly the ghost family must dangle", 1, dangling.size)
            assertTrue(
                "permanent sticky debt survives the commit (only backfill lifts it)",
                s1.hasClosedUnsettledAdmission(),
            )
            // R23-1 split pin on the REAL session: sealed + pending-empty +
            // completed with no live obligation reads dead even while the
            // permanent sticky debt holds — so the expiry below may promote —
            // while the evidence still trips and flushDurable stays false.
            assertTrue(vmEvidence(vm, "isLedgerStreamingOrPending", s1))
            assertFalse("sticky debt must keep flushDurable false", withTimeout(10_000) { s1.flushDurable() })
            assertTrue(
                "completed writer with only sticky debt must read dead",
                vmEvidence(vm, "isWriterSealedDead", s1),
            )
            assertFalse(
                "sticky-only dead writer must not read alive",
                vmEvidence(vm, "isWriterAliveEvidence", s1),
            )

            // #4 expiry with the dead proof: promotes exactly one INTERRUPTED
            // for the ghost family despite the sticky debt, and lifts the block.
            fakeNow += 600L
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "fourth open must record its backfill flag") {
                sessions.backfillFlags.size >= 4
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(
                listOf(true, false, false, true),
                sessions.backfillFlags.toList(),
            )
            val marks = interruptedMarkers(inner, oldId)
            assertEquals("dead-writer expiry must mark the ghost family exactly once", 1, marks.size)
            assertEquals("turn ${dangling.single()} interrupted", marks.single().text)
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertNull(
                "promoted reopen resolves the dead ledger with no stale warning",
                vm.notice.value,
            )
            assertEquals(0, blockSize(vm))
            // Promoted new session: ledger clean so flushDurable must be true
            // with no dangling (fresh sink owns no sticky debt; the old s1
            // keeps it). Room parity boundary: FakeSessionStore here, no Room
            // schema change — JSONL round-trip / UI replay parity is pinned by
            // LateFailureCompensationTest, not re-pinned here.
            val sGhostNew = sessions.attached.last()
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertTrue(
                "promoted new session must flushDurable true",
                withTimeout(10_000) { sGhostNew.flushDurable() },
            )

            // #5: ledger clean → backfill again, zero new markers.
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "fifth open must record its backfill flag") {
                sessions.backfillFlags.size >= 5
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(
                listOf(true, false, false, true, true),
                sessions.backfillFlags.toList(),
            )
            assertEquals(
                "clean reopen must not duplicate the marker",
                1,
                interruptedMarkers(inner, oldId).size,
            )
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertEquals(0, blockSize(vm))
        } finally {
            gate.released = true
            gate.release.complete(Unit)
            for (s in sessions.attached) runCatching { s.close() }
        }
    }

    /**
     * Sealed group: a non-final SEALED-uncompensated receipt redoes the same
     * defer → promote-single → no-dup shape through the VM.
     *
     * Cancel lands mid-stream (partial emitted, retryable failure pending, no
     * terminal yet — the notice-first non-final path); the parked assistant
     * write starves the waiter through the close seal → SEALED without proven
     * family-final. The first same-ID reopen defers; expiry promotes exactly
     * one INTERRUPTED via the dead proof (sticky receipt + liftable close
     * debt ignored); the next reopen adds zero.
     */
    @Test fun sealedReceiptDebt_defersThenPromotesSingleInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val inner = FakeSessionStore()
        val parking = AssistantParkingStore(inner)
        val oldId = inner.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val provider = SealedPartialProvider()
        val sessions = RecordingFactorySessions(factoryWith(parking, provider))
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 20_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }
        // The parked writer must never survive the test: the finally below
        // releases the gate and closes every built session.
        try {
            // #1 attach the real session and stream a turn with a partial.
            onMain { vm.openSession(oldId) }
            awaitCond(10_000, "VM must attach the old ledger") { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true), sessions.backfillFlags.toList())
            val s1 = sessions.attached.single()
            val drive = driveScope()
            val admission = drive.async { s1.startOrEnqueue("q-sealed") }
            val started = withTimeout(10_000) { admission.await() }
            assertTrue("admission must start, was $started", started is TurnStart.Started)
            check(parking.entered.await(5, TimeUnit.SECONDS)) { "terminal must park on the assistant gate" }
            awaitCond(10_000, "partial must be visible") {
                s1.uiState.value.messages.any { it.text == "half-sealed" }
            }

            // Cancel mid-stream with no terminal: receipt PENDING (terminal
            // still owed by the parked writer).
            s1.cancel()
            awaitCond(15_000, "receipt must be unsettled after cancel") {
                s1.hasUnsettledCancelReceipts()
            }

            // #2 first same-ID reopen: close seals while the terminal is still
            // parked → waiter degrades to SEALED → defer + park.
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "second open must record its backfill flag") {
                sessions.backfillFlags.size >= 2
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true, false), sessions.backfillFlags.toList())
            assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
            assertTrue(interruptedMarkers(inner, oldId).isEmpty())
            assertEquals(1, blockSize(vm))
            awaitCond(20_000, "waiter must degrade to SEALED after the seal") {
                s1.hasSealedUncompensatedReceipts()
            }
            assertFalse(
                "SEALED receipt must keep flushDurable false",
                withTimeout(10_000) { s1.flushDurable() },
            )
            // R23-1 split on the REAL session: the SEALED receipt (plus the
            // liftable close debt, which can never lift past it) still trips
            // the evidence here. The dead proof additionally requires writer
            // completion (R14-1), so its pins live after the bounded release
            // awaits below — never before completion (cancellable park: close
            // leaves the writer still gated, isWriterCompleted=false).
            assertTrue(vmEvidence(vm, "isLedgerStreamingOrPending", s1))

            // Cancellable-park cancel-death release: the close seal already
            // doomed the parked assistant write (plain gate.await(), unlike
            // the ghost NonCancellable park which commits post-seal). The
            // release below only unparks a still-gated delegate so the writer
            // can finish its teardown deterministically — it never resurrects
            // a commit past the seal. The family still dangles (no proven
            // final row, no system repair) and SEALED stays visible.
            parking.gate.complete(Unit)
            awaitCond(20_000, "released writer must complete") { s1.isTranscriptWriterCompleted() }
            awaitCond(10_000, "doomed host must settle") { !s1.hasActiveHost() }
            // Dead/alive pins AFTER completion (bounded above, no main-thread
            // delay inference): sealed + pending-empty + completed with only
            // sticky debt reads dead, never alive.
            assertTrue(vmEvidence(vm, "isLedgerStreamingOrPending", s1))
            assertTrue(
                "sealed + pending-empty writer with only sticky debt must read dead",
                vmEvidence(vm, "isWriterSealedDead", s1),
            )
            assertFalse(
                "sticky-only dead writer must not read alive",
                vmEvidence(vm, "isWriterAliveEvidence", s1),
            )
            assertTrue(
                "SEALED without proven final must stay visible",
                s1.hasSealedUncompensatedReceipts(),
            )
            val dangling = findDanglingRunIds(rows(inner, oldId))
            assertEquals("exactly the cancelled family must dangle", 1, dangling.size)

            // #3 expiry with the dead proof: promotes exactly one INTERRUPTED
            // despite the sticky receipt, and lifts the block.
            fakeNow += 600L
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "third open must record its backfill flag") {
                sessions.backfillFlags.size >= 3
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(
                listOf(true, false, true),
                sessions.backfillFlags.toList(),
            )
            val marks = interruptedMarkers(inner, oldId)
            assertEquals("dead-writer expiry must mark the family exactly once", 1, marks.size)
            assertEquals("turn ${dangling.single()} interrupted", marks.single().text)
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertNull(
                "promoted reopen resolves the dead ledger with no stale warning",
                vm.notice.value,
            )
            assertEquals(0, blockSize(vm))
            // Promoted new session: ledger clean so flushDurable must be true
            // with no dangling (fresh sink owns no sticky debt; the old s1
            // keeps it). Same Room parity boundary as the ghost group above.
            val sSealedNew = sessions.attached.last()
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertTrue(
                "promoted new session must flushDurable true",
                withTimeout(10_000) { sSealedNew.flushDurable() },
            )

            // #4: ledger clean → backfill again, zero new markers.
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "fourth open must record its backfill flag") {
                sessions.backfillFlags.size >= 4
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(
                listOf(true, false, true, true),
                sessions.backfillFlags.toList(),
            )
            assertEquals(
                "clean reopen must not duplicate the marker",
                1,
                interruptedMarkers(inner, oldId).size,
            )
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertEquals(0, blockSize(vm))
        } finally {
            parking.gate.complete(Unit)
            for (s in sessions.attached) runCatching { s.close() }
        }
    }
}
