package dev.librepocket.chat

/**
 * Minimal persistence port. [TurnController] routes every call through its
 * session-owned [OrderedTranscriptSink] (bounded channel, single writer, core
 * events durably acked), so implementations observe admission order.
 *
 * External-sink contract (S5, fail-closed; Current, 已實作): production
 * ([dev.librepocket.chat.TurnController] →
 * [dev.librepocket.session.SessionTranscriptSink]) always calls the full
 * forms (attempt-bound success/failure/cancel with `parentRunId` /
 * `attemptIndex`, plus `isFinal` / `hasReasoning` where present). External
 * [TranscriptSink] implementations MUST override every overload they can
 * receive — the lossy interface defaults below throw
 * [UnsupportedOperationException] instead of downgrading (never a silent
 * `isFinal=false` → final write, never a dropped `parentRunId` /
 * `attemptIndex` linkage, never a dropped `hasReasoning` marker). A legacy
 * sink that overrides only the 2-arg / 3-arg base forms fails closed (throws,
 * zero rows) on retryable/non-final/reasoning calls instead of prematurely
 * closing the logical family. First-attempt shape (`parentRunId == null` and
 * `attemptIndex == null || attemptIndex == 0`) is lossless and still forwards
 * to the base form — production's ordered path always sends `attemptIndex=0`
 * on the first attempt, so only true linkage (`parentRunId != null` or
 * `attemptIndex != 0`) throws.
 *
 * Core vs notice: [onTurnStarted], all [onTurnSucceeded] overloads, all
 * [onTurnFailed] overloads, all [onTurnCancelled] overloads and
 * [onLogicalTurnCancelled] are durable core events — a store failure
 * propagates so the session writer fails the ack instead of reporting a
 * false durable write. [onTurnRetried], [onSteerQueued], [onToolDone] and
 * [onUsage] stay best-effort notices: failures are swallowed and never
 * break the chat loop. Cancellation always propagates.
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
  /**
   * Stage C (implemented): attempt-bound success terminal linked to its
   * logical turn. [parentRunId] is the logical turn id (null when
   * `runId` already is the logical id, i.e. the first attempt);
   * [attemptIndex] is the 0-based attempt number. S5 fail-closed: the default
   * throws [UnsupportedOperationException] when true attempt linkage would be
   * dropped (`parentRunId != null || (attemptIndex != null && attemptIndex != 0)`)
   * instead of writing a single-id row; the first-attempt shape
   * (`parentRunId == null` with `attemptIndex == null || attemptIndex == 0`,
   * which is what production sends on attempt 0) still forwards to the base
   * form. Production ([dev.librepocket.chat.TurnController] →
   * [dev.librepocket.session.SessionTranscriptSink]) always calls this form.
   *
   * Stage F: success rows are always final (`isFinal=1`).
   */
  suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean = false,
  ) {
    if (parentRunId != null || (attemptIndex != null && attemptIndex != 0)) {
      throw UnsupportedOperationException("TranscriptSink: override 5-arg onTurnSucceeded to preserve attempt linkage")
    }
    onTurnSucceeded(runId, text, hasReasoning)
  }
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
   *
   * Marker contract: the legacy three-arg / five-arg forms below never
   *   persist a `reasoning` marker (compat — the failed `assistant` row is
   *   `isPartial=1`, so it never enters the provider request: display-only,
   *   no provider impact). A reasoning-sourced failure uses the seven-arg
   *   `isFinal` + `hasReasoning` form below, which persists `assistant` +
   *   payload-free `reasoning` marker atomically (same D15-3 batch pattern as
   *   success/cancel); UI replay restores the flag via the exact-`runId`
   *   marker, so a missing marker would reopen `hasReasoning=true` as false.
   *
   * Stage F: this three-arg form is always final (`isFinal=1`).
   */
  suspend fun onTurnFailed(runId: String, partialText: String, error: String) {
    onTurnFailed(runId, error)
  }
  /**
   * Stage C (implemented): attempt-bound failure terminal (including retried
   * partials written before the retry notice) linked to its logical turn.
   * Same [parentRunId]/[attemptIndex] semantics as the success overload. S5
   * fail-closed: the default throws [UnsupportedOperationException] when true
   * attempt linkage would be dropped (`parentRunId != null ||
   * `(attemptIndex != null && attemptIndex != 0)`) instead of writing a
   * single-id row; the first-attempt shape (`null`, `null`/`0`) still forwards.
   *
   * Stage F: this five-arg form is always final (`isFinal=1`). Retryable
   * intermediate partials (`isFinal=0`) use the six-arg form below.
   */
  suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) {
    if (parentRunId != null || (attemptIndex != null && attemptIndex != 0)) {
      throw UnsupportedOperationException("TranscriptSink: override 5-arg onTurnFailed to preserve attempt linkage")
    }
    onTurnFailed(runId, partialText, error)
  }
  /**
   * Stage F (implemented): explicit final flag for attempt failure rows.
   * Success, terminal-failure and cancel rows pass `isFinal=true`; a
   * retryable failure's intermediate fragment (written before its retry
   * notice) passes `isFinal=false` and never closes the logical family. S5
   * fail-closed: the default throws [UnsupportedOperationException] on
   * `isFinal=false` instead of downgrading to a final write (which would
   * prematurely close the family); `isFinal=true` without linkage still
   * forwards to the five-arg form (which itself guards linkage).
   */
  suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
  ) {
    if (!isFinal) {
      throw UnsupportedOperationException("TranscriptSink: override 6-arg onTurnFailed; legacy sinks cannot persist non-final partials")
    }
    onTurnFailed(runId, partialText, error, parentRunId, attemptIndex)
  }
  /**
   * R18-3 (implemented): reasoning-aware failed partial. Same `assistant`
   * (`isPartial=1` + `failureReason`, `parentRunId`/`attemptIndex`/`isFinal`
   * as the six-arg form) plus, when [hasReasoning] is true, one payload-free
   * `reasoning` marker (same `runId`, empty text, `parentRunId`/`attemptIndex`
   * /`isFinal` inherited verbatim, flag-only) persisted as one atomic batch
   * via [dev.librepocket.session.SessionStore.appendEvents] (Room single tx,
   * fake snapshot + rollback; external default sequential fallback may leave
   * a partial batch behind — fail-closed, never a premature close). S5
   * fail-closed: the default throws [UnsupportedOperationException] when the
   * marker would be dropped (`hasReasoning=true`) or when the row is
   * non-final (`isFinal=false`) instead of downgrading; production
   * ([dev.librepocket.session.SessionTranscriptSink]) overrides to persist
   * the marker.
   */
  suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    hasReasoning: Boolean,
  ) {
    if (!isFinal || hasReasoning) {
      throw UnsupportedOperationException("TranscriptSink: override 7-arg onTurnFailed; legacy sinks cannot drop isFinal/hasReasoning")
    }
    onTurnFailed(runId, partialText, error, parentRunId, attemptIndex, isFinal)
  }
  suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long)
  /** Same [hasReasoning] marker contract as [onTurnSucceeded] for partial text. */
  suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean = false)
  /**
   * Stage C (implemented): attempt-bound cancel terminal linked to its
   * logical turn. Same [parentRunId]/[attemptIndex] semantics as above plus
   * the [hasReasoning] marker contract. S5 fail-closed: the default throws
   * [UnsupportedOperationException] when true attempt linkage would be dropped
   * (`parentRunId != null || (attemptIndex != null && attemptIndex != 0)`)
   * instead of writing a single-id row; the first-attempt shape (`null`,
   * `null`/`0`) still forwards, with the marker riding into the base form
   * (which itself carries [hasReasoning], so nothing is dropped).
   *
   * Stage F: this form is always final (`isFinal=1`).
   */
  suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean = false,
  ) {
    if (parentRunId != null || (attemptIndex != null && attemptIndex != 0)) {
      throw UnsupportedOperationException("TranscriptSink: override 5-arg onTurnCancelled to preserve attempt linkage/marker")
    }
    onTurnCancelled(runId, partialText, hasReasoning)
  }
  /**
   * Stage F (implemented): deduped cancel mark for an already-terminalized
   * attempt. When [dev.librepocket.chat.TurnController] cancels in a retry
   * backoff gap, the just-failed attempt already owns a durable partial row;
   * writing a second assistant row for the same attempt would duplicate it.
   * This call writes one `system`-kind final mark
   * (`turn <logicalTurnId> cancelled`, `isFinal=1`) bound to the logical
   * family instead — hidden from the chat replay, visible in export, and a
   * terminal for [findDanglingRunIds][dev.librepocket.agent.ui.chat.findDanglingRunIds].
   * Idempotent exact-text pattern (same as the INTERRUPTED marker): the mark
   * text must equal `cancelledMarkerText(logicalTurnId)` with
   * `runId == logicalTurnId`, so reopen treats it as the family terminal (no
   * false INTERRUPTED, second open adds zero rows). This sink never dedups —
   * a repeated call rewrites the mark; idempotency rests on the controller
   * dedup plus the backfill skip. See also
   * [interruptedMarkerText][dev.librepocket.agent.ui.chat.interruptedMarkerText].
   * Durable core event. The default forwards to the attempt-bound cancel
   * terminal (second assistant) so existing fakes keep compiling and keep
   * their assertions; production ([dev.librepocket.session.SessionTranscriptSink])
   * overrides to the system mark.
   */
  suspend fun onLogicalTurnCancelled(
    logicalTurnId: String,
    attemptRunId: String,
    partialText: String,
    attemptIndex: Int?,
  ) {
    onTurnCancelled(
      attemptRunId,
      partialText,
      if (attemptRunId == logicalTurnId) null else logicalTurnId,
      attemptIndex,
    )
  }
  /**
   * R18-1 Stage 2 cancel-intent notice (durable but non-terminal).
   *
   * Written by the cancel host BEFORE the raced retryable terminal settles,
   * so the family stays dangling (reopen backfills INTERRUPTED) instead of
   * being prematurely closed by a system final. `retry` kind is never a
   * terminal for [findDanglingRunIds][dev.librepocket.agent.ui.chat.findDanglingRunIds]
   * (like the existing retry notice), hidden from chat replay, visible in
   * export. Best-effort notice (failures swallowed, never breaks the loop);
   * a lost notice still leaves the family dangling (user only), safe.
   * The default forwards to [onTurnRetried] so existing fakes keep compiling.
   * Fake semantics: the in-memory test fake ([dev.librepocket.session.FakeSessionStore]
   * via [dev.librepocket.session.SessionTranscriptSink]) persists notices with the
   * same best-effort path as production (appendNotice); only legacy fakes that keep
   * this interface default observe the coarse `onTurnRetried` fallback mapping
   * (`attempt = (attemptIndex ?: 0) + 1`), never the exact cancel-notice text.
   */
  suspend fun onTurnCancelRequested(
    logicalTurnId: String,
    attemptRunId: String,
    attemptIndex: Int?,
  ) {
    onTurnRetried(logicalTurnId, (attemptIndex ?: 0) + 1, 3, 0L)
  }
  /**
   * R18-1 Stage 2 atomic FAILED batch: retryable terminal failed (no row)
   * with a racing cancel — persists `partial(isFinal=false)` +
   * `system cancelled(isFinal=true)` as one all-or-nothing unit via
   * [dev.librepocket.session.SessionStore.appendEvents] (Room single tx,
   * fake snapshot + rollback; sinks that override the full forms but keep
   * this interface default get a sequential partial-first fallback, so
   * a mid-batch failure still leaves the family dangling, fail-closed).
   * The default forwards sequentially (partial via the 7-arg `isFinal=false`
   * form, then the cancel mark); production
   * ([dev.librepocket.session.SessionTranscriptSink]) overrides to the atomic
   * batch. S5 fail-closed: a legacy sink that overrides only the 2-arg /
   * 3-arg base forms throws [UnsupportedOperationException] with zero rows
   * here (the first forward is `isFinal=false`, which the 7-arg default
   * rejects) instead of writing a downgraded final row — never a premature
   * close. Fake semantics: the current in-memory test fake implements atomic
   * [dev.librepocket.session.SessionStore.appendEvents] (snapshot + rollback,
   * same all-or-nothing contract as Room), so tests exercise the atomic path;
   * only sinks that override the full forms yet keep this interface default
   * observe the sequential partial-first fallback (which may leave the partial
   * behind on a mid-batch failure — still fail-closed, never a premature
   * close).
   *
   * R18-3: [hasReasoning] adds the payload-free `reasoning` marker to the
   * partial (same `runId`, empty text, `parentRunId`/`attemptIndex` inherited,
   * `isFinal=false` like the partial, flag-only), so the batch is 2 rows
   * without reasoning vs 3 rows with it. The default forwards dropping the
   * flag so existing fakes keep compiling.
   */
  suspend fun onTurnFailedAndCancelled(
    logicalTurnId: String,
    attemptRunId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean = false,
  ) {
    onTurnFailed(attemptRunId, partialText, error, parentRunId, attemptIndex, false, hasReasoning)
    onLogicalTurnCancelled(logicalTurnId, attemptRunId, partialText, attemptIndex)
  }
  suspend fun onSteerQueued(text: String)

  /** One tool call finished (P1 records only, never executes). */
  suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String)

  /** Token usage for this turn (billing/context accounting; P1 records only). */
  suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?)

  /**
   * Post-core best-effort flush of any usage buffered for [runId] (#84/#94).
   * Must NOT be part of the core terminal ack: callers invoke it after the
   * terminal core write has committed so a slow/failing usage write can
   * never delay or negate an already-durable terminal. Best-effort: a
   * cancellation-cooperative stall is bounded by the sink's best-effort entry
   * timeout (the entry is skipped) and non-VM failures are swallowed;
   * [CancellationException] from real scope teardown and VM-fatal always
   * propagate. The persisted row is a bare `system` usage row: it never
   * carries attempt linkage (`parentRunId`/`attemptIndex`), matching the
   * JSONL shape gate that allows linkage only on assistant/reasoning rows.
   * Default no-op for legacy sinks.
   */
  suspend fun flushBufferedUsageBestEffort(runId: String) = Unit
}

