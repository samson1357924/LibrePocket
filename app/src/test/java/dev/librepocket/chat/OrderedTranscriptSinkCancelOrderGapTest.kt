package dev.librepocket.chat

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private open class GapProbe : TranscriptSink {
  val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())
  override suspend fun onTurnStarted(runId: String, text: String) {
    recorded.add("core:$runId")
  }
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
    recorded.add("notice:$runId")
  }
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
 * D15-1 gap coverage (G1/G2/G4/G5, deterministic via G3 seams).
 *
 * Every test parks A in `beforeSend` (ticket 0) and observes park points via
 * [OrderedTranscriptSink.onPrevAwaitEnteredForTest] /
 * [OrderedTranscriptSink.onFlushTailAwaitEnteredForTest] — no delay inference
 * for arrival. The short post-abandon drain windows that remain are
 * negative-control overtake windows (a buggy successor would record there),
 * not arrival inference.
 */
class OrderedTranscriptSinkCancelOrderGapTest {

  private suspend fun awaitCond(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
      if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
      delay(10)
    }
  }

  /**
   * G1: offerSteer queued abandon.
   *
   * A (core, ticket 0) parks in beforeSend. offerSteer(B) reserves queued
   * (ticket 1, prev = A gate) then its reserve hook throws pre-send — the
   * slot defers to the forwarder. Successor core C (ticket 2) and tail steer
   * D (ticket 3, exercises the fast-path gate: prev != null so queued) must
   * both stay behind A; after release the drain is A,C,D with no pending.
   */
  @Test fun offerSteerQueuedAbandonKeepsOrderAndTail() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = GapProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val throwOnce = AtomicBoolean(true)
      sink.onAdmissionReservedForTest = { ticket ->
        reserved.add(ticket)
        if (ticket == 1L && throwOnce.getAndSet(false)) {
          throw IllegalStateException("steer reserve boom")
        }
      }
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      sink.beforeSendForTest = { ticket ->
        if (ticket == 0L) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          awaitCond("A reserved") { reserved.size >= 1 }
          // B steer reserves queued (ticket 1) then abandons pre-send via
          // the reserve hook (writerScope-job/hook-throw family: the send is
          // never invoked, so the gate must defer, never complete past A).
          try {
            sink.offerSteer("B-steer")
            fail("steer reserve hook throw must propagate")
          } catch (e: IllegalStateException) {
            assertTrue(e.message == "steer reserve boom")
          }
          awaitCond("B reservation observed") { reserved.size >= 2 }
          // Successor core C behind the abandoned steer gate.
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          awaitCond("C reached await") { entered.contains(2L) }
          // Tail steer D arrives after the abandon: its link must be queued
          // behind C (prev != null, no fast-path), so it parks as well.
          sink.offerSteer("D-steer")
          awaitCond("D reserved") { reserved.size >= 4 }
          awaitCond("D reached await") { entered.contains(3L) }
          // Overtake window: a buggy immediate-complete / tail-clear would
          // have let C or D record past still-parked A by now.
          delay(200)
          assertTrue(
            "abandoned steer must not let successor overtake A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          assertTrue("drain must succeed", sink.flush(10_000))
          assertEquals(
            listOf("core:A-run", "core:C-run", "steer:D-steer"),
            delegate.recorded,
          )
          assertTrue("pending must be empty: ${sink.pendingRunIds()}", sink.pendingRunIds().isEmpty())
          assertFalse("no durable failure", sink.hasDurableFailures())
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

  /**
   * G2 (timeout): flush itself abandons in the tail await.
   *
   * A parks; async flush(200) enters [awaitAdmissionTail] (observed via the
   * tail seam) and times out before ever reserving a barrier ticket, so no
   * gate exists to corrupt. Newcomer D must still land behind A and the
   * chain must drain afterwards (no stuck gate).
   */
  @Test fun flushTimeoutAbandonKeepsOrder() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = GapProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val tailEntered = CompletableDeferred<Unit>()
      sink.onFlushTailAwaitEnteredForTest = { tailEntered.complete(Unit) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      sink.beforeSendForTest = { ticket ->
        if (ticket == 0L) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val flushing = async { sink.flush(200L) }
          tailEntered.await()
          assertTrue("flush must park behind A, not drain past it", !flushing.isCompleted)
          assertFalse("timed-out flush must report false", flushing.await())
          // Newcomer D after the timeout abandon still links behind A.
          val dJob = async { sink.onTurnStarted("D-run", "d") }
          awaitCond("D reserved") { reserved.size >= 2 }
          awaitCond("D reached await") { entered.contains(1L) }
          delay(150)
          assertTrue(
            "post-timeout newcomer must not fast-path past A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          dJob.await()
          assertTrue("chain must not stick after timeout abandon", sink.flush(10_000))
          assertEquals(listOf("core:A-run", "core:D-run"), delegate.recorded)
          assertTrue(sink.pendingRunIds().isEmpty())
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.onPrevAwaitEnteredForTest = null
      sink.onFlushTailAwaitEnteredForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * G2 (cancel): flushing.cancel() while parked in the tail await.
   *
   * Same shape as the timeout version but the waiter is cancelled instead of
   * timing out: the CE propagates, no barrier ticket was ever reserved, and
   * the later newcomer still orders behind A with a live chain.
   */
  @Test fun flushCancelAbandonKeepsOrder() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = GapProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val tailEntered = CompletableDeferred<Unit>()
      sink.onFlushTailAwaitEnteredForTest = { tailEntered.complete(Unit) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      sink.beforeSendForTest = { ticket ->
        if (ticket == 0L) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          val flushing = async { sink.flush(10_000) }
          tailEntered.await()
          assertTrue("flush must park behind A", !flushing.isCompleted)
          flushing.cancel()
          try {
            flushing.await()
            fail("cancelled flush must throw CancellationException")
          } catch (_: CancellationException) {
          }
          val dJob = async { sink.onTurnStarted("D-run", "d") }
          awaitCond("D reserved") { reserved.size >= 2 }
          awaitCond("D reached await") { entered.contains(1L) }
          delay(150)
          assertTrue(
            "post-cancel newcomer must not fast-path past A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          dJob.await()
          assertTrue("chain must not stick after flush cancel", sink.flush(10_000))
          assertEquals(listOf("core:A-run", "core:D-run"), delegate.recorded)
          assertTrue(sink.pendingRunIds().isEmpty())
        }
      }
      sink.onAdmissionReservedForTest = null
      sink.onPrevAwaitEnteredForTest = null
      sink.onFlushTailAwaitEnteredForTest = null
      sink.beforeSendForTest = null
      runBlocking { withTimeout(10_000) { sink.shutdown()?.join() } }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * G4: consecutive double abandon.
   *
   * A parks; B (ticket 1) and C (ticket 2) both reserve, both reach their
   * park points (observed, not inferred), both cancel pre-send — a two-hop
   * forwarder chain (C -> B gate -> A). Newcomer D (ticket 3) links behind
   * it; releasing A drains A,D in order with empty pending and no durable
   * failure (both cancels proved never-admitted).
   */
  @Test fun doubleAbandonKeepsOrder() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = GapProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      sink.onAdmissionReservedForTest = { reserved.add(it) }
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      sink.beforeSendForTest = { ticket ->
        if (ticket == 0L) {
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
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          awaitCond("C reached await") { entered.contains(2L) }
          bJob.cancel()
          try {
            bJob.await()
            fail("cancelled B must throw")
          } catch (_: CancellationException) {
          }
          cJob.cancel()
          try {
            cJob.await()
            fail("cancelled C must throw")
          } catch (_: CancellationException) {
          }
          val dJob = async { sink.onTurnStarted("D-run", "d") }
          awaitCond("D reserved") { reserved.size >= 4 }
          awaitCond("D reached await") { entered.contains(3L) }
          delay(200)
          assertTrue(
            "double abandon must not let D overtake A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          dJob.await()
          assertTrue("drain must succeed", sink.flush(10_000))
          assertEquals(listOf("core:A-run", "core:D-run"), delegate.recorded)
          assertTrue("pending must be empty: ${sink.pendingRunIds()}", sink.pendingRunIds().isEmpty())
          assertFalse("abandons prove never-admitted, never durable-failed", sink.hasDurableFailures())
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

  /**
   * G5 (minimal): notice hook-throw in the middle.
   *
   * A (core, ticket 0) parks; B is a notice (ticket 1) whose reserve hook
   * throws pre-send, so its gate defers behind A. Successor core C (ticket 2)
   * must stay behind A; the drain is A,C with B never persisted.
   */
  @Test fun noticeHookThrowMiddleKeepsOrder() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = GapProbe()
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      val reserved: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val entered: MutableList<Long> = Collections.synchronizedList(mutableListOf())
      val throwOnce = AtomicBoolean(true)
      sink.onAdmissionReservedForTest = { ticket ->
        reserved.add(ticket)
        if (ticket == 1L && throwOnce.getAndSet(false)) {
          throw IllegalStateException("notice reserve boom")
        }
      }
      sink.onPrevAwaitEnteredForTest = { entered.add(it) }
      val aEntered = CompletableDeferred<Unit>()
      val aRelease = CompletableDeferred<Unit>()
      sink.beforeSendForTest = { ticket ->
        if (ticket == 0L) {
          aEntered.complete(Unit)
          aRelease.await()
        }
      }
      runBlocking {
        withTimeout(15_000) {
          val aJob = async { sink.onTurnStarted("A-run", "a") }
          aEntered.await()
          awaitCond("A reserved") { reserved.size >= 1 }
          try {
            sink.onTurnRetried("N-run", 1, 3, 0L)
            fail("notice reserve hook throw must propagate")
          } catch (e: IllegalStateException) {
            assertTrue(e.message == "notice reserve boom")
          }
          awaitCond("B reservation observed") { reserved.size >= 2 }
          val cJob = async { sink.onTurnStarted("C-run", "c") }
          awaitCond("C reserved") { reserved.size >= 3 }
          awaitCond("C reached await") { entered.contains(2L) }
          delay(200)
          assertTrue(
            "notice abandon must not let C overtake A: ${delegate.recorded}",
            delegate.recorded.isEmpty(),
          )
          aRelease.complete(Unit)
          aJob.await()
          cJob.await()
          assertTrue("drain must succeed", sink.flush(10_000))
          assertEquals(listOf("core:A-run", "core:C-run"), delegate.recorded)
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
}
