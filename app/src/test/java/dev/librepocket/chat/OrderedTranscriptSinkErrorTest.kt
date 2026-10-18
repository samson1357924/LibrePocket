package dev.librepocket.chat

import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D15-4: fatal [Error] from the delegate must fail the waiter ack explicitly
 * (never a false durable ack) and seal the writer like teardown. [Exception]
 * (e.g. IOException) keeps the writer alive; [CancellationException] seals.
 */
class OrderedTranscriptSinkErrorTest {

  @Test fun assertionErrorFailsAckVerbatimAndSealsWriter() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val fatal = AssertionError("store invariant broken")
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          throw fatal
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-fatal", "hi")
            fail("fatal Error must not ack as durable")
          } catch (e: AssertionError) {
            // Verbatim propagation (type + message) through the failed ack:
            // CompletableDeferred.await recovers the stack trace, so identity
            // is not preserved — assert the exact payload instead.
            assertTrue("waiter must rethrow the fatal message", e.message == fatal.message)
          }
          assertTrue(sink.interruptedRunIds().contains("run-fatal"))
          assertTrue(sink.pendingRunIds().isEmpty())
          assertTrue(sink.hasDurableFailures())
          assertFalse(sink.flushDurable())
          // Fatal seals like teardown: writer dead, later cores fail fast.
          withTimeout(5_000) { while (!sink.isSealed()) delay(10) }
          assertTrue(sink.isSealed())
          val started = System.currentTimeMillis()
          withTimeout(4_000) {
            try {
              sink.onTurnSucceeded("run-next", "late", hasReasoning = false)
              fail("post-fatal core must fail fast, never succeed")
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
          }
          val elapsed = System.currentTimeMillis() - started
          assertTrue("post-fatal core must fail fast, took ${elapsed}ms", elapsed < 4_000L)
          assertTrue(sink.interruptedRunIds().contains("run-next"))
          assertTrue(sink.pendingRunIds().isEmpty())
          assertTrue("sealed barrier must still drain", sink.flush())
          assertFalse("fatal must keep flushDurable false", sink.flushDurable())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun linkageErrorFailsAckVerbatim() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val fatal = LinkageError("bad link")
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          throw fatal
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-linkage", "hi")
            fail("fatal Error must not ack as durable")
          } catch (e: LinkageError) {
            // Same as above: verbatim type + message, not reference identity.
            assertTrue("waiter must rethrow the fatal message", e.message == fatal.message)
          }
          assertTrue(sink.interruptedRunIds().contains("run-linkage"))
          assertTrue(sink.pendingRunIds().isEmpty())
          assertTrue(sink.hasDurableFailures())
          assertFalse(sink.flushDurable())
          withTimeout(5_000) { while (!sink.isSealed()) delay(10) }
          assertTrue(sink.isSealed())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * D15-4 notice-fatal: a notice entry carries no ack and no runId, so a
   * fatal [Error] from its delegate block still seals the writer (never a
   * silent continue behind the fatal) but poisons nothing — no
   * INTERRUPTED mark, [OrderedTranscriptSink.hasDurableFailures] stays false. A later core still
   * fails fast instead of parking behind the dead writer.
   */
  @Test fun steerNoticeFatalSealsWithoutDurableFailureAndLaterCoreFailsFast() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) = Unit
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
        override suspend fun onSteerQueued(text: String) {
          throw AssertionError("notice invariant broken")
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          // Notices only wait for admission, so this returns while the
          // writer dies asynchronously behind the fatal.
          sink.onSteerQueued("fatal-steer")
          withTimeout(5_000) { while (!sink.isSealed()) delay(10) }
          assertTrue(sink.isSealed())
          assertTrue(sink.pendingRunIds().isEmpty())
          assertTrue(sink.interruptedRunIds().isEmpty())
          assertFalse(sink.hasDurableFailures())
          // Later core fails fast (never parks, never succeeds).
          val started = System.currentTimeMillis()
          withTimeout(4_000) {
            try {
              sink.onTurnStarted("run-next", "hi")
              fail("post-fatal core must fail fast, never succeed")
            } catch (_: CancellationException) {
            } catch (_: ClosedSendChannelException) {
            }
          }
          val elapsed = System.currentTimeMillis() - started
          assertTrue("post-fatal core must fail fast, took ${elapsed}ms", elapsed < 4_000L)
          assertTrue(sink.interruptedRunIds().contains("run-next"))
          assertTrue(sink.pendingRunIds().isEmpty())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun ioExceptionControlWriterStaysAlive() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          if (runId == "run-bad") throw IOException("db down")
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-bad", "hi")
            fail("durable failure must not ack as success")
          } catch (e: IOException) {
            // Explicit failure, writer survives.
          }
          assertTrue(sink.interruptedRunIds().contains("run-bad"))
          assertTrue(sink.hasDurableFailures())
          // Writer alive: a later healthy core still persists.
          sink.onTurnStarted("run-good", "hi")
          assertTrue("writer survives a non-fatal delegate failure", sink.flush())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun cancellationControlWriterSeals() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          throw CancellationException("writer teardown")
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-1", "hi")
            fail("writer teardown must not ack as durable")
          } catch (_: CancellationException) {
          }
          assertTrue(sink.interruptedRunIds().contains("run-1"))
          assertTrue(sink.pendingRunIds().isEmpty())
          withTimeout(5_000) { while (!sink.isSealed()) delay(10) }
          assertTrue(sink.isSealed())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * R23-1 storeFailed guard regression: a genuine store failure for an id
   * (delegate IOException) poisons durability, and a later successful commit
   * reusing the SAME id (first-attempt user row failed, terminal committed)
   * must NOT clear it — the family still dangles. [OrderedTranscriptSink.hasDurableFailures] stays
   * true, [OrderedTranscriptSink.flushDurable] stays false, interrupted still names the id.
   */
  @Test fun storeFailurePoisonSurvivesLaterCommitSameId() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      var userCalls = 0
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          if (runId == "run-sticky" && userCalls++ == 0) throw IOException("user row lost")
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-sticky", "hi")
            fail("durable failure must not ack as success")
          } catch (_: IOException) {
          }
          assertTrue(sink.interruptedRunIds().contains("run-sticky"))
          assertTrue(sink.hasDurableFailures())
          // Same id later commits (terminal reuses the first-attempt id):
          // the success-path clear must honor storeFailed and keep the poison.
          sink.onTurnSucceeded("run-sticky", "late-terminal", hasReasoning = false)
          assertTrue(
            "genuine failure must survive a later same-id commit",
            sink.interruptedRunIds().contains("run-sticky"),
          )
          assertTrue("genuine failure must keep hasDurableFailures true", sink.hasDurableFailures())
          assertTrue("barrier must still drain", sink.flush())
          assertFalse("genuine failure must keep flushDurable false", sink.flushDurable())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Control for the guard above: a drain-timeout mark ([OrderedTranscriptSink.markInterrupted]) for
   * an id lifts when the same id later commits — the row IS durable now.
   * Same flow, opposite expectation: interrupted clean, [OrderedTranscriptSink.hasDurableFailures]
   * false, [OrderedTranscriptSink.flushDurable] true.
   *
   * Seam-level control: this drives the [OrderedTranscriptSink.markInterrupted]
   * seam directly to pin the success-path clear branch (commit-then-clear),
   * not the production shutdown-timeout path. The production path — shutdown
   * drain-timeout marks, then the ghost user row commits late — is covered
   * end-to-end by the ghost group in `ChatSameIdReopenStickyDebtTest` (late
   * commit clears the drain-timeout mark, `hasDurableFailures == false`,
   * while the permanent sticky debt holds). The shutdown snapshot→mark skip
   * branch itself is deliberately unpinned here: parking a commit inside that
   * gap has no existing gate and would need a flaky timing seam. A missed
   * skip still fails safe — it can only leave a timeout mark (never a
   * storeFailed poison; the skip shares `submitTruthLock` with
   * commit-then-clear), which the next same-id commit clears, or which stays
   * poisoned in the fail-closed incomplete-warning direction, never
   * false-clean. The mark→late-commit convergence above is what both tests pin.
   */
  @Test fun timeoutMarkClearsOnLaterCommitSameId() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) = Unit
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          sink.markInterrupted("run-timeout")
          assertTrue(sink.interruptedRunIds().contains("run-timeout"))
          assertTrue(sink.hasDurableFailures())
          sink.onTurnStarted("run-timeout", "hi")
          assertFalse(
            "timeout mark must lift on the proven same-id commit",
            sink.interruptedRunIds().contains("run-timeout"),
          )
          assertFalse(sink.hasDurableFailures())
          assertTrue("cleared timeout mark must drain clean", sink.flushDurable())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Writer-teardown race on the flush barrier (CI flake root, play/github
   * 2026-10-18): a barrier buffered behind a writer that dies afterwards is
   * completed exceptionally by the seal orphan-drain with the teardown CE
   * ("transcript writer dead"). [OrderedTranscriptSink.flush] must map that
   * seal race to NOT drained — `false` with the durable-failure poison set —
   * instead of rethrowing the foreign teardown CE through [flushDurable]
   * (which a controller-level caller treats as a fail-closed Boolean), which
   * previously failed `ErrorTerminalTest.delegateThrowsErrorOnStarted_projectsDurabilityError`
   * non-deterministically.
   *
   * Deterministic interleave (test hook, no timing inference): `afterBarrierSendForTest`
   * parks flush at the exact barrier-is-buffered + chain-clear proof point
   * (post send, post gate release, pre barrier-await); the teardown is only
   * then armed, so its internal flush barrier await (200ms budget) and
   * channel close provably start AFTER the barrier is buffered; releasing
   * the hook finishes flush's await. The parked writer is then killed by the
   * teardown scope cancel into the writer finally seal + orphan drain,
   * failing THIS barrier with the teardown CE.
   *
   * All goroutines run off the runBlocking event loop (feeder/flush on
   * Dispatchers.Default, writer on the single-thread sink executor) and every
   * test-side wait is a suspend await — no thread-blocking latch can starve
   * the runBlocking loop.
   */
  @Test fun flushBarrierWriterTeardownRace_mapsToFalseNotThrowsCe() {
    val exec = Executors.newSingleThreadExecutor()
    var scope: CoroutineScope? = null
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val writerParked = CompletableDeferred<Unit>()
      val barrierBuffered = CompletableDeferred<Unit>()
      val releaseBarrier = CompletableDeferred<Unit>()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          writerParked.complete(Unit)
          awaitCancellation() // parked inside the writer; resumed only by teardown cancel
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      sink.afterBarrierSendForTest = {
        barrierBuffered.complete(Unit)
        releaseBarrier.await() // hold flush at the barrier-is-buffered proof point
      }
      runBlocking {
        withTimeout(20_000) {
          scope.launch { runCatching { sink.onTurnStarted("run-park", "hi") } }
          writerParked.await() // writer consumed the entry; parked in the delegate
          val drained = scope.async { sink.flush(10_000) }
          barrierBuffered.await() // barrier send returned, chain clear, writer still parked
          // Seal armed only after the barrier is provably buffered. The
          // teardown's own flush then runs, times out on its barrier (200ms),
          // closes the channel, times out joining the parked writer, and
          // finally cancels the scope → seal + orphan drain → THIS barrier
          // fails with the teardown CE.
          val teardown = sink.shutdown(drainTimeoutMs = 200)
          releaseBarrier.complete(Unit)
          val outcome = runCatching { drained.await() }
          assertFalse(
            "teardown-raced barrier must report NOT drained, got ${outcome.getOrNull()}",
            outcome.getOrNull() == true,
          )
          assertTrue(
            "flush must swallow the writer-teardown CE, not rethrow it, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() == null,
          )
          assertTrue(sink.isSealed())
          assertTrue("seal must poison the parked core write", sink.hasDurableFailures())
          assertTrue(sink.interruptedRunIds().contains("run-park"))
          assertFalse("flushDurable on a dead writer must stay fail-closed", sink.flushDurable())
          teardown?.join()
        }
      }
    } finally {
      scope?.cancel()
      exec.shutdown()
    }
  }

  /**
   * Control for the seal-race mapping above: caller cancellation while the
   * flush barrier is parked behind a LIVE writer must still propagate as a
   * [CancellationException] (never swallowed into a `false`), and the writer
   * must stay unsealed.
   */
  @Test fun flushCallerCancellation_stillPropagates_whenWriterAlive() {
    val exec = Executors.newSingleThreadExecutor()
    var scope: CoroutineScope? = null
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val writerParked = CompletableDeferred<Unit>()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          writerParked.complete(Unit)
          awaitCancellation() // parked forever; never resumed in this control
        }
        override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
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
      val sink = OrderedTranscriptSink(delegate, dispatcher, capacity = 8)
      runBlocking {
        withTimeout(20_000) {
          scope.launch { runCatching { sink.onTurnStarted("run-park-2", "hi") } }
          writerParked.await()
          val flushed = scope.async { sink.flush(30_000) } // long timeout: the cancel below, never timeout
          // Guard budget tight to 2s: an unexpected seal would surface within
          // ~10ms ticks, so a long budget only taxes the GOOD path (the loop
          // always exhausts when the writer stays alive, as required here).
          withTimeoutOrNull(2_000) { while (!sink.isSealed()) delay(10) }
            ?.let { fail("writer must stay alive in this control") }
          assertFalse("writer must be unsealed for the control", sink.isSealed())
          flushed.cancel()
          val outcome = runCatching { withTimeout(5_000) { flushed.await() } }
          assertTrue(
            "caller cancellation must propagate as CE, got ${outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is CancellationException,
          )
          sink.shutdown(drainTimeoutMs = 200)?.join()
        }
      }
    } finally {
      scope?.cancel()
      exec.shutdown()
    }
  }
}
