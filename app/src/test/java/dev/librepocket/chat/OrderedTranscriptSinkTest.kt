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
}
