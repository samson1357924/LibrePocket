package dev.librepocket.chat

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.agent.ui.chat.findDanglingRunIds
import dev.librepocket.policy.PolicyDecision
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderProtocol as ProviderProto
import dev.librepocket.provider.StreamEvent
import dev.librepocket.session.FakeSessionStore
import dev.librepocket.session.JsonlCodec
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionTranscriptSink
import dev.librepocket.session.TranscriptEvent
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class GapProvider(
  var handler: (ChatRequest) -> Flow<StreamEvent> = { emptyFlow() },
) : LlmProvider {
  override val protocol: ProviderProto = ProviderProto.CHAT_COMPLETIONS
  val streamCalls = AtomicInteger(0)
  override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
    streamCalls.incrementAndGet()
    emitAll(handler(request))
  }
  override suspend fun listModels(): List<String> = emptyList()
}

private class GapPolicy : PolicyStore {
  override fun evaluate(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
  override suspend fun setRule(rule: PolicyRule) = Unit
  override suspend fun removeRule(pattern: String) = Unit
  override suspend fun listRules(): List<PolicyRule> = emptyList()
  override suspend fun evaluateFresh(action: String, resource: String) =
    PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
}

/**
 * D15-2 gap coverage (TerminalAckCancelGapTest).
 *
 * Covers the cells missing from [TurnControllerTerminalAckCancelTest]:
 * FAILED/store-failure, retry-id × commit前 (SUBMITTING >= 1) and retry-id ×
 * ack后 (acked), plus one true Controller + SessionTranscriptSink + Room
 *对照 for commit后ack前 (same-attempt single outcome).
 *
 * opId维度正名 (naming note, minimal option): this grid's "retry-id" means a
 * fresh attempt runId (String, minted via TurnController newId, tracked via
 * attemptTerminalFinal / terminalizedAttempts / coreCommitCount), NOT the
 * admission operation ID (Long opId used by startOrEnqueue / steerQueue /
 * same-opId resend blocking). The two namespaces never overlap: opId guards
 * admission duplication, runId guards terminal dedup. No same-opId resend
 * race cell is added here (annotation option chosen as minimal).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TerminalAckCancelGapTest {

  /** Store that can hold assistant writes in the commit window, with fault injection. */
  private open class GapGatedStore : FakeSessionStore() {
    @Volatile var blockAssistant = false
    val assistantEntered = CountDownLatch(1)
    val assistantGate = CompletableDeferred<Unit>()
    val assistantCalls = AtomicInteger(0)
    override suspend fun appendEvent(event: TranscriptEvent): Long {
      if (event.kind == "assistant") {
        val n = assistantCalls.incrementAndGet()
        if (blockAssistant && shouldBlock(n, event)) {
          assistantEntered.countDown()
          assistantGate.await()
          afterGate(n, event)
        }
      }
      return super.appendEvent(event)
    }
    protected open fun shouldBlock(n: Int, event: TranscriptEvent): Boolean = true
    protected open suspend fun afterGate(n: Int, event: TranscriptEvent) = Unit
  }

  private fun controller(
    provider: GapProvider,
    sink: SessionTranscriptSink,
    n: AtomicInteger,
    sleeper: suspend (Long) -> Unit = {},
    retry: TurnRetryConfig = TurnRetryConfig(),
  ): TurnController = TurnController(
    provider = provider,
    policy = GapPolicy(),
    transcript = sink,
    retryConfig = retry,
    dispatcher = Dispatchers.Default,
    sleeper = sleeper,
    newId = { "gap-${n.incrementAndGet() - 1}" },
  )

  private fun startedHost(c: TurnController): kotlinx.coroutines.Job {
    val admission = runBlocking { c.startOrEnqueue("hi") }
    assertTrue("admission must start, was $admission", admission is TurnStart.Started)
    return (admission as TurnStart.Started).host
  }

  private fun joinHost(host: kotlinx.coroutines.Job) {
    runBlocking { withTimeout(8_000) { host.join() } }
  }

  private fun flush(c: TurnController) {
    runBlocking { withTimeout(8_000) { c.flushTranscript() } }
  }

  // ---- FAILED/store-failure × commit前: FAILED (no commit, INTERRUPTED) → normal final. ----

