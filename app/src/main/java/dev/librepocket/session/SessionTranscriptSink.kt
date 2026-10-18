package dev.librepocket.session

import dev.librepocket.chat.TranscriptSink
import dev.librepocket.redact.Redactor
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
 * done -> `tool` event, usage -> one `system` event per attempt (authoritative
 * last-wins #84: pre-terminal `onUsage` calls merge by runId and flush with
 * the attempt terminal, so an Anthropic dual or a flood persists exactly one
 * row with the final values; a post-terminal trailing call appends once,
 * further trailing calls drop).
 *
 * A reasoning-sourced turn (`hasReasoning == true`) persists one extra
 * `reasoning` event with the same runId and empty text together with its
 * `assistant` event on success, on cancel, and (R18-3) on failure: the marker
 * itself is empty
 * (flag-only; no reasoning
 * payload touches the store), but visible reasoning deltas remain merged
 * into the assistant text and persist as usual (pre-existing behavior).
 * Legacy three-arg / five-arg failure forms (no `hasReasoning` parameter)
 * never write a marker (compat); the seven-arg `isFinal` + `hasReasoning`
 * form does. Failed `assistant` rows stay `isPartial=1` and never enter the
 * provider request (display-only) — the marker exists so UI replay restores
 * the flag instead of reopening a reasoning partial as plain text.
 * History replay restores
 * [dev.librepocket.chat.UiMessage.hasReasoning] so resumed reasoning-origin
 * turns still fail closed on tool-dependent / thinking-mode follow-ups
 * (ordinary no-tools follow-ups continue; see `requireNoReasoningHistory`).
 * Resume history feeds the provider via `loadModelHistory` into
 * [dev.librepocket.chat.TurnController], so the restored flag is
 * provider-visible. Phase 4: a truncation-suspect resume (`dropped > 0`)
 * conservatively flags every kept assistant (fail-closed on
 * tool-dependent follow-ups); only a non-truncated legacy orphan without a
 * marker still resumes as plain text (compat fail-open, pinned by tests).
 *
 * D15-3: the `assistant` + `reasoning` pair persists as one atomic batch
 * via [SessionStore.appendEvents] (single transaction on [RoomSessionStore],
 * snapshot + rollback on the in-memory test fake): a second-row failure
 * leaves zero rows, never an orphaned `assistant` that would read back as a
 * complete plain-text result. Phase2 marker-first: the batch order is
 * [`reasoning`?, `assistant`] (same partial-first precedent as
 * [TranscriptSink.onTurnFailedAndCancelled]), so an external [SessionStore]
 * keeping the default sequential [SessionStore.appendEvents] fallback that
 * fails on the 2nd row leaves only an orphan `reasoning` marker behind —
 * harmless by construction (no `user` family, never model context, ignored
 * by `findDanglingRunIds`, bounded UI `pendingMarkers`), and the family
 * stays dangling (fail-closed) instead of completing as plain text.
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
 * false durable write. [onTurnRetried], [onSteerQueued] and [onToolDone] stay
 * best-effort notices: failures are swallowed and never break the chat loop.
 * [onUsage] itself never throws (best-effort call), but the merged row is
 * persisted durably with the attempt terminal (#84), never one row per call.
 * Cancellation propagates for core writes. Post-core accounting paths
 * ([flushBufferedUsageBestEffort]) swallow it so a cancelled best-effort
 * usage write cannot negate an already-committed terminal ack (P1 #2); the
 * separate trailing [onUsage] entry follows the general propagation contract.
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

    companion object {
        /**
         * Per-run usage-event flood cap (#84): at most this many `onUsage`
         * calls per runId are merged; further pre-terminal calls are ignored
         * (frozen, never a failure). Legitimate callers emit 1 per attempt
         * (see `TurnController`, which already coalesces), so 128 leaves wide
         * headroom while bounding merge CPU against a direct-sink flood.
         */
        const val MAX_USAGE_EVENTS_PER_RUN = 128

        /**
         * Bound on distinct runIds with buffered pre-terminal usage (#84):
         * further new runIds are dropped best-effort (usage is accounting,
         * never load-bearing). Terminal flushes remove entries, so production
         * (one live attempt) never trips this.
         */
        const val MAX_PENDING_USAGE_RUNS = 256

        /**
         * Bound on retained failed-terminal usage rows (#94 P2).
         */
        const val MAX_RETAINED_FAILED_USAGE_RUNS = 256
    }

    /** Pre-terminal authoritative usage per runId (#84, last-wins merged). */
    private data class PendingUsage(val inputTokens: Int?, val outputTokens: Int?)

    /** Buffered usages not yet flushed with their attempt terminal. Single-writer (Ordered), insertion-ordered. Guarded by the ordered single-writer discipline of the hosting session writer — never touch from a second writer. */
    private val pendingUsage = LinkedHashMap<String, PendingUsage>()

    private val retainedFailedUsage = LinkedHashMap<String, PendingUsage>()

    /** Per-run pre-terminal onUsage arrival counts for the flood cap. Same single-writer guard as [pendingUsage]. */
    private val usageSeen = HashMap<String, Int>()

    /**
     * RunIds that already own a terminal row. A post-terminal `onUsage`
     * (trailing accounting, e.g. legacy call order) is appended immediately
     * once per runId instead of buffering forever; pre-terminal floods still
     * merge to one row via [pendingUsage]. Bounded by the session's terminal
     * count (same discipline as the controller's terminal sets); deliberately
     * uncapped (an eviction would break the once-per-runId dedup — a bounded
     * residue of small ids, not payload). Same single-writer guard.
     */
    private val terminalizedRuns = HashSet<String>()

    /** RunIds that already consumed their single trailing post-terminal usage write (rate-limit). Same bound and single-writer guard as [terminalizedRuns]. */
    private val trailingUsageWritten = HashSet<String>()

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
     * fake (snapshot + rollback) commit all rows or zero. Phase2 marker-first
     * (see the class note): reasoning batches lead with the marker, so an
     * external [SessionStore] keeping the default sequential fallback leaves
     * at most an orphan marker (fail-closed) on a mid-batch throw — callers
     * must still treat a throw as a possibly-partial batch unless the store
     * documents atomicity, but the partial is harmless by construction.
     * Store failures propagate so the writer fails the ack
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

    /**
     * Post-ack authoritative usage flush (#84 follow-up, fail-closed
     * accounting): persists the run's buffered pre-terminal usage as one
     * `system` row AFTER the core terminal ack, best-effort (failures
     * swallowed by [appendNotice], never break the terminal — usage is
     * accounting, never load-bearing). Call only after the core write
     * returns: the usage row is deliberately NOT part of the terminal unit,
     * so a usage-write failure on a non-atomic store fallback can never
     * leave a committed final behind a thrown terminal (see [onUsage]).
     * On core failure the entry stays buffered (bounded residue, same
     * discipline as [terminalizedRuns]) so a same-runId terminal retry
     * still owns its authoritative row. Marks [trailingUsageWritten] when a
     * row is flushed, so a later trailing call drops (exactly one usage row
     * per runId ever).
     */
    override suspend fun flushBufferedUsageBestEffort(
        runId: String,
        parentRunId: String?,
        attemptIndex: Int?,
    ) {
        // Mutual exclusion with the trailing path: if a trailing usage already
        // landed for this runId, drop the buffered entry (no duplicate row).
        if (runId in trailingUsageWritten) {
            pendingUsage.remove(runId)
            retainedFailedUsage.remove(runId)
            usageSeen.remove(runId)
            return
        }
        val usage = pendingUsage[runId] ?: retainedFailedUsage[runId] ?: return
        var ok = false
        try {
            store.appendEvent(
                TranscriptEvent(
                    sessionId = sessionId,
                    runId = runId,
                    kind = "system",
                    text = usageText(usage),
                    createdAt = clock(),
                    isPartial = false,
                    failureReason = null,
                    parentRunId = parentRunId,
                    attemptIndex = attemptIndex,
                    isFinal = true,
                ),
            )
            ok = true
        } catch (e: Throwable) {
            if (e is Error) throw e
        }
        if (ok) {
            pendingUsage.remove(runId)
            retainedFailedUsage.remove(runId)
            usageSeen.remove(runId)
            trailingUsageWritten.add(runId)
        } else {
            pendingUsage.remove(runId)
            usageSeen.remove(runId)
            retainedFailedUsage[runId] = usage
            while (retainedFailedUsage.size > MAX_RETAINED_FAILED_USAGE_RUNS) {
                val oldest = retainedFailedUsage.keys.firstOrNull() ?: break
                retainedFailedUsage.remove(oldest)
            }
        }
    }

    private fun usageText(usage: PendingUsage): String =
        "usage input=${usage.inputTokens} output=${usage.outputTokens}"

    /**
     * Marks [runId] terminalized for the trailing-usage rule (see [onUsage]).
     */
    private fun markTerminalized(runId: String) {
        terminalizedRuns.add(runId)
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
        val batch = ArrayList<TranscriptEvent>(3)
        // Phase2 marker-first: the payload-free `reasoning` marker leads so a
        // non-atomic fallback store (default sequential loop) that fails on
        // the 2nd row leaves only an orphan marker behind — harmless (no user
        // family, never model context, ignored by findDanglingRunIds, bounded
        // UI pendingMarkers) instead of an orphan final `assistant` that would
        // resume as a complete plain-text result (fail-open). Atomic stores
        // (Room single tx, fake snapshot + rollback) still commit all-or-zero.
        if (hasReasoning) batch.add(coreEvent(runId, "reasoning", "", now, isFinal = true))
        batch.add(coreEvent(runId, "assistant", text, now, isFinal = true))
        appendDurableBatch(batch)
        // #84 follow-up: authoritative usage flushes best-effort after the
        // core ack (see flushBufferedUsageBestEffort), never in the batch.
        markTerminalized(runId)
    }

    override suspend fun onTurnSucceeded(
        runId: String,
        text: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
    ) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(3)
        // Phase2 marker-first (see the 3-arg form above): orphan marker is
        // harmless, orphan assistant would fail open.
        if (hasReasoning) {
            batch.add(coreEvent(runId, "reasoning", "", now, parentRunId = parentRunId, attemptIndex = attemptIndex, isFinal = true))
        }
        batch.add(coreEvent(runId, "assistant", text, now, parentRunId = parentRunId, attemptIndex = attemptIndex, isFinal = true))
        appendDurableBatch(batch)
        // #84 follow-up: authoritative usage flushes best-effort after the
        // core ack (see flushBufferedUsageBestEffort), never in the batch.
        markTerminalized(runId)
    }

    /**
     * Legacy two-arg failure (compat): `system` row `turn <runId> failed:
     * <reason>`. Phase 4: the reason is pre-sanitized with [Redactor.redactError]
     * (stricter URL/query/token stripping) for parity with `failureReason`
     * rows (which [RoomSessionStore] re-sanitizes with `redactError` on
     * write); the store's text-path `redact()` second pass is then a no-op.
     */
    override suspend fun onTurnFailed(runId: String, error: String) {
        appendDurable(runId, "system", "turn $runId failed: ${Redactor.redactError(error)}", isFinal = true)
        markTerminalized(runId)
    }

    override suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
        appendDurable(runId, "assistant", partialText, isPartial = true, failureReason = error, isFinal = true)
        markTerminalized(runId)
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
        markTerminalized(runId)
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
        markTerminalized(runId)
    }

    /**
     * R18-3 reasoning-aware failed partial: `assistant` + optional
     * payload-free `reasoning` marker as one [appendDurableBatch] unit (D15-3
     * pattern: Room single tx, fake snapshot + rollback; 1 row without
     * reasoning, 2 rows with it). The marker inherits `parentRunId` /
     * `attemptIndex` / `isFinal` verbatim (flag-only, empty text).
     */
    override suspend fun onTurnFailed(
        runId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        isFinal: Boolean,
        hasReasoning: Boolean,
    ) {
        if (!hasReasoning) {
            appendDurable(
                runId, "assistant", partialText,
                isPartial = true, failureReason = error,
                parentRunId = parentRunId, attemptIndex = attemptIndex,
                isFinal = isFinal,
            )
            markTerminalized(runId)
            return
        }
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(3)
        // Phase2 marker-first (see onTurnSucceeded): the marker inherits
        // `parentRunId` / `attemptIndex` / `isFinal` verbatim (flag-only,
        // empty text) and leads, so a non-atomic fallback leaves only an
        // orphan marker (fail-closed) instead of an orphan partial assistant.
        batch.add(
            coreEvent(
                runId, "reasoning", "", now,
                parentRunId = parentRunId, attemptIndex = attemptIndex,
                isFinal = isFinal,
            ),
        )
        batch.add(
            coreEvent(
                runId, "assistant", partialText, now,
                isPartial = true, failureReason = error,
                parentRunId = parentRunId, attemptIndex = attemptIndex,
                isFinal = isFinal,
            ),
        )
        // #84 follow-up: authoritative usage flushes best-effort after the
        // core ack (see flushBufferedUsageBestEffort), never in the batch.
        appendDurableBatch(batch)
        markTerminalized(runId)
    }

    override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) {
        appendNotice(runId, "retry", "attempt $attempt/$maxAttempts after ${delayMs}ms", isFinal = true)
    }

    override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(3)
        // Phase2 marker-first (see onTurnSucceeded): orphan marker harmless.
        if (hasReasoning) batch.add(coreEvent(runId, "reasoning", "", now, isFinal = true))
        batch.add(coreEvent(runId, "assistant", partialText, now, isPartial = true, isFinal = true))
        appendDurableBatch(batch)
        // #84 follow-up: authoritative usage flushes best-effort after the
        // core ack (see flushBufferedUsageBestEffort), never in the batch.
        markTerminalized(runId)
    }

    override suspend fun onTurnCancelled(
        runId: String,
        partialText: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
    ) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(3)
        // Phase2 marker-first (see onTurnSucceeded): orphan marker harmless.
        if (hasReasoning) {
            batch.add(coreEvent(runId, "reasoning", "", now, parentRunId = parentRunId, attemptIndex = attemptIndex, isFinal = true))
        }
        batch.add(
            coreEvent(
                runId, "assistant", partialText, now,
                isPartial = true, parentRunId = parentRunId, attemptIndex = attemptIndex,
                isFinal = true,
            ),
        )
        appendDurableBatch(batch)
        // #84 follow-up: authoritative usage flushes best-effort after the
        // core ack (see flushBufferedUsageBestEffort), never in the batch.
        markTerminalized(runId)
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
        // #84 follow-up: any still-pending usage (mid-stream cancel) flushes
        // best-effort after the cancel mark — the attempt's row first, then
        // the logical id's if distinct (see flushBufferedUsageBestEffort).
        markTerminalized(logicalTurnId)
        markTerminalized(attemptRunId)
    }

    /**
     * R18-1 Stage 2 cancel-intent notice (best-effort, non-terminal).
     * `retry` kind never closes a family for `findDanglingRunIds`, so a kill
     * before the deferred system/batch leaves user(+partial)+notice dangling
     * (reopen INTERRUPTED) instead of silently closing without the partial.
     */
    override suspend fun onTurnCancelRequested(
        logicalTurnId: String,
        attemptRunId: String,
        attemptIndex: Int?,
    ) {
        appendNotice(
            logicalTurnId, "retry",
            "cancel requested for attempt ${attemptIndex ?: 0} (attempt $attemptRunId)",
            isFinal = true,
        )
    }

    /**
     * R18-1 Stage 2 atomic FAILED batch (single [store.appendEvents] unit).
     * [RoomSessionStore] (single DB transaction) and the in-memory test fake
     * (snapshot + rollback) commit both rows or zero; an external store keeping
     * the default sequential fallback may leave the partial behind — still
     * fail-closed (non-final partial alone never closes the family, reopen
     * backfills INTERRUPTED). Single entry.runId key is the attempt id (see
     * OrderedTranscriptSink): first attempt (cancelId == logical) observes
     * user(1)+batch(1)=2 (>=2 holds); fresh retry id observes batch(1)=1
     * (>=1 holds).
     *
     * R18-3: [hasReasoning] adds the payload-free `reasoning` marker to the
     * partial inside the same batch (same `runId`, empty text,
     * `parentRunId`/`attemptIndex` inherited, `isFinal=false` like the
     * partial, flag-only): 2 rows without reasoning vs 3 rows with it.
     */
    override suspend fun onTurnFailedAndCancelled(
        logicalTurnId: String,
        attemptRunId: String,
        partialText: String,
        error: String,
        parentRunId: String?,
        attemptIndex: Int?,
        hasReasoning: Boolean,
    ) {
        val now = clock()
        val batch = ArrayList<TranscriptEvent>(if (hasReasoning) 4 else 3)
        batch.add(
            coreEvent(
                attemptRunId, "assistant", partialText, now,
                isPartial = true, failureReason = error,
                parentRunId = parentRunId, attemptIndex = attemptIndex,
                isFinal = false,
            ),
        )
        if (hasReasoning) {
            batch.add(
                coreEvent(
                    attemptRunId, "reasoning", "", now,
                    parentRunId = parentRunId, attemptIndex = attemptIndex,
                    isFinal = false,
                ),
            )
        }
        // #84 follow-up: the attempt's authoritative usage flushes
        // best-effort after the atomic batch ack (see
        // flushBufferedUsageBestEffort), never inside it — so the
        // racing-cancel path still owns one row without endangering the
        // batch's fail-closed shape.
        batch.add(
            coreEvent(
                logicalTurnId, "system", "turn $logicalTurnId cancelled", now,
                isFinal = true,
            ),
        )
        appendDurableBatch(batch)
        markTerminalized(logicalTurnId)
        markTerminalized(attemptRunId)
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

    /**
     * Authoritative per-attempt usage (#84): pre-terminal calls merge
     * last-wins by [runId] (null fields keep the previous value) and flush
     * as exactly one `system` row after the attempt's terminal ack (see
     * [flushBufferedUsageBestEffort]) — an Anthropic dual or a 100-event
     * flood persists one row with the final values. The row is deliberately
     * outside the terminal unit: on a non-atomic store fallback a usage
     * failure can never strand a committed final behind a thrown terminal
     * (fail-closed), and usage is accounting, never load-bearing.
     * Pre-terminal floods are capped ([MAX_USAGE_EVENTS_PER_RUN], frozen past
     * the cap, never a failure) and distinct buffered runIds are capped
     * ([MAX_PENDING_USAGE_RUNS], best-effort drop). A post-terminal trailing
     * call (legacy call order: terminal already written) appends immediately
     * once per runId — unless the terminal already flushed this runId's row
     * (see [flushBufferedUsageBestEffort]), in which case it drops; further
     * trailing calls drop (rate-limit). The call
     * itself never throws (best-effort); CancellationException propagates.
     */
    override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) {
        if (runId in terminalizedRuns) {
            if (runId in trailingUsageWritten) {
                pendingUsage.remove(runId)
                retainedFailedUsage.remove(runId)
                usageSeen.remove(runId)
                return
            }
            // Merge with any retained buffered usage (active or
            // failed-terminal) so an authoritative earlier value is not
            // replaced by nulls.
            val prev = pendingUsage[runId] ?: retainedFailedUsage[runId]
            val inTok = inputTokens ?: prev?.inputTokens
            val outTok = outputTokens ?: prev?.outputTokens
            var ok = false
            try {
                store.appendEvent(
                    TranscriptEvent(
                        sessionId = sessionId,
                        runId = runId,
                        kind = "system",
                        text = "usage input=$inTok output=$outTok",
                        createdAt = clock(),
                        isPartial = false,
                        failureReason = null,
                        isFinal = true,
                    ),
                )
                ok = true
            } catch (e: Throwable) {
                if (e is Error || e is CancellationException) throw e
                // Best-effort: allow a later retry.
            }
            if (ok) {
                trailingUsageWritten.add(runId)
                pendingUsage.remove(runId)
                retainedFailedUsage.remove(runId)
                usageSeen.remove(runId)
            } else {
                // Retain in the failed-terminal bucket for a later retry,
                // never the active quota.
                pendingUsage.remove(runId)
                usageSeen.remove(runId)
                val merged = PendingUsage(inputTokens = inTok, outputTokens = outTok)
                retainedFailedUsage[runId] = merged
                while (retainedFailedUsage.size > MAX_RETAINED_FAILED_USAGE_RUNS) {
                    val oldest = retainedFailedUsage.keys.firstOrNull() ?: break
                    retainedFailedUsage.remove(oldest)
                }
            }
            return
        }
        if (pendingUsage[runId] == null && pendingUsage.size >= MAX_PENDING_USAGE_RUNS) return
        val seen = (usageSeen[runId] ?: 0) + 1
        if (seen > MAX_USAGE_EVENTS_PER_RUN) return
        usageSeen[runId] = seen
        val prev = pendingUsage[runId]
        pendingUsage[runId] = PendingUsage(
            inputTokens = inputTokens ?: prev?.inputTokens,
            outputTokens = outputTokens ?: prev?.outputTokens,
        )
    }
}
