package dev.librepocket.agent.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatImageRef
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatUiState
import dev.librepocket.chat.QueuedIntent
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.chat.TurnController
import dev.librepocket.chat.TurnRetryConfig
import dev.librepocket.chat.TurnStart
import dev.librepocket.keystore.EncryptedPrefsVault
import dev.librepocket.keystore.InMemoryPrefs
import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R24-1 P1: a cancel-receipt waiter cancelled before its first dispatch must
 * still converge (never stuck PENDING).
 *
 * The waiter's PENDING/CLAIMED→SEALED fallbacks all live inside the coroutine
 * body; a job enqueued on the writer dispatcher but never started (parent
 * scope torn down before first dispatch, or launched into an already
 * cancelled scope) completes without running a single instruction, so the
 * receipt stayed PENDING forever — fail-closed [flushDurable], a never-dead
 * writer ([isWriterSealedDead] false via [hasUnsettledCancelReceipts]), and a
 * permanently renewed backfill block. The [TurnController] completion
 * fail-safe (monotonic PENDING/CLAIMED→SEALED, no transcript write) closes
 * exactly this gap; the R14-1 dead-proof fence (PENDING/CLAIMED guard kept)
 * is untouched.
 *
 * Shape under test, end-to-end through the REAL [ChatViewModel] same-ID
 * reopen path with a REAL TurnController (gated writer dispatcher) behind a
 * [ChatSession] delegate:
 * - terminal SUBMITTING (assistant write parked in the delegate store block)
 *   + host cancel → receipt PENDING + waiter launched + registered;
 * - writer dispatcher held → waiter queued, body provably never started
 *   ([beforeCancelReceiptWaiterBodyForTest] seam stays cold);
 * - waiter job cancelled before first execution (production-faithful
 *   equivalent of a parent teardown cancel landing pre-dispatch: a child
 *   cancelled before start behaves identically either way) → receipt must be
 *   SEALED (never PENDING), body still cold;
 * - close exhausts its budget against the parked writer (seal + writer join
 *   bounded), flushDurable stays false (old session, forever);
 * - writer completed → [isWriterSealedDead] true despite the sticky SEALED
 *   receipt;
 * - fake monotonic clock drives reopen: first same-ID reopen defers, expiry
 *   while the writer is still inside its delegate store block keeps
 *   deferring (negative control — never promote on the clock alone), release
 *   → expiry promotes exactly one INTERRUPTED, next reopen adds zero.
 *
 * The existing [CancelReceiptCloseBarrierTest] waiter-enter seam only proves
 * launch+registration (never first execution) and the StickyDebt sealed group
 * only covers a body-executed SEALED — neither covers pre-dispatch
 * cancellation. Every park is a gate/latch; convergence waits are bounded
 * `withTimeout + delay` polls, never fixed sleeps.
 */