  @Test fun failedStoreFailureCancelBeforeCommitWritesNormalFinal() {
    // First assistant (terminal success) blocks then throws IOException (no
    // commit, INTERRUPTED); second assistant (cancel terminal) must succeed
    // as a normal final (non-zero new, not a system mark).
    val store = object : GapGatedStore() {
      init { blockAssistant = true }
      override suspend fun afterGate(n: Int, event: TranscriptEvent) {
        if (n == 1) throw IOException("injected terminal store failure")
      }
    }
    val sid = runBlocking { store.createSession("hi", "m") }
    val provider = GapProvider {
      flow {
        emit(StreamEvent.TextDelta(0, 0, "ok"))
        emit(StreamEvent.Done("stop"))
      }
    }
    val n = AtomicInteger(0)
    val c = controller(provider, SessionTranscriptSink(store, sid), n)
    try {
      val host = startedHost(c)
      assertTrue("terminal write never entered", store.assistantEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      store.assistantGate.complete(Unit)
      joinHost(host)
      flush(c)
      // (a) ledger: user + normal cancel assistant (terminal failed left zero
      // rows, cancel writes one final); never a system mark, non-zero new.
      assertEquals(listOf("user", "assistant"), store.events.map { it.kind })
      // (b) same attempt owns exactly one assistant row: the normal cancel
      // final (isPartial + isFinal, full streamed text, no failureReason).
      val attemptId = store.events[0].runId
      val assistants = store.events.filter { it.kind == "assistant" && it.runId == attemptId }
      assertEquals("expected single cancel assistant for $attemptId: ${store.events}", 1, assistants.size)
      assertEquals("ok", assistants.single().text)
      assertEquals(true, assistants.single().isPartial)
      assertEquals("FAILED must keep the normal final cancel assistant", true, assistants.single().isFinal)
      assertEquals(null, assistants.single().failureReason)
      // (c) family closed by the cancel final (no dangling, no system mark).
      assertTrue("no system mark expected: ${store.events}", store.events.none { it.kind == "system" })
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) single provider attempt, one UI assistant block (cancel partial kept).
      assertEquals(1, provider.streamCalls.get())
      assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      store.assistantGate.complete(Unit)
      c.close()
    }
  }

  // ---- retry-id × commit前: SUBMITTING (fresh id, >= 1) final → zero new rows. ----

