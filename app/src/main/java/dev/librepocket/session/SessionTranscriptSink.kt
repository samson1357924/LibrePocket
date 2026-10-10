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
 * The controller routes every call through its session-owned
 * [dev.librepocket.chat.OrderedTranscriptSink] (single session writer:
 * admission order is preserved, core events are durably acked before the
 * turn proceeds, and one logical turn owns exactly one `user` row with
 * retry notices bound to that same id).
 *
 * Stage C (implemented): attempt-bound assistant terminals carry
 * `parentRunId=logicalTurnId` (null when the attempt reuses the logical id)
 * plus the 0-based `attemptIndex`, so the logical family
 * (`parentRunId ?: runId`) owns one user row, N attempt assistant rows
 * (retried partials included), and the retry notices. Every retryable
 * failure first persists its partial as an `isPartial=1` terminal before the
 * retry notice, so reopen/export never loses the intermediate fragment.
 *
 * Core vs notice: [onTurnStarted], [onTurnSucceeded], both [onTurnFailed]
 * overloads and [onTurnCancelled] are durable core events — a store failure
 * propagates so the session writer fails the ack instead of reporting a
 * false durable write. [onTurnRetried], [onSteerQueued], [onToolDone] and
 * [onUsage] stay best-effort notices: failures are swallowed and never
 * break the chat loop. Cancellation always propagates.
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

    /** Durable core write: store failures propagate so the writer fails the ack. */
    private suspend fun appendDurable(
        runId: String,
        kind: String,
        text: String,
        isPartial: Boolean = false,
        failureReason: String? = null,
        parentRunId: String? = null,
        attemptIndex: Int? = null,
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
                    parentRunId = parentRunId,
                    attemptIndex = attemptIndex,
                ),
            )
        } catch (e: CancellationException) {
            // Writer teardown: the session writer owns cancellation, so
            // propagate instead of swallowing it as a store failure.
            throw e
        }
    }

    /** Best-effort notice write: persistence must never break the chat loop. */
    private suspend fun appendNotice(
        runId: String,
        kind: String,
        text: String,
        isPartial: Boolean = false,
        failureReason: String? = null,
        parentRunId: String? = null,
        attemptIndex: Int? = null,
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
                    parentRunId = parentRunId,
                    attemptIndex = attemptIndex,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: notices never break the chat loop.
        }
    }

    override suspend fun onTurnStarted(runId: String, text: String) {
        appendDurable(runId, "user", text)
    }

    override suspend fun onTurnSucceeded(runId: String, text: String) {
        appendDurable(runId, "assistant", text)
    }

    override suspend fun onTurnSucceeded(runId: String, text: String, parentRunId: String?, attemptIndex: Int?) {
        appendDurable(runId, "assistant", text, parentRunId = parentRunId, attemptIndex = attemptIndex)
    }

    override suspend fun onTurnFailed(runId: String, error: String) {
        appendDurable(runId, "system", "turn $runId failed: $error")
    }

    override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
        appendDurable(runId, "assistant", partialText, isPartial = true, failureReason = error)
    }

    override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
    ) {
        appendDurable(
            runId, "assistant", partialText,
            isPartial = true, failureReason = error,
            parentRunId = parentRunId, attemptIndex = attemptIndex,
        )
    }

    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
        appendNotice(runId, "retry", "attempt $attempt/$maxAttempts after ${delayMs}ms")
    }

    override suspend fun onTurnCancelled(runId: String, partialText: String) {
        appendDurable(runId, "assistant", partialText, isPartial = true)
    }

    override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        parentRunId: String?,
        attemptIndex: Int?,
    ) {
        appendDurable(
            runId, "assistant", partialText,
            isPartial = true, parentRunId = parentRunId, attemptIndex = attemptIndex,
        )
    }

    override suspend fun onSteerQueued(text: String) {
        appendNotice("steer-${UUID.randomUUID()}", "steer", text)
    }

    override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
    ) {
        appendNotice(runId, "tool", "[tool:$name $argumentsJson]")
    }

    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
        appendNotice(runId, "system", "usage input=$inputTokens output=$outputTokens")
    }
}
