package dev.librepocket.chat

import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatImage
import dev.librepocket.provider.ChatMessage
import dev.librepocket.provider.ChatRequest
import dev.librepocket.provider.LlmProvider
import dev.librepocket.provider.ProviderFailure
import dev.librepocket.provider.ProviderFailureCode
import dev.librepocket.provider.SseFrameParser
import dev.librepocket.provider.StreamEvent
import dev.librepocket.provider.TEXT_TOO_LARGE
import dev.librepocket.provider.TOOL_ARGS_TOO_LARGE
import dev.librepocket.provider.FailureKind
import dev.librepocket.provider.ProviderErrorClassifier
import dev.librepocket.provider.inferAggFailureCodeName
import dev.librepocket.provider.requireToolAggBudget
import dev.librepocket.provider.requireToolCountBudget
import dev.librepocket.provider.requireTotalToolAggBudget
import dev.librepocket.provider.requireTurnTextBudget
import dev.librepocket.redact.Redactor
import java.io.IOException
import dev.librepocket.router.SafeToolSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.jvm.Volatile

/**
 * The sole retry budget for one logical turn (at most 3 retries / 4 requests).
 * Provider adapters make one classified attempt and never multiply this budget.
 */
data class TurnRetryConfig(
  val maxRetries: Int = 3,
  val retryDelaysMs: List<Long> = listOf(2_000L, 4_000L, 8_000L),
) {
  init {
    require(maxRetries in 0..3) { "a turn may make at most four provider attempts" }
  }

  /** 1-based retry index → delay. Extra retry indices reuse the last delay. */
  fun delayForRetry(retryIndex: Int): Long =
    retryDelaysMs.getOrElse(retryIndex - 1) { retryDelaysMs.lastOrNull() ?: 0L }
}

private data class PendingSteer(val opId: Long?, val text: String, val images: List<ChatImageRef>)

/** Explicit acknowledgment for [TurnController.startOrEnqueue]. */
sealed interface TurnStart {
  /** The text was accepted: gate passed, appended, turn running. Join it. */
  data class Started(val host: Job) : TurnStart

  /** A turn was already in flight: the text is queued FIFO for next round. */
  data object Queued : TurnStart

  /**
   * The controller is idle but has intents left by a denied or failed
   * promotion policy check. They
   * have been atomically removed from the FIFO for caller recovery; the
   * current admission is not accepted. In the second-lock branch it may
   * already have passed one fresh policy gate; retrying after recovery
   * evaluates policy again.
   */
  data class NeedsRecovery(val queuedIntents: List<QueuedIntent>) : TurnStart
}

/** Fresh chat.send policy evaluation failed; the admission was not accepted. */
class PolicyEvaluationException(cause: Exception) :
  Exception("chat.send policy evaluation failed", cause)

/** Fresh chat.send policy requires approval; this build has no interactive consent flow. */
class ApprovalRequiredException : Exception("chat.send approval required")

/**
 * Fail-closed signal for the P2 slice continuation budget: the serialized
 * request payload (message text + the structured assistant `toolCalls`
 * id/name/argumentsJson) exceeds [TurnController.MAX_REQUEST_CHARS] even
 * after the full history-prefix trim (the newest base row + the structured
 * round cannot be shrunk further). Within one logical turn history only
 * grows, so this is never retried; `runTurnLoop` converts it into a typed
 * non-retryable terminal and no over-cap request is shipped to the provider.
 */
class ContinuationOverBudgetException : Exception("continuation request over budget")

/**
 * Outgoing context window for one [ChatRequest], and the resumed-history
 * prefix bound (Phase 3).
 *
 * The cap bounds the combined request copy (capped resumed prefix + live
 * messages), newest kept — a new live turn evicts the oldest prefix row once
 * the total reaches the cap. Oldest-first truncation at whole-message
 * boundaries. Tool-pair integrity
 * is structural — tool calls ride inline in their assistant block
 * (`[tool:name args]` marker text, never separate provider `tool` messages
 * in the persisted replay) — so whole-message truncation can never detach a
 * tool result from its call. The wire-only continuation round (structured
 * assistant `toolCalls` + `tool` messages, single-turn — see
 * [buildToolContinuation]) is exempt from this appends-after-trim window
 * argument by construction: its own accounting includes the structured
 * payload, and when even the untrimmable minimum (newest base row + the
 * round) still exceeds `MAX_REQUEST_CHARS` it fails closed (throw caught
 * in runTurnLoop → typed non-retryable terminal) rather than shipping
 * over cap. Any trimming is
 * observable via [TurnController.droppedHistoryCount] and the
 * continuation's own trim publication, never silent.
 *
 * The default is [Unbounded] (window everything, truncate nothing): no
 * on-device token/context measurement exists in this branch to justify a
 * tighter default (TODO #16: measure real session sizes before fixing a
 * number). [MaxMessages]/[MaxChars] exist so callers and tests can lock the
 * truncation behavior today.
 */
sealed interface HistoryWindowCap {
  data object Unbounded : HistoryWindowCap
  data class MaxMessages(val maxMessages: Int) : HistoryWindowCap {
    init {
      require(maxMessages > 0) { "maxMessages must be positive" }
    }
  }
  data class MaxChars(val maxChars: Int) : HistoryWindowCap {
    init {
      require(maxChars > 0) { "maxChars must be positive" }
    }
  }
}

/**
 * Oldest-first eviction (evicts oldest first, retains newest; eviction order,
 * not iteration order), whole-message truncation for the resumed prefix and
 * the outgoing request copy. Whole messages only (tool-pair integrity is
 * structural, see above). A single newest message that already exceeds a
 * char budget is still kept whole — truncation drops messages, never splits
 * one — so at least the newest message always survives.
 */
internal fun applyHistoryCap(history: List<ChatMessage>, cap: HistoryWindowCap): List<ChatMessage> =
  when (cap) {
    is HistoryWindowCap.Unbounded -> history
    is HistoryWindowCap.MaxMessages -> history.takeLast(cap.maxMessages)
    is HistoryWindowCap.MaxChars -> {
      var kept = 0
      var chars = 0
      for (m in history.asReversed()) {
        if (kept > 0 && chars + m.text.length > cap.maxChars) break
        chars += m.text.length
        kept++
      }
      history.takeLast(kept)
    }
  }

/** A direct admission cannot overtake queued work that still needs explicit recovery. */
class RecoveryRequiredException : IllegalStateException("queued turn recovery required before admission")

/**
 * Single-turn input (plus its ephemeral runtime time block) exceeds
 * [TurnController.MAX_REQUEST_CHARS]. Thrown fail-fast at admission and at
 * every post-gate second-lock / promotion re-validation, always before any
 * append/transcript/activity bookkeeping on that path (no append, no bump, no
 * transcript on throw). Subclasses
 * [IllegalArgumentException] so existing pre-accept callers treat it as
 * "not accepted, caller retains the text" with no API break.
 */
class InputTooLongException(message: String) : IllegalArgumentException(message)

/**
 * Stage 6 (R4-3): admission-time durable failure for the user row.
 *
 * Thrown fail-closed when [TurnController.startTurn]/[TurnController.startOrEnqueue]
 * cannot durably persist the user row within
 * [TurnController.ACCEPT_DURABLE_TIMEOUT_MS] *without* the entry ever
 * reaching the session channel (timeout-before-send), on a fast delegate
 * IOException, or on a writer seal race. Deliberately NOT a [CancellationException]
 * so ViewModel admission loops treat it like [InputTooLongException]
 * (notice + draft restore via `restoreOpForFailedSend`, caller retains the
 * text for explicit retry) instead of propagating caller cancellation.
 * The logical id is already INTERRUPTED-marked via
 * [OrderedTranscriptSink.interruptedRunIds]; a later-unblocked writer cannot
 * persist anything because nothing was sent.
 *
 * R5-1: timeout-after-send is NOT this exception — it is
 * [TranscriptPendingException] (UNKNOWN/PENDING_COMMIT, ownership retained,
 * same-opId resend blocked until settle). See [DurableAdmission].
 */
class TranscriptDurabilityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * R5-1 (admission timeout late-commit) / R6-2 (external cancel after send):
 * the user-row entry was buffered in
 * the session channel ([OrderedTranscriptSink.onTurnStartedTracked] send
 * proof true) but its durable ack did not arrive within
 * [TurnController.ACCEPT_DURABLE_TIMEOUT_MS] (R5-1) or the caller's wait was
 * externally cancelled after the send (R6-2). Fate is UNKNOWN: the
 * independent writer may still late-commit it as a ghost row. The controller
 * retains ownership (single-flight still held, same-opId admission blocked),
 * keeps the optimistic UI row with `transcript store pending`, and settles in
 * the background via bounded flush + ticket tracking: flush success without
 * an INTERRUPTED mark claims the ghost (same logical id, no resend);
 * INTERRUPTED/seal converts to [TranscriptDurabilityException] semantics
 * (retryable). Deliberately distinct from [TranscriptDurabilityException] so
 * the ViewModel projects `CHAT_STORE_PENDING` without `restoreOpForFailedSend`.
 */
class TranscriptPendingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * R5-1 admission-time durable fate for one user row.
 *
 * - [ACKED]: ack arrived in budget; `Started` is durable.
 * - [NOT_ADMITTED]: entry never reached the channel (timeout-before-send),
 *   fast delegate failure, or seal-before-send. Existing fail-closed rollback
 *   semantics preserved (INTERRUPTED-marked, UI rolled back, immediately
 *   retryable via [TranscriptDurabilityException]).
 * - [UNKNOWN]: send proof true but ack timed out. Ownership retained
 *   (PENDING_COMMIT); same-opId resend blocked until bounded settle.
 */
internal enum class DurableAdmission {
  ACKED,
  NOT_ADMITTED,
  UNKNOWN,
}

/**
 * Turn controller: per-turn [Flow] collection + retry orchestration +
 * single-flight guard + steer queue + cooperative cancel.
 *
 * The controller consumes the unified [StreamEvent] union from [LlmProvider]
 * directly (no M3-local stub): [StreamEvent.TextDelta] and
 * [StreamEvent.ReasoningDelta] extend the current assistant block, with
 * reasoning events ([StreamEvent.ReasoningDelta] and
 * [StreamEvent.ReasoningPresent]) flagging it reasoning-sourced
 * ([UiMessage.hasReasoning], rebuilt into [ChatMessage.hasReasoning] so
 * tool-dependent / thinking-mode follow-ups fail closed while ordinary
 * no-tools follow-ups continue),
 * and persisted as a payload-free `reasoning` marker event (marker itself
 * empty, flag-only) via
 * [TranscriptSink.onTurnSucceeded]/[TranscriptSink.onTurnCancelled] so
 * resume restores the flag. Visible reasoning deltas remain merged into the
 * assistant text and persist as usual (pre-existing behavior),
 * [StreamEvent.ToolDelta]/[StreamEvent.ToolDone] are aggregated and recorded
 * (marker text + [TranscriptSink.onToolDone]) so tool calls are never dropped,
 * [StreamEvent.Usage] is recorded ([TranscriptSink.onUsage] + [lastUsage]).
 * Retry ownership is here: each retryable terminal failure gets a new runId
 * and isolated assistant/tool aggregation, with at most four provider attempts
 * per logical turn.
 *
 * Records-only default (production: toolSlice=null): without a loop this
 * controller records ToolDone only and never executes tools. Projection
 * NATIVE means switch/flavor semantics only; model visibility
 * additionally requires `ToolDef.executionReady` (see
 * [ToolRegistry.visibleTools], the ONLY list that may be sent to the model),
 * which is true for exactly one tool (the P2 slice `lsp.symbols`; reference
 * it via [SafeToolSlice.TOOL_NAME], not a hardcoded count) and false
 * for the others. The remaining product tool loop (grant/confirm + Android
 * executors + E2E per tool) still lands tool-by-tool in wiring PRs.
 * P2 slice opt-in (see below) attaches the single-tool closed loop.
 *
 * P2 safe slice (#19, default-off): when constructed with a [SafeToolSlice],
 * the single wired tool (`lsp.symbols`, the only `executionReady=true` entry)
 * runs a same-turn closed loop — request tools → model ToolDone → schema
 * validation → latest scope/policy re-check → executor → typed result →
 * provider continuation → final UI/transcript. All other tools stay
 * records-only; without a loop (null, the production composition) nothing
 * executes and the request stays pure-chat.
 *
 * Execution-basis policy: every turn (including steered follow-ups) is gated
 * by [PolicyStore.evaluateFresh]. [PolicyStore.evaluate] is UI pre-display
 * only and is never used here.
 *
 * Cancel is cooperative via the collecting coroutine's [Job] (the [LlmProvider]
 * contract): state flips to [ChatStatus.CANCELLED] first so the UI stops even
 * if the transport close blocks briefly, then the in-flight job is cancelled,
 * which cancels the underlying HTTP call promptly. No IO on this path.
 *
 * Transcript durability: every transcript event goes through the
 * session-owned [OrderedTranscriptSink] (bounded channel, single writer, core
 * events durably acked). The writer runs outside the turn scope, so
 * cancelling the network never discards an admitted event; [close] settles
 * the doomed host and drains with a bounded timeout instead of cancelling the
 * scope out from under pending writes.
 *
 * Retry: each attempt gets a fresh runId; failed partial output is kept with
 * `isPartial=true` and the next attempt starts a new assistant block instead
 * of backfilling the old one. Stage F: the intermediate fragment is
 * non-final (`isFinal=false`) and never closes the logical family; only the
 * final success / terminal-failure / cancel row (`isFinal=true`) does. A
 * cancel in a retry backoff gap writes one system-kind final mark instead of
 * a second assistant row for the same attempt.
 *
 * Aggregation budget: ToolDelta fragments (args/id/name) for one tool index
 * share one memory budget (see `MAX_TOOL_CALL_AGG_BYTES`); exceeding it ends
 * the attempt with typed non-retryable `TOOL_ARGS_TOO_LARGE` (never silent
 * truncation, never a retry of the poisoned stream). The same budget is
 * additionally enforced as a per-attempt whole-round total across ALL tool
 * indexes (see `requireTotalToolAggBudget`: many small calls, each under its
 * own budget, still trip the shared 1 MiB total), plus a distinct-tool cap
 * (see `MAX_TOOL_CALLS_PER_ATTEMPT` / `requireToolCountBudget`). All three
 * scopes share one failure semantics; the round total is monotonic per
 * attempt (`ToolDone` arrivals never decrement it).
 *
 * Visible text has its own per-attempt budget (#83, see
 * `MAX_TURN_TEXT_CHARS` / `requireTurnTextBudget`): `TextDelta` +
 * `ReasoningDelta` chars accepted into one attempt's assistant block may not
 * exceed 100_000 (the durable-row cap, so a text-only attempt that passes
 * fits the store without silent truncation). Exceeding it throws the same
 * typed non-retryable `AGG_TOO_LARGE` (`TEXT_TOO_LARGE`, never silent
 * truncation, never a retry — the failed fragment stays partial like every
 * other budget overflow). Tool-marker text is excluded (own budgets above);
 * a marker-heavy attempt may still hit the store truncate path (pre-existing
 * residual, disclosed — never silently introduced here).
 *
 * Usage is authoritative per attempt (#84): every [StreamEvent.Usage] of one
 * attempt merges last-wins (null fields keep the previous value) into a
 * single pending usage, written once via [TranscriptSink.onUsage] before the
 * attempt terminal — an Anthropic normal turn (dual `Usage`: `message_start`
 * + `message_delta`) persists exactly one row with the final values, and a
 * 100-event flood still persists one row. Retries start fresh (attempt
 * isolation, like the tool budgets). [lastUsage] holds the merged
 * authoritative value.
 *
 * UI update cost: visible deltas accumulate in a per-attempt buffer and flush
 * to [_uiState] immediately while the block is small (fast first paint:
 * shorter than [UI_TEXT_FLUSH_CHARS] and at most [UI_TEXT_FLUSH_DELTAS]
 * appends — streaming visibility and small-turn snapshots behave exactly as
 * before), then at most every [UI_TEXT_FLUSH_CHARS] unflushed chars or
 * [UI_TEXT_FLUSH_DELTAS] unflushed deltas once it outgrows that cadence
 * (#83 hybrid incremental update: buffer appends cost O(delta), only flushes
 * copy the block, so a hostile tiny-delta drip costs O(flushes) instead of
 * O(deltas)). Terminal and cancel paths always flush, and snapshots read the
 * buffer, so cancellation and terminal snapshots stay exact while streaming
 * stays live (at most one cadence behind past the first paint). The cost is
 * observable via [streamedTextDeltaCount] (arrival count, every delta),
 * [uiTextUpdateCount] (flush count) and [uiTextCopiedChars] (chars copied by
 * flush commits).
 *
 * Context window: the outgoing request carries [historyCap] (default
 * Unbounded — zero behavior change); truncation is oldest-first,
 * whole-message, and counted on [droppedHistoryCount]. The resumed prefix
 * below is part of that windowed history.
 *
 * Resumed history (Phase 3): [initialHistory] carries the pre-truncation
 * user/assistant prefix restored from the transcript store (partial rows are
 * excluded by the caller — they replay in the UI flagged, but never read as
 * completed model context, mirroring the live `!isPartial` filter below).
 * [historyCap] bounds that prefix oldest-first at whole-message boundaries
 * ([HistoryWindowCap]); the default hydrates everything while the measured
 * token budget is still a TODO. Every outgoing [ChatRequest] is
 * history-prefix + live messages, so the first request after resume already
 * sees the prior conversation.
 *
 * Ephemeral runtime time context (Phase 2): every outgoing [ChatRequest]
 * carries a [buildRuntimeTimeContext] block appended to a **copy** of the last
 * user message. The block never touches [_uiState] (no [UiMessage] residue)
 * and never flows through [TranscriptSink] (transcript keeps the raw user
 * text). Unknown time is still sent as `(time unknown)` so the model knows
 * the clock is unavailable instead of hallucinating one; fallback/unknown
 * only emits `Log.w` and never blocks the turn.
 *
 * @param clock clock used to stamp the ephemeral time block.
 * @param userTimezone IANA id preferred for the time block; blank/invalid
 *   falls back to the system zone (fail-closed, never throws).
 * @param sessionStart session creation instant for the `Session started`
 *   line; null omits the line. Callers should pass the session creation time.
 * @param systemZone supplier re-read every turn for the ephemeral time block
 *   when [userTimezone] is blank (defaults to `ZoneId.systemDefault`, so a
 *   mid-session system timezone change is picked up on the next turn instead
 *   of reusing the [clock] construction-time snapshot).
 */
/** Admission details for a turn that passed policy and appended a user row but whose host has not yet started. */
data class PendingAdmission(
  val logicalTurnId: String,
  val text: String,
  val images: List<ChatImageRef>,
  val attemptRef: AtomicReference<String>,
  val attemptIndexRef: AtomicInteger,
)

