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
 * 3. [steer] never cancels the current turn; the instruction is queued FIFO
 *    and sent as the next user message after the current turn fully ends.
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
   * N1 ownership: pass the caller's operation id as [opId] so a [TurnStart.Queued]
   * verdict stays reclaimable via [drainQueued]. Queued is NOT acceptance.
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
   * Steering: queue an instruction for the next round.
   * Never cancels the current HTTP request or the current turn; the queued
   * instruction is sent automatically once the current turn fully ends.
   */
  fun steer(text: String)

  /** Release (close HTTP call, drop queue). */
  fun close()
}
