package dev.librepocket.session

import dev.librepocket.chat.TranscriptSink
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * [TranscriptSink] that persists turns into a [SessionStore] session.
 *
 * Mapping: turn started -> `user` event, turn succeeded/cancelled -> `assistant`
 * event (cancel keeps the partial text so resume shows it), turn failed ->
 * `system` event (kept out of the chat replay, visible in export), retried ->
 * `retry` event, steer queued -> `steer` event, tool done -> `tool` event,
 * usage -> `system` event (accounting, P1 records only).
 *
 * The controller routes every call through its session-owned
 * [dev.librepocket.chat.OrderedTranscriptSink] (single session writer:
 * admission order is preserved, core events are durably acked before the
 * turn proceeds, and one logical turn owns exactly one `user` row with
 * retry notices bound to that same id). Implementations therefore observe
 * calls in order and must still stay best-effort themselves: failures here
 * must never propagate, so every call guards its store access as well.
 * Text is redacted again by [RoomSessionStore] on write.
 */
class SessionTranscriptSink(
    private val store: SessionStore,
    private val sessionId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : TranscriptSink {

    private suspend fun append(runId: String, kind: String, text: String) {
        try {
            store.appendEvent(
                TranscriptEvent(
                    sessionId = sessionId,
                    runId = runId,
                    kind = kind,
                    text = text,
                    createdAt = clock(),
                ),
            )
        } catch (e: CancellationException) {
            // Writer teardown: the session writer owns cancellation, so
            // propagate instead of swallowing it as a store failure.
            throw e
        } catch (_: Exception) {
            // Best effort: persistence must never break the chat loop.
        }
    }

    override suspend fun onTurnStarted(runId: String, text: String) {
        append(runId, "user", text)
    }

    override suspend fun onTurnSucceeded(runId: String, text: String) {
        append(runId, "assistant", text)
    }

    override suspend fun onTurnFailed(runId: String, error: String) {
        append(runId, "system", "turn $runId failed: $error")
    }

    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
        append(runId, "retry", "attempt $attempt/$maxAttempts after ${delayMs}ms")
    }

    override suspend fun onTurnCancelled(runId: String, partialText: String) {
        append(runId, "assistant", partialText)
    }

    override suspend fun onSteerQueued(text: String) {
        append("steer-${UUID.randomUUID()}", "steer", text)
    }

    override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
    ) {
        append(runId, "tool", "[tool:$name $argumentsJson]")
    }

    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
        append(runId, "system", "usage input=$inputTokens output=$outputTokens")
    }
}