class TurnController(
  private val provider: LlmProvider,
  private val policy: PolicyStore,
  private val transcript: TranscriptSink = NoOpTranscriptSink(),
  private val retryConfig: TurnRetryConfig = TurnRetryConfig(),
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
  private val newId: () -> String = { UUID.randomUUID().toString() },
  private val model: String = DEFAULT_MODEL,
  private val imageLoader: (List<ChatImageRef>) -> List<ChatImage> = ::defaultChatImageLoader,
  private val clock: Clock = Clock.systemDefaultZone(),
  private val userTimezone: String? = null,
  private val sessionStart: Instant? = null,
  private val systemZone: () -> ZoneId = ZoneId::systemDefault,
  initialHistory: List<ChatMessage> = emptyList(),
  private val historyCap: HistoryWindowCap = HistoryWindowCap.Unbounded,
  transcriptDispatcher: CoroutineDispatcher? = null,
  private val acceptTimeoutMs: Long = ACCEPT_DURABLE_TIMEOUT_MS,
  transcriptCapacity: Int = OrderedTranscriptSink.DEFAULT_CAPACITY,
  /**
   * R17-1 review: bounded settle-wait for a SUBMITTING terminal before the
   * sync cancel suppression decision (injectable seam for CI; production
   * default matches the 5s ledger budget). Tests use a short budget with a
   * proportionally longer writer stall to deterministically hit the
   * still-pending receipt path without a real 5.1s stall.
   */
  private val cancelSettleTimeoutMs: Long = CANCEL_SETTLE_TIMEOUT_MS,
  /**
   * WARN-1 + S3-F2: bounded compensation write for the late-writer receipt
   * waiter AND the immediate cancel terminal AND the orphan drain. All three
   * run in NonCancellable (channel.send + ack.await); without a timeout a
   * wedged writer parks them forever. Timeout writes no terminal row and
   * degrades to SEALED/backfill (receipt waiter) or to PENDING-receipt /
   * markInterrupted convergence (immediate cancel: deferred notice-first keeps
   * its PENDING receipt, every other path marks INTERRUPTED; orphan drain
   * marks INTERRUPTED) like the sealed path. Injectable for the wedged-writer
   * test.
   *
   * Current (S3-F2): this bound owns all three wedged-writer convergences.
   * Production is 5s ([CANCEL_RECEIPT_COMPENSATION_TIMEOUT_MS]); tests inject
   * 1s to prove the mechanism without a real 5s stall. #94 note: the
   * immediate-cancel handler now wraps its usage transfer + persist + drain in
   * their own [OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS] bounds OUTSIDE
   * this core bound, so a worst-case handler can exceed this timeout by up to
   * the settle bound (`cancelSettleTimeoutMs`, production 5s, only on the
   * SUBMITTING settle-wait sub-path) plus three best-effort bounds (the 6s
   * fence asserts the core bound, not the full handler wall time). Target:
   * single shared ledger budget instead of three shares of this timeout.
   */
  private val cancelCompensationTimeoutMs: Long = CANCEL_RECEIPT_COMPENSATION_TIMEOUT_MS,
  /**
   * R18-1 Stage 2: bounded receipt poll rounds (injectable seam for CI).
   * Production default 60 rounds (~29s with the 50→500ms capped backoff);
   * timeout degrades to SEALED/backfill (no terminal write, family stays
   * dangling, reopen INTERRUPTED takes over) instead of parking forever.
   * Tests inject a small count to cover deadline-expired → late FAIL → reopen
   * without waiting 30s. The same bound also applies to the legacy aggregated
   * waiter below (still-pending → SEALED), so an injected small count shortens
   * legacy-path tests as well; production 60 is shared by both paths.
   */
  private val cancelReceiptMaxRounds: Int = CANCEL_RECEIPT_MAX_ROUNDS,
  /**
   * P2 safe slice (#19, default-off): the single-tool closed loop. Null
   * (production composition and all pre-slice tests) keeps records-only
   * behavior: ToolDone markers persist, nothing executes, requests carry no
   * tools. Non-null runs the `lsp.symbols` same-turn continuation (request
   * tools → execute → typed result → one continuation request → final).
   */
  private val toolSlice: SafeToolSlice? = null,
) {
  companion object {
    const val POLICY_ACTION = "chat.send"
    const val POLICY_RESOURCE = "chat"
    const val DEFAULT_MODEL = "default"

    /**
     * Stage 6 (R4-3): bounded wait for the admission-time durable user-row
     * ack. [startTurn]/[startOrEnqueue] return `Started` only after
     * `onTurnStarted` is acked within this budget; a stall throws
     * [TranscriptDurabilityException] fail-closed (caller retains the text).
     * Same 5s budget as the ledger drain/flush path. Suspend (never block),
     * so no ANR: Room stays on IO via its own `withContext(IO)`.
     */
    const val ACCEPT_DURABLE_TIMEOUT_MS = 5_000L

    /**
     * R17-1 P1: bounded settle-wait for a SUBMITTING terminal before the sync
     * cancel suppression decision. Production default matches the 5s ledger
     * budget (5.1s stall in the wild times out here and installs the receipt);
     * tests inject a short budget with a proportionally longer stall.
     */
    const val CANCEL_SETTLE_TIMEOUT_MS = 5_000L

    /**
     * WARN-1: bounded compensation write for the receipt waiter
     * (channel.send + ack.await inside NonCancellable). Timeout degrades to
     * SEALED/backfill instead of parking forever behind a wedged writer.
     */
    const val CANCEL_RECEIPT_COMPENSATION_TIMEOUT_MS = 5_000L

    /**
     * R18-1 Stage 2: bounded receipt poll rounds (see [cancelReceiptMaxRounds]).
     * 60 rounds with 50→100→200→400→500ms capped backoff ≈ 29s. Timeout writes
     * no terminal row (family stays dangling, reopen INTERRUPTED takes over).
     * Shared by the notice-first waiter and the legacy aggregated waiter below.
     */
    const val CANCEL_RECEIPT_MAX_ROUNDS = 60

    /** Single-image byte cap (spec §4: >10 MiB is rejected, never transcoded). */
    const val MAX_IMAGE_BYTES = 10 * 1024 * 1024L

    /** Spec §4: at most 4 images per turn; extras are omitted. */
    const val MAX_IMAGES_PER_TURN = 4

    /**
     * Strict aggregate request context char cap (R2-6, R4-4).
     * Equal to RoomSessionStore.MAX_TEXT_CHARS.
     *
     * Boundary invariant (not an absolute per-request guarantee): at every
     * admission/handoff boundary the single-turn payload
     * `text.length + 2 ("\n\n") + actual time-block length` is checked against
     * [MAX_REQUEST_CHARS], including the ephemeral runtime time block
     * (`"\n\n" + buildRuntimeTimeContext(...)`) suffixed to the last user copy.
     * Image payload bytes are out of scope (never counted in `text.length`).
     *
     * Enforcement is admission fail-fast, never silent truncation of the
     * current turn: any single-turn `text` with
     * `text.length + 2 + actualTimeBlock.length > MAX_REQUEST_CHARS` is
     * rejected with [InputTooLongException] with no append, no bump, and no
     * transcript on the rejecting path.
     * Multi-message overflow still trims oldest-first whole messages
     * (observable via [TurnController.lastRequestTrimmedCount]); the last
     * user message is only ever present because it individually fits at its
     * admission boundary.
     *
     * Handoff re-validation keeps the boundary invariant across the gate/queue
     * race — admission ([startTurn]/[startOrEnqueue]/[steer] pre-gate checks),
     * post-gate second-lock re-checks ([startTurn] before bump/append,
     * [startOrEnqueue] both the lost-race enqueue path before enqueue and the
     * idle direct-start path before drain/bump/append), and a promotion peek
     * ([hostedTurn] re-checks the FIFO head with [checkInputLengthOrThrow]
     * before dequeue, `failPromotion("input too long")` retains the FIFO).
     * The only remaining residual is the intra-turn retry rebuild race (fresh
     * zone read per attempt in [runTurnLoop]): a zone that grows mid-turn can
     * add at most the time-block delta to an already-admitted turn, surfaced
     * at the next admission/promotion boundary.
     */
    const val MAX_REQUEST_CHARS = 100_000

    /**
     * Per-attempt usage-notice flood cap (#84): at most this many
     * [StreamEvent.Usage] events are merged into one attempt's authoritative
     * usage; further events are ignored (frozen at the cap, never a failure).
     * Legitimate turns emit at most 2 (Anthropic `message_start` +
     * `message_delta`), so 128 leaves 64x headroom while bounding merge CPU
     * against a usage-event flood. The sink owns the same cap per run
     * ([dev.librepocket.session.SessionTranscriptSink]), so a direct-sink
     * flood is bounded there too.
     */
    const val MAX_USAGE_EVENTS_PER_ATTEMPT = 128

    /**
     * Visible-text UI flush cadence (#83): while one attempt's block is
     * shorter than [UI_TEXT_FLUSH_CHARS] with at most [UI_TEXT_FLUSH_DELTAS]
     * appends, every append commits immediately (fast first paint, small-turn
     * snapshots exactly as before); past that, commits happen at most every
     * cadence. Streaming stays live (at most one cadence behind past the
     * first paint) while a hostile drip of tiny deltas costs O(flushes) full
     * copies instead of O(deltas): 3000 x 1-char deltas flush ~125 times
     * (~143 Ki chars copied) instead of 3000 times (~4.5 Mi chars). Terminal
     * and cancel paths always flush, so snapshots stay exact (see
     * [assistantTextOf]).
     */
    const val UI_TEXT_FLUSH_CHARS = 4_096
    const val UI_TEXT_FLUSH_DELTAS = 32
  }

  init {
    require(model.isNotBlank()) { "model must not be blank" }
    require(transcriptCapacity > 0) { "transcriptCapacity must be positive" }
  }

  private val lock = Any()
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  /**
   * Stage 6: writer runs on its own dispatcher so the admission-time durable
   * await ([ACCEPT_DURABLE_TIMEOUT_MS]) never deadlocks against a paused host
   * dispatcher. Defaults to [dispatcher] (prod: same Default pool); tests
   * split host (paused) from writer (Default) to prove Started==durable
   * without dispatching the host.
   *
   * Dispatcher precondition (R4-3 review): the bounded wait above only holds
   * when this dispatcher can make progress independently of the host
   * dispatcher. A single-threaded or paused dispatcher shared with the host
   * self-deadlocks (neither the ack nor the 5s timeout can run), so prod must
   * pass a multi-threaded pool (Dispatchers.Default) or an explicit
   * `transcriptDispatcher = Dispatchers.Default`.
   */
  private val writerDispatcher: CoroutineDispatcher = transcriptDispatcher ?: dispatcher
  private val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
  /**
   * Session-owned serialized transcript writer. All [TranscriptSink] calls
   * below go through it (never directly to [transcript]), so persistence
   * order matches admission order and core events are durably acked. Its
   * scope is independent of [scope], so turn cancellation cannot strand an
   * admitted event.
   */
  private val orderedTranscript = OrderedTranscriptSink(transcript, writerDispatcher, transcriptCapacity)
  /**
   * Stage 6: logical ids whose user row ack already succeeded (admission
   * await or [runTurnLoop] promotion write). [runTurnLoop] and
   * [drainOrphanLocked] skip the user-row write when present, so exactly one
   * user row exists per logical turn even across the admission/host/close
   * race. Bounded by the session's turn count (same as
   * [terminalizedAttempts]).
   */
  private val durableUserRows: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()
  /** Background settle+drain+seal launched by [close]; awaited by [flushTranscript]. Guarded by [lock]. */
  private var shutdownJob: Job? = null
  private var pendingAdmission: PendingAdmission? = null
  /**
   * Admission-window cancel flag (Finding 1): logicalTurnId for which [cancel]
   * was called while [pendingAdmission] was set but no host had launched yet
   * (the durable await runs outside [lock]). The waiter observes it in its
   * finalize block, abandons the launch, drains a cancel (user row already
   * durable, skipped there) and returns a cancelled host instead of
   * ghost-launching. Guarded by [lock]; cleared by whichever waiter path wins
   * (cancel-drain, closed-rollback, or durability-rollback).
   *
   * R6-1: reused for the promotion UNKNOWN window (predecessor host already
   * completed, [pendingAdmission] null, [unknownAdmission.isPromotion] true).
   * [cancel] records the promotion reservation id here; [settlePendingAdmission]
   * and [claimPromotionUnknownLocked] observe it and abort the launch
   * (convert-like, FIFO retained, no second ledger row).
   */
  private var admissionCancelRequestedId: String? = null
  /**
   * R5-2 promotion-scoped reservation (guarded by [lock]): the follow-up
   * identity minted by the promotion peek, held across the durable user-row
   * await. Deliberately separate from [pendingAdmission] (direct admissions):
   * [close] drains only direct orphans, and a promotion await is always
   * covered by the still-active predecessor host, so cancel/close reach it by
   * cancelling that host — except the promotion UNKNOWN window (predecessor
   * already completed, ownership moved to [unknownAdmission]): there [cancel]
   * reuses [admissionCancelRequestedId] for the promotion id. Invariant: set
   * only while the predecessor host is still the live in-flight job, or
   * transiently until [enterUnknownAdmission] moves it (cleared explicitly by
   * [close] as well as the async waiter path).
   */
  private var promotionPending: PendingAdmission? = null
  private var orphanDrainJob: Job? = null

  /**
   * R5-1 UNKNOWN/PENDING_COMMIT ownership (guarded by [lock], at most one:
   * single-flight admits at most one pending user row). Retains the
   * reservation + optimistic UI row + caller opId across the accept timeout so
   * a late-committing ghost is claimed under the SAME logical id instead of
   * being duplicated by an immediate same-opId resend.
   *
   * R6-1: promotion UNKNOWN reuses the same slot. Direct admissions carry a
   * non-null [uiMessageId] (optimistic row already appended); promotions have
   * no optimistic row yet so [uiMessageId] is null and [isPromotion] is true
   * with the predecessor [host] for inFlight bookkeeping. Claim then performs
   * the Lock-2 commit (dequeue + append + launch) instead of the direct claim.
   */
  private data class UnknownAdmission(
    val reservation: PendingAdmission,
    val uiMessageId: String?,
    val opId: Long?,
    val isPromotion: Boolean = false,
    val host: Job? = null,
  )
  private var unknownAdmission: UnknownAdmission? = null
  private var unknownSettleJob: Job? = null

  /** Test seam forwarding to the sink's pre-send hook (NOT_ADMITTED simulation). */
  internal var beforeAdmissionSendForTest: (suspend (Long) -> Unit)?
    get() = orderedTranscript.beforeSendForTest
    set(value) {
      orderedTranscript.beforeSendForTest = value
    }

  /**
   * Test seam forwarding to the sink's post-send hook. Deterministic proxy
   * (stronger-than-production: guaranteed-admitted) for the send-races-cancel
   * flag shape: production's true window is a CE racing a suspended send,
   * while send-return → `sent.set` has no suspension point so an external
   * cancel cannot land there.
   */
  internal var afterAdmissionSendForTest: (suspend (Long) -> Unit)?
    get() = orderedTranscript.afterSendForTest
    set(value) {
      orderedTranscript.afterSendForTest = value
    }

  /** Test seam for filling the session channel (R8-6 full-channel simulation). */
  internal fun offerSteerForTest(text: String) = orderedTranscript.offerSteer(text)

  /**
   * D15-2 test seam forwarding to the sink's commit→ack hook (commit后ack前
   * simulation). Holds the session writer between its commit-count record and
   * its ack completion so a cancel deterministically lands on COMMITTED.
   */
  internal var afterCommitBeforeAckForTest: ((String) -> Unit)?
    get() = orderedTranscript.afterCommitBeforeAckForTest
    set(value) {
      orderedTranscript.afterCommitBeforeAckForTest = value
    }

  private fun drainOrphanLocked(pending: PendingAdmission): Job {
    if (pendingAdmission?.logicalTurnId == pending.logicalTurnId) {
      pendingAdmission = null
    }
    val alreadyDurable = durableUserRows.contains(pending.logicalTurnId)
    val drain = writerScope.launch(NonCancellable) {
      try {
        // S3-F2: bounded orphan drain. The compensation runs in
        // NonCancellable (channel.send + ack.await); without a timeout a
        // wedged writer parks it forever. Timeout writes no terminal row and
        // marks INTERRUPTED so flushDurable stays false until reopen
        // backfill (same fail-closed as the seal path). Shares
        // cancelCompensationTimeoutMs with the immediate-cancel and
        // receipt-waiter bounds.
        //
        // Current (S3-F2): single shared bound for user + terminal; timeout
        // marks only when the family still dangles (landing check below), so
        // a commit that landed just before the timeout never gets a false
        // INTERRUPTED. Production 5s, tests inject 1s. Target: split
        // user/terminal bounds with per-row landing proof.
        val completed = withTimeoutOrNull(cancelCompensationTimeoutMs) {
          // Stage 6: admission-path user rows are already durable before the
          // host launches; re-writing would duplicate the runId. Only orphans
          // whose durable await never completed need the user row here.
          if (!alreadyDurable) {
            orderedTranscript.onTurnStarted(pending.logicalTurnId, pending.text)
            durableUserRows.add(pending.logicalTurnId)
          }
          orderedTranscript.onLogicalTurnCancelled(
            pending.logicalTurnId,
            pending.attemptRef.get(),
            "",
            pending.attemptIndexRef.get(),
          )
          try {
            orderedTranscript.flushBufferedUsageBestEffort(pending.attemptRef.get())
          } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
          }
        }
        if (completed == null) {
          // M3 landing check: an orphan is always a first-attempt family
          // (user + terminal share pending.logicalTurnId), so only
          // commitCount < 2 still dangles. A commit that landed just before
          // the timeout (count >= 2) skips the mark instead of poisoning a
          // durable family; a genuine store failure (count 0, poison tier in
          // the sink) still marks here without clearing that poison (R23-1:
          // markInterrupted never clears storeFailed, and the sink's
          // clearTimeoutMarkOnCommit honors the tier).
          val landed = orderedTranscript.coreSubmitTruth(pending.logicalTurnId)
          if (landed.commitCount < 2) {
            orderedTranscript.markInterrupted(pending.logicalTurnId)
          }
        }
      } catch (_: CancellationException) {
        orderedTranscript.markInterrupted(pending.logicalTurnId)
      } catch (_: Exception) {
        orderedTranscript.markInterrupted(pending.logicalTurnId)
      } catch (e: Throwable) {
        // S3-F1 Error symmetry: fatal Error (e.g. store AssertionError/
        // LinkageError) marks INTERRUPTED like Exception instead of escaping
        // the drain. The sink writer already failed the ack and sealed; this
        // keeps the interruption explicit. Never rethrows (drain is
        // best-effort) except VM-fatal, which always propagates (never
        // swallowed here, unlike the sink's fail+throw+seal which rethrows
        // everything after failing the ack).
        // Current (S3-F1): narrowed to non-VM Errors. Target: sink-owned
        // fail+throw+seal for drains as well.
        if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
        orderedTranscript.markInterrupted(pending.logicalTurnId)
      }
    }
    orphanDrainJob = drain
    return drain
  }
  private val _uiState = MutableStateFlow(
    ChatUiState(messages = emptyList(), status = ChatStatus.IDLE, pendingSteerCount = 0, error = null),
  )
  val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

  /** Last usage reported by the provider (billing/context accounting; P1 records only). */
  @Volatile
  var lastUsage: StreamEvent.Usage? = null
    private set

  /** Resumed-history prefix after [historyCap] (oldest-first, whole messages). */
  private val cappedHistory: List<ChatMessage> = applyHistoryCap(initialHistory, historyCap)

  /**
   * Messages dropped from the last outgoing request by [historyCap], seeded
   * with the resumed-prefix drops at load time (0 when
   * [HistoryWindowCap.Unbounded]). The observable counterpart to context
   * truncation: the request copy never silently loses history. Updated on
   * every [buildRequest] to that request's combined-window drop
   * (`combined - base`, capped prefix + live); the seed is then replaced, so
   * a resumed session at cap reports a growing send-time count while the
   * load-time total stays observable separately via the factory's
   * historyDroppedCount. The UI list and transcript keep everything.
   */
  @Volatile var droppedHistoryCount: Int = (initialHistory.size - cappedHistory.size).coerceAtLeast(0)
    private set

  /**
   * Visible-text deltas collected this controller lifetime (text + reasoning).
   * Counts every accepted delta at arrival (the tripping over-budget delta is
   * rejected before counting). Together with [uiTextUpdateCount]/
   * [uiTextCopiedChars] this keeps the per-delta arrival rate observable while
   * the UI commit cost below is batched (#83). Atomic so the count stays
   * correct even if the single-writer discipline is ever relaxed.
   */
  private val _streamedTextDeltaCount = AtomicInteger(0)
  var streamedTextDeltaCount: Int
    get() = _streamedTextDeltaCount.get()
    private set(value) {
      _streamedTextDeltaCount.set(value)
    }

  /** UI text commits performed (one per flush: immediate while small, cadenced past it; tool markers included). Atomic, see above. */
  private val _uiTextUpdateCount = AtomicInteger(0)
  var uiTextUpdateCount: Int
    get() = _uiTextUpdateCount.get()
    private set(value) {
      _uiTextUpdateCount.set(value)
    }

  /**
   * Chars copied by immutable UI text commits (materialized block length per
   * flush, tool markers included). Buffer appends between flushes cost
   * O(delta) and are not counted here — that uncounted incremental path is
   * the #83 O(n^2) fix; only flush commits copy. Exact under the
   * controller's single-writer discipline (no concurrent writers on one
   * attempt's buffer); atomic like the counters above.
   */
  private val _uiTextCopiedChars = AtomicLong(0)
  var uiTextCopiedChars: Long
    get() = _uiTextCopiedChars.get()
    private set(value) {
      _uiTextCopiedChars.set(value)
    }

  /**
   * Per-attempt visible-text buffer (#83): the full assistant-block text
   * (streamed text + reasoning + tool markers) plus the unflushed tail.
   * Single-writer per attempt id (only the host coroutine touches its live
   * attempt's buffer), so no locking beyond the concurrent map. Entries are
   * discarded after their attempt terminal is snapshotted, so at most the
   * live attempt is retained.
   */
  private class TextBuffer {
    val text = StringBuilder()
    var unflushedChars = 0
    var unflushedDeltas = 0
    /** All appends this attempt (markers included); selects the flush path below. */
    var totalDeltas = 0
    var hasReasoning = false
  }
  private val attemptTextBuffers = java.util.concurrent.ConcurrentHashMap<String, TextBuffer>()

  /**
   * Per-attempt authoritative usage (#84): merged last-wins value per live
   * attempt id, written once before the attempt terminal. Removed by
   * [flushAttemptUsage] (terminal/cancel paths), so at most the live attempt
   * is retained. Attempt isolation: retries mint fresh ids with fresh cells.
   */
  private val attemptUsages = java.util.concurrent.ConcurrentHashMap<String, StreamEvent.Usage>()
  /** Per-attempt usage-event arrival counts for the [MAX_USAGE_EVENTS_PER_ATTEMPT] flood cap. */
  private val attemptUsageSeen = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

  /**
   * Last-wins usage merge (#84): the incoming event's non-null fields win,
   * null fields keep the previous value — so an Anthropic dual (`Usage(in,
   * null)` then `Usage(in, out)`) and a split (`Usage(in, null)` then
   * `Usage(null, out)`) both converge to the authoritative final.
   */
  private fun mergeUsage(old: StreamEvent.Usage?, event: StreamEvent.Usage): StreamEvent.Usage =
    StreamEvent.Usage(
      inputTokens = event.inputTokens ?: old?.inputTokens,
      outputTokens = event.outputTokens ?: old?.outputTokens,
    )

  /**
   * Writes one attempt's authoritative usage (if any) as a single
   * [TranscriptSink.onUsage] notice and clears its cells. Best-effort like
   * every notice (seal race drops explicitly, cancellation propagates).
   * Called before every attempt terminal (success / terminal-failure /
   * intermediate partial) and on the cancel path, so one attempt owns at
   * most one usage notice no matter how many `Usage` events arrived.
   */
  private suspend fun flushAttemptUsage(runId: String) {
    val merged = attemptUsages[runId]
    if (merged != null) {
      try {
        writeNotice { orderedTranscript.onUsage(runId, merged.inputTokens, merged.outputTokens) }
        attemptUsages.remove(runId)
        attemptUsageSeen.remove(runId)
      } catch (e: Throwable) {
        if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
        // Best-effort: swallow the failure and keep the cell. No non-cancel
        // path flushes this attempt id again; the only later transfer is the
        // cancel handler's flushAttemptUsage(cancelId), while this attempt is
        // still current and the branch is not the suppressed final (e.g. the
        // retry-backoff gap before the next mint, or a terminal window that
        // settles FAILED), bounded by BEST_EFFORT_TIMEOUT_MS. Otherwise the
        // row is lost — deliberate: usage is accounting-only, never
        // load-bearing.
      }
    } else {
      attemptUsageSeen.remove(runId)
    }
  }

  private val _lastRequestTrimmedCount = AtomicInteger(0)

  /**
   * Number of messages trimmed from the head of the request to fit within [MAX_REQUEST_CHARS] (R2-6).
   * Observable counterpart to request-level context trimming.
   */
  val lastRequestTrimmedCount: Int get() = _lastRequestTrimmedCount.get()

  /**
   * R5-5: [lastRequestTrimmedCount] as a StateFlow, emitted synchronously in
   * [buildRequest] alongside the AtomicInteger set (one emission per attempt,
   * at most 4 per send — negligible cost; last attempt wins); a tool
   * continuation (`buildToolContinuation`) additionally emits its total once
   * per tool round, adding the continuation's own prefix trim on top of the
   * base request's count. Distinct from
   * the load-time [droppedHistoryCount] (DB tail cap): this counts send-time
   * per-request 100k-char trims. Never summed with resume omission; surfaced
   * via ChatSession/VM as `requestTrimmedCount`. Rejections before any request
   * (oversize input, UNKNOWN admission) leave the last emitted value untouched.
   */
  private val _requestTrimmedCount = MutableStateFlow(0)
  val requestTrimmedCount: StateFlow<Int> = _requestTrimmedCount.asStateFlow()

  private var inFlight: Job? = null
  private val steerQueue: ArrayDeque<PendingSteer> = ArrayDeque()
  private var closed = false
  /**
   * R22 Phase 1 sticky close obligation, liftable tier (guarded by [lock]).
   *
   * Set by [close] when it observes a controller-observed terminal obligation
   * (direct [pendingAdmission], a live host via [activeLocked], or an
   * in-flight [orphanDrainJob]): the post-close path for those families still
   * runs through controller code (finalize-closed rollback + cancel-terminal
   * drain, doomed-host join + cancel terminal + receipt settle), so the flag
   * lifts once [hasClosedUnsettledAdmission]'s clean proof holds (shutdown
   * completed, no flags, no durable failures, receipts settled). Until then
   * [flushDurable] stays false (never true-and-dangling).
   *
   * Unprovable fates (UNKNOWN ghost, promotion await — see
   * [closedWithUnknownGhost]) never lift: only reopen backfill can own them.
   */
  private var closedWithUnsettledAdmission = false
  /**
   * R22 Phase 1 sticky close obligation, permanent tier (guarded by [lock]).
   *
   * Set by [close] when it drops an [unknownAdmission] (direct or promotion
   * ghost: fate unprovable by definition) or a [promotionPending] reservation
   * (Lock-1 may already have sent it, and the post-close Lock-2 closed-win
   * writes no row and drains no terminal). The controller can never prove
   * those families final — the acked ghost, if any, survives as an orphan
   * user row with no cancel-terminal — so this never lifts on this session;
   * only reopen INTERRUPTED backfill (a new session) repairs the ledger.
   * Conservative by design (a pre-send parked promotion with a clean ledger
   * also trips it): fail-closed until a ledger probe proves otherwise
   * (Target: probeLedgerHasNoDangling; Current: this flag).
   */
  private var closedWithUnknownGhost = false
  /** Monotonic under [lock]; invalidates fresh-gate failures after turn activity. */
  private var activityRevision = 0L
  /**
   * Stage F: attempt ids whose terminal row is already durably acked
   * (success final, terminal-failure final, or retryable intermediate partial
   * non-final). A cancel that lands on a contained id writes one system-kind
   * final mark instead of a second assistant row for the same attempt. Ids
   * are unique per attempt (UUID/newId), so the set stays bounded by the
   * session's attempt count; entries are added only after the ack succeeds.
   *
   * D15-2: the set alone cannot see the commit→ack window (the writer has
   * committed the store row but the ack has not yet returned, so the set is
   * still empty and the old handler wrote a duplicate assistant row for the
   * same attempt). The handler now combines this acked set with the sink's
   * synchronous submit truth ([OrderedTranscriptSink.coreSubmitTruth]) and the
   * attempt-dimension terminal intent below ([attemptTerminalFinal]):
   * SUBMITTING or COMMITTED suppresses the duplicate assistant; a suppressed
   * final terminal writes zero new rows
   * (the family already owns its final), a suppressed intermediate partial
   * writes one system-kind final mark (the family still dangles). FAILED or
   * never-submitted keeps the normal final cancel assistant. The sink owns
   * the commit truth, the controller owns the cancel decision.
   */
  private val terminalizedAttempts: MutableSet<String> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

  /**
   * R18-2 direction-A per-entry terminal receipt (attemptId -> terminal entry).
   *
   * Replaces the D15-2 Boolean-only intent (`true` for success /
   * terminal-failure finals, `false` for retryable intermediate partials)
   * with the terminal entry itself: [TerminalEntry.isFinal] keeps the
   * D15-2 kind, [TerminalEntry.outcome] is the fate cell published by that
   * entry's writer (see
   * [OrderedTranscriptSink.onTurnSucceededWithReceipt] /
   * [OrderedTranscriptSink.onTurnFailedWithReceipt]). Recorded synchronously
   * BEFORE the terminal write is admitted, so a cancel landing in the
   * commit→ack window (or while SUBMITTING) still sees which kind of terminal
   * it is racing even though [terminalizedAttempts] is not yet updated — and
   * the receipt waiter observes that outcome directly (null vs true vs false) instead of the aggregated per-runId
   * commitCounts/interrupted mix, which cannot tell terminal SUCCESS +
   * system FAIL apart from terminal FAIL + system SUCCESS (both observe
   * `commitCount == 2, interrupted == true, pending == false` for a
   * first-attempt id). Entries are never removed (bounded by the session's
   * attempt count, same as [terminalizedAttempts]); the store stays
   * append-only and legal retries still mint fresh ids with fresh entries.
   */
  private data class TerminalEntry(
    val isFinal: Boolean,
    val ack: CompletableDeferred<Unit>,
    val outcome: AtomicReference<Boolean?>,
  )
  private val attemptTerminalFinal: java.util.concurrent.ConcurrentHashMap<String, TerminalEntry> =
    java.util.concurrent.ConcurrentHashMap()

  /**
   * R17-1 P1 attempt-scoped terminal receipt (controller-owned cancel intent).
   *
   * Installed only when a cancel's suppression rests solely on SUBMITTING
   * (terminal intent present + writer still pending after the bounded
   * settle-wait, neither acked nor COMMITTED). The host keeps its existing
   * optimistic outcome (zero new rows for final intent, one system mark for
   * non-final) so COMMITTED-revoke is a no-op; a late writer FAIL (pending
   * cleared, no commit, INTERRUPTED-marked) is claimed exactly once via CAS
   * and compensated in the background (see [launchCancelReceiptWaiter]).
   *
   * - Key: cancel attempt runId (attempt-scoped, NOT the admission opId).
   *   opId guards admission duplication; runId guards terminal dedup — the
   *   two namespaces never overlap.
   * - `suppressedFinal`: the D15-2 attempt-dimension intent (`true` for
   *   success / terminal-failure finals, `false` for retryable intermediate
   *   partials). Decides the compensation kind: FAIL + final → normal cancel
   *   final (assistant, isPartial, isFinal=true); FAIL + non-final → partial
   *   dump (assistant, isPartial, isFinal=false, preserving the cancel-time
   *   text snapshot) — chosen over a second system mark because it preserves
   *   the user-visible fragment that would otherwise be lost, reuses the
   *   existing sink API without new methods, and stays backfill-idempotent
   *   (the family is already closed by the optimistic system mark).
   * - State: PENDING → REVOKED (COMMITTED/acked, no op) | CLAIMED → COMPENSATED
   *   (FAILED, compensation written) | SEALED (sealed/closed/killed: no write,
   *   degrade to INTERRUPTED/backfill). CAS via [AtomicReference] guarantees
   *   exactly-once in-process; the writer-finally outcome (commit vs fail) is
   *   the single linearization point observed via the sink's atomic
   *   [OrderedTranscriptSink.coreSubmitTruth] snapshot (+ [terminalizedAttempts]).
   * - Bounded by the session's attempt count (one entry per cancelId, never
   *   removed — same bound as [attemptTerminalFinal]/[terminalizedAttempts]).
   *   Commit truth stays in the sink (append-only counts); this map holds only
   *   cancel intent, never persistence truth. Not persisted across restart —
   *   a kill falls back to the reopen INTERRUPTED backfill.
   */
  private enum class CancelReceiptState { PENDING, REVOKED, CLAIMED, COMPENSATED, SEALED }
  /**
   * R18-2 failure attribution for the optimistic system mark (non-final only).
   *
   * Captured synchronously at the `onLogicalTurnCancelled` call site inside
   * NonCancellable (success vs store-failure vs seal), never inferred from
   * the aggregated per-runId mix. Together with the per-entry terminal
   * outcome ([CancelReceipt.terminalOutcome]) this is the attribution beyond
   * [CancelReceipt.suppressedFinal]: terminal FAIL → assistant path, system
   * FAIL → system-mark idempotent exact-text rewrite (never a second
   * assistant row).
   */
  private enum class SystemMarkOutcome { SKIPPED, PENDING, SUCCESS, FAILED, SEALED }
  private data class CancelReceipt(
    val cancelId: String,
    val logicalTurnId: String,
    val suppressedFinal: Boolean,
    val cancelText: String,
    val cancelParent: String?,
    val cancelIndex: Int,
    val cancelReasoning: Boolean,
    /**
     * R18-2 per-entry terminal outcome: the same [AtomicReference] published
     * by the sink writer for this terminal entry (null = pending, true =
     * committed, false = failed/sealed). Shared with [TerminalEntry.outcome],
     * observed by the waiter with a plain atomic read (no delicate ack
     * introspection, no aggregated mix).
     */
    val terminalOutcome: AtomicReference<Boolean?>? = null,
    /**
     * R18-2 system-mark attribution: SKIPPED when no system mark was
     * attempted (final intent, optimistic zero rows); PENDING until the
     * optimistic `onLogicalTurnCancelled` settles, then SUCCESS/FAILED/SEALED.
     *
     * Current (Phase 5 note): no write site exists — every receipt is
     * constructed SKIPPED and nothing ever CASes it, so the waiter branches
     * keyed on PENDING (still-settling stall) and FAILED (system-only
     * repair) are dead, and [isSealedUncompensated] effectively reads
     * `suppressedFinal && terminalOutcome == true`. Kept as an explicit
     * dimension (not deleted) so a future optimistic system-mark write has
     * a cell to land in; wiring that write is Target, not claimed here.
     * Observed by the background waiter.
     */
    val systemOutcome: AtomicReference<SystemMarkOutcome> =
      AtomicReference(SystemMarkOutcome.SKIPPED),
    val state: AtomicReference<CancelReceiptState> = AtomicReference(CancelReceiptState.PENDING),
  )
  /**
   * R19-1 cancel receipts: one entry per cancel attempt, never removed
   * within the session lifetime, hence bounded by the session's attempt
   * count (not by wall-clock time or ledger size).
   *
   * Stage-3 resource note (P2-6, Current): no session-level FIFO cap is
   * enforced — a pathological session with N cancels holds N small receipt
   * cells (+ N waiter Job handles in [cancelReceiptWaiters]) until close.
   * Each entry is a few AtomicReferences (no payload, no transcript rows),
   * so production cancel counts stay negligible; a session-level cap (e.g.
   * 128 entries, FIFO-evicting sealed-and-backfilled first) stays Target,
   * not claimed here. Kill recovery never depends on this map (reopen
   * INTERRUPTED backfill owns cross-restart durability).
   */
  private val cancelReceipts: java.util.concurrent.ConcurrentHashMap<String, CancelReceipt> =
    java.util.concurrent.ConcurrentHashMap()

  /**
   * R19-1 receipt waiter jobs keyed by cancelId (same attempt boundary as
   * [cancelReceipts]: one entry per cancel attempt, never removed, bounded
   * by the session's attempt count). [launchCancelReceiptWaiter] registers
   * here; [close]'s shutdown settle joins the snapshot after the doomed-host
   * + orphan joins and before the flush barrier, so a terminal outcome
   * handoff (CLAIM/REVOKED/COMPENSATED/SEALED) always precedes the barrier
   * and any compensation admission lands before `channel.close()`.
   */
  private val cancelReceiptWaiters: java.util.concurrent.ConcurrentHashMap<String, Job> =
    java.util.concurrent.ConcurrentHashMap()

  /**
   * R17-1 P1 late-writer compensation waiter (background, NonCancellable).
   *
   * Runs in [writerScope] (independent of the cancelled host) so the UI
   * cancel stays synchronous (CANCELLED already flipped by [cancel]) while
   * settlement continues. Observes the sink's synchronous submit truth via
   * one atomic [OrderedTranscriptSink.coreSubmitTruth] snapshot per round —
   * never separate commit-then-pending reads, whose torn mix (stale count
   * `0` + already-cleared pending) misclaimed a committed terminal as FAILED
   * (see that API's KDoc) — with the same D15-2 predicates (first-attempt
   * terminal needs `commitCount >= 2`, fresh retry ids need `>= 1`;
   * SUBMITTING additionally requires terminal intent) until the writer
   * settles:
   * - COMMITTED/acked → CAS PENDING→REVOKED, no op (optimistic stands).
   * - FAILED (pending cleared, no commit, not acked) → double-check the
   *   snapshot once more immediately before the CAS PENDING→CLAIMED (no
   *   suspend between, so the window is microseconds), then compensate via
   *   the normal [orderedTranscript] path (which reserves admission queuing;
   *   never a synchronous send inside the sink writer thread — this waiter
   *   is a separate coroutine): final intent gets the normal cancel final,
   *   non-final gets the partial dump documented above. A commit landing
   *   between CLAIM and the write is re-checked pre-write and revokes
   *   (CLAIMED→REVOKED, no row written yet, optimistic stands).
   *   Compensation rows use the same isFinal/kind as the immediate paths, so
   *   backfill stays idempotent (family already closed or closed by the
   *   compensation final; a second open adds zero rows).
   * - Sealed/closed → CAS→SEALED, no write (degrade to INTERRUPTED/backfill).
   * - Bounded poll (50ms base, capped exponential backoff, finite rounds —
   *   see below): timeout degrades to SEALED with a Log.w instead of parking
   *   forever behind a wedged writer. Cancelled with the scope on
   *   [close]/shutdown teardown likewise degrades.
   */
  /**
   * R17-1 P1 late-writer compensation waiter (background, NonCancellable) +
   * R18-2 direction-A per-entry attribution.
   *
   * Runs in [writerScope] (independent of the cancelled host) so the UI
   * cancel stays synchronous (CANCELLED already flipped by [cancel]) while
   * settlement continues. The terminal fate is observed via the per-entry
   * outcome ([CancelReceipt.terminalOutcome]: null vs true vs false) and
   * the optimistic system-mark outcome via
   * ([CancelReceipt.systemOutcome]: SKIPPED/PENDING/SUCCESS/FAILED/SEALED —
   * Phase 5 Current: always SKIPPED, no write site exists, so the
   * PENDING/FAILED branches below are dead; kept as the explicit dimension
   * for a future optimistic write, see the field KDoc) — never the aggregated per-runId commitCounts/interrupted
   * mix alone, which observes terminal SUCCESS + system FAIL and terminal
   * FAIL + system SUCCESS identically (`commitCount == 2, interrupted ==
   * true, pending == false` for a first-attempt id) and mis-compensated with
   * a duplicate assistant row.
   *
   * - Terminal committed + system ok/skipped → CAS PENDING→REVOKED, no op
   *   (optimistic stands).
   * - Terminal committed + system FAILED → CLAIM then system-mark idempotent
   *   exact-text rewrite (`onLogicalTurnCancelled` with the same ids/text),
   *   never a second assistant row.
   * - Terminal FAILED (+ system ok/skipped) → CLAIM then assistant path
   *   (final intent → normal cancel final; non-final → partial dump, same
   *   isFinal/kind as the immediate paths so backfill stays idempotent).
   * - Both failed (non-final terminal FAIL + system FAILED) → CLAIM then
   *   assistant first + system exact-text second, sequentially in the same
   *   NonCancellable; both must succeed for COMPENSATED, else SEALED.
   * - Sealed/closed → CAS→SEALED, no write (degrade to INTERRUPTED/backfill).
   * - Still pending (terminal ack incomplete or system PENDING) → back off
   *   (50→100→200→400→500ms cap, 60 rounds) and bound the wait; timeout
   *   degrades to SEALED with a Log.w instead of parking forever behind a
   *   wedged writer. Cancelled with the scope on [close]/shutdown teardown
   *   likewise degrades.
   *
   * Thresholds, submitTruthLock snapshot, CAS exactly-once and seal
   * degradation are preserved: [OrderedTranscriptSink.coreSubmitTruth] is
   * still read atomically per round (submitTruthLock) with the same D15-2
   * `commitCount >= 2` (first attempt) vs `>= 1` (fresh retry id) predicates
   * for the legacy revoke fast-path and the pre-write re-check; the
   * per-entry ack + system outcome decide attribution where the aggregate
   * cannot. Compensation writes stay bounded via
   * [cancelCompensationTimeoutMs] (WARN-1) and re-enter NonCancellable
   * through the normal [orderedTranscript] path (which reserves admission
   * queuing; never a synchronous send inside the sink writer thread — this
   * waiter is a separate coroutine). A commit landing between CLAIM and the
   * write is re-checked pre-write and revokes (CLAIMED→REVOKED, no row
   * written yet, optimistic stands).
   */
  private fun launchCancelReceiptWaiter(receipt: CancelReceipt): Job {
    // Child of writerScope (cancelled on shutdown teardown → degrades to
    // SEALED/backfill); compensation writes below re-enter NonCancellable.
    // R19-1: the job is registered by cancelId (same attempt boundary as
    // [cancelReceipts]) so [close]'s shutdown settle can bounded-join it
    // before the flush barrier (see [close]).
    val job = writerScope.launch {
      // R24-1 pre-dispatch seam: first instruction of the waiter body, so a
      // test holding the writer dispatcher can prove whether the body ever
      // started (cancelled-before-start children complete without running a
      // single instruction — see the invokeOnCompletion fail-safe below).
      beforeCancelReceiptWaiterBodyForTest?.invoke()
      fun committedThreshold(count: Int): Boolean =
        if (receipt.cancelId == receipt.logicalTurnId) count >= 2 else count >= 1
      fun terminalCommitted(): Boolean {
        if (receipt.terminalOutcome?.get() == true) return true
        return terminalizedAttempts.contains(receipt.cancelId)
      }
      fun terminalFailed(): Boolean = receipt.terminalOutcome?.get() == false
      fun terminalPending(): Boolean {
        val out = receipt.terminalOutcome ?: return false
        return out.get() == null
      }
      // Bounded system-mark exact-text rewrite (WARN-1): same timeout/seal/
      // store-failure mapping as the assistant path below. Returns true on
      // durable success, false on timeout/seal/store-failure (caller degrades
      // to SEALED/backfill).
      suspend fun writeSystemMarkBounded(): Boolean {
        return try {
          val completed = withTimeoutOrNull(cancelCompensationTimeoutMs) {
            orderedTranscript.onLogicalTurnCancelled(
              receipt.logicalTurnId, receipt.cancelId,
              receipt.cancelText, receipt.cancelIndex,
            )
          }
          if (completed == null) {
            runCatching {
              android.util.Log.w(
                "TurnController",
                "cancel receipt system compensation timed out for ${receipt.cancelId}",
              )
            }
            false
          } else {
            try {
              orderedTranscript.flushBufferedUsageBestEffort(receipt.cancelId)
              if (receipt.cancelId != receipt.logicalTurnId) {
                orderedTranscript.flushBufferedUsageBestEffort(receipt.logicalTurnId)
              }
              orderedTranscript.flush()
            } catch (e: Throwable) {
              if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            }
            true
          }
        } catch (_: ClosedSendChannelException) {
          false
        } catch (sealFailure: CancellationException) {
          if (!orderedTranscript.isSealed()) throw sealFailure
          false
        } catch (storeFailure: Exception) {
          runCatching {
            android.util.Log.w(
              "TurnController",
              "cancel receipt system compensation failed: ${sanitizeError(storeFailure.message ?: "")}",
            )
          }
          false
        }
      }
      suspend fun writeAssistantFinalBounded(): Boolean {
        return try {
          val completed = withTimeoutOrNull(cancelCompensationTimeoutMs) {
            orderedTranscript.onTurnCancelled(
              receipt.cancelId, receipt.cancelText,
              receipt.cancelParent, receipt.cancelIndex, receipt.cancelReasoning,
            )
          }
          if (completed == null) {
            runCatching {
              android.util.Log.w(
                "TurnController",
                "cancel receipt compensation timed out for ${receipt.cancelId}",
              )
            }
            false
          } else {
            try {
              orderedTranscript.flushBufferedUsageBestEffort(receipt.cancelId)
              if (receipt.cancelId != receipt.logicalTurnId) {
                orderedTranscript.flushBufferedUsageBestEffort(receipt.logicalTurnId)
              }
              orderedTranscript.flush()
            } catch (e: Throwable) {
              if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            }
            true
          }
        } catch (_: ClosedSendChannelException) {
          false
        } catch (sealFailure: CancellationException) {
          if (!orderedTranscript.isSealed()) throw sealFailure
          false
        } catch (storeFailure: Exception) {
          runCatching {
            android.util.Log.w(
              "TurnController",
              "cancel receipt compensation failed: ${sanitizeError(storeFailure.message ?: "")}",
            )
          }
          false
        }
      }
      suspend fun writeAssistantPartialBounded(): Boolean {
        return try {
          val completed = withTimeoutOrNull(cancelCompensationTimeoutMs) {
            orderedTranscript.onTurnFailed(
              receipt.cancelId, receipt.cancelText, "partial lost (store failure)",
              receipt.cancelParent, receipt.cancelIndex, false,
              receipt.cancelReasoning,
            )
          }
          if (completed == null) {
            runCatching {
              android.util.Log.w(
                "TurnController",
                "cancel receipt compensation timed out for ${receipt.cancelId}",
              )
            }
            false
          } else {
            try {
              orderedTranscript.flushBufferedUsageBestEffort(receipt.cancelId)
              if (receipt.cancelId != receipt.logicalTurnId) {
                orderedTranscript.flushBufferedUsageBestEffort(receipt.logicalTurnId)
              }
              orderedTranscript.flush()
            } catch (e: Throwable) {
              if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            }
            true
          }
        } catch (_: ClosedSendChannelException) {
          false
        } catch (sealFailure: CancellationException) {
          if (!orderedTranscript.isSealed()) throw sealFailure
          false
        } catch (storeFailure: Exception) {
          runCatching {
            android.util.Log.w(
              "TurnController",
              "cancel receipt compensation failed: ${sanitizeError(storeFailure.message ?: "")}",
            )
          }
          false
        }
      }
      // R18-1 Stage 2 atomic FAILED batch (bounded, same timeout/seal mapping).
      // Single core entry keyed by cancelId (attempt id) persisting
      // partial(non-final)+system(final) via one appendEvents unit. True only
      // on durable success; timeout/seal/store-failure degrades to
      // SEALED/backfill (family stays dangling, reopen INTERRUPTED takes over).
      suspend fun writeFailedAndCancelledBatchBounded(): Boolean {
        return try {
          val completed = withTimeoutOrNull(cancelCompensationTimeoutMs) {
            orderedTranscript.onTurnFailedAndCancelled(
              receipt.logicalTurnId, receipt.cancelId,
              receipt.cancelText, "partial lost (store failure)",
              receipt.cancelParent, receipt.cancelIndex,
              receipt.cancelReasoning,
            )
          }
          if (completed == null) {
            runCatching {
              android.util.Log.w(
                "TurnController",
                "cancel receipt batch compensation timed out for ${receipt.cancelId}",
              )
            }
            false
          } else {
            try {
              orderedTranscript.flushBufferedUsageBestEffort(receipt.cancelId)
              if (receipt.cancelId != receipt.logicalTurnId) {
                orderedTranscript.flushBufferedUsageBestEffort(receipt.logicalTurnId)
              }
              orderedTranscript.flush()
            } catch (e: Throwable) {
              if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            }
            true
          }
        } catch (_: ClosedSendChannelException) {
          false
        } catch (sealFailure: CancellationException) {
          if (!orderedTranscript.isSealed()) throw sealFailure
          false
        } catch (storeFailure: Exception) {
          runCatching {
            android.util.Log.w(
              "TurnController",
              "cancel receipt batch compensation failed: ${sanitizeError(storeFailure.message ?: "")}",
            )
          }
          false
        }
      }
      try {
        var backoffMs = 50L
        var waits = 0
        while (true) {
          // R19-1: only the channel seal (not controller [closed]) degrades
          // here. [close]'s shutdown settle bounded-joins this waiter BEFORE
          // the flush barrier, so a receipt still owed its handoff when close
          // lands must keep polling until the terminal outcome resolves and
          // the compensation is admitted pre-`channel.close()` — bailing on
          // [closed] would seal the family dangling while the terminal (and
          // its compensation window) is still alive, producing
          // true-and-dangling. Post-seal the channel truth ([isSealed] /
          // ClosedSendChannelException below) still degrades to SEALED.
          if (orderedTranscript.isSealed()) {
            receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.SEALED)
            break
          }
          // R18-1 Stage 2 notice-first deferred final (non-final only).
          //
          // The host wrote only a durable non-terminal notice (retry-like,
          // family stays dangling); the system final was NOT written
          // optimistically, so there is nothing to revoke here. The terminal
          // outcome decides the deferred write: SUCCESS (committed) → append
          // system cancelled in order; FAILED → atomic batch
          // partial(non-final)+system(final) via one appendEvents unit.
          // Still-pending → back off with [cancelReceiptMaxRounds]; timeout /
          // seal writes no terminal row (family stays dangling,
          // reopen INTERRUPTED takes over). Per-entry outcome is authoritative
          // (Stage 1 preserved); legacy acked/committed thresholds (>=2 first
          // attempt, >=1 fresh retry) are kept as the commit fast-path.
          if (!receipt.suppressedFinal) {
            val nAcked = terminalizedAttempts.contains(receipt.cancelId)
            val nSnap = orderedTranscript.coreSubmitTruth(receipt.cancelId)
            val nFailedMark = orderedTranscript.interruptedRunIds().contains(receipt.cancelId)
            val nCommittedAgg = committedThreshold(nSnap.commitCount) && !nFailedMark
            val nTermOk = terminalCommitted()
            val nTermFail = terminalFailed()
            val nTermPend = terminalPending()
            val nCommitted = nTermOk || ((nAcked || nCommittedAgg) && !nTermFail)
            if (nTermPend && !nCommitted && !nTermFail) {
              waits++
              if (waits >= cancelReceiptMaxRounds) {
                runCatching {
                  android.util.Log.w(
                    "TurnController",
                    "cancel receipt waiter timed out for ${receipt.cancelId}",
                  )
                }
                receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.SEALED)
                break
              }
              delay(backoffMs)
              backoffMs = (backoffMs * 2).coerceAtMost(500L)
              continue
            }
            if (!receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.CLAIMED)) break
            try {
              withContext(NonCancellable) {
                // R19-1: seal-only gate (see the loop-top note): the settle
                // join holds the barrier open for this CLAIM, so controller
                // [closed] must not revoke the admission — only a real seal
                // (or a failed/bounded write below) degrades to SEALED.
                if (orderedTranscript.isSealed()) {
                  receipt.state.set(CancelReceiptState.SEALED)
                } else {
                  // Pre-write re-check (no suspend between CLAIM and these
                  // atomic reads): a commit landing after the loop read selects
                  // the SUCCESS system path, never a duplicate partial.
                  val curOk = terminalCommitted()
                  val curAcked = terminalizedAttempts.contains(receipt.cancelId)
                  val curSnap = orderedTranscript.coreSubmitTruth(receipt.cancelId)
                  val curFailedMark = orderedTranscript.interruptedRunIds().contains(receipt.cancelId)
                  val curAgg = committedThreshold(curSnap.commitCount) && !curFailedMark
                  val curFail = terminalFailed()
                  val curCommitted = curOk || ((curAcked || curAgg) && !curFail)
                  if (curCommitted && !curFail) {
                    if (writeSystemMarkBounded()) {
                      receipt.state.set(CancelReceiptState.COMPENSATED)
                    } else {
                      receipt.state.set(CancelReceiptState.SEALED)
                    }
                  } else if (curFail) {
                    if (writeFailedAndCancelledBatchBounded()) {
                      receipt.state.set(CancelReceiptState.COMPENSATED)
                    } else {
                      receipt.state.set(CancelReceiptState.SEALED)
                    }
                  } else {
                    runCatching {
                      android.util.Log.w(
                        "TurnController",
                        "cancel receipt indeterminate for ${receipt.cancelId}",
                      )
                    }
                    receipt.state.set(CancelReceiptState.SEALED)
                  }
                }
              }
            } catch (_: CancellationException) {
              receipt.state.compareAndSet(CancelReceiptState.CLAIMED, CancelReceiptState.SEALED)
            } catch (compensationFailure: Exception) {
              runCatching {
                android.util.Log.w(
                  "TurnController",
                  "cancel receipt waiter failed: ${sanitizeError(compensationFailure.message ?: "")}",
                )
              }
              receipt.state.compareAndSet(CancelReceiptState.CLAIMED, CancelReceiptState.SEALED)
            }
            break
          }
          // Legacy aggregated snapshot (submitTruthLock atomic, thresholds
          // preserved): fast-path revoke only, never attribution. A
          // store-failed terminal stays in interrupted (append-only), while a
          // committed terminal never does — so COMMITTED requires no
          // interrupt mark (R17-1 fix kept).
          val ackedNow = terminalizedAttempts.contains(receipt.cancelId)
          val snap = orderedTranscript.coreSubmitTruth(receipt.cancelId)
          val failedNow = orderedTranscript.interruptedRunIds().contains(receipt.cancelId)
          val committedNow = committedThreshold(snap.commitCount) && !failedNow
          // Per-entry fate (authoritative for attribution).
          val termOk = terminalCommitted()
          val termFail = terminalFailed()
          val termPend = terminalPending()
          val sys = receipt.systemOutcome.get()
          // Revoke when the optimistic outcome already stands: terminal
          // committed and no system repair needed. Legacy acked/committed is
          // kept as an additional fast-path (same thresholds as D15-2/R17-1);
          // per-entry covers the ack-not-yet-added commit→ack window.
          if ((ackedNow || committedNow || termOk) && (sys == SystemMarkOutcome.SKIPPED || sys == SystemMarkOutcome.SUCCESS)) {
            // For final intent (SKIPPED) the terminal alone decides; for
            // non-final the system must also have succeeded. When the system
            // FAILED below, this does not revoke even though the aggregate
            // count may look committed — that is the R18-2 fix.
            if (termOk && (sys == SystemMarkOutcome.SKIPPED || sys == SystemMarkOutcome.SUCCESS)) {
              receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.REVOKED)
              break
            }
            if ((ackedNow || committedNow) && (sys == SystemMarkOutcome.SKIPPED || sys == SystemMarkOutcome.SUCCESS) && !termFail) {
              receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.REVOKED)
              break
            }
          }
          // Still settling: terminal ack incomplete OR optimistic system mark
          // not yet settled (host still in its NonCancellable write).
          val stillPending = termPend || sys == SystemMarkOutcome.PENDING
          if (stillPending) {
            waits++
            if (waits >= cancelReceiptMaxRounds) {
              runCatching {
                android.util.Log.w(
                  "TurnController",
                  "cancel receipt waiter timed out for ${receipt.cancelId}",
                )
              }
              receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.SEALED)
              break
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(500L)
            continue
          }
          // Both settled and not revoked → need compensation. Double-check
          // immediately before CLAIM (no suspend between, microseconds): a
          // commit landing after the loop read above revokes instead of
          // claiming.
          val reTermOk = terminalCommitted()
          val reSys = receipt.systemOutcome.get()
          val reAcked = terminalizedAttempts.contains(receipt.cancelId)
          val reSnap = orderedTranscript.coreSubmitTruth(receipt.cancelId)
          val reFailed = orderedTranscript.interruptedRunIds().contains(receipt.cancelId)
          val reCommitted = committedThreshold(reSnap.commitCount) && !reFailed
          if (reTermOk && (reSys == SystemMarkOutcome.SKIPPED || reSys == SystemMarkOutcome.SUCCESS)) {
            receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.REVOKED)
            break
          }
          if ((reAcked || reCommitted) && (reSys == SystemMarkOutcome.SKIPPED || reSys == SystemMarkOutcome.SUCCESS) && !terminalFailed()) {
            receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.REVOKED)
            break
          }
          if (!receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.CLAIMED)) break
          try {
            withContext(NonCancellable) {
              // R19-1: seal-only gate (see the loop-top note).
              if (orderedTranscript.isSealed()) {
                receipt.state.set(CancelReceiptState.SEALED)
              } else {
                // Pre-write re-check: commit/ack landing between CLAIM and
                // the write revokes (no row written yet, optimistic stands).
                val curTermOk = terminalCommitted()
                val curSys = receipt.systemOutcome.get()
                val curAcked = terminalizedAttempts.contains(receipt.cancelId)
                val curSnap = orderedTranscript.coreSubmitTruth(receipt.cancelId)
                val curFailed = orderedTranscript.interruptedRunIds().contains(receipt.cancelId)
                val curCommitted = committedThreshold(curSnap.commitCount) && !curFailed
                val curTermFail = terminalFailed()
                if ((curTermOk || ((curAcked || curCommitted) && !curTermFail)) &&
                  (curSys == SystemMarkOutcome.SKIPPED || curSys == SystemMarkOutcome.SUCCESS)
                ) {
                  receipt.state.set(CancelReceiptState.REVOKED)
                } else if (!curTermFail && curSys == SystemMarkOutcome.FAILED) {
                  // R18-2 system-only repair: terminal committed, optimistic
                  // system mark failed. Idempotent exact-text rewrite, never
                  // a second assistant row.
                  if (writeSystemMarkBounded()) {
                    receipt.state.set(CancelReceiptState.COMPENSATED)
                  } else {
                    receipt.state.set(CancelReceiptState.SEALED)
                  }
                } else if (curTermFail) {
                  // Terminal failed: assistant path (same isFinal/kind as the
                  // immediate paths). When the system also failed (non-final
                  // both-failed), the assistant lands first and the system
                  // exact-text second, sequentially; both must succeed.
                  val assistantOk = if (receipt.suppressedFinal) {
                    writeAssistantFinalBounded()
                  } else {
                    writeAssistantPartialBounded()
                  }
                  if (!assistantOk) {
                    receipt.state.set(CancelReceiptState.SEALED)
                  } else if (curSys == SystemMarkOutcome.FAILED) {
                    if (writeSystemMarkBounded()) {
                      receipt.state.set(CancelReceiptState.COMPENSATED)
                    } else {
                      receipt.state.set(CancelReceiptState.SEALED)
                    }
                  } else {
                    receipt.state.set(CancelReceiptState.COMPENSATED)
                  }
                } else {
                  // Unexpected: neither committed nor failed after both
                  // settled (e.g. terminal ack abandoned pre-send with no
                  // intent). Degrade loudly so backfill owns the row, never
                  // leave CLAIMED stuck.
                  runCatching {
                    android.util.Log.w(
                      "TurnController",
                      "cancel receipt indeterminate for ${receipt.cancelId}",
                    )
                  }
                  receipt.state.set(CancelReceiptState.SEALED)
                }
              }
            }
          } catch (_: CancellationException) {
            receipt.state.compareAndSet(CancelReceiptState.CLAIMED, CancelReceiptState.SEALED)
          } catch (compensationFailure: Exception) {
            runCatching {
              android.util.Log.w(
                "TurnController",
                "cancel receipt waiter failed: ${sanitizeError(compensationFailure.message ?: "")}",
              )
            }
            receipt.state.compareAndSet(CancelReceiptState.CLAIMED, CancelReceiptState.SEALED)
          }
          break
        }
      } catch (_: CancellationException) {
        receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.SEALED)
      } catch (waiterFailure: Exception) {
        runCatching {
          android.util.Log.w(
            "TurnController",
            "cancel receipt waiter failed: ${sanitizeError(waiterFailure.message ?: "")}",
          )
        }
        receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.SEALED)
      }
    }
    cancelReceiptWaiters.putIfAbsent(receipt.cancelId, job)
    // R24-1 cancelled-before-start fail-safe: every PENDING/CLAIMED→SEALED
    // fallback above lives inside the coroutine body, so a waiter that was
    // enqueued but never started (parent scope torn down before first
    // dispatch, or launched into an already-cancelled scope) completes
    // without running the body and would leave the receipt PENDING forever
    // (fail-closed flushDurable + never-dead writer + permanently renewed
    // backfill block). Seal monotonically here — no transcript write, only
    // PENDING/CLAIMED→SEALED, never overwriting
    // REVOKED/COMPENSATED/SEALED. A CLAIMED receipt genuinely inside
    // NonCancellable compensation still owes its terminal row, so the dead
    // proof keeps its PENDING/CLAIMED guard (R14-1 fence untouched); this
    // only converges the never-ran case (and any post-CLAIM teardown
    // residual the body catches missed) to SEALED/backfill.
    job.invokeOnCompletion {
      if (!receipt.state.compareAndSet(CancelReceiptState.PENDING, CancelReceiptState.SEALED)) {
        receipt.state.compareAndSet(CancelReceiptState.CLAIMED, CancelReceiptState.SEALED)
      }
    }
    // Phase 5: fire the waiter-entered seam synchronously after registration
    // (see the seam KDoc): awaiting tests then happens-before any later
    // close snapshot, so the snapshot necessarily contains this job.
    onCancelReceiptWaiterEnteredForTest?.invoke()
    return job
  }

  /**
   * R19-1 read-only receipt settle proof: true while any cancel receipt is
   * still PENDING (awaiting the terminal outcome) or CLAIMED (compensation
   * write in flight). REVOKED/COMPENSATED are settled. SEALED is settled
   * only with proven family-final (see [hasSealedUncompensatedReceipts]):
   * a SEALED receipt that never proved its terminal (especially the
   * non-final notice-first path, where a late SUCCESS still leaves the
   * family dangling without its deferred system mark) is NOT settled.
   * Lock-free (ConcurrentHashMap + AtomicReference reads); never
   * suspends. [flushDurable] conjoins this plus
   * [hasSealedUncompensatedReceipts] so a drained-but-uncompensated
   * ledger never reports true-and-dangling; the ViewModel treats either as
   * same-level downgrade evidence (see `isLedgerStreamingOrPending`).
   */
  fun hasUnsettledCancelReceipts(): Boolean =
    cancelReceipts.values.any {
      when (it.state.get()) {
        CancelReceiptState.PENDING, CancelReceiptState.CLAIMED -> true
        else -> false
      }
    }

  /**
   * Phase 1 SEALED truth contract: true while any cancel receipt is SEALED
   * without proven family-final. The only SEALED receipt that counts as
   * settled is the REVOKED-equivalent the seal raced: final intent
   * ([CancelReceipt.suppressedFinal]) whose per-entry terminal outcome
   * committed (`terminalOutcome == true`) with no system repair owed
   * (`systemOutcome` SKIPPED/SUCCESS). Every other SEALED receipt —
   * non-final notice-first (a late SUCCESS still dangles without its
   * deferred system/batch write), terminal FAILED/null, or system
   * FAILED/PENDING/SEALED — stays uncompensated (fail-closed) until reopen
   * INTERRUPTED backfill owns the family. Lock-free; never suspends.
   * Conjoined into [flushDurable] and consulted by the ViewModel's
   * `isLedgerStreamingOrPending` alongside [hasUnsettledCancelReceipts].
   */
  fun hasSealedUncompensatedReceipts(): Boolean =
    cancelReceipts.values.any { isSealedUncompensated(it) }

  /**
   * Phase 5 structural-test seam: read-only snapshot of cancel-receipt
   * terminal states (`cancelId -> PENDING|REVOKED|CLAIMED|COMPENSATED|SEALED`
   * name). Exposed so tests assert the waiter handoff without reflecting
   * into the private [cancelReceipts] map and its `state` cell. The map is
   * in-memory only (one entry per cancel attempt, never removed, bounded by
   * the session's attempt count; a kill falls back to reopen INTERRUPTED
   * backfill), so this snapshot is attempt-liveness evidence, never
   * cross-restart durability proof — only [flushDurable] plus reopen
   * backfill prove the ledger.
   */
  fun cancelReceiptStates(): Map<String, String> =
    cancelReceipts.entries.associate { it.key to it.value.state.get().name }

  /** Phase 1 SEALED truth predicate (see [hasSealedUncompensatedReceipts]). */
  private fun isSealedUncompensated(receipt: CancelReceipt): Boolean {
    if (receipt.state.get() != CancelReceiptState.SEALED) return false
    if (receipt.suppressedFinal && receipt.terminalOutcome?.get() == true &&
      (receipt.systemOutcome.get() == SystemMarkOutcome.SKIPPED ||
        receipt.systemOutcome.get() == SystemMarkOutcome.SUCCESS)
    ) {
      return false
    }
    return true
  }

  /**
   * D15-2 test seam: runs in [runTurnLoop] immediately after a terminal ack
   * succeeds and [terminalizedAttempts] is updated, before the return /
   * retry-notice / sleeper step (suspending, null in production). Lets the
   * terminal-ack cancel grid deterministically pause the host in the true
   * post-ack window, cancel it there, and assert zero-new (final) vs
   * system-mark (intermediate) without racing the microsecond return path.
   */
  @Volatile
  internal var afterTerminalAckForTest: (suspend (String) -> Unit)? = null

  /** Test seam between an idle admission snapshot and its fresh policy gate. */
  internal var beforeAdmissionGateForTest: (() -> Unit)? = null

  /** Test seam for racing an idle legacy steer with new recovery work. */
  internal var afterIdleSteerCheckForTest: (() -> Unit)? = null

  /** Test seam for pausing after follow-up policy approval, before handoff. */
  internal var afterFollowUpGateForTest: (() -> Unit)? = null

  /**
   * Phase 5 waiter-entered seam: runs synchronously after the waiter job
   * is registered in `cancelReceiptWaiters`, before
   * [launchCancelReceiptWaiter] returns (non-suspending, null in
   * production). Lets the close-barrier grid await waiter
   * launch+registration observably instead of sleeping a fixed 20ms
   * heuristic: once the seam fires, a later [close] snapshot necessarily
   * contains the job, so the handoff-before-barrier order no longer races
   * waiter startup. A cancel still racing [close] concurrently is fenced by
   * close's re-snapshot join; any residual stays fail-closed
   * ([hasUnsettledCancelReceipts] still blocks [flushDurable]).
   */
  internal var onCancelReceiptWaiterEnteredForTest: (() -> Unit)? = null

  /**
   * R24-1 pre-dispatch seam: runs as the first instruction of the waiter
   * coroutine body (non-suspending, null in production). Together with a
   * held writer dispatcher it lets the pre-dispatch regression prove the
   * body never started (seam cold) while the [invokeOnCompletion] fail-safe
   * still converges the receipt — the existing
   * [onCancelReceiptWaiterEnteredForTest] seam only proves
   * launch+registration, never first execution.
   */
  internal var beforeCancelReceiptWaiterBodyForTest: (() -> Unit)? = null

  /**
   * R24-1 waiter-handle seam: returns the registered waiter [Job] for a
   * cancel attempt (null when absent). Lets the pre-dispatch regression
   * cancel the waiter deterministically while the writer dispatcher still
   * holds it undispatched — the production-faithful equivalent of a parent
   * teardown cancel landing before first dispatch (a child cancelled
   * before start behaves identically whether cancelled directly or via its
   * parent). Read-only; never completes or replaces the job.
   */
  internal fun cancelReceiptWaiterJobForTest(cancelId: String): Job? =
    cancelReceiptWaiters[cancelId]

  /** Test seam for pausing a promotion after its peek-reserve, before the durable await. */
  internal var beforePromotionAwaitForTest: (() -> Unit)? = null

  /** Test seam for pausing after follow-up policy denial, before settlement. */
  internal var afterFollowUpDenyForTest: (() -> Unit)? = null

  /** Test seam for arranging a queue admission immediately before completion checks it. */
  internal var beforeCompletionQueueCheckForTest: (() -> Unit)? = null

  /** Test seam for pausing startOrEnqueue after its fresh gate and before its second lock. */
  internal var afterStartOrEnqueueGateForTest: (() -> Unit)? = null

  /** Test seam for pausing startTurn after its fresh gate and before its second lock. */
  internal var afterStartTurnGateForTest: (() -> Unit)? = null

  /**
   * R21-1 Phase 1 test seam: runs after the durable user-row await succeeds
   * (ACKED) and before the finalize lock in [startTurn]/[startOrEnqueue]
   * (suspending, null in production). Lets the admission-finalize barrier grid
   * deterministically park the waiter with the user row already durable but no
   * terminal yet, so close/cancel/fork interleave before the host launches.
   * Must run outside the lock (suspend must never hold the intrinsic monitor).
   */
  internal var beforeAdmissionFinalizeForTest: (suspend () -> Unit)? = null

  /**
   * R22-3 Phase 1 TOCTOU linearization gate: runs inside [flushDurable]
   * between the first durable-failure read (after the admission proof) and
   * the failure re-read (suspending, null in production). Lets the gate test
   * deterministically open a new terminal obligation (or land a durable
   * failure) in that window and prove the second read still fails closed.
   * Must run outside the lock (suspend must never hold the intrinsic monitor).
   */
  internal var beforeFlushDurableReverifyForTest: (suspend () -> Unit)? = null

  /**
   * Stage-2 return-window gate: runs inside [flushDurable] between the
   * second admission snapshot and the final failure read (suspending, null
   * in production). Lets the return-window regression deterministically
   * admit a must-fail write after the last snapshot and prove the
   * fail-last failure read still fails closed. With the fail-last order a
   * failure is the monotonic consequence of an admission/receipt window, so
   * ending on the failure read covers the snapshot-to-return gap the
   * snapshot-last order left open; the snapshot-to-failure gap is the
   * symmetric one-window residual closed only by the Target single-lock
   * snapshot plus monotonic failure epoch. Must run outside the lock.
   */
  internal var beforeFlushDurableReturnForTest: (suspend () -> Unit)? = null

  /**
   * D15-2 settle-wait seam: runs synchronously on the cancelled host after
   * the SUBMITTING admission snapshot and immediately before the bounded
   * settle wait (non-suspending, null in production). Lets the cancel-gap
   * grid await the settle-wait decision observably instead of sleeping a
   * fixed heuristic: once the seam fires, the host has provably committed to
   * the SUBMITTING path, so releasing a blocked terminal writer afterwards
   * still exercises the post-wait re-evaluation (COMMITTED keeps suppression,
   * FAILED falls through to the normal final) instead of racing it.
   */
  internal var onCancelSettleWaitEnteredForTest: (() -> Unit)? = null

  /**
   * D15-2 suppressed-final seam: runs synchronously on the cancelled host as
   * the first instruction of the `suppressed && suppressedFinal` branch
   * (non-suspending, null in production). The branch is also reachable via
   * the still-pending-stall sub-case (settle-wait timeout, optimistic
   * suppression, no commit observed), so the seam alone only proves branch
   * entry. In the commit→ack grid it fires while the writer's ack is still
   * held (the test pins the commit via
   * [OrderedTranscriptSink.afterCommitBeforeAckForTest] before cancelling),
   * which is what lets that grid await it and pin "cancel landed inside the
   * commit→ack window" without a fixed sleep.
   */
  internal var onSuppressedFinalFlushEnteredForTest: (() -> Unit)? = null

  /**
   * Returns the live in-flight host job, lazily clearing a completed one.
   *
   * Has a lazy-clear side effect (`inFlight = null` when completed), so the
   * caller must hold [lock] — like every other `*Locked` accessor here.
   * Read via [activeLocked] rather than touching [inFlight] directly, so the
   * completed-job slot is never mistaken for a live turn.
   */
  private fun activeLocked(): Job? {
    val current = inFlight ?: return null
    if (current.isCompleted) {
      inFlight = null
      return null
    }
    return current
  }

  /**
   * Stage 6: single-flight including the admission-time durable window.
   * [pendingAdmission] is set before the first suspend (durable await) and
   * cleared only on failure or host entry, so a concurrent admission arriving
   * during the await must queue/throw instead of starting a second turn.
   * R6-1: [unknownAdmission] (direct or promotion UNKNOWN) and
   * [promotionPending] (promotion await window) also hold the slot, so a
   * promotion ghost cannot be duplicated by a racing direct admission or
   * reclaimed by drain while its fate is still unknown.
   * Caller must hold [lock].
   *
   * PG-S2 ownership states (Round 7 Stage 4, documentation only — no behavior
   * change; a sealed-class refactor is left to a future RFC because the legal
   * overlaps below do not fit a single-state model and there are 30+ writers):
   *
   * Four flags, all guarded by [lock] (see the declaration-site KDocs for the
   * full set/clear lists):
   * - Active (`inFlight`, via [activeLocked]): the live host job. Set at every
   *   host launch ([claimUnknownLocked], [claimPromotionUnknownLocked],
   *   direct/promotion Lock-2 launches); cleared only by identity guard
   *   (`inFlight === job/self/host`) on completion/cancel, or by the
   *   [activeLocked] lazy clear above.
   * - `pendingAdmission`: direct-admission durable-await window. Set before the
   *   user-row await, cleared on host entry ([hostedTurn] prologue) or
   *   NOT_ADMITTED rollback; retained across the UNKNOWN handoff
   *   ([enterUnknownAdmission] keeps it for direct UNKNOWN), cleared later by
   *   claim / convert / cancel-drain / [close].
   * - `unknownAdmission`: at most one UNKNOWN/PENDING_COMMIT ownership
   *   (direct: `isPromotion == false`, with optimistic UI row; promotion:
   *   `isPromotion == true`, no optimistic row yet, predecessor [Job] kept in
   *   `host`). Set only by [enterUnknownAdmission] (seal-guarded: returns false
   *   and installs nothing when [closed]); cleared only by claim /
   *   convert / cancel-drain / [close], always under the logicalTurnId identity
   *   guard except the [close] seal, which clears unconditionally.
   * - `promotionPending`: Lock-1 promotion reservation (follow-up identity
   *   minted by the promotion peek, held across its durable await). Deliberately
   *   separate from [pendingAdmission] so [close] drains only direct orphans;
   *   cleared by the promotion waiter paths, [enterUnknownAdmission] handoff,
   *   or [close], always under the logicalTurnId identity guard except the
   *   [close] seal, which clears unconditionally.
   *
   * Transition sketch (full set/clear lists are in the declaration-site KDocs
   * above; this sketch is the summary):
   * ```
   * Idle ──direct admit──▶ DirectAwait(pending)
   *   │                      ├─ACK────▶ Active(hostedTurn; pending cleared at entry)
   *   │                      ├─NOT_ADMITTED─▶ Idle (rollback, retryable)
   *   │                      ├─UNKNOWN──▶ UnknownDirect(unknown + pending, same id)
   *   │                      └─cancel/close─▶ Idle (cancel-drain / orphan drain)
   *   │
   *   └──promotion peek──▶ PromoAwait(active predecessor + promotionPending)
   *                          ├─ACK────▶ Active(predecessor → follow handoff)
   *                          ├─NOT_ADMITTED(proven never-attempted timeout/CE or seal)─▶ no launch,
   *                          │                 FIFO retained, ERROR (predecessor terminal already recorded)
   *                          ├─drain guard (Round 10 H1): drainQueued() throws Pending and
   *                          │                 drainRecoveryLocked() returns null while PromoAwait holds —
   *                          │                 the head is NOT reclaimable until ACK/settle
   *                          ├─close─▶ Idle (FIFO dropped per drain-before-close; no post-seal
   *                          │                 UNKNOWN rebuild — Round 10 M1 fail-closed)
   *                          └─UNKNOWN(timeout-fuzzy or CE-fuzzy R8-1: attempted regardless
   *                                        of sent)──▶ UnknownPromo(unknown[isPromotion], promotionPending
   *                                        cleared at handoff; predecessor host may still
   *                                        be live until settle observes completion)
   * UnknownDirect ─settle claim──▶ Active(same id, no resend)
   *               ─settle fail/seal─▶ Idle (convert: UI rolled back, retryable)
   *               ─cancel/close──▶ Idle (cancel-drain / orphan drain)
   * UnknownPromo ─settle claim──▶ Active(Lock-2 commit: dequeue + append + launch, same id)
   *               ─settle fail/seal/cancel─▶ convert-like (FIFO retained, ERROR +
   *                                          reclaimable, no second ledger row)
   *               ─close──▶ drained per the drain-before-close contract
   * ```
   *
   * Legal overlaps are by design, not bugs:
   * - Active + pending may briefly co-exist: the host entry ([hostedTurn]
   *   prologue) clears `pendingAdmission` just after launch, so the launch
   *   path and the clearing path overlap transiently.
   * - UnknownDirect holds unknown + pending with the SAME id: the direct
   *   reservation stays in `pendingAdmission` while UNKNOWN ownership lives in
   *   `unknownAdmission` (settle claims or converts both).
   * - Active + promotionPending co-exist: the still-live predecessor host
   *   covers the promotion await; cancel/close reach the promotion by
   *   cancelling that host (except the promotion UNKNOWN window, where
   *   ownership has moved to `unknownAdmission` and [cancel] reuses
   *   [admissionCancelRequestedId] for the promotion id).
   *
   * Invariants (all currently KDoc-only; no runtime asserts — a mispredicted
   * hard throw in prod is riskier than the documentation value, and R6 just
   * converged):
   * 1. `unknownAdmission` holds at most one entry (single-flight admits at most
   *    one pending user row); `isPromotion` distinguishes direct from promotion.
   * 2. `promotionPending` and `unknownAdmission` never claim different ids at
   *    once: the UNKNOWN handoff moves the same reservation id from
   *    `promotionPending` to `unknownAdmission` ([enterUnknownAdmission]).
   * 3. Direct UNKNOWN keeps unknown + pending on the SAME id (see overlap above).
   * 4. Promotion UNKNOWN has `promotionPending == null` (cleared at the handoff);
   *    the predecessor host, if still live, is tracked via `unknown.host` /
   *    `inFlight`, never via `promotionPending`.
   * 5. After [close] seals, both `unknownAdmission` and `promotionPending` are
   *    null (single-monotone seal: close drains direct orphans and clears the
   *    promotion reservation explicitly as well as via the waiter path).
   *    Round 10 M1: the seal is monotone — [enterUnknownAdmission] never
   *    rebuilds UNKNOWN after [closed] (returns false; waiters take the
   *    fail-closed convert/rollback path instead).
   */
  private fun isBusyLocked(): Boolean =
    activeLocked() != null || pendingAdmission != null || unknownAdmission != null || promotionPending != null

  /**
   * Stage 6: durable user-row await shared by [startTurn]/[startOrEnqueue].
   * Bounded by [ACCEPT_DURABLE_TIMEOUT_MS] (suspend, never block — Room stays
   * on IO).
   *
   * R5-1 tri-state (send proof via
   * [OrderedTranscriptSink.onTurnStartedTracked]):
   * - ack in budget → [DurableAdmission.ACKED] (records [durableUserRows]);
   * - timeout with send proof false, fast delegate failure, or seal → throws
   *   [TranscriptDurabilityException] / returns [DurableAdmission.NOT_ADMITTED]
   *   (fail-closed, existing rollback semantics; proven never-attempted ids
   *   are visible-but-not-durable-failed per R11-2, never global poison);
   * - timeout with send proof true → [DurableAdmission.UNKNOWN] (no
   *   INTERRUPTED mark — the ghost may still late-commit; caller retains
   *   ownership via [unknownAdmission]).
   * R6-2 caller cancellation consults the same send proof ([sent] is
   * caller-owned so the outer catch can distinguish): external cancel with
   * proven never-attempted (`!sent && !sendAttempted`) keeps NOT_ADMITTED
   * semantics (R11-2 visible-but-not-durable-failed mark here, rollback +
   * rethrow CE outside, immediately retryable); external cancel with send
   * attempted does NOT mark here — the outer catch retains UNKNOWN
   * ownership (no rollback) and throws [TranscriptPendingException] like the
   * timeout-after-send path. Seal races keep the old fail-closed mapping
   * (INTERRUPTED-marked [TranscriptDurabilityException]).
   * R8-6: `sent == false` is untrusted — the sink sets [sendAttempted]
   * immediately before the (possibly suspending) channel send, so a CE racing
   * a suspended send leaves `sent == false` for an entry the writer may
   * already own. Only `!sent && !sendAttempted` is proven NOT_ADMITTED (the
   * send was never attempted); `!sent && sendAttempted` is fuzzy and maps to
   * UNKNOWN here and in the outer catches (no mark, no rollback — settle
   * claims or converts under the same id).
   *
   * Error symmetry (admission, S3-F1 with the terminal S3 trio): a fatal
   * Error from the delegate store (e.g. AssertionError/LinkageError) marks
   * INTERRUPTED like Exception and projects [TranscriptDurabilityException]
   * (retryable, never a bare Error to the caller); VM-fatal
   * (VirtualMachineError/ThreadDeath) always propagates unwrapped.
   */
  private suspend fun awaitUserRowDurable(
    logicalTurnId: String,
    text: String,
    sent: AtomicBoolean,
    sendAttempted: AtomicBoolean,
  ): DurableAdmission {
    try {
      val acked = withTimeoutOrNull(acceptTimeoutMs) {
        orderedTranscript.onTurnStartedTracked(logicalTurnId, text, sent, sendAttempted)
      } != null
      if (acked) {
        durableUserRows.add(logicalTurnId)
        return DurableAdmission.ACKED
      }
      // Bounded timeout: consult the send proof before claiming anything.
      // R8-6 tri-state: proven-not-admitted (send never attempted) stays
      // fail-closed; fuzzy (send attempted, ack still pending — the ghost may
      // late-commit) is UNKNOWN like the timeout-after-send path.
      // R11-2: a proven never-attempted id never entered the channel, so it
      // is not an admitted write failure — record it visible-but-not-
      // durable-failed (never poisons flushDurable for the later retry).
      if (!sent.get()) {
        if (!sendAttempted.get()) {
          try {
            orderedTranscript.markNeverAdmitted(logicalTurnId)
          } catch (_: Exception) {
          }
          return DurableAdmission.NOT_ADMITTED
        }
        return DurableAdmission.UNKNOWN
      }
      return DurableAdmission.UNKNOWN
    } catch (e: CancellationException) {
      if (orderedTranscript.isSealed()) {
        try {
          orderedTranscript.markInterrupted(logicalTurnId)
        } catch (_: Exception) {
        }
        throw TranscriptDurabilityException("transcript store unavailable (sealed)", e)
      }
      // R6-2: external cancel before the channel send never entered the ledger
      // (NOT_ADMITTED, mark like the timeout-before-send path). After the send
      // the independent writer still owns the entry, so never mark here — the
      // outer catch retains UNKNOWN ownership instead.
      // R8-6: the same split consults the attempt proof — a CE racing a
      // suspended send (sent false, attempt true) is fuzzy, never NOT_ADMITTED.
      // R11-2: proven never-attempted uses the non-poison mark (visible via
      // interruptedRunIds, never counted by hasDurableFailures/flushDurable).
      if (!sent.get() && !sendAttempted.get()) {
        try {
          orderedTranscript.markNeverAdmitted(logicalTurnId)
        } catch (_: Exception) {
        }
      }
      throw e
    } catch (e: TranscriptDurabilityException) {
      throw e
    } catch (e: Exception) {
      // L1: consult the send proof like the CE path. A post-send hook
      // failure (send attempted, ack still pending — fuzzy, the entry may
      // already be with the writer) maps to UNKNOWN, never NOT_ADMITTED:
      // return UNKNOWN so the caller retains ownership under the same id
      // (settle claims or converts). Only proven pre-send (send never
      // attempted) and post-ack delegate failures (sent == true) stay
      // fail-closed with Durability below.
      // R11-2: proven pre-send never entered the channel, so it uses the
      // non-poison mark (visible, never counted by hasDurableFailures);
      // only a sent (admitted) failure poisons durability.
      if (!sent.get() && sendAttempted.get()) {
        return DurableAdmission.UNKNOWN
      }
      try {
        if (!sent.get() && !sendAttempted.get()) {
          orderedTranscript.markNeverAdmitted(logicalTurnId)
        } else {
          orderedTranscript.markInterrupted(logicalTurnId)
        }
      } catch (_: Exception) {
      }
      throw TranscriptDurabilityException("transcript store failed", e)
    } catch (e: Throwable) {
      // Admission Error symmetry (with the S3 terminal trio): a fatal Error
      // from the delegate store (e.g. AssertionError/LinkageError) follows
      // the Exception leg above — fuzzy post-send maps to UNKNOWN (no mark,
      // caller retains ownership), proven pre-send uses the non-poison mark,
      // admitted failures mark INTERRUPTED and project
      // TranscriptDurabilityException (retryable); VM-fatal always propagates
      // unwrapped, and CancellationException never lands here (caught first).
      if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
      if (!sent.get() && sendAttempted.get()) {
        return DurableAdmission.UNKNOWN
      }
      try {
        if (!sent.get() && !sendAttempted.get()) {
          orderedTranscript.markNeverAdmitted(logicalTurnId)
        } else {
          orderedTranscript.markInterrupted(logicalTurnId)
        }
      } catch (_: Exception) {
      }
      throw TranscriptDurabilityException("transcript store failed", e)
    }
  }

  /** Caller must hold [lock]. True when [opId] duplicates pending UNKNOWN ownership. */
  private fun isUnknownDuplicateLocked(opId: Long?): Boolean {
    val u = unknownAdmission ?: return false
    // Same-opId resend is always a duplicate; a null opId (startTurn/legacy
    // steer) cannot prove distinctness, so it is blocked conservatively.
    if (opId == null || u.opId == null) return true
    return opId == u.opId
  }

  /**
   * Caller must hold [lock]. Throws [TranscriptPendingException] when [opId]
   * would duplicate UNKNOWN ownership or an already-visible same-opId user
   * row/queue entry while busy. Prevents the R5-1 double user row: the ghost
   * (if any) already owns that opId.
   */
  private fun checkPendingDuplicateLocked(opId: Long?) {
    if (isUnknownDuplicateLocked(opId)) {
      throw TranscriptPendingException("transcript store pending")
    }
    if (opId != null && isBusyLocked()) {
      if (_uiState.value.messages.any { it.role == "user" && it.operationId == opId }) {
        throw TranscriptPendingException("duplicate admission for opId $opId")
      }
      if (steerQueue.any { it.opId == opId }) {
        throw TranscriptPendingException("duplicate queued opId $opId")
      }
    }
  }

  /**
   * R5-1: retain UNKNOWN ownership after a timeout-after-send. Keeps
   * [pendingAdmission] (single-flight stays held), keeps the optimistic UI
   * row, projects `transcript store pending`, and launches one bounded
   * background settle (flush + ticket tracking, never blocks the UI).
   * R6-1: promotion callers pass `uiMessageId = null` (no optimistic row yet),
   * `isPromotion = true` and the predecessor [host]; the reservation moves
   * from [promotionPending] to [unknownAdmission] (same id, head opId kept)
   * and the FIFO head stays queued with `queuedRecoveryRequired = false`
   * (pending, not reclaimable) until settle claims or converts.
   * Round 10 M1: seal-guarded — when [closed] is already true the ownership
   * is NOT rebuilt (Invariant 5 single-monotone seal). Returns false so the
   * waiter takes the fail-closed path instead (direct: rollback +
   * [TranscriptDurabilityException]; promotion: clear [promotionPending] +
   * [failPromotion]). A close racing the install (set after this returns true)
   * is still cleared by [close] as before; only post-seal rebuild is blocked.
   * Caller must NOT hold [lock].
   *
   * @return true when UNKNOWN ownership was installed, false when sealed.
   */
  private fun enterUnknownAdmission(
    reservation: PendingAdmission,
    uiMessageId: String?,
    opId: Long?,
    isPromotion: Boolean = false,
    host: Job? = null,
  ): Boolean {
    synchronized(lock) {
      // Round 10 M1: never rebuild UNKNOWN after the seal. The background
      // settle's seal pre-check ([settlePendingAdmission]) already converts
      // promptly, so no new ownership is needed here.
      if (closed) return false
      // Single-flight guarantees at most one UNKNOWN; a racing settle that
      // already cleared must not be overwritten — but the waiter owns this
      // reservation, so overwrite is the same reservation.
      if (isPromotion && promotionPending?.logicalTurnId == reservation.logicalTurnId) {
        promotionPending = null
      }
      unknownAdmission = UnknownAdmission(reservation, uiMessageId, opId, isPromotion, host)
      activityRevision++
      _uiState.update { s ->
        s.copy(
          status = ChatStatus.ERROR,
          error = "transcript store pending",
          pendingSteerCount = steerQueue.size,
          // UNKNOWN means fate unknown: never project recovery (which means
          // "definitely not written, safe to resend"). Recovery is only for
          // confirmed NOT_ADMITTED/unavailable.
          queuedRecoveryRequired = false,
        )
      }
    }
    val job = writerScope.launch {
      try {
        settlePendingAdmission(acceptTimeoutMs)
      } catch (_: CancellationException) {
      } catch (_: Exception) {
      }
    }
    synchronized(lock) {
      unknownSettleJob = job
    }
    job.invokeOnCompletion {
      synchronized(lock) {
        if (unknownSettleJob === job) unknownSettleJob = null
      }
    }
    return true
  }

  /**
   * R5-1 bounded settle for UNKNOWN ownership (ticket-tracked flush, never
   * unbounded). Flush barrier is ordered behind the ghost send via the sink's
   * admission chain, so `flushed && !interrupted` proves the ghost durable —
   * with one R8-6 exception below.
   *
   * R8-6 pending-guard: a fuzzy CE racing a suspended send (attempted, entry
   * never entered the channel) also drains the barrier successfully, so
   * `flushed` alone would mis-claim (marking [durableUserRows] for a row the
   * writer never owns → ledger misses the row, sink pending dangles). After a
   * successful flush, a still-pending id (`pendingRunIds()` contains it) proves
   * non-delivery — an admitted ghost's pending is always removed by the writer
   * `finally` before the barrier (FIFO order) drains — R9-1 resolves it in the
   * open session via tryResolveUndelivered (true INTERRUPTED, convert, same-opId
   * retryable); only when the resolver loses (seal won) does settle return
   * false and retain UNKNOWN. Flush timeout already returns false, so it stays
   * safe.
   *
   * - ghost durable (flushed + pending gone) → claim under the SAME logical id (adds [durableUserRows]
   *   before the host starts so [runTurnLoop] skips a second user row;補 UI
   *   idempotently; launches the host; no resend).
   * - INTERRUPTED/sealed → convert to fail-closed (UI rolled back, ownership
   *   released, same-opId retry becomes a fresh [TranscriptDurabilityException]
   *   path).
   * - flush timeout → still unknown: keep ownership, no false INTERRUPTED
   *   mark; a later explicit retry re-settles.
   *
   * @return true when resolved (claimed or failed), false when still unknown.
   * M2: caller cancellation is never swallowed — a caller-cancelled settle
   * rethrows CancellationException directly (never TranscriptPendingException);
   * false only means the bounded flush timed out with ownership retained.
   */
  suspend fun settlePendingAdmission(timeoutMs: Long = acceptTimeoutMs): Boolean {
    val snapshot: UnknownAdmission = synchronized(lock) { unknownAdmission } ?: return true
    val logicalTurnId = snapshot.reservation.logicalTurnId
    if (orderedTranscript.interruptedRunIds().contains(logicalTurnId) || orderedTranscript.isSealed()) {
      synchronized(lock) {
        val cur = unknownAdmission ?: return true
        if (cur.reservation.logicalTurnId != logicalTurnId) return true
        if (cur.isPromotion) {
          convertPromotionUnknownToFailedLocked(cur, "transcript store unavailable")
        } else {
          convertUnknownToFailedLocked(cur, "transcript store unavailable")
        }
      }
      return true
    }
    val flushed: Boolean = try {
      orderedTranscript.flush(timeoutMs)
    } catch (e: CancellationException) {
      // M2: a caller-cancelled settle must propagate — swallowing it as
      // "still unknown" hides the caller's own cancellation from the
      // startTurn/startOrEnqueue pre-settle path (which would misreport
      // Pending). Only the flush timeout (null → false, no throw) stays
      // unknown. A background settle cancelled by close() only fires after
      // ownership was cleared, so rethrow there is benign.
      if (coroutineContext[Job]?.isCancelled == true) throw e
      false
    } catch (_: Exception) {
      false
    }
    synchronized(lock) {
      val cur = unknownAdmission ?: return true
      if (cur.reservation.logicalTurnId != logicalTurnId) return true
      if (orderedTranscript.interruptedRunIds().contains(logicalTurnId) || orderedTranscript.isSealed()) {
        if (cur.isPromotion) {
          convertPromotionUnknownToFailedLocked(cur, "transcript store unavailable")
        } else {
          convertUnknownToFailedLocked(cur, "transcript store unavailable")
        }
        return true
      }
      if (!flushed) return false
      // R9-1 non-delivery proof (before cancel/claim): flush success with the id
      // still pending proves the fuzzy entry never entered the channel (an
      // admitted ghost's pending is removed before the barrier drains). Resolve
      // in the open session: tryResolveUndelivered releases the dangling ack
      // (never late-commits) with a true INTERRUPTED mark, then convert —
      // direct rolls back the UI row (same opId retryable with a fresh id),
      // promotion retains the FIFO for explicit recovery. No durableUserRows,
      // no launch, no second row. Resolver loss (seal won) stays UNKNOWN.
      if (orderedTranscript.pendingRunIds().contains(logicalTurnId)) {
        if (orderedTranscript.tryResolveUndelivered(logicalTurnId)) {
          if (cur.isPromotion) {
            convertPromotionUnknownToFailedLocked(cur, "transcript store unavailable")
          } else {
            convertUnknownToFailedLocked(cur, "transcript store unavailable")
          }
          return true
        }
        return false
      }
      // Admission-window cancel raced the late commit: direct admissions drain
      // a cancel terminal instead of ghost-launching (Finding 1, UNKNOWN
      // variant). Promotion UNKNOWN takes the convert-like branch below
      // (FIFO retained, no second ledger row, no launch) — see
      // [claimPromotionUnknownLocked].
      if (admissionCancelRequestedId == logicalTurnId) {
        if (cur.isPromotion) {
          // R6-1 promotion-UNKNOWN cancel wins: the ghost already proved
          // durable via the flush above, but the user pressed cancel, so never
          // launch and never write another ledger row here (no dequeue, no
          // append, no drainOrphan cancel-terminal — unlike the direct branch
          // there is no optimistic UI row to keep and no pendingAdmission to
          // drain). Retain the never-dequeued FIFO for explicit recovery
          // (ERROR + queuedRecoveryRequired, reclaimable via drainQueued like
          // the NOT_ADMITTED path); the acked ghost survives as an orphan
          // (no cancel-terminal; reopen marks it INTERRUPTED).
          admissionCancelRequestedId = null
          unknownAdmission = null
          if (promotionPending?.logicalTurnId == logicalTurnId) promotionPending = null
          val host = cur.host
          if (host != null && inFlight === host) inFlight = null
          durableUserRows.add(logicalTurnId)
          activityRevision++
          _uiState.update { s ->
            s.copy(
              status = ChatStatus.ERROR,
              error = "turn admission cancelled",
              pendingSteerCount = steerQueue.size,
              queuedRecoveryRequired = steerQueue.isNotEmpty(),
            )
          }
          return true
        }
        admissionCancelRequestedId = null
        unknownAdmission = null
        durableUserRows.add(logicalTurnId)
        val pending =
          pendingAdmission?.takeIf { it.logicalTurnId == logicalTurnId } ?: cur.reservation
        drainOrphanLocked(pending)
        activityRevision++
        _uiState.update { s ->
          s.copy(
            status = ChatStatus.CANCELLED,
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        return true
      }
      if (cur.isPromotion) {
        claimPromotionUnknownLocked(cur)
      } else {
        claimUnknownLocked(cur)
      }
      return true
    }
  }

  /**
   * R5-1: does an UNKNOWN ownership exist? (Test/VM seam.)
   *
   * Naming note: despite the name, this checks ONLY [unknownAdmission] — not
   * [pendingAdmission] (direct durable-await window) nor [promotionPending]
   * (promotion await window). Kept as-is to avoid churning callers; internal
   * lock-held callers use [isBusyLocked] (private) when the question is really
   * "is any admission window or host holding single-flight?". External callers
   * cannot reach [isBusyLocked]; use this public seam (via [ChatSessionImpl]
   * to the ViewModel).
   */
  fun hasPendingAdmission(): Boolean = synchronized(lock) { unknownAdmission != null }

  /**
   * R22 Phase 1 promotion settle proof: true while a promotion reservation
   * still holds the single-flight slot ([promotionPending] set: Lock-1 await,
   * no UNKNOWN yet) or a promotion ghost still owns it ([unknownAdmission]
   * with `isPromotion == true`). Named subset of [hasUnsettledAdmission]
   * (which covers the same windows plus direct ones) so promotion barriers
   * assert — and [flushDurable] conjoins — the window explicitly. Lock-held
   * read, lock-free, never suspends.
   */
  fun hasUnsettledPromotion(): Boolean = synchronized(lock) {
    promotionPending != null || unknownAdmission?.isPromotion == true
  }

  /**
   * R22 Phase 1 live-host proof: true while a hosted turn job is still
   * running ([inFlight] live via [activeLocked]). A live host owns a user row
   * with no proven terminal yet, so [flushDurable] conjoins this separately
   * from [hasUnsettledAdmission] (which clears at the [hostedTurn] prologue
   * and stays false while the host runs) — otherwise a streaming turn whose
   * user row already drained would report true-and-dangling. Lock-held read
   * (via the lazy-clearing [activeLocked], like every other `*Locked`
   * accessor), never suspends.
   */
  fun hasActiveHost(): Boolean = synchronized(lock) { activeLocked() != null }

  /**
   * R22 Phase 1 sticky close proof: true while [close] dropped a terminal
   * obligation this session can no longer prove final.
   *
   * - Permanent tier ([closedWithUnknownGhost]): close observed an UNKNOWN
   *   ghost or a promotion reservation — unprovable fates (orphan user row
   *   with no cancel-terminal, Lock-2 closed-win writes nothing). Never lifts
   *   on this session; only reopen INTERRUPTED backfill repairs the ledger.
   * - Liftable tier ([closedWithUnsettledAdmission]): close observed a
   *   controller-observed family (direct pending, live host, orphan drain).
   *   Lifts only once the background settle+drain+seal completed
   *   ([shutdownJob] done) with no remaining obligation evidence (no
   *   pending/UNKNOWN/promotion/active/orphan-drain, no durable failures, no
   *   unsettled or SEALED-uncompensated receipts). Until then fail-closed.
   *
   * Conjoined into [flushDurable]. The ViewModel observes this session's debt
   * indirectly today (a `false` from [flushDurable] degrades the fork to
   * skip-dangling + incomplete marker); direct `isLedgerStreamingOrPending`
   * wiring for this seam is follow-up work, not claimed here. Lock-held read,
   * never suspends.
   */
  fun hasClosedUnsettledAdmission(): Boolean = synchronized(lock) {
    if (closedWithUnknownGhost) return@synchronized true
    if (!closedWithUnsettledAdmission) return@synchronized false
    val shutdownDone = shutdownJob?.isCompleted == true
    val orphanActive = orphanDrainJob?.isCompleted == false
    val clean =
      shutdownDone && pendingAdmission == null && unknownAdmission == null &&
        promotionPending == null && activeLocked() == null && !orphanActive &&
        !orderedTranscript.hasDurableFailures() && !hasUnsettledCancelReceipts() &&
        !hasSealedUncompensatedReceipts()
    !clean
  }

  /**
   * R21-1 Phase 1 admission settle proof, R22 Phase 1 broadened from
   * direct-only to every terminal-obligation window (the old "direct-only"
   * claim is superseded): true while a direct admission still owns the
   * single-flight slot ([pendingAdmission] set: durable await or finalize
   * barrier, host not yet entered), while a promotion reservation holds it
   * ([promotionPending] set: Lock-1 await — see [hasUnsettledPromotion]),
   * while any UNKNOWN ownership exists ([unknownAdmission] set: direct ghost
   * or promotion ghost — see [hasPendingAdmission], which stays UNKNOWN-only),
   * or while an orphan cancel drain is still in flight ([orphanDrainJob] not
   * completed). Cleared on host entry ([hostedTurn] prologue), rollback,
   * claim/convert, cancel-drain, drain completion, or the Lock-2 promotion
   * commit — so a running host alone never trips this (no false positive;
   * that window is owned by [hasActiveHost] instead), while a user row
   * without its terminal (admission barrier, promotion await/UNKNOWN, ghost,
   * close/cancel interleave) keeps it true. Lock-held read, lock-free, never
   * suspends. Conjoined into [flushDurable] and consulted by the ViewModel's
   * `isLedgerStreamingOrPending` alongside [hasPendingAdmission] (which stays
   * UNKNOWN-only), [hasUnsettledCancelReceipts], and
   * [hasSealedUncompensatedReceipts] — so the promotion windows newly covered
   * here already reach the VM through this proof. The sibling proofs
   * [hasUnsettledPromotion], [hasActiveHost], and [hasClosedUnsettledAdmission]
   * are [flushDurable]-conjoined and — since Stage-3 F-E — also wired
   * directly into the VM's `isLedgerStreamingOrPending` (admission /
   * promotion / host fail closed on read failure; sticky close debt stays
   * best-effort skip, fail-closed via the flush conjunction instead).
   */
  internal fun hasUnsettledAdmission(): Boolean = synchronized(lock) {
    pendingAdmission != null || promotionPending != null || unknownAdmission != null ||
      (orphanDrainJob?.isCompleted == false)
  }

  /** Caller must hold [lock]. Claim the ghost: same id,補 UI, launch host, no resend. */
  private fun claimUnknownLocked(unknown: UnknownAdmission) {
    unknownAdmission = null
    durableUserRows.add(unknown.reservation.logicalTurnId)
    activityRevision++
    val hasRow =
      _uiState.value.messages.any { it.id == unknown.uiMessageId && it.role == "user" }
    if (!hasRow) {
      appendUser(unknown.reservation.text, unknown.opId)
    }
    _uiState.update { s ->
      s.copy(status = ChatStatus.STREAMING, error = null, queuedRecoveryRequired = false)
    }
    val r = unknown.reservation
    val job = scope.launch {
      hostedTurn(r.text, r.images, r.logicalTurnId, r.attemptRef, r.attemptIndexRef)
    }
    inFlight = job
    val lid = r.logicalTurnId
    job.invokeOnCompletion {
      synchronized(lock) {
        if (inFlight === job) inFlight = null
        val pending = pendingAdmission
        if (pending?.logicalTurnId == lid) {
          drainOrphanLocked(pending)
        }
      }
    }
  }

  /** Caller must hold [lock]. Ghost confirmed failed/sealed: roll back, release, retryable. */
  private fun convertUnknownToFailedLocked(unknown: UnknownAdmission, error: String) {
    unknownAdmission = null
    if (pendingAdmission?.logicalTurnId == unknown.reservation.logicalTurnId) {
      pendingAdmission = null
    }
    if (admissionCancelRequestedId == unknown.reservation.logicalTurnId) {
      admissionCancelRequestedId = null
    }
    activityRevision++
    _uiState.update { s ->
      s.copy(
        messages = s.messages.filterNot { it.id == unknown.uiMessageId && it.role == "user" },
        status = ChatStatus.ERROR,
        error = error,
      )
    }
  }

  /**
   * R6-1: promotion UNKNOWN claim — the Lock-2 commit for a late-committed
   * ghost. Dequeues the still-queued head only when it still matches
   * (opId + text, like Lock 2), appends the user row, records durability
   * BEFORE launch (so [runTurnLoop] skips a second row), projects STREAMING
   * and launches the follow-up host with the SAME logical id (no resend).
   * The predecessor [Job] already completed; [inFlight] is atomically replaced
   * self → follow. Caller must hold [lock].
   *
   * Cancel/close mirror Lock-2: a CANCELLED projection or a matching
   * [admissionCancelRequestedId] (see [cancel] promotion-UNKNOWN branch) aborts
   * the launch with the convert-like transition (FIFO retained, ERROR +
   * reclaimable, no ledger write); [closed] drops the FIFO per the
   * drain-before-close contract (acked ghost survives as an orphan, no
   * cancel-terminal — reopen marks it INTERRUPTED). The steal/close paths add
   * [durableUserRows] for the orphan so a later retry with a fresh id never
   * rewrites it.
   */
  private fun claimPromotionUnknownLocked(unknown: UnknownAdmission) {
    unknownAdmission = null
    if (promotionPending?.logicalTurnId == unknown.reservation.logicalTurnId) {
      promotionPending = null
    }
    // R6-1 cancel wins after the ack while settle was parked (mirrors the
    // Lock-2 `status == CANCELLED` guard; the flag covers the promotion
    // UNKNOWN window where [cancel] ran with no active host). Never launch and
    // never write another ledger row here (no dequeue, no append, no
    // drainOrphan): retain the FIFO for explicit recovery like
    // [convertPromotionUnknownToFailedLocked]. The acked ghost survives as an
    // orphan (no cancel-terminal; reopen marks it INTERRUPTED).
    if (
      admissionCancelRequestedId == unknown.reservation.logicalTurnId ||
      _uiState.value.status == ChatStatus.CANCELLED
    ) {
      if (admissionCancelRequestedId == unknown.reservation.logicalTurnId) {
        admissionCancelRequestedId = null
      }
      val host = unknown.host
      if (host != null && inFlight === host) inFlight = null
      durableUserRows.add(unknown.reservation.logicalTurnId)
      activityRevision++
      _uiState.update { s ->
        s.copy(
          status = ChatStatus.ERROR,
          error = "turn admission cancelled",
          pendingSteerCount = steerQueue.size,
          queuedRecoveryRequired = steerQueue.isNotEmpty(),
        )
      }
      return
    }
    // Close won after the ack while settle was parked: never launch and never
    // write another row here (close already dropped the FIFO per the
    // drain-before-close contract; the acked ghost survives as an orphan, no
    // cancel-terminal — reopen marks it INTERRUPTED). Note: via settle this
    // branch is almost unreachable (close cancels the settle job and clears
    // ownership first); it only fires when the flush already proved the ghost
    // durable in the same locked section after close slipped in.
    if (closed) {
      val host = unknown.host
      if (host != null && inFlight === host) inFlight = null
      // L2: record durability like the steal path above so doc/code agree —
      // the acked ghost survives as an orphan (reopen marks it INTERRUPTED)
      // and a later retry with a fresh id never rewrites it.
      durableUserRows.add(unknown.reservation.logicalTurnId)
      return
    }
    val currentHead = steerQueue.firstOrNull()
    if (currentHead == null || currentHead.opId != unknown.opId || currentHead.text != unknown.reservation.text) {
      // Head stolen mid-settle (drain is blocked while UNKNOWN, so this is
      // only the close-cleared or a racing-drain edge): mirror the Lock-2
      // drain-steal transition. The acked ghost stays as-is (orphan).
      val host = unknown.host
      if (host != null && inFlight === host) inFlight = null
      durableUserRows.add(unknown.reservation.logicalTurnId)
      _uiState.update { s ->
        if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED || s.status == ChatStatus.ERROR) {
          s.copy(status = ChatStatus.IDLE, error = null)
        } else {
          s
        }
      }
      return
    }
    steerQueue.removeFirstOrNull()
    activityRevision++
    durableUserRows.add(unknown.reservation.logicalTurnId)
    appendUser(currentHead.text, currentHead.opId)
    _uiState.update {
      it.copy(
        status = ChatStatus.STREAMING,
        error = null,
        pendingSteerCount = steerQueue.size,
        queuedRecoveryRequired = false,
      )
    }
    pendingAdmission = PendingAdmission(
      unknown.reservation.logicalTurnId, currentHead.text, currentHead.images,
      unknown.reservation.attemptRef, unknown.reservation.attemptIndexRef,
    )
    val follow = scope.launch {
      hostedTurn(
        currentHead.text, currentHead.images,
        unknown.reservation.logicalTurnId, unknown.reservation.attemptRef, unknown.reservation.attemptIndexRef,
      )
    }
    inFlight = follow
    val followLogicalId = unknown.reservation.logicalTurnId
    follow.invokeOnCompletion {
      synchronized(lock) {
        if (inFlight === follow) inFlight = null
        val pending = pendingAdmission
        if (pending?.logicalTurnId == followLogicalId) {
          drainOrphanLocked(pending)
        }
      }
    }
  }

  /**
   * R6-1: promotion UNKNOWN convert — the ghost is confirmed failed/sealed.
   * Retains the never-dequeued FIFO for explicit recovery (ERROR +
   * `transcript store unavailable` + queuedRecoveryRequired, reclaimable via
   * [drainQueued] like the NOT_ADMITTED path), releases ownership so the same
   * opId becomes retryable. Caller must hold [lock].
   */
  private fun convertPromotionUnknownToFailedLocked(unknown: UnknownAdmission, error: String) {
    unknownAdmission = null
    if (promotionPending?.logicalTurnId == unknown.reservation.logicalTurnId) {
      promotionPending = null
    }
    // R6-1: a promotion-UNKNOWN cancel flag (see [cancel]) must not leak when
    // the ghost is confirmed failed/sealed — seal wins, flag cleared like the
    // direct convert path.
    if (admissionCancelRequestedId == unknown.reservation.logicalTurnId) {
      admissionCancelRequestedId = null
    }
    val host = unknown.host
    if (host != null && inFlight === host) inFlight = null
    activityRevision++
    _uiState.update {
      it.copy(
        status = ChatStatus.ERROR,
        error = error,
        pendingSteerCount = steerQueue.size,
        queuedRecoveryRequired = steerQueue.isNotEmpty(),
      )
    }
  }

  /**
   * Stage 6: rollback for a durably-failed admission. Clears a still-owned
   * [pendingAdmission] (and any matching [admissionCancelRequestedId]), removes
   * the optimistically appended UI user row, and projects ERROR (fail-closed)
   * without touching the FIFO. Caller must NOT hold [lock].
   */
  private fun rollbackFailedAdmission(
    uiMessageId: String,
    reservation: PendingAdmission,
    error: String,
  ) {
    synchronized(lock) {
      if (pendingAdmission?.logicalTurnId == reservation.logicalTurnId) {
        pendingAdmission = null
      }
      if (admissionCancelRequestedId == reservation.logicalTurnId) {
        admissionCancelRequestedId = null
      }
      activityRevision++
      _uiState.update { s ->
        s.copy(
          messages = s.messages.filterNot { it.id == uiMessageId && it.role == "user" },
          status = ChatStatus.ERROR,
          error = error,
        )
      }
    }
  }

  /**
   * Locked rollback for a finalize that observed [closed] after its durable
   * ack already succeeded (Finding 2). The ledger already holds the user row,
   * so this first drains a cancel terminal (user row skipped as durable via
   * [drainOrphanLocked]) and then rolls back the optimistic UI row + projects
   * ERROR, so the caller retains the text for explicit retry (same restore
   * semantics as [TranscriptDurabilityException], surfaced to the ViewModel
   * as such instead of a bare IllegalStateException that strands pendingOps).
   * Clears any matching [admissionCancelRequestedId]. Caller must hold [lock].
   */
  private fun rollbackClosedAdmissionLocked(uiMessageId: String, reservation: PendingAdmission): Job? {
    if (admissionCancelRequestedId == reservation.logicalTurnId) {
      admissionCancelRequestedId = null
    }
    val pending = pendingAdmission?.takeIf { it.logicalTurnId == reservation.logicalTurnId }
    val drain: Job? = if (pending != null) {
      drainOrphanLocked(pending)
    } else if (!durableUserRows.contains(reservation.logicalTurnId)) {
      // Pending already cleared but the user row never became durable (e.g. a
      // racing rollback won): no ledger row to cancel, nothing to drain.
      null
    } else {
      // Pending cleared by another path but the row is durable: still ensure
      // the cancel terminal via the reservation itself.
      drainOrphanLocked(reservation)
    }
    activityRevision++
    _uiState.update { s ->
      s.copy(
        messages = s.messages.filterNot { it.id == uiMessageId && it.role == "user" },
        status = ChatStatus.ERROR,
        error = "transcript store unavailable",
      )
    }
    return drain
  }

  /**
   * Sends one turn and returns after the hosted turn finishes.
   *
   * Stage 6: returning already implied terminal durability (core writes are
   * acked inline); since `Started` itself is now durable (user row acked
   * before [startTurn] returns), the old asymmetry is gone — `send()` adds
   * the terminal-row guarantee on top of the same durable user row, it no
   * longer repairs a memory-only acceptance.
   */
  suspend fun send(text: String, images: List<ChatImageRef> = emptyList()) {
    val host = startTurn(text, images)
    try {
      host.join()
    } catch (e: CancellationException) {
      // Our own turn was cancelled via cancel()/close(): state already flipped.
      // Only propagate if the *caller* itself was cancelled.
      if (coroutineContext[Job]?.isCancelled == true) throw e
    }
  }

  /**
   * Start a turn and return once the text is accepted (fresh `chat.send`
   * policy passed, user message appended, STREAMING) without waiting for the
   * hosted turn to finish. Callers that must retain an unsent draft across
   * endpoint invalidation (R1) clear it only after this returns; a
   * [CancellationException]/[SecurityException]/[IllegalStateException]/
   * [TranscriptDurabilityException] before return means the text was NOT
   * accepted and must stay recoverable. A [TranscriptPendingException] means
   * UNKNOWN/PENDING_COMMIT (R5-1 timeout-after-send, R6-2 external cancel
   * after-send): ownership retained, same-opId resend blocked until bounded
   * settle; the caller must NOT treat it as not-accepted and must NOT
   * immediately resend. R6-2: an external (caller-scope) cancel with send
   * proof false stays NOT_ADMITTED (CE + rollback + INTERRUPTED, immediately
   * retryable); with send proof true it becomes UNKNOWN/PENDING_COMMIT
   * (Pending, no rollback, no INTERRUPTED — settle claims the ghost under the
   * same id). A [RecoveryRequiredException] means
   * queued recovery work must be drained before this direct admission can
   * proceed; this method never drains it. If recovery appears after the
   * initial admission lock, this attempt may already have passed a fresh
   * policy gate; retrying evaluates policy again.
   *
   * Stage 6 (R4-3) durability: `Started` (the returned [Job]) is durable —
   * the user row is acked by the session writer within
   * [ACCEPT_DURABLE_TIMEOUT_MS] BEFORE this returns, so a process kill
   * immediately after return never loses the text (no `close`/`flush`
   * needed). A stalled store throws [TranscriptDurabilityException]
   * fail-closed (UI append rolled back, logical id INTERRUPTED-marked,
   * caller retains the text like [InputTooLongException]). This eliminates
   * the old `send()` (join host, terminal acked) vs `Started` (memory-only)
   * asymmetry: both now imply a durable user row; `send()` additionally
   * implies the terminal row.
   *
   * Admission-window cancel (Finding 1): a [cancel] that lands while the
   * durable await is still parked does NOT ghost-launch. The waiter observes
   * the cancel flag in finalize, drains a cancel terminal (user row already
   * durable, skipped there), keeps the UI user row with status CANCELLED, and
   * returns an already-cancelled host (accepted-then-cancelled, never a second
   * provider call). Finalize that observes [closed] after a successful ack is
   * instead treated as a durability failure (Finding 2): ledger keeps the user
   * row + cancel terminal, the UI row is rolled back with ERROR, and a
   * [TranscriptDurabilityException] is thrown so the caller retains the text
   * for explicit retry (never a bare IllegalStateException that strands
   * pendingOps).
   */
  suspend fun startTurn(text: String, images: List<ChatImageRef> = emptyList()): Job {
    require(text.isNotBlank()) { "text must not be blank" }
    checkInputLengthOrThrow(text)
    // R5-1: a still-unsettled UNKNOWN owns the single-flight slot. Settle
    // boundedly (outside the lock) before deciding, so a late-committed ghost
    // is claimed instead of duplicated. M2: a caller-cancelled settle
    // rethrows CE (never Pending) so the VM does not misread it as UNKNOWN.
    if (synchronized(lock) { unknownAdmission != null }) {
      try {
        settlePendingAdmission(acceptTimeoutMs)
      } catch (e: CancellationException) {
        if (coroutineContext[Job]?.isCancelled == true) throw e
        throw TranscriptPendingException("transcript store pending")
      } catch (_: Exception) {
      }
    }
    val revisionAtAdmission = synchronized(lock) {
      check(!closed) { "controller is closed" }
      checkPendingDuplicateLocked(null)
      if (isBusyLocked()) {
        _uiState.update { it.copy(error = "busy: another turn is in flight") }
        throw IllegalStateException("already in flight")
      }
      checkNoQueuedRecoveryLocked()
      activityRevision
    }
    beforeAdmissionGateForTest?.invoke()
    gateOrThrow(revisionAtAdmission)
    afterStartTurnGateForTest?.invoke()
    // Second lock: reserve (bump + optimistic UI append + pending) but do NOT
    // launch yet. The durable await below runs outside the lock (suspend must
    // never hold the intrinsic monitor); [pendingAdmission] guards the window
    // so concurrent admissions queue/throw instead of starting a second turn.
    // STREAMING is projected only after the ack (Stage 6).
    val reservation: PendingAdmission
    val uiMessageId: String
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      // R4-4 second-lock re-validation: fresh zone read after the gate, before
      // any bump/append/transcript. Length first (before busy/recovery) for
      // fail-fast priority matching admission; throw is fail-closed (no append,
      // no bump, no transcript) with InputTooLongException to the caller.
      // Closed stays first (lifecycle seal); length still precedes busy/recovery.
      checkInputLengthOrThrow(text)
      checkPendingDuplicateLocked(null)
      if (isBusyLocked()) {
        _uiState.update { it.copy(error = "busy: another turn is in flight") }
        throw IllegalStateException("already in flight")
      }
      checkNoQueuedRecoveryLocked()
      activityRevision++
      uiMessageId = appendUser(text)
      // Stage B: the logical turn id exists before the first suspension
      // (onTurnStarted ack), so a cancel parked on that ack still attributes
      // to this turn instead of falling back to the previous assistant.
      // Stage C: the attempt index ref travels with the attempt id ref so a
      // cancel in any retry gap attributes to the live attempt's family.
      val logicalTurnId = newId()
      val attemptRef = AtomicReference(logicalTurnId)
      val attemptIndexRef = AtomicInteger(0)
      reservation = PendingAdmission(logicalTurnId, text, images, attemptRef, attemptIndexRef)
      pendingAdmission = reservation
    }
    // Stage 6 durable gate (outside the lock): Started == durable user row.
    // R5-1 tri-state: UNKNOWN retains ownership and throws Pending (no
    // rollback, no INTERRUPTED mark); NOT_ADMITTED keeps fail-closed rollback.
    // R6-2: the send proof is caller-owned so the CE catch below can consult
    // it. Installs UNKNOWN via writerScope (see enterUnknownAdmission); never
    // clears admissionCancelRequestedId first (accepted-then-cancelled race).
    // R8-6: the attempt proof travels alongside `sent` — `sent == false` alone
    // no longer proves non-delivery.
    val sent = AtomicBoolean(false)
    val sendAttempted = AtomicBoolean(false)
    try {
      when (awaitUserRowDurable(reservation.logicalTurnId, text, sent, sendAttempted)) {
        DurableAdmission.ACKED -> Unit
        DurableAdmission.NOT_ADMITTED -> {
          rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
          throw TranscriptDurabilityException(
            "transcript store timed out after $acceptTimeoutMs ms",
          )
        }
        DurableAdmission.UNKNOWN -> {
          // Round 10 M1: sealed after the await — never rebuild UNKNOWN.
          if (!enterUnknownAdmission(reservation, uiMessageId, null)) {
            rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
            throw TranscriptDurabilityException("controller is closed")
          }
          throw TranscriptPendingException("transcript store pending")
        }
      }
    } catch (e: CancellationException) {
      // R6-2 external cancel: sent == false (never entered the channel) stays
      // NOT_ADMITTED (rollback + rethrow CE, retryable); sent == true (writer
      // still owns the entry) becomes UNKNOWN (no mark, no rollback — settle
      // claims the ghost under the same id). Sealed races already surfaced as
      // TranscriptDurabilityException inside the await, never here.
      // R8-6: proven-not-admitted is `!sent && !sendAttempted`; a CE racing a
      // suspended send (fuzzy) retains UNKNOWN ownership like after-send.
      if (!sent.get() && !sendAttempted.get()) {
        rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
        throw e
      }
      // Round 10 M1: sealed after the await — fail closed instead of
      // resurrecting UNKNOWN (Invariant 5).
      if (!enterUnknownAdmission(reservation, uiMessageId, null)) {
        rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
        throw TranscriptDurabilityException("controller is closed", e)
      }
      throw TranscriptPendingException("transcript store pending", e)
    } catch (e: TranscriptPendingException) {
      throw e
    } catch (e: TranscriptDurabilityException) {
      rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
      throw e
    }
    // R21-1 Phase 1 finalize barrier (outside the lock): the user row is
    // already durable here but no terminal exists yet. Test-only pause point.
    beforeAdmissionFinalizeForTest?.invoke()
    // Finalize under lock: project STREAMING + launch. Closed after the ack
    // is treated as a durability failure (Finding 2: drain cancel + roll back
    // UI + throw TranscriptDurabilityException for retry, never a bare
    // IllegalStateException). A cancel parked in the durable window (Finding 1)
    // abandons the launch and returns an already-cancelled host instead of
    // ghost-launching.
    val host: Job = synchronized(lock) {
      if (closed) {
        rollbackClosedAdmissionLocked(uiMessageId, reservation)
        throw TranscriptDurabilityException("controller is closed")
      }
      if (admissionCancelRequestedId == reservation.logicalTurnId) {
        admissionCancelRequestedId = null
        val pending =
          pendingAdmission?.takeIf { it.logicalTurnId == reservation.logicalTurnId } ?: reservation
        drainOrphanLocked(pending)
        _uiState.update { s ->
          s.copy(
            status = ChatStatus.CANCELLED,
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        val cancelled = Job()
        cancelled.cancel()
        return@synchronized cancelled
      }
      _uiState.update {
        it.copy(status = ChatStatus.STREAMING, error = null, queuedRecoveryRequired = false)
      }
      val job = scope.launch {
        hostedTurn(text, images, reservation.logicalTurnId, reservation.attemptRef, reservation.attemptIndexRef)
      }
      inFlight = job
      val logicalTurnId = reservation.logicalTurnId
      job.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === job) inFlight = null
          val pending = pendingAdmission
          if (pending?.logicalTurnId == logicalTurnId) {
            drainOrphanLocked(pending)
          }
        }
      }
      job
    }
    return host
  }

  /**
   * Cooperative cancel. Cancelling a live host flips to CANCELLED first so the
   * UI stops even if the transport close blocks briefly, then cancels the
   * in-flight job. A cancel that lands in the admission-time durable window
   * ([pendingAdmission] set, no host yet) is NOT a no-op: it records
   * [admissionCancelRequestedId] and projects CANCELLED, so the waiter
   * abandons its launch and drains a cancel terminal instead of
   * ghost-launching (accepted-then-cancelled: ledger keeps the durable user
   * row + cancel mark, UI keeps the row, caller gets an already-cancelled
   * host). R6-1: a cancel in the promotion UNKNOWN window (predecessor
   * already completed, no active host, [pendingAdmission] null, promotion
   * [unknownAdmission] pending) likewise records [admissionCancelRequestedId]
   * for the promotion reservation id and projects CANCELLED; the background
   * settle observes it and aborts the follow-up launch (convert-like: FIFO
   * retained, ERROR + reclaimable, no second ledger row, no provider call).
   * No join, no IO.
   */
  fun cancel() {
    synchronized(lock) {
      val current = activeLocked()
      if (current != null) {
        activityRevision++
        _uiState.update {
          it.copy(
            status = ChatStatus.CANCELLED,
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        // Cooperative: cancelling collection cancels the underlying HTTP call.
        current.cancel()
        return
      }
      val pending = pendingAdmission
      if (pending != null) {
        if (admissionCancelRequestedId == pending.logicalTurnId) return
        admissionCancelRequestedId = pending.logicalTurnId
        activityRevision++
        _uiState.update {
          it.copy(
            status = ChatStatus.CANCELLED,
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        return
      }
      // R6-1 promotion UNKNOWN window: no active host and no direct pending,
      // but a promotion ghost still owns the slot. Without this flag the
      // later settle would claim and launch a follow-up provider call despite
      // the user pressing cancel (Lock-2 checks CANCELLED, direct UNKNOWN
      // settle checks the flag — claimPromotion must see the same signal).
      val unknown = unknownAdmission
      if (unknown != null && unknown.isPromotion) {
        if (admissionCancelRequestedId == unknown.reservation.logicalTurnId) return
        admissionCancelRequestedId = unknown.reservation.logicalTurnId
        activityRevision++
        _uiState.update {
          it.copy(
            status = ChatStatus.CANCELLED,
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        return
      }
      return
    }
    // No join, no IO: return immediately so the UI stops within 200ms.
  }

  /**
   * Atomic start-or-enqueue: the busy check and the FIFO insert happen under
   * the same lock, so the decision is never stale. Returns [TurnStart.Started]
   * once the text is accepted (fresh `chat.send` passed, user message
   * appended, durably persisted) without waiting for the turn to finish,
   * [TurnStart.Queued] when a turn was already in flight, or
   * [TurnStart.NeedsRecovery] when an idle controller still has
   * promotion-gate-failed intents to return to the caller before it retries
   * this admission. A [CancellationException]/[SecurityException]/
   * [IllegalStateException]/[TranscriptDurabilityException] before return
   * means NOT accepted. A [TranscriptPendingException] means UNKNOWN (R5-1
   * timeout-after-send, R6-2 external cancel after-send):
   * the entry may already be in the channel — do NOT resend the same opId
   * until [settlePendingAdmission] resolves (claimed under the same id, or
   * converted to retryable). R6-2: external cancel with send proof false stays
   * NOT_ADMITTED (CE + rollback + INTERRUPTED, retryable); with send proof
   * true it becomes UNKNOWN like the timeout path. Unlike [steer], the idle path never fire-and-forgets:
   * callers always get an explicit acknowledgment.
   *
   * Stage 6 (R4-3): [TurnStart.Started] is durable — see [startTurn]. A
   * stalled store throws [TranscriptDurabilityException] fail-closed (caller
   * retains the text, INTERRUPTED-marked). An admission-window [cancel]
   * abandons the launch and returns `Started` with an already-cancelled host
   * (accepted-then-cancelled, no ghost provider call); a finalize that
   * observes close after a successful ack rolls back the UI and throws
   * [TranscriptDurabilityException] for retry (never a bare
   * IllegalStateException).
   *
   * N1 ownership: [TurnStart.Queued] is NOT acceptance (no gate, no append).
   * [TurnStart.NeedsRecovery] also does not accept the current text; its
   * returned queue has been atomically drained and must be surfaced for
   * explicit user recovery. In the second-lock branch, this admission may
   * already have passed one fresh policy gate; retrying after recovery runs
   * the policy gate again.
   * An admission that finds a turn active (either at the initial lock or
   * after losing the post-gate race) can queue text only. Non-empty [images]
   * throws [IllegalStateException] without adding a FIFO entry; the caller
   * retains ownership of both text and images and may explicitly retry later.
   * The caller keeps owning the text — pass [opId] so endpoint teardown can
   * reclaim it via [drainQueued] instead of losing it in [close]. Queued
   * intents are never auto-resent to a new endpoint; draining only surfaces
   * them for explicit user recovery.
   */
  suspend fun startOrEnqueue(
    text: String,
    images: List<ChatImageRef> = emptyList(),
    opId: Long? = null,
  ): TurnStart {
    require(text.isNotBlank()) { "text must not be blank" }
    checkInputLengthOrThrow(text)
    // R5-1: same-opId duplicate of an unsettled UNKNOWN must not enqueue a
    // second row. Settle boundedly first (outside the lock); if still
    // unknown the guards below throw Pending. M2: caller-cancelled settle
    // rethrows CE (never Pending) like startTurn.
    if (synchronized(lock) { unknownAdmission != null }) {
      try {
        settlePendingAdmission(acceptTimeoutMs)
      } catch (e: CancellationException) {
        if (coroutineContext[Job]?.isCancelled == true) throw e
        throw TranscriptPendingException("transcript store pending")
      } catch (_: Exception) {
      }
    }
    val revisionAtAdmission = synchronized(lock) {
      check(!closed) { "controller is closed" }
      checkPendingDuplicateLocked(opId)
      if (isBusyLocked()) {
        check(images.isEmpty()) { "cannot queue a turn with images; text and images remain caller-owned" }
        enqueueLocked(opId, text, images)
        return TurnStart.Queued
      }
      drainRecoveryLocked()?.let { return TurnStart.NeedsRecovery(it) }
      activityRevision
    }
    beforeAdmissionGateForTest?.invoke()
    gateOrThrow(revisionAtAdmission)
    afterStartOrEnqueueGateForTest?.invoke()
    // Second lock: reserve only (bump + optimistic append + pending). The
    // durable await runs outside the lock; [pendingAdmission] guards the
    // window. Lost-race arrivals enqueue (Queued) as before.
    val reservation: PendingAdmission
    val uiMessageId: String
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      checkPendingDuplicateLocked(opId)
      if (isBusyLocked()) {
        // Lost the race during the gate: re-validate length with the fresh
        // zone before queueing, so a zone that grew during the gate cannot
        // enqueue oversize work. Oversize throws like admission (no enqueue,
        // no revision bump, no transcript); the caller retains the text.
        checkInputLengthOrThrow(text)
        // Lost the race during the gate: queue instead of throwing, so the
        // caller still gets an explicit acceptance signal. Image turns are
        // the exception: QueuedIntent cannot preserve their image refs.
        check(images.isEmpty()) { "cannot queue a turn with images; text and images remain caller-owned" }
        enqueueLocked(opId, text, images)
        return TurnStart.Queued
      }
      // R4-4 idle direct-start re-validation: fresh zone read after the gate,
      // before any bump/append/transcript. Must precede drainRecoveryLocked's
      // bump/clear so the throw path has no append, no bump, no transcript and
      // the FIFO is retained; caller receives InputTooLongException and retains
      // the text.
      checkInputLengthOrThrow(text)
      drainRecoveryLocked()?.let { return TurnStart.NeedsRecovery(it) }
      activityRevision++
      uiMessageId = appendUser(text, opId)
      // Same Stage B ownership as startTurn: id before first suspension.
      // Stage C: attempt index ref travels with the attempt id ref.
      val logicalTurnId = newId()
      val attemptRef = AtomicReference(logicalTurnId)
      val attemptIndexRef = AtomicInteger(0)
      reservation = PendingAdmission(logicalTurnId, text, images, attemptRef, attemptIndexRef)
      pendingAdmission = reservation
    }
    // Stage 6 durable gate (outside the lock): Started == durable user row.
    // R5-1 tri-state: UNKNOWN retains ownership and throws Pending (same-opId
    // resend blocked until settle); NOT_ADMITTED keeps fail-closed rollback.
    // R6-2: same send-proof split as startTurn for external cancel.
    // R8-6: the attempt proof travels alongside `sent` (see startTurn).
    val sent = AtomicBoolean(false)
    val sendAttempted = AtomicBoolean(false)
    try {
      when (awaitUserRowDurable(reservation.logicalTurnId, text, sent, sendAttempted)) {
        DurableAdmission.ACKED -> Unit
        DurableAdmission.NOT_ADMITTED -> {
          rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
          throw TranscriptDurabilityException(
            "transcript store timed out after $acceptTimeoutMs ms",
          )
        }
        DurableAdmission.UNKNOWN -> {
          // Round 10 M1: sealed after the await — never rebuild UNKNOWN.
          if (!enterUnknownAdmission(reservation, uiMessageId, opId)) {
            rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
            throw TranscriptDurabilityException("controller is closed")
          }
          throw TranscriptPendingException("transcript store pending")
        }
      }
    } catch (e: CancellationException) {
      // R8-6 tri-state like startTurn: proven-not-admitted (`!sent &&
      // !sendAttempted) rolls back and rethrows CE; fuzzy (send attempted,
      // ack pending) retains UNKNOWN ownership instead.
      if (!sent.get() && !sendAttempted.get()) {
        rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
        throw e
      }
      // Round 10 M1: sealed after the await — fail closed (Invariant 5).
      if (!enterUnknownAdmission(reservation, uiMessageId, opId)) {
        rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
        throw TranscriptDurabilityException("controller is closed", e)
      }
      throw TranscriptPendingException("transcript store pending", e)
    } catch (e: TranscriptPendingException) {
      throw e
    } catch (e: TranscriptDurabilityException) {
      rollbackFailedAdmission(uiMessageId, reservation, "transcript store unavailable")
      throw e
    }
    // R21-1 Phase 1 finalize barrier (outside the lock): same durable-but-
    // unterminated window as startTurn. Test-only pause point.
    beforeAdmissionFinalizeForTest?.invoke()
    val host = synchronized(lock) {
      if (closed) {
        rollbackClosedAdmissionLocked(uiMessageId, reservation)
        throw TranscriptDurabilityException("controller is closed")
      }
      if (admissionCancelRequestedId == reservation.logicalTurnId) {
        admissionCancelRequestedId = null
        val pending =
          pendingAdmission?.takeIf { it.logicalTurnId == reservation.logicalTurnId } ?: reservation
        drainOrphanLocked(pending)
        _uiState.update { s ->
          s.copy(
            status = ChatStatus.CANCELLED,
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        val cancelled = Job()
        cancelled.cancel()
        return@synchronized cancelled
      }
      _uiState.update {
        it.copy(status = ChatStatus.STREAMING, error = null, queuedRecoveryRequired = false)
      }
      val job = scope.launch {
        hostedTurn(text, images, reservation.logicalTurnId, reservation.attemptRef, reservation.attemptIndexRef)
      }
      inFlight = job
      val logicalTurnId = reservation.logicalTurnId
      job.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === job) inFlight = null
          val pending = pendingAdmission
          if (pending?.logicalTurnId == logicalTurnId) {
            drainOrphanLocked(pending)
          }
        }
      }
      job
    }
    return TurnStart.Started(host)
  }

  /**
   * Queue behind an active turn, or start asynchronously when idle. An idle
   * call that finds retained recovery work throws [RecoveryRequiredException]
   * synchronously. If recovery wins after the idle check, this legacy intent
   * is retained for recovery and the UI exposes the failure instead of
   * silently dropping it or promoting it automatically.
   */
  fun steer(text: String) {
    require(text.isNotBlank()) { "text must not be blank" }
    checkInputLengthOrThrow(text)
    synchronized(lock) {
      check(!closed) { "controller is closed" }
      if (isBusyLocked()) {
        // Never cancel the current turn: queue for the next round (FIFO).
        // Stage 6: includes the admission-time durable window (pending set,
        // host not yet launched) so a steer racing a durable await queues
        // synchronously instead of taking the async idle path.
        enqueueLocked(null, text, emptyList())
        return
      }
      if (steerQueue.isNotEmpty()) {
        // Keep the legacy synchronous recovery signal, but do not make its
        // caller's new text disappear behind the already-retained FIFO.
        retainSteerForRecoveryLocked(text)
        throw RecoveryRequiredException()
      }
    }
    afterIdleSteerCheckForTest?.invoke()
    // Idle: use atomic admission so a turn that wins this race queues the
    // instruction instead of making a stale busy check reject and lose it.
    scope.launch {
      try {
        when (val admission = startOrEnqueue(text, opId = null)) {
          is TurnStart.Started, TurnStart.Queued -> Unit
          is TurnStart.NeedsRecovery -> {
            // Put the transferred older FIFO back first, then retain this
            // unaccepted legacy intent. Recovery-owned work is never promoted.
            retainSteerForRecovery(text, recovered = admission.queuedIntents)
          }
        }
      } catch (_: SecurityException) {
        retainSteerForRecovery(text, error = "denied by policy")
      } catch (_: InputTooLongException) {
        // Admission fail-fast (R4-4): never retain oversize text for
        // recovery (no enqueue, no transcript, no activityRevision).
        // The caller retains ownership; surface idle-only.
        projectInputTooLongIdle()
      } catch (_: ApprovalRequiredException) {
        retainSteerForRecovery(text, error = "approval required")
      } catch (_: PolicyEvaluationException) {
        retainSteerForRecovery(text, error = "policy evaluation failed")
      } catch (_: TranscriptPendingException) {
        // R5-1 UNKNOWN: ownership retained by the pending admission (ghost may
        // late-commit and be claimed under the same id). Do NOT retain as
        // recovery — that would duplicate the opId on claim. Surface pending.
        synchronized(lock) {
          if (!isBusyLocked()) {
            activityRevision++
            _uiState.update { s ->
              if (s.status == ChatStatus.IDLE || s.status == ChatStatus.ERROR) {
                s.copy(status = ChatStatus.ERROR, error = "transcript store pending")
              } else {
                s
              }
            }
          }
        }
      } catch (_: TranscriptDurabilityException) {
        // Stage 6: admission-time durable failure (store stall/seal) never
        // loses the steer text; retain for explicit recovery like any other
        // unaccepted admission.
        retainSteerForRecovery(text, error = "transcript store unavailable")
      } catch (_: CancellationException) {
        // close() may discard intents by lifecycle contract. Other admission
        // cancellation must leave the unaccepted text recoverable.
        retainSteerForRecovery(text, error = "turn admission cancelled")
      } catch (_: Exception) {
        // Never silently swallow an unaccepted intent. Closed controllers are
        // intentionally ignored by retainSteerForRecovery().
        retainSteerForRecovery(text, error = "turn admission failed")
      }
    }
  }

  fun close(drainTimeoutMs: Long = OrderedTranscriptSink.DEFAULT_DRAIN_TIMEOUT_MS) {
    // Drop the FIFO under the same lock that seals the controller, so no
    // admission can slip in after the seal. Reclaim-then-seal ordering across
    // threads is the caller's job: the ViewModel drains-then-closes with no
    // suspension on its Main-confined path, so nothing slips between them
    // there. Callers that must keep unstarted work (endpoint-switch recovery)
    // drain it explicitly via drainQueued() BEFORE close().
    //
    // R6-1 UNKNOWN exception to drain-before-close: while an UNKNOWN ownership
    // is pending, drainQueued() throws TranscriptPendingException (the queued
    // head may already be in the channel as a ghost — handing it out would
    // duplicate it on retry), so an endpoint-switch caller that drains-then-
    // closes sees an empty-equivalent and proceeds to close(). Close then drops
    // the FIFO head and clears the UNKNOWN ownership here; the head disappears
    // from the outbox (not reclaimable via the recovery box) while the acked
    // ghost — if the flush later proves it — survives in the ledger as an
    // orphan user row with no cancel-terminal. R22 Phase 1 fail-closed: that
    // orphan is NOT claimed clean here — [closedWithUnknownGhost] keeps
    // [flushDurable] false on this session until reopen INTERRUPTED backfill
    // (a new session) repairs the family, so the seal never reports
    // true-and-dangling.
    // Confirmed NOT_ADMITTED/unavailable FIFO stays reclaimable as before;
    // only the UNKNOWN-fated head is excepted.
    // Round 10 H1: the promotion-await window (promotionPending set, no UNKNOWN
    // yet) throws the same TranscriptPendingException from drainQueued() /
    // returns null from drainRecoveryLocked() — Lock-1 already minted the
    // follow id and the await may have sent it, so the head is NOT reclaimable
    // until the await ACKs (Lock-2 commit) or settles (UNKNOWN claim/convert).
    // Drain-then-close callers treat the throw as empty-equivalent (catch →
    // emptyList) exactly like UNKNOWN. Round 10 M1: waiters that observe the
    // seal after their await never rebuild UNKNOWN ([enterUnknownAdmission]
    // returns false when closed) — direct waiters roll back + throw
    // TranscriptDurabilityException, promotion waiters clear promotionPending +
    // failPromotion — so Invariant 5 (no post-seal UNKNOWN) holds.
    val doomed: Job?
    val orphanJob: Job?
    synchronized(lock) {
      if (closed) return
      closed = true
      activityRevision++
      steerQueue.clear()
      // R22 Phase 1 sticky close obligation (fail-closed): capture the
      // terminal obligations close is about to drop, BEFORE clearing them
      // below. Unprovable fates (UNKNOWN ghost, promotion reservation — the
      // await may already have sent it and the Lock-2 closed-win writes
      // nothing) take the permanent tier ([closedWithUnknownGhost]: this
      // session never reports durable-true again; only reopen backfill
      // repairs the ledger). Controller-observed families (direct pending,
      // live host, in-flight orphan drain) take the liftable tier
      // ([closedWithUnsettledAdmission]: lifts once the background
      // settle+drain+seal below completes with no remaining evidence).
      // Either tier keeps [flushDurable] false until proven — never
      // true-and-dangling across the close.
      if (unknownAdmission != null || promotionPending != null) {
        closedWithUnknownGhost = true
      }
      if (
        pendingAdmission != null || activeLocked() != null ||
        orphanDrainJob?.isCompleted == false
      ) {
        closedWithUnsettledAdmission = true
      }
      // R5-1: an UNKNOWN waiter already threw Pending — nobody will finalize
      // its pendingAdmission. Clear it (and the UNKNOWN ownership) here so no
      // second user-row write can duplicate a late-committing ghost; the
      // shutdown drain below settles the ghost entry itself (drained or
      // INTERRUPTED-marked, never rewritten).
      // R6-1: also clear promotionPending explicitly (promotion await window).
      // The async waiter catch would clear it eventually, but without an
      // explicit clear a racing admission could briefly observe a stale
      // reservation via isBusyLocked() after the seal.
      promotionPending = null
      unknownSettleJob?.cancel()
      unknownSettleJob = null
      val unknown = unknownAdmission
      unknownAdmission = null
      if (unknown != null && pendingAdmission?.logicalTurnId == unknown.reservation.logicalTurnId) {
        pendingAdmission = null
      }
      if (unknown != null && admissionCancelRequestedId == unknown.reservation.logicalTurnId) {
        admissionCancelRequestedId = null
      }
      val current = activeLocked()
      doomed = current
      val orphaned = pendingAdmission
      // Stage 6: a pending admission with no launched host is still in its
      // admission-time durable window (the waiter owns the user-row write
      // outside the lock). Do NOT launch a second concurrent user-row write
      // here; the waiter finalizes (drains a cancel when it observes `closed`,
      // or rolls back on durability failure). Only drain orphans whose host
      // already launched (inFlight set) but never ran.
      orphanJob = if (orphaned != null && current != null) {
        drainOrphanLocked(orphaned)
      } else {
        orphanDrainJob
      }
      _uiState.update {
        it.copy(
          pendingSteerCount = 0,
          status = if (current != null) ChatStatus.CANCELLED else it.status,
          queuedRecoveryRequired = false,
        )
      }
      current?.cancel()
    }
    scope.cancel()
    // Bounded ledger drain on the session-owned writer (independent of
    // [scope], so it survives the cancel above): settle the doomed host first
    // so its non-cancellable terminal record is admitted ahead of the flush
    // barrier, then receipt outcome handoff, then drain, then seal. Core runIds
    // still unacked afterwards are exposed via [interruptedTranscriptRunIds],
    // never silently dropped. The synchronous part only launches this work, so
    // close() stays fast even when the store stalls; await it via
    // [flushTranscript]. The job is published under [lock] so a concurrent
    // [flushTranscript] observes it.
    //
    // R19-1 order: host settle → receipt outcome handoff
    // (CLAIM/REVOKED/COMPENSATED/SEALED terminal) → flush barrier → seal.
    // The waiter snapshot is taken AFTER the doomed-host + orphan joins (a
    // receipt is installed synchronously before its host completes, so the
    // join happens-before the snapshot and no host-installed receipt is
    // missed; post-seal admissions are impossible since [closed] is set).
    // Phase 2 (B) remaining-budget linearization: the whole settle
    // (doomed-host + orphan + waiters) shares one [drainTimeoutMs] fence via
    // the outer [OrderedTranscriptSink.shutdown] budget. The host/orphan
    // joins above spend it first; the waiter phase below gets only the
    // remainder (deadline-now), never a fresh full budget — so a waiter
    // admission owed while the terminal is still alive linearizes before
    // the flush barrier/seal whenever it fits, and a timeout explicitly
    // leaves the receipt PENDING/CLAIMED (later SEALED-on-seal) instead of
    // silently extending the fence. [flushDurable] stays false via
    // [hasUnsettledCancelReceipts] plus the Phase 1
    // [hasSealedUncompensatedReceipts] conjunct (never true-and-dangling),
    // and — R22 Phase 1 — via [hasActiveHost] while the doomed host still
    // runs plus the sticky [hasClosedUnsettledAdmission] tiers after it.
    // R22 doomed-host settle timeout: if the bounded join above times out,
    // the still-live host keeps owning its terminal (no INTERRUPTED mark is
    // forged here — only provably-unacked pendingCore ids are marked by the
    // drain timeout below, never a never-admitted terminal); the sticky
    // tiers carry that debt instead, so an unterminated doomed family is
    // never reported clean. Reopen backfill owns the repair.
    // This covers both waiter self-expiry ([cancelReceiptMaxRounds] SEALED)
    // and settle-budget exhaustion (outer timeout) with one bounded fence.
    // Compensation writes run inside the waiter job, hence before the
    // barrier and before `channel.close()`.
    val shutdown = orderedTranscript.shutdown(drainTimeoutMs) {
      val settleStartNs = System.nanoTime()
      try {
        doomed?.join()
      } catch (_: CancellationException) {
        // Hosts rethrow cancellation after persisting the terminal record.
      }
      try {
        val drain = synchronized(lock) { orphanDrainJob } ?: orphanJob
        drain?.join()
      } catch (_: CancellationException) {
      }
      try {
        // Phase 5: re-snapshot loop. A cancel racing this close can install
        // its receipt and launch its waiter between the snapshot above and
        // the barrier; joining only the first snapshot would let the barrier
        // pass pre-handoff. Re-read the map and join newcomers within the
        // same remainder budget (deadline, never a fresh budget). A waiter
        // that still misses the deadline stays PENDING/CLAIMED and fails
        // [hasUnsettledCancelReceipts] instead of reporting true-and-dangling.
        val elapsedMs = (System.nanoTime() - settleStartNs) / 1_000_000L
        val waiterBudgetMs = (drainTimeoutMs - elapsedMs).coerceAtLeast(0L)
        val waiterDeadlineNs = System.nanoTime() + waiterBudgetMs * 1_000_000L
        val joinedWaiters = HashSet<Job>()
        while (true) {
          val fresh = cancelReceiptWaiters.values.filter { it !in joinedWaiters }
          if (fresh.isEmpty()) break
          val remainingMs = ((waiterDeadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
          if (remainingMs <= 0L) break
          var exhausted = false
          for (waiter in fresh) {
            val leftMs = ((waiterDeadlineNs - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
            if (leftMs <= 0L) {
              exhausted = true
              break
            }
            val done = try {
              withTimeoutOrNull(leftMs) {
                try {
                  waiter.join()
                } catch (_: CancellationException) {
                }
              } != null
            } catch (_: Throwable) {
              // Security Finding3 (Stage-3): intentional teardown swallow —
              // the waiter join above must never abort the close fence (an
              // unhanded receipt stays PENDING/CLAIMED and fails
              // hasUnsettledCancelReceipts fail-closed below). VM-fatal
              // (VirtualMachineError/ThreadDeath) is not specially rethrown
              // here because the shutdown runs on the session-owned writer
              // scope whose cancel below converges teardown anyway; the
              // flush barrier below still drains what was admitted.
              true
            }
            joinedWaiters.add(waiter)
            if (!done) {
              exhausted = true
              break
            }
          }
          if (exhausted) break
        }
      } catch (_: Throwable) {
        // Security Finding3 (Stage-3): intentional teardown swallow — the
        // flush barrier below still drains what was admitted; an unhanded
        // receipt stays PENDING/CLAIMED and fails
        // [hasUnsettledCancelReceipts] instead of reporting true-and-dangling.
        // Best effort: only the notice/fence is at stake, never durability.
      }
    }
    shutdown?.invokeOnCompletion {
      writerScope.cancel()
    }
    synchronized(lock) { shutdownJob = shutdown }
  }

  /**
   * Suspends until the session ledger is fully settled: first the background
   * [close] drain (doomed-host settle + admitted drain + seal, all bounded),
   * then every event admitted so far. Each phase gets its own [timeoutMs]
   * budget — the [close] shutdown-join first, then the admitted-event drain —
   * so the total wait is bounded by roughly 2x[timeoutMs], not [timeoutMs]
   * (true = drained, false = timed out). Send returning already implies
   * durability for the terminal record (core writes are acked inline); use
   * this after [close]/[cancel] to await the asynchronous terminal record.
   * Cancellation of the caller still propagates.
   *
   * Concurrency: [shutdownJob] is read under the same [lock] that [close]
   * publishes it with, so a concurrent [flushTranscript] either observes the
   * drain job or — if it raced ahead of [close] — only flushes admitted
   * events; call again after [close] returns to await the full drain.
   */
  suspend fun flushTranscript(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
    val shutdown = synchronized(lock) { shutdownJob }
    try {
      if (shutdown != null && withTimeoutOrNull(timeoutMs) { shutdown.join() } == null) return false
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Best effort: the drain below still waits for what was admitted.
    }
    return orderedTranscript.flush(timeoutMs)
  }

  /** True if any admitted transcript event encountered a durable store failure or interruption. */
  fun hasDurableFailures(): Boolean = orderedTranscript.hasDurableFailures()

  /**
   * Suspends until every transcript event admitted so far is persisted and verifies
   * that no durable store failure occurred, no cancel receipt is still unsettled,
   * no SEALED receipt is still uncompensated, and no terminal obligation is
   * still open.
   *
   * Returns true only when [flushTranscript] drains within [timeoutMs] AND all
   * of the following hold (Current: eight conjuncts; R22 Phase 1 — never
   * true-and-dangling across every terminal-obligation window):
   * 1. [hasDurableFailures] is false (re-read AFTER the admission proof and
   *    once more fail-last after the second snapshot, see the R22-3 note
   *    below);
   * 2. [hasUnsettledCancelReceipts] is false (no PENDING/CLAIMED receipt —
   *    the R19-1 close-barrier guard);
   * 3. [hasSealedUncompensatedReceipts] is false (no SEALED receipt without
   *    proven family-final — the Phase 1 SEALED truth contract; SEALED alone
   *    is NOT settled);
   * 4. [hasUnsettledAdmission] is false (no direct pending, promotion
   *    reservation, UNKNOWN ghost, or orphan drain — the R21-1 Phase 1
   *    barrier, R22-broadened from direct-only);
   * 5. [hasUnsettledPromotion] is false (no promotion await / promotion ghost
   *    — named subset of (4), conjoined explicitly);
   * 6. [hasActiveHost] is false (no live host: a running host owns a user row
   *    with no proven terminal yet — the R22 streaming/doomed-host guard);
   * 7. [hasClosedUnsettledAdmission] is false (no sticky close obligation:
   *    close-dropped UNKNOWN/promotion fates stay false until reopen
   *    backfill; controller-observed families stay false until the
   *    settle+drain+seal clean proof holds).
   *
   * R22-3 TOCTOU linearization: the five obligation reads above are separate
   * lock acquisitions, so a window opened between two reads could slip
   * through a single pass. The failure epoch is therefore re-read after the
   * admission proof (a failure admitted during the guard window still fails
   * closed), the test gate [beforeFlushDurableReverifyForTest] runs between
   * the two failure reads, the admission snapshot is re-verified once more,
   * and the failure epoch is read a final time before returning true (a
   * failure admitted during the second snapshot window still fails closed).
   * The return-window gate [beforeFlushDurableReturnForTest] runs between
   * the second snapshot and the final failure read. Ending fail-last (on
   * the failure read, rather than snapshot-last) is the symmetric move: a
   * durable failure is the monotonic consequence of a receipt/admission
   * obligation, so the fail-last order covers the snapshot-to-return gap the
   * snapshot-last order left open; either last-read still leaves a symmetric
   * one-window residual, so only the Target single-lock snapshot plus a
   * monotonic failure epoch closes both directions. A single-lock snapshot
   * plus a monotonic failure epoch is Target; Current is this triple-read.
   */
  suspend fun flushDurable(timeoutMs: Long = OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
    if (!flushTranscript(timeoutMs)) return false
    if (!admissionSettledSnapshot()) return false
    if (hasDurableFailures()) return false
    beforeFlushDurableReverifyForTest?.invoke()
    if (hasDurableFailures()) return false
    if (!admissionSettledSnapshot()) return false
    beforeFlushDurableReturnForTest?.invoke()
    if (hasDurableFailures()) return false
    return true
  }

  /**
   * One pass over every non-failure [flushDurable] obligation (see
   * [flushDurable] conjuncts 2–7). Read twice per call (before and after the
   * failure-epoch re-read, plus a final failure-epoch read) for the R22-3
   * linearization.
   */
  private fun admissionSettledSnapshot(): Boolean =
    !hasUnsettledCancelReceipts() && !hasSealedUncompensatedReceipts() &&
      !hasUnsettledAdmission() && !hasUnsettledPromotion() && !hasActiveHost() &&
      !hasClosedUnsettledAdmission()

  /**
   * Core runIds admitted but never acked (drain timeout / seal race).
   * Explicit INTERRUPTED marks for Phase 2/3 recovery; no Room schema change
   * in Phase 1.
   */
  fun interruptedTranscriptRunIds(): List<String> = orderedTranscript.interruptedRunIds()

  /** Core runIds admitted and still awaiting the session writer. */
  fun pendingTranscriptRunIds(): List<String> = orderedTranscript.pendingRunIds()

  /**
   * R10-2 writer-dead proof: true once the session writer is sealed/torn
   * down (later core writes fail fast instead of parking). The backfill gate
   * consults this together with an empty pending set, [isTranscriptWriterCompleted]
   * (R14-1), and no Phase 1 obligation evidence (R22 Phase 2 barrier-aware)
   * to promote an expired block whose ledger still dangles (terminal will
   * never land → marking INTERRUPTED is correct); without this proof an
   * expired-but-unsettled ledger keeps deferring.
   *
   * R14-1: seal alone is NOT the dead proof (see
   * [OrderedTranscriptSink.isWriterCompleted]) — the gate conjoins
   * [isTranscriptWriterCompleted].
   */
  fun isTranscriptSealed(): Boolean = orderedTranscript.isSealed()

  /**
   * R14-1 writer-completion proof: true only once the session writer can no
   * longer execute a store block. Conjoined with [isTranscriptSealed], empty
   * [pendingTranscriptRunIds], no [hasPendingAdmission], and no Phase 1
   * obligation evidence (R22 Phase 2 barrier-aware) before an expired
   * outstanding teardown may be treated as dead.
   */
  fun isTranscriptWriterCompleted(): Boolean = orderedTranscript.isWriterCompleted()

  // ---- internals ----

  /** Caller must hold [lock]. Direct starts cannot take ownership of recovery FIFO. */
  private fun checkNoQueuedRecoveryLocked() {
    if (steerQueue.isNotEmpty()) throw RecoveryRequiredException()
  }

  /**
   * Admission fail-fast for R4-4 (boundary [MAX_REQUEST_CHARS] invariant).
   *
   * Rejects a single-turn [text] whose own payload alone — `text.length + 2
   * ("\n\n") + actual [buildRuntimeTimeContext] block length` — already
   * exceeds [MAX_REQUEST_CHARS]. Uses the actual block rendered with this
   * controller's [clock]/[userTimezone]/[sessionStart]/[systemZone] (same
   * overload [buildRequest] uses), so the check matches the emitted request
   * without a fixed reserve that could flake across zones. Image bytes are
   * never counted (out of scope, same as [buildRequest]).
   *
   * Must run before any gate/append/transcript/activity bookkeeping on its
   * path: on throw nothing is appended, nothing is written, and
   * [activityRevision] is untouched on that path. Callers retain the text (VM
   * restores the draft).
   *
   * The same check re-validates every handoff: admission pre-gate checks,
   * post-gate second-lock re-checks ([startTurn] before bump/append with
   * length-before-busy fail-fast priority; [startOrEnqueue] both the
   * lost-race enqueue path before enqueue and the idle direct-start path
   * before drain/bump/append, FIFO retained on throw), and the promotion path
   * peeking the FIFO head before dequeue (oversize retains the FIFO via
   * `failPromotion("input too long")`). Intra-turn retry rebuilds in
   * [runTurnLoop] re-read the zone per attempt; any growth there contributes
   * only the time-block delta. L3: the settle-claim window
   * ([settlePendingAdmission] claims a ghost admitted moments earlier
   * without re-checking) is the same accepted-residual class — a zone that
   * grows during the bounded flush adds at most the time-block delta to an
   * already-admitted turn, surfaced at the next admission/promotion
   * boundary.
   */
  private fun checkInputLengthOrThrow(text: String) {
    val block = buildRuntimeTimeContext(clock, userTimezone, sessionStart, systemZone)
    if (text.length + 2 + block.length > MAX_REQUEST_CHARS) {
      throw InputTooLongException(
        "input too long: text=${text.length} + timeBlock=${block.length} + 2 exceeds $MAX_REQUEST_CHARS",
      )
    }
  }

  /**
   * Best-effort idle projection for an oversize legacy steer that only
   * surfaced after the async hop (zone grew between the synchronous check
   * and the async [startOrEnqueue] check). Intentionally side-effect free:
   * no enqueue, no transcript write, no [activityRevision] bump — the text
   * stays caller-owned. Only projects when idle so a live turn is never
   * disturbed (idle includes the admission-time durable window: [isBusyLocked]
   * covers both the host and [pendingAdmission]).
   */
  private fun projectInputTooLongIdle() {
    synchronized(lock) {
      if (closed || isBusyLocked()) return
      val status = _uiState.value.status
      if (status != ChatStatus.IDLE && status != ChatStatus.ERROR) return
      _uiState.update { it.copy(status = ChatStatus.ERROR, error = "input too long") }
    }
  }

  /** Retain an unaccepted steer and any transferred FIFO for explicit recovery. */
  private fun retainSteerForRecovery(
    text: String,
    error: String? = null,
    recovered: List<QueuedIntent> = emptyList(),
  ) {
    synchronized(lock) {
      if (closed) return
      retainSteerForRecoveryLocked(text, error, recovered)
    }
  }

  /** Caller must hold [lock]. Transferred intents stay ahead of existing/new work. */
  private fun retainSteerForRecoveryLocked(
    text: String,
    error: String? = null,
    recovered: List<QueuedIntent> = emptyList(),
  ) {
    recovered.asReversed().forEach { intent ->
      steerQueue.addFirst(PendingSteer(intent.opId, intent.text, emptyList()))
    }
    enqueueLocked(null, text, emptyList())
    // Admission-window aware: a pending durable await still owns the
    // single-flight slot, so recovery must not project ERROR over it.
    val busy = isBusyLocked()
    _uiState.update { state ->
      val mayProjectError = !busy && state.status in setOf(ChatStatus.IDLE, ChatStatus.ERROR)
      state.copy(
        status = if (mayProjectError) ChatStatus.ERROR else state.status,
        error = if (!mayProjectError) state.error else if (state.queuedRecoveryRequired && state.error != null) {
          state.error
        } else {
          error ?: state.error ?: "queued turn recovery required before admission"
        },
        pendingSteerCount = steerQueue.size,
        queuedRecoveryRequired = true,
      )
    }
  }

  /** Caller must hold [lock]. Enqueues a follow-up and projects the count. */
  private fun enqueueLocked(opId: Long?, text: String, images: List<ChatImageRef>) {
    activityRevision++
    steerQueue.addLast(PendingSteer(opId, text, images))
    _uiState.update {
      it.copy(
        pendingSteerCount = steerQueue.size,
        // A cancelled-but-not-yet-completed host still owns the single-flight
        // slot. Sends in that window queue normally, but cannot be promoted.
        queuedRecoveryRequired = it.queuedRecoveryRequired || it.status == ChatStatus.CANCELLED,
      )
    }
    // Session-ordered (never fire-and-forget): the writer admits this behind
    // the caller's lock ordering, so queue order matches transcript order.
    orderedTranscript.offerSteer(text)
  }

  /**
   * Caller must hold [lock]; idle residual FIFO is transferred atomically.
   * R6-1: while an UNKNOWN ownership is pending the FIFO head may already be
   * in the channel (ghost) — it is NOT definitely un-written, so it must not
   * be transferred for resend. Return null (no recovery) and let the caller
   * hit the Pending guard instead.
   * Round 10 H1: the promotion-await window ([promotionPending] set,
   * [unknownAdmission] still null) holds the same hazard — Lock-1 already
   * minted the follow id and the await may have sent it — so it returns null
   * exactly like UNKNOWN. The head stays queued until the await ACKs (Lock-2
   * commit) or settles (UNKNOWN claim/convert).
   */
  private fun drainRecoveryLocked(): List<QueuedIntent>? {
    if (unknownAdmission != null || promotionPending != null) return null
    if (steerQueue.isEmpty()) return null
    val out = steerQueue.map { QueuedIntent(it.opId, it.text) }
    activityRevision++
    steerQueue.clear()
    _uiState.update { it.copy(pendingSteerCount = 0, queuedRecoveryRequired = false) }
    return out
  }

  /**
   * Reclaim queued-but-unstarted intents FIFO (N1). Returns the intents in
   * queue order and clears the FIFO without starting anything. The caller
   * owns them afterwards: surface for explicit user recovery, never
   * auto-resend. Safe to call on a closed controller (drains the remainder).
   *
   * R6-1: while an UNKNOWN ownership is pending the queued head is NOT
   * reclaimable — its fate is unknown (ghost may late-commit under the same
   * id). Throw [TranscriptPendingException] instead of handing it out, so a
   * racing reclaim cannot duplicate the ghost on retry. Confirmed
   * NOT_ADMITTED/unavailable FIFO stays reclaimable as before.
   * Round 10 H1: the promotion-await window ([promotionPending] set, no UNKNOWN
   * yet) throws the same [TranscriptPendingException] — Lock-1 already minted
   * the follow id and the await may have sent it, so handing out the head
   * would let the await ACK (ghost durable) and the drained retry (fresh id)
   * write two user rows. Drain-then-close callers already treat this throw as
   * empty-equivalent (catch → emptyList) like the UNKNOWN case.
   */
  fun drainQueued(): List<QueuedIntent> {
    synchronized(lock) {
      if (steerQueue.isEmpty()) return emptyList()
      if (unknownAdmission != null || promotionPending != null) throw TranscriptPendingException("transcript store pending")
      val out = steerQueue.map { QueuedIntent(it.opId, it.text) }
      activityRevision++
      steerQueue.clear()
      _uiState.update { it.copy(pendingSteerCount = 0, queuedRecoveryRequired = false) }
      return out
    }
  }

  private suspend fun gateOrThrow(revisionAtAdmission: Long) {
    val decision = try {
      policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      projectIdleGateFailure("policy evaluation failed", revisionAtAdmission)
      throw PolicyEvaluationException(e)
    }
    if (decision.verdict == Verdict.DENY) {
      projectIdleGateFailure("denied by policy", revisionAtAdmission)
      throw SecurityException("chat.send denied by policy")
    }
    if (decision.verdict == Verdict.ASK) {
      projectIdleGateFailure("approval required", revisionAtAdmission)
      throw ApprovalRequiredException()
    }
  }

  /** A stale gate result must not overwrite any controller activity since admission began. */
  private fun projectIdleGateFailure(error: String, revisionAtAdmission: Long) {
    synchronized(lock) {
      val status = _uiState.value.status
      if (
        closed || activityRevision != revisionAtAdmission || activeLocked() != null ||
        status == ChatStatus.CANCELLED || (status != ChatStatus.IDLE && status != ChatStatus.ERROR)
      ) return
      activityRevision++
      _uiState.update {
        val hasQueuedRecovery = steerQueue.isNotEmpty()
        it.copy(
          status = ChatStatus.ERROR,
          // A late fresh-gate result must not replace the promotion failure
          // that explains why this already-retained FIFO needs recovery.
          error = if (hasQueuedRecovery && it.queuedRecoveryRequired && it.error != null) it.error else error,
          pendingSteerCount = steerQueue.size,
          queuedRecoveryRequired = hasQueuedRecovery,
        )
      }
    }
  }

  /**
   * Hosts one logical turn. [logicalTurnId] and [attemptRef]/[attemptIndexRef]
   * are created before the host launches (before the first suspension), so a
   * cancel parked on the `onTurnStarted` ack still attributes to this turn.
   * Cancel always records the current attempt id + that attempt's text
   * (pre-placeholder partial is "" with this turn's id); it never falls
   * back to a previous turn's id and never uses an empty runId. If the user
   * row itself is not yet durable, the cancel terminal still uses this
   * turn's id (ordered behind the started entry, or INTERRUPTED-marked on
   * store/seal failure). Stage C: the cancel terminal carries the logical
   * family linkage (`parentRunId` + `attemptIndex`) like every other attempt
   * terminal. Stage F: a cancel that lands on an already-terminalized
   * attempt (retry backoff gap: the just-failed partial is already durable)
   * writes one system-kind final mark instead of a second assistant row for
   * the same attempt; otherwise the cancel assistant row is final
   * (`isFinal=1`). D15-2: a cancel racing a success / terminal-failure final
   * in its commit→ack window (or while SUBMITTING) writes zero new rows.
   */
  private suspend fun hostedTurn(
    text: String,
    images: List<ChatImageRef>,
    logicalTurnId: String,
    attemptRef: AtomicReference<String>,
    attemptIndexRef: AtomicInteger,
  ) {
    synchronized(lock) {
      if (pendingAdmission?.logicalTurnId == logicalTurnId) {
        pendingAdmission = null
      }
    }
    val self = coroutineContext[Job]
    try {
      runTurnLoop(text, images, logicalTurnId, attemptRef, attemptIndexRef)
    } catch (e: CancellationException) {
      // Current attempt only: attemptRef tracks the live attempt across
      // retries/sleeper gaps, so this never reuses a previous turn's id.
      // Snapshot before the write: assistantTextOf reads uiState at execution
      // time, and the ordered write below suspends until admitted.
      val cancelId = attemptRef.get()
      val cancelIndex = attemptIndexRef.get()
      val cancelParent = parentFor(cancelId, logicalTurnId)
      // #83: a cancel mid-stream still owns buffered (possibly unflushed)
      // text — flush so the UI copy is exact before snapshotting. The flush
      // is non-suspending (_uiState.update only), safe in this cancelled
      // context; the snapshot below also reads the buffer directly.
      flushAssistantText(cancelId)
      val cancelText = assistantTextOf(cancelId)
      // Stage F dedup, D15-2 submit-truth fix: the terminalized set holds every
      // attempt whose row is already durably acked, but it is updated only
      // AFTER the ack returns — a host cancelled in the commit→ack window
      // (writer committed, ack still in flight) used to miss the set and write
      // a duplicate assistant row for the same attempt. The sink now owns the
      // submit truth synchronously (no suspend): SUBMITTING (admitted, writer
      // not yet committed) or COMMITTED (writer committed, ack may still be
      // in flight) both suppress the duplicate. Attempt-dimension: a first
      // attempt reuses its logical id for the user row, so its terminal
      // commit is `commitCount >= 2` (user + terminal); fresh retry ids use
      // `>= 1`. SUBMITTING additionally requires terminal intent
      // ([attemptTerminalFinal] present) so a user-row admission race (same
      // id, no terminal attempted yet) never suppresses a streaming cancel.
      // FAILED (store failure, INTERRUPTED-marked, no commit) and
      // never-submitted keep the normal final cancel assistant. Suppressed
      // finals write zero new rows (the family already owns — or will own —
      // its final); suppressed intermediate partials (non-final, family still
      // dangling) write one system-kind final mark instead.
      //
      // D15-2 gap (FAILED/store-failure): SUBMITTING alone cannot distinguish
      // a terminal that will commit (suppress: zero/system-mark) from one
      // that will fail with no commit (FAILED: normal final). A cancel that
      // lands while the terminal is still pending but the writer then fails
      // (no commit, INTERRUPTED) used to suppress optimistically and leave
      // only the user row (dangling). When suppression rests solely on
      // SUBMITTING (not yet acked/committed), settle-wait boundedly for the
      // independent writer (NonCancellable, never blocks the already-flipped
      // CANCELLED UI) and re-evaluate: COMMITTED/acked stays suppressed,
      // FAILED (pending cleared, still no commit) falls through to the
      // normal final. A still-pending stall keeps the optimistic suppression.
      val terminalRecord = attemptTerminalFinal[cancelId]
      val terminalFinal = terminalRecord?.isFinal
      val terminalOutcomeForCancel = terminalRecord?.outcome
      var acked = terminalizedAttempts.contains(cancelId)
      // R17-1 review BLOCK-1: one atomic snapshot per decision point — never
      // separate coreCommitCount/isCorePending reads, whose torn mix (stale
      // count + already-cleared pending, or vice versa) misclassifies
      // COMMITTED as SUBMITTING/FAILED. The writer publishes the success pair
      // under submitTruthLock; coreSubmitTruth reads both under the same lock.
      // The older accessors stay only for pre-existing D15 settle paths.
      var snap = orderedTranscript.coreSubmitTruth(cancelId)
      var commitCount = snap.commitCount
      var terminalCommitted =
        if (cancelId == logicalTurnId) commitCount >= 2 else commitCount >= 1
      var terminalSubmitting = terminalFinal != null && snap.pending
      if (terminalSubmitting && !acked && !terminalCommitted) {
        // D15-2 test seam (null in production): fires after the SUBMITTING
        // decision so the cancel-gap grid can release its blocked terminal
        // writer deterministically instead of sleeping a fixed heuristic.
        onCancelSettleWaitEnteredForTest?.invoke()
        withContext(NonCancellable) {
          withTimeoutOrNull(cancelSettleTimeoutMs) {
            while (orderedTranscript.coreSubmitTruth(cancelId).pending) delay(10)
          }
        }
        acked = terminalizedAttempts.contains(cancelId)
        snap = orderedTranscript.coreSubmitTruth(cancelId)
        commitCount = snap.commitCount
        terminalCommitted =
          if (cancelId == logicalTurnId) commitCount >= 2 else commitCount >= 1
        terminalSubmitting = terminalFinal != null && snap.pending
      }
      val suppressed = acked || terminalCommitted || terminalSubmitting
      val suppressedFinal = terminalFinal ?: true
      // R17-1 P1 + R18-2 direction-A + R18-1 Stage 2 notice-first: still-pending
      // stall (suppression rests solely on SUBMITTING after the bounded wait)
      // keeps the optimistic outcome below AND installs an attempt-scoped
      // PENDING receipt for late-writer compensation. R18-2 records the
      // terminal entry outcome cell itself ([terminalOutcomeForCancel]) instead
      // of only the runId, so the background waiter observes that outcome
      // directly (null vs true vs false). R18-1 Stage 2: for non-final
      // (retryable intermediate) the host writes only a durable non-terminal
      // notice below (retry-like, family stays dangling); the system final is
      // NOT written optimistically but deferred to the waiter (SUCCESS → system
      // in order, FAILED → atomic partial+system batch). Hence systemOutcome
      // stays SKIPPED for both intents here (no optimistic system to track);
      // the waiter decides the deferred write by the terminal outcome. Final
      // intent keeps zero new rows (family already owns — or will own — its
      // final). The receipt is in-memory only (no cross-restart persistence —
      // kill falls back to backfill) and bounded by attempt (one entry per
      // cancelId, never removed). Existing D15-2 thresholds/predicates above
      // (submitTruthLock snapshot, commitCount >= 2 vs >= 1, CAS
      // exactly-once, seal degradation) are untouched; this only adds the
      // per-entry attribution the aggregated mix cannot see.
      val cancelReasoning = assistantReasoningOf(cancelId)
      if (terminalSubmitting && !acked && !terminalCommitted) {
        val fresh = CancelReceipt(
          cancelId = cancelId,
          logicalTurnId = logicalTurnId,
          suppressedFinal = suppressedFinal,
          cancelText = cancelText,
          cancelParent = cancelParent,
          cancelIndex = cancelIndex,
          cancelReasoning = cancelReasoning,
          terminalOutcome = terminalOutcomeForCancel,
          systemOutcome = AtomicReference(SystemMarkOutcome.SKIPPED),
        )
        if (cancelReceipts.putIfAbsent(cancelId, fresh) == null) {
          launchCancelReceiptWaiter(fresh)
        }
      }
      if (suppressed && suppressedFinal) {
        // D15-2 test seam (null in production): fires on branch entry. The
        // commit→ack grid pins the commit (afterCommitBeforeAckForTest) and
        // holds the ack, so its wait proves the COMMITTED-observed path;
        // the still-pending-stall sub-case also reaches this branch.
        onSuppressedFinalFlushEnteredForTest?.invoke()
        // Success / terminal-failure final already durable (COMMITTED/acked,
        // or SUBMITTING that settled as COMMITTED during the bounded wait
        // above, or still-pending stall keeping the optimistic zero below with
        // a PENDING receipt above): zero new rows, so the attempt keeps
        // exactly one assistant row. A SUBMITTING that settled as FAILED falls
        // through below to the normal final. Still rethrow the original
        // cancellation below.
        // Durable and non-cancellable: the host is cancelled but the session
        // writer is independent, so the record below still drains on close (or
        // is explicitly marked INTERRUPTED instead of silently dropped).
        //
        // #84: the terminal path already transferred this attempt's usage
        // into the sink buffer before the terminal entry was registered.
        // Persist it as a bare system row bounded here; FIFO admission keeps
        // it behind the terminal's slot whenever that terminal lands. On the
        // acked/COMMITTED sub-case no receipt exists; on the still-pending
        // SUBMITTING sub-case a PENDING receipt exists, but its revoke and
        // compensation paths do not own usage (dedup-safe via
        // trailingUsageWritten), so the host persists here either way. This
        // branch dropped the buffered usage silently before the flush.
        withContext(NonCancellable) {
          try {
            withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
              orderedTranscript.flushBufferedUsageBestEffort(cancelId)
              if (cancelId != logicalTurnId) {
                orderedTranscript.flushBufferedUsageBestEffort(logicalTurnId)
              }
            }
          } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
          }
        }
      } else {
        // S3-F2: bounded immediate cancel. The compensation runs in
        // NonCancellable (channel.send + ack.await); without a timeout a
        // wedged writer parks the cancelled host forever (cancel() itself
        // stays fast — it only flips state — but the host join behind
        // close()/flush never converges). Bound by
        // cancelCompensationTimeoutMs like the receipt waiter: timeout writes
        // no terminal row and relies on the PENDING receipt (deferred
        // notice-first path, waiter still owns the handoff) or on an
        // explicit markInterrupted (all other paths) so flushDurable stays
        // false until reopen INTERRUPTED backfill — never true-and-dangling.
        // The VM keeps reporting streaming/pending via
        // isLedgerStreamingOrPending (CANCELLED status + unsettled/sealed
        // receipts + pending runIds) until that backfill lands.
        //
        // Current (S3-F2): production 5s, tests inject 1s (see
        // cancelCompensationTimeoutMs). Target: split notice/terminal bounds.
        // #84 isolation: the authoritative usage transfer + persist runs
        // OUTSIDE the timed core unit below (before/after, each under its own
        // best-effort bound), so a stalled accounting write can never consume
        // the core budget and turn a committed cancel into a false
        // INTERRUPTED mark. Worst-case handler wall time is therefore
        // settle ≤ cancelSettleTimeoutMs (production 5s; only on the
        // SUBMITTING settle-wait sub-path) + transfer ≤ 1x best-effort bound +
        // core ≤ cancelCompensationTimeoutMs + persist ≤ 1x (non-deferred) +
        // drain ≤ 1x: ≤ 16s with the production 5s settle + 5s core, ≤ 14s on
        // the deferred path (no persist), ≤ 11s when the settle-wait does not
        // apply. The 6s test fence asserts the core bound, not this total.
        // The deferred
        // (notice-first) case is owned by the cancel receipt waiter, which
        // flushes after its deferred core write; a waiter that degrades to
        // SEALED without a deferred write intentionally drops the usage row
        // with the dangling family (reopen backfill owns the family, usage is
        // export-only data).
        val deferred = terminalSubmitting && !acked && !terminalCommitted
        withContext(NonCancellable) {
          try {
            // #84: transfer the controller's usage cell into the sink buffer
            // before the core unit (the persist below reads the buffer).
            try {
              withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
                flushAttemptUsage(cancelId)
              }
            } catch (e: Throwable) {
              if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            }
            val landingId = if (suppressed) logicalTurnId else cancelId
            val beforeLanded = orderedTranscript.coreSubmitTruth(landingId).commitCount
            val completed = withTimeoutOrNull(cancelCompensationTimeoutMs) {
              if (suppressed) {
                // R18-1 Stage 2: split already-durable vs still-pending.
                // Already-durable (acked || COMMITTED, no receipt): the
                // intermediate partial is safely on disk, so the system final
                // closes immediately (backoff-gap cancel, same as before).
                // Still-pending SUBMITTING only (receipt installed above):
                // notice-first (non-final only reaches here: suppressed &&
                // !suppressedFinal; suppressed && suppressedFinal returns zero
                // rows above). Writes a durable non-terminal notice (retry-like,
                // best-effort) so the family stays dangling until the waiter
                // appends the deferred system/batch. A kill or receipt timeout
                // before that deferred write leaves user(+partial)+notice
                // dangling (reopen INTERRUPTED takes over) instead of user+system
                // closed without the partial. Timeout / seal exits write no
                // terminal row here (notice only or nothing).
                if (!deferred) {
                  try {
                    orderedTranscript.onLogicalTurnCancelled(logicalTurnId, cancelId, cancelText, cancelIndex)
                  } catch (_: ClosedSendChannelException) {
                  } catch (sealFailure: CancellationException) {
                    if (!orderedTranscript.isSealed()) throw sealFailure
                  } catch (_: Exception) {
                  } catch (e: Throwable) {
                    // S3-F1 (Current): narrowed fatal in the cancel mark (sink
                    // already failed the ack + sealed); keep original cancel.
                    // VM-fatal always propagates.
                    if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
                  }
                } else {
                  try {
                    orderedTranscript.onTurnCancelRequested(logicalTurnId, cancelId, cancelIndex)
                  } catch (_: ClosedSendChannelException) {
                    // Sealed: notice dropped, family stays dangling for backfill.
                  } catch (sealFailure: CancellationException) {
                    if (!orderedTranscript.isSealed()) throw sealFailure
                    // Sealed fail-fast: notice dropped, backfill owns the row.
                  } catch (_: Exception) {
                    // Best-effort notice: a store failure here still leaves the
                    // family dangling (no premature close), waiter degrades to
                    // SEALED/backfill.
                  } catch (e: Throwable) {
                    // S3-F1 (Current): narrowed fatal in the notice (writer
                    // seals); the PENDING receipt above still owns the
                    // handoff. VM-fatal always propagates.
                    if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
                  }
                }
              } else {
                try {
                  orderedTranscript.onTurnCancelled(cancelId, cancelText, cancelParent, cancelIndex, cancelReasoning)
                } catch (_: ClosedSendChannelException) {
                  // Sealed after close(): explicit INTERRUPTED mark already recorded.
                } catch (sealFailure: CancellationException) {
                  if (!orderedTranscript.isSealed()) throw sealFailure
                  // Sealed fail-fast: INTERRUPTED already marked, keep original cancel.
                } catch (_: Exception) {
                  // Durable store failure for the cancel terminal itself: the sink
                  // already marked this runId INTERRUPTED; keep original cancel.
                } catch (e: Throwable) {
                  // S3-F1 (Current): narrowed fatal in the cancel terminal
                  // (sink already failed the ack + sealed); keep original
                  // cancel. VM-fatal always propagates.
                  if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
                }
              }
            }
            // #84: accounting persist outside the timed core unit. The
            // deferred (notice-first) case is owned by the cancel receipt
            // waiter (it flushes after its deferred core write); every other
            // case persists here, bounded, so a stalled usage write cannot
            // extend the core compensation budget.
            if (!deferred) {
              try {
                withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
                  orderedTranscript.flushBufferedUsageBestEffort(cancelId)
                  if (suppressed && cancelId != logicalTurnId) {
                    orderedTranscript.flushBufferedUsageBestEffort(logicalTurnId)
                  }
                }
              } catch (e: Throwable) {
                if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
              }
            }
            if (completed == null) {
              // Core-unit timeout. [markInterruptedIfNoNewCommit] re-checks the
              // landing truth under the sink's submitTruthLock after the delta
              // snapshot ([beforeLanded]), so a commit that landed just before
              // the timeout is never poisoned with a false INTERRUPTED mark
              // (and a commit racing the mark itself still clears it via
              // clearTimeoutMarkOnCommit). Deferred notice-first keeps the
              // PENDING receipt above (waiter → COMPENSATED/SEALED); every
              // other path marks INTERRUPTED only when nothing new landed, so
              // a dangling family stays visible to flushDurable/reopen instead
              // of reporting true-and-dangling.
              if (!deferred) {
                orderedTranscript.markInterruptedIfNoNewCommit(landingId, beforeLanded)
              }
            }
            // Best-effort drain of accounting writes without consuming the
            // compensation timeout above (core terminal already decided).
            try {
              withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
                orderedTranscript.flush()
              }
            } catch (e: Throwable) {
              if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
            }
          } catch (_: ClosedSendChannelException) {
            // Sealed after close(): explicit INTERRUPTED mark already recorded.
          } catch (sealFailure: CancellationException) {
            if (!orderedTranscript.isSealed()) throw sealFailure
          } catch (_: Exception) {
          } catch (e: Throwable) {
            // S3-F1 (Current): narrowed fatal around the bounded immediate
            // write; the sink already failed the ack + sealed, and the timeout
            // branch above (or the PENDING receipt) owns convergence. Never
            // replace the original cancellation with a new fatal, except
            // VM-fatal which always propagates (contrast OrderedTranscriptSink
            // fail+throw+seal: fail the ack, mark, then rethrow).
            if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
          }
        }
      }
      discardTextBuffer(cancelId)
      throw e
    } catch (e: Exception) {
      // Durable core-write failure (e.g. store IOException on started/
      // succeeded/failed): the sink already failed the ack loudly and marked
      // the runId INTERRUPTED. Project ERROR so the UI never sticks in
      // STREAMING, and never report success. Falls through to the normal
      // completion path below (same as a provider failure): empty queue keeps
      // ERROR, queued work may still promote.
      // The terminal path above never ran its discardTextBuffer, so drop only
      // the active attempt's buffer (ids are never reused; clear-all could
      // wipe a queued attempt's buffer under future multi-flight).
      discardTextBuffer(attemptRef.get())
      synchronized(lock) {
        activityRevision++
        _uiState.update {
          it.copy(status = ChatStatus.ERROR, error = sanitizeError(e.message ?: "transcript store failed"))
        }
      }
    } catch (e: Throwable) {
      // S3-F1 Error symmetry (Current): narrowed fatal Error from the terminal
      // write (e.g. store AssertionError/LinkageError via
      // onTurnSucceededWithReceipt, or a provider Error rethrown from
      // runTurnLoop) takes the same fail-closed path as Exception above —
      // sanitizeError → UI ERROR, then fall through to the promotion handoff
      // below (failPromotion semantics for queued work).
      // The sink writer already failed the ack explicitly and sealed like
      // teardown (see OrderedTranscriptSink writer catch Throwable:
      // fail+throw+seal), so the INTERRUPTED mark + seal are already owned
      // there; this only owns the UI projection. CancellationException never
      // lands here (caught first above), so CE semantics are unchanged.
      // VM-fatal (VirtualMachineError/ThreadDeath) never lands here either —
      // it always propagates (never swallowed like the sink's rethrow).
      if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
      // Same leak guard as the Exception path above: the terminal discard
      // never ran, so drop only the active attempt's buffer.
      discardTextBuffer(attemptRef.get())
      synchronized(lock) {
        activityRevision++
        _uiState.update {
          it.copy(status = ChatStatus.ERROR, error = sanitizeError(e.message ?: "transcript store failed"))
        }
      }
    }
    // Normal completion only: hand off at most one queued steer as a detached
    // follow-up turn, so send() returns after its own turn. The follow-up
    // gate runs BEFORE dequeue, so a deny leaves the queue intact (still
    // reclaimable via drainQueued).
    //
    // R5-2 durable promotion: the peek and the commit no longer share one
    // lock. Lock 1 only peeks the FIFO head and reserves the follow-up
    // identity ([promotionPending] + WAITING_STEERED already projected above:
    // no dequeue, no appendUser, no STREAMING); the user row is then durably
    // awaited OUTSIDE the lock with [ACCEPT_DURABLE_TIMEOUT_MS] (same
    // tri-state contract as direct admissions via [awaitUserRowDurable]);
    // lock 2 dequeues, appends, projects STREAMING and launches ONLY after
    // the ack. A process kill before the commit leaves the intent in the FIFO
    // (still reclaimable via drainQueued); a store failure retains the FIFO
    // with ERROR + queuedRecoveryRequired instead of losing dequeued text;
    // UNKNOWN retains the FIFO with a pending error and never resends; a
    // cancel/close win retains the FIFO with CANCELLED and never launches
    // nor writes another ledger row here.
    beforeCompletionQueueCheckForTest?.invoke()
    val hasQueued = synchronized(lock) {
      if (closed || self?.isActive != true) {
        if (inFlight === self) inFlight = null
        false
      } else if (steerQueue.isEmpty()) {
        activityRevision++
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
        false
      } else if (_uiState.value.queuedRecoveryRequired) {
        // Recovery-owned FIFO must not be auto-promoted, including a legacy
        // steer retained after its idle admission raced with recovery.
        activityRevision++
        if (inFlight === self) inFlight = null
        _uiState.update { state ->
          val status = if (state.status == ChatStatus.STREAMING || state.status == ChatStatus.WAITING_STEERED) {
            ChatStatus.ERROR
          } else {
            state.status
          }
          state.copy(
            status = status,
            error = if (status == ChatStatus.ERROR) {
              state.error ?: "queued turn recovery required before admission"
            } else {
              state.error
            },
            pendingSteerCount = steerQueue.size,
            queuedRecoveryRequired = steerQueue.isNotEmpty(),
          )
        }
        false
      } else {
        activityRevision++
        _uiState.update { it.copy(pendingSteerCount = steerQueue.size, status = ChatStatus.WAITING_STEERED) }
        true
      }
    }
    if (!hasQueued) {
      return
    }
    val decision = try {
      policy.evaluateFresh(POLICY_ACTION, POLICY_RESOURCE)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      failPromotion("policy evaluation failed", self)
      return
    }
    if (decision.verdict == Verdict.ASK) {
      failPromotion("approval required", self)
      return
    }
    if (decision.verdict == Verdict.DENY) {
      afterFollowUpDenyForTest?.invoke()
      failPromotion("denied by policy", self)
      return
    }
    afterFollowUpGateForTest?.invoke()
    // Lock 1: peek + reserve only (no dequeue, no appendUser, no STREAMING yet).
    val head: PendingSteer
    val reservation: PendingAdmission
    synchronized(lock) {
      if (
        closed || self?.isActive != true || _uiState.value.status == ChatStatus.CANCELLED ||
        _uiState.value.queuedRecoveryRequired
      ) {
        if (inFlight === self) inFlight = null
        return
      }
      val peeked = steerQueue.firstOrNull()
      if (peeked == null) {
        // drainQueued() won after the fresh gate. Mirror the empty-queue
        // transition while preserving a racing close()'s CANCELLED state.
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
        return
      }
      // Promotion re-validation (R4-4): peek the FIFO head with the fresh
      // zone before any dequeue. Oversize retains the FIFO via the existing
      // failPromotion path (no dequeue, no appendUser, no transcript, no
      // provider call). Reentrant: failPromotion takes the same lock.
      try {
        checkInputLengthOrThrow(peeked.text)
      } catch (_: InputTooLongException) {
        failPromotion("input too long", self)
        return
      }
      // Reserve only: mint the follow-up identity and hold the single-flight
      // slot in the promotion-scoped field (NOT pendingAdmission — close()
      // drains only direct orphans, and the direct cancel flag stays direct).
      val followLogicalId = newId()
      val followAttemptRef = AtomicReference(followLogicalId)
      val followAttemptIndexRef = AtomicInteger(0)
      reservation = PendingAdmission(
        followLogicalId, peeked.text, peeked.images, followAttemptRef, followAttemptIndexRef,
      )
      promotionPending = reservation
      head = peeked
    }
    beforePromotionAwaitForTest?.invoke()
    // Durable gate outside the lock (suspend must never hold the intrinsic
    // monitor). STREAMING stays behind this ack: a kill before the commit
    // below leaves the intent in the FIFO, reclaimable via drainQueued.
    // R6-2: the await no longer marks INTERRUPTED on cancel-after-send
    // (sent == true) — the ghost may still late-commit as an orphan; the FIFO
    // stays retained via failPromotion below for proven-not-admitted only.
    // R8-1: the CE catch below consults the same attempt proof as timeouts —
    // proven never-attempted (!sent && !attempted) stays fail-closed (FIFO
    // retained, ERROR + reclaimable); fuzzy (attempted, regardless of sent —
    // a CE racing a suspended send, or sent == true) retains promotion UNKNOWN
    // ownership like the timeout-UNKNOWN path (settle/claim under the same id).
    // R8-6: the attempt proof travels alongside `promotionSent` so the shared
    // await tri-states timeouts (proven NOT_ADMITTED vs fuzzy UNKNOWN) and the
    // CE catch (proven failPromotion vs fuzzy UNKNOWN).
    val promotionSent = AtomicBoolean(false)
    val promotionAttempted = AtomicBoolean(false)
    try {
      when (awaitUserRowDurable(reservation.logicalTurnId, head.text, promotionSent, promotionAttempted)) {
        DurableAdmission.ACKED -> Unit
        DurableAdmission.NOT_ADMITTED -> {
          synchronized(lock) {
            if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
          }
          failPromotion("transcript store unavailable", self)
          return
        }
        DurableAdmission.UNKNOWN -> {
          // R6-1: timeout-after-send with the ghost possibly late-committing.
          // Same contract as direct UNKNOWN: retain ownership under the SAME
          // logical id (reservation moves promotionPending → unknownAdmission,
          // head opId kept for the same-opId resend block + Lock-2 dequeue
          // match), keep the FIFO head queued with pending (NOT recovery) and
          // settle in the background (claim = Lock-2 commit, convert =
          // unavailable + reclaimable). FIFO is not reclaimable until settle.
          // Round 10 M1: sealed after the await — never rebuild UNKNOWN
          // (Invariant 5); fail closed via failPromotion instead.
          if (!enterUnknownAdmission(reservation, null, head.opId, isPromotion = true, host = self)) {
            synchronized(lock) {
              if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
            }
            failPromotion("controller is closed", self)
            return
          }
          return
        }
      }
    } catch (e: CancellationException) {
      // R8-1: consult the attempt proof like the direct paths and the timeout
      // branch above. Proven never-attempted (!sent && !attempted) stays
      // fail-closed: retain the FIFO for explicit recovery (failPromotion keeps
      // closed/CANCELLED projections untouched, only clears the finished host
      // slot). Fuzzy (attempted, regardless of sent — a CE racing a suspended
      // send, or sent == true) retains UNKNOWN ownership under the SAME id
      // (R6-1 background settle/claim, drain + same-opId blocked,
      // queuedRecoveryRequired=false). Seal races surfaced as
      // TranscriptDurabilityException inside the await and never land here.
      if (!promotionSent.get() && !promotionAttempted.get()) {
        synchronized(lock) {
          if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
        }
        failPromotion("turn admission cancelled", self)
        return
      }
      // Round 10 M1: sealed after the await — never rebuild UNKNOWN.
      if (!enterUnknownAdmission(reservation, null, head.opId, isPromotion = true, host = self)) {
        synchronized(lock) {
          if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
        }
        failPromotion("controller is closed", self)
        return
      }
      return
    } catch (_: TranscriptDurabilityException) {
      // Fast store failure / seal race before any ack: never dequeued, so
      // the FIFO is retained for explicit recovery (fail-closed like direct).
      synchronized(lock) {
        if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
      }
      failPromotion("transcript store unavailable", self)
      return
    }
    // Lock 2: commit. Dequeue + appendUser + STREAMING + launch ONLY now that
    // the user row is durable ([runTurnLoop] skips the rewrite via
    // [durableUserRows], so exactly one user row exists per logical turn).
    synchronized(lock) {
      if (
        closed || self?.isActive != true || _uiState.value.status == ChatStatus.CANCELLED ||
        _uiState.value.queuedRecoveryRequired
      ) {
        // Cancel/close won after the ack: never launch and never write another
        // ledger row here (the acked user row stays as-is; an explicit retry
        // mints a fresh id). Cancel wins retain the FIFO for explicit
        // recovery and keep the CANCELLED projection; close wins drop the FIFO
        // per the drain-before-close lifecycle contract (an already-acked row
        // then survives as an orphan, marked INTERRUPTED on reopen).
        if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
        if (inFlight === self) inFlight = null
        return
      }
      val currentHead = steerQueue.firstOrNull()
      if (currentHead == null || currentHead.opId != head.opId || currentHead.text != head.text) {
        // Round 10 H1: drainQueued() can no longer steal the head mid-await
        // (it throws Pending while promotionPending holds), so this is only the
        // close-cleared edge: the drained/closed caller owns the outcome now —
        // never dequeue its successor by mistake. Mirror the empty-queue
        // transition; the acked user row stays as-is (orphan on close).
        if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
        if (inFlight === self) inFlight = null
        _uiState.update { s ->
          if (s.status == ChatStatus.STREAMING || s.status == ChatStatus.WAITING_STEERED) {
            s.copy(status = ChatStatus.IDLE)
          } else {
            s
          }
        }
        return
      }
      steerQueue.removeFirstOrNull()
      activityRevision++
      if (promotionPending?.logicalTurnId == reservation.logicalTurnId) promotionPending = null
      appendUser(currentHead.text, currentHead.opId)
      _uiState.update { it.copy(status = ChatStatus.STREAMING, error = null, pendingSteerCount = steerQueue.size) }
      // Follow-up owns the reserved logical id, also created before launch.
      pendingAdmission = PendingAdmission(
        reservation.logicalTurnId, currentHead.text, currentHead.images,
        reservation.attemptRef, reservation.attemptIndexRef,
      )
      val follow = scope.launch { hostedTurn(currentHead.text, currentHead.images, reservation.logicalTurnId, reservation.attemptRef, reservation.attemptIndexRef) }
      inFlight = follow
      val followLogicalId = reservation.logicalTurnId
      follow.invokeOnCompletion {
        synchronized(lock) {
          if (inFlight === follow) inFlight = null
          val pending = pendingAdmission
          if (pending?.logicalTurnId == followLogicalId) {
            drainOrphanLocked(pending)
          }
        }
      }
    }
  }

  /** Caller has completed the promotion gate; fail closed without consuming the FIFO. */
  private fun failPromotion(error: String, host: Job?) {
    synchronized(lock) {
      // A concurrent close/cancel owns the terminal projection. Otherwise
      // expose an idle error so the VM can explicitly recover the retained FIFO.
      if (closed || (host != null && !host.isActive) || _uiState.value.status == ChatStatus.CANCELLED) {
        if (inFlight === host) inFlight = null
        return
      }
      if (inFlight === host) inFlight = null
      activityRevision++
      _uiState.update {
        it.copy(
          status = ChatStatus.ERROR,
          error = error,
          pendingSteerCount = steerQueue.size,
          queuedRecoveryRequired = steerQueue.isNotEmpty(),
        )
      }
    }
  }

  /**
   * Best-effort ordered notice (tool / usage / retry): the sink throws
   * [ClosedSendChannelException] on the post-[close] seal race instead of
   * succeeding silently, and that race is owned here — the notice is dropped
   * explicitly (tool/usage notices have no drainQueued recovery path, and
   * `close()` clears the FIFO). Cancellation of the caller still propagates.
   */
  private suspend fun writeNotice(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: ClosedSendChannelException) {
      // Sealed after close(): explicit drop, no recovery path.
    }
  }

  /**
   * Stage C family linkage: the first attempt reuses the logical id (parent
   * stays null for byte-compat with pre-C rows); later attempts get
   * `parentRunId=logicalTurnId`. Retry notices already bind the logical id.
   */
  private fun parentFor(attemptRunId: String, logicalTurnId: String): String? =
    if (attemptRunId == logicalTurnId) null else logicalTurnId

  private suspend fun runTurnLoop(
    text: String,
    images: List<ChatImageRef>,
    logicalTurnId: String,
    attemptRef: AtomicReference<String>,
    attemptIndexRef: AtomicInteger,
  ) {
    // One logical turn owns exactly one user row (logicalTurnId, created
    // before launch). Stage 6: direct admissions ([startTurn]/[startOrEnqueue])
    // already durably acked it before returning Started (and recorded
    // [durableUserRows]), so the host skips it and starts from the first
    // provider attempt; follow-up promotions durably await it during the
    // two-lock handoff (R5-2) and likewise skip the rewrite here. Retries add attempt records bound to that id and start
    // a new assistant block each; they never emit another user row. Direct
    // suspend calls (no fire-and-forget hop), so no snapshot copies are
    // needed: each write completes in call order. attemptRef always holds the
    // live attempt id (sleeper gaps keep the just-failed id until the next id
    // is minted), so the hostedTurn cancel handler never needs the global
    // latest assistant.
    // Stage C: every attempt terminal (success, terminal failure, retried
    // partial, cancel) carries parentRunId/attemptIndex so the logical family
    // (parentRunId ?: runId) owns the full attempt chain; retry notices stay
    // bound to the logical id.
    if (!durableUserRows.contains(logicalTurnId)) {
      orderedTranscript.onTurnStarted(logicalTurnId, text)
      durableUserRows.add(logicalTurnId)
    }
    // Authoritative usage is per-turn (#84): a turn that emits no Usage must
    // not inherit the previous turn's billing.
    lastUsage = null
    var attempt = 0
    var attemptRunId = logicalTurnId
    // P2 slice budget: at most one tool round per turn, including retries.
    // A retryable continuation failure starts a fresh attempt whose request
    // drops the failed partial fragment but re-attaches the executed pair
    // (tools=[] on the wire) — without this flag the model could re-emit the
    // call and the slice would execute it a second time.
    var sliceExecutedThisTurn = false
    // The one executed round, frozen at execution time. A post-execution
    // continuation failure re-attaches this pair on the retry request so the
    // provider still sees the executed result instead of answering blind.
    // Frozen (never re-derived from the live per-attempt accumulation list)
    // so record-only re-emitted calls on retries cannot desync the
    // calls/results sizes that `continuationMessages` requires. The
    // `executedSliceResults.isNotEmpty()` loop-top guard also fails safe to
    // the old pure-chat request shape in the (cancel-only) window where
    // execute() throws before returning — keep the freeze AFTER execute().
    var executedSliceCalls: List<dev.librepocket.provider.ToolCall> = emptyList()
    var executedSliceResults: List<SafeToolSlice.ToolResult> = emptyList()
    while (true) {
      appendAssistantPlaceholder(attemptRunId)
      var failed: StreamEvent.Failed? = null
      var done = false
      // P2 slice: completed model calls for the single wired tool, executed
      // after a successful collect (same turn). Calls for any other tool stay
      // records-only (marker + transcript, never executed).
      val sliceCalls = mutableListOf<dev.librepocket.provider.ToolCall>()
      // Shared aggregation state, fresh per attempt (see ToolAggState).
      val toolState = ToolAggState()
      try {
        // Request build sits inside the attempt try so a builder failure
        // (notably the continuation budget fail-close below) becomes a
        // typed terminal, never an escaped turn crash.
        val request =
          if (sliceExecutedThisTurn && executedSliceResults.isNotEmpty() && toolSlice != null) {
            // Post-execution retry: same shape as the first continuation —
            // re-attach the frozen executed pair with `tools=[]`. The provider
            // sees the already-executed result; no re-execution, no second
            // tools offer.
            buildToolContinuation(images, executedSliceCalls, executedSliceResults, toolSlice)
          } else {
            buildRequest(images, allowTools = !sliceExecutedThisTurn)
          }
        val streamOutcome = collectStream(
          request,
          attemptRunId,
          toolState,
          onWiredToolDone = { call -> sliceCalls.add(call) },
        )
        failed = streamOutcome.failed
        done = streamOutcome.done
        // P2 slice (default-off): same-turn tool continuation for the single
        // wired tool. Cancel between collect and execution aborts here with
        // zero further effects (ensureActive + the loop's own checks throw
        // CancellationException, which the catch below rethrows). A failed
        // continuation falls into the existing retry/terminal logic below.
        // Budget: exactly one tool round per turn — continuation ToolDones
        // are recorded, never executed (see collectToolContinuation), and a
        // retried attempt never re-executes (sliceExecutedThisTurn): the
        // executed pair is frozen at execution time and re-attached on the
        // retry request with tools=[], so the provider keeps seeing the
        // executed result. A call newly re-emitted on a retried attempt is
        // recorded-only and stays out of that re-attached pair, pinned by
        // test.
        val loop = toolSlice
        if (failed == null && done && loop != null && sliceCalls.isNotEmpty() && !sliceExecutedThisTurn) {
          coroutineContext.ensureActive()
          // Mark before executing: even an unexpected non-cancel throw below
          // must not grant a second round on retry (fail-closed direction).
          sliceExecutedThisTurn = true
          val sliceResults = loop.execute(sliceCalls)
          executedSliceCalls = sliceCalls.toList()
          executedSliceResults = sliceResults
          for (r in sliceResults) {
            coroutineContext.ensureActive()
            // Length-prefixed marker: resultJson embeds `]` (symbols array)
            // and model-controlled text, so a bare `]` terminator would be
            // ambiguous. Format: [tool-result:<name>:<len>:<json>], where
            // <len> is String.length (UTF-16 units, matching substring
            // slicing). Sanitize the name so model-controlled call names cannot
            // inject framing/colons into the persisted marker.
            val safeName = r.name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.ifBlank { "tool" }
            appendAssistantText(attemptRunId, "\n[tool-result:$safeName:${r.resultJson.length}:${r.resultJson}]")
          }
          coroutineContext.ensureActive()
          val textDeltasBeforeContinuation = streamedTextDeltaCount
          val contRequest = buildToolContinuation(images, sliceCalls, sliceResults, loop)
          val contOutcome =
            collectToolContinuation(contRequest, attemptRunId, toolState.totalChars, toolState.visibleTextChars)
          toolState.totalChars = contOutcome.totalToolAggChars
          toolState.visibleTextChars = contOutcome.visibleTextChars
          if (contOutcome.failed != null) {
            failed = contOutcome.failed
          } else if (streamedTextDeltaCount == textDeltasBeforeContinuation) {
            // A tool round that yields no final answer is not a complete
            // success; force retry/terminal handling instead of silently
            // finalizing an empty/partial fragment.
            failed = StreamEvent.Failed("incomplete tool continuation", retryable = true)
          }
        }
        // Catch parity with the pre-extraction loop: the slice block only
        // throws CancellationException in practice (typed denials cover the
        // rest), but a non-cancel throw here still becomes a terminal (not a
        // leaked crash) exactly like the old inline catches.
      } catch (e: ContinuationOverBudgetException) {
        // Fail closed (P2 continuation budget): the serialized continuation
        // payload cannot fit MAX_REQUEST_CHARS even after the full
        // history-prefix trim. Within one logical turn history only grows
        // (a retry cannot shrink the request) — terminal without shipping
        // an over-cap request, and never a second tool round.
        failed = StreamEvent.Failed(
          e.message ?: "continuation request over budget",
          retryable = false,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed = StreamEvent.Failed(e.message ?: "provider error", retryable = true)
        if (!shouldDiscardPendingTools(failed)) {
          try {
            flushPendingTools(toolState, attemptRunId)
          } catch (pf: ProviderFailure) {
            failed = StreamEvent.Failed(
              pf.message ?: "provider error",
              retryable = false,
              code = pf.code?.name ?: inferAggFailureCodeName(pf.message),
            )
          }
        }
      } catch (e: Throwable) {
        if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
        coroutineContext.ensureActive()
        failed = StreamEvent.Failed(
          e.message ?: "provider error",
          retryable = false,
          code = inferAggFailureCodeName(e.message) ?: ProviderFailureCode.UNKNOWN_RUNTIME.name,
        )
      }
      val failure = failed
      if (failure == null) {
        // Single authoritative usage notice (#84) precedes the terminal, so
        // the sink observes exactly one usage per attempt in order.
        flushAttemptUsage(attemptRunId)
        // Flush the text buffer (#83) so the UI copy is exact before the
        // snapshot below; the snapshot itself also reads the buffer.
        flushAssistantText(attemptRunId)
        // Done arrived and post-collection flush succeeded: the fragment is
        // genuinely settled. Finalize here (not at Done receipt) so a flush
        // budget failure keeps isPartial=true per the failed-fragment
        // contract below.
        finalizeAssistant(attemptRunId)
        // Snapshot text: assistantTextOf reads the buffer (exact, including
        // unflushed tail) with the UI copy as fallback.
        val successText = assistantTextOf(attemptRunId)
        val successReasoning = assistantReasoningOf(attemptRunId)
        // D15-2 + R18-2: record the per-entry terminal receipt BEFORE the
        // write is admitted, so a cancel racing the commit→ack window still
        // sees a final terminal AND the shared outcome cell for direct fate
        // observation (null vs true vs false).
        val successReceipt = CompletableDeferred<Unit>()
        val successOutcome = AtomicReference<Boolean?>(null)
        attemptTerminalFinal[attemptRunId] = TerminalEntry(true, successReceipt, successOutcome)
        orderedTranscript.onTurnSucceededWithReceipt(
          attemptRunId, successText,
          parentFor(attemptRunId, logicalTurnId), attempt, successReasoning,
          successReceipt, successOutcome,
        )
        terminalizedAttempts.add(attemptRunId)
        withContext(NonCancellable) {
          try {
            // Bounded end-to-end (admission + drain): a wedged best-effort
            // usage write must not park the host past its own timeout.
            withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
              orderedTranscript.flushBufferedUsageBestEffort(attemptRunId)
              orderedTranscript.flush()
            }
          } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
          }
        }
        try {
          afterTerminalAckForTest?.invoke(attemptRunId)
        } catch (e: Throwable) {
          if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
        }
        discardTextBuffer(attemptRunId)
        return
      }
      // Keep the failed fragment as-is (isPartial=true); the retry below
      // starts a brand-new assistant block with a brand-new runId. Phase 3
      // persists the failed partial structurally: the ledger row keeps the
      // partial text with isPartial=1 plus the sanitized reason (parent-aware
      // onTurnFailed), while UI memory keeps the same fragment flagged.
      // Defensive invariant: a resource-exhausted terminal never retries,
      // even if a legacy/custom path marks it retryable (poisoned streams
      // must not burn the retry budget).
      if (!failure.retryable || isResourceExhausted(failure) || attempt >= retryConfig.maxRetries) {
        val clean = sanitizeError(failure.message)
        synchronized(lock) {
          activityRevision++
          _uiState.update { it.copy(status = ChatStatus.ERROR, error = clean) }
        }
        // Single authoritative usage + exact text flush (#84/#83) precede
        // the terminal, like the success path above. A TEXT_TOO_LARGE
        // overflow lands here (typed non-retryable): the accepted fragment
        // stays partial with the typed reason, never retried.
        flushAttemptUsage(attemptRunId)
        flushAssistantText(attemptRunId)
        // D15-2 + R18-2: per-entry receipt before admission (final, like success).
        // R18-3: the failed partial carries the live reasoning flag so the
        // ledger marker survives reopen (same contract as success/cancel).
        val terminalReceipt = CompletableDeferred<Unit>()
        val terminalOutcome = AtomicReference<Boolean?>(null)
        attemptTerminalFinal[attemptRunId] = TerminalEntry(true, terminalReceipt, terminalOutcome)
        orderedTranscript.onTurnFailedWithReceipt(
          attemptRunId, assistantTextOf(attemptRunId), clean,
          parentFor(attemptRunId, logicalTurnId), attempt, true,
          terminalReceipt, terminalOutcome,
          assistantReasoningOf(attemptRunId),
        )
        terminalizedAttempts.add(attemptRunId)
        withContext(NonCancellable) {
          try {
            // Bounded end-to-end (admission + drain): a wedged best-effort
            // usage write must not park the host past its own timeout.
            withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
              orderedTranscript.flushBufferedUsageBestEffort(attemptRunId)
              orderedTranscript.flush()
            }
          } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
          }
        }
        try {
          afterTerminalAckForTest?.invoke(attemptRunId)
        } catch (e: Throwable) {
          if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
        }
        discardTextBuffer(attemptRunId)
        return
      }
      // Stage C: persist the retried partial BEFORE the retry notice, so a
      // retryable failure's intermediate fragment survives reopen/export even
      // when the next attempt later succeeds. Same durable form as the
      // terminal failure above (isPartial=1 + sanitized reason), bound to the
      // same logical family. Stage F: intermediate partials are non-final
      // (isFinal=false) so the family still dangles until a final row lands.
      val retriedClean = sanitizeError(failure.message)
      // Single authoritative usage + exact text flush (#84/#83) precede the
      // intermediate terminal, like the paths above.
      flushAttemptUsage(attemptRunId)
      flushAssistantText(attemptRunId)
      // D15-2 + R18-2: per-entry receipt before admission (non-final: the
      // family still dangles until a final row lands, so a suppressing cancel
      // writes the system mark instead of zero rows).
      // R18-3: same reasoning flag as the terminal path — a compensation
      // with a marker but an intermediate partial without one would reopen
      // inconsistently.
      val intermediateReceipt = CompletableDeferred<Unit>()
      val intermediateOutcome = AtomicReference<Boolean?>(null)
      attemptTerminalFinal[attemptRunId] = TerminalEntry(false, intermediateReceipt, intermediateOutcome)
      orderedTranscript.onTurnFailedWithReceipt(
        attemptRunId, assistantTextOf(attemptRunId), retriedClean,
        parentFor(attemptRunId, logicalTurnId), attempt, false,
        intermediateReceipt, intermediateOutcome,
        assistantReasoningOf(attemptRunId),
      )
      terminalizedAttempts.add(attemptRunId)
      withContext(NonCancellable) {
        try {
          // Bounded end-to-end (admission + drain): a wedged best-effort
          // usage write must not park the host past its own timeout.
          withTimeoutOrNull(OrderedTranscriptSink.BEST_EFFORT_TIMEOUT_MS) {
            orderedTranscript.flushBufferedUsageBestEffort(attemptRunId)
            orderedTranscript.flush()
          }
        } catch (e: Throwable) {
          if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
        }
      }
      try {
        afterTerminalAckForTest?.invoke(attemptRunId)
      } catch (e: Throwable) {
        if (e is CancellationException || e is VirtualMachineError || e is ThreadDeath) throw e
      }
      discardTextBuffer(attemptRunId)
      attempt += 1
      val waitMs = retryConfig.delayForRetry(attempt)
      // Bound to the logical turn (single user row), not to the failed
      // attempt: retries never re-emit onTurnStarted.
      writeNotice { orderedTranscript.onTurnRetried(logicalTurnId, attempt, retryConfig.maxRetries, waitMs) }
      try {
        sleeper(waitMs)
      } catch (e: CancellationException) {
        // A cancel during the backoff gap still attributes to the just-failed
        // attempt (its partial is already durable above); the new id is minted
        // only after the sleep, so rethrow without minting.
        throw e
      }
      // Cancellation-cooperative gate (review-88-S3 cancel-first parity): a
      // gate-injected sleeper (or delay(<=0) / a test sleeper whose fast path
      // returns without checking cancellation) can return with the host
      // already cancelled. Minting/publishing the retry id here would
      // attribute the cancel terminal to a placeholder with empty text (the
      // just-failed attempt's partial durably non-final) instead of the
      // just-failed attempt the backoff-gap contract above promises.
      // Re-check before the mint so a cancel delivered up to this point still
      // attributes to the failed attempt id (later arrivals race the mint;
      // see the publish note below).
      coroutineContext.ensureActive()
      attemptRunId = newId()
      // Publish before the next placeholder: a cancel in the gap still sees
      // the just-failed id only until the new id is minted here. The index
      // travels with the id so the cancel terminal keeps the family linkage.
      attemptRef.set(attemptRunId)
      attemptIndexRef.set(attempt)
    }
  }

  /**
   * Shared per-response tool-aggregation state for stream collects.
   * Per-attempt whole-round tool total + distinct-index set (Finding #1):
   * many small ToolDeltas across many indexes must still trip the shared
   * budget. Monotonic within the attempt (ToolDone arrivals never
   * decrement it); retries start a fresh attempt with fresh counters.
   * `seenCallIds` additionally dedups the execution path by call id (a
   * duplicate id at two indexes executes once; later occurrences stay
   * records-only so calls/results pairing stays unique).
   *
   * Per-attempt visible-text budget (#94): text/reasoning deltas check
   * [requireTurnTextBudget] against [visibleTextChars] before buffering (the
   * tripping delta is never retained and never counted); tool markers are
   * excluded (their own budgets live in the tool-agg fields above). The
   * budget accumulates across the tool-continuation rounds of one attempt
   * (seeded by [collectToolContinuation]) — retries start a fresh attempt
   * with fresh counters.
   */
  private class ToolAggState(
    var totalChars: Int = 0,
    var visibleTextChars: Int = 0,
    val distinctIndexes: MutableSet<Int> = mutableSetOf(),
    val argText: MutableMap<Int, StringBuilder> = mutableMapOf(),
    val idText: MutableMap<Int, StringBuilder> = mutableMapOf(),
    val nameText: MutableMap<Int, StringBuilder> = mutableMapOf(),
    val doneSeen: MutableSet<Int> = mutableSetOf(),
    val seenCallIds: MutableSet<String> = mutableSetOf(),
  )

  /** Outcome of one shared stream collect: terminal failure (if any) plus Done. */
  private data class StreamOutcome(val failed: StreamEvent.Failed?, val done: Boolean)

  private suspend fun flushPendingTools(state: ToolAggState, attemptRunId: String) {
    coroutineContext.ensureActive()
    // Atomicity: preflight every pending index (including the synthetic
    // id/name defaults, which delta billing never sees) before any
    // marker/transcript side effect, so a later index overflowing cannot
    // leave an earlier index committed. Already-completed ToolDone
    // records are skipped in both passes (written inline at collection).
    class PendingTool(val index: Int, val id: String, val name: String, val args: String)
    val pending = state.argText.mapNotNull { (index, args) ->
      if (index in state.doneSeen) null
      else {
        val id = state.idText[index]?.toString().orEmpty().ifEmpty { "call_pending_$index" }
        val name = state.nameText[index]?.toString().orEmpty().ifEmpty { "pending" }
        PendingTool(index, id, name, args.toString())
      }
    }
    for (p in pending) {
      coroutineContext.ensureActive()
      // Terminal guard (Finding #4): whole pending ToolDone must fit
      // the per-call budget before any marker/transcript side effect.
      requireToolAggBudget(0, p.id.length + p.name.length + p.args.length)
    }
    // Round-total guard: synthetic pending id/name defaults were never
    // delta-billed, so bill their growth (projected minus already-billed,
    // floor 0) against the per-attempt total before any side effect.
    var totalGrowth = 0
    for (p in pending) {
      coroutineContext.ensureActive()
      val billed =
        p.args.length +
          (state.idText[p.index]?.length ?: 0) +
          (state.nameText[p.index]?.length ?: 0)
      totalGrowth += (p.id.length + p.name.length + p.args.length - billed).coerceAtLeast(0)
    }
    requireTotalToolAggBudget(state.totalChars, totalGrowth)
    state.totalChars += totalGrowth
    for (p in pending) {
      coroutineContext.ensureActive()
      val safeName = p.name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.ifBlank { "tool" }
      // Redact THEN scrub (full parity with noteToolDone): the flushed
      // pending marker rides assistant text, which buildRequest re-sends to
      // the provider on every later attempt/turn, so the crumb args take the
      // same structural redaction posture as the continuation echo. Scrub
      // AFTER redaction — a valid echo legitimately contains `[`/`]`. The
      // CR/LF fold is belt-and-braces only: post-redaction echo output
      // carries no raw control chars (the JSON serializer escapes them; a
      // parse-fail collapses to the fixed receipt), so it is inert today but
      // guards future edits to the echo path. Redact-then-scrub; audit stays
      // raw by design.
      val safeArgs = SafeToolSlice.redactedArgsEcho(p.args)
        .replace("[", "［").replace("]", "］").replace("\n", " ")
        .replace("\r", " ")
      appendAssistantText(attemptRunId, "\n[tool:$safeName $safeArgs]")
      writeNotice { orderedTranscript.onToolDone(attemptRunId, p.index, p.id, p.name, p.args) }
      state.doneSeen.add(p.index)
    }
  }

  private fun noteToolDelta(state: ToolAggState, event: StreamEvent.ToolDelta) {
    // Fail-closed per-call aggregation budget: a hostile stream of
    // tiny fragments (args or id/name chunks) must not grow memory
    // without bound. Throws typed non-retryable TOOL_ARGS_TOO_LARGE
    // (never silent truncation); the collect catch skips the pending
    // flush so poison-adjacent fragments are never recorded.
    // The same budget additionally guards the per-attempt total
    // across all indexes, plus a distinct-index cap — all one
    // failure semantics.
    // Finding #5 first-chunk-wins: id/name winners are the first
    // non-empty chunk only (same rule as ChatCompletionsMapper);
    // repeats contribute 0 and are never appended. Args stay
    // full-volume.
    val bufferedArgs = state.argText[event.toolIndex]?.length ?: 0
    val bufferedId = state.idText[event.toolIndex]?.length ?: 0
    val bufferedName = state.nameText[event.toolIndex]?.length ?: 0
    val idIncoming =
      if (event.idChunk != null && event.idChunk.isNotEmpty() && bufferedId == 0) {
        event.idChunk.length
      } else {
        0
      }
    val nameIncoming =
      if (event.nameChunk != null && event.nameChunk.isNotEmpty() && bufferedName == 0) {
        event.nameChunk.length
      } else {
        0
      }
    val toolIncoming = event.argsChunk.length + idIncoming + nameIncoming
    if (!state.distinctIndexes.contains(event.toolIndex)) {
      requireToolCountBudget(state.distinctIndexes.size + 1)
      state.distinctIndexes.add(event.toolIndex)
    }
    requireToolAggBudget(
      bufferedArgs + bufferedId + bufferedName,
      toolIncoming,
    )
    requireTotalToolAggBudget(state.totalChars, toolIncoming)
    state.argText.getOrPut(event.toolIndex) { StringBuilder() }.append(event.argsChunk)
    if (idIncoming > 0) {
      state.idText.getOrPut(event.toolIndex) { StringBuilder() }.append(event.idChunk!!)
    }
    if (nameIncoming > 0) {
      state.nameText.getOrPut(event.toolIndex) { StringBuilder() }.append(event.nameChunk!!)
    }
    state.totalChars += toolIncoming
  }

  private suspend fun noteToolDone(
    state: ToolAggState,
    event: StreamEvent.ToolDone,
    attemptRunId: String,
    onWiredToolDone: ((dev.librepocket.provider.ToolCall) -> Unit)?,
  ) {
    // Terminal-only guard (Stage E): a direct ToolDone that never
    // went through delta budgeting must still fit ALL budgets
    // before any marker/transcript side effect. Per-call first,
    // then distinct-index cap (BEFORE-insert), then round-total
    // growth (candidate minus already-buffered delta bytes, floor
    // 0 — delta-then-done never double-bills). Throws typed
    // AGG_TOO_LARGE; the collect catch turns it into the typed
    // terminal with no marker and no transcript record for the
    // tripping index (already-emitted ToolDones stay).
    // Duplicate ToolDone for an already-completed index is ignored
    // outright: no second billing, no second record.
    if (event.toolIndex in state.doneSeen) return
    val toolDoneCandidate =
      event.id.length + event.name.length + event.argumentsJson.length
    requireToolAggBudget(0, toolDoneCandidate)
    if (event.toolIndex !in state.distinctIndexes) {
      requireToolCountBudget(state.distinctIndexes.size + 1)
    }
    val toolDoneBuffered =
      (state.argText[event.toolIndex]?.length ?: 0) +
        (state.idText[event.toolIndex]?.length ?: 0) +
        (state.nameText[event.toolIndex]?.length ?: 0)
    val toolDoneGrowth = (toolDoneCandidate - toolDoneBuffered).coerceAtLeast(0)
    requireTotalToolAggBudget(state.totalChars, toolDoneGrowth)
    state.distinctIndexes.add(event.toolIndex)
    state.totalChars += toolDoneGrowth
    state.doneSeen.add(event.toolIndex)
    val safeName = event.name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.ifBlank { "tool" }
    // Redact THEN scrub: the inline marker rides assistant text, which
    // buildRequest re-sends to the provider on every later attempt/turn, so
    // the args take the same structural redaction posture as the continuation
    // echo (SafeToolSlice.redactedArgsEcho — parse/size/depth failures already
    // collapse to a fixed receipt). Scrubbing happens AFTER redaction, because
    // a valid echo legitimately contains `[`/`]` array framing. The CR/LF
    // fold below is belt-and-braces only: post-redaction echo output carries
    // no raw control chars (the JSON serializer escapes them; the fallback
    // receipt is fixed), so it is inert today but guards future edits to the
    // echo path. The durable transcript audit record below deliberately keeps
    // the RAW args (audit truth, never provider-visible text).
    val safeArgs = SafeToolSlice.redactedArgsEcho(event.argumentsJson)
      .replace("[", "［").replace("]", "］").replace("\n", " ").replace("\r", " ")
    appendAssistantText(attemptRunId, "\n[tool:$safeName $safeArgs]")
    writeNotice {
      orderedTranscript.onToolDone(attemptRunId, event.toolIndex, event.id, event.name, event.argumentsJson)
    }
    // P2 slice: only the single wired tool proceeds to execution
    // (after a successful collect, same turn); everything else stays
    // records-only even when a loop is attached. A null callback means
    // record-only (tool continuation second round).
    // Execution-path id intake hardening:
    // (a) a duplicate call id within one response executes only once —
    //     later occurrences stay records-only so calls/results pairing
    //     stays unique (dedup by wire id, not index);
    // (b) a blank id synthesizes the deterministic per-index wire id
    //     (`call_${event.toolIndex}`): the
    //     internal pairing validator (ProviderJson TOOL_CALL_MISSING_ID /
    //     TOOL_CALL_DUPLICATE_ID, non-retryable) and providers alike fail
    //     closed on an empty tool-call id. The transcript record above
    //     deliberately keeps the RAW event id for audit truth.
    val wireId = event.id.ifBlank { "call_${event.toolIndex}" }
    if (
      onWiredToolDone != null &&
      toolSlice != null &&
      event.name == SafeToolSlice.TOOL_NAME &&
      wireId !in state.seenCallIds
    ) {
      state.seenCallIds.add(wireId)
      onWiredToolDone(
        dev.librepocket.provider.ToolCall(
          id = wireId,
          name = event.name,
          argumentsJson = event.argumentsJson,
        ),
      )
    }
  }

  /**
   * Shared stream-event collect for the attempt loop and the P2 tool
   * continuation. Text/reasoning deltas run the shared per-attempt
   * visible-text budget ([ToolAggState.visibleTextChars] +
   * [requireTurnTextBudget]); Usage events merge into the per-attempt
   * authoritative value (#84) — one [flushAttemptUsage] notice before the
   * attempt terminal, never one per event; notice handling identical; plus
   * the same Failed/Retrying/Done/truncated-terminal semantics as the old
   * inline loop. ToolDone optionally forwarded to [onWiredToolDone] (attempt
   * loop) or recorded only (continuation, null callback — one tool round per
   * turn).
   */
  private suspend fun collectStream(
    request: ChatRequest,
    attemptRunId: String,
    state: ToolAggState,
    onWiredToolDone: ((dev.librepocket.provider.ToolCall) -> Unit)?,
  ): StreamOutcome {
    coroutineContext.ensureActive()
    var done = false
    var failed: StreamEvent.Failed? = null
    try {
      provider.stream(request).collect { event ->
        coroutineContext.ensureActive()
        // A provider terminal is authoritative; ignore buggy/legacy events
        // that an implementation emits after Done or Failed.
        if (done || failed != null) return@collect
        when (event) {
          is StreamEvent.TextDelta -> {
            // Fail-closed per-turn visible-text budget (#94): a hostile
            // stream of tiny text fragments must not grow memory without
            // bound. Throws typed non-retryable TEXT_TOO_LARGE (never silent
            // truncation); the catch below turns it into the typed terminal
            // with the accepted fragment kept partial, like the tool
            // budgets. Checked BEFORE buffering/display: the tripping delta
            // is never retained and never counted. Enforced against the
            // shared [ToolAggState] so both collect paths count one budget
            // per attempt block.
            requireTurnTextBudget(state.visibleTextChars, event.delta.length)
            state.visibleTextChars += event.delta.length
            _streamedTextDeltaCount.incrementAndGet()
            appendAssistantText(attemptRunId, event.delta)
          }
          // Reasoning stays in the same assistant block: P1 keeps one visible
          // stream and never loses thinking content. The block is additionally
          // flagged reasoning-sourced so tool-dependent / thinking-mode
          // follow-up rebuilds fail closed (ordinary no-tools ones continue).
          // Single _uiState.update (text + flag) to avoid two full maps per delta.
          // Shares the visible-text budget above (reasoning + text are one
          // assistant block, one budget).
          is StreamEvent.ReasoningDelta -> {
            requireTurnTextBudget(state.visibleTextChars, event.delta.length)
            state.visibleTextChars += event.delta.length
            _streamedTextDeltaCount.incrementAndGet()
            appendReasoningDelta(attemptRunId, event.delta)
          }
          // Opaque reasoning marker: no visible text, only the source flag
          // (covers reasoning origins with no replayable payload).
          is StreamEvent.ReasoningPresent -> markAssistantReasoning(attemptRunId)
          is StreamEvent.ToolDelta -> noteToolDelta(state, event)
          is StreamEvent.ToolDone -> noteToolDone(state, event, attemptRunId, onWiredToolDone)
          is StreamEvent.Usage -> {
            // Authoritative per-attempt usage (#84): merge last-wins (null
            // fields keep the previous value) and write once before the
            // attempt terminal below — never one notice per event. An
            // Anthropic normal turn (dual Usage) persists exactly one row
            // with the final values; a flood still persists one row
            // (capped: frozen at MAX_USAGE_EVENTS_PER_ATTEMPT, never a
            // failure). Attempt isolation: each retry merges into its own
            // attempt id. [lastUsage] holds the merged authoritative value.
            val counter = attemptUsageSeen.computeIfAbsent(attemptRunId) { AtomicInteger(0) }
            // Freeze the per-attempt flood counter at the cap (sink parity):
            // once MAX_USAGE_EVENTS_PER_ATTEMPT is reached, neither the
            // counter nor the merge advances.
            if (counter.get() < MAX_USAGE_EVENTS_PER_ATTEMPT) {
              counter.incrementAndGet()
              val merged = mergeUsage(attemptUsages[attemptRunId], event)
              attemptUsages[attemptRunId] = merged
              lastUsage = merged
            }
          }
          is StreamEvent.Done -> {
            done = true
          }
          is StreamEvent.Failed -> failed = event
          is StreamEvent.Retrying -> {
            // Compatibility with legacy/custom providers. Built-in adapters
            // do not retry; this controller owns retries from Failed events.
            // A per-attempt provider notice keeps the attempt id.
            writeNotice { orderedTranscript.onTurnRetried(attemptRunId, event.attempt, event.maxAttempts, event.delayMs) }
          }
        }
      }
      // Finding #3: a provider resource-exhausted Failed (typed AGG_TOO_LARGE
      // code or overflow markers) must not flush poison-adjacent pending
      // ToolDeltas; already-completed ToolDone records stay (emitted inline
      // at collection time). Truncated fallback (failed == null) still
      // flushes: isResourceExhausted(null) is false.
      if (!shouldDiscardPendingTools(failed)) {
        flushPendingTools(state, attemptRunId)
      }
      if (!done && failed == null) {
        failed = StreamEvent.Failed("truncated stream", retryable = true)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: ProviderFailure) {
      coroutineContext.ensureActive()
      failed = StreamEvent.Failed(
        e.message ?: "provider error",
        retryable = e.retryable,
        code = e.code?.name ?: inferAggFailureCodeName(e.message),
      )
      // AGG_TOO_LARGE is this collector's own guard: fail closed without
      // recording poison-adjacent pending tools (the typed terminal error
      // is the observable record). Other failures flush, guarded by the
      // same discard predicate as the Failed-event path.
      if (e.code != ProviderFailureCode.AGG_TOO_LARGE && !shouldDiscardPendingTools(failed)) {
        try {
          flushPendingTools(state, attemptRunId)
        } catch (pf: ProviderFailure) {
          failed = StreamEvent.Failed(
            pf.message ?: "provider error",
            retryable = false,
            code = pf.code?.name ?: inferAggFailureCodeName(pf.message),
          )
        }
      }
    } catch (e: IllegalArgumentException) {
      coroutineContext.ensureActive()
      failed = StreamEvent.Failed(
        e.message ?: "provider error",
        retryable = false,
        code = inferAggFailureCodeName(e.message),
      )
      if (!shouldDiscardPendingTools(failed)) {
        try {
          flushPendingTools(state, attemptRunId)
        } catch (pf: ProviderFailure) {
          failed = StreamEvent.Failed(
            pf.message ?: "provider error",
            retryable = false,
            code = pf.code?.name ?: inferAggFailureCodeName(pf.message),
          )
        }
      }
    } catch (e: IOException) {
      coroutineContext.ensureActive()
      val kind = ProviderErrorClassifier.classify(null, e, null)
      val aggCode = inferAggFailureCodeName(e.message)
      failed = StreamEvent.Failed(
        e.message ?: "provider error",
        retryable = kind == FailureKind.RETRYABLE && aggCode == null,
        code = aggCode,
      )
      if (!shouldDiscardPendingTools(failed)) {
        try {
          flushPendingTools(state, attemptRunId)
        } catch (pf: ProviderFailure) {
          failed = StreamEvent.Failed(
            pf.message ?: "provider error",
            retryable = false,
            code = pf.code?.name ?: inferAggFailureCodeName(pf.message),
          )
        }
      }
    } catch (e: Exception) {
      coroutineContext.ensureActive()
      failed = StreamEvent.Failed(
        e.message ?: "provider error",
        retryable = false,
        code = inferAggFailureCodeName(e.message) ?: ProviderFailureCode.UNKNOWN_RUNTIME.name,
      )
    } catch (e: Throwable) {
      // S3-F1 Error symmetry (Current, option (a)): fatal Error from the
      // provider stream (e.g. AssertionError/LinkageError from a buggy
      // adapter) is a non-retryable terminal like AGG_TOO_LARGE — never
      // flush poison-adjacent pending ToolDeltas (like Exception, which
      // now also discards), never retry. The outer hostedTurn Throwable catch below
      // (or the retry-exhausted terminal path here) still projects
      // sanitizeError → UI ERROR. CancellationException never lands here
      // (rethrown first above), so CE semantics are unchanged. VM-fatal
      // always propagates (contrast OrderedTranscriptSink fail+throw+seal).
      if (e is VirtualMachineError || e is ThreadDeath) throw e
      coroutineContext.ensureActive()
      failed = StreamEvent.Failed(e.message ?: "provider error", retryable = false)
    }
    return StreamOutcome(failed, done)
  }

  /** Outcome of the P2 slice continuation collect: terminal failure (if any) plus the updated per-attempt tool total. */
  private data class ContinuationOutcome(
    val failed: StreamEvent.Failed?,
    val totalToolAggChars: Int,
    val visibleTextChars: Int,
  )

  /**
   * P2 slice continuation request: the current history/user context plus the
   * structured tool round (assistant `toolCalls` + paired `tool` results —
   * pairing is total within one executed round). Markers stay inline in the
   * assistant text AND the structured round rides alongside (double carry
   * within one request). This builder runs once for the live continuation,
   * then is re-derived once per post-execution retry attempt — each retry
   * re-attaches the frozen executed pair after a retryable continuation
   * failure (a provider `Failed` or the forced incomplete-round failure —
   * see runTurnLoop). The
   * failed attempt's partial inline markers do not survive that retry, the
   * structured pair does. Re-attached requests inherit the attempt-0
   * continuation's `requireNoReasoningHistory` semantics unchanged: with a
   * reasoning-sourced settled history a post-exec continuation failure
   * terminals instead of succeeding blind (fail-closed by construction, not
   * a new adapter refusal class). NO tools are
   * advertised (`allowTools = false`): the continuation request carries no
   * `tools` array. If the model still emits a second-round call it is
   * recorded (markers + transcript, `onWiredToolDone = null`) but never
   * executed — the one-tool-round-per-turn budget holds on the wire, not
   * only in code.
   *
   * When the structured round pushes the request past [MAX_REQUEST_CHARS],
   * history-prefix trimming (oldest-first, whole messages, tool pairing kept
   * atomic, the structured payload counted) is additionally published on
   * [lastRequestTrimmedCount]/[requestTrimmedCount]
   * as the total head trim of the outgoing request (added on top of the
   * base request's own emission) — never silent. If even the untrimmable
   * minimum (the newest base row + the structured round) exceeds the cap,
   * this throws [ContinuationOverBudgetException]: runTurnLoop fails the
   * turn with a typed non-retryable terminal instead of shipping an
   * over-cap request (a retry cannot shrink history that only grows).
   */

  private fun buildToolContinuation(
    images: List<ChatImageRef>,
    calls: List<dev.librepocket.provider.ToolCall>,
    results: List<SafeToolSlice.ToolResult>,
    loop: SafeToolSlice,
  ): ChatRequest {
    val base = buildRequest(images, allowTools = false)
    val contMessages = loop.continuationMessages(calls, results)
    // Keep tool-call/tool-result pairing atomic: trim only the history prefix
    // when the structured round pushes the request over MAX_REQUEST_CHARS.
    // Payload accounting includes the structured assistant toolCalls
    // (id/name/args echo — the echo can reach MAX_INPUT_CHARS): a text-only
    // sum under-counts everything that actually serializes into the
    // outgoing request.
    val baseMessages = base.messages.toMutableList()
    var totalChars = baseMessages.sumOf { it.wirePayloadChars() } + contMessages.sumOf { it.wirePayloadChars() }
    var drop = 0
    while (drop < baseMessages.size - 1 && totalChars > MAX_REQUEST_CHARS) {
      totalChars -= baseMessages[drop].wirePayloadChars()
      drop++
    }
    if (totalChars > MAX_REQUEST_CHARS) {
      // Untrimmable: the newest base row + the structured round exceed the
      // cap even at the minimum prefix (the trim never removes the newest
      // row). History only grows within one logical turn, so a retry cannot
      // shrink this request — fail closed (typed, non-retryable) instead of
      // silently shipping an over-cap request.
      // Trim-count contract: on this throw path requestTrimmedCount
      // retains only the base request's published dropCount (the request
      // was never shipped); the next build resets it per request.
      throw ContinuationOverBudgetException()
    }
    val finalBase = if (drop > 0) baseMessages.drop(drop) else baseMessages
    // Publish the continuation's own prefix trim (never silent): buildRequest
    // already emitted the base request's context-window trim, so this adds on
    // top — lastRequestTrimmedCount then reflects the TOTAL head trim of the
    // outgoing continuation request. Single-writer (same coroutine), so the
    // read-add-set is exact.
    if (drop > 0) {
      val total = _lastRequestTrimmedCount.get() + drop
      _lastRequestTrimmedCount.set(total)
      _requestTrimmedCount.value = total
    }
    return base.copy(messages = finalBase + contMessages)
  }

  /**
   * Serialized-size estimate of one [ChatMessage] on the outgoing request:
   * message text plus every structured assistant `toolCalls` payload field
   * (id/name/argumentsJson — the args echo is bounded by
   * SafeToolSlice.MAX_INPUT_CHARS). The estimate is a raw String.length
   * basis — the same measurement basis as buildRequest's own (text-only)
   * accounting. JSON-escaping expansion and adapter wrapper keys are NOT
   * counted (deliberately never over-counts), so the fail-close can only
   * fire for a request that is already over cap on the strict measure.
   */
  private fun ChatMessage.wirePayloadChars(): Int =
    text.length + toolCalls.sumOf { it.id.length + it.name.length + it.argumentsJson.length }

  /**
   * P2 slice continuation collect: delegates to [collectStream] with
   * record-only tool semantics (`onWiredToolDone = null`) — ToolDone/pending
   * ToolDeltas get markers + transcript records but are never executed,
   * enforcing the one-tool-round-per-turn budget. Aggregation budgets share
   * the attempt total ([attemptTotalChars] in, updated total out);
   * index-keyed maps are fresh per response (provider tool indexes restart
   * per response, so cross-round sharing would forge false duplicates).
   *
   * Round identity: continuation records share this attempt's [attemptRunId]
   * with the first round (both are append-only rows, never upserts, so no
   * overwrite). Rounds are distinguished by order only in the durable
   * transcript (markers hold name+args; call ids reach in-memory sinks but
   * are not persisted); a typed per-round transcript event is future work
   * (not this slice). The visible-text budget is seeded from the attempt's
   * running [ToolAggState.visibleTextChars] (main's per-attempt budget keeps
   * accumulating across the rounds of one same-block attempt); the
   * index-keyed tool-agg floors above stay fresh per round as reasoned here.
   */
  private suspend fun collectToolContinuation(
    request: ChatRequest,
    attemptRunId: String,
    attemptTotalChars: Int,
    attemptVisibleTextChars: Int,
  ): ContinuationOutcome {
    coroutineContext.ensureActive()
    val state = ToolAggState(totalChars = attemptTotalChars, visibleTextChars = attemptVisibleTextChars)
    val outcome = collectStream(request, attemptRunId, state, onWiredToolDone = null)
    return ContinuationOutcome(outcome.failed, state.totalChars, state.visibleTextChars)
  }

  /**
   * Maps the resumed history plus the current (settled) UI history plus this
   * turn's images to one [ChatRequest]: the capped history prefix first, then
   * live messages. The current user message is already in [uiState] (appended
   * by [send] before the turn starts), so images attach to the last user line.
   *
   * Context window: [historyCap] truncates a **copy** of the combined
   * (capped prefix + settled non-partial live) history oldest-first at
   * whole-message boundaries before
   * images/time-block attach. UI state and transcript keep everything; only
   * the outgoing request is windowed. The drop count is published on
   * [droppedHistoryCount] (0 when Unbounded), never silent.
   *
   * Ephemeral time context: a [buildRuntimeTimeContext] block is appended to a
   * copy of the last user message only. No user message means no injection
   * (never fabricates a message for the clock). UI state and transcript keep
   * the raw user text.
   *
   * `allowTools` gates the structured `tools` offer: `true` (attempt round,
   * default) offers the slice tool when the loop is attached; the tool
   * continuation round passes `false` so the wire carries NO tool array
   * (one-tool-round-per-turn enforced on the request shape). Kotlin default
   * args compile to a SINGLE JVM method `(List, boolean)` (+ the `$default`
   * bridge) — reflection callers must use the two-param signature.
   */
  private fun buildRequest(images: List<ChatImageRef>, allowTools: Boolean = true): ChatRequest {
    val live = _uiState.value.messages
      .filter { !it.isPartial }
      .map { ChatMessage(role = it.role, text = it.text, hasReasoning = it.hasReasoning) }
    val combined = ArrayList<ChatMessage>(cappedHistory.size + live.size)
    combined.addAll(cappedHistory)
    combined.addAll(live)
    val base = applyHistoryCap(combined, historyCap).toMutableList()
    droppedHistoryCount = (combined.size - base.size).coerceAtLeast(0)
    // Per-request image re-load/decode is an accepted cost trade-off;
    // decoder-level reuse/caching is deliberately deferred (bounds documented
    // in defaultChatImageLoader's KDoc).
    val loaded = runCatching { imageLoader(images) }.getOrDefault(emptyList())
    if (loaded.isNotEmpty()) {
      val lastUser = base.indexOfLast { it.role == "user" }
      if (lastUser >= 0) {
        base[lastUser] = base[lastUser].copy(images = loaded)
      } else {
        base.add(ChatMessage(role = "user", text = "", images = loaded))
      }
    }
    val lastUser = base.indexOfLast { it.role == "user" }
    if (lastUser >= 0) {
      val block = buildRuntimeTimeContext(clock, userTimezone, sessionStart, systemZone)
      warnOnTimeContextDegraded(block)
      base[lastUser] = base[lastUser].copy(text = base[lastUser].text + "\n\n" + block)
    }

    var totalChars = base.sumOf { it.text.length }
    var dropCount = 0
    val maxDropLimit = if (lastUser >= 0) lastUser else (base.size - 1).coerceAtLeast(0)
    while (dropCount < maxDropLimit && totalChars > MAX_REQUEST_CHARS) {
      totalChars -= base[dropCount].text.length
      dropCount++
    }
    _lastRequestTrimmedCount.set(dropCount)
    _requestTrimmedCount.value = dropCount
    val finalMessages = if (dropCount > 0) base.drop(dropCount) else base
    // P2 slice: tools attach ONLY the loop's offer (visibleTools ∩ slice,
    // empty when the loop is absent — the production composition — or when
    // the slice projects unavailable). ChatRequest.tools may only carry
    // ToolRegistry.visibleTools() output (projection + executionReady); 40
    // tools stay executionReady=false, so a non-slice request is pure-chat
    // by construction.
    // P2 slice: after a tool round has executed this turn, never advertise
    // tools again (continuation or retry) — only paired result/terminal
    // semantics, no second tool round.
    val offered = if (allowTools) toolSlice?.offeredTools().orEmpty() else emptyList()
    return ChatRequest(model = model, messages = finalMessages, tools = offered)
  }

  /**
   * Observability for the ephemeral time block: `Log.w` only, never blocks
   * the turn. Fires when the block renders `(time unknown)` or when a
   * non-blank [userTimezone] failed to parse (fell back to system zone).
   * A successful parse never warns, even when the resolved id is normalized
   * (e.g. `UTC+8` → `UTC+08:00`). `runCatching` keeps JVM unit tests (no
   * mocked `android.util.Log`) green.
   *
   * Log/block consistency: the reported `used` zone is resolved exactly like
   * [buildRequest] — blank [userTimezone] reads [systemZone] (with the same
   * `resolveZone(null)` fallback), so the log never names a different zone
   * than the block actually used.
   */
  private fun warnOnTimeContextDegraded(block: String) {
    val fellBack = isTimezoneFallback(userTimezone)
    if (!block.contains("(time unknown)") && !fellBack) return
    val used = if (userTimezone.isNullOrBlank()) {
      runCatching { systemZone().id }.getOrDefault(
        runCatching { resolveZone(null).id }.getOrDefault("?"),
      )
    } else {
      runCatching { resolveZone(userTimezone).id }.getOrDefault("?")
    }
    runCatching {
      android.util.Log.w(
        "TurnController",
        "runtime time context degraded: requested=$userTimezone used=$used unknown=${block.contains("(time unknown)")}",
      )
    }
  }

  // ---- uiState helpers (StateFlow.update is atomic; no lock needed) ----

  /**
   * Stage 6: returns the UI row id so an admission-time durable failure can
   * roll it back (see [rollbackFailedAdmission]/[rollbackClosedAdmissionLocked]).
   * The ledger user row uses the separate logical turn id (see
   * [PendingAdmission]); the two ids are intentionally distinct (UI row `*-0`,
   * logical `*-1` in tests).
   */
  private fun appendUser(text: String, operationId: Long? = null): String {
    val id = newId()
    _uiState.update { s ->
      s.copy(
        messages = s.messages + UiMessage(
          id = id,
          role = "user",
          text = text,
          isPartial = false,
          operationId = operationId,
        ),
      )
    }
    return id
  }

  private fun appendAssistantPlaceholder(runId: String) {
    // Fresh buffer per attempt (a retried placeholder never inherits the
    // previous attempt's tail; the old buffer was discarded at its terminal).
    attemptTextBuffers[runId] = TextBuffer()
    _uiState.update { s ->
      s.copy(messages = s.messages + UiMessage(id = runId, role = "assistant", text = "", isPartial = true))
    }
  }

  /**
   * Buffers one visible write (#83 incremental update): appends [delta] to
   * the attempt buffer in O(delta) and commits to [_uiState] immediately
   * while the block is small, at the flush cadence past it (see
   * [flushAssistantText]). Terminal and cancel paths flush unconditionally,
   * and snapshots read the buffer, so they stay exact. Single-writer per
   * attempt id (only the host coroutine touches its live attempt's buffer).
   */
  private fun appendAssistantText(runId: String, delta: String) {
    if (delta.isEmpty()) return
    bufferAppend(runId, delta, reasoning = false)
  }

  /**
   * Shared buffer-append for [appendAssistantText] (reasoning=false) and
   * [appendReasoningDelta] (reasoning=true, text excluded from nothing —
   * both share the one visible-text budget enforced by the caller).
   */
  private fun bufferAppend(runId: String, delta: String, reasoning: Boolean) {
    val buf = attemptTextBuffers.computeIfAbsent(runId) { TextBuffer() }
    if (buf.text.isEmpty()) {
      // Late join without a placeholder (defensive; production always
      // placeholders first): seed from the committed UI copy so the buffer
      // stays the full block. Seeded chars are already committed, so only
      // the new delta counts toward the next flush.
      _uiState.value.messages.firstOrNull { it.id == runId && it.role == "assistant" }?.let {
        if (it.text.isNotEmpty()) buf.text.append(it.text)
      }
    }
    buf.text.append(delta)
    if (reasoning) buf.hasReasoning = true
    buf.totalDeltas += 1
    buf.unflushedChars += delta.length
    buf.unflushedDeltas += 1
    // #83 hybrid cadence: fast first paint while the block is small
    // (immediate commit — preserves streaming visibility and every
    // small-turn snapshot exactly as before), batched once it outgrows the
    // cadence (only flush at cadence — bounds hostile tiny-delta drips to
    // O(flushes) full copies instead of O(deltas)).
    if (buf.text.length < UI_TEXT_FLUSH_CHARS && buf.totalDeltas <= UI_TEXT_FLUSH_DELTAS) {
      flushAssistantText(runId)
    } else if (buf.unflushedChars >= UI_TEXT_FLUSH_CHARS || buf.unflushedDeltas >= UI_TEXT_FLUSH_DELTAS) {
      flushAssistantText(runId)
    }
  }

  /**
   * Commits one attempt's buffered text to [_uiState] with a single immutable
   * copy. Exactly one [uiTextUpdateCount] increment and one
   * [uiTextCopiedChars] charge (materialized length) per commit; buffer
   * appends between commits cost O(delta) and are uncounted. Non-suspending
   * (safe on the cancel path). No-op when nothing is pending.
   */
  private fun flushAssistantText(runId: String) {
    val buf = attemptTextBuffers[runId] ?: return
    if (buf.unflushedChars == 0 && buf.unflushedDeltas == 0) return
    // No matching UI row (defensive; production always placeholders first):
    // skip the full-history copy plus the no-op emission instead of counting
    // a phantom commit. The buffer is kept so the terminal snapshot stays
    // exact via assistantTextOf. Clear the dirty counters so the guard does
    // not rescan on every later flush.
    if (_uiState.value.messages.none { it.id == runId && it.role == "assistant" }) {
      buf.unflushedChars = 0
      buf.unflushedDeltas = 0
      return
    }
    val snapshot = buf.text.toString()
    val reasoning = buf.hasReasoning
    // The update{} lambda may re-run on contention, so counter side effects
    // stay outside update{} and execute exactly once per committed flush.
    _uiState.update { s ->
      s.copy(
        messages = s.messages.map { m ->
          if (m.id == runId && m.role == "assistant") {
            m.copy(text = snapshot, hasReasoning = m.hasReasoning || reasoning)
          } else {
            m
          }
        },
      )
    }
    buf.unflushedChars = 0
    buf.unflushedDeltas = 0
    _uiTextUpdateCount.incrementAndGet()
    _uiTextCopiedChars.addAndGet(snapshot.length.toLong())
  }

  /** Drops one attempt's buffer after its terminal snapshot (UI already exact). */
  private fun discardTextBuffer(runId: String) {
    attemptTextBuffers.remove(runId)
  }

  /**
   * Flags the attempt's assistant block as reasoning-sourced (idempotent).
   * Display text is untouched; the flag only rides [buildRequest] into
   * [ChatMessage.hasReasoning] so adapters fail closed on a tool-dependent /
   * thinking-mode follow-up (ordinary no-tools follow-ups continue).
   * Short-circuits when the flag is already set to avoid a full list copy
   * plus a no-op flow emission (opaque [StreamEvent.ReasoningPresent] path).
   * Also records the flag in the attempt buffer so a later flush preserves
   * it even when the UI copy was already flagged first.
   */
  private fun markAssistantReasoning(runId: String) {
    attemptTextBuffers[runId]?.hasReasoning = true
    if (_uiState.value.messages.any { it.id == runId && it.role == "assistant" && it.hasReasoning }) return
    _uiState.update { s ->
      s.copy(
        messages = s.messages.map { m ->
          if (m.id == runId && m.role == "assistant") m.copy(hasReasoning = true) else m
        },
      )
    }
  }

  /**
   * Merges one reasoning delta into the attempt's buffer and flags it
   * reasoning-sourced. An empty [delta] still sets the flag (empty reasoning
   * counts as reasoning-sourced, matching the previous mark-then-append
   * behavior) without text or flush accounting.
   */
  private fun appendReasoningDelta(runId: String, delta: String) {
    if (delta.isEmpty()) {
      markAssistantReasoning(runId)
      attemptTextBuffers.computeIfAbsent(runId) { TextBuffer() }.hasReasoning = true
      return
    }
    bufferAppend(runId, delta, reasoning = true)
  }

  private fun finalizeAssistant(runId: String) {
    _uiState.update { s ->
      s.copy(
        messages = s.messages.map { m ->
          if (m.id == runId && m.role == "assistant") m.copy(isPartial = false) else m
        },
      )
    }
  }

  /**
   * One attempt's assistant-block text, exact including any unflushed buffer
   * tail (#83). Falls back to the committed UI copy once the buffer is
   * discarded (post-terminal, UI already flushed exact).
   */
  private fun assistantTextOf(runId: String): String =
    attemptTextBuffers[runId]?.text?.toString()
      ?: _uiState.value.messages.firstOrNull { it.id == runId }?.text.orEmpty()

  /** Reasoning-source flag of one attempt's block (buffer or UI, false when absent). */
  private fun assistantReasoningOf(runId: String): Boolean =
    attemptTextBuffers[runId]?.hasReasoning == true ||
      (_uiState.value.messages.firstOrNull { it.id == runId && it.role == "assistant" }?.hasReasoning == true)
}