  @Test fun retryIdCancelBeforeCommitWritesZeroNew() {
    // Only the second assistant (retry success, fresh id) blocks; the first
    // (intermediate partial) commits immediately so the cancel deterministically
    // lands on retry-id SUBMITTING (commitCount 0 + pending + final intent).
    val secondEntered = CountDownLatch(1)
    val secondGate = CompletableDeferred<Unit>()
    val store = object : FakeSessionStore() {
      val assistantCalls = AtomicInteger(0)
      override suspend fun appendEvent(event: TranscriptEvent): Long {
        if (event.kind == "assistant" && assistantCalls.incrementAndGet() == 2) {
          secondEntered.countDown()
          secondGate.await()
        }
        return super.appendEvent(event)
      }
    }
    val sid = runBlocking { store.createSession("hi", "m") }
    val calls = AtomicInteger(0)
    val provider = GapProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "half"))
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
    )
    try {
      val host = startedHost(c)
      assertTrue("retry terminal never entered", secondEntered.await(5, TimeUnit.SECONDS))
      c.cancel()
      Thread.sleep(300)
      secondGate.complete(Unit)
      joinHost(host)
      flush(c)
      // (a) ledger: user + intermediate partial + retry notice + retry success, no cancel row.
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      // (b) retry attempt owns exactly one final assistant row.
      val user = store.events[0]
      val partial = store.events[1]
      val retryNotice = store.events[2]
      val success = store.events[3]
      assertEquals(user.runId, partial.runId)
      assertEquals(user.runId, retryNotice.runId)
      assertEquals(true, partial.isPartial)
      assertEquals(false, partial.isFinal)
      assertEquals("boom", partial.failureReason)
      assertTrue("retry must mint a fresh id, was ${success.runId} vs logical ${user.runId}", success.runId != user.runId)
      assertEquals("ok", success.text)
      assertEquals(true, success.isFinal)
      assertEquals(false, success.isPartial)
      assertEquals(1, store.events.count { it.kind == "assistant" && it.runId == success.runId })
      // (c) family closed (no dangling).
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) both provider attempts ran, UI keeps one block per attempt.
      assertEquals(2, provider.streamCalls.get())
      assertEquals(2, c.uiState.value.messages.count { it.role == "assistant" })
      for (e in store.events) {
        val back = JsonlCodec.decode(1, JsonlCodec.encode(e.copy(seq = e.seq.coerceAtLeast(1))))
        assertEquals("isFinal must round-trip for ${e.kind}", e.isFinal, back.isFinal)
      }
    } finally {
      secondGate.complete(Unit)
      c.close()
    }
  }

  // ---- retry-id × ack后: acked (fresh id) final → zero new rows. ----

  @Test fun retryIdCancelAfterAckWritesZeroNew() {
    val store = FakeSessionStore()
    val sid = runBlocking { store.createSession("hi", "m") }
    val calls = AtomicInteger(0)
    val provider = GapProvider {
      flow {
        if (calls.incrementAndGet() == 1) {
          emit(StreamEvent.TextDelta(0, 0, "half"))
          emit(StreamEvent.Failed("boom", retryable = true))
        } else {
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
    }
    val n = AtomicInteger(0)
    val c = controller(
      provider, SessionTranscriptSink(store, sid), n,
      retry = TurnRetryConfig(maxRetries = 1, retryDelaysMs = listOf(0L)),
    )
    val terminalAcks = AtomicInteger(0)
    val ackedLatch = CountDownLatch(1)
    val holdHost = CompletableDeferred<Unit>()
    c.afterTerminalAckForTest = {
      // First terminal = intermediate partial (ack-1 family), second = retry
      // success (fresh id). Hold only the second to hit retry-id acked.
      if (terminalAcks.incrementAndGet() == 2) {
        ackedLatch.countDown()
        holdHost.await()
      }
    }
    try {
      val host = startedHost(c)
      assertTrue("retry terminal never acked", ackedLatch.await(5, TimeUnit.SECONDS))
      c.cancel()
      joinHost(host)
      flush(c)
      // (a) ledger: user + intermediate partial + retry notice + retry success, no cancel row.
      assertEquals(listOf("user", "assistant", "retry", "assistant"), store.events.map { it.kind })
      // (b) retry attempt owns exactly one final assistant row.
      val user = store.events[0]
      val partial = store.events[1]
      val success = store.events[3]
      assertEquals(user.runId, partial.runId)
      assertEquals(true, partial.isPartial)
      assertEquals(false, partial.isFinal)
      assertTrue("retry must mint a fresh id, was ${success.runId} vs logical ${user.runId}", success.runId != user.runId)
      assertEquals("ok", success.text)
      assertEquals(true, success.isFinal)
      assertEquals(1, store.events.count { it.kind == "assistant" && it.runId == success.runId })
      // (c) family closed (no dangling).
      assertTrue("dangling: ${store.events}", findDanglingRunIds(store.events).isEmpty())
      // (d) both provider attempts ran, UI keeps one block per attempt.
      assertEquals(2, provider.streamCalls.get())
      assertEquals(2, c.uiState.value.messages.count { it.role == "assistant" })
    } finally {
      holdHost.complete(Unit)
      c.close()
    }
  }

  // ---- Room对照: success × commit后ack前 with true Controller + Sink + Room. ----

  @Test fun successCancelBetweenCommitAndAckWithRoomWritesZeroNew() {
    // Same latch pattern as RoomPostCommitResumeCancelTest (commit signal +
    // release), but at the sink commit→ack seam so the hold is observably
    // COMMITTED (commitCount >= 2 for the first-attempt id) with a truly
    // durable Room row underneath. Same-attempt single outcome, zero new rows.
    val db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
    try {
      val store = RoomSessionStore(db) { 1_700_000_000_000L }
      val sid = runBlocking { store.createSession("hi", "m") }
      val provider = GapProvider {
        flow {
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
      val n = AtomicInteger(0)
      val c = controller(provider, SessionTranscriptSink(store, sid), n)
      val commits = AtomicInteger(0)
      val committedLatch = CountDownLatch(1)
      val releaseWriter = CountDownLatch(1)
      c.afterCommitBeforeAckForTest = {
        if (commits.incrementAndGet() == 2) {
          committedLatch.countDown()
          releaseWriter.await(5, TimeUnit.SECONDS)
        }
      }
      try {
        val host = startedHost(c)
        assertTrue("terminal never committed", committedLatch.await(5, TimeUnit.SECONDS))
        c.cancel()
        Thread.sleep(300)
        releaseWriter.countDown()
        joinHost(host)
        flush(c)
        val events = runBlocking { store.loadEvents(sid, 0, 100) }
        // (a) ledger: one user + one success, no cancel row.
        assertEquals(listOf("user", "assistant"), events.map { it.kind })
        // (b) same attempt owns exactly one final assistant row (no duplicate).
        val attemptId = events[0].runId
        val assistants = events.filter { it.kind == "assistant" && it.runId == attemptId }
        assertEquals("duplicate assistant for $attemptId: $events", 1, assistants.size)
        assertEquals("ok", assistants.single().text)
        assertEquals(true, assistants.single().isFinal)
        // (c) family closed by the success final (no dangling).
        assertTrue("dangling: $events", findDanglingRunIds(events).isEmpty())
        // (d) single provider attempt, one UI assistant block.
        assertEquals(1, provider.streamCalls.get())
        assertEquals(1, c.uiState.value.messages.count { it.role == "assistant" })
        // Durability: the committed row survives a reload from Room.
        val reloaded = runBlocking { store.loadEvents(sid, 0, 100) }
        assertEquals(events.map { it.runId to it.text }, reloaded.map { it.runId to it.text })
      } finally {
        releaseWriter.countDown()
        c.close()
      }
    } finally {
      db.close()
    }
  }

  // ---- sanity: intermediate × ack后 with Room would also hold, but one Room
  // cell suffices for the对照 requirement (success final, COMMITTED, zero new).
  // A second Room retryable cell is intentionally omitted (Fake grid already
  // pins the system-mark branch; Room here pins real-DB durability for finals).
  // ---------------------------------------------------------------------------

  @Test fun successCancelBetweenCommitAndAckKeepsUsageRow() {
    // Same commit→ack hold as the Room cell above, with provider usage on the
    // attempt: the suppressed-final cancel branch persists the buffered
    // authoritative usage as a bare system row AFTER the durable success final
    // (it was silently dropped before the #94 fix). Linkage stays null so the
    // JSONL shape gate accepts the row on export/import.
    val db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
    try {
      val store = RoomSessionStore(db) { 1_700_000_000_000L }
      val sid = runBlocking { store.createSession("hi", "m") }
      val provider = GapProvider {
        flow {
          emit(StreamEvent.Usage(11, 22))
          emit(StreamEvent.TextDelta(0, 0, "ok"))
          emit(StreamEvent.Done("stop"))
        }
      }
      val n = AtomicInteger(0)
      val c = controller(provider, SessionTranscriptSink(store, sid), n)
      val commits = AtomicInteger(0)
      val committedLatch = CountDownLatch(1)
      val releaseWriter = CountDownLatch(1)
      c.afterCommitBeforeAckForTest = {
        if (commits.incrementAndGet() == 2) {
          committedLatch.countDown()
          releaseWriter.await(5, TimeUnit.SECONDS)
        }
      }
      try {
        val host = startedHost(c)
        assertTrue("terminal never committed", committedLatch.await(5, TimeUnit.SECONDS))
        c.cancel()
        Thread.sleep(300)
        releaseWriter.countDown()
        joinHost(host)
        flush(c)
        val events = runBlocking { store.loadEvents(sid, 0, 100) }
        // (a) user + success final + usage; no cancel row, no duplicate.
        assertEquals(listOf("user", "assistant", "system"), events.map { it.kind })
        assertEquals("usage input=11 output=22", events[2].text)
        // (b) bare system row: no attempt linkage (JSONL S2-F2 shape gate).
        assertEquals(null, events[2].parentRunId)
        assertEquals(null, events[2].attemptIndex)
        // (c) the whole transcript survives the JSONL export/import gate
        // (linkage on a system row would throw here).
        events.forEachIndexed { i, e -> JsonlCodec.decode(i + 1, JsonlCodec.encode(e)) }
        // (d) family closed by the success final (one usage row, never a
        // second terminal).
        assertTrue("dangling: $events", findDanglingRunIds(events).isEmpty())
        assertEquals(1, events.count { it.kind == "system" && it.text.startsWith("usage ") })
      } finally {
        releaseWriter.countDown()
        c.close()
      }
    } finally {
      db.close()
    }
  }
}
