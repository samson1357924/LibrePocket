package dev.librepocket.chat

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** No-op delegate base so tests only override the hook they stall. */
private open class SinkProbe : TranscriptSink {
  val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())
  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = onTurnSucceeded(runId, text, hasReasoning)
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) = onTurnFailed(runId, partialText, error)
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
  ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex)
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    hasReasoning: Boolean,
  ) = onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = onTurnCancelled(runId, partialText, hasReasoning)
  override suspend fun onSteerQueued(text: String) {
    recorded.add(text)
  }
  override suspend fun onToolDone(
    runId: String,
    toolIndex: Int,
    id: String,
    name: String,
    argumentsJson: String,
  ) = Unit
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}

/**
 * OrderedTranscriptSink regressions for the review blockers:
 * - parked overflow steers keep admission order under a full channel (M3);
 * - post-seal notices fail visibly instead of succeeding silently (M2);
 * - flush is bounded and reports whether it drained (M5).
 */
class OrderedTranscriptSinkTest {

  @Test fun parkedOverflowSteersAdmitInOrder() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val gate = CompletableDeferred<Unit>()
      var first = true
      val delegate = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          // Stall the single writer on the first entry: every later offer
          // meets a full channel and parks behind the overflow mutex.
          if (first) {
            first = false
            gate.await()
          }
          recorded.add(text)
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 1)
      val offered = (0 until 50).map { "steer-$it" }
      runBlocking {
        withTimeout(10_000) {
          offered.forEach { sink.offerSteer(it) }
          gate.complete(Unit)
          assertTrue("flush must drain the parked overflows", sink.flush())
        }
      }
      assertEquals(offered, delegate.recorded)
    } finally {
      exec.shutdown()
    }
  }

  @Test fun noticeAfterSealThrowsInsteadOfSilentlySucceeding() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val sink = OrderedTranscriptSink(SinkProbe(), dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          sink.shutdown()?.join()
          try {
            sink.onUsage("sealed-run", 1, 2)
            fail("post-seal notice must not succeed silently")
          } catch (_: ClosedSendChannelException) {
            // Visible failure: the caller owns the seal race.
          }
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun writerCancellationFailsAckExplicitlyAndMarksInterrupted() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = object : SinkProbe() {
        override suspend fun onTurnStarted(runId: String, text: String) {
          // Simulates writer teardown mid-entry: the row may never persist.
          throw CancellationException("writer teardown")
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-1", "hi")
            fail("writer teardown must not ack as durable")
          } catch (_: CancellationException) {
            // Explicit failure: the caller owns the teardown, never a silent success.
          }
          assertTrue(
            "a failed ack must surface as INTERRUPTED, never a false durable ack",
            sink.interruptedRunIds().contains("run-1"),
          )
          assertTrue(sink.pendingRunIds().isEmpty())
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun flushTimesOutWhileTheStoreStalls() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      val delegate = object : SinkProbe() {
        override suspend fun onTurnStarted(runId: String, text: String) {
          entered.complete(Unit)
          gate.await()
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          val admitted = async { sink.onTurnStarted("stalled-run", "hi") }
          entered.await()
          assertFalse("flush must report the stall instead of parking forever", sink.flush(200))
          gate.complete(Unit)
          admitted.await()
          assertTrue("flush drains once the store unblocks", sink.flush())
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Stage E: a later offer must not trySend past parked predecessors. The
   * writer is frozen mid-drain (blocking sleeps on the single-thread
   * dispatcher) so a freed slot coexists with a non-empty overflow chain —
   * deterministically. Pre-fix, D trySend-succeeds into the free slot and the
   * final order is blocker,filler,P,D,A,B; post-fix D queues behind B.
   */
  @Test fun laterOfferNeverOvertakesParkedPredecessors() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
      val delegate = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          started.add(text)
          // Blocking (not suspending): freezes the single-thread dispatcher
          // mid-drain so parked coroutines cannot advance while the test
          // thread offers the newcomer into the freed slot.
          if (text == "blocker" || text == "filler") Thread.sleep(3_000)
          recorded.add(text)
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 2)
      fun awaitStarted(text: String, timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!started.contains(text)) {
          if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $text")
          Thread.sleep(10)
        }
      }
      fun awaitRecorded(size: Int, timeoutMs: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (delegate.recorded.size < size) {
          if (System.currentTimeMillis() > end) {
            throw AssertionError("timed out waiting for $size records, have ${delegate.recorded}")
          }
          Thread.sleep(10)
        }
      }
      sink.offerSteer("blocker")
      awaitStarted("blocker")
      // Writer is frozen in blocker-sleep with buffer space: P fast-paths,
      // then A/B meet a full channel and park (chain=[B]).
      sink.offerSteer("filler")
      sink.offerSteer("P")
      sink.offerSteer("A")
      sink.offerSteer("B")
      // Blocker sleep ends; writer takes filler and freezes again in
      // filler-sleep. Buffer now holds only P (one free slot) while the
      // chain is still [B] and A/B cannot advance. The newcomer D must queue
      // behind B, never trySend into the free slot ahead of them.
      awaitStarted("filler")
      Thread.sleep(300)
      sink.offerSteer("D")
      awaitRecorded(6)
      assertEquals(listOf("blocker", "filler", "P", "A", "B", "D"), delegate.recorded)
      runBlocking {
        withTimeout(10_000) {
          assertTrue("drained sink must flush", sink.flush())
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Stage E: flush awaits the parked tail — including a send parked while a
   * previous tail was awaited (re-check) — and only then admits the barrier.
   * After a true flush, every steer offered before it is recorded.
   */
  @Test fun flushDoesNotOvertakeTailPark() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val gate = CompletableDeferred<Unit>()
      var first = true
      val delegate = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          if (first) {
            first = false
            gate.await()
          }
          recorded.add(text)
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 1)
      runBlocking {
        withTimeout(15_000) {
          sink.offerSteer("blocker")
          sink.offerSteer("filler")
          // Buffer full behind the stalled writer: A parks (chain=[A]).
          sink.offerSteer("A")
          val flushing = async { sink.flush(10_000) }
          // B parks while flush is awaiting the A tail (chain=[B]): the
          // re-check must cover it, so the barrier cannot overtake B.
          sink.offerSteer("B")
          kotlinx.coroutines.delay(300)
          assertTrue("flush must wait for the parked tail, not overtake it", !flushing.isCompleted)
          gate.complete(Unit)
          assertTrue("flush must drain the parked tail", flushing.await())
          // A true flush implies every steer offered before it is recorded.
          assertEquals(listOf("blocker", "filler", "A", "B"), delegate.recorded)
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * R2-7: When the writer is blocked and the buffer is full (capacity=1),
   * an offerSteer is parked in the admission chain. Subsequent writeCore and
   * writeOrdered calls must await the parked steer admission so that
   * the delegate receives events strictly in order (parked steer -> core -> notice).
   */
  @Test fun parkedSteerTakesPrecedenceOverLaterCoreAndOrderedWrites() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val blockerGate = CompletableDeferred<Unit>()
      var isFirst = true
      val delegate = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          if (isFirst) {
            isFirst = false
            blockerGate.await()
          }
          recorded.add(text)
        }
        override suspend fun onTurnStarted(runId: String, text: String) {
          recorded.add("core:$runId")
        }
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
          recorded.add("notice:$runId")
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 1)
      runBlocking {
        withTimeout(10_000) {
          // 1. Blocker: writer enters onSteerQueued and suspends on blockerGate
          sink.offerSteer("blocker")
          // 2. Filler: buffer of capacity=1 is now full
          sink.offerSteer("filler")
          // 3. Parked steer: buffer full, parked in the admission chain
          sink.offerSteer("parked-steer")
          kotlinx.coroutines.delay(100)

          // 4. While steer is parked, call writeCore and writeOrdered
          val coreJob = async { sink.onTurnStarted("turn-1", "core-text") }
          val orderedJob = async { sink.onTurnRetried("turn-2", 1, 3, 1000L) }
          kotlinx.coroutines.delay(100)

          // 5. Unblock writer
          blockerGate.complete(Unit)

          // 6. Await writes and flush
          coreJob.await()
          orderedJob.await()
          assertTrue("flush must succeed", sink.flush())

          // 7. Assert strict ordering: parked steer must precede core and ordered notice
          assertEquals(
            listOf("blocker", "filler", "parked-steer", "core:turn-1", "notice:turn-2"),
            delegate.recorded,
          )
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Stage 3 (R4-5) A: parked steer C -> waiting core D -> later steer E must
   * admit C,D,E. Pre-fix, D only snapshot-awaited the C tail without holding
   * a slot: once C completed and cleared the chain, D sat in the await ->
   * send resume gap while E saw an empty chain and fast-pathed past D
   * (C,E,D). The [beforeSendForTest] hook deterministically widens D's gap;
   * post-fix E links behind D's synchronously reserved gate either way.
   */
  @Test fun parkedTailWaitingCoreCannotBeOvertakenByLaterSteer() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val writerGate = CompletableDeferred<Unit>()
      var isFirst = true
      val delegate = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          if (isFirst) {
            isFirst = false
            writerGate.await()
          }
          recorded.add(text)
        }
        override suspend fun onTurnStarted(runId: String, text: String) {
          recorded.add("core:$runId")
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 1)
      val reservedTickets: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reservedTickets.add(it) }
      suspend fun awaitAdmission(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
          if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
          kotlinx.coroutines.delay(10)
        }
      }
      runBlocking {
        withTimeout(15_000) {
          sink.offerSteer("blocker")
          sink.offerSteer("filler")
          sink.offerSteer("C")
          // Let C's parked body pass its pre-send point and suspend in the
          // full-channel send before D reserves (C sees a null hook here).
          kotlinx.coroutines.delay(300)
          val coreJob = async { sink.onTurnStarted("D-run", "d") }
          awaitAdmission("D reservation + pending mark") {
            reservedTickets.size >= 4 && sink.pendingRunIds().contains("D-run")
          }
          val dTicket = reservedTickets[3]
          val gapEntered = CompletableDeferred<Unit>()
          val gapRelease = CompletableDeferred<Unit>()
          sink.beforeSendForTest = { ticket ->
            if (ticket == dTicket) {
              gapEntered.complete(Unit)
              gapRelease.await()
            }
          }
          // Admit C: D wakes from the C gate straight into the widened gap.
          writerGate.complete(Unit)
          gapEntered.await()
          // Later arrival while D waits: must queue behind D, never trySend
          // past it into the freed slot.
          sink.offerSteer("E")
          kotlinx.coroutines.delay(300)
          gapRelease.complete(Unit)
          coreJob.await()
          assertTrue("flush must drain C,D,E", sink.flush())
          assertEquals(
            listOf("blocker", "filler", "C", "core:D-run", "E"),
            delegate.recorded,
          )
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.beforeSendForTest = null
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Stage 3 (R4-5) B: same three-stage arrival but the waiter is a notice
   * (writeOrdered) and the newcomer is a steer. Notices hold a reservation
   * exactly like cores, so the later steer still queues behind them.
   */
  @Test fun parkedTailWaitingNoticeCannotBeOvertakenByLaterSteer() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val writerGate = CompletableDeferred<Unit>()
      var isFirst = true
      val delegate = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          if (isFirst) {
            isFirst = false
            writerGate.await()
          }
          recorded.add(text)
        }
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
          recorded.add("notice:$runId")
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 1)
      val reservedTickets: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reservedTickets.add(it) }
      suspend fun awaitAdmission(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
          if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
          kotlinx.coroutines.delay(10)
        }
      }
      runBlocking {
        withTimeout(15_000) {
          sink.offerSteer("blocker")
          sink.offerSteer("filler")
          sink.offerSteer("C")
          kotlinx.coroutines.delay(300)
          val noticeJob = async { sink.onTurnRetried("N-run", 1, 3, 1000L) }
          awaitAdmission("N reservation") { reservedTickets.size >= 4 }
          val nTicket = reservedTickets[3]
          val gapEntered = CompletableDeferred<Unit>()
          val gapRelease = CompletableDeferred<Unit>()
          sink.beforeSendForTest = { ticket ->
            if (ticket == nTicket) {
              gapEntered.complete(Unit)
              gapRelease.await()
            }
          }
          writerGate.complete(Unit)
          gapEntered.await()
          sink.offerSteer("E")
          kotlinx.coroutines.delay(300)
          gapRelease.complete(Unit)
          noticeJob.await()
          assertTrue("flush must drain C,N,E", sink.flush())
          assertEquals(
            listOf("blocker", "filler", "C", "notice:N-run", "E"),
            delegate.recorded,
          )
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.beforeSendForTest = null
    } finally {
      exec.shutdown()
    }
  }

  /**
   * R8-6: `Channel.send` delivery is ambiguous under cancel — a CE racing a
   * suspended send may fire after the entry was already handed to the writer,
   * with `sent` still false. Only the send-attempt proof (`sendAttempted`,
   * set immediately before the send call) distinguishes proven-not-admitted
   * (send never attempted: ack + pending dropped, CE propagates) from fuzzy
   * (send invoked: ack + pending retained for the writer/settle, never
   * INTERRUPTED-marked, upstream maps to UNKNOWN).
   *
   * Phase A cancels a genuinely suspended send (writer parked, buffer full, no
   * receiver — physically never admitted) and asserts the conservative
   * retention. Phase B lets the send really resume, parks it post-send but
   * pre-`sent`-flag via [OrderedTranscriptSink.afterSendForTest] (admitted yet
   * `sent == false`), cancels, and asserts the same fuzzy mapping plus the
   * late commit. Positioning uses gate observables (attempt flag / hook entry),
   * never a sleep-guessed `sent`.
   */
  @Test fun suspendedSendRacesCancelStaysFuzzyUntilSettled() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      // ---- Phase A: real suspended send + cancel (no receiver available). ----
      val writerGateA = CompletableDeferred<Unit>()
      val blockerEnteredA = CompletableDeferred<Unit>()
      val delegateA = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          if (text == "blocker") {
            blockerEnteredA.complete(Unit)
            writerGateA.await()
          }
          recorded.add(text)
        }
      }
      val sinkA = OrderedTranscriptSink(delegateA, dispatcher, capacity = 1)
      runBlocking {
        withTimeout(10_000) {
          sinkA.offerSteer("blocker")
          blockerEnteredA.await()
          sinkA.offerSteer("filler")
          val sentA = AtomicBoolean(false)
          val attemptedA = AtomicBoolean(false)
          val suspended = async { sinkA.onTurnStartedTracked("ghost-A", "hi", sentA, attemptedA) }
          // Observable proof the waiter reached the send (set immediately
          // before the call). The buffer holds filler behind the parked
          // writer, so the waiter is provably suspended inside channel.send;
          // the settle delay only lets the suspension land, it never guesses
          // `sent`.
          withTimeout(5_000) { while (!attemptedA.get()) delay(10) }
          delay(100)
          suspended.cancel()
          try {
            suspended.await()
            fail("cancelled suspended send must throw CancellationException")
          } catch (_: CancellationException) {
            // Expected fuzzy path (not a NOT_ADMITTED cleanup).
          }
          assertFalse("sent must stay false", sentA.get())
          assertTrue("attempt must be recorded", attemptedA.get())
          // R8-6 fuzzy retention: ack + pending stay for the writer/settle to
          // decide; never dropped, never INTERRUPTED-marked here.
          assertTrue(
            "fuzzy cancel must retain the pending mark",
            sinkA.pendingRunIds().contains("ghost-A"),
          )
          assertTrue(
            "fuzzy cancel must not mark INTERRUPTED",
            sinkA.interruptedRunIds().isEmpty(),
          )
          // No receiver was available while parked, so this interleaving is
          // physically not admitted: drain blocker + filler only.
          writerGateA.complete(Unit)
          assertTrue("drain must succeed", sinkA.flush())
          assertEquals(listOf("blocker", "filler"), delegateA.recorded)
          // Conservative retention stands until seal: only a non-delivery
          // proof (here the shutdown seal) settles it fail-closed.
          assertTrue(sinkA.pendingRunIds().contains("ghost-A"))
          sinkA.shutdown()?.join()
          assertTrue(sinkA.pendingRunIds().isEmpty())
          assertTrue(
            "sealed fuzzy entry converts fail-closed",
            sinkA.interruptedRunIds().contains("ghost-A"),
          )
        }
      }
      // ---- Phase B: resumed send, parked post-send-pre-flag, then cancel. ----
      val writerGateB = CompletableDeferred<Unit>()
      val blockerEnteredB = CompletableDeferred<Unit>()
      val ghostGateB = CompletableDeferred<Unit>()
      val afterSendEnteredB = AtomicBoolean(false)
      val hookReleaseB = CompletableDeferred<Unit>()
      val delegateB = object : SinkProbe() {
        override suspend fun onSteerQueued(text: String) {
          if (text == "blocker") {
            blockerEnteredB.complete(Unit)
            writerGateB.await()
          }
          recorded.add(text)
        }
        override suspend fun onTurnStarted(runId: String, text: String) {
          ghostGateB.await()
          recorded.add("core:$runId")
        }
      }
      val sinkB = OrderedTranscriptSink(delegateB, dispatcher, capacity = 1)
      runBlocking {
        withTimeout(10_000) {
          sinkB.offerSteer("blocker")
          blockerEnteredB.await()
          sinkB.offerSteer("filler")
          sinkB.afterSendForTest = { _ ->
            afterSendEnteredB.set(true)
            hookReleaseB.await()
          }
          val sentB = AtomicBoolean(false)
          val attemptedB = AtomicBoolean(false)
          val admitted = async { sinkB.onTurnStartedTracked("ghost-B", "hi", sentB, attemptedB) }
          withTimeout(5_000) { while (!attemptedB.get()) delay(10) }
          // Let the really-suspended send resume; it parks post-send-pre-flag
          // (send returned = admitted, `sent` still false).
          writerGateB.complete(Unit)
          withTimeout(5_000) { while (!afterSendEnteredB.get()) delay(10) }
          assertFalse("waiter must be parked in the post-send hook", admitted.isCompleted)
          admitted.cancel()
          try {
            admitted.await()
            fail("cancelled post-send waiter must throw CancellationException")
          } catch (_: CancellationException) {
            // Expected fuzzy path.
          }
          assertFalse("sent must stay false (flag comes after the hook)", sentB.get())
          assertTrue("attempt must be recorded", attemptedB.get())
          assertTrue(
            "fuzzy cancel must retain the pending mark",
            sinkB.pendingRunIds().contains("ghost-B"),
          )
          assertTrue(
            "fuzzy cancel must not mark INTERRUPTED",
            sinkB.interruptedRunIds().isEmpty(),
          )
          // The admitted entry late-commits through the independent writer.
          hookReleaseB.complete(Unit)
          ghostGateB.complete(Unit)
          assertTrue("late commit must drain", sinkB.flush())
          assertEquals(
            listOf("blocker", "filler", "core:ghost-B"),
            delegateB.recorded,
          )
          assertTrue(sinkB.pendingRunIds().isEmpty())
          assertTrue(
            "late-committed ghost must not be INTERRUPTED",
            sinkB.interruptedRunIds().isEmpty(),
          )
          sinkB.afterSendForTest = null
          sinkB.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * R14-1 mechanism proof: a timed-out shutdown closes the channel and clears
   * pending while the writer is still inside a non-cancellable store block
   * (the Room `runInTransaction` shape: no suspension point between first
   * write and COMMIT). The observable state — sealed + pending empty + writer
   * NOT completed — is real, so the backfill gate must conjoin writer
   * completion before treating an expired outstanding teardown as dead.
   */
  @Test fun shutdownTimeoutWithBlockedCore_sealedEmptyButWriterIncomplete() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val delegate = object : SinkProbe() {
        override suspend fun onTurnStarted(runId: String, text: String) {
          // Models the blocking store transaction: immune to
          // writerScope.cancel(), resolves only via the test gate.
          withContext(NonCancellable) {
            entered.complete(Unit)
            release.await()
          }
          recorded.add(runId)
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        try {
          withTimeout(10_000) {
            val admitted = async { sink.onTurnStarted("r14-late", "q") }
            entered.await()
            withTimeout(5_000) { while (sink.pendingRunIds().isEmpty()) delay(10) }
            // Bounded shutdown times out on the blocked core: it clears pending
            // and seals, but the writer stays inside the store block.
            sink.shutdown(drainTimeoutMs = 50L)?.join()
            assertTrue("timed-out shutdown must seal", sink.isSealed())
            assertTrue(
              "timed-out shutdown clears pending",
              sink.pendingRunIds().isEmpty(),
            )
            assertFalse(
              "writer still inside the store block must not read completed",
              sink.isWriterCompleted(),
            )
            // Late COMMIT: the blocked store call lands after the seal, while
            // the caller already observed the drain-timeout failure.
            release.complete(Unit)
            try {
              withTimeout(5_000) { admitted.await() }
              fail("timed-out admission must surface the drain-timeout failure")
            } catch (_: CancellationException) {
              // Caller-observed failure; the row still lands below.
            }
            withTimeout(5_000) { while (!sink.isWriterCompleted()) delay(10) }
            assertTrue(sink.isWriterCompleted())
            assertEquals(listOf("r14-late"), delegate.recorded)
          }
        } finally {
          // Always unblock the writer so a failed assert cannot leak the
          // single-thread executor (complete is idempotent).
          release.complete(Unit)
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  // ---- #94 best-effort accounting entries: bounded skip, non-sealing, and
  // non-VM swallow vs VM-fatal seal. ----

  @Test fun stalledBestEffortUsageIsBoundedAndSkipped() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val stall = CompletableDeferred<Unit>()
      val notices = AtomicInteger(0)
      val delegate = object : SinkProbe() {
        override suspend fun flushBufferedUsageBestEffort(runId: String) {
          // Cancellation-cooperative stall (a wedged store await): the writer
          // must time the entry out and keep draining.
          stall.await()
        }

        override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
          notices.incrementAndGet()
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          sink.flushBufferedUsageBestEffort("r1")
          // Admitted behind the stalled entry: the bounded skip must let it run.
          sink.onUsage("r1", 1, 2)
          assertTrue("writer must drain after the bounded skip", sink.flush(5_000))
        }
      }
      assertEquals(1, notices.get())
      assertFalse("best-effort stall must not seal the writer", sink.isSealed())
      assertFalse("a skipped accounting write is not a durable failure", sink.hasDurableFailures())
    } finally {
      exec.shutdown()
    }
  }

  @Test fun bestEffortNonVmFatalIsSwallowed() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val notices = AtomicInteger(0)
      val delegate = object : SinkProbe() {
        override suspend fun flushBufferedUsageBestEffort(runId: String) {
          // Non-VM fatal (Error, not Exception): best-effort accounting must
          // swallow it instead of sealing the writer like a core entry would.
          throw AssertionError("simulated accounting fatal")
        }

        override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
          notices.incrementAndGet()
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          sink.flushBufferedUsageBestEffort("r1")
          sink.onUsage("r1", 1, 2)
          assertTrue("writer must drain past the swallowed fatal", sink.flush(5_000))
        }
      }
      assertEquals(1, notices.get())
      assertFalse("non-VM fatal in a best-effort entry must not seal", sink.isSealed())
      assertFalse(sink.hasDurableFailures())
    } finally {
      exec.shutdown()
    }
  }

  @Test fun bestEffortVmFatalStillSeals() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val delegate = object : SinkProbe() {
        override suspend fun flushBufferedUsageBestEffort(runId: String) {
          // VM-fatal is never swallowed, even on a best-effort entry: the
          // writer must end (seal) instead of pretending the accounting ran.
          // Latched so the flush barrier is provably parked behind this entry
          // before the fatal fires (no send-vs-seal race in the assertion).
          entered.complete(Unit)
          release.await()
          throw OutOfMemoryError("simulated VM fatal")
        }
      }
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          sink.flushBufferedUsageBestEffort("r1")
          entered.await()
          val flushOutcome = async {
            runCatching { sink.flush(2_000) }
          }
          delay(100) // barrier admitted, parked behind r1
          release.complete(Unit)
          val outcome = flushOutcome.await()
          val failure = outcome.exceptionOrNull()
          assertTrue(
            "VM-fatal must propagate to the parked flush, got $outcome",
            failure is OutOfMemoryError,
          )
        }
      }
      assertTrue("VM-fatal must seal the writer", sink.isSealed())
    } finally {
      exec.shutdown()
    }
  }
}