/**
 * Pure fallback detector for the warn path: true only when a non-blank
 * [requested] timezone fails to parse (so [resolveZone] falls back to the
 * system zone). Successful parses — including normalized ids such as
 * `UTC+8` → `UTC+08:00` — return false; null/blank returns false (nothing
 * was requested). Never throws; JVM-pure (no `android.util.Log`) so it is
 * directly unit-testable. Fail-closed: any parse failure counts as
 * fallback so the caller warns.
 */
internal fun isTimezoneFallback(requested: String?): Boolean {
  if (requested.isNullOrBlank()) return false
  return runCatching { ZoneId.of(requested.trim()) }.isFailure
}

/**
 * Default image loader: reads app-private files into [ChatImage] bytes.
 * Bounded per spec §4 (10 MiB per image, first 4 images); unreadable or
 * oversize entries are omitted (never crash the turn, never log bytes).
 */
internal fun defaultChatImageLoader(refs: List<ChatImageRef>): List<ChatImage> {
  if (refs.isEmpty()) return emptyList()
  val out = mutableListOf<ChatImage>()
  for (ref in refs.take(TurnController.MAX_IMAGES_PER_TURN)) {
    val bytes = runCatching {
      val file = java.io.File(ref.filePath)
      if (!file.isFile) return@runCatching null
      if (file.length() > TurnController.MAX_IMAGE_BYTES) return@runCatching null
      file.readBytes().takeIf { it.size <= TurnController.MAX_IMAGE_BYTES }
    }.getOrNull() ?: continue
    out.add(ChatImage(bytes = bytes, mimeType = ref.mimeType, preserveOriginal = ref.preserveOriginal))
  }
  return out
}

