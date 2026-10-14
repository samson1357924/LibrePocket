package dev.librepocket.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * S5 legacy-sink fail-closed regression: a sink that overrides only the
 * 2-arg / 3-arg base forms (keeping every lossy interface default) must never
 * prematurely close a logical family on a retryable failure. A 6-arg
 * `isFinal=false` call (and the 7-arg reasoning/non-final variants, plus
 * true-linkage 5-arg calls with `parentRunId != null` or `attemptIndex != 0`)
 * throws [UnsupportedOperationException] with zero rows instead of downgrading
 * to a final write. The first-attempt shape (`parentRunId == null` with
 * `attemptIndex == null || attemptIndex == 0`, which is what production sends
 * on attempt 0) is lossless and still forwards to the base form.
 */
class TranscriptSinkLegacyFailClosedTest {

  /** Legacy shape: only the base forms, every full form keeps the interface default. */
  private open class LegacySink : TranscriptSink {
    val calls = ArrayList<String>()
    override suspend fun onTurnStarted(runId: String, text: String) {
      calls.add("started:$runId")
    }
    override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) {
      calls.add("succeeded:$runId")
    }
    override suspend fun onTurnFailed(runId: String, error: String) {
      calls.add("failed2:$runId")
    }
    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
    override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
      calls.add("cancelled:$runId")
    }
    override suspend fun onSteerQueued(text: String) = Unit
    override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
  }

  @Test fun retryableFailure_isFinalFalse_throwsInsteadOfPrematureClose() = runBlocking {
    val sink: TranscriptSink = LegacySink()
    try {
      sink.onTurnFailed("r1", "half", "boom", null, 0, false)
      fail("isFinal=false on a legacy sink must throw, never downgrade to final")
    } catch (_: UnsupportedOperationException) {
      // Expected fail-closed: zero rows, family stays dangling.
    }
    assertTrue((sink as LegacySink).calls.none { it.startsWith("failed2:") })
  }

  @Test fun reasoningFailure_hasReasoningTrue_throwsInsteadOfDroppingMarker() = runBlocking {
    val sink: TranscriptSink = LegacySink()
    try {
      sink.onTurnFailed("r1", "half", "boom", null, 0, true, true)
      fail("hasReasoning=true on a legacy sink must throw, never drop the marker")
    } catch (_: UnsupportedOperationException) {
    }
    assertTrue((sink as LegacySink).calls.isEmpty())
  }

  @Test fun nonFinalReasoningFailure_throwsInsteadOfDowngrade() = runBlocking {
    val sink: TranscriptSink = LegacySink()
    try {
      sink.onTurnFailed("r1", "half", "boom", null, 0, false, true)
      fail("non-final reasoning failure must throw")
    } catch (_: UnsupportedOperationException) {
    }
  }

  @Test fun attemptLinkageDropping_throwsInsteadOfSingleIdRow() = runBlocking {
    val sink: TranscriptSink = LegacySink()
    try {
      sink.onTurnSucceeded("a2", "answer", "L", 1, hasReasoning = false)
      fail("5-arg success with linkage on a legacy sink must throw")
    } catch (_: UnsupportedOperationException) {
    }
    try {
      sink.onTurnFailed("a2", "half", "boom", "L", 1)
      fail("5-arg failure with linkage on a legacy sink must throw")
    } catch (_: UnsupportedOperationException) {
    }
    try {
      sink.onTurnCancelled("a3", "part", "L", 2, hasReasoning = false)
      fail("5-arg cancel with linkage on a legacy sink must throw")
    } catch (_: UnsupportedOperationException) {
    }
    // attemptIndex != 0 alone is true linkage even with a null parent.
    try {
      sink.onTurnSucceeded("a2", "answer", null, 1, hasReasoning = false)
      fail("5-arg success with attemptIndex=1 and null parent must throw")
    } catch (_: UnsupportedOperationException) {
    }
  }

  @Test fun finalWithoutLinkage_stillForwardsForCompat() = runBlocking {
    val sink = LegacySink()
    // First-attempt shape (parent null, index null): lossless, still forwards.
    sink.onTurnSucceeded("r1", "answer", null, null, hasReasoning = false)
    sink.onTurnFailed("r1", "half", "boom", null, null, true)
    sink.onTurnFailed("r2", "frag", "down", null, null, true, false)
    sink.onTurnCancelled("r3", "part", null, null, hasReasoning = false)
    assertTrue(sink.calls.contains("succeeded:r1"))
  }

  @Test fun firstAttemptZeroIndex_stillForwardsForCompat() = runBlocking {
    // Production's ordered path always sends attemptIndex=0 on the first
    // attempt (parent null + index 0): lossless, must still forward so
    // base-only in-repo fakes keep their write rows instead of throwing.
    val sink = LegacySink()
    sink.onTurnSucceeded("r1", "answer", null, 0, hasReasoning = false)
    sink.onTurnFailed("r1", "half", "boom", null, 0)
    sink.onTurnFailed("r1", "half", "boom", null, 0, true)
    sink.onTurnFailed("r2", "frag", "down", null, 0, true, false)
    sink.onTurnCancelled("r3", "part", null, 0, hasReasoning = false)
    // Cancel marker rides into the base 3-arg form (which carries hasReasoning),
    // so a first-attempt reasoning cancel also forwards losslessly.
    sink.onTurnCancelled("r4", "part", null, 0, hasReasoning = true)
    assertTrue(sink.calls.contains("succeeded:r1"))
    assertTrue(sink.calls.count { it == "failed2:r1" } >= 2)
    assertTrue(sink.calls.contains("cancelled:r3"))
    assertTrue(sink.calls.contains("cancelled:r4"))
  }

  @Test fun failedAndCancelled_onLegacySink_throwsInsteadOfPartialWrite() = runBlocking {
    // S5 fail-closed: the default forwards to the 7-arg isFinal=false form,
    // which rejects legacy sinks — zero rows, never a downgraded final write.
    val sink = LegacySink()
    try {
      sink.onTurnFailedAndCancelled("L", "a1", "half", "boom", null, 0, hasReasoning = false)
      fail("onTurnFailedAndCancelled on a legacy sink must throw, never write")
    } catch (_: UnsupportedOperationException) {
    }
    assertTrue(sink.calls.isEmpty())
  }
}