/** Default sink: drops everything (used until the M4 store lands). */
class NoOpTranscriptSink : TranscriptSink {
  override suspend fun onTurnStarted(runId: String, text: String) = Unit
  override suspend fun onTurnSucceeded(runId: String, text: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnSucceeded(
    runId: String,
    text: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = Unit
  override suspend fun onTurnFailed(runId: String, error: String) = Unit
  override suspend fun onTurnFailed(runId: String, partialText: String, error: String) = Unit
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
  ) = Unit
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
  ) = Unit
  override suspend fun onTurnFailed(
    runId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    isFinal: Boolean,
    hasReasoning: Boolean,
  ) = Unit
  override suspend fun onTurnRetried(runId: String, attempt: Int, maxAttempts: Int, delayMs: Long) = Unit
  override suspend fun onTurnCancelled(runId: String, partialText: String, hasReasoning: Boolean) = Unit
  override suspend fun onTurnCancelled(
    runId: String,
    partialText: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = Unit
  override suspend fun onLogicalTurnCancelled(
    logicalTurnId: String,
    attemptRunId: String,
    partialText: String,
    attemptIndex: Int?,
  ) = Unit
  override suspend fun onTurnCancelRequested(
    logicalTurnId: String,
    attemptRunId: String,
    attemptIndex: Int?,
  ) = Unit
  override suspend fun onTurnFailedAndCancelled(
    logicalTurnId: String,
    attemptRunId: String,
    partialText: String,
    error: String,
    parentRunId: String?,
    attemptIndex: Int?,
    hasReasoning: Boolean,
  ) = Unit
  override suspend fun onSteerQueued(text: String) = Unit
  override suspend fun onToolDone(runId: String, toolIndex: Int, id: String, name: String, argumentsJson: String) = Unit
  override suspend fun onUsage(runId: String, inputTokens: Int?, outputTokens: Int?) = Unit
  override suspend fun flushBufferedUsageBestEffort(runId: String) = Unit
}
