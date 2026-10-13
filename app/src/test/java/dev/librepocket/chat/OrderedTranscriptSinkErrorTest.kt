package dev.librepocket.chat

import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