/**
 * Error sanitizer for [ChatUiState.error]: [Redactor.redactError] strips
 * keys/tokens/URL credentials/private IPs, then truncates to 500 chars.
 * The sanitized string is the only form that reaches UI state, transcript
 * persistence, and logs; the raw message never leaves memory.
 */
internal fun sanitizeError(raw: String): String = Redactor.redactError(raw)

/**
 * Stage D Finding #3: does a collected [StreamEvent.Failed] terminal signal
 * provider-side resource exhaustion, so pending (never-`ToolDone`) tool
 * fragments must be dropped instead of flushed?
 *
 * True when `failed.code` is the typed `AGG_TOO_LARGE` name propagated via
 * [StreamEvent.Failed.code] (`streamingFlow` fills `ProviderFailure.code?.name`
 * or infers it from the message; `pumpSse` sets it for SSE budget overflows),
 * or — for legacy/message-only `Failed`s with `code == null` — when
 * [failed.message] contains `TOOL_ARGS_TOO_LARGE` / `TEXT_TOO_LARGE` /
 * `SSE_FRAME_TOO_LARGE` / `SSE_LINE_TOO_LONG` case-insensitively (same
 * markers as the classifier's fatal list). Null (truncated fallback, not yet
 * failed) is never exhausted, and already-emitted `ToolDone` records are
 * unaffected (written inline at collection time, not via the pending flush).
 */
