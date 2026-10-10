package dev.librepocket.chat

import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stage A seal semantics: durable core failures never report success, a dead
 * writer seals (outstanding + orphan + later cores fail boundedly), and
 * shutdown orphans fail instead of parking forever.
 */
class OrderedTranscriptSinkSealTest {

  @Test fun durableStoreFailureFailsAckInsteadOfReportingSuccess() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val store = object : FakeSessionStore() {
        override suspend fun appendEvent(event: TranscriptEvent): Long {
          if (event.kind == "user" || event.kind == "assistant") throw IOException("db down")
          return super.appendEvent(event)
        }
      }
      val sid = runBlocking { store.createSession("hi", "m") }
      val sink = OrderedTranscriptSink(SessionTranscriptSink(store, sid), dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          try {
            sink.onTurnStarted("run-1", "hello")
            fail("durable user-row failure must not ack as success")
          } catch (e: Exception) {
            assertTrue("ack must fail explicitly, got $e", e is IOException)
          }
          try {
            sink.onTurnSucceeded("run-1", "hi")
            fail("durable assistant-row failure must not ack as success")
          } catch (e: Exception) {
            assertTrue("ack must fail explicitly, got $e", e is IOException)
          }
          assertTrue(
            "failed cores surface as INTERRUPTED, never a false durable ack",
            sink.interruptedRunIds().contains("run-1"),
          )
          assertTrue(sink.pendingRunIds().isEmpty())
          assertTrue("no user/assistant row may persist", store.events.none { it.kind == "user" })
          assertTrue("no user/assistant row may persist", store.events.none { it.kind == "assistant" })
          // Per-entry failure keeps the writer alive: notices still admit and
          // the barrier still drains boundedly.
          sink.onTurnRetried("run-1", 1, 3, 0)
          assertTrue("writer survives a non-cancel delegate failure", sink.flush())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun queuedCoreBehindWriterDeathFailsInsteadOfPendingForever() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val ran = Collections.synchronizedList(mutableListOf<String>())
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          ran.add("started:$runId")
          throw CancellationException("writer teardown")
        }
        override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String) {
          ran.add("cancelled:$runId")
        }
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
          val first = async { sink.onTurnStarted("run-1", "hi") }
          val second = async { sink.onTurnCancelled("run-2", "half") }
          for (job in listOf(first, second)) {
            try {
              job.await()
              fail("core behind a dead writer must fail, never hang or succeed")
            } catch (_: CancellationException) {
              // Explicit bounded failure (teardown seal or fail-fast).
            } catch (_: Exception) {
              // ClosedSendChannelException on the seal race also counts.
            }
          }
          assertTrue(sink.interruptedRunIds().contains("run-1"))
          assertTrue(sink.interruptedRunIds().contains("run-2"))
          assertTrue(sink.pendingRunIds().isEmpty())
          assertTrue("orphan core must never reach the dead delegate: $ran", ran.none { it.startsWith("cancelled:") })
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun coreAfterWriterDeathFailsFastBounded() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          throw CancellationException("writer teardown")
        }
        override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
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
          // Fail-fast: bounded by a short timeout, never an unbounded park.
          val started = System.currentTimeMillis()
          withTimeout(4_000) {
            try {
              sink.onTurnSucceeded("run-2", "late")
              fail("post-death core must fail fast, never succeed")
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
          }
          val elapsed = System.currentTimeMillis() - started
          assertTrue("post-death core must fail fast, took ${elapsed}ms", elapsed < 4_000L)
          assertTrue(sink.interruptedRunIds().contains("run-2"))
          assertTrue(sink.pendingRunIds().isEmpty())
          sink.shutdown()?.join()
        }
      }
    } finally {
      exec.shutdown()
    }
  }

  @Test fun shutdownFailsOrphanAckInsteadOfParkingForever() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val entered = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      val delegate = object : TranscriptSink {
        override suspend fun onTurnStarted(runId: String, text: String) {
          entered.complete(Unit)
          gate.await()
        }
        override suspend fun onTurnSucceeded(runId: String, text: String) = Unit
        override suspend fun onTurnFailed(runId: String, error: String) = Unit
        override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
        override suspend fun onTurnCancelled(runId: String, partialText: String) = Unit
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
          val orphan = async { sink.onTurnStarted("orphan-1", "hi") }
          entered.await()
          sink.shutdown(drainTimeoutMs = 200)?.join()
          try {
            withTimeout(4_000) { orphan.await() }
            fail("shutdown orphan must throw, never hang or succeed")
          } catch (_: CancellationException) {
            // Explicit bounded failure (drain-timeout orphan fail).
          } catch (e: Exception) {
            fail("orphan must fail with CancellationException, got $e")
          }
          assertEquals(listOf("orphan-1"), sink.interruptedRunIds())
          assertTrue(sink.pendingRunIds().isEmpty())
        }
      }
      gate.complete(Unit)
    } finally {
      exec.shutdown()
    }
  }

  /**
   * R2-8: When store fault-injection throws IOException:
   * - core event ack throws exception;
   * - hasDurableFailures() is true;
   * - flush() returns true (barrier drained);
   * - flushDurable() returns false (persistence failed);
   * - shutdown does not mislabel healthy pending core as drain timeout.
   */
  @Test fun storeFaultInjectionSurfacesDurableFailuresAndFlushContract() {
    val exec = Executors.newSingleThreadExecutor()
    try {
      val dispatcher = exec.asCoroutineDispatcher()
      val store = object : FakeSessionStore() {
        override suspend fun appendEvent(event: TranscriptEvent): Long {
          if (event.runId == "faulty-core") throw IOException("fault-injected disk failure")
          return super.appendEvent(event)
        }
      }
      val sid = runBlocking { store.createSession("title", "model") }
      val sink = OrderedTranscriptSink(SessionTranscriptSink(store, sid), dispatcher, capacity = 8)
      runBlocking {
        withTimeout(10_000) {
          // 1. core 事件 ack 拋出例外
          try {
            sink.onTurnStarted("faulty-core", "faulty user text")
            fail("store fault-injection must cause core ack to throw")
          } catch (e: Exception) {
            assertTrue("core ack must throw IOException, got: $e", e is IOException)
          }

          // 2. hasDurableFailures() 為 true
          assertTrue("hasDurableFailures() must be true after store failure", sink.hasDurableFailures())

          // Admit a healthy core event to verify shutdown does not mislabel it
          sink.onTurnStarted("healthy-core", "healthy user text")

          // 3. flush() 回傳 true（barrier 已 drain）
          assertTrue("flush() must return true as barrier drained", sink.flush())

          // 4. flushDurable() 回傳 false（持久化失敗）
          assertFalse("flushDurable() must return false due to durable store failure", sink.flushDurable())

          // 5. shutdown 不會因此將健康的 pending core 誤標為 drain timeout
          sink.shutdown()?.join()
          assertEquals(listOf("faulty-core"), sink.interruptedRunIds())
          assertTrue("healthy core must not be pending", sink.pendingRunIds().isEmpty())
          assertTrue(
            "healthy core must have persisted to store successfully",
            store.events.any { it.runId == "healthy-core" },
          )
        }
      }
    } finally {
      exec.shutdown()
    }
  }
}
