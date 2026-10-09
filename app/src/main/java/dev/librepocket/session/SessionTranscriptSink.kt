package dev.librepocket.session

import dev.librepocket.chat.TranscriptSink
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * [TranscriptSink] that persists turns into a [SessionStore] session.
 *
 * Mapping: turn started -> `user` event, turn succeeded -> `assistant` event
 * (`isPartial=0`), turn cancelled -> `assistant` event with `isPartial=1` (the
 * partial text was already preserved; Phase 3 adds the structured flag),
 * turn failed (three-arg) -> `assistant` event with `isPartial=1` plus
 * `failureReason` (partial fragment AND sanitized reason — Phase 3
 * implemented), legacy two-arg turn failed -> `system` event
 * (`turn <runId> failed: <reason>`, kept out of the chat replay, visible in
 * export), retried -> `retry` event, steer queued -> `steer` event, tool
 * done -> `tool` event, usage -> `system` event (accounting, P1 records only).
 *
 * A reasoning-sourced turn (`hasReasoning == true`) appends one extra
 * `reasoning` event with the same runId and empty text right after its
 * `assistant` event: the marker itself is empty (flag-only; no reasoning
 * payload touches the store), but visible reasoning deltas remain merged
 * into the assistant text and persist as usual (pre-existing behavior).
 * History replay restores
 * [dev.librepocket.chat.UiMessage.hasReasoning] so resumed reasoning-origin
 * turns still fail closed on tool-dependent / thinking-mode follow-ups
 * (ordinary no-tools follow-ups continue; see `requireNoReasoningHistory`).
 * Resume history here is display-only (rebuilt UiMessages, never fed back
 * into a provider request), so the restored flag is defense-in-depth only.
 *
 * The two appends (`assistant` + `reasoning`) are non-atomic: a crash
 * between them loses the flag (fail-open to plain-text continuation);
 * display-only, so no provider impact beyond the missing flag.
 *
 * The controller routes every call through its session-owned
 * [dev.librepocket.chat.OrderedTranscriptSink] (single session writer:
 * admission order is preserved, core events are durably acked before the
 * turn proceeds, and one logical turn owns exactly one `user` row with
 * retry notices bound to that same id). Implementations therefore observe
 * calls in order and must still stay best-effort themselves: failures here
 * must never propagate, so every call guards its store access as well.
 * Text is redacted again by [RoomSessionStore] on write.
 *
 * Cross-restart RUNNING → INTERRUPTED backfill lives in Phase 3 as well (see
 * `ChatSessionFactory.open`): a `system` marker row
 * (`turn <runId> interrupted`) that the chat replay already hides, so it
 * never pollutes normal history.
 */
class SessionTranscriptSink(
    private val store: SessionStore,
    private val sessionId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : TranscriptSink {

    private suspend fun append(
        runId: String,
        kind: String,
        text: String,
        isPartial: Boolean = false,
        failureReason: String? = null,
    ) {
        try {
            store.appendEvent(
                TranscriptEvent(
                    sessionId = sessionId,
                    runId = runId,
                    kind = kind,
                    text = text,
                    createdAt = clock(),
                    isPartial = isPartial,
                    failureReason = failureReason,
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

    override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) {
        append(runId, "assistant", text)
        if (hasReasoning) append(runId, "reasoning", "")
    }

    override suspend fun onTurnFailed(runId: String, error: String) {
        append(runId, "system", "turn $runId failed: $error")
    }

    override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
        append(runId, "assistant", partialText, isPartial = true, failureReason = error)
    }

    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
        append(runId, "retry", "attempt $attempt/$maxAttempts after ${delayMs}ms")
    }

    override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
        append(runId, "assistant", partialText, isPartial = true)
        if (hasReasoning) append(runId, "reasoning", "")
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