internal fun isResourceExhausted(failed: StreamEvent.Failed?): Boolean {
  if (failed == null) return false
  if (failed.code?.equals(ProviderFailureCode.AGG_TOO_LARGE.name, ignoreCase = true) == true) return true
  return failed.message.contains(TOOL_ARGS_TOO_LARGE, ignoreCase = true) ||
    failed.message.contains(TEXT_TOO_LARGE, ignoreCase = true) ||
    failed.message.contains(SseFrameParser.FRAME_TOO_LARGE_MESSAGE, ignoreCase = true) ||
    failed.message.contains(SseFrameParser.LINE_TOO_LONG_MESSAGE, ignoreCase = true)
}

/**
 * P1 residual: should a collected [StreamEvent.Failed] terminal discard
 * poison-adjacent pending (never-`ToolDone`) tool fragments instead of
 * flushing them?
 *
 * True when [isResourceExhausted] holds, or when `failed.code` is the typed
 * `UNKNOWN_RUNTIME` name (`streamingFlow` unknown fallback and the
 * direct-throw unknown/Error catches). Other fatal events with
 * `code == null` (401/validation) keep the historical flush; already-emitted
 * `ToolDone` records are unaffected (written inline at collection time).
 * Null (truncated fallback, not yet failed) never discards.
 */
internal fun shouldDiscardPendingTools(failed: StreamEvent.Failed?): Boolean {
  if (failed == null) return false
  if (isResourceExhausted(failed)) return true
  return failed.code?.equals(ProviderFailureCode.UNKNOWN_RUNTIME.name, ignoreCase = true) == true
}
