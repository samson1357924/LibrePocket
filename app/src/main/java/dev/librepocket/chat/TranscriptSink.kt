package dev.librepocket.chat

/**
 * Minimal persistence port. [TurnController] routes every call through its
 * session-owned [OrderedTranscriptSink] (bounded channel, single writer, core
 * events durably acked), so implementations observe admission order and must
 * only stay best-effort themselves (never throw into the chat loop).
 *
 * [onTurnCancelled] still runs off the [dev.librepocket.chat.ChatSession.cancel]
 * fast path: cancel only flips in-memory state, while this callback is
 * written non-cancellably through the session writer afterwards.
 *
 * Tool and usage events come from the unified M1 [dev.librepocket.provider.StreamEvent]
 * union, which [TurnController] consumes directly: every `ToolDelta`/`ToolDone`
 * is recorded via [onToolDone] (deltas without a terminal `ToolDone` are
 * flushed as pending records, never dropped) and every `Usage` via [onUsage].
 */
interface TranscriptSink {
  suspend fun onTurnStarted(runId: String, text: String)
  /**
   * @param hasReasoning true when the finished assistant block is
   *   reasoning-sourced (see [UiMessage.hasReasoning]); sinks persist it as
   *   a payload-free marker (marker itself empty, flag-only) so resume
   *   restores the flag instead of replaying a reasoning origin as plain
   *   text. Visible reasoning deltas remain merged into the assistant text
   *   and persist as usual (pre-existing behavior).
   */
  suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean = false)
  suspend fun onTurnFailed(runId: String, error: String)
  /**
   * Phase 3 (implemented): terminal failure that keeps BOTH the partial
   * fragment ([partialText]) and the sanitized [error]. Sinks persist this as
   * an `assistant` row with `isPartial=1` plus `failureReason` (see
   * [dev.librepocket.session.SessionTranscriptSink]).
   *
   * Minimal-churn overload: the default forwards to the legacy two-arg form
   * (reason only) so existing fakes and probes that override only
   * [onTurnFailed] keep compiling and keep their assertions; production paths
   * ([dev.librepocket.chat.TurnController]) always call this three-arg form.
   * The legacy two-arg form stays as the reason-only (system-row) record.
   */
  suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
    onTurnFailed(runId, error)
  }
  suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long)
  /** Same [hasReasoning] marker contract as [onTurnSucceeded] for partial text. */
  suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean = false)
  suspend fun onSteerQueued(text: String)

  /** One tool call finished (P1 records only, never executes). */
  suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String)

  /** Token usage for this turn (billing/context accounting; P1 records only). */
  suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?)
}

/** Default sink: drops everything (used until the M4 store lands). */
class NoOpTranscriptSink : TranscriptSink {
  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
}
