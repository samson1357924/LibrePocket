package dev.librepocket.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared zero trim flow for [ChatSession.requestTrimmedCount]'s default (read-only, safe to share). */
private val ZeroRequestTrimmedCount: StateFlow<Int> = MutableStateFlow(0).asStateFlow()

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

  /**
   * R5-5: send-time per-request trim count (oldest-first messages dropped to
   * fit MAX_REQUEST_CHARS), published by the controller on every attempt.
   * Distinct from load-time resume omission (DB tail cap): never summed with
   * it. Default is a shared zero flow so test fakes stay source-compatible;
   * production ([ChatSessionImpl]) overrides with the live controller flow.
   */
  val requestTrimmedCount: StateFlow<Int> get() = ZeroRequestTrimmedCount

  /** Send one user message and stream the reply. One in-flight turn at a time. */
  suspend fun send(text: String, images: List<ChatImageRef> = emptyList())

  /**
   * Start a turn and return once the text is accepted (fresh `chat.send`
   * policy passed, user message appended, STREAMING) without waiting for the
   * hosted turn to finish. A [CancellationException]/[SecurityException]/
   * [IllegalStateException]/[IllegalArgumentException]/[TranscriptDurabilityException] before return means
   * the text was NOT accepted. A [TranscriptPendingException] means UNKNOWN
   * (R5-1 timeout-after-send / R6-2 external-cancel-after-send): ownership retained, do NOT immediately resend
   * the same opId — await [settlePendingAdmission] (claimed under the same id
   * without resend, or converted to retryable).
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
   * that no durable store failure occurred, no cancel receipt is still unsettled,
   * and no SEALED receipt is still uncompensated.
   *
   * Returns true only when [flush] drains within [timeoutMs] AND [hasDurableFailures] is false
   * AND [hasUnsettledCancelReceipts] is false (no PENDING/CLAIMED receipt)
   * AND [hasSealedUncompensatedReceipts] is false (no SEALED receipt without
   * proven family-final). SEALED alone is NOT settled.
   */
  suspend fun flushDurable(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean =
    flush(timeoutMs) && !hasDurableFailures() && !hasUnsettledCancelReceipts() &&
      !hasSealedUncompensatedReceipts()

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

  /**
   * R5-1 bounded settle for an UNKNOWN/PENDING_COMMIT admission
   * (timeout-after-send). Ticket-tracked flush: success without an
   * INTERRUPTED mark claims the ghost under the same id (no resend);
   * INTERRUPTED/seal converts to retryable. Returns true when resolved
   * (claimed, failed, or nothing pending), false when still unknown (flush
   * timed out — keep ownership, no false mark; retry re-settles).
   *
   * M2: caller cancellation is never swallowed — a caller-cancelled settle
   * rethrows CancellationException directly (never TranscriptPendingException);
   * false only means the bounded flush timed out with ownership retained.
   */
  suspend fun settlePendingAdmission(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean =
    true

  /** R5-1: true while an UNKNOWN admission still owns the single-flight slot. */
  fun hasPendingAdmission(): Boolean = false

  /**
   * R19-1 read-only receipt settle proof: true while any cancel receipt on
   * this session is still PENDING (awaiting the terminal outcome) or CLAIMED
   * (compensation write in flight). REVOKED/COMPENSATED are settled; SEALED
   * is settled only with proven family-final (see
   * [hasSealedUncompensatedReceipts]) and otherwise still blocks
   * [flushDurable]. Per-session ownership: a fresh session never claims an
   * old session's receipts (its own map starts empty), so new-session
   * liveness checks are unaffected. Default false so test fakes stay
   * source-compatible; production ([ChatSessionImpl]) overrides with the
   * live controller state.
   */
  fun hasUnsettledCancelReceipts(): Boolean = false

  /**
   * Phase 1 SEALED truth contract: true while any cancel receipt on this
   * session is SEALED without proven family-final (final intent, terminal
   * committed, no system repair owed — anything else stays uncompensated
   * until reopen INTERRUPTED backfill). Conjoined into [flushDurable] and
   * consulted by the ViewModel incomplete fallback alongside
   * [hasUnsettledCancelReceipts]. Default false so test fakes stay
   * source-compatible; production ([ChatSessionImpl]) overrides with the
   * live controller state.
   */
  fun hasSealedUncompensatedReceipts(): Boolean = false

  /**
   * R6-3 streaming-switch fence: core runIds admitted but still awaiting the
   * session writer. Non-empty means the old ledger may still be missing its
   * cancel terminal (the doomed host has not settled yet), so a same-origin
   * fork must await settle + durable flush before reading the ledger and must
   * degrade (skip dangling families + incomplete marker) when the wait fails.
   * Default is empty so test fakes stay source-compatible; production
   * ([ChatSessionImpl]) overrides with the live controller set.
   */
  fun pendingTranscriptRunIds(): List<String> = emptyList()

  /**
   * R10-2 writer-dead proof: true once the session writer is sealed/torn
   * down. Default false so test fakes stay source-compatible; production
   * ([ChatSessionImpl]) overrides with the live controller state. The
   * backfill gate treats `isTranscriptSealed() && pending empty &&
   * !hasPendingAdmission()` as proof the old writer can never land a late
   * terminal, so an expired block may promote even while the ledger still
   * dangles (marking INTERRUPTED is then correct, never a mis-mark).
   *
   * R14-1: seal alone is NOT that proof — a timed-out shutdown closes the
   * channel (sealed) and empties pending while the writer is still inside a
   * blocking store block — so the gate additionally requires
   * [isTranscriptWriterCompleted].
   */
  fun isTranscriptSealed(): Boolean = false

  /**
   * R14-1 writer-completion proof: true only once the session writer can no
   * longer execute a store block. Default false (fail-closed: unknown
   * writers keep their outstanding ownership) so test fakes stay
   * source-compatible; production ([ChatSessionImpl]) overrides with the
   * live controller state.
   */
  fun isTranscriptWriterCompleted(): Boolean = false

  /** Release (close HTTP call, drop queue). */
  fun close()
}
