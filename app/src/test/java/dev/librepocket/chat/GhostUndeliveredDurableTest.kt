package dev.librepocket.chat

import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.PrunePolicy
import dev.librepocket.session.PruneResult
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class GhostFakeProvider(
    var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
    override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
    val streamCalls = AtomicInteger(0)

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        streamCalls.incrementAndGet()
        handler(request).collect { emit(it) }
    }

    override suspend fun listModels(): List<String> = emptyList()
}

private class GhostAllowPolicy : PolicyStore {
    override fun evaluate(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
    override suspend fun setRule(rule: PolicyRule) = Unit
    override suspend fun removeRule(pattern: String) = Unit
    override suspend fun listRules(): List<PolicyRule> = emptyList()
    override suspend fun evaluateFresh(action: String, resource: String) =
        PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

private class GhostDelayStore(
    val sessionId: String = "ghost-durable-session",
) : SessionStore {
    val events: MutableList<TranscriptEvent> = Collections.synchronizedList(mutableListOf())

    override suspend fun createSession(title: String, model: String): String = sessionId
    override suspend fun listSessions(): List<SessionMeta> = emptyList()
    override suspend fun getSession(sessionId: String): SessionMeta? =
        SessionMeta(sessionId, "t", 0L, 0L, "m")
    override suspend fun appendEvent(event: TranscriptEvent): Long {
        events.add(event.copy(seq = (events.size + 1).toLong()))
        return events.size.toLong()
    }
    override suspend fun loadEvents(sessionId: String, afterSeq: Long, limit: Int): List<TranscriptEvent> =
        events.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)
    override suspend fun exportJsonl(sessionId: String, destFile: File) = Unit
    override suspend fun importJsonl(srcFile: File): String = error("unused")
    override suspend fun prune(policy: PrunePolicy): PruneResult = PruneResult(0, 0)
    override suspend fun deleteSession(sessionId: String) = Unit
}

/**
 * R10-3: a proven-undelivered fuzzy ghost (barrier proof via
 * [OrderedTranscriptSink.tryResolveUndelivered]) is an unadmitted/cancelled
 * send attempt — never a persisted-admission write failure — so it must stay
 * visible as INTERRUPTED for its own id without permanently poisoning
 * [OrderedTranscriptSink.hasDurableFailures]/`flushDurable`. A successful
 * same-opId retry afterwards drains clean and `flushDurable()` is true again;
 * genuine store failures still poison durability and are never cleared.
 */
class GhostUndeliveredDurableTest {

