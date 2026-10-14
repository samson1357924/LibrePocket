package dev.librepocket.chat

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private open class CancelOrderProbe : TranscriptSink {
  val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())
  override suspend fun onTurnStarted(runId: String, text: String) {
    recorded.add("core:$runId")
  }
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
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
    recorded.add("notice:$runId")
  }
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = onTurnCancelled(runId, partialText, hasReasoning)
  override suspend fun onSteerQueued(text: String) {
    recorded.add("steer:$text")
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
 * D15-1 deferred forwarding gate regressions (scheme 1, minimal semantic fix).
 *
 * Pre-fix, every pre-send (!sendInvoked) abandon completed its gate
 * immediately and CAS-cleared the tail: a cancelled middle slot woke its
 * successor past a still-waiting predecessor, and a cancelled tail let a
 * newcomer fast-path past everyone (flush barrier included). Post-fix the
 * caller rethrows immediately but the gate completion is delegated to a
 * writerScope forwarder awaiting the predecessor, with the CAS clear riding
 * along — the successor stays parked behind the predecessor.
 */
class OrderedTranscriptSinkCancelOrderTest {

  private suspend fun awaitCond(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
      delay(10)
    }
  }

  @Test fun middleCoreCancelDoesNotOvertake() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      // G3: deterministic park-point signals (no delay inference for arrival).
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnStarted("B-run", "b") }
          awaitCond("B reserved") { reserved.size >= 2 }
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          // G3: B/C reached their park points (deterministic), not inferred.
          awaitCond("B reached await") { entered.contains(1L) }
          awaitCond("C reached await") { entered.contains(2L) }
          // B abandons pre-send while A is still parked in its gap.
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled middle core must throw CancellationException")
          } catch (_: CancellationException) {
          }
          // Post-fix: C stays parked behind A via B's forwarder. Pre-fix C
          // would have been woken immediately and recorded before A.
          delay(300)
          assertTrue(
            "cancelled middle must not let successor overtake still-waiting A: ${delegate.recorded}",
            delegate.recorded.none { it == "core:C-run" },
          )
          assertTrue(
            "A still parked, nothing may have drained: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          assertTrue("drain must succeed", sink.flush())
          assertEquals(listOf("core:A-run", "core:C-run"), delegate.recorded)
          // B proved never-admitted: no pending, no durable failure.
          assertTrue(sink.pendingRunIds().isEmpty())
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.onPrevAwaitEnteredForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun middleNoticeCancelDoesNotOvertake() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnRetried("N-run", 1, 3, 0L) }
          awaitCond("B reserved") { reserved.size >= 2 }
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          delay(300)
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled middle notice must throw CancellationException")
          } catch (_: CancellationException) {
          }
          delay(300)
          assertTrue(
            "cancelled middle notice must not let successor overtake A: ${delegate.recorded}",
            delegate.recorded.none { it == "core:C-run" },
          )
          assertTrue(delegate.recorded.isEmpty())
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          assertTrue("drain must succeed", sink.flush())
          assertEquals(listOf("core:A-run", "core:C-run"), delegate.recorded)
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun tailCancelNewcomerDoesNotOvertake() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      // G3: deterministic park-point signals (no delay inference for arrival).
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnStarted("B-run", "b") }
          awaitCond("B reserved") { reserved.size >= 2 }
          awaitCond("B reached await") { entered.contains(1L) }
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled tail must throw")
          } catch (_: CancellationException) {
          }
          // Newcomer D arrives after the tail cancel while A is still parked.
          // Pre-fix the tail CAS cleared to null and D fast-pathed past A;
          // post-fix D links behind the abandoned B gate and waits for A.
          val dJob = async { sink.onTurnStarted("D-run", "d") }
          awaitCond("D reserved") { reserved.size >= 3 }
          awaitCond("D reached await") { entered.contains(2L) }
          delay(300)
          assertTrue(
            "tail-cancel newcomer must not fast-path past A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          dJob.await()
          assertTrue("drain must succeed", sink.flush())
          assertEquals(listOf("core:A-run", "core:D-run"), delegate.recorded)
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.onPrevAwaitEnteredForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun threeLayerMixedPredecessorsPreserveOrder() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      // G3: deterministic park-point signals (no delay inference for arrival).
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          // A core parked, B notice waiting, C core waiting (mixed chain).
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnRetried("N-run", 1, 3, 0L) }
          awaitCond("B reserved") { reserved.size >= 2 }
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          awaitCond("B reached await") { entered.contains(1L) }
          awaitCond("C reached await") { entered.contains(2L) }
          // Cancel the middle notice: C must still wait for A via B's
          // forwarder (two-hop forwarding once D joins below).
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled middle notice must throw")
          } catch (_: CancellationException) {
          }
          // Newcomer D (notice) joins behind C while A still parked.
          val dJob = async { sink.onTurnRetried("D-run", 1, 3, 0L) }
          awaitCond("D reserved") { reserved.size >= 4 }
          awaitCond("D reached await") { entered.contains(3L) }
          delay(300)
          assertTrue(
            "three-layer chain must not overtake A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          dJob.await()
          assertTrue("drain must succeed", sink.flush())
          assertEquals(
            listOf("core:A-run", "core:C-run", "notice:D-run"),
            delegate.recorded,
          )
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.onPrevAwaitEnteredForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun flushDoesNotOvertakeCancelledTail() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnStarted("B-run", "b") }
          awaitCond("B reserved") { reserved.size >= 2 }
          delay(200)
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled tail must throw")
          } catch (_: CancellationException) {
          }
          // Flush barrier reserved after the cancelled tail must wait for A
          // (late-ticket barrier behind the forwarder), never report a drain
          // that missed A.
          val flushing = async { sink.flush(10_000) }
          delay(300)
          assertTrue(
            "flush must wait for still-waiting A, not overtake via cancelled tail",
            !flushing.isCompleted,
          )
          assertTrue(
            "flush must not falsely drain before A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          assertTrue("flush must drain A once released", flushing.await())
          assertEquals(listOf("core:A-run"), delegate.recorded)
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun sealShutdownInterleaveDoesNotStuck() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnStarted("B-run", "b") }
          // Let B park in the predecessor await, then cancel it so its gate
          // is owned by a forwarder awaiting A.
          delay(300)
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled tail must throw")
          } catch (_: CancellationException) {
          }
          // Shutdown while the forwarder is still pending on A: the settle +
          // drain are bounded, the forwarder still forwards A, and nothing
          // sticks forever.
          val shutdownJob = sink.shutdown(drainTimeoutMs = 2_000)
          // Release A so the forwarder chain can make progress; the shutdown
          // drain may already have timed out, but neither path may hang.
          aRelease.complete(Unit)
          try {
            aJob.await()
          } catch (_: CancellationException) {
            // Seal race after the bounded drain is an explicit failure.
          } catch (_: Exception) {
          }
          withTimeout(10_000) { shutdownJob?.join() }
          assertTrue("shutdown must seal", sink.isSealed())
          // Late cores fail fast and bounded behind the seal.
          withTimeout(4_000) {
            try {
              sink.onTurnStarted("late-run", "late")
              fail("post-seal core must fail fast")
            } catch (_: CancellationException) {
            } catch (_: ClosedSendChannelException) {
            } catch (_: IllegalStateException) {
            }
          }
        }
      }
      sink.beforeSendForTest = null
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.shutdown()?.join()
          } catch (_: Exception) {
          }
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun forwarderDrainsThenShutdownSeals() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstHook = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstHook.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val bJob = async { sink.onTurnStarted("B-run", "b") }
          delay(300)
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled tail must throw")
          } catch (_: CancellationException) {
          }
          // A newcomer joins behind the abandoned gate while A is parked.
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          delay(300)
          assertTrue(
            "successor must stay parked while A is parked: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          // Release A: the forwarder completes normally with Unit (never
          // exceptionally), C is woken, and the chain never sticks even
          // though the scope stays alive for the seal below.
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          assertTrue("flush must drain", sink.flush())
          assertEquals(listOf("core:A-run", "core:C-run"), delegate.recorded)
          // Seal fallback: after the chain drained, shutdown still seals
          // boundedly and later cores fail fast.
          sink.shutdown()?.join()
          assertTrue(sink.isSealed())
          assertTrue("forwarder chain must not stick the barrier", sink.flush(2_000))
        }
      }
      sink.beforeSendForTest = null
    } finally {
      exec.shutdown()
    }
  }

  @Test fun hookThrowTakesDeferredPath() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = CancelOrderProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      // Reserve-hook throw abandons B pre-send (never awaited): B's gate must
      // defer to A via the forwarder, so C stays behind A.
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val throwOnce = AtomicBoolean(true)
      sink.onAdmissionReservedForTest = { ticket ->
        reserved.add(ticket)
        if (ticket == 1L && throwOnce.getAndSet(false)) {
          throw IllegalStateException("reserve hook boom")
        }
      }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      val firstSend = AtomicBoolean(true)
      sink.beforeSendForTest = {
        if (firstSend.getAndSet(false)) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          // Wait until A reserved (ticket 0) and parked in its gap.
          awaitCond("A parked") { reserved.size >= 1 }
          aEntered.await()
          // B reserves (ticket 1) and its reserve hook throws.
          try {
            sink.onTurnStarted("B-run", "b")
            fail("reserve hook throw must propagate")
          } catch (e: IllegalStateException) {
            assertTrue(e.message == "reserve hook boom")
          }
          awaitCond("B reservation observed") { reserved.size >= 2 }
          // C reserves behind the abandoned B gate.
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          delay(300)
          assertTrue(
            "reserve-hook abandon must not let C overtake A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          assertTrue("drain must succeed", sink.flush())
          assertEquals(listOf("core:A-run", "core:C-run"), delegate.recorded)
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }
}
