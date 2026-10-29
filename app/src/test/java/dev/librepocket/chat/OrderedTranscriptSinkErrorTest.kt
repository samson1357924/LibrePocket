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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
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
          // Fatal seals like teardown: wait for writer to seal before asserting post-seal flush.
          withTimeout(5_000) { while (!sink.isSealed()) delay(10) }
          assertTrue(sink.isSealed())
          assertFalse(sink.flushDurable())
          val started = System.currentTimeMillis()
          withTimeout(4_000) {
            try {
              sink.onTurnSucceeded("run-next", "late", hasReasoning = false)
              fail("post-fatal core must fail fast, never succeed")
            } catch (_: CancellationException) {
            }
          }
          val elapsed = System.currentTimeMillis() - started
          // Fail-fast hang pin: the bounded call's blanket CancellationException
          // catch cannot distinguish a timed-out park from a real fail-fast
          // (a withTimeout TCE subclasses CE), so this wall check is the direct
          // signal. 1_000ms is far above the healthy ~ms fail-fast path while a
          // path that actually waits its budget lands ~4_000ms and fails here;
          // the old < 4_000 ceiling only restated the surrounding withTimeout
          // and widened the CI-pause flake window for no coverage gain.
          assertTrue("post-fatal core must fail fast, took ${elapsed}ms", elapsed < 1_000L)
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
          // Fatal seals like teardown: wait for writer to seal before asserting post-seal flush.
          withTimeout(5_000) { while (!sink.isSealed()) delay(10) }
          assertTrue(sink.isSealed())
          assertFalse(sink.flushDurable())
          // Fatal seals like teardown: writer dead, later cores fail fast
          // (same post-fatal control as the sibling test above).
          val started = System.currentTimeMillis()
          withTimeout(4_000) {
            try {
              sink.onTurnSucceeded("run-next", "late", hasReasoning = false)
              fail("post-fatal core must fail fast, never succeed")
            } catch (_: CancellationException) {
            }
          }
          val elapsed = System.currentTimeMillis() - started
          // Same fail-fast hang pin as the sibling above: the blanket CE catch
          // cannot distinguish a timed-out park (TCE subclasses CE), so this
          // wall check is the direct signal; 1_000ms keeps the pin with a
          // CI-pause-safe margin far below the ~4_000ms budget-park shape.
          assertTrue("post-fatal core must fail fast, took ${elapsed}ms", elapsed < 1_000L)
          assertTrue(sink.interruptedRunIds().contains("run-next"))
          assertTrue(sink.pendingRunIds().isEmpty())
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
            }
          }
          val elapsed = System.currentTimeMillis() - started
          // Same fail-fast hang pin as the sibling above: the blanket CE catch
          // cannot distinguish a timed-out park (TCE subclasses CE), so this
          // wall check is the direct signal; 1_000ms keeps the pin with a
          // CI-pause-safe margin far below the ~4_000ms budget-park shape.
          assertTrue("post-fatal core must fail fast, took ${elapsed}ms", elapsed < 1_000L)
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
   * All coroutines run off the runBlocking event loop (feeder/flush on
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

  /**
   * C-P1 settle-VM-fatal regression (S3-F1 fail-loud): the VM-fatal rethrow
   * inserted into [OrderedTranscriptSink.shutdown]'s settle catch was a
   * behaviour-level change with no regression test. This pins the rethrow
   * side: an OOM thrown during settle escapes the teardown job with the
   * injected type + payload, and the drain is NOT attempted — no seal, no
   * interrupt marks, the admitted core host stays pending (a regressed
   * swallow would run the drain instead: seal, orphan marks + failed acks
   * inside the same injected budget — the non-VM sibling below pins that
   * branch).
   *
   * Injection point: the production `settle` parameter itself. Production
   * composes the host-settle lambda through this exact parameter, and
   * shutdown runs it inside the production
   * `withContext(NonCancellable) { withTimeoutOrNull(drainTimeoutMs) {...} }`
   * block whose catch performs the VM-fatal rethrow — so the injected lambda
   * exercises the production catch with no reflection and no test-only
   * seam. The in-flight host is parked in its delegate (entered/gate), the
   * same deterministic parked-orphan shape the seal group uses.
   *
   * Note on completion capture: the teardown job fails exceptionally with
   * the injected OOM, but on kotlinx-coroutines 1.10.1 `Job.join()` returns
   * normally for a failed child (fast and slow path alike, and the parent is
   * a supervisor) — there is no rethrow for the test to catch — so the OOM
   * is pinned as the job's completion CAUSE via `invokeOnCompletion`; had
   * the job already completed at registration, the handler would fire
   * synchronously, so the capture cannot race `shutdown`'s launch.
   * Note on test output: writerScope is a top-level SupervisorJob scope, so
   * the failed child's OOM is never resumed into a parent and surfaces once
   * through the global handler (stderr) — the noise is expected, not a
   * failure.
   */
  @Test fun shutdownSettleVMFatal_rethrowsOutOfMemoryErrorAndAbortsDrain() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          entered.complete(Unit)
          gate.await() // parked in the writer: the in-flight host a working settle would have waited on
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
        val orphan = async { sink.onTurnStarted("orphan-1", "hi") }
        try {
          withTimeout(20_000) {
            entered.await() // writer consumed the entry; parked in the delegate
            val job = checkNotNull(sink.shutdown(
              drainTimeoutMs = 100L,
              settle = { throw OutOfMemoryError("simulated VM fatal in settle") },
            )) { "first shutdown must return the teardown job" }
            val causeSignal = CompletableDeferred<Throwable?>()
            job.invokeOnCompletion { causeSignal.complete(it) }
            val fatal = causeSignal.await()
            assertTrue(
              "settle VM-fatal must fail the teardown job exceptionally, got $fatal",
              fatal is OutOfMemoryError,
            )
            assertTrue(
              "completion cause must keep the injected payload type + message, got $fatal",
              fatal?.message == "simulated VM fatal in settle",
            )
            // Teardown aborts BEFORE the drain: none of the drain's side
            // effects ran (the non-VM sibling below pins the swallow branch,
            // where the same budget marks + fails + seals).
            assertFalse("aborted teardown must not seal", sink.isSealed())
            assertTrue("aborted drain must not mark orphans", sink.interruptedRunIds().isEmpty())
            assertFalse("aborted drain must not poison durability", sink.hasDurableFailures())
            assertTrue("orphan must still be admitted-pending", sink.isCorePending("orphan-1"))
            assertFalse("writer must still be alive", sink.isWriterCompleted())
          }
        } finally {
          // Idempotent unblock, also on a failed assert, so the executor
          // cannot leak a parked writer.
          gate.complete(Unit)
        }
        // Writer survived the aborted teardown: the released host still
        // commits (no discard, no seal, no orphan mark).
        withTimeout(5_000) { while (sink.isCorePending("orphan-1")) delay(10) }
        orphan.join()
        assertTrue("writer must stay alive after the aborted teardown", sink.coreCommitCount("orphan-1") >= 1)
        assertTrue("a committed orphan must leave no marks", sink.interruptedRunIds().isEmpty())
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * Control for the abort above: the ordinary [Error] tier from settle
   * (AssertionError here — OOM/StackOverflowError/ThreadDeath would take the
   * rethrow branch) is INTENTIONALLY swallowed by [OrderedTranscriptSink.shutdown]'s
   * settle catch (D15-4 teardown fail-closed), and the drain still runs to
   * completion: the admitted core is marked a drain-timeout orphan, its ack
   * fails explicitly (never a false durable success), and the teardown seals.
   * Same settled output as a clean settle against a stalled store.
   *
   * Injection point: the same production `settle` parameter as the sibling,
   * landing in the same production settle catch — only the catch tier differs.
   *
   * Note on completion capture: the trailing `writerScope.cancel()` at the
   * end of teardown self-cancels the coroutine running the teardown (a
   * child of that scope), so the job's completion CAUSE is ALWAYS the
   * teardown's own CancellationException — never null. Since `Job.join()`
   * returns normally even for a failed child (coroutines 1.10.1), a plain
   * join can never pin this either way; the CAUSE captured via
   * `invokeOnCompletion` is what proves the swallow — the settled non-VM
   * Error was swallowed if and only if the cause is that
   * CancellationException and NOT the AssertionError (a regression that
   * rethrew it would land the AssertionError as the completion cause and
   * fail this assertion). Same no-race mechanics as the sibling: a job
   * already completed at registration fires the handler synchronously.
   */
  @Test fun shutdownSettleNonVMFatalError_isSwallowedAndDrainStillSeals() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          entered.complete(Unit)
          gate.await() // parked in the writer: the drain must mark + fail it as an orphan
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
        val orphan = async { sink.onTurnStarted("orphan-1", "hi") }
        try {
          withTimeout(20_000) {
            entered.await()
            val job = checkNotNull(sink.shutdown(
              drainTimeoutMs = 200L,
              settle = { throw AssertionError("simulated settle fatal") },
            )) { "first shutdown must return the teardown job" }
            val causeSignal = CompletableDeferred<Throwable?>()
            job.invokeOnCompletion { causeSignal.complete(it) }
            val cause = causeSignal.await()
            assertTrue(
              "non-VM settle Error must be swallowed (completion cause is the trailing self-cancel CE, not the AssertionError), got $cause",
              cause is CancellationException,
            )
            try {
              withTimeout(4_000) { orphan.await() }
              fail("drain-timeout orphan must throw, never hang or succeed")
            } catch (_: CancellationException) {
              // Explicit bounded failure: the drain ran.
            } catch (e: Exception) {
              fail("orphan must fail with CancellationException, got $e")
            }
            assertEquals(
              "drain must mark the timed-out orphan",
              listOf("orphan-1"),
              sink.interruptedRunIds(),
            )
            assertTrue(sink.pendingRunIds().isEmpty())
            assertTrue("drain must still seal after the swallowed Error", sink.isSealed())
            // Tier-agnostic commit discriminator: a drain-timeout orphan reads
            // UNcommitted whether its mark is the provisional tier
            // ([interrupted] only, as shaped here) or the [storeFailed]
            // poison tier — both mean nothing wrote, both leave the count 0.
            // This shape cannot distinguish the two tiers; exposing a
            // storeFailed query seam was deliberately NOT done — one boolean
            // accessor would expose teardown internals for a single shape's
            // discriminator (house minimal-seam practice).
            assertFalse("drained orphan must read uncommitted", sink.isCoreCommitted("orphan-1"))
          }
        } finally {
          // Idempotent unblock, also on a failed assert.
          gate.complete(Unit)
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * F5 pin: [OrderedTranscriptSink.shutdown]'s FLUSH-catch swallow lane
   * (`catch (_: Throwable) { false }` around the internal bounded
   * [OrderedTranscriptSink.flush]). Injection point: the production
   * [OrderedTranscriptSink.afterBarrierSendForTest] hook throwing a plain
   * (non-VM) [Error] while the writer is still alive — the barrier send
   * itself succeeds (channel open), so the detached hook is reached inside
   * the teardown's own flush, there is no catch inside flush that covers a
   * non-CE fault, and the Error escapes [OrderedTranscriptSink.flush]
   * verbatim (fail-loud shape documented in flush's KDoc) into exactly this
   * teardown swallow lane: `flushed = false`, the drain still runs (nothing
   * admitted here, so no orphans, no marks), the teardown seals, and
   * durability truth stays honest (`flushed == false` drove the fail-closed
   * path; no core write ever owed an ack, so nothing was fabricated).
   *
   * Capture style mirrors the two settle VM-fatal siblings above:
   * `invokeOnCompletion` + `CompletableDeferred<Throwable?>`, with the same
   * no-race mechanics (a job already completed at registration fires the
   * handler synchronously). The teardown's completion CAUSE is the trailing
   * `writerScope.cancel()` self-cancel CE — the deterministic always-shape
   * the non-VM settle sibling documents — so asserting the cause is that CE
   * (never the AssertionError) is exactly the swallow proof; no further
   * cause details are asserted (payload is not carried through the swallow).
   * Hook faults are test-only: the hook is null in production.
   */
  @Test fun shutdownFlushCatchSwallowsNonVMFatalError_stillDrainsAndSeals() {
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
      sink.afterBarrierSendForTest = { throw AssertionError("simulated flush fatal") }
      runBlocking {
        withTimeout(4_000) {
          val job = checkNotNull(sink.shutdown()) { "first shutdown must return the teardown job" }
          val causeSignal = CompletableDeferred<Throwable?>()
          job.invokeOnCompletion { causeSignal.complete(it) }
          val cause = causeSignal.await()
          assertTrue(
            "flush Error must land in the swallow lane (completion cause is the trailing self-cancel CE, not the AssertionError), got $cause",
            cause is CancellationException,
          )
          // The swallow never aborts the teardown: drain + seal still happen.
          assertTrue("swallowed flush Error must still drain and seal", sink.isSealed())
          assertTrue("no core write admitted, so no orphan marks", sink.interruptedRunIds().isEmpty())
          assertTrue(sink.pendingRunIds().isEmpty())
          assertFalse("the swallow must never fabricate durability truth", sink.hasDurableFailures())
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  /**
   * F2 contract pin (deterministic seam-level control in the
   * [timeoutMarkClearsOnLaterCommitSameId] style): a runId resolved as a
   * proven-undelivered ghost OWNS the resolved poison slot, and a follow-up
   * genuine store failure for that SAME id records NOTHING —
   * [OrderedTranscriptSink.markInterrupted] and
   * [OrderedTranscriptSink.markStoreFailure] share the
   * [OrderedTranscriptSink.isResolvedUndelivered] early-return, so
   * [OrderedTranscriptSink.hasDurableFailures] reads false-clean afterwards.
   * This is the current intended semantic (the resolve tier wins the slot by
   * design — the ghost may otherwise never late-commit), correct ONLY under
   * the fresh-runId retry contract: `TurnController.settlePendingAdmission`
   * resolves the ghost and rolls the user row back with the SAME opId
   * retryable on a FRESH id, and GhostUndeliveredDurableTest#controllerGhostBarrierProofRetryKeepsFlushDurableClean
   * pins that the same-session same-opId retry admits on a fresh id
   * (`retryId != ghost`). A production path reusing a resolved runId as a
   * real admission id is documented-unsupported here: the genuine failure
   * tier cannot mark it (poison gap) — reuse support would require extending
   * the poison logic first, which is why no tier-promotion path exists.
   *
   * Interleave (existing seams only, no new hook): write #1 is admitted and
   * parked inside the writer's delegate (id admitted-pending), then resolved
   * via the public production seam
   * [OrderedTranscriptSink.tryResolveUndelivered]. Its flush-barrier
   * non-delivery proof is the controller's precondition (see its KDoc);
   * this seam-level control drops the proof phase but drives the same
   * poison-slot collision the F2 comments guard. The genuine failure is a
   * REAL second core write of the same id whose delegate throws
   * IOException — the writer finally routes it through
   * [OrderedTranscriptSink.markStoreFailure], which the resolved guard
   * swallows verbatim.
   */
  @Test fun ghostResolveThenSameIdGenuineFailure_staysCleanUnderFreshRunIdContract() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      // Mutated only on the single writer thread (same pattern as `userCalls`
      // in storeFailurePoisonSurvivesLaterCommitSameId).
      var ghostWrites = 0
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          if (runId == "ghost-then-fail") {
            if (ghostWrites++ == 0) {
              entered.complete(Unit)
              gate.await() // write #1 parked in the writer, id admitted-pending
            } else {
              throw IOException("fault-injected disk failure") // write #2: genuine failure, same id
            }
          }
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
          // Write #1 admitted; its waiter is parked on the ack while the
          // writer is parked inside the delegate. runCatching: the resolver
          // fails this dangling ack on purpose (it can never late-commit).
          val orphan = async { runCatching { sink.onTurnStarted("ghost-then-fail", "before resolve") } }
          entered.await() // writer consumed the entry; parked in the delegate
          assertTrue(
            "resolution must win the poison slot while the id is admitted-pending",
            sink.tryResolveUndelivered("ghost-then-fail"),
          )
          assertTrue(sink.isResolvedUndelivered("ghost-then-fail"))
          assertTrue(
            "resolved ghost keeps its visible true INTERRUPTED mark",
            sink.interruptedRunIds().contains("ghost-then-fail"),
          )
          assertFalse(
            "proven-undelivered ghost must not poison durability",
            sink.hasDurableFailures(),
          )
          // Through the (ack-released) ghost block: the row still commits,
          // untouched by the resolved tier (no storeFailed pin to honor).
          gate.complete(Unit)
          withTimeout(5_000) { while (!sink.isCoreCommitted("ghost-then-fail")) delay(10) }
          // Write #2, same id: the genuine failure the fresh-runId contract
          // forbids. The writer finally routes it to
          // [OrderedTranscriptSink.markStoreFailure], which early-returns on
          // the resolved id — the pin below.
          try {
            sink.onTurnStarted("ghost-then-fail", "same-id reuse after resolve")
            fail("genuine failure must not ack as durable")
          } catch (e: IOException) {
            // Explicit forward: the failed entry's ack, never a false success.
          }
          assertEquals(
            "only the resolved (pre-failure) write counts as committed",
            1,
            sink.coreCommitCount("ghost-then-fail"),
          )
          assertTrue(
            "resolved ghost stays visible after the genuine failure",
            sink.interruptedRunIds().contains("ghost-then-fail"),
          )
          assertTrue(
            "the resolve tier keeps owning the poison slot",
            sink.isResolvedUndelivered("ghost-then-fail"),
          )
          assertTrue(sink.pendingRunIds().isEmpty())
          assertFalse(
            "documented-unsupported same-id reuse reads false-clean; lifting is correct only under the fresh-runId retry contract",
            sink.hasDurableFailures(),
          )
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }
}
