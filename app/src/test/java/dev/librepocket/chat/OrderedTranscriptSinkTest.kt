package dev.librepocket.chat

import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
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
  override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
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
   * an offerSteer is parked in overflowChain. Subsequent writeCore and
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
          // 3. Parked steer: buffer full, parked in overflowChain
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
}