    @OptIn(DelicateCoroutinesApi::class)
    @Test fun sinkGhostResolveKeepsFlushDurableClean() {
        val blockerGate = CompletableDeferred<Unit>()
        val blockerEntered = CompletableDeferred<Unit>()
        val failIds = Collections.synchronizedSet(mutableSetOf<String>())
        val delegate = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) {
                if (runId == "blocker") {
                    blockerEntered.complete(Unit)
                    blockerGate.await()
                }
                if (failIds.contains(runId)) throw IOException("fault-injected disk failure")
            }
            override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnSucceeded(
                runId: String,
                text: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(runId: String, error: String) = Unit
            override suspend fun onTurnFailed(runId: String, partialText: String, error: String) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
                hasReasoning: Boolean,
            ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
            override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnCancelled(
                runId: String,
                partialText: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onLogicalTurnCancelled(
                logicalTurnId: String,
                attemptRunId: String,
                partialText: String,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
            override suspend fun onSteerQueued(text: String) = Unit
            override suspend fun onToolDone(
                runId: String,
                toolIndex: Int,
                id: String,
                name: String,
                argumentsJson: String,
            ) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        val sink = OrderedTranscriptSink(delegate, Dispatchers.Default, capacity = 1)
        try {
            // Fill the channel behind the parked writer: blocker (taken, parked
            // in the delegate) + filler (buffered, capacity 1). The ghost send
            // below suspends with the attempt recorded.
            val blocker = GlobalScope.async(Dispatchers.Default) {
                sink.onTurnStarted("blocker", "b")
            }
            runBlocking { withTimeout(5_000) { blockerEntered.await() } }
            sink.offerSteer("filler")
            val sent = AtomicBoolean(false)
            val attempted = AtomicBoolean(false)
            val ghostAtSend = AtomicBoolean(false)
            sink.beforeSendForTest = { _ -> ghostAtSend.set(true) }
            val ghost = GlobalScope.async(Dispatchers.Default) {
                sink.onTurnStartedTracked("ghost-1", "g", sent, attempted)
            }
            runBlocking {
                withTimeout(5_000) { while (!ghostAtSend.get()) delay(10) }
                withTimeout(5_000) { while (!sink.pendingRunIds().contains("ghost-1")) delay(10) }
            }
            Thread.sleep(300)
            assertFalse("ghost must still be suspended in send", ghost.isCompleted)
            sink.beforeSendForTest = null
            // Fuzzy cancel: attempted but never delivered.
            ghost.cancel()
            runBlocking { withTimeout(5_000) { try { ghost.await() } catch (_: CancellationException) { } } }
            assertTrue(attempted.get())
            assertFalse(sent.get())
            assertTrue(sink.pendingRunIds().contains("ghost-1"))
            // Release the writer so the barrier drains blocker + filler. The
            // ghost never entered, so the barrier proves non-delivery.
            blockerGate.complete(Unit)
            runBlocking {
                withTimeout(5_000) { blocker.await() }
                withTimeout(5_000) { assertTrue(sink.flush()) }
            }
            assertTrue(
                "barrier drained with the id still pending: proven undelivered",
                sink.pendingRunIds().contains("ghost-1"),
            )
            assertTrue(sink.tryResolveUndelivered("ghost-1"))
            // The ghost keeps its true INTERRUPTED mark for its own id ...
            assertTrue(sink.interruptedRunIds().contains("ghost-1"))
            assertTrue(sink.pendingRunIds().isEmpty())
            // ... without poisoning the global durable-failure state.
            assertFalse(
                "proven-undelivered ghost must not count as a durable failure",
                sink.hasDurableFailures(),
            )
            runBlocking {
                withTimeout(5_000) { assertTrue(sink.flushDurable()) }
            }
            // A later successful write (the same-opId retry's fresh id) drains
            // clean and durability stays true.
            runBlocking {
                withTimeout(5_000) { sink.onTurnStarted("retry-1", "g") }
                withTimeout(5_000) { assertTrue(sink.flushDurable()) }
            }
            assertFalse(sink.hasDurableFailures())
            assertTrue(sink.interruptedRunIds().contains("ghost-1"))
            assertFalse(
                "retry id must not carry a false INTERRUPTED mark",
                sink.interruptedRunIds().contains("retry-1"),
            )
        } finally {
            blockerGate.complete(Unit)
            sink.beforeSendForTest = null
        }
    }

    @Test fun sinkTrueStoreFailureStillPoisonsDurableAfterGhostResolved() {
        val delegate = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) {
                if (runId == "faulty-core") throw IOException("fault-injected disk failure")
            }
            override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnSucceeded(
                runId: String,
                text: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(runId: String, error: String) = Unit
            override suspend fun onTurnFailed(runId: String, partialText: String, error: String) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
                hasReasoning: Boolean,
            ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
            override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnCancelled(
                runId: String,
                partialText: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onLogicalTurnCancelled(
                logicalTurnId: String,
                attemptRunId: String,
                partialText: String,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
            override suspend fun onSteerQueued(text: String) = Unit
            override suspend fun onToolDone(
                runId: String,
                toolIndex: Int,
                id: String,
                name: String,
                argumentsJson: String,
            ) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        val sink = OrderedTranscriptSink(delegate, Dispatchers.Default, capacity = 8)
        runBlocking {
            withTimeout(10_000) {
                try {
                    sink.onTurnStarted("faulty-core", "faulty user text")
                    fail("store fault-injection must cause core ack to throw")
                } catch (e: Exception) {
                    assertTrue("core ack must throw IOException, got: $e", e is IOException)
                }
                assertTrue(sink.hasDurableFailures())
                assertTrue("flush() still drains the barrier", sink.flush())
                assertFalse(
                    "genuine persisted-admission failure must keep flushDurable false",
                    sink.flushDurable(),
                )
                assertTrue(sink.interruptedRunIds().contains("faulty-core"))
            }
        }
    }

    /**
     * Controller end-to-end: fuzzy-undelivered ghost → barrier proof → same-opId
     * retry → turn completes → `flushDurable()` is true again (pre-fix it stayed
     * false forever, which would fence every later same-source switch/reopen
     * into INCOMPLETE/deferred). The ghost keeps its mark; the retry id is clean.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test fun controllerGhostBarrierProofRetryKeepsFlushDurableClean() {
        val store = GhostDelayStore()
        val inner = SessionTranscriptSink(store, store.sessionId)
        val writerGate = CompletableDeferred<Unit>()
        val blockerEntered = CompletableDeferred<Unit>()
        val blockingSink = object : TranscriptSink by inner {
            override suspend fun onSteerQueued(text: String) {
                if (text == "blocker") {
                    blockerEntered.complete(Unit)
                    writerGate.await()
                }
                inner.onSteerQueued(text)
            }
        }
        val provider = GhostFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = GhostAllowPolicy(),
            transcript = blockingSink,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "r10ghost-${n++}" },
            acceptTimeoutMs = 10_000L,
            transcriptCapacity = 1,
        )
        val beforeSendEntered = AtomicBoolean(false)
        c.beforeAdmissionSendForTest = {
            beforeSendEntered.set(true)
        }
        val text = "ghost durable text"
        val opId = 904L
        try {
            c.offerSteerForTest("blocker")
            runBlocking {
                withTimeout(5_000) { blockerEntered.await() }
            }
            c.offerSteerForTest("filler")
            val deferred = GlobalScope.async(Dispatchers.Default) {
                c.startOrEnqueue(text, opId = opId)
            }
            runBlocking {
                withTimeout(5_000) { while (!beforeSendEntered.get()) delay(10) }
                withTimeout(5_000) { while (c.pendingTranscriptRunIds().isEmpty()) delay(10) }
            }
            Thread.sleep(200)
            assertFalse("admission must still be suspended in send", deferred.isCompleted)
            deferred.cancel()
            try {
                runBlocking {
                    withTimeout(5_000) { deferred.await() }
                }
                fail("fuzzy suspended-send cancel must throw TranscriptPendingException")
            } catch (e: TranscriptPendingException) {
                // Expected UNKNOWN path (fuzzy, not NOT_ADMITTED).
            } catch (e: CancellationException) {
                fail("fuzzy cancel must not surface CancellationException, got $e")
            }
            c.beforeAdmissionSendForTest = null
            assertTrue(c.hasPendingAdmission())
            val ghost = c.pendingTranscriptRunIds().single()
            assertTrue(c.interruptedTranscriptRunIds().isEmpty())
            writerGate.complete(Unit)
            val settled = runBlocking {
                withTimeout(5_000) { c.settlePendingAdmission(500L) }
            }
            assertTrue("fuzzy-undelivered barrier proof must resolve in the open session", settled)
            assertFalse(c.hasPendingAdmission())
            assertTrue(c.pendingTranscriptRunIds().isEmpty())
            assertTrue(
                "resolved ghost carries a true INTERRUPTED mark (never claimed)",
                c.interruptedTranscriptRunIds().contains(ghost),
            )
            // Same-session retry with the SAME opId admits immediately with a
            // fresh id and runs to completion.
            val retry = runBlocking {
                withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) }
            }
            assertTrue("same-session retry after barrier proof must admit", retry is TurnStart.Started)
            runBlocking {
                (retry as TurnStart.Started).host.join()
                withTimeout(5_000) { c.flushTranscript() }
            }
            val users = store.events.filter { it.kind == "user" }
            assertEquals("retry keeps exactly one user row", 1, users.size)
            assertEquals(text, users.single().text)
            val retryId = users.single().runId
            assertTrue("retry id must differ from the undelivered ghost", retryId != ghost)
            assertTrue(
                "undelivered ghost stays marked",
                c.interruptedTranscriptRunIds().contains(ghost),
            )
            assertTrue(
                "retry id must not carry a false INTERRUPTED mark",
                !c.interruptedTranscriptRunIds().contains(retryId),
            )
            assertTrue(
                "claimed family must not dangle",
                dev.librepocket.agent.ui.chat.findDanglingRunIds(store.events).isEmpty(),
            )
            // R10-3 core assertion: the proven-undelivered ghost must not
            // permanently poison durability — the retried ledger is fully
            // durable, so later same-source switch/reopen fences see clean.
            assertFalse(
                "proven-undelivered ghost must not count as a durable failure",
                c.hasDurableFailures(),
            )
            runBlocking {
                withTimeout(5_000) { assertTrue(c.flushDurable()) }
            }
        } finally {
            writerGate.complete(Unit)
            c.beforeAdmissionSendForTest = null
            c.close()
        }
    }

    /**
     * A2 seal/resolve competition: a ghost resolved BEFORE the seal must never
     * be dual-poisoned by the seal's two loops (coreAcks + orphan drain), even
     * though the seal runs concurrently on the writer thread. True failures
     * still poison (covered by [sinkTrueStoreFailureStillPoisonsDurableAfterGhostResolved]).
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test fun sealAfterResolveSkipsGhostPoison() {
        val blockerGate = CompletableDeferred<Unit>()
        val blockerEntered = CompletableDeferred<Unit>()
        val delegate = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) {
                if (runId == "blocker") {
                    blockerEntered.complete(Unit)
                    blockerGate.await()
                }
            }
            override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnSucceeded(
                runId: String,
                text: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(runId: String, error: String) = Unit
            override suspend fun onTurnFailed(runId: String, partialText: String, error: String) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                text: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                text: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
                hasReasoning: Boolean,
            ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
            override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnCancelled(
                runId: String,
                text: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onLogicalTurnCancelled(
                logicalTurnId: String,
                attemptRunId: String,
                partialText: String,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
            override suspend fun onSteerQueued(text: String) = Unit
            override suspend fun onToolDone(
                runId: String,
                toolIndex: Int,
                id: String,
                name: String,
                argumentsJson: String,
            ) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        val sink = OrderedTranscriptSink(delegate, Dispatchers.Default, capacity = 1)
        try {
            val blocker = GlobalScope.async(Dispatchers.Default) {
                sink.onTurnStarted("blocker", "b")
            }
            runBlocking { withTimeout(5_000) { blockerEntered.await() } }
            sink.offerSteer("filler")
            val sent = AtomicBoolean(false)
            val attempted = AtomicBoolean(false)
            val ghostAtSend = AtomicBoolean(false)
            sink.beforeSendForTest = { _ -> ghostAtSend.set(true) }
            val ghost = GlobalScope.async(Dispatchers.Default) {
                sink.onTurnStartedTracked("ghost-seal-race", "g", sent, attempted)
            }
            runBlocking {
                withTimeout(5_000) { while (!ghostAtSend.get()) delay(10) }
                withTimeout(5_000) { while (!sink.pendingRunIds().contains("ghost-seal-race")) delay(10) }
            }
            Thread.sleep(300)
            sink.beforeSendForTest = null
            ghost.cancel()
            runBlocking { withTimeout(5_000) { try { ghost.await() } catch (_: CancellationException) { } } }
            assertTrue(attempted.get())
            assertFalse(sent.get())
            blockerGate.complete(Unit)
            runBlocking {
                withTimeout(5_000) { blocker.await() }
                withTimeout(5_000) { assertTrue(sink.flush()) }
            }
            assertTrue(sink.tryResolveUndelivered("ghost-seal-race"))
            assertTrue(sink.isResolvedUndelivered("ghost-seal-race"))
            assertFalse(sink.hasDurableFailures())
            // Resolve happens-before the seal below: the seal's coreAcks +
            // orphan loops must skip the resolved ghost (no dual-poison), so
            // durability stays clean even though the seal runs concurrently.
            val sealJob = sink.shutdown(5_000L) {}
            runBlocking { withTimeout(10_000) { sealJob?.join() } }
            assertTrue(
                "resolved ghost must stay visible after seal",
                sink.interruptedRunIds().contains("ghost-seal-race"),
            )
            assertTrue(sink.isResolvedUndelivered("ghost-seal-race"))
            assertFalse(
                "seal racing a resolved ghost must not dual-poison durability",
                sink.hasDurableFailures(),
            )
            runBlocking {
                withTimeout(5_000) { assertTrue(sink.flushDurable()) }
            }
        } finally {
            blockerGate.complete(Unit)
            sink.beforeSendForTest = null
        }
    }

    /**
     * A1 lock-order: concurrent poison readers/writers must never deadlock
     * (single [poisonLock] order, no interrupted↔resolved nesting) and must
     * never dual-poison one id into both sets. Single process, bounded.
     */
    @Test fun concurrentPoisonReadsNeverDeadlockNorDualPoison() {
        val delegate = object : TranscriptSink {
            override suspend fun onTurnStarted(runId: String, text: String) = Unit
            override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnSucceeded(
                runId: String,
                text: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(runId: String, error: String) = Unit
            override suspend fun onTurnFailed(runId: String, partialText: String, error: String) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                text: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                text: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
            ) = Unit
            override suspend fun onTurnFailed(
                runId: String,
                partialText: String,
                error: String,
                parentRunId: String?,
                attemptIndex: Int?,
                isFinal: Boolean,
                hasReasoning: Boolean,
            ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
            override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
            override suspend fun onTurnCancelled(
                runId: String,
                text: String,
                parentRunId: String?,
                attemptIndex: Int?,
                hasReasoning: Boolean,
            ) = Unit
            override suspend fun onLogicalTurnCancelled(
                logicalTurnId: String,
                attemptRunId: String,
                partialText: String,
                attemptIndex: Int?,
            ) = Unit
            override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
            override suspend fun onSteerQueued(text: String) = Unit
            override suspend fun onToolDone(
                runId: String,
                toolIndex: Int,
                id: String,
                name: String,
                argumentsJson: String,
            ) = Unit
            override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
        }
        val sink = OrderedTranscriptSink(delegate, Dispatchers.Default, capacity = 8)
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(8)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(8) { worker ->
            kotlin.concurrent.thread(name = "poison-hammer-$worker") {
                try {
                    startLatch.await()
                    repeat(2_000) { i ->
                        val id = "hammer-${worker % 4}-$i"
                        // Opposite-order pair pre-fix: readers snapshot while a
                        // resolver checks under the other order. Post-fix both
                        // share one lock and cannot stick.
                        sink.interruptedRunIds()
                        sink.hasDurableFailures()
                        sink.isResolvedUndelivered(id)
                        if (i % 3 == 0) sink.markInterrupted(id)
                        if (i % 5 == 0) sink.tryResolveUndelivered(id)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }
        startLatch.countDown()
        assertTrue(
            "poison hammer must finish without deadlock",
            doneLatch.await(30, java.util.concurrent.TimeUnit.SECONDS),
        )
        assertTrue("hammer workers must not throw: $errors", errors.isEmpty())
    }

    /**
     * R11-2 timeout path: a proven never-attempted admission (send never
     * invoked, timeout before send) is NOT an admitted write failure, so it
     * must stay visible for its own id without poisoning [hasDurableFailures]/
     * `flushDurable`. A successful same-session retry afterwards drains clean.
     * Pre-fix the first id stayed in `interrupted` forever and every later
     * `flushDurable()` returned false (spurious HISTORY_SNAPSHOT_INCOMPLETE).
     */
    @Test fun controllerNeverAdmittedTimeoutRetryKeepsFlushDurableClean() {
        val store = GhostDelayStore()
        val inner = SessionTranscriptSink(store, store.sessionId)
        val provider = GhostFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = GhostAllowPolicy(),
            transcript = inner,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "r11-never-${n++}" },
            acceptTimeoutMs = 400L,
        )
        val gate = CompletableDeferred<Unit>()
        c.beforeAdmissionSendForTest = { gate.await() }
        val text = "never-admitted timeout text"
        val opId = 911L
        try {
            try {
                runBlocking { c.startOrEnqueue(text, opId = opId) }
                fail("proven never-attempted timeout must throw TranscriptDurabilityException")
            } catch (e: TranscriptDurabilityException) {
                // Expected fail-closed NOT_ADMITTED path.
            }
            assertTrue(c.pendingTranscriptRunIds().isEmpty())
            assertTrue(
                "never-admitted id stays visible",
                c.interruptedTranscriptRunIds().contains("r11-never-1"),
            )
            assertFalse(
                "proven never-attempted must not count as a durable failure",
                c.hasDurableFailures(),
            )
            gate.complete(Unit)
            c.beforeAdmissionSendForTest = null
            val retry = runBlocking {
                withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) }
            }
            assertTrue("retry after never-admitted timeout must admit", retry is TurnStart.Started)
            runBlocking {
                (retry as TurnStart.Started).host.join()
                withTimeout(5_000) { c.flushTranscript() }
            }
            val users = store.events.filter { it.kind == "user" }
            assertEquals("retry keeps exactly one user row", 1, users.size)
            assertEquals(text, users.single().text)
            val retryId = users.single().runId
            assertTrue(
                "never-admitted ghost stays marked",
                c.interruptedTranscriptRunIds().contains("r11-never-1"),
            )
            assertTrue(
                "retry id must not carry a false INTERRUPTED mark",
                !c.interruptedTranscriptRunIds().contains(retryId),
            )
            assertFalse(
                "retry must leave durability clean",
                c.hasDurableFailures(),
            )
            runBlocking {
                withTimeout(5_000) { assertTrue(c.flushDurable()) }
            }
        } finally {
            gate.complete(Unit)
            c.beforeAdmissionSendForTest = null
            c.close()
        }
    }

    /**
     * R11-2 cancel path: external cancel parked before the channel send
     * (proven never-attempted) rethrows CE and stays retryable — likewise
     * without poisoning durability for the later retry.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test fun controllerNeverAdmittedCancelRetryKeepsFlushDurableClean() {
        val store = GhostDelayStore()
        val inner = SessionTranscriptSink(store, store.sessionId)
        val provider = GhostFakeProvider {
            flow {
                emit(StreamEvent.TextDelta(0, 0, "ok"))
                emit(StreamEvent.Done("stop"))
            }
        }
        var n = 0
        val c = TurnController(
            provider = provider,
            policy = GhostAllowPolicy(),
            transcript = inner,
            dispatcher = Dispatchers.Default,
            sleeper = {},
            newId = { "r11-cancel-${n++}" },
            acceptTimeoutMs = 10_000L,
        )
        val gate = CompletableDeferred<Unit>()
        val entered = AtomicBoolean(false)
        c.beforeAdmissionSendForTest = {
            entered.set(true)
            gate.await()
        }
        val text = "never-admitted cancel text"
        val opId = 912L
        try {
            val deferred = GlobalScope.async(Dispatchers.Default) {
                c.startOrEnqueue(text, opId = opId)
            }
            runBlocking {
                withTimeout(5_000) { while (!entered.get()) delay(10) }
            }
            Thread.sleep(200)
            assertFalse("admission must still be parked before send", deferred.isCompleted)
            deferred.cancel()
            try {
                runBlocking {
                    withTimeout(5_000) { deferred.await() }
                }
                fail("before-send cancel must throw CancellationException")
            } catch (e: TranscriptPendingException) {
                fail("before-send cancel must not become UNKNOWN/Pending, got $e")
            } catch (e: CancellationException) {
                // Expected NOT_ADMITTED path.
            }
            assertFalse(c.hasPendingAdmission())
            assertTrue(c.pendingTranscriptRunIds().isEmpty())
            assertTrue(
                "never-admitted id stays visible",
                c.interruptedTranscriptRunIds().contains("r11-cancel-1"),
            )
            assertFalse(
                "proven never-attempted cancel must not count as a durable failure",
                c.hasDurableFailures(),
            )
            gate.complete(Unit)
            c.beforeAdmissionSendForTest = null
            val retry = runBlocking {
                withTimeout(5_000) { c.startOrEnqueue(text, opId = opId) }
            }
            assertTrue("retry after never-admitted cancel must admit", retry is TurnStart.Started)
            runBlocking {
                (retry as TurnStart.Started).host.join()
                withTimeout(5_000) { c.flushTranscript() }
            }
            assertEquals(1, store.events.count { it.kind == "user" })
            assertFalse(c.hasDurableFailures())
            runBlocking {
                withTimeout(5_000) { assertTrue(c.flushDurable()) }
            }
        } finally {
            gate.complete(Unit)
            c.beforeAdmissionSendForTest = null
            c.close()
        }
    }
}