class CancelReceiptPreDispatchTest {

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
        val dir = Files.createTempDirectory("r241-predispatch").toFile()
        tmpDirs.add(dir)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes.add(scope)
        return EndpointStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "r241.preferences_pb") },
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

    /** Writer dispatcher held by the test: queues while paused, forwards after release. */
    private class GateDispatcher : CoroutineDispatcher() {
        private val tasks = Collections.synchronizedList(ArrayList<Runnable>())
        @Volatile var paused = false
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (paused) {
                tasks.add(block)
            } else {
                Dispatchers.Default.dispatch(context, block)
            }
        }
        fun resumeAll() {
            paused = false
            val queued = synchronized(tasks) {
                val copy = ArrayList(tasks)
                tasks.clear()
                copy
            }
            for (task in queued) {
                Dispatchers.Default.dispatch(kotlin.coroutines.EmptyCoroutineContext, task)
            }
        }
    }

    /**
     * Delegate store gate: parks only `assistant` appends (the cancel-pending
     * terminal) inside NonCancellable until released, so the session writer
     * can sit inside its blocking store block across the close seal (the
     * exact R14-1 shape: sealed + pending-empty WITHOUT writer completion)
     * without the teardown cancel killing the parked block. Non-assistant
     * rows (user, notice) never park, so admission stays durable and the
     * cancel installs its receipt while SUBMITTING. The late release lets
     * the owed terminal commit via the independent writer.
     */
    private class ParkingStore(
        val inner: FakeSessionStore,
    ) : SessionStore by inner {
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        @Volatile var released = false
        private suspend fun parkIfAssistant(events: List<TranscriptEvent>) {
            if (events.any { it.kind == "assistant" }) {
                entered.countDown()
                if (!released) withContext(NonCancellable) { release.await() }
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

    /** Mid-stream retryable failure: partial + retry, so the waiter stays PENDING. */
    private class PreDispatchProvider : LlmProvider {
        override val protocol: ProviderProtocol = ProviderProtocol.CHAT_COMPLETIONS
        override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextDelta(0, 0, "half-predispatch"))
            emit(StreamEvent.Failed("boom", retryable = true))
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    private class AllowAllPolicy : PolicyStore {
        override fun evaluate(action: String, resource: String) =
            PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
        override suspend fun setRule(rule: PolicyRule) = Unit
        override suspend fun removeRule(pattern: String) = Unit
        override suspend fun listRules(): List<PolicyRule> = emptyList()
        override suspend fun evaluateFresh(action: String, resource: String) =
            PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    }

    /**
     * Thin [ChatSession] delegate over a test-built [TurnController], so the
     * pre-dispatch session (gated writer dispatcher) can ride the REAL
     * ViewModel reopen flow. Every durability/liveness read delegates to the
     * live controller — never a fake proof.
     */
    private class ControllerSession(val c: TurnController) : ChatSession {
        override val uiState: StateFlow<ChatUiState> get() = c.uiState
        override suspend fun send(text: String, images: List<ChatImageRef>) = c.send(text, images)
        override suspend fun startTurn(text: String, images: List<ChatImageRef>): Job =
            c.startTurn(text, images)
        override suspend fun startOrEnqueue(
            text: String,
            images: List<ChatImageRef>,
            opId: Long?,
        ): TurnStart = c.startOrEnqueue(text, images, opId)
        override fun drainQueued(): List<QueuedIntent> = c.drainQueued()
        override fun cancel() = c.cancel()
        override suspend fun flush(timeoutMs: Long): Boolean = c.flushTranscript(timeoutMs)
        override fun hasDurableFailures(): Boolean = c.hasDurableFailures()
        override suspend fun flushDurable(timeoutMs: Long): Boolean = c.flushDurable(timeoutMs)
        override fun steer(text: String) = c.steer(text)
        override suspend fun settlePendingAdmission(timeoutMs: Long): Boolean =
            c.settlePendingAdmission(timeoutMs)
        override fun hasPendingAdmission(): Boolean = c.hasPendingAdmission()
        override fun hasUnsettledAdmission(): Boolean = c.hasUnsettledAdmission()
        override fun hasUnsettledPromotion(): Boolean = c.hasUnsettledPromotion()
        override fun hasActiveHost(): Boolean = c.hasActiveHost()
        override fun hasClosedUnsettledAdmission(): Boolean = c.hasClosedUnsettledAdmission()
        override fun hasUnsettledCancelReceipts(): Boolean = c.hasUnsettledCancelReceipts()
        override fun hasSealedUncompensatedReceipts(): Boolean = c.hasSealedUncompensatedReceipts()
        override fun pendingTranscriptRunIds(): List<String> = c.pendingTranscriptRunIds()
        override fun isTranscriptSealed(): Boolean = c.isTranscriptSealed()
        override fun isTranscriptWriterCompleted(): Boolean = c.isTranscriptWriterCompleted()
        override fun close() = c.close()
    }

    /**
     * Session provider: the first open of [firstSid] builds the REAL gated
     * pre-dispatch session (empty ledger at attach, so no hydration is owed);
     * every later open delegates to the REAL factory (fresh sessions over the
     * same ledger) while recording the exact `backfillInterrupted` flag the VM
     * passed — same contract as the StickyDebt recording wrapper.
     */
    private class PreDispatchSessions(
        val inner: ChatSessionFactory,
        val firstSid: String,
        val buildFirst: () -> ChatSession,
    ) : ChatSessionProvider {
        val backfillFlags = CopyOnWriteArrayList<Boolean>()
        val attached = CopyOnWriteArrayList<ChatSession>()
        private val firstUsed = AtomicBoolean(false)
        override fun modelFor(endpoint: EndpointConfig): String = inner.modelFor(endpoint)
        override suspend fun storeOrNull(): SessionStore? = inner.storeOrNull()
        override suspend fun create(
            endpoint: EndpointConfig,
            title: String,
            initialHistory: List<ChatMessage>,
            keyIsCurrent: suspend () -> Boolean,
        ): CreatedSession =
            inner.create(endpoint, title, initialHistory, keyIsCurrent).also { attached.add(it.session) }

        override suspend fun open(
            endpoint: EndpointConfig,
            sessionId: String,
            keyIsCurrent: suspend () -> Boolean,
        ): CreatedSession =
            inner.open(endpoint, sessionId, keyIsCurrent).also { attached.add(it.session) }

        // P0-1 open-full-union: explicit route to the open-backfill-canonical
        // below, honoring backfillInterrupted (recorded, then threaded into
        // the real factory). allowAsk is intentionally discarded: this is a
        // policy-agnostic test double with no key.read gate of its own.
        override suspend fun open(
            endpoint: EndpointConfig,
            sessionId: String,
            allowAsk: Boolean,
            keyIsCurrent: suspend () -> Boolean,
            backfillInterrupted: Boolean,
        ): CreatedSession = open(endpoint, sessionId, keyIsCurrent, backfillInterrupted)

        override suspend fun open(
            endpoint: EndpointConfig,
            sessionId: String,
            keyIsCurrent: suspend () -> Boolean,
            backfillInterrupted: Boolean,
        ): CreatedSession {
            backfillFlags.add(backfillInterrupted)
            if (sessionId == firstSid && firstUsed.compareAndSet(false, true)) {
                val s = buildFirst()
                attached.add(s)
                return CreatedSession(sessionId, s, endpoint.providerId, modelFor(endpoint))
            }
            return inner.open(endpoint, sessionId, keyIsCurrent, backfillInterrupted)
                .also { attached.add(it.session) }
        }
    }

    @Test fun preDispatchCancelledWaiter_sealsAndReopensSingleInterrupted() = runBlocking {
        val store = newStore()
        runBlocking { store.save(sampleEndpoint()) }
        val inner = FakeSessionStore()
        val parking = ParkingStore(inner)
        val oldId = inner.createSession("t", "preset:openai@https://api.openai.com/gpt-4o-mini")
        val gate = GateDispatcher()
        val turnProvider = PreDispatchProvider()
        val n = AtomicInteger(0)
        val sessions = PreDispatchSessions(factoryWith(parking, turnProvider), oldId) {
            TurnController(
                provider = turnProvider,
                policy = AllowAllPolicy(),
                transcript = SessionTranscriptSink(parking, oldId),
                retryConfig = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(5_000L)),
                dispatcher = Dispatchers.Default,
                sleeper = {},
                newId = { "r241-pre-${n.incrementAndGet() - 1}" },
                transcriptDispatcher = gate,
                cancelSettleTimeoutMs = 300L,
                cancelCompensationTimeoutMs = 5_000L,
            ).let { ControllerSession(it) }
        }
        val vm = chatViewModels.own(ChatViewModel(store, sessions, InMemoryPolicyStore()))
        var fakeNow = 30_000_000L
        onMain {
            vm.backfillClockMs = { fakeNow }
            vm.backfillBlockHorizonMs = 500L
        }
        try {
            // #1 attach the pre-dispatch session over the empty ledger.
            onMain { vm.openSession(oldId) }
            awaitCond(10_000, "VM must attach the old ledger") { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true), sessions.backfillFlags.toList())
            val s1 = sessions.attached.single() as ControllerSession
            val c = s1.c
            assertTrue(rows(inner, oldId).isEmpty())

            // Stream a turn; the terminal parks in the delegate store block
            // (writer runs — gate still open) while the partial is visible.
            val drive = driveScope()
            val admission = drive.async { s1.startOrEnqueue("q-predispatch") }
            val started = withTimeout(10_000) { admission.await() }
            assertTrue("admission must start, was $started", started is TurnStart.Started)
            check(parking.entered.await(5, TimeUnit.SECONDS)) { "terminal must park in the store block" }
            awaitCond(10_000, "partial must be visible") {
                s1.uiState.value.messages.any { it.text == "half-predispatch" }
            }

            // Hold the writer dispatcher BEFORE the cancel, so the waiter the
            // cancel installs is queued but provably never started. The held
            // terminal keeps the settle wait pending past its 300ms budget
            // (microsecond gate-close vs 300ms settle: no race), so the
            // receipt path is deterministic.
            gate.paused = true
            val bodyEntered = AtomicBoolean(false)
            c.beforeCancelReceiptWaiterBodyForTest = { bodyEntered.set(true) }
            val registered = CountDownLatch(1)
            c.onCancelReceiptWaiterEnteredForTest = { registered.countDown() }

            // Cancel mid-stream with the terminal still owed: receipt PENDING.
            s1.cancel()
            awaitCond(15_000, "receipt must be unsettled after cancel") {
                s1.hasUnsettledCancelReceipts()
            }
            assertTrue("waiter must launch+register", registered.await(5, TimeUnit.SECONDS))
            assertFalse("waiter body must not have started while the dispatcher is held", bodyEntered.get())
            val cancelId = c.cancelReceiptStates().keys.single()
            assertEquals("PENDING", c.cancelReceiptStates().getValue(cancelId))

            // Cancel the waiter before its first execution — the
            // production-faithful equivalent of a parent teardown cancel
            // landing pre-dispatch. A queued task still needs one dispatch
            // slot to observe the cancellation, so the gate opens only after
            // the cancel: program order (cancel-then-resume) guarantees the
            // task sees the dead job and completes without running the body.
            val waiter = c.cancelReceiptWaiterJobForTest(cancelId)
            assertTrue("waiter job must be registered", waiter != null)
            waiter!!.cancel()
            assertFalse(
                "waiter body must not have started while the dispatcher is held",
                bodyEntered.get(),
            )
            gate.resumeAll()
            runCatching {
                withTimeout(8_000) { waiter.join() }
            }
            assertTrue("cancelled waiter must complete", waiter.isCompleted)
            assertFalse(
                "cancelled-before-start waiter body must never have executed",
                bodyEntered.get(),
            )
            awaitCond(8_000, "receipt must converge SEALED via the completion fail-safe") {
                c.cancelReceiptStates().getValue(cancelId) == "SEALED"
            }
            assertFalse(
                "fail-safe SEALED must clear the unsettled proof",
                s1.hasUnsettledCancelReceipts(),
            )

            // Release the writer dispatcher: the sink writer runs back into
            // the (still held) delegate block; close then exhausts its budget
            // against the parked writer on the next reopen's teardown.
            gate.resumeAll()

            // #2 first same-ID reopen: close seals while the writer is still
            // inside its delegate store block → defer + park.
            onMain { vm.openSession(oldId) }
            awaitCond(60_000, "second open must record its backfill flag") {
                sessions.backfillFlags.size >= 2
            }
            awaitCond(10_000, "VM must reattach the same ledger") { vm.currentSessionId.value == oldId }
            assertEquals(listOf(true, false), sessions.backfillFlags.toList())
            assertEquals("HISTORY_SNAPSHOT_INCOMPLETE", vm.notice.value)
            assertTrue(interruptedMarkers(inner, oldId).isEmpty())
            assertEquals(1, blockSize(vm))
            awaitCond(20_000, "teardown must seal the old writer") { s1.isTranscriptSealed() }
            assertTrue(
                "drain-timeout mark must have cleared pending",
                s1.pendingTranscriptRunIds().isEmpty(),
            )
            assertFalse(
                "writer still inside the blocking store block must not read completed",
                s1.isTranscriptWriterCompleted(),
            )
            assertTrue(
                "fail-safe SEALED receipt must stay visible (never silently settled)",
                s1.hasSealedUncompensatedReceipts(),
            )
            assertFalse(
                "old flushDurable must stay false while the family dangles",
                withTimeout(10_000) { s1.flushDurable() },
            )
            // R14-1 fence intact: seal + empty pending WITHOUT completion is
            // NOT dead — the PENDING/CLAIMED guard was not removed to reach
            // the fail-safe SEALED.
            assertFalse(
                "writer inside its store block must not read dead",
                vmEvidence(vm, "isWriterSealedDead", s1),
            )
            assertTrue(
                "incomplete writer must still read alive (block must not promote)",
                vmEvidence(vm, "isWriterAliveEvidence", s1),
            )

            // #3 negative control: past the horizon while the writer is still
            // inside its delegate store block → must renew + defer, never
            // promote on the clock alone.
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

            // Release the delegate block: the owed rows commit via the
            // independent writer and the writer completes. The fail-safe
            // SEALED receipt stays (never revoked by the late commit) while
            // the family still dangles without its deferred system write.
            parking.released = true
            parking.release.complete(Unit)
            awaitCond(20_000, "released writer must complete") { s1.isTranscriptWriterCompleted() }
            awaitCond(10_000, "doomed host must settle") { !s1.hasActiveHost() }
            assertFalse(
                "cancelled-before-start body must never have executed, even after release",
                bodyEntered.get(),
            )
            assertEquals("SEALED", c.cancelReceiptStates().getValue(cancelId))
            assertTrue(
                "SEALED without proven final must stay visible",
                s1.hasSealedUncompensatedReceipts(),
            )
            val dangling = findDanglingRunIds(rows(inner, oldId))
            assertEquals("exactly the cancelled family must dangle", 1, dangling.size)
            // Dead/alive pins AFTER completion: sealed + pending-empty +
            // completed with only sticky debt reads dead, never alive — the
            // backfill liveness the P1 blocked.
            assertTrue(vmEvidence(vm, "isLedgerStreamingOrPending", s1))
            assertFalse("old flushDurable must stay false", withTimeout(10_000) { s1.flushDurable() })
            assertTrue(
                "completed writer with only sticky debt must read dead",
                vmEvidence(vm, "isWriterSealedDead", s1),
            )
            assertFalse(
                "sticky-only dead writer must not read alive",
                vmEvidence(vm, "isWriterAliveEvidence", s1),
            )

            // #4 expiry with the dead proof: promotes exactly one INTERRUPTED
            // despite the sticky receipt, and lifts the block.
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
            assertEquals("dead-writer expiry must mark the family exactly once", 1, marks.size)
            assertEquals("turn ${dangling.single()} interrupted", marks.single().text)
            assertTrue(findDanglingRunIds(rows(inner, oldId)).isEmpty())
            assertNull(
                "promoted reopen resolves the dead ledger with no stale warning",
                vm.notice.value,
            )
            assertEquals(0, blockSize(vm))
            val sNew = sessions.attached.last()
            assertTrue(
                "promoted new session must flushDurable true",
                withTimeout(10_000) { sNew.flushDurable() },
            )
            // F-D INTERRUPTED-then-late-terminal coexistence: the owed
            // terminal committed via the pre-#4 release (late relative to the
            // cancel/close) and the #4 expiry added the INTERRUPTED marker —
            // both rows coexist with the family closed (no dangle), the
            // fail-safe SEALED receipt stays settled-visible, and the new
            // session drains durable. The release stays pre-#4 (dead-proof
            // prerequisite: the writer must complete before expiry promotes).
            val afterPromote = rows(inner, oldId)
            assertTrue(
                "late terminal must have committed, kinds=${afterPromote.map { it.kind }}",
                afterPromote.any { it.kind == "assistant" },
            )
            assertEquals(1, interruptedMarkers(inner, oldId).size)
            assertTrue(findDanglingRunIds(afterPromote).isEmpty())
            val promoteSize = afterPromote.size
            val promoteDurable = withTimeout(10_000) { sNew.flushDurable() }

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
            // F-D second-open stability: coexisting terminal + marker gain
            // zero rows, and durability reads consistent with the post-promote
            // proof (true both times, never true-and-dangling).
            assertEquals(promoteSize, rows(inner, oldId).size)
            val sClean = sessions.attached.last()
            assertTrue(
                "clean reopen must stay flushDurable true like post-promote ($promoteDurable)",
                withTimeout(10_000) { sClean.flushDurable() },
            )
        } finally {
            parking.released = true
            parking.release.complete(Unit)
            gate.resumeAll()
            for (s in sessions.attached) runCatching { s.close() }
        }
    }
}
