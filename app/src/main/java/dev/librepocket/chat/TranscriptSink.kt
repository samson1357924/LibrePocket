package dev.librepocket.chat

/**
 * Minimal persistence port (M4 stub). All calls are fire-and-forget from the
 * chat loop and must never run on the [ChatSession.cancel] path — cancel only
 * flips in-memory state, while these callbacks run asynchronously afterwards.
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
   *   a payload-free marker so resume restores the flag instead of replaying
   *   a reasoning origin as plain text.
   */
  suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean = false)
  suspend fun onTurnFailed(runId: String, error: String)
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
