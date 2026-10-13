package dev.librepocket.session

import dev.librepocket.chat.TranscriptSink
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * [TranscriptSink] that persists turns into a [SessionStore] session.
 *
 * Mapping: turn started -> `user` event, turn succeeded -> `assistant` event
 * (`isPartial=0`, `isFinal=1`), turn cancelled -> `assistant` event with `isPartial=1`
 * (the partial text was already preserved; Phase 3 adds the structured flag)
 * and `isFinal=1`, turn failed (three-arg) -> `assistant` event with `isPartial=1` plus
 * `failureReason` (partial fragment AND sanitized reason — Phase 3
 * implemented) and `isFinal=1`, legacy two-arg turn failed -> `system` event
 * (`turn <runId> failed: <reason>`, kept out of the chat replay, visible in
 * export), retried -> `retry` event, steer queued -> `steer` event, tool
 * done -> `tool` event, usage -> `system` event (accounting, P1 records only).
 *
 * A reasoning-sourced turn (`hasReasoning == true`) persists one extra
 * `reasoning` event with the same runId and empty text together with its
 * `assistant` event on success and on cancel: the marker itself is empty
 * (flag-only; no reasoning
 * payload touches the store), but visible reasoning deltas remain merged
 * into the assistant text and persist as usual (pre-existing behavior).
 * Failed turns never write a `reasoning` marker (their `assistant` row is
 * `isPartial=1`, so it never enters the provider request — display-only,
 * no provider impact).
 * History replay restores
 * [dev.librepocket.chat.UiMessage.hasReasoning] so resumed reasoning-origin
 * turns still fail closed on tool-dependent / thinking-mode follow-ups
 * (ordinary no-tools follow-ups continue; see `requireNoReasoningHistory`).
 * Resume history feeds the provider via `loadModelHistory` into
 * [dev.librepocket.chat.TurnController], so the restored flag is
 * provider-visible: a missing flag fail-opens to plain-text continuation
 * on a tool-dependent follow-up instead of failing closed.
 *
 * D15-3: the `assistant` + `reasoning` pair persists as one atomic batch
 * via [SessionStore.appendEvents] (single transaction on [RoomSessionStore],
 * snapshot + rollback on the in-memory test fake): a second-row failure
 * leaves zero rows, never an orphaned `assistant` that would read back as a
 * complete plain-text result. External [SessionStore] implementations that
 * keep the default sequential [SessionStore.appendEvents] fallback may still
 * leave a partial batch behind — callers must treat a throw as a
 * possibly-partial batch unless the store documents atomicity (fail-closed
 * handling on the caller side; the default itself is unchanged).
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

 * Stage F (implemented): [TranscriptEvent.isFinal] distinguishes the
 * logical-turn final result from per-attempt intermediate partials. Success,
 * terminal-failure and cancel assistant rows are final (`isFinal=1`); a
 * retryable failure's intermediate fragment uses the six-arg failure
 * overload with `isFinal=false` and never closes the family. A cancel that
 * lands on an already-terminalized attempt (retry backoff gap) uses
 * [onLogicalTurnCancelled]: one `system`-kind final mark
 * (`turn <logicalTurnId> cancelled`, `isFinal=1`) instead of a second
 * assistant row for the same attempt — hidden from the chat replay, visible
 * in export, and a terminal for the backfill scan.
 *
 * Core vs notice: [onTurnStarted], all [onTurnSucceeded] overloads, all
 * [onTurnFailed] overloads, all [onTurnCancelled] overloads and
 * [onLogicalTurnCancelled] are durable core events — a store failure
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
        isFinal: Boolean = true,
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
                    isFinal = isFinal,
                ),
            )
        } catch (e: CancellationException) {
            // Writer teardown: the session writer owns cancellation, so
            // propagate instead of swallowing it as a store failure.
            throw e
        }
    }

    /**
     * D15-3 atomic batch core write: persists every event as one
     * all-or-nothing unit via [SessionStore.appendEvents].
     * [RoomSessionStore] (single DB transaction) and the in-memory test
     * fake (snapshot + rollback) commit all rows or zero; an external
     * [SessionStore] keeping the default sequential fallback may leave the
     * earlier rows behind, so callers must treat a throw as a
     * possibly-partial batch unless the store documents atomicity
     * (fail-closed handling on the caller side; the default itself is
     * unchanged here). Store failures propagate so the writer fails the ack
     * instead of reporting a false durable write. Cancellation always
     * propagates.
     */
    private suspend fun appendDurableBatch(events: List<TranscriptEvent>) {
        try {
            store.appendEvents(events)
        } catch (e: CancellationException) {
            // Same teardown contract as [appendDurable]: propagate.
            throw e
        }
    }

    private fun coreEvent(
        runId: String,
        kind: String,
        text: String,
        createdAt: Long,
        isPartial: Boolean = false,
        failureReason: String? = null,
        parentRunId: String? = null,
        attemptIndex: Int? = null,
        isFinal: Boolean = true,
    ): TranscriptEvent = TranscriptEvent(
        sessionId = sessionId,
        runId = runId,
        kind = kind,
        text = text,
        createdAt = createdAt,
        isPartial = isPartial,
        failureReason = failureReason,
        parentRunId = parentRunId,
        attemptIndex = attemptIndex,
        isFinal = isFinal,
    )

    /** Best-effort notice write: persistence must never break the chat loop. */
    private suspend fun appendNotice(
        runId: String,
        kind: String,
        text: String,
        isPartial: Boolean = false,
        failureReason: String? = null,
        parentRunId: String? = null,
        attemptIndex: Int? = null,
        isFinal: Boolean = true,
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
                    isFinal = isFinal,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: notices never break the chat loop.
        }
    }

    override suspend fun onTurnStarted(runId: String, text: String) {
        appendDurable(runId, "user", text, isFinal = true)
    }

    override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(2)
        batch.add(coreEvent(runId, "assistant", text, now, isFinal = true))
        if (hasReasoning) batch.add(coreEvent(runId, "reasoning", "", now, isFinal = true))
        appendDurableBatch(batch)
    }

    override suspend fun onTurnSucceeded(
        runId: String,
        text: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
    ) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(2)
        batch.add(coreEvent(runId, "assistant", text, now, parentRunId = parentRunId, attemptIndex = attemptIndex, isFinal = true))
        if (hasReasoning) {
            batch.add(coreEvent(runId, "reasoning", "", now, parentRunId = parentRunId, attemptIndex = attemptIndex, isFinal = true))
        }
        appendDurableBatch(batch)
    }

    override suspend fun onTurnFailed(runId: String, error: String) {
        appendDurable(runId, "system", "turn $runId failed: $error", isFinal = true)
    }

    override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
        appendDurable(runId, "assistant", partialText, isPartial = true, failureReason = error, isFinal = true)
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
            isFinal = true,
        )
    }

    override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        isFinal: Boolean,
    ) {
        appendDurable(
            runId, "assistant", partialText,
            isPartial = true, failureReason = error,
            parentRunId = parentRunId, attemptIndex = attemptIndex,
            isFinal = isFinal,
        )
    }

    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
        appendNotice(runId, "retry", "attempt $attempt/$maxAttempts after ${delayMs}ms", isFinal = true)
    }

    override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(2)
        batch.add(coreEvent(runId, "assistant", partialText, now, isPartial = true, isFinal = true))
        if (hasReasoning) batch.add(coreEvent(runId, "reasoning", "", now, isFinal = true))
        appendDurableBatch(batch)
    }

    override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
    ) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(2)
        batch.add(
            coreEvent(
                runId, "assistant", partialText, now,
                isPartial = true, parentRunId = parentRunId, attemptIndex = attemptIndex,
                isFinal = true,
            ),
        )
        if (hasReasoning) {
            batch.add(coreEvent(runId, "reasoning", "", now, parentRunId = parentRunId, attemptIndex = attemptIndex, isFinal = true))
        }
        appendDurableBatch(batch)
    }

    /**
     * Backoff-gap cancel mark: one `system`-kind final row with the idempotent
     * exact-text `turn <logicalTurnId> cancelled` (same pattern as
     * [dev.librepocket.agent.ui.chat.interruptedMarkerText]; reopen treats it
     * as the family terminal, so no false INTERRUPTED and a second open adds
     * zero rows). This sink never dedups — a repeated call rewrites the mark;
     * idempotency rests on the controller dedup plus the backfill skip.
     */
    override suspend fun onLogicalTurnCancelled(
        logicalTurnId: String,
        attemptRunId: String,
        partialText: String,
        attemptIndex: Int?,
    ) {
        // Deduped cancel: the attempt already owns a durable partial row, so
        // bind one system-kind final mark to the logical family instead of a
        // second assistant row for the same attempt. Exact text pattern (like
        // the INTERRUPTED marker): hidden from the chat replay (system kind),
        // visible in export, terminal for the backfill scan.
        appendDurable(logicalTurnId, "system", "turn $logicalTurnId cancelled", isFinal = true)
    }

    override suspend fun onSteerQueued(text: String) {
        appendNotice("steer-${UUID.randomUUID()}", "steer", text, isFinal = true)
    }

    override suspend fun onToolDone(
        runId: String,
        toolIndex: Int,
        id: String,
        name: String,
        argumentsJson: String,
    ) {
        appendNotice(runId, "tool", "[tool:$name $argumentsJson]", isFinal = true)
    }

    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
        appendNotice(runId, "system", "usage input=$inputTokens output=$outputTokens", isFinal = true)
    }
}
