package dev.librepocket.chat

import kotlinx.coroutines.flow.StateFlow

/**
 * Single-turn chat controller: send / stream / cancel / steering.
 *
 * Invariants (P1 acceptance):
 * 1. Only one in-flight turn; [send] while busy throws [IllegalStateException]
 *    (also projected via [ChatUiState.error], never crashes).
 * 2. [cancel] only flips state + closes the HTTP call + cancels the job.
 *    No IO, no DB writes on that path (persistence is async elsewhere).
 * 3. [steer] never cancels the current turn; queued work normally auto-promotes
 *    FIFO after an accepted turn ends. A denied/cancelled turn or failed
 *    recovery gate keeps the queue for explicit recovery instead.
 */
interface ChatSession {
  /** Messages for UI (collect; includes streaming placeholders). */
  val uiState: StateFlow<ChatUiState>

  /** Send one user message and stream the reply. One in-flight turn at a time. */
  suspend fun send(text: String, images: List<ChatImageRef> = emptyList())

  /**
   * Start a turn and return once the text is accepted (fresh `chat.send`
   * policy passed, user message appended, STREAMING) without waiting for the
   * hosted turn to finish. A [CancellationException]/[SecurityException]/
   * [IllegalStateException]/[IllegalArgumentException] before return means
   * the text was NOT accepted.
   */
  suspend fun startTurn(text: String, images: List<ChatImageRef> = emptyList()): kotlinx.coroutines.Job

  /**
   * Atomic start-or-enqueue with an explicit [TurnStart] acknowledgment: the
   * busy check and the FIFO insert share one lock, so the verdict is never
   * stale. Prefer over start-then-steer fallback sequences, whose two checks
   * can straddle a turn completing (Q1).
   *
   * N1 ownership: pass the caller's operation id as [opId]. [TurnStart.Queued]
   * is not acceptance and stays reclaimable via [drainQueued].
   * [TurnStart.NeedsRecovery] atomically returns a FIFO retained after a
   * denied or failed promotion policy check for recovery; the caller must
   * preserve it according to lifecycle semantics
   * (including handing it to a replacement endpoint when appropriate) before
   * retrying the current admission. The current admission is not accepted;
   * when recovery is discovered under the second admission lock, it may
   * already have passed one fresh policy gate, and retrying evaluates policy
   * again. Neither result accepts the returned FIFO.
   */
  suspend fun startOrEnqueue(
    text: String,
    images: List<ChatImageRef> = emptyList(),
    opId: Long? = null,
  ): TurnStart

  /**
   * Reclaim queued-but-unstarted intents FIFO without starting anything (N1).
   * Endpoint teardown drains this BEFORE [close] so queued text stays
   * recoverable; explicit discards (newChat/open/logout) skip the drain and
   * let [close] drop the FIFO.
   */
  fun drainQueued(): List<QueuedIntent>

  /** Cancel the in-flight turn; UI must stop updating within 200ms. */
  fun cancel()

  /**
   * Bounded ledger drain: suspends until every transcript event admitted so
   * far is persisted (true = drained, false = timed out). Two sequential
   * per-call budgets of [timeoutMs] — the [close] shutdown-join first
   * (doomed-host settle + admitted drain + seal), then the admitted-event
   * drain — so a flush after [close] waits up to ~2x[timeoutMs], while a
   * flush before/without [close] only runs the second phase (~1x[timeoutMs]).
   * Caller cancellation still propagates. The ViewModel awaits this
   * (bounded) before [close] on newChat / openSession / endpoint-switch so
   * admitted notices are not lost with the session, without ever blocking
   * the UI unboundedly.
   */
  suspend fun flush(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean

  /**
   * Returns true if any admitted transcript event encountered a durable persistence failure
   * (e.g. store IOException) or seal/interruption mark.
   */
  fun hasDurableFailures(): Boolean = false

  /**
   * Suspends until every transcript event admitted so far is persisted and verifies
   * that no durable store failure occurred.
   *
   * Returns true only when [flush] drains within [timeoutMs] AND [hasDurableFailures] is false.
   */
  suspend fun flushDurable(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean =
    flush(timeoutMs) && !hasDurableFailures()

  /**
   * Steering: queue an instruction for the next round.
   * Never cancels the current HTTP request or the current turn; the queued
   * instruction is sent automatically once the current turn fully ends. When
   * idle, retained recovery work causes a synchronous
   * [RecoveryRequiredException] instead of allowing this instruction to
   * overtake it; the new text is retained behind that FIFO. If recovery wins
   * after the idle check, the text is retained for explicit recovery and
   * surfaced in [uiState].
   */
  fun steer(text: String)

  /** Release (close HTTP call, drop queue). */
  fun close()
}
