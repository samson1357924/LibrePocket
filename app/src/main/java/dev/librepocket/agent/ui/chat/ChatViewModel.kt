package dev.librepocket.agent.ui.chat

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.PolicyEvaluationException
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import dev.librepocket.chat.ApprovalRequiredException
import dev.librepocket.chat.InputTooLongException
import dev.librepocket.chat.TranscriptDurabilityException
import dev.librepocket.chat.TranscriptPendingException
import dev.librepocket.chat.TurnStart
import dev.librepocket.chat.UiMessage
import dev.librepocket.policy.PolicyStore
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.policy.Verdict
import dev.librepocket.provider.ChatMessage
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val EMPTY_SESSION_STATE = ChatUiState(
    messages = emptyList(),
    status = ChatStatus.IDLE,
    pendingSteerCount = 0,
    error = null,
)

private const val HISTORY_PAGE = 200
private const val HISTORY_CAP = 2000
// Upper bound on raw events scanned by loadHistory. importJsonl can write a
// session that bypasses the prune cap (bulk import preserves file seqs; prune
// only trims on its own schedule), so an unbounded page-through could do
// unbounded I/O and hold unbounded memory on open. Stop at the bound and
// build messages from what was scanned.
private const val HISTORY_SCAN_CAP = HISTORY_CAP * 4

/** Stage E: tail-loaded replay prefix with its exact omitted-row count. */
internal data class LoadedUiHistory(
    val messages: List<UiMessage>,
    val droppedCount: Int,
)

/**
 * One-shot key.read consent for an ASK verdict, mirroring the key.write
 * consent in SetupViewModel (SetupViewModel KeyWriteConsent).
 *
 * In-memory only: never persisted, carries no key material (connection
 * identity snapshot only). Bound to the immutable endpoint binding
 * observed at mint time ([confirmKeyRead]): action + providerId /
 * effectiveBaseUrl / origin / protocol / apiKeyRef / configRevision /
 * model + the lifecycle [generation] observed at mint start. Consumed
 * (nulled) on the first authorization that takes it, so it can never be
 * replayed or reused. Any endpoint-identity difference fails the match and
 * denies closed; a generation difference fails closed as well (data-inner
 * fence against ABA: A→B→A returns to the same binding but a newer
 * generation, so the stale consent must not pass on binding equality
 * alone).
 */
private data class KeyReadConsent(
    val action: String,
    val providerId: String,
    val effectiveBaseUrl: String,
    val origin: String,
    val protocol: ProviderProtocol,
    val apiKeyRef: String,
    val configRevision: Long,
    val model: String,
    val generation: Long,
)

/**
 * P2 待確認 token：拒絕時與 [emitKeyReadConfirmRequired] 同 emission 產生，
 * sequence 單調遞增永不重置沿用。綁定原待確認請求的不可變快照
 * （binding＋generation＋confirmSequence＋opId/openSessionId），供
 * [confirmKeyRead] 三者全等驗證（仍為原請求、generation/seq 未變、
 * binding 全欄相等）才 mint。In-memory only，零 key material。
 */
data class PendingKeyReadConfirm(
    val binding: EndpointSessionBinding,
    val generation: Long,
    val confirmSequence: Long,
    val opId: Long?,
    val openSessionId: String?,
)
/**
 * R4-2 (Stage 4): provenance captured before an endpoint-switch fork creates
 * its fresh transcript session, consumed after creation by the durable
 * snapshot. [oldModelShort] is the previous session's short model id
 * ("unknown" when its header is gone); [sameOrigin] is the
 * [isSameProviderOrigin] gate outcome; [crossProviderWithheld] is the copy
 * gate (`!sameOrigin`): a cross-origin fork never copies old rows — not even
 * terminal/reasoning-only ledgers — and persists only the withheld marker.
 * It is deliberately NOT diluted by history presence; history presence gates
 * only the switch-time notice ([ForkRead.crossProviderWithheld]).
 */
private data class ForkPrefix(
    val oldModelShort: String,
    val sameOrigin: Boolean,
    val crossProviderWithheld: Boolean,
)

/**
 * R4-2: one IO round-trip's fork inputs — display replay, model prefix,
 * notice verdict, snapshot provenance. [crossProviderWithheld] is the
 * notice gate (`!sameOrigin && hasHistory`): an empty old ledger withholds
 * nothing visible, so no RESUME_CROSS_PROVIDER_HISTORY_WITHHELD notice is
 * shown at switch time (the persisted marker still records the withhold, so
 * a later restart re-surfaces the notice via the marker scan).
 */
private data class ForkRead(
    val replay: LoadedUiHistory?,
    val modelHistory: List<ChatMessage>,
    val crossProviderWithheld: Boolean,
    val fork: ForkPrefix,
)

/**
 * PG-S1: endpoint-switch fork fence outcome (ensureSession segment C).
 * [read] is null when there is no previous session to fork from; otherwise
 * it carries the ledger read for the caller to apply on Main.
 * [wasStreaming]/[settleOk]/[durableOk] feed the durable snapshot fallback.
 */
private data class SettledFork(
    val read: ForkRead?,
    val wasStreaming: Boolean,
    val settleOk: Boolean,
    val durableOk: Boolean,
)

/**
 * PG-S1: durable fork snapshot outcome (ensureSession segment E).
 * The callee performs store I/O only and returns the verdict; the caller
 * writes `_notice` on Main (keeps the four warn branches distinct for tests).
 * Values: SKIPPED (null-gate defense, unreachable via ensureSession),
 * CLEAN (snapshot copied, no warning), DANGLING_WARNED (streaming-settled
 * but dangling families excluded), FENCE_FAILED_WARNED (settle/flush failed),
 * BYTE_WARNED (byte over-budget abandon), FAILED_WARNED (batch threw).
 */
private enum class SnapshotOutcome {
    SKIPPED,
    CLEAN,
    DANGLING_WARNED,
    FENCE_FAILED_WARNED,
    BYTE_WARNED,
    FAILED_WARNED,
}

/**
 * Stage 5 (R4-1) stale-fork cleanup decision for [ChatViewModel.openSession]:
 * a cross-origin `open()` mints a fresh provider-owned transcript id inside
 * the call, so a binding change that lands after the fork but before attach
 * (the post-`open()` stale check) must roll that new row back
 * (`deleteTranscriptRow = true`), or the empty fork (marker only) leaks into
 * the session list. Same-id resumes reuse the archived row and must never
 * delete it (`false`); storeless results (`sessionId == null`) likewise never
 * delete. Mirrors `ensureSession`'s always-new `create()` row, which already
 * discards with `deleteTranscriptRow = true`.
 */
internal fun shouldDeleteStaleFork(requestedSessionId: String, created: CreatedSession): Boolean =
    created.sessionId != null && created.sessionId != requestedSessionId

/**
 * Per-phase budget for the bounded pre-close ledger drain (Phase 2
 * conversation-ledger wiring): newChat / openSession / endpoint-switch /
 * transport-replace flush the detached live session before closing it, so
 * admitted transcript events are not lost with the session.
 * [ChatSession.flush] spends one such budget on the close shutdown-join and
 * another on the admitted-event drain, so one flush-then-close waits up to
 * ~2x this value after close (~1x when nothing was closed yet). An
 * endpoint-switch fork fences up to three such bounded waits in series on
 * IO — the prior parked fence join (~2x), its session re-flush (~2x), and
 * the just-detached close-then-flush (~2x) — so the single-flight mutex is
 * held up to ~6x this value (~30s) worst case before the snapshot read. A
 * same-ID openSession reopen fences the same three waits (~6x) inside the
 * mutex before its gated backfill; a different-ID open keeps the fast path
 * (no fence: the backfill targets another ledger). The
 * UI never waits on any of it: VM state is detached synchronously and each
 * wait is bounded — a stalled store only delays the background close / the
 * next fork's snapshot, never user interaction. Mirrors
 * [dev.librepocket.chat.OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS].
 */
private const val SESSION_FLUSH_TIMEOUT_MS = 5_000L

/**
 * R9-3 deferred-backfill block horizon: a same-ID reopen whose pre-read
 * settle failed (flush-timeout: the old writer may still land its terminal
 * late) parks `pendingBackfillBlock[sessionId] = now + this`. A later open
 * whose settle trivially succeeds (fresh idle live) must NOT backfill while
 * the block holds — the ledger still dangles — until promotion (the ledger
 * proves its terminal) or this deadline (old writer truly dead/restarted;
 * bounded, never a permanent ban). 6x the flush budget (~30s) so a slow but
 * live writer has room to settle across consecutive reopens. Each forced
 * hold refreshes the deadline (idle-bounded: back-to-back premature reopens
 * keep sliding it); R10-2 expiry still requires verifiable proof (ledger
 * terminal or writer-dead via seal + empty pending) — the deadline alone
 * never promotes an unsettled ledger, so the block can outlive the horizon
 * while the old writer is provably alive (fail-closed defer, never a
 * mis-mark). A restart inside the window drops the memory-only block,
 * so the first post-restart reopen of a still-dangling id may backfill early
 * (pre-existing behavior class, bounded by the same horizon).
 */
private const val PENDING_BACKFILL_BLOCK_MS = SESSION_FLUSH_TIMEOUT_MS * 6

/**
 * Chat UI state holder wired to the real [ChatSession] plus the transcript store.
 *
 * Exposes replayed history ([messages] = stored history + live session messages),
 * the current transcript session id, and send/cancel/retry/new/open operations.
 * History kinds other than user/assistant (tool/steer/retry/system/reasoning) are kept in
 * the store/export but hidden from the chat replay.
 */
class ChatViewModel(
    private val store: EndpointStore,
    private val sessions: ChatSessionProvider,
    private val policy: PolicyStore,
) : ViewModel() {

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _sessionState = MutableStateFlow(EMPTY_SESSION_STATE)
    val sessionState: StateFlow<ChatUiState> = _sessionState.asStateFlow()

    private val _history = MutableStateFlow<List<UiMessage>>(emptyList())

    /**
     * Stage E: exact number of older replay rows omitted by the tail cap.
     * Observable truncation (never silent): surfaces "已省略N則" without a
     * new UI flow. Reset alongside [_history] on every load path.
     */
    private val _historyOmittedCount = MutableStateFlow(0)
    val historyOmittedCount: StateFlow<Int> = _historyOmittedCount.asStateFlow()

    /**
     * R5-5: send-time per-request trim count, collected from the attached
     * [ChatSession.requestTrimmedCount]. Load-time resume omission
     * ([historyOmittedCount], DB tail cap) and this send-time trim are
     * orthogonal counters — never summed, reset independently. Reset to 0 on
     * every attach (new session starts untrimmed) and on lifecycle/endpoint
     * invalidation (the owning session is detached).
     */
    private val _requestTrimmedCount = MutableStateFlow(0)
    val requestTrimmedCount: StateFlow<Int> = _requestTrimmedCount.asStateFlow()

    val messages: StateFlow<List<UiMessage>> =
        combine(_history, _sessionState) { history, live ->
            history + live.messages.filter { it.role == "user" || it.role == "assistant" }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // Q3: observable outbox depth. Restoring/discarding through
    // restoreNextRecovered()/discardNextRecovered() costs zero provider
    // calls; the box is only ever filled when blank, never auto-sent.
    private val _pendingRecoveryCount = MutableStateFlow(0)
    val pendingRecoveryCount: StateFlow<Int> = _pendingRecoveryCount.asStateFlow()

    // S7：key.read 確認序列：每次 ConfirmRequired deny 單調遞增，永不重置
    //（含 newChat/open/logout/endpoint 變更亦不重置）。ChatScreen 以
    // (notice, sequence) 為 LaunchedEffect key，使 ConfirmRequired→null→
    // ConfirmRequired 中間 null 被 StateFlow conflation 吃掉時仍能重置本地
    // dialog 狀態並重 peek；同 sequence 內 confirm 進度不受影響。
    private val _keyReadConfirmSequence = MutableStateFlow(0L)
    val keyReadConfirmSequence: StateFlow<Long> = _keyReadConfirmSequence.asStateFlow()

    /** Restore the oldest recoverable text into a blank box. Never overwrites, never sends. */
    fun restoreNextRecovered(): Boolean {
        if (_input.value.isNotBlank()) return false
        val next = recoverableOps.removeFirstOrNull() ?: return false
        setInput(next.text, next.opId, next.text, next.recoverySequence)
        syncRecoveryCount()
        return true
    }

    /** Drop the oldest recoverable text. A denied intent dropped here is no longer retryable. */
    fun discardNextRecovered(): Boolean {
        val dropped = recoverableOps.removeFirstOrNull() ?: return false
        if (retryableOp?.opId == dropped.opId) retryableOp = null
        if (retryFallbackTarget?.opId == dropped.opId) retryFallbackTarget = null
        if (retryTargetCandidate?.opId == dropped.opId) retryTargetCandidate = null
        syncRecoveryCount()
        return true
    }

    private fun syncRecoveryCount() {
        _pendingRecoveryCount.value = recoverableOps.size
    }

    /** Assign a FIFO position once; explicit NeedsRecovery rebasing is handled separately. */
    private fun withRecoverySequence(op: PendingOp): PendingOp {
        val existing = op.recoverySequence
        if (existing != null) {
            nextRecoverySequence = maxOf(nextRecoverySequence, existing)
            return op
        }
        return op.copy(recoverySequence = ++nextRecoverySequence)
    }

    /** Insert idempotently by provenance rather than guessing head/tail from the current input path. */
    private fun addRecoverable(op: PendingOp): PendingOp {
        op.recoverySequence?.let { sequence ->
            nextRecoverySequence = maxOf(nextRecoverySequence, sequence)
        }
        recoverableOps.firstOrNull { it.opId == op.opId }?.let { return it }
        val sequenced = withRecoverySequence(op)
        val ordered = (recoverableOps.toList() + sequenced)
            .sortedBy { checkNotNull(it.recoverySequence) }
        recoverableOps.clear()
        recoverableOps.addAll(ordered)
        return sequenced
    }

    // Lifecycle state is main-thread confined. The generation is also read by
    // the provider's suspending key callback, so it is volatile across IO.
    @Volatile
    private var lifecycleGeneration = 0L
    /** Changes only for an explicit lifecycle discard, not endpoint replacement. */
    private var explicitDiscardEpoch = 0L
    /** Monotonic evidence that a non-null endpoint replacement crossed an admission. */
    private var endpointReplacementEpoch = 0L
    private var lifecycleJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var lifecycleScope = CoroutineScope(viewModelScope.coroutineContext + lifecycleJob)
    private val sessionMutex = Mutex()
    private var currentSession: ChatSession? = null
    private var currentBinding: EndpointSessionBinding? = null
    private var currentSessionGeneration: Long? = null
    private var sessionCollectJob: Job? = null
    private var requestTrimCollectJob: Job? = null
    private var openJob: Job? = null
    // One-shot key.read consent (ASK path). Minted by [confirmKeyRead] from a
    // suspending context and taken by [authorizeEndpoint] on Main, so all
    // access goes through [keyReadConsentLock] (same shape as the key.write
    // consentLock in SetupViewModel). Never persisted, never leaves this VM.
    private val keyReadConsentLock = Any()
    private var pendingKeyReadConsent: KeyReadConsent? = null
    // P2: pending key.read confirm token (see PendingKeyReadConfirm). Created
    // with emitKeyReadConfirmRequired in the same emission (sequence +1);
    // burned together with the consent under keyReadConsentLock on
    // invalidateLifecycle(clearChat)/invalidateForEndpointChange(null)/
    // openSession new round/logout, discarded by dismissKeyReadConfirm.
    // A successful mint burns it in the same lock (single-use); a stale tap
    // with no pending/mismatched token fails closed with zero vault reads.
    private var pendingKeyReadConfirm: PendingKeyReadConfirm? = null
    // S2: pending history open retained across a key.read ConfirmRequired.
    // Set only by openSession's ConfirmRequired branch (sessionId + endpoint
    // snapshot + generation at that time); consumed once by an explicit
    // resumePendingOpen without a second invalidate, so the just-minted
    // consent is not burned. Never auto-resumed, never auto-sent. Cleared
    // on successful attach, explicit discard (newChat/open/logout) and
    // terminal open failures; kept across endpoint replacement/dismiss so a
    // stale resume still fails closed with a fresh ConfirmRequired (re-armed
    // to the current binding/generation). Main-confined like the consent
    // slot; binding equality covers all EndpointSessionBinding fields.
    private data class PendingOpen(
        val sessionId: String,
        val binding: EndpointSessionBinding,
        val generation: Long,
    )
    private var pendingOpen: PendingOpen? = null
    private val _pendingOpenSessionId = MutableStateFlow<String?>(null)
    val pendingOpenSessionId: StateFlow<String?> = _pendingOpenSessionId.asStateFlow()
    /**
     * R6-3 streaming-switch fence (Main-confined, guarded by [sessionMutex]
     * like every other lifecycle field): the detached old ledger's background
     * close-then-flush job plus the streaming evidence captured before its
     * detach. [invalidateForEndpointChange] is synchronous and cannot suspend,
     * so it parks the detached session here; the next `ensureSession` fork
     * joins the fence (bounded, on IO) before reading the old ledger, so the
     * doomed host's cancel terminal is already durable when the snapshot
     * copies. Cleared once consumed. Null when no detached ledger is pending.
     * Cancel-safety: a fork that consumed the fence but is lifecycle-cancelled
     * before its settle completes re-parks the unfinished ledger
     * ([reparkUnsettledLedger]); a fork whose history was discarded instead
     * drops it ([invalidateLifecycle] with `clearChat`, `openSession` after
     * attach, or [invalidateForEndpointChange] with `binding == null`) so a
     * later unrelated fork never joins an ancient job.
     *
     * R8-2: the fence lifecycle is decoupled from [lifecycleGeneration]. A
     * non-null endpoint switch only replaces the transport ([binding] != null
     * keeps `_currentSessionId`/`_history`); it hands the fence over, never
     * drops it. Only an explicit history discard (clearChat, openSession
     * attach, binding == null) clears the slot. [pendingLedgerOwnerSessionId]
     * is the `_currentSessionId` at park time (the ledger the next fork
     * snapshots: the lineage the consume re-fills on re-park). The cancel
     * re-park decision itself uses the caller's [explicitDiscardEpoch]
     * snapshot (`entryDiscardEpoch` in `ensureSession`, taken before any
     * suspension) plus `_currentSessionId == previousSessionId` — "same
     * epoch" (no discard crossed) or "same owner" (snapshot target intact)
     * re-parks, otherwise the ledger is dropped as abandoned.
     */
    private var pendingLedgerFence: Job? = null
    private var pendingLedgerSession: ChatSession? = null
    private var pendingLedgerWasStreaming: Boolean = false
    private var pendingLedgerOwnerSessionId: String? = null
    /**
     * R10-1 outstanding-teardown table (Main-confined, guarded by [sessionMutex]
     * like every other lifecycle field): source sessionId → detached ledger's
     * background close-then-flush job + session + streaming evidence. An
     * explicit history discard ([invalidateLifecycle] with `clearChat`,
     * `openSession` preamble for a different id, or
     * [invalidateForEndpointChange] with `binding == null`) clears only the
     * UI/history binding ([pendingLedgerFence] slot + `_currentSessionId`);
     * the detached writer's settle ownership moves here keyed by its source
     * sessionId, even though the binding is gone. A later `openSession(id)`
     * consults this table: a live (unexpired) entry for the requested id
     * forces the slow-path settle (join + flushDurable re-check) before the
     * gated backfill, so a `newChat → immediate reopen` cannot fast-path a
     * false INTERRUPTED while the old writer is still alive. An unrelated id
     * never waits (fast path unchanged). Entries are consumed by the slow
     * settle (success/timeout/dead all remove; failure parks
     * [pendingBackfillBlock] per R9-3), overdue entries are proof-gated on
     * every park/consult (R11-1): an entry past [PENDING_BACKFILL_BLOCK_MS]
     * is removed only with writer-dead/clean proof (fence completed AND
     * session null or sealed-dead); a still-unsettled entry slides its
     * deadline instead of being erased, so a clock deadline bounds UI
     * waiting but never erases ownership while a commit is still possible —
     * and [onCleared] drops the whole table. Never assume "discarded binding =
     * old ledger never reopens".
     */
    private data class OutstandingTeardown(
        var fence: Job?,
        var session: ChatSession?,
        var wasStreaming: Boolean,
        var parkedAtMs: Long,
    )
    private val outstandingTeardowns = HashMap<String, OutstandingTeardown>()
    /**
     * R10-1 helpers (Main, never take [sessionMutex] — callers inside
     * `withLock` already hold it; direct map access avoids deadlock).
     */
    /**
     * R10-2 monotonic clock seam for every backfill/outstanding deadline.
     * Default is [System.nanoTime]-based (monotonic, immune to wall-clock
     * jumps); tests inject a manual clock to shrink the horizon or simulate
     * jumps deterministically. Wall-clock ([System.currentTimeMillis]) must
     * never gate ledger promotion — a jump would unconditionally drop the
     * block and mis-mark a still-live writer's turn.
     */
    internal var backfillClockMs: () -> Long = { System.nanoTime() / 1_000_000L }
    /**
     * R10-2 horizon seam (default [PENDING_BACKFILL_BLOCK_MS]). Tests shrink
     * this to exercise expiry-while-writer-alive without waiting ~30s.
     */
    internal var backfillBlockHorizonMs: Long = PENDING_BACKFILL_BLOCK_MS

    private fun purgeExpiredOutstanding(nowMs: Long = backfillClockMs()) {
        val it = outstandingTeardowns.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value.parkedAtMs < backfillBlockHorizonMs) continue
            // R11-1: never erase a still-unsettled teardown on clock alone.
            // A deadline may bound waiting/UI responsiveness, but must not
            // erase ownership while a commit is still possible. Overdue-but-
            // live entries slide their deadline (same renew semantics as
            // pendingBackfillBlock); only provably-dead/clean entries are
            // removed. S4-F1: shares [outstandingLiveness] with peek.
            if (outstandingLiveness(e.value)) {
                e.value.parkedAtMs = nowMs
            } else {
                it.remove()
            }
        }
    }
    /**
     * R11-1 liveness for the outstanding table: true while the detached
     * writer may still commit its terminal. Fail-closed: any uncertainty
     * keeps the entry (renew, never purge).
     * - Unfinished fence → live (the background close-then-flush still runs).
     * - Sealed-dead session (sealed + no pending + writer completed) → dead:
     *   its terminal can never land late, so a later backfill of its dangling
     *   turn is correct. Seal + empty pending WITHOUT writer completion is
     *   still live (R14-1: timed-out shutdown shape).
     * - Streaming/pending evidence → live.
     * - Unsealed session with no fence completion proof → live (a stalled
     *   delegate / non-cancellable terminal persistence may still commit
     *   despite bounded caller waits and empty session-side flags).
     * - Fence completed + session null or sealed-dead → dead.
     * Best-effort reads only, never throws.
     *
     * S4-F1: single shared liveness ([outstandingLiveness]); [purgeExpiredOutstanding]
     * and [peekOutstandingLive] both consult it so the two can never fork
     * (fence==null + session!=null unexpired stays live/defer, never promote).
     */
    private fun outstandingLiveness(entry: OutstandingTeardown): Boolean {
        try {
            if (entry.fence?.isCompleted == false) return true
        } catch (_: Exception) {
            return true
        }
        val s = entry.session
        if (s == null) {
            // No session left: with the fence finished (or absent) no writer
            // remains to commit. An unfinished fence already returned true.
            return false
        }
        try {
            if (isWriterSealedDead(s)) return false
        } catch (_: Exception) {
        }
        try {
            if (isLedgerStreamingOrPending(s)) return true
        } catch (_: Exception) {
        }
        // Unsealed without session-side evidence: only a completed fence
        // proves the teardown ran to completion. No fence (or an unreadable
        // fence) means no completion proof — keep the entry.
        // R14-1: a sealed session here is NOT dead (sealed-dead above already
        // returned false, i.e. the writer has not completed) — a timed-out
        // shutdown seals and empties pending while the writer is still inside
        // a blocking store block, so keep ownership. Unsealed + pending-empty
        // implies no in-flight block (an in-flight core always occupies
        // pendingCore until the writer's own finally; only the sealed
        // shutdown path clears it early), so a completed fence still proves
        // completion there. Seal reads fail closed (kept).
        return try {
            if (entry.fence == null) return true
            try {
                s.isTranscriptSealed()
            } catch (_: Exception) {
                true
            }
        } catch (_: Exception) {
            true
        }
    }
    /** S4-F1 alias: historic name for the shared [outstandingLiveness]. */
    private fun isOutstandingEntryLive(entry: OutstandingTeardown): Boolean =
        outstandingLiveness(entry)
    private fun rememberOutstandingTeardown(
        owner: String?,
        fence: Job?,
        session: ChatSession?,
        wasStreaming: Boolean,
    ) {
        if (owner == null) return
        if (fence == null && session == null) return
        purgeExpiredOutstanding()
        val nowMs = backfillClockMs()
        val existing = outstandingTeardowns[owner]
        if (existing == null) {
            outstandingTeardowns[owner] = OutstandingTeardown(fence, session, wasStreaming, nowMs)
        } else {
            val mergedFence = when {
                existing.fence != null && fence != null && existing.fence !== fence ->
                    compositeLedgerJoin(existing.fence!!, fence)
                fence != null -> fence
                else -> existing.fence
            }
            existing.fence = mergedFence
            if (session != null) existing.session = session
            existing.wasStreaming = existing.wasStreaming || wasStreaming
            existing.parkedAtMs = nowMs
        }
    }
    /** Move the single-slot fence into the per-session table (single ownership). */
    private fun stashPendingSlotToOutstanding() {
        val fence = pendingLedgerFence
        val session = pendingLedgerSession
        val wasStreaming = pendingLedgerWasStreaming
        val owner = pendingLedgerOwnerSessionId
        if (fence == null && session == null) return
        rememberOutstandingTeardown(owner, fence, session, wasStreaming)
    }
    /** Consume the table entry for [sessionId] (slow-path single ownership). */
    private fun takeOutstanding(sessionId: String): OutstandingTeardown? {
        purgeExpiredOutstanding()
        return outstandingTeardowns.remove(sessionId)
            ?.takeIf { it.fence != null || it.session != null }
    }
    /**
     * R9-3 deferred-backfill block (Main-confined, guarded by [sessionMutex]
     * like every other lifecycle field): sessionId → deadlineMs. A deferred
     * same-ID reopen (`!backfillNow`: settle failed, terminal may land late)
     * parks its id here; a later open with a trivially-successful settle
     * (fresh idle live, old writer still alive) stays deferred while its
     * entry holds. Promotion deletes the entry (ledger proves no dangling
     * family AND the detached live shows no pending evidence); R10-2 expiry
     * no longer deletes it on clock alone — it promotes only on verifiable
     * proof ([tryPromoteExpiredBlock]: ledger clean, or writer-dead proof via
     * seal + empty pending) and otherwise renews the deadline and keeps
     * deferring. Memory-only: a process restart drops
     * every entry (a durable marker would need a single-event delete for its
     * clear semantics — no SessionStore API — and the head-anchored scan
     * loses pruned markers anyway, so the marker stays a noted follow-up and
     * this block is the enforcement).
     */
    private var pendingBackfillBlock = HashMap<String, Long>()
    /**
     * R10-2 pending-writer identity for expiry promotion: sessionId → the
     * unsettled old-writer session observed when the block was parked (the
     * same-ID defer's [detached] live, which still owned the ledger then).
     * A later expiry must not promote on wall-clock alone: it re-reads this
     * writer's liveness (streaming/pending evidence, seal + empty pending +
     *   writer completion) so
     * a still-alive writer keeps deferring while a provably-dead writer may
     * promote even with a dangling ledger (its terminal will never land, so
     * marking INTERRUPTED is correct). Only entries with pending/streaming
     * evidence are stored (a clean fresh-idle detached never overwrites the
     * old writer); cleared together with the block on promotion/expiry-allow
     * and on [onCleared]. Memory-only like the block.
     */
    private var pendingBackfillWriter = HashMap<String, ChatSession?>()
    // R2: unsent texts are owned per send/steer operation, never by a single
    // global String. Cleared only when the controller accepts that exact op
    // (startOrEnqueue Started/Queued verdict); pre-accept endpoint
    // invalidation drains every entry into recoverableOps (never auto-sent
    // to the new binding).
    private data class PendingOp(
        val opId: Long,
        val generation: Long,
        val text: String,
        val retryTargetEpoch: Long = 0L,
        // Stable FIFO provenance assigned when an attempt is created; it only
        // affects explicit recovery if needed. Controller-accepted queue
        // order is established anew by the controller's drained FIFO.
        val recoverySequence: Long? = null,
    )
    private var nextOpId = 0L
    private var nextRetryTargetEpoch = 0L
    private val pendingOps = LinkedHashMap<Long, PendingOp>()
    // Endpoint-cancelled but never accepted: surfaced one-by-one through the
    // input box after each explicit send; discarded by newChat/open/logout.
    private val recoverableOps = ArrayDeque<PendingOp>()
    private var nextRecoverySequence = 0L
    // Q2: every box value has an owner — user input (null tag) or the op
    // that restored it. The recovery sequence preserves its FIFO provenance
    // across restore → retry/direct-send → preaccept denial. Retry adopts the
    // failed intent and consumes the box only while it still holds that
    // unmodified draft (opId + revision + text must all match: no
    // string-equality ownership, no clobbering newer typing, identical
    // independent texts stay separate).
    private var inputRevision = 0L
    private data class InputDraft(
        val opId: Long,
        val revision: Long,
        val text: String,
        val recoverySequence: Long,
    )
    private var inputDraft: InputDraft? = null
    // Latest denied-but-retryable intent (CHAT_SEND_DENIED path). A retry
    // reuses its opId, while retryTargetEpoch distinguishes later attempts.
    private var retryableOp: PendingOp? = null
    // Only controller Started admissions advance this marker. Queued stays
    // caller-owned and must not make an older, still-running gate stale.
    private var latestAcceptedStartedTarget: PendingOp? = null
    // Fallback for post-accept turn errors. This is operation-scoped rather
    // than a String fallback: discarding a recovered op must not revive it via
    // last-user-text equality, while an independent identical send stays valid.
    private var retryFallbackTarget: PendingOp? = null
    private var retryTargetCandidate: PendingOp? = null
    // Main-confined admission guard: policy evaluation suspends before the
    // controller accepts a retry, so ERROR can remain visible during the gate.
    // Identity keeps an obsolete attempt's finally from releasing a newer one.
    private class RetryAttempt(val opId: Long, val text: String) {
        var queuedRetry: Boolean = false
        var queuedSession: ChatSession? = null
    }
    private var retryInFlight: RetryAttempt? = null

    /** Single choke point for all `_input` writes: bumps the revision and (re)tags the owner. */
    private fun setInput(
        value: String,
        draftOpId: Long? = null,
        draftText: String? = null,
        recoverySequence: Long? = null,
    ) {
        _input.value = value
        inputRevision++
        inputDraft = draftOpId?.let {
            InputDraft(it, inputRevision, draftText ?: value, checkNotNull(recoverySequence))
        }
    }
    private var observedBinding: EndpointSessionBinding? = null
    private var hasObservedBinding = false

    init {
        // MainScreen obtains this ViewModel above the NavHost, so it survives
        // SetupScreen saves. Observe the shared DataStore rather than relying on
        // a screen callback to invalidate an already-live transport.
        viewModelScope.launch {
            try {
                store.observe().collect { config ->
                    observeEndpoint(config)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                observeEndpoint(null)
            }
        }
    }

    val canRetry: Boolean
        get() = resolveRetryTarget() != null

    /** In-flight or follow-up pending: a new turn must steer, never send. */
    private fun isBusy(status: ChatStatus): Boolean =
        status == ChatStatus.STREAMING || status == ChatStatus.WAITING_STEERED

    fun onInputChange(value: String) {
        setInput(value)
    }

    /**
     * S2 系統 STT 入口：辨識正文以 prefill 進入輸入框（不自動送出），
     * 由使用者確認後送出；寫庫前經 Redactor（Room 寫路徑）。
     */
    fun prefill(text: String) {
        if (text.isBlank()) return
        setInput(text)
    }

    /**
     * Mint a one-shot key.read confirmation bound to the currently persisted
     * endpoint binding (providerId / effectiveBaseUrl / origin / protocol /
     * apiKeyRef / configRevision / model) plus the mint-start generation.
     *
     * P2 token gate: the caller must also present the pending token captured
     * with the displayed snapshot ([peekKeyReadConfirmToken] at dialog
     * initiation). The mint proceeds only when all three hold: still the
     * original pending request (token == current pending), generation/seq
     * unchanged (pending.generation == lifecycleGeneration and
     * pending.confirmSequence == current sequence), and binding full-field
     * equality (expected == pending.binding == freshly re-read binding).
     * Any mismatch returns false with no mint and zero vault reads. No
     * pending / dismissed / newChat / open / logout stale tap passes.
     *
     * The next [authorizeEndpoint] that observes an ASK verdict consumes it:
     * a binding match allows that single read, any mismatch denies closed.
     * DENY always wins and burns the consent; a policy evaluation failure
     * burns it too and denies closed. A successful mint burns the pending
     * token in the same lock (single-use: the same token never mints twice,
     * even before the consent is consumed). Returns false when no usable endpoint
     * is persisted (no consent minted). A later mint overwrites an
     * unconsumed one; an endpoint replacement keeps the slot (the binding
     * match still rejects the stale snapshot), only a null endpoint
     * (logout) or clearChat (newChat/open) discards it (together with the
     * pending token under the same lock).
     *
     * S6 locking: the final mint (pending recheck + generation recheck +
     * slot assign) shares [keyReadConsentLock] with the bump+burn in
     * [invalidateForEndpointChange] / [invalidateLifecycle], so a racing
     * invalidation cannot be overwritten by a stale mint (the mint aborts)
     * nor leave a stale consent behind (the burn clears under the same lock).
     *
     * S7: production caller is the ChatScreen key.read confirm dialog, which
     * shows the immutable snapshot from [peekKeyReadBinding] plus the token
     * from [peekKeyReadConfirmToken] and mints via
     * [confirmKeyRead(expected, token)][confirmKeyRead]. Minting never
     * auto-sends: the denied text is already restored to the input box, and
     * the user must explicitly send again to consume the consent.
     *
     * S3: production must call [confirmKeyRead(expected, token)][confirmKeyRead]
     * with the displayed snapshot + captured token. The mint fences on
     * full-field equality against the freshly re-read binding: any mismatch
     * fails closed with no mint and no burn (see below). The no-arg
     * confirmKeyReadForTest below is test-only (@VisibleForTesting internal).
     */
    suspend fun confirmKeyRead(
        expected: EndpointSessionBinding?,
        token: PendingKeyReadConfirm?,
    ): Boolean {
        // P2 + S3: null snapshot/token (peek failure/expired/no pending) never mints.
        if (expected == null || token == null) return false
        // P2 fail-fast before any IO (zero vault reads on stale tap): the
        // presented token must still be the current pending, with current
        // generation/sequence and matching binding.
        val pendingSnapshot = synchronized(keyReadConsentLock) { pendingKeyReadConfirm }
        if (pendingSnapshot == null) return false
        if (pendingSnapshot != token) return false
        if (pendingSnapshot.confirmSequence != _keyReadConfirmSequence.value) return false
        if (pendingSnapshot.generation != lifecycleGeneration) return false
        if (expected != pendingSnapshot.binding) return false
        val startedGeneration = lifecycleGeneration
        if (startedGeneration != token.generation) return false
        val endpoint = try {
            readEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        if (!isGenerationCurrent(startedGeneration)) return false
        val binding = bindingOf(endpoint) ?: return false
        if (!isGenerationCurrent(startedGeneration)) return false
        // S3 snapshot fence: the displayed snapshot must equal the mint-time
        // binding on every field (data-class equality covers providerId /
        // effectiveBaseUrl / origin / protocol / apiKeyRef / configRevision /
        // model). A mismatch (peek A, endpoint now B) fails closed: no mint.
        // Deliberately no burn here: the mismatch is a display-stale no-op,
        // and the slot (if any) belongs to another generation/binding whose
        // own match still rejects it. Burning on a stale click would conflate
        // it with an explicit dismiss and could destroy a just-minted valid
        // consent racing the click.
        if (binding != expected) return false
        return mintKeyReadConsentWithToken(startedGeneration, binding, token)
    }

    /**
     * Test-only mint without a snapshot fence (no displayed binding to check).
     * Production (ChatScreen dialog) must use [confirmKeyRead(expected, token)][confirmKeyRead]
     * so a stale dialog snapshot can never mint for a replaced endpoint.
     * Renamed + internal (cf. VoiceSpeaker preEmitHook/postCheckHook
     * @VisibleForTesting internal convention) so a production call site cannot
     * accidentally use the unfenced overload: only confirmKeyRead(expected, token)
     * is public.
     */
    @VisibleForTesting
    internal suspend fun confirmKeyReadForTest(): Boolean {
        // Capture identity before the IO read: an invalidation clearing or
        // replacing the endpoint during the read (newChat/open/logout) must
        // stop this mint. Returning false keeps the consent absent.
        val startedGeneration = lifecycleGeneration
        val endpoint = try {
            readEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        if (!isGenerationCurrent(startedGeneration)) return false
        val binding = bindingOf(endpoint) ?: return false
        if (!isGenerationCurrent(startedGeneration)) return false
        return mintKeyReadConsent(startedGeneration, binding)
    }

    /** Final mint for the test-only unfenced path (generation recheck + slot assign under lock). */
    private fun mintKeyReadConsent(startedGeneration: Long, binding: EndpointSessionBinding): Boolean {
        val consent = KeyReadConsent(
            action = "key.read",
            providerId = binding.providerId,
            effectiveBaseUrl = binding.effectiveBaseUrl,
            origin = binding.origin,
            protocol = binding.protocol,
            apiKeyRef = binding.apiKeyRef,
            configRevision = binding.configRevision,
            model = binding.model,
            generation = startedGeneration,
        )
        synchronized(keyReadConsentLock) {
            if (!isGenerationCurrent(startedGeneration)) return false
            pendingKeyReadConsent = consent
        }
        return true
    }

    /**
     * P2 final mint with token recheck (pending + generation + seq + binding
     * equality under the same lock as bump+burn). A racing invalidation
     * (bump+burn) or newer deny (pending overwrite + seq bump) aborts the
     * stale mint; a successful mint burns the pending token in the same lock
     * (single-use: the same token can never mint twice — a replay with the
     * same token after mint or after T0 consume fails closed with zero vault
     * reads). Only a still-pending deny can mint; a mismatch still burns
     * nothing (see confirmKeyRead), and invalidate/dismiss/logout burn both
     * slots.
     */
    private fun mintKeyReadConsentWithToken(
        startedGeneration: Long,
        binding: EndpointSessionBinding,
        token: PendingKeyReadConfirm,
    ): Boolean {
        val consent = KeyReadConsent(
            action = "key.read",
            providerId = binding.providerId,
            effectiveBaseUrl = binding.effectiveBaseUrl,
            origin = binding.origin,
            protocol = binding.protocol,
            apiKeyRef = binding.apiKeyRef,
            configRevision = binding.configRevision,
            model = binding.model,
            generation = startedGeneration,
        )
        synchronized(keyReadConsentLock) {
            val currentPending = pendingKeyReadConfirm ?: return false
            if (currentPending != token) return false
            if (currentPending.confirmSequence != _keyReadConfirmSequence.value) return false
            if (currentPending.generation != lifecycleGeneration) return false
            if (!isGenerationCurrent(startedGeneration)) return false
            if (startedGeneration != currentPending.generation) return false
            if (binding != currentPending.binding) return false
            pendingKeyReadConsent = consent
            // Single-use: burn the token in the same critical section so a
            // replay with this token (after mint, or after T0 consumes the
            // consent) fails closed on the null-pending check above.
            pendingKeyReadConfirm = null
        }
        return true
    }

    /**
     * Abandon an unconsumed key.read confirmation and its pending token, if
     * any. Single critical section: consent + token clear atomically, so an
     * in-flight confirm parked in readEndpoint cannot slip a mint between a
     * split take and token clear and leave a reusable consent behind.
     */
    fun dismissKeyReadConfirm() {
        synchronized(keyReadConsentLock) {
            pendingKeyReadConsent = null
            pendingKeyReadConfirm = null
        }
    }

    /**
     * S7 read-only snapshot for the key.read confirm dialog.
     *
     * Reads the persisted endpoint and maps it to the immutable
     * [EndpointSessionBinding] shown in the dialog (providerId /
     * effectiveBaseUrl / origin / protocol / apiKeyRef / configRevision /
     * model). Mints no consent, touches no consent slot, and is generation
     * insensitive: a newer generation does not invalidate the returned
     * snapshot (the later [confirmKeyRead] mint still fences on the current
     * generation and fails closed when stale). Any read/mapping failure
     * returns null (fail-closed: the dialog shows expired instead of a
     * partial snapshot). Never carries key material.
     */
    suspend fun peekKeyReadBinding(): EndpointSessionBinding? {
        return try {
            val endpoint = readEndpoint()
            bindingOf(endpoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * P2 read-only pending token for the key.read confirm dialog.
     *
     * Returns the current [PendingKeyReadConfirm] created with the latest
     * [emitKeyReadConfirmRequired] emission, or null when there is no
     * pending request (never denied / already burned by newChat/open/logout/
     * replacement-dismiss / consumed-then-rearmed). Mints nothing, touches
     * no slot. The dialog must capture this together with the binding
     * snapshot and present both to [confirmKeyRead]; a stale tap with a
     * mismatched or nulled token fails closed with zero vault reads.
     */
    fun peekKeyReadConfirmToken(): PendingKeyReadConfirm? =
        synchronized(keyReadConsentLock) { pendingKeyReadConfirm }

    /** Consume-once take: the returned consent (if any) can never be used again. */
    private fun takePendingKeyReadConsent(): KeyReadConsent? =
        synchronized(keyReadConsentLock) {
            val consent = pendingKeyReadConsent
            pendingKeyReadConsent = null
            consent
        }

    private fun keyReadConsentMatches(
        consent: KeyReadConsent,
        binding: EndpointSessionBinding,
        authorizedGeneration: Long,
    ): Boolean {
        if (consent.action != "key.read") return false
        // S6 data-inner fence: the mint-start generation must equal the
        // authorizing operation's generation. Timing checks alone
        // (isGenerationCurrent before/after the policy suspension) cannot
        // reject an ABA replay where the binding returns to an identical
        // snapshot on a newer generation.
        if (consent.generation != authorizedGeneration) return false
        return consent.providerId == binding.providerId &&
            consent.effectiveBaseUrl == binding.effectiveBaseUrl &&
            consent.origin == binding.origin &&
            consent.protocol == binding.protocol &&
            consent.apiKeyRef == binding.apiKeyRef &&
            consent.configRevision == binding.configRevision &&
            consent.model == binding.model
    }

    fun send() {
        val raw = _input.value
        val text = raw.trim()
        if (text.isEmpty()) return
        // Q2: adopt the tagged draft when the box still holds that exact
        // unmodified restore; otherwise this explicit send supersedes any
        // retryable intent (abandoned by user action, never duplicated).
        val draft = inputDraft
        val adoptedId =
            if (draft != null && draft.revision == inputRevision && draft.text == raw) draft.opId else null
        val adoptedRecoverySequence = if (adoptedId != null) draft?.recoverySequence else null
        if (adoptedId != null) recoverableOps.removeAll { it.opId == adoptedId }
        else retryableOp = null
        syncRecoveryCount()
        setInput("")
        if (isBusy(_sessionState.value.status)) {
            steer(text, adoptedId, adoptedRecoverySequence)
        } else {
            if (adoptedId != null) {
                launchSendOp(adoptedId, text, lifecycleGeneration, recoverySequence = adoptedRecoverySequence)
            }
            else sendText(text)
        }
    }

    /** Entry path: normalized text goes straight out (no input box round-trip). */
    fun sendDirect(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (isBusy(_sessionState.value.status)) {
            steer(clean)
            return
        }
        // N2: a tagged recoverable draft in the box (deny/endpoint-cancel
        // restore) is owned recovery state, not abandoned typing: stash it to
        // the outbox before clearing, instead of losing it silently. This
        // explicit direct-send is newer than that recovery and replaces its
        // Retry target, but never auto-sends or discards the stashed draft.
        stashInputDraftToRecoverable()
        setInput("")
        sendText(clean)
    }

    /** Queue an instruction for the next round; never preempts the live turn. */
    fun steer(text: String, adoptedOpId: Long? = null) = steer(text, adoptedOpId, null)

    private fun steer(text: String, adoptedOpId: Long?, recoverySequence: Long?) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val startedAt = lifecycleGeneration
        // Q2: a fresh steer mints a new op and supersedes any retryable
        // intent; an adopted retry reuses its opId (no outbox growth).
        // N2: supersede only applies when the box holds no live tagged
        // draft — a visible restored draft stays retryable while the fresh
        // steer goes out alongside it.
        val opId = adoptedOpId ?: ++nextOpId
        val pending = beginRetryTargetCandidate(opId, clean, recoverySequence)
        if (adoptedOpId == null && !isBoxHoldingTaggedDraft()) retryableOp = null
        pendingOps[opId] = pending.copy(generation = startedAt)
        lifecycleScope.launch {
            var operationGeneration = startedAt
            try {
                when (val access = authorizeEndpoint(startedAt)) {
                    is EndpointAccess.Ready -> {
                        operationGeneration = access.endpoint.generation
                        val handle = try {
                            ensureSession(access.endpoint, clean)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: CaptureDeniedException) {
                            // S1: burn a consent minted between T0 and T1 so
                            // a T1 deny leaves no residual slot for replay.
                            // CancellationException stays on its own branch
                            // above and never burns.
                            takePendingKeyReadConsent()
                            // S6 capture-boundary T1 DENY/failure (factory
                            // CaptureDeniedException): T0 key.read passed but
                            // T1 failed. Distinct from chat.send CHAT_SEND_DENIED
                            // below (the inner startOrEnqueue catch stays).
                            // Only CaptureDeniedException maps to POLICY_DENIED
                            // here; a non-Capture SecurityException falls
                            // through to the outer NO_ENDPOINT branch.
                            setNoticeIfCurrent(operationGeneration, "POLICY_DENIED")
                            restoreOpForFailedSend(opId, "POLICY_DENIED")
                            return@launch
                        }
                        if (handle == null || !isHandleCurrent(handle)) {
                            // The generation is dead or the session was
                            // replaced: endpoint invalidation (or explicit
                            // discard) already owns this op's entry, so leave
                            // it for the drain/clear path instead of dropping
                            // or double-restoring here.
                            return@launch
                        }
                        operationGeneration = handle.generation
                        // ensureSession may replace a mismatched transport
                        // under a bumped generation; re-root the op so a
                        // chat.send deny still restores to input instead of
                        // stranding on the stale generation.
                        pendingOps[opId] = checkNotNull(pendingOps[opId]).copy(generation = handle.generation)
                        // Q1: single atomic admission. startOrEnqueue decides
                        // start-vs-queue under the controller's lock, so the
                        // verdict is never stale: Started owns the text
                        // (remove + join), Queued stays owned by the caller
                        // (remove + return, reclaimable via drainQueued on
                        // endpoint teardown — see invalidateForEndpointChange).
                        // Anything thrown before either verdict leaves the op
                        // for invalidate/restore.
                        // Never treat a bare steer() return as acceptance:
                        // its idle path is fire-and-forget pre-gate.
                        var recoveredAdmissionRace = false
                        var started: TurnStart? = null
                        while (started == null) {
                            val discardEpoch = explicitDiscardEpoch
                            val endpointEpoch = endpointReplacementEpoch
                            val verdict = try {
                                handle.created.session.startOrEnqueue(clean, opId = opId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: IllegalStateException) {
                                // Closed session: the teardown path (invalidation
                                // drain or explicit discard) owns this op.
                                return@launch
                            } catch (_: ApprovalRequiredException) {
                                setNoticeIfCurrent(operationGeneration, "CHAT_APPROVAL_REQUIRED")
                                restoreOpForFailedSend(opId, "CHAT_APPROVAL_REQUIRED")
                                return@launch
                            } catch (_: PolicyEvaluationException) {
                                setNoticeIfCurrent(operationGeneration, "CHAT_POLICY_UNAVAILABLE")
                                restoreOpForFailedSend(opId, "CHAT_POLICY_UNAVAILABLE")
                                return@launch
                            } catch (_: SecurityException) {
                                // Fresh chat.send deny is projected by the
                                // controller; keep the text recoverable. Distinct
                                // code from key.read POLICY_DENIED: key.read passed.
                                setNoticeIfCurrent(operationGeneration, "CHAT_SEND_DENIED")
                                restoreOpForFailedSend(opId, "CHAT_SEND_DENIED")
                                return@launch
                            } catch (_: InputTooLongException) {
                                // R4-4 admission fail-fast: single-turn input +
                                // time block exceeds MAX_REQUEST_CHARS. Never
                                // sent; keep the text recoverable (draft).
                                setNoticeIfCurrent(operationGeneration, "CHAT_INPUT_TOO_LONG")
                                restoreOpForFailedSend(opId, "CHAT_INPUT_TOO_LONG")
                                return@launch
                            } catch (_: TranscriptPendingException) {
                                // R5-1/R6-2 UNKNOWN/PENDING_COMMIT (timeout-after-send /
                                // external-cancel-after-send):
                                // ownership retained by the controller, same-opId
                                // resend blocked until settle. Do NOT restore
                                // (no draft, no retry yet); project pending and
                                // flip on settle (claimed → accept, failed →
                                // UNAVAILABLE retryable).
                                setNoticeIfCurrent(operationGeneration, "CHAT_STORE_PENDING")
                                launchPendingSettleWaiter(opId, clean, handle, operationGeneration)
                                return@launch
                            } catch (_: TranscriptDurabilityException) {
                                // Stage 6 (R4-3) admission fail-closed: the user
                                // row could not be durably persisted within the
                                // bounded timeout (store stall/seal). Same
                                // restore semantics as InputTooLong (notice +
                                // draft, caller retains the text for retry).
                                setNoticeIfCurrent(operationGeneration, "CHAT_STORE_UNAVAILABLE")
                                restoreOpForFailedSend(opId, "CHAT_STORE_UNAVAILABLE")
                                return@launch
                            }
                            if (verdict !is TurnStart.NeedsRecovery) {
                                if (verdict is TurnStart.Started) {
                                    recordStartedRetryTarget(opId, clean)
                                } else {
                                    recordQueuedRetryTarget(opId, clean, handle.created.session)
                                }
                                started = verdict
                                continue
                            }
                            if (!isHandleCurrent(handle)) {
                                handoffStaleRecovery(
                                    verdict.queuedIntents,
                                    handle,
                                    discardEpoch,
                                    endpointEpoch,
                                )
                                return@launch
                            }
                            val retryTarget = absorbQueuedRecovery(
                                handle.created.session,
                                verdict.queuedIntents,
                                handle.generation,
                            )
                            if (!isHandleCurrent(handle)) return@launch
                            rebasePendingAdmissionAfterRecovery(opId, clean, handle.generation)
                            if (recoveredAdmissionRace) {
                                // A second independent promotion deny raced the
                                // retry. Preserve this send without unbounded
                                // admission retries; keep Retry aimed at the
                                // denied queued operation, not this fresh send.
                                restoreOpForFailedSend(opId, "CHAT_SEND_DENIED")
                                if (retryTarget != null) retryableOp = retryTarget
                                return@launch
                            }
                            recoveredAdmissionRace = true
                        }
                        val admission = checkNotNull(started)
                        pendingOps.remove(opId)
                        if (retryableOp?.opId == opId) retryableOp = null
                        val host = (admission as? TurnStart.Started)?.host ?: return@launch
                        try {
                            host.join()
                        } catch (cancelled: CancellationException) {
                            if (coroutineContext[Job]?.isCancelled == true) throw cancelled
                        } catch (_: Exception) {
                            // Turn failed after acceptance: transcript keeps
                            // the user message; nothing to restore.
                        }
                        drainNextRecoverableToInput()
                    }
                    EndpointAccess.Denied -> {
                        setNoticeIfCurrent(operationGeneration, "POLICY_DENIED")
                        restoreOpForFailedSend(opId, "POLICY_DENIED")
                    }
                    is EndpointAccess.ConfirmRequired -> {
                        // P2:還原先於發布（input/outbox 就緒後才設 notice+seq+token），
                        // 使觀測到 notice 即輸入已還原（消除 notice→input 競態）。
                        restoreOpForConfirmRequired(opId, "KEY_READ_CONFIRM_REQUIRED")
                        emitKeyReadConfirmRequired(access.generation, access.binding, opId = opId)
                    }
                    EndpointAccess.Missing -> {
                        setNoticeIfCurrent(operationGeneration, "NO_ENDPOINT")
                        restoreOpForFailedSend(opId, "NO_ENDPOINT")
                    }
                    EndpointAccess.Stale -> pendingOps.remove(opId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setNoticeIfCurrent(operationGeneration, "NO_ENDPOINT")
                restoreOpForFailedSend(opId, "NO_ENDPOINT")
            }
        }
    }

    fun retry() {
        val (op, retryText) = resolveRetryTarget() ?: return
        if (retryInFlight != null) return
        val retryOpId = op?.opId ?: ++nextOpId
        val attempt = RetryAttempt(retryOpId, retryText)
        retryInFlight = attempt

        try {
            if (op == null) {
                // Post-accept failure (e.g. provider error mid-turn): no denied
                // intent is tracked for this send, so resend the exact accepted
                // fallback target as a fresh op. A newer explicit direct-send
                // already supersedes any older retryable intent; stashed work
                // stays recoverable without taking this Retry slot.
                sendText(retryText, retryAttempt = attempt, opId = retryOpId)
                return
            }
            // Q2: adopt the failed intent under its own opId. Consume the box
            // only while it still holds that exact unmodified restore; newer
            // typing is never touched. Repeated deny→retry reuses the opId, so
            // the outbox cannot grow copies of one intent.
            val draft = inputDraft
            if (draft != null && draft.opId == op.opId && draft.revision == inputRevision &&
                draft.text == _input.value
            ) {
                setInput("")
            }
            recoverableOps.removeAll { it.opId == op.opId }
            syncRecoveryCount()
            launchSendOp(
                op.opId,
                op.text,
                lifecycleGeneration,
                retryAttempt = attempt,
                recoverySequence = op.recoverySequence,
            )
        } catch (failure: Throwable) {
            finishRetryAttempt(attempt)
            throw failure
        }
    }

    private fun sendText(text: String, retryAttempt: RetryAttempt? = null, opId: Long? = null) {
        // A fresh explicit send supersedes any retryable intent.
        retryableOp = null
        val sendOpId = opId ?: ++nextOpId
        launchSendOp(sendOpId, text, lifecycleGeneration, retryAttempt)
    }

    /** Shared send coroutine: minters pass a fresh opId, retry passes the adopted one. */
    private fun launchSendOp(
        opId: Long,
        text: String,
        startedAt: Long,
        retryAttempt: RetryAttempt? = null,
        recoverySequence: Long? = null,
    ) {
        val pending = beginRetryTargetCandidate(opId, text, recoverySequence)
        _notice.value = null
        pendingOps[opId] = pending.copy(generation = startedAt)
        lifecycleScope.launch {
            var operationGeneration = startedAt
            try {
                when (val access = authorizeEndpoint(startedAt)) {
                    is EndpointAccess.Ready -> {
                        operationGeneration = access.endpoint.generation
                        val handle = try {
                            ensureSession(access.endpoint, text)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: CaptureDeniedException) {
                            // S1: burn a consent minted between T0 and T1 so
                            // a T1 deny leaves no residual slot for replay.
                            // CancellationException stays on its own branch
                            // above and never burns.
                            takePendingKeyReadConsent()
                            // S6 capture-boundary T1 DENY/failure (factory
                            // CaptureDeniedException): T0 key.read passed but
                            // T1 failed. Distinct from chat.send CHAT_SEND_DENIED
                            // below (the inner startOrEnqueue catch stays).
                            // Only CaptureDeniedException maps to POLICY_DENIED
                            // here; a non-Capture SecurityException falls
                            // through to the outer NO_ENDPOINT branch.
                            setNoticeIfCurrent(operationGeneration, "POLICY_DENIED")
                            restoreOpForFailedSend(opId, "POLICY_DENIED")
                            return@launch
                        }
                        if (handle == null || !isHandleCurrent(handle)) {
                            // The generation is dead or the session was
                            // replaced: endpoint invalidation (or explicit
                            // discard) already owns this op's entry.
                            return@launch
                        }
                        operationGeneration = handle.generation
                        // ensureSession may replace a mismatched transport
                        // under a bumped generation; re-root the op so a
                        // chat.send deny still restores to input instead of
                        // stranding on the stale generation.
                        pendingOps[opId] = checkNotNull(pendingOps[opId]).copy(generation = handle.generation)
                        // Q1: single atomic admission (see steer() above).
                        // Started owns the text (remove + join); Queued stays
                        // owned by the caller (remove + return, reclaimable
                        // via drainQueued on endpoint teardown). Anything
                        // thrown before either verdict leaves the op for
                        // invalidate/restore. Never treat a bare steer()
                        // return as acceptance.
                        var recoveredAdmissionRace = false
                        var started: TurnStart? = null
                        while (started == null) {
                            val discardEpoch = explicitDiscardEpoch
                            val endpointEpoch = endpointReplacementEpoch
                            val verdict = try {
                                handle.created.session.startOrEnqueue(text, opId = opId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: IllegalStateException) {
                                // Closed session: the teardown path (invalidation
                                // drain or explicit discard) owns this op.
                                return@launch
                            } catch (_: ApprovalRequiredException) {
                                setNoticeIfCurrent(operationGeneration, "CHAT_APPROVAL_REQUIRED")
                                restoreOpForFailedSend(opId, "CHAT_APPROVAL_REQUIRED")
                                return@launch
                            } catch (_: PolicyEvaluationException) {
                                setNoticeIfCurrent(operationGeneration, "CHAT_POLICY_UNAVAILABLE")
                                restoreOpForFailedSend(opId, "CHAT_POLICY_UNAVAILABLE")
                                return@launch
                            } catch (_: SecurityException) {
                                // Fresh chat.send deny is projected by the
                                // controller; keep the text recoverable.
                                setNoticeIfCurrent(operationGeneration, "CHAT_SEND_DENIED")
                                restoreOpForFailedSend(opId, "CHAT_SEND_DENIED")
                                return@launch
                            } catch (_: InputTooLongException) {
                                // R4-4 admission fail-fast: single-turn input +
                                // time block exceeds MAX_REQUEST_CHARS. Never
                                // sent; keep the text recoverable (draft).
                                setNoticeIfCurrent(operationGeneration, "CHAT_INPUT_TOO_LONG")
                                restoreOpForFailedSend(opId, "CHAT_INPUT_TOO_LONG")
                                return@launch
                            } catch (_: TranscriptPendingException) {
                                // R5-1/R6-2 UNKNOWN/PENDING_COMMIT: same as the steer
                                // path above (no restore; settle waiter flips).
                                setNoticeIfCurrent(operationGeneration, "CHAT_STORE_PENDING")
                                launchPendingSettleWaiter(opId, text, handle, operationGeneration)
                                return@launch
                            } catch (_: TranscriptDurabilityException) {
                                // Stage 6 (R4-3) admission fail-closed: same
                                // restore semantics as InputTooLong.
                                setNoticeIfCurrent(operationGeneration, "CHAT_STORE_UNAVAILABLE")
                                restoreOpForFailedSend(opId, "CHAT_STORE_UNAVAILABLE")
                                return@launch
                            }
                            if (verdict !is TurnStart.NeedsRecovery) {
                                if (verdict is TurnStart.Queued) {
                                    retryAttempt?.let { markRetryQueued(it, handle.created.session) }
                                }
                                if (verdict is TurnStart.Started) {
                                    recordStartedRetryTarget(opId, text)
                                } else {
                                    recordQueuedRetryTarget(opId, text, handle.created.session)
                                }
                                started = verdict
                                continue
                            }
                            if (!isHandleCurrent(handle)) {
                                handoffStaleRecovery(
                                    verdict.queuedIntents,
                                    handle,
                                    discardEpoch,
                                    endpointEpoch,
                                )
                                return@launch
                            }
                            val retryTarget = absorbQueuedRecovery(
                                handle.created.session,
                                verdict.queuedIntents,
                                handle.generation,
                            )
                            if (!isHandleCurrent(handle)) return@launch
                            rebasePendingAdmissionAfterRecovery(opId, text, handle.generation)
                            if (recoveredAdmissionRace) {
                                restoreOpForFailedSend(opId, "CHAT_SEND_DENIED")
                                if (retryTarget != null) retryableOp = retryTarget
                                return@launch
                            }
                            recoveredAdmissionRace = true
                        }
                        val admission = checkNotNull(started)
                        pendingOps.remove(opId)
                        if (admission is TurnStart.Started && retryableOp?.opId == opId) retryableOp = null
                        val host = (admission as? TurnStart.Started)?.host ?: return@launch
                        try {
                            host.join()
                        } catch (cancelled: CancellationException) {
                            if (coroutineContext[Job]?.isCancelled == true) throw cancelled
                        } catch (_: Exception) {
                            // Turn failed after acceptance: the transcript
                            // keeps the user message; nothing to restore.
                        }
                        drainNextRecoverableToInput()
                    }
                    EndpointAccess.Denied -> {
                        setNoticeIfCurrent(operationGeneration, "POLICY_DENIED")
                        restoreOpForFailedSend(opId, "POLICY_DENIED")
                    }
                    is EndpointAccess.ConfirmRequired -> {
                        // P2:還原先於發布（同上，消除 notice→input 競態）。
                        restoreOpForConfirmRequired(opId, "KEY_READ_CONFIRM_REQUIRED")
                        emitKeyReadConfirmRequired(access.generation, access.binding, opId = opId)
                    }
                    EndpointAccess.Missing -> {
                        setNoticeIfCurrent(operationGeneration, "NO_ENDPOINT")
                        restoreOpForFailedSend(opId, "NO_ENDPOINT")
                    }
                    EndpointAccess.Stale -> pendingOps.remove(opId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setNoticeIfCurrent(operationGeneration, "NO_ENDPOINT")
                restoreOpForFailedSend(opId, "NO_ENDPOINT")
            } finally {
                retryAttempt?.takeUnless { it.queuedRetry }?.let(::finishRetryAttempt)
            }
        }
    }

    /** Queued retry ownership is released only by exact opId evidence, never by a count delta. */
    private fun markRetryQueued(attempt: RetryAttempt, session: ChatSession) {
        attempt.queuedRetry = true
        attempt.queuedSession = session
        confirmQueuedRetryAccepted(session, session.uiState.value)
        resolveRetryAlreadyRecovered(attempt)
    }

    private fun confirmQueuedRetryAccepted(session: ChatSession, state: ChatUiState) {
        val attempt = retryInFlight?.takeIf {
            it.queuedRetry && it.queuedSession === session && state.messages.any { message ->
                message.role == "user" && message.operationId == it.opId
            }
        } ?: return
        if (retryableOp?.opId == attempt.opId) retryableOp = null
        finishRetryAttempt(attempt)
    }

    /** Restore a drained retry's exact identity before permitting another click. */
    private fun resolveQueuedRetryFromRecovery(drained: List<dev.librepocket.chat.QueuedIntent>) {
        val attempt = retryInFlight?.takeIf { queuedAttempt ->
            queuedAttempt.queuedRetry && drained.any { it.opId == queuedAttempt.opId }
        } ?: return
        val queued = drained.first { it.opId == attempt.opId }
        val recovered = inputDraft?.takeIf { it.opId == attempt.opId }?.let {
            PendingOp(it.opId, lifecycleGeneration, it.text, recoverySequence = it.recoverySequence)
        } ?: recoverableOps.firstOrNull { it.opId == attempt.opId }
            ?: PendingOp(attempt.opId, lifecycleGeneration, queued.text)
        promoteQueuedRetryTarget(recovered)
        finishRetryAttempt(attempt)
    }

    private fun resolveRetryAlreadyRecovered(attempt: RetryAttempt) {
        if (retryInFlight !== attempt) return
        val recovered = inputDraft?.takeIf { it.opId == attempt.opId }?.let {
            PendingOp(it.opId, lifecycleGeneration, it.text, recoverySequence = it.recoverySequence)
        } ?: recoverableOps.firstOrNull { it.opId == attempt.opId } ?: return
        setRetryableIfNewer(recovered)
        finishRetryAttempt(attempt)
    }

    private fun finishRetryAttempt(attempt: RetryAttempt) {
        if (retryInFlight === attempt) retryInFlight = null
    }

    fun cancel() {
        currentSession?.cancel()
    }

    fun newChat() {
        invalidateLifecycle(clearChat = true)
    }

    /** Resume an existing transcript session (history replay + live binding). */
    fun openSession(sessionId: String) {
        // R8-3 same-ID reopen fence: snapshot before invalidateLifecycle
        // clears it. invalidate (clearChat=true) synchronously drops the
        // fence slot and nulls _currentSessionId, so after it runs a reopen
        // can no longer tell it targets the live ledger. Take one-time
        // ownership of the fence slot here (mirrors the ensureSession
        // consume); a different-ID reopen drops it (fast path: the backfill
        // targets another ledger). A same-ID reopen detaches its live
        // session now so the invalidate below does not launch a second
        // concurrent teardown for it — the slow-path IO settles it exactly
        // once, then reads, then backfills gated on the settle outcome.
        val prevId: String? = _currentSessionId.value
        val prevLive: ChatSession? = currentSession
        val fencedJob0: Job? = pendingLedgerFence
        val fencedSession0: ChatSession? = pendingLedgerSession
        val fencedWasStreaming0: Boolean = pendingLedgerWasStreaming
        val fencedOwner0: String? = pendingLedgerOwnerSessionId
        // S4-R2 preamble: snapshot slot/table hints here (no take/clear yet –
        // authoritative take/remember/clear live under sessionMutex below);
        // the sync ticket + sync parks run after the ticket (see below).
        val isSameIdReopen = prevId != null && prevId == sessionId
        // R9-2 from-null owner-match: an abort/early-exit already nulled
        // _currentSessionId, so prevId==null even though the parked fence
        // still owns the requested ledger (owner==sessionId). Dropping it
        // here would send the reopen down the different-ID fast path
        // (unfenced loadHistory + open(...,true) hard backfill) while the
        // teardown is still unsettled — a false INTERRUPTED. An
        // owner-matched reopen therefore retains the fence and is treated
        // as the same-ID slow path (fence join + parked flushDurable
        // re-check, no live needed), even when prevLive==null.
        var isOwnerMatchedReopen = !isSameIdReopen &&
            fencedOwner0 != null &&
            fencedOwner0 == sessionId &&
            (fencedJob0 != null || fencedSession0 != null)
        // R10-1 cross-discard ownership: a newChat/open-other already nulled
        // _currentSessionId and stashed the detached writer into
        // outstandingTeardowns keyed by its source id, so prevId==null and
        // the slot is empty even though the old writer for the requested id
        // is still settling. Consult the table (single ownership via take);
        // an unrelated id never waits. Take before stashing the unrelated
        // slot below so the requested entry is never merged into another id.
        // S4-F2: read-only hint here (no purge/remove); authoritative take
        // happens inside withLock (first segment) under mutex.
        val takenOutstandingHint = outstandingTeardowns[sessionId]
        val outstandingFenceHint: Job? = takenOutstandingHint?.fence
        val outstandingSessionHint: ChatSession? = takenOutstandingHint?.session
        val outstandingWasStreamingHint: Boolean = takenOutstandingHint?.wasStreaming ?: false
        val hasOutstandingHint = outstandingFenceHint != null || outstandingSessionHint != null
        // Authoritative locals (set under lock below, overwrite hints).
        // Placeholders so closure captures compile; real values assigned
        // in the withLock first segment.
        var takenOutstanding: OutstandingTeardown? = null
        var outstandingFence: Job? = null
        var outstandingSession: ChatSession? = null
        var outstandingWasStreaming: Boolean = false
        var hasOutstanding: Boolean = false
        if (!isSameIdReopen && !isOwnerMatchedReopen) {
            // Preserve an unrelated slot fence for its own future reopen
            // instead of dropping it (R10-1: per-id consult only). When this
            // branch runs with a non-null slot, its owner differs from the
            // requested id (otherwise it would have been owner-matched), so
            // stashing cannot pollute this open's settle.
            // S4-R2: stashed sync after the ticket below (see sync parks) so a
            // pre-dispatch cancel cannot leak it; the lock re-checks idempotently.
        }
        // Effective slot fence for this open's settle: only when it targets
        // the requested ledger. An unrelated slot (stashed above) must not
        // gate this ledger's backfill. Hint values here; authoritative
        // recompute under lock below overwrites them.
        var effectiveFencedJob: Job? = if (isSameIdReopen || isOwnerMatchedReopen) fencedJob0 else null
        var effectiveFencedSession: ChatSession? =
            if (isSameIdReopen || isOwnerMatchedReopen) fencedSession0 else null
        var effectiveFencedWasStreaming: Boolean =
            if (isSameIdReopen || isOwnerMatchedReopen) fencedWasStreaming0 else false
        var effectiveFencedOwner: String? =
            if (isSameIdReopen || isOwnerMatchedReopen) fencedOwner0 else null
        var isOutstandingReopen = hasOutstandingHint
        var isSlowReopen = isSameIdReopen || isOwnerMatchedReopen || isOutstandingReopen
        // R9-2: same-ID reopen owns its teardown eagerly (sync area). The
        // live turn is cancelled IO-free via cancelAndDetachSync (semantics
        // unchanged), then close+flush launches immediately on viewModelScope
        // (NOT lifecycleScope, so cancelLifecycleOperations below cannot kill
        // it; TurnController.close is idempotent, double-close safe). The
        // closure hands sameIdTeardown+prevLive to the slow path, which joins
        // (2x) + flushDurable re-checks (parked pattern) instead of a second
        // closeThenFlushDurable. Early-exit/cancel re-parks (owner=prevId).
        // S4-R2 sync ticket (last-wins): bump generation + cancel prev open
        // synchronously in the preamble (Main, no suspension). A double-tap
        // while the first open suspends on IO cancels the first (its generation
        // goes stale + its openJob is cancelled); the lock below only does
        // authoritative slot/table ownership, never generation/UI.
        var prevWasStreaming = if (prevLive != null) {
            try {
                isLedgerStreamingOrPending(prevLive)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        // Launch deferred to the lock first segment (no pre-dispatch leak:
        // nothing launched here, so a cancel before dispatch owns nothing).
        var sameIdTeardown: Job? = null
        // Sync ticket: explicit discard + new lifecycle scope (retryInFlight
        // null + scope renew, same as invalidateLifecycle) + cancel prev open.
        // A teardown launch below lives on viewModelScope, never the
        // just-invalidated lifecycle scope, so it survives this cancel.
        lifecycleGeneration += 1
        explicitDiscardEpoch += 1
        cancelLifecycleOperations()
        openJob?.cancel()
        openJob = null
        // Sync detach (UI never waits, IO-free): VM state detached now so the
        // UI updates immediately; the close-then-flush launch + slot/table
        // ownership happen under the mutex below (same-ID and different-ID
        // alike – ownership only in lock).
        if (prevLive != null) {
            cancelAndDetachSync(prevLive)
        }
        // Sync UI clear (no lock): history/binding/ops reset now, never while
        // holding sessionMutex (lock below touches only slot/table).
        _sessionState.value = EMPTY_SESSION_STATE
        _notice.value = null
        _history.value = emptyList()
        _historyOmittedCount.value = 0
        _requestTrimmedCount.value = 0
        _currentSessionId.value = null
        pendingOps.clear()
        recoverableOps.clear()
        syncRecoveryCount()
        retryableOp = null
        retryFallbackTarget = null
        retryTargetCandidate = null
        latestAcceptedStartedTarget = null
        inputDraft = null
        val requestedGeneration = lifecycleGeneration
        // R9-2 discard-scoped entry: post-ticket epoch. The re-park in the
        // lock + defensive finally only parks when no newer explicit discard
        // crossed it; _currentSessionId is already null here so owner uses
        // prevId / retained owner.
        val sameIdEntryDiscardEpoch = explicitDiscardEpoch
        var sameIdCombinedWasStreaming =
            effectiveFencedWasStreaming || prevWasStreaming || outstandingWasStreamingHint
        // S4-R2 sync parks (pre-dispatch coverage, no leak): preamble detached
        // above, so launch + park synchronously – ownership already sits in
        // slot/table even if openJob is cancelled before dispatch. The lock
        // below re-takes authoritatively (idempotent, never relaunches):
        // single launch, fence joins exactly once. Unrelated slot stashed
        // per-id; same-ID/owner/outstanding parked to slot (same-owner merge);
        // different-ID live launched + remembered under prevId (future reopen
        // fences it, never this open's settle).
        if (!isSameIdReopen && !isOwnerMatchedReopen) {
            if (fencedJob0 != null || fencedSession0 != null) {
                rememberOutstandingTeardown(
                    fencedOwner0,
                    fencedJob0,
                    fencedSession0,
                    fencedWasStreaming0,
                )
            }
        }
        pendingLedgerFence = null
        pendingLedgerSession = null
        pendingLedgerWasStreaming = false
        pendingLedgerOwnerSessionId = null
        if (isSameIdReopen && prevLive != null) {
            // Detach already sync above; launch survives the ticket cancel
            // (viewModelScope, never lifecycleScope; close idempotent).
            sameIdTeardown = launchCloseThenFlush(prevLive)
        }
        if (isSameIdReopen && prevLive != null && sameIdTeardown != null) {
            var mergedFence: Job? = sameIdTeardown
            if (effectiveFencedJob != null) {
                mergedFence = compositeLedgerJoin(effectiveFencedJob, mergedFence)
            }
            if (outstandingFenceHint != null) {
                mergedFence = compositeLedgerJoin(outstandingFenceHint, mergedFence)
            }
            pendingLedgerFence = mergedFence
            pendingLedgerSession = prevLive
            pendingLedgerWasStreaming = sameIdCombinedWasStreaming
            pendingLedgerOwnerSessionId = prevId
        } else if (isOwnerMatchedReopen || hasOutstandingHint) {
            var parkFence: Job? = effectiveFencedJob ?: outstandingFenceHint
            if (effectiveFencedJob != null && outstandingFenceHint != null &&
                effectiveFencedJob !== outstandingFenceHint
            ) {
                parkFence = compositeLedgerJoin(effectiveFencedJob, outstandingFenceHint)
            }
            val parkSession: ChatSession? = effectiveFencedSession ?: outstandingSessionHint
            if (parkFence != null || parkSession != null) {
                pendingLedgerFence = parkFence
                pendingLedgerSession = parkSession
                pendingLedgerWasStreaming = sameIdCombinedWasStreaming
                pendingLedgerOwnerSessionId = if (isOwnerMatchedReopen) {
                    effectiveFencedOwner
                } else {
                    sessionId
                }
            }
        }
        if (!isSameIdReopen && prevLive != null) {
            // Different-ID live (UI already detached sync above): launch +
            // remember under its own id now so a pre-dispatch cancel cannot
            // leak it; the lock below skips relaunch when it sees this entry.
            // Unrelated to this open's settle (different key, never merged).
            val diffFenceSync = launchCloseThenFlush(prevLive)
            if (prevId != null) {
                rememberOutstandingTeardown(prevId, diffFenceSync, prevLive, prevWasStreaming)
            }
        }
        openJob = lifecycleScope.launch {
            var candidate: CreatedSession? = null
            var attached = false
            var cancellation: CancellationException? = null
            try {
                // Fast path (different ID) preloads outside the mutex as
                // before; the slow path reads AFTER its settle inside the
                // mutex, so its snapshot can never see an orphan user row.
                // Owner-matched from-null reopens take the slow path too
                // (isSlowReopen), so their loadHistory is fenced.
                val fastLoaded: LoadedUiHistory? = if (!isSlowReopen) {
                    try {
                        withContext(Dispatchers.IO) { loadHistory(sessionId) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                if (!isSlowReopen) {
                    if (!isGenerationCurrent(requestedGeneration)) return@launch
                    if (fastLoaded == null) {
                        _notice.value = "UNKNOWN_SESSION"
                        return@launch
                    }
                }

                val access = when (val result = authorizeEndpoint(requestedGeneration)) {
                    is EndpointAccess.Ready -> result.endpoint
                    EndpointAccess.Denied -> {
                        setNoticeIfCurrent(requestedGeneration, "POLICY_DENIED")
                        return@launch
                    }
                    is EndpointAccess.ConfirmRequired -> {
                        // S2: retain the pending open (same generation, no second
                        // invalidate) so an explicit resumePendingOpen can
                        // consume the just-minted consent once. The stored
                        // binding is the authorize-time snapshot; the later
                        // resume re-validates sessionId + full binding +
                        // generation before any vault read.
                        if (isGenerationCurrent(requestedGeneration)) {
                            pendingOpen = PendingOpen(sessionId, result.binding, result.generation)
                            _pendingOpenSessionId.value = sessionId
                        }
                        emitKeyReadConfirmRequired(result.generation, result.binding, openSessionId = sessionId)
                        return@launch
                    }
                    EndpointAccess.Missing -> {
                        setNoticeIfCurrent(requestedGeneration, "NO_ENDPOINT")
                        return@launch
                    }
                    EndpointAccess.Stale -> return@launch
                }
                // Opening is tied to the endpoint selected when this operation
                // started; a config transition must not move old context to B.
                if (access.generation != requestedGeneration) return@launch

                sessionMutex.withLock {
                    if (!isGenerationCurrent(requestedGeneration)) return@withLock
                    // S4-R2 authoritative ownership only (ticket + UI already
                    // done sync in the preamble above; cancelling scope/job here
                    // would kill self – generation check suffices, and the
                    // preamble already cancelled the previous open for last-wins).
                    // S4-R2 authoritative ownership (first segment under mutex):
                    // fresh take with purge; stash unrelated slot (no-op when the
                    // preamble already stashed); recompute effective/slow; parks
                    // overwrite slot (single ownership, fence join exactly once).
                    // The preamble above already ticketed (generation bump +
                    // prev-open cancel for last-wins) + parked sync for
                    // pre-dispatch coverage; this re-take is idempotent (same
                    // fence/session instances merge without a second launch),
                    // so a double-tap while the first open suspends on IO cannot
                    // steal ownership here.
                    val takenAuth = takeOutstanding(sessionId)
                    takenOutstanding = takenAuth
                    outstandingFence = takenAuth?.fence
                    outstandingSession = takenAuth?.session
                    outstandingWasStreaming = takenAuth?.wasStreaming ?: false
                    hasOutstanding = outstandingFence != null || outstandingSession != null
                    val freshSlotJob: Job? = pendingLedgerFence
                    val freshSlotSession: ChatSession? = pendingLedgerSession
                    val freshSlotWasStreaming: Boolean = pendingLedgerWasStreaming
                    val freshSlotOwner: String? = pendingLedgerOwnerSessionId
                    val isOwnerMatchedAuth = !isSameIdReopen &&
                        freshSlotOwner != null &&
                        freshSlotOwner == sessionId &&
                        (freshSlotJob != null || freshSlotSession != null)
                    // Publish authoritative owner-match for the abort finally below.
                    isOwnerMatchedReopen = isOwnerMatchedAuth
                    if (!isSameIdReopen && !isOwnerMatchedAuth) {
                        if (freshSlotJob != null || freshSlotSession != null) {
                            rememberOutstandingTeardown(
                                freshSlotOwner,
                                freshSlotJob,
                                freshSlotSession,
                                freshSlotWasStreaming,
                            )
                        }
                    }
                    // Different-live ownership (authoritative, idempotent): preamble
                    // already detached sync (UI immediate) + launched +
                    // remembered under prevId (no pre-dispatch leak). Skip
                    // relaunch when the preamble entry already owns prevLive
                    // (single launch, fence joins exactly once); otherwise
                    // launch here (e.g. preamble raced – defensive, same key,
                    // never merged into this open's settle).
                    if (!isSameIdReopen && prevLive != null) {
                        val alreadyOwns = if (prevId != null) {
                            outstandingTeardowns[prevId]?.session === prevLive
                        } else {
                            false
                        }
                        if (!alreadyOwns) {
                            val diffFence = launchCloseThenFlush(prevLive)
                            if (prevId != null) {
                                rememberOutstandingTeardown(prevId, diffFence, prevLive, prevWasStreaming)
                            }
                        }
                    }
                    effectiveFencedJob =
                        if (isSameIdReopen || isOwnerMatchedAuth) freshSlotJob else null
                    effectiveFencedSession =
                        if (isSameIdReopen || isOwnerMatchedAuth) freshSlotSession else null
                    effectiveFencedWasStreaming =
                        if (isSameIdReopen || isOwnerMatchedAuth) freshSlotWasStreaming else false
                    effectiveFencedOwner =
                        if (isSameIdReopen || isOwnerMatchedAuth) freshSlotOwner else null
                    isOutstandingReopen = hasOutstanding
                    // Authoritative slow recompute overwrites the preload hint;
                    // hint-slow/auth-fast is safe (extra settle, still correct).
                    isSlowReopen = isSameIdReopen || isOwnerMatchedAuth || isOutstandingReopen
                    sameIdCombinedWasStreaming =
                        effectiveFencedWasStreaming || prevWasStreaming || outstandingWasStreaming
                    // Clear the slot before authoritative parks (single ownership;
                    // parks below overwrite). Stashed unrelated slot already
                    // preserved above, so clearing cannot lose it.
                    pendingLedgerFence = null
                    pendingLedgerSession = null
                    pendingLedgerWasStreaming = false
                    pendingLedgerOwnerSessionId = null
                    // Same-ID teardown re-use (single launch in the preamble sync
                    // park above – no pre-dispatch leak, no second launch here;
                    // TurnController.close idempotent, double-close safe). The
                    // authoritative park below re-merges the preamble launch
                    // with the fresh slot + authoritative outstanding take
                    // (idempotent joins, exactly-once fence ownership).
                    if (isSameIdReopen && prevLive != null && sameIdTeardown != null) {
                        var mergedFence: Job? = sameIdTeardown
                        val effJobPark: Job? = effectiveFencedJob
                        if (effJobPark != null && effJobPark !== mergedFence) {
                            mergedFence = compositeLedgerJoin(effJobPark, mergedFence)
                        }
                        val outFencePark: Job? = outstandingFence
                        if (outFencePark != null && outFencePark !== mergedFence &&
                            outFencePark !== effJobPark
                        ) {
                            mergedFence = compositeLedgerJoin(outFencePark, mergedFence)
                        }
                        pendingLedgerFence = mergedFence
                        pendingLedgerSession = prevLive
                        pendingLedgerWasStreaming = sameIdCombinedWasStreaming
                        pendingLedgerOwnerSessionId = prevId
                    } else if (isOwnerMatchedAuth || isOutstandingReopen) {
                        val effJobPark2: Job? = effectiveFencedJob
                        val outFencePark2: Job? = outstandingFence
                        var parkFence: Job? = effJobPark2 ?: outFencePark2
                        if (effJobPark2 != null && outFencePark2 != null &&
                            effJobPark2 !== outFencePark2
                        ) {
                            parkFence = compositeLedgerJoin(effJobPark2, outFencePark2)
                        }
                        val parkSession: ChatSession? = effectiveFencedSession ?: outstandingSession
                        if (parkFence != null || parkSession != null) {
                            pendingLedgerFence = parkFence
                            pendingLedgerSession = parkSession
                            pendingLedgerWasStreaming = sameIdCombinedWasStreaming
                            pendingLedgerOwnerSessionId = if (isOwnerMatchedAuth) {
                                effectiveFencedOwner
                            } else {
                                sessionId
                            }
                        }
                    }
                    val latest = readEndpoint()
                    if (!isGenerationCurrent(requestedGeneration)) return@withLock
                    if (bindingOf(latest) != access.binding || latest == null) {
                        reconcileReadBinding(bindingOf(latest))
                        return@withLock
                    }

                    if (!isSlowReopen) {
                        val creationGeneration = requestedGeneration
                        // R9-3: a from-null reopen of a still-blocked ledger
                        // defers like the slow path (no live to settle here,
                        // so settle evidence is vacuously clean; only the
                        // ledger probe can promote). Different-ID opens check
                        // their own id, so an unrelated ledger never inherits
                        // this block.
                        val fastBackfill = gateBackfillForSession(
                            sessionId,
                            settleOk = true,
                            durableOk = true,
                            detached = null,
                        )
                        try {
                            withContext(Dispatchers.IO) {
                                candidate = sessions.open(
                                    latest,
                                    sessionId,
                                    allowAsk = access.allowAsk,
                                    keyIsCurrent = { isBindingCurrent(access.binding, creationGeneration) },
                                    backfillInterrupted = fastBackfill,
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: CaptureDeniedException) {
                            // S1/S6 (see the slow path below): burn a consent
                            // minted between T0 and T1 so a T1 deny on open
                            // leaves no residual slot for replay; only
                            // CaptureDeniedException maps to POLICY_DENIED.
                            takePendingKeyReadConsent()
                            setNoticeIfCurrent(requestedGeneration, "POLICY_DENIED")
                            return@withLock
                        }
                        val created = candidate ?: return@withLock
                        if (!isGenerationCurrent(creationGeneration)) return@withLock
                        val afterCreate = readEndpoint()
                        if (!isGenerationCurrent(creationGeneration)) return@withLock
                        if (bindingOf(afterCreate) != access.binding || afterCreate == null) {
                            reconcileReadBinding(bindingOf(afterCreate))
                            return@withLock
                        }

                        // Stage 5 (R4-1): cross-origin open() forks to a new
                        // provider-owned transcript id; the display replay
                        // above intentionally still shows the archived rows
                        // (mirrors the ensureSession endpoint-switch path),
                        // while the durable ledger holds only the withheld
                        // marker + new turns. A restart of the forked id then
                        // shows only its own rows.
                        // S4-F2: hint-slow/auth-fast over-conservative case
                        // (preload skipped on hint, authoritative fast) falls
                        // back to an in-lock load (rare, safe – same fenced
                        // read the slow path does, just without settle).
                        val fastHistory = fastLoaded ?: try {
                            withContext(Dispatchers.IO) { loadHistory(sessionId) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (fastHistory == null) {
                            _notice.value = "UNKNOWN_SESSION"
                            return@withLock
                        }
                        _history.value = fastHistory.messages
                        _historyOmittedCount.value = fastHistory.droppedCount
                        attach(created, access.binding, creationGeneration)
                        attached = true
                        candidate = null
                        // R6-3 (b): open loads the requested id's history —
                        // not a fork of the previous ledger — so a fence
                        // parked by an earlier endpoint-switch is stale for
                        // every future fork. Drop it now (the pre-clear above
                        // already dropped it via
                        // invalidateLifecycle(clearChat=true); this covers the
                        // attach window): otherwise the next fork would join
                        // an ancient job, re-flush a long-sealed session, and
                        // OR ancient wasStreaming into a new INCOMPLETE
                        // decision. R8-2: the owner lineage goes with it.
                        pendingLedgerFence = null
                        pendingLedgerSession = null
                        pendingLedgerWasStreaming = false
                        pendingLedgerOwnerSessionId = null
                        // Stage D: a cross-provider resume keeps the local
                        // replay but withholds the model prefix; say so
                        // visibly instead of resuming silently on a blank
                        // context. R9-3: a fast-path defer (still-blocked
                        // ledger, see fastBackfill above) warns like the slow
                        // path instead of the marker scan.
                        _notice.value = if (!fastBackfill) {
                            "HISTORY_SNAPSHOT_INCOMPLETE"
                        } else if (created.historyWithheld) {
                            "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD"
                        } else {
                            // R4-2: a cross-provider fork marker persisted in
                            // this session re-surfaces the withhold notice
                            // after a restart (the switch-time notice lived in
                            // the old process). The withhold itself is
                            // structural — the old rows were never copied — so
                            // a failed scan only drops the notice, never leaks
                            // history. Stage 5 (R4-1): cross-origin open()
                            // forks to a new provider-owned id (attach()
                            // already points _currentSessionId at it), so scan
                            // the ATTACHED id, not the requested one. R5-4: a
                            // failed fork snapshot outranks a clean withhold
                            // — check the incomplete marker first so a
                            // restart re-warns HISTORY_SNAPSHOT_INCOMPLETE
                            // even when no withheld marker exists.
                            try {
                                val attachedId = created.sessionId ?: sessionId
                                withContext(Dispatchers.IO) {
                                    val backing = sessions.storeOrNull()
                                    when {
                                        backing == null -> null
                                        hasIncompleteSnapshotMarker(backing, attachedId) ->
                                            "HISTORY_SNAPSHOT_INCOMPLETE"
                                        hasWithheldSwitchMarker(backing, attachedId) ->
                                            "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD"
                                        else -> null
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                        return@withLock
                    }

                    // R8-3 slow path (same ID): settle the same ledger
                    // BEFORE reading it, bounded on IO under the
                    // single-flight mutex (same three waits as the
                    // endpoint-switch fork: prior fence join ~2x, parked
                    // re-flush ~2x, just-detached join + re-check ~2x).
                    // R9-2: the just-detached ledger was already torn down
                    // eagerly in the sync area (sameIdTeardown on
                    // viewModelScope, double-close safe) — join it bounded
                    // plus a flushDurable re-check (parked pattern), never a
                    // second closeThenFlushDurable. Caller cancellation
                    // propagates; a timeout or flushDurable==false degrades
                    // to defer (skip this backfill, warn; the next open
                    // retries), never to a false INTERRUPTED. Store throws
                    // swallow to false.
                    var settleOk = true
                    var durableOk = true
                    // S4-F2: snapshot authoritative vars to locals for smart-cast
                    // across suspension (mutex held, no concurrent mutation).
                    val settleEffJob: Job? = effectiveFencedJob
                    val settleEffSession: ChatSession? = effectiveFencedSession
                    val settleOutFence: Job? = outstandingFence
                    val settleOutSession: ChatSession? = outstandingSession
                    withContext(Dispatchers.IO) {
                        // R10-1: settle every teardown owned for the requested
                        // ledger — the slot fence (same ledger only, unrelated
                        // slots were stashed, never settled here) plus the
                        // outstanding-table fence (newChat/open-other cross-
                        // discard ownership). Each phase is bounded (~2x);
                        // any miss degrades to defer (R9-3 block), never to a
                        // false INTERRUPTED.
                        if (settleEffJob != null) {
                            val joined = try {
                                withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) { settleEffJob.join() } != null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!joined) settleOk = false
                        }
                        val parked = settleEffSession
                        if (parked != null) {
                            // The background job already closed+flushed;
                            // re-check durability bounded (idempotent seal).
                            val durable = try {
                                withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                                    parked.flushDurable(SESSION_FLUSH_TIMEOUT_MS)
                                } ?: false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!durable) durableOk = false
                        }
                        if (settleOutFence != null && settleOutFence !== settleEffJob) {
                            val joined = try {
                                withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) { settleOutFence.join() } != null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!joined) settleOk = false
                        }
                        val outstandingParked = settleOutSession
                        if (outstandingParked != null && outstandingParked !== parked) {
                            val durable = try {
                                withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                                    outstandingParked.flushDurable(SESSION_FLUSH_TIMEOUT_MS)
                                } ?: false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!durable) durableOk = false
                        }
                        // From-null owner-match has no live to settle: only the
                        // fence join + parked flushDurable re-check above run.
                        // A different-ID live (owner-matched but prevId !=
                        // requested) is already detached unfenced by the
                        // invalidate above, so it must not gate this ledger's
                        // backfill — only a true same-ID live settles here.
                        val detached = if (isSameIdReopen) prevLive else null
                        val detachedTeardown = sameIdTeardown
                        if (detached != null && detachedTeardown != null) {
                            // Eager teardown handover: await the sync-area
                            // close-then-flush (bounded 2x) then re-check
                            // durability bounded (idempotent after seal).
                            val joined = try {
                                withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                                    detachedTeardown.join()
                                } != null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (!joined) {
                                settleOk = false
                                durableOk = false
                            } else {
                                val durable = try {
                                    withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                                        detached.flushDurable(SESSION_FLUSH_TIMEOUT_MS)
                                    } ?: false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                // closeThenFlushDurable parity: any unsettled
                                // ledger (join miss or re-check false) fails
                                // both gates so this open defers its backfill.
                                if (!durable) {
                                    settleOk = false
                                    durableOk = false
                                }
                            }
                        } else if (detached != null) {
                            val ok = try {
                                withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                                    closeThenFlushDurable(detached, SESSION_FLUSH_TIMEOUT_MS)
                                } ?: false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            // closeThenFlushDurable returns flushDurable
                            // (drain AND no durable failure); an outer
                            // timeout (null) and a false both mean the ledger
                            // may still miss its terminal.
                            if (!ok) {
                                settleOk = false
                                durableOk = false
                            }
                        }
                    }
                    if (!isGenerationCurrent(requestedGeneration)) return@withLock
                    val settledLoaded = try {
                        withContext(Dispatchers.IO) { loadHistory(sessionId) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (!isGenerationCurrent(requestedGeneration)) return@withLock
                    if (settledLoaded == null) {
                        _notice.value = "UNKNOWN_SESSION"
                        return@withLock
                    }
                    // Gate ONLY the same-origin backfill: settleOk &&
                    // durableOk backfills, otherwise this open skips it and
                    // relies on the next open's retry (best-effort). The
                    // cross-origin fork path always backfills its fresh
                    // ledger and is never gated. R9-3: the gate is
                    // sessionId-grained ([gateBackfillForSession]) — a live
                    // defer block forces a second premature reopen to defer
                    // again even though its fresh idle live settles cleanly
                    // (flush-timeout vs flush-failure are indistinguishable
                    // here; only the ledger state tells them apart).
                    val detachedForGate = if (isSameIdReopen) prevLive else null
                    // B4: slot evidence prefers the retained slot session over
                    // the fresh detached live — a clean fresh-idle detached
                    // must not shadow the true old-writer culprit. The gate
                    // verifies both (plus stored + outstanding) and remembers
                    // both, so a slot session distinct from stored and outside
                    // the outstanding table is never missed.
                    val slotEvidenceForGate = effectiveFencedSession ?: outstandingSession
                    val backfillNow = gateBackfillForSession(
                        sessionId,
                        settleOk,
                        durableOk,
                        detachedForGate,
                        slotEvidenceForGate ?: detachedForGate,
                    )
                    val creationGeneration = requestedGeneration
                    try {
                        withContext(Dispatchers.IO) {
                            candidate = sessions.open(
                                latest,
                                sessionId,
                                allowAsk = access.allowAsk,
                                keyIsCurrent = { isBindingCurrent(access.binding, creationGeneration) },
                                backfillInterrupted = backfillNow,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: CaptureDeniedException) {
                        // S1: burn a consent minted between T0 and T1 so a
                        // T1 deny on open leaves no residual slot for replay.
                        takePendingKeyReadConsent()
                        // S6 capture-boundary T1 DENY/failure on open. Only
                        // CaptureDeniedException maps to POLICY_DENIED here;
                        // a non-Capture SecurityException falls through to
                        // the outer NO_ENDPOINT branch.
                        setNoticeIfCurrent(requestedGeneration, "POLICY_DENIED")
                        return@withLock
                    }
                    val created = candidate ?: return@withLock
                    if (!isGenerationCurrent(creationGeneration)) return@withLock
                    val afterCreate = readEndpoint()
                    if (!isGenerationCurrent(creationGeneration)) return@withLock
                    if (bindingOf(afterCreate) != access.binding || afterCreate == null) {
                        reconcileReadBinding(bindingOf(afterCreate))
                        return@withLock
                    }

                    // Stage 5 (R4-1): cross-origin open() forks to a new
                    // provider-owned transcript id; the display replay above
                    // intentionally still shows the archived rows (mirrors the
                    // ensureSession endpoint-switch path), while the durable
                    // ledger holds only the withheld marker + new turns. A
                    // restart of the forked id then shows only its own rows.
                    _history.value = settledLoaded.messages
                    _historyOmittedCount.value = settledLoaded.droppedCount
                    attach(created, access.binding, creationGeneration)
                    attached = true
                    candidate = null
                    // R6-3 (b): open loads the requested id's history — not a
                    // fork of the previous ledger — so a fence parked by an
                    // earlier endpoint-switch is stale for every future fork.
                    // Drop it now (the pre-clear above already dropped it via
                    // invalidateLifecycle(clearChat=true); this covers the
                    // attach window): otherwise the next fork would join an
                    // ancient job, re-flush a long-sealed session, and OR
                    // ancient wasStreaming into a new INCOMPLETE decision.
                    // R8-2: the owner lineage goes with it.
                    pendingLedgerFence = null
                    pendingLedgerSession = null
                    pendingLedgerWasStreaming = false
                    pendingLedgerOwnerSessionId = null
                    if (!backfillNow) {
                        // Deferred backfill: the ledger may still miss its
                        // terminal, so warn visibly (reuse: the restart scan
                        // re-warns the same notice once the marker regime
                        // settles) and hydrate what exists. The skipped
                        // backfill is retried by the next open.
                        _notice.value = "HISTORY_SNAPSHOT_INCOMPLETE"
                    } else {
                        // Stage D: a cross-provider resume keeps the local replay
                        // but withholds the model prefix; say so visibly instead
                        // of resuming silently on a blank context.
                        _notice.value = if (created.historyWithheld) {
                            "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD"
                        } else {
                            // Same scan as the fast path above (R4-2/R5-4):
                            // scan the ATTACHED id for a persisted incomplete
                            // or withhold marker. A failed scan only drops the
                            // notice, never leaks history.
                            try {
                                val attachedId = created.sessionId ?: sessionId
                                withContext(Dispatchers.IO) {
                                    val backing = sessions.storeOrNull()
                                    when {
                                        backing == null -> null
                                        hasIncompleteSnapshotMarker(backing, attachedId) ->
                                            "HISTORY_SNAPSHOT_INCOMPLETE"
                                        hasWithheldSwitchMarker(backing, attachedId) ->
                                            "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD"
                                        else -> null
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                throw cancelled
            } catch (_: Exception) {
                setNoticeIfCurrent(requestedGeneration, "NO_ENDPOINT")
            } finally {
                // R9-2 defensive re-park: every slow-path abort (auth
                // Denied/Missing/Stale, fast-preload gate at 1008, auth
                // gates at 1015-1025, access-generation gate at 1029,
                // mutex gates at 1032-1037, or openJob cancel in slow IO)
                // keeps the teardown owned. The sync area already parked it
                // (so pre-dispatch cancel is covered even though this
                // finally never runs for it); this is idempotent —
                // already-parked stays, a newer discard suppresses via the
                // epoch check, and success (attached) never re-parks (slot
                // stays consumed). Runs on Main (lifecycleScope), same
                // confinement as the slot; never takes sessionMutex (callers
                // inside withLock already hold it — direct assign avoids
                // deadlock).
                if (!attached && isSlowReopen &&
                    (prevLive != null || effectiveFencedJob != null || effectiveFencedSession != null ||
                        outstandingFence != null || outstandingSession != null)
                ) {
                    // Same-ID owns its live teardown (owner==prevId);
                    // from-null owner-match owns the retained slot fence
                    // (owner==effectiveFencedOwner, no live to hand over);
                    // R10-1 outstanding owns the table fence (owner==sessionId).
                    // Merge the slot + table fences so an abort never drops an
                    // unresolved same-ledger teardown; the session prefers the
                    // slot (snapshot target) while the other drains via its
                    // own job unfenced.
                    var abortFence: Job? = effectiveFencedJob
                    val abortOutFence: Job? = outstandingFence
                    if (abortOutFence != null && abortOutFence !== abortFence) {
                        abortFence = if (abortFence != null) {
                            compositeLedgerJoin(abortFence, abortOutFence)
                        } else {
                            abortOutFence
                        }
                    }
                    val abortSession: ChatSession? =
                        effectiveFencedSession ?: outstandingSession
                    reparkSameIdTeardownOnAbort(
                        sameIdTeardown = sameIdTeardown,
                        prevLive = if (isSameIdReopen) prevLive else null,
                        fencedJob = abortFence,
                        fencedSession = abortSession,
                        combinedWasStreaming = sameIdCombinedWasStreaming,
                        ownerSessionId = when {
                            isSameIdReopen -> prevId
                            isOwnerMatchedReopen -> effectiveFencedOwner
                            else -> sessionId
                        },
                        entryDiscardEpoch = sameIdEntryDiscardEpoch,
                    )
                }
                val unattached = candidate
                if (!attached && unattached != null) {
                    // Stage 5 (R4-1) stale-fork cleanup: same-id resumes reuse
                    // the archived row (never delete), but a cross-origin fork
                    // minted a fresh id inside open() — a stale binding after
                    // that must roll it back (see shouldDeleteStaleFork), or
                    // the empty fork leaks into the session list.
                    val deleteForkRow = shouldDeleteStaleFork(sessionId, unattached)
                    try {
                        sessions.discardUnattached(unattached, deleteTranscriptRow = deleteForkRow)
                    } catch (cleanupFailure: Throwable) {
                        val originalCancellation = cancellation
                        if (originalCancellation != null) {
                            originalCancellation.addSuppressed(cleanupFailure)
                        } else {
                            throw cleanupFailure
                        }
                    }
                }
            }
        }
    }

    /**
     * S2: explicit resume for a history open retained across ConfirmRequired.
     *
     * Consumes the one-shot consent minted after [openSession]'s
     * ConfirmRequired exactly once, without a second [invalidateLifecycle]
     * (which would burn the consent and bump the generation into a dead
     * loop). Fences on sessionId + full binding + generation: any mismatch
     * fails closed with a fresh ConfirmRequired (sequence +1, zero
     * transport); cancel still burns via [dismissKeyReadConfirm]; the
     * generation fence is never weakened (a stale generation never adopts
     * the new one to pass). Success clears the pending slot, loads history,
     * attaches once (exactly one getKey/build) and leaves the sequence
     * untouched. Never auto-sends.
     */
    fun resumePendingOpen(sessionId: String) {
        val pending = pendingOpen ?: return
        openJob?.cancel()
        openJob = lifecycleScope.launch {
            var candidate: CreatedSession? = null
            var attached = false
            var cancellation: CancellationException? = null
            try {
                // Sid fence first: a wrong sid never touches the consent slot
                // nor any transport; it fails closed with a fresh confirm on
                // the current generation and keeps the original pending.
                if (pending.sessionId != sessionId || pendingOpen?.sessionId != sessionId) {
                    val currentGen = lifecycleGeneration
                    val currentBinding = observedBinding ?: pending.binding
                    if (isGenerationCurrent(currentGen)) {
                        emitKeyReadConfirmRequired(currentGen, currentBinding, openSessionId = pending.sessionId)
                    }
                    return@launch
                }
                if (!isGenerationCurrent(pending.generation)) {
                    rearmPendingOpenAndRequireConfirm(sessionId)
                    return@launch
                }
                val loaded = try {
                    withContext(Dispatchers.IO) { loadHistory(sessionId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (!isGenerationCurrent(pending.generation)) {
                    rearmPendingOpenAndRequireConfirm(sessionId)
                    return@launch
                }
                if (loaded == null) {
                    pendingOpen = null
                    _pendingOpenSessionId.value = null
                    _notice.value = "UNKNOWN_SESSION"
                    return@launch
                }
                val access = when (val result = authorizeEndpoint(pending.generation)) {
                    is EndpointAccess.Ready -> result.endpoint
                    is EndpointAccess.ConfirmRequired -> {
                        // Consent absent/mismatched but binding+generation
                        // still current: keep the pending slot so a fresh
                        // confirm + resume can pass without a fresh open.
                        emitKeyReadConfirmRequired(result.generation, result.binding, openSessionId = pending.sessionId)
                        return@launch
                    }
                    EndpointAccess.Denied -> {
                        pendingOpen = null
                        _pendingOpenSessionId.value = null
                        setNoticeIfCurrent(pending.generation, "POLICY_DENIED")
                        return@launch
                    }
                    EndpointAccess.Missing -> {
                        pendingOpen = null
                        _pendingOpenSessionId.value = null
                        setNoticeIfCurrent(pending.generation, "NO_ENDPOINT")
                        return@launch
                    }
                    EndpointAccess.Stale -> {
                        rearmPendingOpenAndRequireConfirm(sessionId)
                        return@launch
                    }
                }
                if (access.generation != pending.generation || !isGenerationCurrent(pending.generation)) {
                    rearmPendingOpenAndRequireConfirm(sessionId)
                    return@launch
                }
                // Binding fence: full-field equality against the retained
                // snapshot (endpoint switch must not move old context to B;
                // ABA returns to the same binding on a newer generation and
                // is already rejected by the generation check above).
                if (access.binding != pending.binding) {
                    rearmPendingOpenAndRequireConfirm(sessionId)
                    return@launch
                }
                sessionMutex.withLock {
                    if (!isGenerationCurrent(pending.generation)) return@withLock
                    val latest = readEndpoint()
                    if (!isGenerationCurrent(pending.generation)) return@withLock
                    if (bindingOf(latest) != access.binding || latest == null) {
                        reconcileReadBinding(bindingOf(latest))
                        return@withLock
                    }
                    val creationGeneration = pending.generation
                    try {
                        withContext(Dispatchers.IO) {
                            candidate = sessions.open(
                                latest,
                                sessionId,
                                allowAsk = access.allowAsk,
                                keyIsCurrent = { isBindingCurrent(access.binding, creationGeneration) },
                                // Explicit resume: repair dangling rows like
                                // the allowAsk-form default would.
                                backfillInterrupted = true,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: CaptureDeniedException) {
                        takePendingKeyReadConsent()
                        pendingOpen = null
                        _pendingOpenSessionId.value = null
                        setNoticeIfCurrent(pending.generation, "POLICY_DENIED")
                        return@withLock
                    }
                    val created = candidate ?: return@withLock
                    if (!isGenerationCurrent(creationGeneration)) return@withLock
                    val afterCreate = readEndpoint()
                    if (!isGenerationCurrent(creationGeneration)) return@withLock
                    if (bindingOf(afterCreate) != access.binding || afterCreate == null) {
                        reconcileReadBinding(bindingOf(afterCreate))
                        return@withLock
                    }
                    _history.value = loaded
                    attach(created, access.binding, creationGeneration)
                    attached = true
                    candidate = null
                    pendingOpen = null
                    _pendingOpenSessionId.value = null
                    _notice.value = null
                }
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                throw cancelled
            } catch (_: Exception) {
                setNoticeIfCurrent(pending.generation, "NO_ENDPOINT")
            } finally {
                val unattached = candidate
                if (!attached && unattached != null) {
                    try {
                        sessions.discardUnattached(unattached, deleteTranscriptRow = false)
                    } catch (cleanupFailure: Throwable) {
                        val originalCancellation = cancellation
                        if (originalCancellation != null) {
                            originalCancellation.addSuppressed(cleanupFailure)
                        } else {
                            throw cleanupFailure
                        }
                    }
                }
            }
        }
    }

    /**
     * S2: re-arm a stale pending open to the current binding/generation and
     * require a fresh confirmation. Called when the retained generation no
     * longer matches (endpoint switch/ABA/explicit discard racing resume) or
     * the retained binding differs. Emits ConfirmRequired on the current
     * generation (sequence +1, zero transport); a missing endpoint clears
     * the slot with NO_ENDPOINT instead.
     */
    private suspend fun rearmPendingOpenAndRequireConfirm(sessionId: String) {
        val currentGen = lifecycleGeneration
        val latest = try {
            readEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (!isGenerationCurrent(currentGen)) return
        val currentBinding = bindingOf(latest)
        if (latest == null || currentBinding == null) {
            pendingOpen = null
            _pendingOpenSessionId.value = null
            setNoticeIfCurrent(currentGen, "NO_ENDPOINT")
            return
        }
        pendingOpen = PendingOpen(sessionId, currentBinding, currentGen)
        _pendingOpenSessionId.value = sessionId
        emitKeyReadConfirmRequired(currentGen, currentBinding, openSessionId = sessionId)
    }

    private suspend fun authorizeEndpoint(startedAt: Long): EndpointAccess {
        if (!isGenerationCurrent(startedAt)) return EndpointAccess.Stale
        val endpoint = try {
            readEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EndpointAccess.Denied
        }
        if (!isGenerationCurrent(startedAt)) return EndpointAccess.Stale
        if (endpoint == null) {
            return if (reconcileReadBinding(null)) EndpointAccess.Stale else EndpointAccess.Missing
        }
        val binding = bindingOf(endpoint)
        if (binding == null) {
            return if (reconcileReadBinding(null)) EndpointAccess.Stale else EndpointAccess.Missing
        }

        // If the fresh snapshot differs from the observed binding, invalidation
        // cancels this old-generation operation. Fail closed as Stale; do not
        // adopt the new generation and continue an action that began on old data.
        if (reconcileReadBinding(binding)) return EndpointAccess.Stale
        if (!isGenerationCurrent(startedAt)) return EndpointAccess.Stale
        val authorizedGeneration = startedAt

        val decision = try {
            withContext(Dispatchers.IO) {
                policy.evaluateFresh("key.read", endpoint.providerId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // key.read evaluation failure stays POLICY_DENIED (fail-closed,
            // no separate UNAVAILABLE code on this gate). This differs
            // deliberately from the chat.send gate below, where a
            // PolicyEvaluationException surfaces CHAT_POLICY_UNAVAILABLE to
            // distinguish "controller unavailable" from an explicit
            // CHAT_SEND_DENIED. Copy unchanged.
            takePendingKeyReadConsent()
            return EndpointAccess.Denied
        }
        if (!isGenerationCurrent(authorizedGeneration)) return EndpointAccess.Stale
        // key.read ASK never passes through: it requires a bound one-shot
        // confirmation minted by confirmKeyRead(expected, token) (providerId /
        // effectiveBaseUrl / origin / protocol / apiKeyRef / configRevision /
        // model + mint generation). DENY always wins (and burns any minted
        // consent), as does a policy evaluation failure above; only an
        // explicit ALLOW proceeds without one. No synthetic ALLOW is
        // introduced here.
        // S1: every authorization consumes the one-shot slot at most once.
        // DENY, ALLOW, and evaluation failure all burn an unconsumed
        // consent; ASK takes it and passes only on a binding- and
        // generation-matched consent (allowAsk proof for the T1 recheck).
        // An ALLOW→ASK flip between T0 and T1 must not pass: T0 ALLOW
        // carries no proof, so T1 ASK denies.
        val allowAsk = when (decision.verdict) {
            Verdict.DENY -> {
                takePendingKeyReadConsent()
                return EndpointAccess.Denied
            }
            Verdict.ALLOW -> {
                takePendingKeyReadConsent()
                false
            }
            Verdict.ASK -> {
                val consent = takePendingKeyReadConsent()
                if (consent == null || !keyReadConsentMatches(consent, binding, authorizedGeneration)) {
                    return EndpointAccess.ConfirmRequired(binding, authorizedGeneration)
                }
                true
            }
        }

        // Fresh policy evaluation suspended. Re-read the complete connection
        // identity so a save/logout racing that suspension fails closed.
        val latest = try {
            readEndpoint()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return EndpointAccess.Missing
        }
        if (!isGenerationCurrent(authorizedGeneration)) return EndpointAccess.Stale
        if (bindingOf(latest) != binding || latest == null) {
            reconcileReadBinding(bindingOf(latest))
            return EndpointAccess.Stale
        }
        return EndpointAccess.Ready(AuthorizedEndpoint(latest, binding, authorizedGeneration, allowAsk))
    }

    /**
     * PG-S1 segment C: settle the detached old ledger(s) BEFORE reading it,
     * then load the fork inputs. Pure move of the ensureSession fence + read
     * block (zero behavior change).
     *
     * Must be called with sessionMutex held. The caller holds the mutex across
     * this call; this callee never takes it. [entryDiscardEpoch] snapshots
     * [explicitDiscardEpoch] before any suspension. The cancel re-park below
     * is discard-scoped, not
     * generation-scoped (R8-2): a non-null endpoint switch bumps the
     * generation but keeps the history, so the fence must be re-parked; only
     * an explicit discard (explicitDiscardEpoch moved, or the snapshot target
     * itself is gone) drops it. [fencedOwnerSessionId] is the slot owner at
     * consume time, re-filled on re-park so the next fork keeps the ledger
     * lineage. [endpoint] is the endpoint identity snapshot (the effective
     * baseUrl is derived inside [isSameOriginForResume], the same choke point
     * the factory `open` path uses — one ledger never gets two verdicts).
     * The pending-ledger inputs are passed by value with one-time
     * ownership (the caller already cleared the slots). Only store I/O plus
     * the ledger settle run here; `_history`/`_notice` writes stay with the
     * caller back on Main.
     */
    private suspend fun settleDetachedLedger(
        previousSessionId: String?,
        endpoint: EndpointConfig,
        fencedJob: Job?,
        fencedSession: ChatSession?,
        fencedWasStreaming: Boolean,
        fencedOwnerSessionId: String?,
        entryDiscardEpoch: Long,
        mismatchedForSettle: ChatSession?,
        mismatchedWasStreaming: Boolean,
    ): SettledFork {
        val wasStreaming = mismatchedWasStreaming || fencedWasStreaming
        var settleOk = true
        var durableOk = true
        val prevId = previousSessionId
            ?: return SettledFork(null, wasStreaming, settleOk, durableOk)
        // R6-3 (a): the fence locals above were destructively consumed
        // before any suspension. If this IO section is lifecycle-cancelled,
        // close still runs under NonCancellable but flushDurable throws
        // CancellationException, the mutex releases, and the locals would
        // be lost (next fork: no fence to join, wasStreaming evidence
        // gone). So a cancellation before [fenceSettled] re-parks the
        // unfinished ledger below — R8-2 discard-scoped (explicit history
        // abandon drops; a bare transport replacement re-parks even though
        // the generation moved).
        var fenceSettled = false
        val read = try {
            withContext(Dispatchers.IO) {
                // R6-3 fence: settle the old ledger(s) BEFORE reading it.
                // Prior invalidate fence first (background close-then-flush),
                // then the just-detached mismatched session (close-then-flush
                // here). Each phase gets SESSION_FLUSH_TIMEOUT_MS (~2x total
                // after close); a timeout or flushDurable==false degrades to
                // the incomplete path instead of copying a dangling family.
                if (fencedJob != null || fencedSession != null) {
                    val fence = fencedJob
                    if (fence != null) {
                        val joined = try {
                            withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) { fence.join() } != null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (!joined) settleOk = false
                    }
                    val parked = fencedSession
                    if (parked != null) {
                        // The background job already closed+flushed; re-check
                        // durability bounded (idempotent after seal).
                        val durable = try {
                            withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                                parked.flushDurable(SESSION_FLUSH_TIMEOUT_MS)
                            } ?: false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (!durable) durableOk = false
                    }
                }
                val detached = mismatchedForSettle
                if (detached != null) {
                    val ok = try {
                        withTimeoutOrNull(SESSION_FLUSH_TIMEOUT_MS * 2) {
                            closeThenFlushDurable(detached, SESSION_FLUSH_TIMEOUT_MS)
                        } ?: false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    // closeThenFlushDurable returns flushDurable (drain AND no
                    // durable failure); an outer timeout (null) and a false
                    // both mean the ledger may still miss its terminal.
                    if (!ok) {
                        settleOk = false
                        durableOk = false
                    }
                }
                val store = sessions.storeOrNull()
                // R6-3 (a) + R10: the re-park gate below must see the exact
                // settle state. Only a fully durable settle clears it: a
                // failed settle (timeout / flushDurable false) leaves the ledger
                // possibly-dangling, so a later cancel in the ledger read must still re-park for the next
                // fork's re-join + re-check (which re-derives the failure
                // flags instead of copying an orphan row). Assigned before
                // any further suspension.
                fenceSettled = settleOk && durableOk
                val previousMeta = store?.getSession(prevId)
                // D3: same choke point as the factory `open` path — one
                // ledger, one verdict, no drift between the fork read here
                // and a later `open` of the same id.
                val sameOrigin = previousMeta != null && isSameOriginForResume(previousMeta.model, endpoint)
                val rep = loadHistory(prevId)
                val hasHistory = (rep != null && rep.messages.isNotEmpty()) || (store != null && store.countHistoryEvents(prevId, includePartial = true) > 0)
                val hist = if (sameOrigin) {
                    loadModelHistory(checkNotNull(store), prevId).messages
                } else {
                    emptyList()
                }
                // Copy gate is origin-only: a cross-origin fork never copies
                // old rows (even a terminal/reasoning-only ledger with no
                // user/assistant left) — only the withheld marker. History
                // presence gates only the switch-time notice below.
                val historyWithheld = !sameOrigin
                val noticeWithheld = !sameOrigin && hasHistory
                ForkRead(
                    rep,
                    hist,
                    noticeWithheld,
                    ForkPrefix(
                        oldModelShort = previousMeta?.model?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "unknown",
                        sameOrigin = sameOrigin,
                        crossProviderWithheld = historyWithheld,
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            // R6-3 (a) + R8-2: re-park the unfinished ledger so the next fork
            // still fences it (see reparkUnsettledLedger). Back on Main
            // here with the mutex still held — same confinement as the
            // pendingLedger* fields. Discard-scoped, NOT generation-scoped:
            // a concurrent non-null endpoint switch bumps lifecycleGeneration
            // but keeps the history (owner still the snapshot target), so the
            // fence must survive for the next fork. Only an explicit history
            // discard drops: the discard epoch moved AND the snapshot target
            // is no longer current (clearChat / openSession reset /
            // binding == null). Either "same epoch" (no discard crossed) or
            // "same owner" (snapshot target intact) re-parks.
            if (!fenceSettled &&
                (explicitDiscardEpoch == entryDiscardEpoch || _currentSessionId.value == previousSessionId)
            ) {
                reparkUnsettledLedger(
                    fencedJob,
                    fencedSession,
                    fencedWasStreaming,
                    fencedOwnerSessionId ?: previousSessionId,
                    mismatchedForSettle,
                    mismatchedWasStreaming,
                )
            }
            throw cancelled
        }
        return SettledFork(read, wasStreaming, settleOk, durableOk)
    }

    /**
     * PG-S1 segment E: durable fork snapshot into the new transcript session.
     * Pure move of the ensureSession snapshot block (zero behavior change;
     * the three notice branches are kept distinct, never merged).
     *
     * R4-2: persist the carried prefix into the new transcript session so
     * restart/openSession(newId) still sees the old context plus the new
     * turns. Best-effort by contract (see snapshotSwitchHistory): the live
     * turn already carries initialHistory in memory, so a bookkeeping failure
     * must not fail the send. Runs before attach, so snapshot rows always
     * sort below the new turn's rows; the single-flight sessionMutex fences
     * concurrent switches. A stale generation here rolls back through the
     * discardUnattached path (Room cascades the snapshot).
     * R5-4: the snapshot batch is atomic (marker + copies commit together or
     * not at all), so a failure leaves zero snapshot rows — never a silent
     * half prefix. The catch below best-effort appends one system incomplete
     * marker and surfaces HISTORY_SNAPSHOT_INCOMPLETE (fail-open: the send
     * still proceeds on the in-memory prefix).
     * R6-3 streaming-switch fallback (C): the fence awaited the old ledger,
     * but a streaming old turn (STREAMING/CANCELLED/WAITING projection,
     * UNKNOWN admission, or pending cores) or a fence timeout /
     * flushDurable==false means the ledger may still miss its terminal. Then
     * the snapshot degrades: it skips dangling families (never copies an
     * orphan user row that would gain a false INTERRUPTED on reopen) and
     * rides one incomplete marker in the same atomic batch so the restart
     * scan re-warns. A clean streaming switch whose terminal already settled
     * copies the full family with no warning (exclude is a no-op without
     * dangling).
     *
     * Must be called with sessionMutex held. The caller holds the mutex across
     * this call; this callee never takes it. [forkProvenance] is passed whole
     * (never split into Booleans). Only store I/O runs here; the caller maps
     * the returned [SnapshotOutcome] to `_notice` on Main (testability) and
     * keeps the post-snapshot generation gate.
     */
    private suspend fun durableSnapshotOrWarn(
        previousSessionId: String?,
        forkTargetId: String?,
        forkProvenance: ForkPrefix?,
        newModel: String,
        wasStreaming: Boolean,
        settleOk: Boolean,
        durableOk: Boolean,
    ): SnapshotOutcome {
        val prevId = previousSessionId ?: return SnapshotOutcome.SKIPPED
        val targetId = forkTargetId ?: return SnapshotOutcome.SKIPPED
        val provenance = forkProvenance ?: return SnapshotOutcome.SKIPPED
        val fenceFailed = !settleOk || !durableOk
        val excludeDangling = wasStreaming || fenceFailed
        val markIncomplete = fenceFailed
        try {
            withContext(Dispatchers.IO) {
                val backing = sessions.storeOrNull()
                if (backing != null) {
                    snapshotSwitchHistory(
                        backing,
                        prevId,
                        targetId,
                        provenance.oldModelShort,
                        newModel,
                        provenance.crossProviderWithheld,
                        excludeDanglingFamilies = excludeDangling,
                        markIncomplete = markIncomplete,
                    )
                }
            }
            if (markIncomplete) {
                // The batch rode the incomplete marker atomically, so
                // the restart scan will re-warn; surface it now too
                // (overrides a withhold notice: the copy itself is
                // what degraded).
                return SnapshotOutcome.FENCE_FAILED_WARNED
            } else if (excludeDangling) {
                // Streaming switch whose terminal settled (no fence
                // failure) but whose prefix still dangled: the snapshot
                // auto-marks incomplete when it drops a family (see
                // snapshotSwitchHistory) — re-surface that warning
                // without another scan on the hot path unless needed.
                // Best-effort bounded head/tail scan: only the notice
                // is at stake.
                val droppedWarn = try {
                    withContext(Dispatchers.IO) {
                        val backing = sessions.storeOrNull()
                        backing != null && hasIncompleteSnapshotMarker(backing, targetId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (droppedWarn) return SnapshotOutcome.DANGLING_WARNED
                return SnapshotOutcome.CLEAN
            } else {
                // R6-5 byte-abandon: a clean fence + non-streaming fork
                // whose prefix blew SnapshotBudget.SNAPSHOT_MAX_TOTAL_CHARS still rides
                // the byte marker atomically (snapshotSwitchHistory
                // returns prefix.size only, so 0 is ambiguous with a
                // clean empty history) — bounded scan re-surfaces
                // HISTORY_SNAPSHOT_INCOMPLETE live, never silent until
                // restart. Phase2: the snapshot filter is now unconditional,
                // so a clean-path "dangling excluded" marker lands here too
                // (same notice; only the cause tag differs). Same best-effort scan as the branch above:
                // only the notice is at stake.
                val byteWarn = try {
                    withContext(Dispatchers.IO) {
                        val backing = sessions.storeOrNull()
                        backing != null && hasIncompleteSnapshotMarker(backing, targetId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (byteWarn) return SnapshotOutcome.BYTE_WARNED
                return SnapshotOutcome.CLEAN
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // R5-4: the atomic batch failed, so the fork holds zero
            // snapshot rows (no silent half prefix). Mark it
            // explicitly with one system incomplete row so the
            // restart scan (hasIncompleteSnapshotMarker) can re-warn;
            // surface HISTORY_SNAPSHOT_INCOMPLETE now (overrides a
            // withhold notice: the copy itself is what failed). Both
            // are best-effort bookkeeping — the live turn already
            // carries the prefix in memory, so a marker failure here
            // still must not fail the send (fail-open, degrades to
            // pre-R4-2 durability: new rows only after a restart).
            try {
                withContext(Dispatchers.IO) {
                    val backing = sessions.storeOrNull()
                    if (backing != null) {
                        backing.appendEvent(
                            TranscriptEvent(
                                sessionId = targetId,
                                runId = "snapshot-incomplete-${java.util.UUID.randomUUID()}",
                                kind = SNAPSHOT_INCOMPLETE_MARKER_KIND,
                                text = snapshotIncompleteMarkerText(
                                    provenance.oldModelShort,
                                    newModel,
                                ),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Degrades to an unmarked fork: isolation still holds,
                // only the restart notice is lost.
            }
            return SnapshotOutcome.FAILED_WARNED
        }
    }

    /** Single-flight construction. All mutable ViewModel state stays on Main. */
    private suspend fun ensureSession(
        authorized: AuthorizedEndpoint,
        title: String,
    ): SessionHandle? = sessionMutex.withLock {
        var generation = authorized.generation
        if (!isGenerationCurrent(generation)) return@withLock null

        val endpoint = readEndpoint()
        if (!isGenerationCurrent(generation)) return@withLock null
        if (endpoint == null || bindingOf(endpoint) != authorized.binding) {
            reconcileReadBinding(bindingOf(endpoint))
            return@withLock null
        }

        if (currentSession != null &&
            currentBinding == authorized.binding &&
            currentSessionGeneration == generation
        ) {
            return@withLock SessionHandle(
                CreatedSession(_currentSessionId.value, currentSession!!, endpoint.providerId, authorized.binding.model),
                authorized.binding,
                generation,
            )
        }

        // A mismatched live session is closed before any replacement work, so
        // it cannot keep sending to the old host during endpoint migration.
        // N1: reclaim its queued-but-unstarted FIFO first (same ownership as
        // the invalidate path); the current op stays in pendingOps and is
        // never touched here.
        // R6-3: streaming-switch ledger fence inputs, captured on Main before
        // any suspension and consumed on IO before the ledger read/snapshot
        // below. The just-detached mismatched session settles on IO (not
        // fire-and-forget), and any prior invalidate-parked ledger is joined
        // there too — otherwise the snapshot would copy a ledger still
        // missing its cancel terminal and the reopen backfill would stamp a
        // false INTERRUPTED.
        var mismatchedForSettle: ChatSession? = null
        var mismatchedWasStreaming = false
        var fencedJob: Job? = null
        var fencedSession: ChatSession? = null
        var fencedWasStreaming = false
        var fencedOwnerSessionId: String? = null
        if (currentSession != null) {
            val mismatched = currentSession
            mismatchedWasStreaming = isLedgerStreamingOrPending(mismatched)
            val queuedBeforeReplace: List<dev.librepocket.chat.QueuedIntent> = try {
                mismatched?.drainQueued() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            lifecycleGeneration += 1
            generation = lifecycleGeneration
            // F2: keep visible transcript when the transport is replaced.
            snapshotLiveToHistory()
            // R6-3: synchronous detach now (UI immediate); the old ledger
            // settles on IO below before any DB read (never a bare closeLive
            // and never fire-and-forget here: admitted events must drain AND
            // the doomed host must settle first, as on every other detach
            // path — see closeThenFlushDurable).
            if (mismatched != null) {
                cancelAndDetachSync(mismatched)
                mismatchedForSettle = mismatched
            }
            // Consume any prior invalidate-parked fence so the IO phase owns
            // it exactly once. R8-2: the slot owner travels with the fence so
            // a cancel re-park can restore the ledger lineage. Unconditional
            // consume is safe: the slot is either empty or owned by the
            // snapshot target below — a non-null switch never changes
            // _currentSessionId (so owner == previousSessionId), and every
            // history-abandoning path (clearChat, openSession attach,
            // binding == null) clears the slot before the next fork.
            fencedJob = pendingLedgerFence
            fencedSession = pendingLedgerSession
            fencedWasStreaming = pendingLedgerWasStreaming
            fencedOwnerSessionId = pendingLedgerOwnerSessionId
            pendingLedgerFence = null
            pendingLedgerSession = null
            pendingLedgerWasStreaming = false
            pendingLedgerOwnerSessionId = null
            _sessionState.value = EMPTY_SESSION_STATE
            // R5-5: the detached session's send-time trim no longer applies;
            // zero now so a failed/slow replacement cannot leave a stale badge
            // (mirrors invalidateForEndpointChange).
            _requestTrimmedCount.value = 0
            absorbDrainedQueued(queuedBeforeReplace, fillInput = false, includePending = false)
        } else {
            // No live mismatched, but a prior invalidate may have parked a
            // detached ledger whose settle the fork must still fence on.
            // R8-2: consecutive non-null switches hand the fence over — the
            // slot survives transport replacement (only an explicit discard
            // clears it), so a fork racing a second switch still joins the
            // first ledger's teardown instead of snapshotting early. Same
            // empty-or-owner guarantee as the consume above.
            fencedJob = pendingLedgerFence
            fencedSession = pendingLedgerSession
            fencedWasStreaming = pendingLedgerWasStreaming
            fencedOwnerSessionId = pendingLedgerOwnerSessionId
            pendingLedgerFence = null
            pendingLedgerSession = null
            pendingLedgerWasStreaming = false
            pendingLedgerOwnerSessionId = null
        }

        val previousSessionId = _currentSessionId.value
        // R8-2 (A): snapshot the discard epoch before any suspension. The
        // settle catch re-parks unless an explicit discard crossed this
        // point AND the snapshot target is gone — a bare generation bump
        // (endpoint replacement) must not read as abandonment.
        val entryDiscardEpoch = explicitDiscardEpoch
        var initialHistory: List<ChatMessage> = emptyList()
        var fork: ForkPrefix? = null
        // PG-S1: fence + read live in settleDetachedLedger (segment C); the
        // outcome locals below are read by the snapshot call, never shared
        // across segments as vars.
        val settled = settleDetachedLedger(
            previousSessionId,
            endpoint,
            fencedJob,
            fencedSession,
            fencedWasStreaming,
            fencedOwnerSessionId,
            entryDiscardEpoch,
            mismatchedForSettle,
            mismatchedWasStreaming,
        )
        val read = settled.read
        if (read != null) {
            if (!isGenerationCurrent(generation)) return@withLock null
            val afterHistory = readEndpoint()
            if (!isGenerationCurrent(generation)) return@withLock null
            if (afterHistory == null || bindingOf(afterHistory) != authorized.binding) {
                reconcileReadBinding(bindingOf(afterHistory))
                return@withLock null
            }
            val (replay, loadedHistory, crossProviderWithheld) = read
            if (replay != null) {
                _history.value = replay.messages
                _historyOmittedCount.value = replay.droppedCount
            }
            initialHistory = loadedHistory
            if (crossProviderWithheld) {
                _notice.value = "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD"
            } else if (_notice.value == "RESUME_CROSS_PROVIDER_HISTORY_WITHHELD") {
                _notice.value = null
            }
            fork = read.fork
        }

        val creationGeneration = generation
        var candidate: CreatedSession? = null
        var attached = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                candidate = sessions.create(endpoint, title, initialHistory, allowAsk = authorized.allowAsk) {
                    isBindingCurrent(authorized.binding, creationGeneration)
                }
            }
            val created = candidate ?: return@withLock null
            if (!isGenerationCurrent(creationGeneration)) return@withLock null
            val afterCreate = readEndpoint()
            if (!isGenerationCurrent(creationGeneration)) return@withLock null
            if (afterCreate == null || bindingOf(afterCreate) != authorized.binding) {
                reconcileReadBinding(bindingOf(afterCreate))
                return@withLock null
            }
            // PG-S1: durable fork snapshot lives in durableSnapshotOrWarn
            // (segment E). Detail comments (R4-2/R5-4/R6-3) moved with it;
            // the post-snapshot generation gate stays here by contract.
            val forkTargetId = created.sessionId
            val forkProvenance = fork
            if (forkTargetId != null && forkProvenance != null && previousSessionId != null) {
                val snapshotOutcome = durableSnapshotOrWarn(
                    previousSessionId,
                    forkTargetId,
                    forkProvenance,
                    created.model,
                    settled.wasStreaming,
                    settled.settleOk,
                    settled.durableOk,
                )
                // Four warn branches stay distinct (never merged): each
                // cause re-warns the same notice for a different reason.
                when (snapshotOutcome) {
                    SnapshotOutcome.FENCE_FAILED_WARNED -> _notice.value = "HISTORY_SNAPSHOT_INCOMPLETE"
                    SnapshotOutcome.DANGLING_WARNED -> _notice.value = "HISTORY_SNAPSHOT_INCOMPLETE"
                    SnapshotOutcome.BYTE_WARNED -> _notice.value = "HISTORY_SNAPSHOT_INCOMPLETE"
                    SnapshotOutcome.FAILED_WARNED -> _notice.value = "HISTORY_SNAPSHOT_INCOMPLETE"
                    SnapshotOutcome.CLEAN, SnapshotOutcome.SKIPPED -> Unit
                }
                if (!isGenerationCurrent(creationGeneration)) return@withLock null
            }
            attach(created, authorized.binding, creationGeneration)
            attached = true
            candidate = null
            SessionHandle(created, authorized.binding, creationGeneration)
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            val unattached = candidate
            if (!attached && unattached != null) {
                try {
                    sessions.discardUnattached(unattached, deleteTranscriptRow = true)
                } catch (cleanupFailure: Throwable) {
                    val originalFailure = failure
                    if (originalFailure != null) {
                        originalFailure.addSuppressed(cleanupFailure)
                    } else {
                        throw cleanupFailure
                    }
                }
            }
        }
    }

    private suspend fun isBindingCurrent(
        binding: EndpointSessionBinding,
        generation: Long,
    ): Boolean {
        if (!isGenerationCurrent(generation)) return false
        return try {
            val latest = readEndpoint()
            isGenerationCurrent(generation) && bindingOf(latest) == binding
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun attach(
        created: CreatedSession,
        binding: EndpointSessionBinding,
        generation: Long,
    ) {
        // F1: openSession() reads history before taking sessionMutex, so a
        // send() in that window can attach a live same-generation session.
        // Flush-then-close the replaced incumbent (bounded, background) so its
        // admitted transcript events still drain; its transcript row is
        // retained: it already received user input.
        val incumbent = currentSession
        if (incumbent != null && incumbent !== created.session) {
            detachAndFlushClose(incumbent)
        }
        currentSession = created.session
        currentBinding = binding
        currentSessionGeneration = generation
        _currentSessionId.value = created.sessionId
        // S2: any successful attach consumes the pending history open (send
        // success included, so a stale "開啟歷史" never lingers).
        pendingOpen = null
        _pendingOpenSessionId.value = null
        // R5-5: the new session starts untrimmed; clear any stale count from
        // the detached incumbent before the live flow re-emits.
        _requestTrimmedCount.value = 0
        sessionCollectJob?.cancel()
        sessionCollectJob = viewModelScope.launch {
            created.session.uiState.collect { state ->
                if (isGenerationCurrent(generation) && currentSession === created.session) {
                    confirmQueuedRetryFallback(created.session, state)
                    confirmQueuedRetryAccepted(created.session, state)
                    if (state.queuedRecoveryRequired && state.pendingSteerCount > 0) {
                        reclaimQueuedRecovery(created.session, generation)
                    }
                    _sessionState.value = state
                }
            }
        }
        requestTrimCollectJob?.cancel()
        requestTrimCollectJob = viewModelScope.launch {
            created.session.requestTrimmedCount.collect { trimmed ->
                if (isGenerationCurrent(generation) && currentSession === created.session) {
                    _requestTrimmedCount.value = trimmed
                }
            }
        }
    }

    /**
     * A promotion failure or cancellation leaves the controller's FIFO intact.
     * Move that unaccepted work into the explicit recovery path before another
     * admission can overtake it. This is also called immediately before
     * startOrEnqueue: its StateFlow collector can be dispatched later than a
     * user's next send. drainQueued() is atomic with controller promotion, so
     * whichever side wins retains sole ownership of each opId.
     */
    private fun reclaimQueuedRecovery(session: ChatSession, generation: Long) {
        if (!isGenerationCurrent(generation) || currentSession !== session) return
        val state = session.uiState.value
        if (!state.queuedRecoveryRequired || state.pendingSteerCount == 0) return
        val drained = try {
            session.drainQueued()
        } catch (_: Exception) {
            emptyList()
        }
        absorbQueuedRecovery(session, drained, generation)
    }

    /** Transfer an atomically drained FIFO to explicit recovery. */
    private fun absorbQueuedRecovery(
        session: ChatSession,
        drained: List<dev.librepocket.chat.QueuedIntent>,
        generation: Long,
    ): PendingOp? {
        if (drained.isEmpty() || !isGenerationCurrent(generation)) return null
        // Legacy ChatSession.steer() callers may have queued intents without
        // an operation id. Normalize once here so both absorption and Retry
        // target lookup use the same identity.
        val normalized = drained.map { queued ->
            if (queued.opId == null) queued.copy(opId = ++nextOpId) else queued
        }
        // The operation whose admission is about to run may already be in
        // pendingOps. A queue reclaim transfers only controller-owned queued
        // work; it must not steal that concurrent, still pre-accept op.
        absorbDrainedQueued(normalized, fillInput = true, includePending = false)
        val firstId = checkNotNull(normalized.first().opId)
        val op = firstId.let { id ->
            inputDraft?.takeIf { it.opId == id }?.let {
                PendingOp(id, generation, it.text, recoverySequence = it.recoverySequence)
            }
                ?: recoverableOps.firstOrNull { it.opId == id }
        }
        // A queue recovery is the latest failed operation and owns Retry,
        // even when another failed operation remains recoverable.
        val retryTarget = op?.let(::promoteQueuedRetryTarget)
        val recoveryState = session.uiState.value
        val noticeCode = when {
            recoveryState.status == ChatStatus.CANCELLED -> "CHAT_CANCELLED_RECOVERY"
            recoveryState.error == "policy evaluation failed" -> "CHAT_POLICY_UNAVAILABLE"
            recoveryState.error == "approval required" -> "CHAT_APPROVAL_REQUIRED"
            recoveryState.error == "denied by policy" -> "CHAT_SEND_DENIED"
            else -> "CHAT_QUEUE_RECOVERY_REQUIRED"
        }
        setNoticeIfCurrent(generation, noticeCode)
        return retryTarget
    }

    /**
     * A NeedsRecovery batch has already left the old controller FIFO. If that
     * controller became stale only because its endpoint was replaced, hand
     * the batch to the new generation's recovery outbox. Explicit lifecycle
     * discard (newChat/open/logout) invalidates [discardEpoch]. This runs on
     * Main with no suspension.
     */
    private fun handoffStaleRecovery(
        drained: List<dev.librepocket.chat.QueuedIntent>,
        handle: SessionHandle,
        discardEpoch: Long,
        endpointEpoch: Long,
    ) {
        if (drained.isEmpty() || explicitDiscardEpoch != discardEpoch) return
        if (lifecycleGeneration <= handle.generation || endpointReplacementEpoch <= endpointEpoch ||
            observedBinding == null
        ) return
        absorbDrainedQueued(drained, fillInput = true, includePending = false)
    }

    /**
     * key.read ASK requires a bound one-shot confirmation ([confirmKeyRead]);
     * without it authorization denies closed before any vault read. Only an
     * explicit ALLOW proceeds unconsumed; DENY always wins. The endpoint
     * revision/origin is revalidated immediately before a provider receives
     * its session-frozen credential snapshot.
     */
    private suspend fun readEndpoint(): EndpointConfig? = withContext(Dispatchers.IO) {
        store.observe().first()
    }

    private fun bindingOf(endpoint: EndpointConfig?): EndpointSessionBinding? {
        val config = endpoint ?: return null
        return try {
            config.toSessionBinding(sessions.modelFor(config))
        } catch (_: Exception) {
            null
        }
    }

    /** Flow-side detection also covers SetupViewModel saves and logout. */
    private fun observeEndpoint(endpoint: EndpointConfig?) {
        val next = bindingOf(endpoint)
        if (!hasObservedBinding) {
            hasObservedBinding = true
            observedBinding = next
            if (currentSession != null && (next == null || currentBinding != next)) {
                invalidateForEndpointChange(next)
            }
            return
        }
        if (next != observedBinding) {
            invalidateForEndpointChange(next)
        }
    }

    /**
     * Reconcile a fresh read that beat the asynchronous Flow collector.
     * Returns true when this read invalidated the operation's generation.
     */
    private fun reconcileReadBinding(binding: EndpointSessionBinding?): Boolean {
        if (!hasObservedBinding) {
            hasObservedBinding = true
            observedBinding = binding
            return false
        }
        if (binding == observedBinding) return false
        invalidateForEndpointChange(binding)
        return true
    }

    /**
     * Endpoint change invalidates the entire old operation scope, including a
     * caller that discovered the change by rereading DataStore. Such callers
     * must immediately return Stale; subsequent user actions use the new scope.
     */
    private fun invalidateForEndpointChange(binding: EndpointSessionBinding?) {
        // S3: capture the pre-invalidation notice BEFORE mutating anything: a
        // replacement that lands while a ConfirmRequired dialog is showing must
        // retain that notice (and bump the sequence for a forced re-peek of the
        // new binding) instead of clearing to null/SEND_CANCELLED, which would
        // strand the dialog on a stale snapshot.
        val hadConfirmNotice = _notice.value == "KEY_READ_CONFIRM_REQUIRED"
        observedBinding = binding
        // N1: reclaim queued-but-unstarted intents BEFORE close() drops the
        // FIFO. Queued is not acceptance, so the caller still owns the text:
        // it joins the recoverable outbox below (never auto-resent).
        // Reclaim and detach run back-to-back with no suspension on
        // this Main-confined path, so no admission slips between them; the
        // generation bump below only fences generations. The detached live
        // session is flushed (bounded) before its background close, so no
        // suspension here ever blocks the UI.
        val queuedFromSession: List<dev.librepocket.chat.QueuedIntent> = try {
            currentSession?.drainQueued() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        // S6: bump+burn share keyReadConsentLock with confirmKeyRead's mint,
        // so a racing mint cannot overwrite this invalidation's burn with a
        // stale consent (mint aborts on the generation mismatch inside the
        // same lock) and the burn cannot slip between the mint's recheck and
        // assign. P2: the pending token is burned together with the consent
        // on a cleared endpoint (logout); a replacement keeps the consent
        // slot (binding+generation match still rejects stale) but the old
        // token is superseded by the retention re-emit below.
        synchronized(keyReadConsentLock) {
            lifecycleGeneration += 1
            if (binding == null) explicitDiscardEpoch += 1
            else endpointReplacementEpoch += 1
            // A cleared endpoint (logout) discards any minted key.read consent
            // and its pending token. A replacement keeps the consent slot:
            // the consent's own binding+generation match still rejects the
            // stale snapshot, so the mismatch path stays genuinely enforced.
            if (binding == null) {
                pendingKeyReadConsent = null
                pendingKeyReadConfirm = null
            }
        }
        retryFallbackTarget = null
        retryTargetCandidate = null
        latestAcceptedStartedTarget = null
        cancelLifecycleOperations()
        openJob?.cancel()
        openJob = null
        // S2: logout (null binding) discards the pending history open; a
        // replacement keeps it stale so an explicit resume still fails closed
        // with a fresh ConfirmRequired (re-armed, never auto-adopted).
        if (binding == null) {
            pendingOpen = null
            _pendingOpenSessionId.value = null
        }
        // F2: decouple transport invalidation from transcript display.
        if (binding != null) snapshotLiveToHistory()
        releaseLiveAfterBoundedFlush()
        _sessionState.value = EMPTY_SESSION_STATE
        // R5-5: the live session is detached on every endpoint change, so its
        // send-time trim count no longer describes anything on screen. Reset
        // unconditionally (unlike _history, which survives a non-null switch
        // via snapshotLiveToHistory): the next attach re-emits from zero.
        _requestTrimmedCount.value = 0
        if (binding == null) {
            // R8-2: no endpoint means the history is abandoned (mirrors
            // clearChat): move the parked slot into the per-session
            // outstanding table (R10-1) instead of dropping it, so a later
            // reopen of the same source id still fences it. Unrelated forks
            // never join it (per-id consult only). The background
            // close-then-flush keeps running fenced by sessionId. A non-null
            // switch never lands here: it hands the fence over via
            // releaseLiveAfterBoundedFlush above.
            stashPendingSlotToOutstanding()
            pendingLedgerFence = null
            pendingLedgerSession = null
            pendingLedgerWasStreaming = false
            pendingLedgerOwnerSessionId = null
            _history.value = emptyList()
            _historyOmittedCount.value = 0
            _currentSessionId.value = null
            pendingOps.clear()
            recoverableOps.clear()
            syncRecoveryCount()
            retryableOp = null
            retryFallbackTarget = null
            retryTargetCandidate = null
            latestAcceptedStartedTarget = null
            setInput("")
            _notice.value = "NO_ENDPOINT"
        } else {
            // R2: fail-closed sends keep EVERY unaccepted op recoverable,
            // never auto-sent to the new binding. The oldest fills an empty
            // box; newer typing is never overwritten; the rest wait in
            // recoverableOps and surface one-by-one after each explicit send.
            // N1: queued-but-unstarted intents reclaimed above join the same
            // outbox via absorbDrainedQueued (pending preaccept entries first,
            // then controller-drained FIFO; never auto-sent).
            //
            // S3: replacement during ConfirmRequired retains the Confirm notice
            // and bumps the sequence (forced re-peek of the new binding) rather
            // than clearing to null/SEND_CANCELLED. The outbox is still
            // absorbed first so no unaccepted text is lost; the input box keeps
            // its restored Confirm text (absorb never overwrites non-blank).
            // S2 compat: pendingOpen stays stale (kept above, not re-armed
            // here), so an explicit resume still fails closed with a fresh
            // ConfirmRequired via rearmPendingOpenAndRequireConfirm (one more
            // sequence bump) until the user confirms the new binding.
            val hadUnaccepted = pendingOps.isNotEmpty() || queuedFromSession.isNotEmpty()
            if (hadConfirmNotice) {
                absorbDrainedQueued(queuedFromSession, fillInput = true)
                // P2: retention re-emit supersedes the old token with the new
                // binding/generation (forced re-peek); the stale token fails
                // on seq/generation/binding mismatch. Preserve the pending
                // open sid so an explicit resume still fails closed via rearm.
                val retainedOpenSid = pendingOpen?.sessionId
                val newBinding = checkNotNull(binding)
                emitKeyReadConfirmRequired(lifecycleGeneration, newBinding, openSessionId = retainedOpenSid)
            } else if (!hadUnaccepted) {
                _notice.value = null
            } else {
                absorbDrainedQueued(queuedFromSession, fillInput = true)
                _notice.value = "SEND_CANCELLED_ENDPOINT_CHANGED"
            }
        }
    }

    /**
     * Merge reclaimed queued intents plus still-pending pre-accept ops into
     * [recoverableOps] FIFO (N1/R2). Merge order is still-pending preaccept
     * entries in [pendingOps] insertion order, followed by [drained] in the
     * controller's drain/FIFO order. opId is identity only, not admission
     * order: an older recovered op may be queued after a newer op. Returns
     * true when anything became recoverable. Idempotent per opId within and
     * across calls (never duplicates entries already in [recoverableOps] or
     * the tagged input box; stale pending entries shadowed by those are
     * dropped). Moved entries are re-stamped to the current generation so a
     * later idempotent re-arm still sees them as current. When [fillInput] is
     * false the box is left untouched for the caller to surface later
     * (transport replacement mid-send); the badge count still reflects the
     * outbox. When [includePending] is false only the drained queue moves and
     * [pendingOps] is never touched (the transport-replace path, whose
     * triggering op is still alive in [pendingOps]).
     */
    private fun absorbDrainedQueued(
        drained: List<dev.librepocket.chat.QueuedIntent>,
        fillInput: Boolean,
        includePending: Boolean = true,
    ): Boolean {
        val seen = HashSet<Long>(recoverableOps.size + drained.size + 1)
        for (existing in recoverableOps) seen.add(existing.opId)
        inputDraft?.let { seen.add(it.opId) }
        val reclaimed = ArrayList<PendingOp>(drained.size)
        for (queued in drained) {
            val id = queued.opId ?: ++nextOpId
            if (!seen.add(id)) continue
            val pending = pendingOps[id]?.takeIf { it.text == queued.text }
            reclaimed.add(
                pending?.copy(generation = lifecycleGeneration)
                    ?: PendingOp(id, lifecycleGeneration, queued.text),
            )
        }
        val movedPending = if (includePending) {
            pendingOps.values.filter { seen.add(it.opId) }
                .map { if (it.generation == lifecycleGeneration) it else it.copy(generation = lifecycleGeneration) }
        } else {
            emptyList()
        }
        if (includePending) pendingOps.clear()
        // Keep controller admission order within the drained batch; IDs only
        // deduplicate operation identity and do not describe queue order.
        val merged = movedPending + reclaimed
        for (op in merged) addRecoverable(op)
        syncRecoveryCount()
        if (fillInput && merged.isNotEmpty() && _input.value.isBlank()) {
            recoverableOps.removeFirstOrNull()?.let {
                setInput(it.text, it.opId, it.text, it.recoverySequence)
            }
            syncRecoveryCount()
        }
        resolveQueuedRetryFromRecovery(drained)
        return merged.isNotEmpty()
    }

    private fun invalidateLifecycle(clearChat: Boolean) {
        // S6: same lock as the mint (see invalidateForEndpointChange): the
        // generation bump and the consent+token burn are atomic, so a confirm
        // parked in readEndpoint cannot mint over this clear. P2: newChat/
        // open new round burns the pending token together with the consent,
        // so a stale tap captured before the clear fails closed.
        synchronized(keyReadConsentLock) {
            lifecycleGeneration += 1
            if (clearChat) explicitDiscardEpoch += 1
            if (clearChat) {
                pendingKeyReadConsent = null
                pendingKeyReadConfirm = null
            }
        }
        cancelLifecycleOperations()
        openJob?.cancel()
        openJob = null
        // Flush (bounded, background) before close on the newChat / openSession
        // reset path; the UI observes only the synchronous detach above.
        releaseLiveAfterBoundedFlush()
        _sessionState.value = EMPTY_SESSION_STATE
        _notice.value = null
        if (clearChat) {
            // R6-3 (b): the history is discarded below, so no future fork
            // snapshots the detached ledger via the single slot — move the
            // slot into the per-session outstanding table instead of dropping
            // it (R10-1). The background close-then-flush keeps running fenced
            // by sessionId: a later unrelated fork never joins it (its open
            // consults only its own id), while a later reopen of the same
            // source id still fences it (settle-or-defer, R9-3 block
            // semantics). Only the UI/history binding is cleared here.
            // R8-2: the slot owner lineage moves with the entry.
            stashPendingSlotToOutstanding()
            pendingLedgerFence = null
            pendingLedgerSession = null
            pendingLedgerWasStreaming = false
            pendingLedgerOwnerSessionId = null
            _history.value = emptyList()
            _historyOmittedCount.value = 0
            // R5-5: the owning session is detached; a later attach restarts
            // the trim count from zero.
            _requestTrimmedCount.value = 0
            _currentSessionId.value = null
            pendingOpen = null
            _pendingOpenSessionId.value = null
            pendingOps.clear()
            recoverableOps.clear()
            syncRecoveryCount()
            retryableOp = null
            retryFallbackTarget = null
            retryTargetCandidate = null
            latestAcceptedStartedTarget = null
            inputDraft = null
        }
    }

    private fun isGenerationCurrent(generation: Long): Boolean =
        lifecycleGeneration == generation

    private fun cancelLifecycleOperations() {
        // Also covers a retry launch cancelled before its coroutine body gets
        // dispatched; its eventual finally is identity-checked and harmless.
        retryInFlight = null
        lifecycleJob.cancel()
        lifecycleJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        lifecycleScope = CoroutineScope(viewModelScope.coroutineContext + lifecycleJob)
    }

    private fun isHandleCurrent(handle: SessionHandle): Boolean =
        isGenerationCurrent(handle.generation) &&
            currentSession === handle.created.session &&
            currentBinding == handle.binding &&
            currentSessionGeneration == handle.generation

    private fun setNoticeIfCurrent(generation: Long, value: String) {
        if (isGenerationCurrent(generation)) _notice.value = value
    }

    /**
     * S7：ConfirmRequired 發布單一入口。世代不符回 false（不碰
     * notice/sequence/token）；否則以同 emission 產生 P2 pending token
     *（binding＋generation＋新 sequence＋opId/openSessionId）並設 notice、
     *單調遞增 sequence。confirm/dismiss/peek 永不經此（不 bump）。
     *
     * P2 發布順序（全程無 suspension，Main-confined）：token 落定才發布
     * notice＋sequence（token → notice → sequence）。觀測到 notice 即
     * token 可見；觀測到新 sequence 即其 token 可見。 racing 的 bump+burn
     * 在鎖內仍優先（emission 在發布前中止，不留半套 notice/sequence）。
     * 呼叫方（send 路徑）須先完成輸入／outbox 還原再調用，使觀測到
     * notice 即輸入已還原（同 restoreOpForFailedSend 的 notice-last 形狀）。
     */
    private fun emitKeyReadConfirmRequired(
        generation: Long,
        binding: EndpointSessionBinding,
        opId: Long? = null,
        openSessionId: String? = null,
    ): Boolean {
        if (!isGenerationCurrent(generation)) return false
        val nextSequence = _keyReadConfirmSequence.value + 1
        val token = PendingKeyReadConfirm(
            binding = binding,
            generation = generation,
            confirmSequence = nextSequence,
            opId = opId,
            openSessionId = openSessionId,
        )
        synchronized(keyReadConsentLock) {
            // Main-confined no-suspend path: generation cannot have moved
            // between the check above and here without an interleaving
            // suspension, but recheck under lock keeps mint/burn atomicity
            // (a racing bump+burn wins over this emission before anything
            // is published).
            if (!isGenerationCurrent(generation)) return false
            pendingKeyReadConfirm = token
        }
        _notice.value = "KEY_READ_CONFIRM_REQUIRED"
        _keyReadConfirmSequence.value = nextSequence
        return true
    }

    // F2: retain visible user/assistant messages before dropping live state,
    // so a model/revision change never blanks the screen until the next send
    // replays. Id-deduped; logout/newChat clearing is preserved elsewhere.
    // Timing note: the VM projection (_sessionState) trails the controller by
    // one collector dispatch. A text already accepted (appended) but not yet
    // projected must still survive: also read the live session truth
    // (synchronous StateFlow value) before the detach below nulls it. Accepted
    // text belongs here in _history, never in the unsent outbox.
    private fun snapshotLiveToHistory() {
        val projected = _sessionState.value.messages.filter { it.role == "user" || it.role == "assistant" }
        // Accepted-but-unprojected tail: already appended by startOrEnqueue,
        // collector not yet dispatched. Read before closeLive(); exceptions
        // (closed session) mean nothing to add, never fail the switch.
        val direct = try {
            currentSession?.uiState?.value?.messages?.filter { it.role == "user" || it.role == "assistant" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: emptyList()
        // Direct is strictly newer than projected (same ids evolve in place:
        // placeholder → deltas → finalized), so it wins on conflict.
        val merged = LinkedHashMap<String, UiMessage>(projected.size + direct.size)
        for (m in projected) merged[m.id] = m
        for (m in direct) merged[m.id] = m
        val live = merged.values.toList()
        if (live.isEmpty()) return
        val existing = _history.value.map { it.id }.toSet()
        val fresh = live.filter { it.id !in existing }
        if (fresh.isNotEmpty()) _history.value = _history.value + fresh
    }

    // R2: restore one op by identity, so two identical texts never
    // cross-clear, and only when the input box is still empty, so later
    // typing is never overwritten. A non-blank box keeps the user's text and
    // the cancelled op waits in recoverableOps instead of being deleted.
    // Explicit newChat/logout/open already discarded the op.
    // Q2: idempotent per opId (a re-denied adopted retry re-arms the same
    // entry instead of growing copies) and re-arms retryableOp; the restored
    // box is tagged so retry() can adopt it.
    private fun restoreOpForFailedSend(opId: Long, noticeValue: String) {
        if (recoverableOps.any { it.opId == opId } || inputDraft?.opId == opId) {
            val stashed = recoverableOps.firstOrNull { it.opId == opId }
            if (stashed != null && isGenerationCurrent(stashed.generation)) setRetryableIfNewer(stashed)
            return
        }
        val op = pendingOps.remove(opId) ?: return
        if (!isGenerationCurrent(op.generation)) return
        // Gate calls may settle out of order. A delayed failure for an older
        // send must not steal Retry from newer queued recovery already
        // reclaimed by the session-state collector.
        val recoverable = withRecoverySequence(op)
        setRetryableIfNewer(recoverable)
        if (_input.value.isNotBlank()) {
            addRecoverable(recoverable)
            syncRecoveryCount()
            return
        }
        setInput(recoverable.text, recoverable.opId, recoverable.text, recoverable.recoverySequence)
        _notice.value = noticeValue
    }

    /**
     * S6: ConfirmRequired restore path. Same input/recoverable ownership as
     * [restoreOpForFailedSend] but never arms [retryableOp]: Retry stays
     * controller-ERROR-only ([resolveRetryTarget] already gates on
     * `ChatStatus.ERROR`, and this path leaves IDLE), so a
     * KEY_READ_CONFIRM_REQUIRED must not become retryable even if an ERROR
     * is already visible from another turn.
     */
    private fun restoreOpForConfirmRequired(opId: Long, noticeValue: String) {
        if (recoverableOps.any { it.opId == opId } || inputDraft?.opId == opId) {
            if (retryableOp?.opId == opId) retryableOp = null
            return
        }
        val op = pendingOps.remove(opId) ?: return
        if (!isGenerationCurrent(op.generation)) return
        if (retryableOp?.opId == opId) retryableOp = null
        val recoverable = withRecoverySequence(op)
        // Intentionally no setRetryableIfNewer.
        if (_input.value.isNotBlank()) {
            addRecoverable(recoverable)
            syncRecoveryCount()
            return
        }
        setInput(recoverable.text, recoverable.opId, recoverable.text, recoverable.recoverySequence)
        _notice.value = noticeValue
    }
    /**
     * R5-1 UNKNOWN settle waiter (bounded, never blocks the UI). The admission
     * already threw [TranscriptPendingException] with ownership retained
     * (pendingOps kept, no draft restore). This waiter drives one bounded
     * [ChatSession.settlePendingAdmission] and flips the notice:
     * - claimed (ghost durable, UI row present, no resend): drop the pending
     *   op as accepted and clear a stale PENDING notice;
     * - failed/sealed (row rolled back): restore as `CHAT_STORE_UNAVAILABLE`
     *   (retryable, same restore semantics as fail-closed);
     * - still unknown (flush timeout): keep PENDING; a later explicit retry
     *   re-settles via the controller guard.
     */
    private fun launchPendingSettleWaiter(opId: Long, text: String, handle: SessionHandle, generation: Long) {
        lifecycleScope.launch {
            val settled: Boolean = try {
                handle.created.session.settlePendingAdmission(SESSION_FLUSH_TIMEOUT_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!isHandleCurrent(handle) || !isGenerationCurrent(generation)) return@launch
            if (!settled) return@launch
            val state = try {
                handle.created.session.uiState.value
            } catch (_: Exception) {
                null
            }
            val hasRow = state?.messages?.any { it.role == "user" && it.operationId == opId } == true
            if (hasRow) {
                // Claimed under the same id: the text is accepted, nothing to
                // restore and nothing to resend. Mirror Started bookkeeping so
                // a stale gate cannot steal Retry afterwards.
                pendingOps.remove(opId)
                if (retryableOp?.opId == opId) retryableOp = null
                if (_notice.value == "CHAT_STORE_PENDING") _notice.value = null
                drainNextRecoverableToInput()
                return@launch
            }
            // No row: settle converted to fail-closed (or a racing close
            // dropped it). Flip to retryable UNAVAILABLE; the text is retained
            // for explicit retry, never auto-resent.
            if (pendingOps.containsKey(opId)) {
                setNoticeIfCurrent(generation, "CHAT_STORE_UNAVAILABLE")
                restoreOpForFailedSend(opId, "CHAT_STORE_UNAVAILABLE")
            } else if (_notice.value == "CHAT_STORE_PENDING") {
                _notice.value = "CHAT_STORE_UNAVAILABLE"
            }
        }
    }

    /** A stale gate restore cannot outrank a newer Started target or later retry attempt. */
    private fun setRetryableIfNewer(op: PendingOp) {
        val accepted = latestAcceptedStartedTarget
        if (accepted != null && op.retryTargetEpoch < accepted.retryTargetEpoch) return
        val current = retryableOp
        if (current == null || op.retryTargetEpoch > current.retryTargetEpoch ||
            (op.retryTargetEpoch == current.retryTargetEpoch && op.opId >= current.opId)
        ) {
            retryableOp = op
        }
    }

    /** A reclaimed queue entry keeps its established priority over the admission that exposed it. */
    private fun promoteQueuedRetryTarget(op: PendingOp): PendingOp {
        val target = op.copy(retryTargetEpoch = ++nextRetryTargetEpoch)
        retryableOp = target
        return target
    }

    /** A new retry-target attempt invalidates any older post-accept fallback candidate. */
    private fun beginRetryTargetCandidate(
        opId: Long,
        text: String,
        recoverySequence: Long? = null,
    ): PendingOp {
        val stableRecoverySequence = recoverySequence ?: ++nextRecoverySequence
        nextRecoverySequence = maxOf(nextRecoverySequence, stableRecoverySequence)
        val candidate = PendingOp(
            opId = opId,
            generation = lifecycleGeneration,
            text = text,
            retryTargetEpoch = ++nextRetryTargetEpoch,
            recoverySequence = stableRecoverySequence,
        )
        retryTargetCandidate = candidate
        retryFallbackTarget = null
        return candidate
    }

    /**
     * Queued transfers ordering authority to the controller FIFO, while a
     * preaccept denial keeps the existing recovery sequence on pendingOps.
     * Only Started advances the started marker.
     */
    private fun recordQueuedRetryTarget(opId: Long, text: String, session: ChatSession) {
        pendingOps[opId]?.takeIf { it.text == text }?.let {
            pendingOps[opId] = it.copy(recoverySequence = null)
        }
        if (retryTargetCandidate?.let { it.opId == opId && it.text == text } == true) {
            confirmQueuedRetryFallback(session, session.uiState.value)
        }
    }

    private fun confirmQueuedRetryFallback(session: ChatSession, state: ChatUiState) {
        if (session !== currentSession) return
        val candidate = retryTargetCandidate ?: return
        if (state.messages.any { it.role == "user" && it.operationId == candidate.opId }) {
            retryFallbackTarget = candidate.copy(generation = lifecycleGeneration)
        }
    }

    /** Keep the still-unaccepted admission behind the FIFO batch it just exposed. */
    private fun rebasePendingAdmissionAfterRecovery(opId: Long, text: String, generation: Long) {
        if (!isGenerationCurrent(generation)) return
        val pending = pendingOps[opId]?.takeIf {
            it.text == text && it.generation == generation
        } ?: return
        val tailSequence = ++nextRecoverySequence
        pendingOps[opId] = pending.copy(recoverySequence = tailSequence)
        val candidate = retryTargetCandidate
        if (candidate?.opId == opId && candidate.text == text) {
            retryTargetCandidate = candidate.copy(recoverySequence = tailSequence)
        }
    }

    /** Started, unlike Queued, proves this target crossed the controller admission gate. */
    private fun recordStartedRetryTarget(opId: Long, text: String) {
        pendingOps[opId]?.takeIf { it.text == text }?.let {
            pendingOps[opId] = it.copy(recoverySequence = null)
        }
        val started = pendingOps[opId]?.takeIf { it.text == text } ?: return
        if (retryTargetCandidate?.let { it.opId == opId && it.text == text } == true) {
            retryFallbackTarget = retryTargetCandidate?.copy(generation = lifecycleGeneration)
        }
        val current = latestAcceptedStartedTarget
        if (current == null || started.retryTargetEpoch > current.retryTargetEpoch) {
            latestAcceptedStartedTarget = started
            if (retryableOp?.let {
                    it.retryTargetEpoch < started.retryTargetEpoch || it.opId == opId
                } == true
            ) {
                retryableOp = null
            }
        }
    }

    private fun resolveRetryTarget(): Pair<PendingOp?, String>? {
        if (_sessionState.value.status != ChatStatus.ERROR) return null
        retryableOp?.let { return it to it.text }
        retryFallbackTarget?.let { target ->
            val recovered = recoverableOps.firstOrNull { it.opId == target.opId }
            return recovered to (recovered?.text ?: target.text)
        }
        val candidate = retryTargetCandidate ?: return null
        val recovered = recoverableOps.firstOrNull { it.opId == candidate.opId } ?: return null
        return recovered to recovered.text
    }

    // R2: surface the next endpoint-cancelled text after an explicit send
    // completes, so every unaccepted op is recoverable through the input box
    // without auto-sending anything to the new binding. The drained box is
    // tagged for a possible retry adoption.
    private fun drainNextRecoverableToInput() {
        if (_input.value.isNotBlank()) return
        val next = recoverableOps.removeFirstOrNull() ?: return
        syncRecoveryCount()
        setInput(next.text, next.opId, next.text, next.recoverySequence)
    }

    // N2: true only while the box still holds an unmodified tagged restore
    // (deny/endpoint-cancel recovery owned by that opId). Any user edit goes
    // through setInput() and untags, so this never mistakes fresh typing for
    // recovery state.
    private fun isBoxHoldingTaggedDraft(): Boolean {
        val draft = inputDraft ?: return false
        return draft.revision == inputRevision && _input.value == draft.text
    }

    /**
     * N2: move a tagged recoverable draft from the box into the outbox before
     * a direct send clears it (sendDirect never adopts the box, unlike
     * send()). Only explicit user overwrite (untagged via onInputChange),
     * explicit discard, or newChat/open/logout may drop it unstashed.
     * Idempotent per opId; the stashed entry is always stamped with the
     * current generation (same contract as absorbDrainedQueued). Restore and
     * retry provenance carries its original recovery sequence, so it returns
     * to that position even if newer entries were added meanwhile. A fresh
     * attempt receives its sequence before its gate, preserving creation order
     * when concurrent denials settle out of order. Never auto-sends. Returns
     * the stashed entry, or null when the box held no tagged draft. Ownership
     * of [retryableOp] is decided by the caller.
     */
    private fun stashInputDraftToRecoverable(): PendingOp? {
        val draft = inputDraft ?: return null
        if (draft.revision != inputRevision || _input.value != draft.text) return null
        recoverableOps.firstOrNull { it.opId == draft.opId }?.let { return it }
        val op = PendingOp(
            opId = draft.opId,
            generation = lifecycleGeneration,
            text = draft.text,
            recoverySequence = draft.recoverySequence,
        )
        val stashed = addRecoverable(op)
        syncRecoveryCount()
        return stashed
    }

    /**
     * Synchronous detach + close, only for [onCleared]: ViewModel teardown
     * cannot host background work (viewModelScope is cancelled there), so
     * there is no background flush to await — the bounded drain relies on
     * [dev.librepocket.chat.TurnController.close]'s internal bounded
     * settle+drain+seal instead. Every other detach path uses
     * [detachAndFlushClose] so admitted events drain before close.
     */
    private fun closeLive() {
        sessionCollectJob?.cancel()
        sessionCollectJob = null
        requestTrimCollectJob?.cancel()
        requestTrimCollectJob = null
        try {
            currentSession?.close()
        } catch (_: Exception) {
        }
        currentSession = null
        currentBinding = null
        currentSessionGeneration = null
    }

    /**
     * Detach + bounded close-then-flush for explicit lifecycle resets
     * (newChat / openSession pre-clear), endpoint switches, transport
     * replacement, and attach-time incumbent replacement. The live turn is
     * cancelled synchronously (prompt stop, IO-free, a no-op when idle) and
     * VM state is detached synchronously so the UI updates immediately; the
     * detached session is then closed and flushed on [viewModelScope] —
     * never the per-generation lifecycle scope, so a later invalidation
     * cannot cancel another generation's teardown — strictly in that order.
     *
     * R6-3 order (close before flush): [ChatSession.flush] alone only drains
     * barrier-reserved terminals; a cancel terminal whose host has not settled
     * yet is still unreserved and would be missed. [close] first settles the
     * doomed host (bounded: its NonCancellable terminal is admitted ahead of
     * the flush barrier via the shutdown settle), then the flush drains it.
     * No outer timeout wraps the background work: [ChatSession.flush] is
     * already bounded (one [SESSION_FLUSH_TIMEOUT_MS] budget for the close
     * shutdown-join plus one for the admitted-event drain, ~2x total after
     * close). [close] runs under [NonCancellable] so teardown always seals
     * the session even if the background launch is cancelled. A stalled
     * store only delays that background close — the next generation is
     * unaffected because all VM references were already detached above.
     * Returns the background teardown job so a later `ensureSession` fork can
     * fence on it (see [pendingLedgerFence]).
     */
    private fun detachAndFlushClose(session: ChatSession): Job {
        cancelAndDetachSync(session)
        return launchCloseThenFlush(session)
    }

    /**
     * R6-3 synchronous half of [detachAndFlushClose]: prompt cancel (IO-free)
     * plus VM-state detach, with no suspension so the UI updates immediately.
     * The caller owns the ledger settle separately: either
     * [launchCloseThenFlush] (fire-and-forget fence) or an IO-bounded
     * close-then-flush await inside `ensureSession` before the snapshot read.
     */
    private fun cancelAndDetachSync(session: ChatSession) {
        // Prompt stop first: cancel() is synchronous, IO-free, and a no-op
        // when idle, so a running turn never lingers into the flush window.
        try {
            session.cancel()
        } catch (_: Exception) {
        }
        if (currentSession === session) {
            sessionCollectJob?.cancel()
            sessionCollectJob = null
            requestTrimCollectJob?.cancel()
            requestTrimCollectJob = null
            currentSession = null
            currentBinding = null
            currentSessionGeneration = null
        }
    }

    /**
     * R6-3 background half: close (settle, includes the doomed-host terminal
     * admission) strictly before the durable flush, on [viewModelScope] so it
     * survives per-generation invalidation. Callers that need the ledger
     * before forking (same-origin `ensureSession`) await the returned job
     * (bounded, on IO) instead of only launching it.
     */
    private fun launchCloseThenFlush(session: ChatSession): Job {
        val job = viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    session.close()
                }
            } catch (_: Exception) {
            }
            try {
                session.flushDurable(SESSION_FLUSH_TIMEOUT_MS)
            } catch (_: Exception) {
                // Bounded: timeout or flush failure still leaves the session
                // sealed via close above. A scope-teardown
                // CancellationException is absorbed here on purpose: close()
                // above is the teardown that matters and still runs under
                // NonCancellable.
            }
            try {
                withContext(NonCancellable) {
                    session.close()
                }
            } catch (_: Exception) {
            }
        }
        return job
    }

    /**
     * R9-2 composite fence join: await [prior] then [fresh] sequentially,
     * swallowing store failures but propagating caller cancellation (same
     * semantics as every inline composite-join launch it replaces). Runs on
     * [viewModelScope] so it survives per-generation invalidation.
     */
    private fun compositeLedgerJoin(prior: Job, fresh: Job): Job = viewModelScope.launch {
        try {
            prior.join()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        try {
            fresh.join()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /**
     * R6-3 IO-bounded settle for a just-detached old session: close (settle)
     * then durable flush, one [SESSION_FLUSH_TIMEOUT_MS] budget per phase
     * (~2x total after close). Returns true only when the drain completed AND
     * no durable failure was recorded AND no cancel receipt is still unsettled
     * (R19-1 PENDING/CLAIMED via [ChatSession.hasUnsettledCancelReceipts],
     * conjoined inside `flushDurable`); false (timeout / flushDurable false /
     * store throw) means the fork must degrade via the incomplete path (see
     * `ensureSession`). Caller cancellation still propagates.
     */
    private suspend fun closeThenFlushDurable(
        session: ChatSession,
        perPhaseTimeoutMs: Long = SESSION_FLUSH_TIMEOUT_MS,
    ): Boolean {
        try {
            withContext(NonCancellable) {
                session.close()
            }
        } catch (_: Exception) {
        }
        return try {
            session.flushDurable(perPhaseTimeoutMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * R9-3 (A) sessionId-granularity backfill gate. Must be called with
     * [sessionMutex] held on Main (same confinement as
     * [pendingBackfillBlock]); the promotion probe suspends on IO internally
     * and resumes on Main before touching the map.
     *
     * - `settleOk && durableOk` with no live block backfills (unchanged).
     * - `!backfillNow` (settle failed: flush-timeout means the old writer
     *   may still land its terminal late; flush-failure means it is sealed)
     *   parks `block[sessionId] = now + horizon` and returns false. The caller
     *   still hydrates and warns HISTORY_SNAPSHOT_INCOMPLETE; the next open
     *   retries.
     * - A later open with `backfillNow == true` but a live (unexpired) block
     *   is forced to false UNLESS promotion proves the ledger already
     *   terminal (no dangling family in the backfill window AND the detached
     *   live shows no pending evidence) — this is the second-reopen fix: a
     *   fresh idle live settles trivially while the old writer is still
     *   alive, and only the ledger state tells the two apart.
     * - R10-2 expired block: NEVER promotes on the clock alone. Expiry runs
     *   the verifiable promotion ([tryPromoteExpiredBlock]): ledger clean +
     *   fresh live clean + old writer not alive + no live outstanding
     *   teardown promotes (or writer-dead proof — sealed + pending empty +
     *   writer completed (R14-1) + no non-sticky obligation evidence (R22
     *   Phase 2 barrier-aware, R23-1 sticky split: permanent close debt and
     *   SEALED-uncompensated receipts stay in [ChatSession.flushDurable] but never block
     *   the dead proof) —
     *   promotes even with a dangling ledger, since its terminal will never
     *   land and marking INTERRUPTED is then correct). Anything unverified
     *   renews the deadline and defers again (fail-closed: the caller keeps
     *   warning HISTORY_SNAPSHOT_INCOMPLETE). Deadlines use the monotonic
     *   [backfillClockMs], never wall-clock, so a clock jump cannot prematurely
     *   promote an unsettled ledger.
     *
     * Idempotent and fail-closed: a probe failure keeps the block until its
     * deadline. Caller cancellation propagates.
     *
     * Notice scope: every `!backfillNow` here surfaces the same
     * HISTORY_SNAPSHOT_INCOMPLETE as a failed fork snapshot (see the
     * canonical-form `open` KDoc) — cause-agnostic by design, with different
     * follow-ups (a defer retries the backfill on the next open; a snapshot
     * failure persists its marker and re-warns without ever re-copying).
     *
     * Sealed alive-vs-dead bifurcation (R23-1): the expired-block
     * sealed-dead vs sealed-but-obligated split is pinned end-to-end by
     * `ChatSameIdReopenStickyDebtTest` (real ViewModel + real factory + real
     * controller, ghost + sealed groups) — referenced, not re-pinned here.
     */
    private suspend fun gateBackfillForSession(
        sessionId: String,
        settleOk: Boolean,
        durableOk: Boolean,
        detached: ChatSession?,
        writerEvidence: ChatSession? = null,
    ): Boolean {
        var backfillNow = settleOk && durableOk
        val nowMs = backfillClockMs()
        val blockedUntil = pendingBackfillBlock[sessionId]
        if (blockedUntil != null) {
            if (nowMs >= blockedUntil) {
                if (tryPromoteExpiredBlock(sessionId, detached, writerEvidence)) {
                    pendingBackfillBlock.remove(sessionId)
                    pendingBackfillWriter.remove(sessionId)
                } else {
                    backfillNow = false
                    pendingBackfillBlock[sessionId] = nowMs + backfillBlockHorizonMs
                    rememberBlockWriter(sessionId, writerEvidence)
                    rememberBlockWriter(sessionId, detached)
                }
            } else if (backfillNow) {
                val ledgerClean = try {
                    withContext(Dispatchers.IO) { probeLedgerHasNoDangling(sessionId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                // B1: re-read the fresh detached liveness AFTER the probe
                // suspension — a new idle→streaming transition (fresh
                // dangling) landing while the probe was suspended must not
                // promote. Live-block verifies the fresh live only: the
                // stored old writer may keep a stale STREAMING status after
                // its terminal landed (pending empty, ledger clean), which
                // must not shadow the ledger proof here — the stored writer
                // is still fully verified on the expiry path below.
                val liveClean = !isWriterAliveEvidence(detached)
                if (ledgerClean && liveClean) {
                    pendingBackfillBlock.remove(sessionId)
                    pendingBackfillWriter.remove(sessionId)
                } else {
                    backfillNow = false
                }
            }
        }
        if (!backfillNow) {
            pendingBackfillBlock[sessionId] = nowMs + backfillBlockHorizonMs
            rememberBlockWriter(sessionId, writerEvidence)
            rememberBlockWriter(sessionId, detached)
        }
        return backfillNow
    }

    /**
     * R10-2 expiry promotion proof. Returns true only when the blocked ledger
     * verifiably settled (or its writer verifiably can never settle):
     * - the fresh [detached] live shows no streaming/pending evidence, AND
     * - the slot evidence ([writerEvidence]: retained slot / outstanding
     *   session, checked even when it differs from stored and sits outside
     *   the outstanding table — B4) shows none, AND
     * - no live outstanding teardown for [sessionId] is still settling, AND
     * - the stored old writer (see [pendingBackfillWriter]) shows none, AND
     * - either the ledger proves its terminal (no dangling family) OR a
     *   stored/slot old writer is provably dead (sealed + pending empty +
     *   writer completed + no non-sticky obligation evidence — R23-1: sticky
     *   close debt and SEALED-uncompensated receipts never block the dead
     *   proof — so
     *   its terminal will never land and an INTERRUPTED mark is correct).
     * B1: liveness is verified BEFORE the probe (fast fail) and re-verified
     * AFTER it — a probe suspension window that lands a new dangling must
     * not promote. B2: sealed-dead is decided FIRST: a stuck STREAMING
     * status on a sealed writer never blocks the dead proof (dead is not
     * alive). Anything else (probe failure, live writer, unknown writer
     * with a still-dangling ledger) returns false so the caller renews the
     * block and keeps deferring. Never throws (best-effort reads only);
     * caller cancellation propagates from the ledger probe.
     */
    private suspend fun tryPromoteExpiredBlock(
        sessionId: String,
        detached: ChatSession?,
        writerEvidence: ChatSession? = null,
    ): Boolean {
        // B2 first: dead is not alive — check liveness sealed-aware.
        if (isWriterAliveEvidence(detached)) return false
        if (writerEvidence !== detached && isWriterAliveEvidence(writerEvidence)) return false
        run {
            val s = pendingBackfillWriter[sessionId]
            if (s !== detached && s !== writerEvidence && isWriterAliveEvidence(s)) return false
        }
        if (peekOutstandingLive(sessionId)) return false
        val ledgerClean = try {
            withContext(Dispatchers.IO) { probeLedgerHasNoDangling(sessionId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // B1 post-probe re-verification: the probe suspended on IO while the
        // mutex was held; a live transition in that window must fail closed.
        if (isWriterAliveEvidence(detached)) return false
        if (writerEvidence !== detached && isWriterAliveEvidence(writerEvidence)) return false
        val stored = pendingBackfillWriter[sessionId]
        if (stored !== detached && stored !== writerEvidence && isWriterAliveEvidence(stored)) {
            return false
        }
        if (peekOutstandingLive(sessionId)) return false
        if (ledgerClean) return true
        if (stored != null && isWriterSealedDead(stored)) return true
        if (writerEvidence !== stored && writerEvidence != null && isWriterSealedDead(writerEvidence)) {
            return true
        }
        run {
            purgeExpiredOutstanding()
            val outstandingSession = outstandingTeardowns[sessionId]?.session
            if (outstandingSession !== stored && outstandingSession !== writerEvidence &&
                outstandingSession !== detached && outstandingSession != null &&
                isWriterSealedDead(outstandingSession)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * B2 sealed-aware liveness: a provably-dead writer (sealed + pending
     * empty + writer completed AND no non-sticky obligation evidence, see
     * [isWriterSealedDead]) is NOT alive, even when its uiState still sticks
     * at STREAMING after the seal — and even while permanent sticky debt
     * (close-dropped UNKNOWN/promotion fates, SEALED-uncompensated receipts)
     * still trips [isLedgerStreamingOrPending] below. Lets the expiry
     * dead-proof stay reachable instead of being shadowed by the stuck status
     * or by unliftable debt. Dead-first order is safe for the barrier shape
     * because the dead proof itself stays barrier-aware (R22 Phase 2, R23-1
     * split): sealed + still-unsettled admission / promotion / host /
     * PENDING-CLAIMED receipt evidence is never dead, so
     * [isLedgerStreamingOrPending] still reports alive and the block keeps
     * deferring. Best-effort, never throws.
     *
     * Defense-in-depth note (D7): this sealed-aware check and the raw
     * [isLedgerStreamingOrPending] below are deliberately two answers — the
     * dead-first short-circuit keeps expiry reachable under a stuck
     * STREAMING status or unliftable sticky debt, while the raw evidence
     * keeps `flushDurable` honest (never true-and-dangling). TODO: converge
     * both call sites onto a single `promotionProof` value object when the
     * call graph allows it without widening the barrier semantics; until
     * then the split is documented here, not hidden.
     */
    private fun isWriterAliveEvidence(session: ChatSession?): Boolean {
        if (session == null) return false
        if (isWriterSealedDead(session)) return false
        return isLedgerStreamingOrPending(session)
    }

    /**
     * R10-2 pending-writer identity store. Keeps the FIRST unsettled writer
     * observed for [sessionId] until the block lifts: later renewals carry a
     * fresh idle detached (which proves nothing about the old writer) and
     * must not overwrite it. A null evidence keeps whatever exists.
     * B3: the first store is filtered — only a writer with live
     * streaming/pending evidence or a sealed writer is kept; a clean
     * fresh-idle detached (unsealed + no evidence) proves nothing about the
     * old writer and must not occupy the slot ahead of the true culprit.
     */
    private fun rememberBlockWriter(sessionId: String, evidence: ChatSession?) {
        if (evidence == null) return
        if (pendingBackfillWriter[sessionId] != null) return
        val alive = try {
            isLedgerStreamingOrPending(evidence)
        } catch (_: Exception) {
            false
        }
        val sealed = try {
            evidence.isTranscriptSealed()
        } catch (_: Exception) {
            false
        }
        if (!alive && !sealed) return
        pendingBackfillWriter[sessionId] = evidence
    }

    /**
     * R10-2: peek without consuming — true while the same-id teardown still settles.
     * S4-F1: shares [outstandingLiveness] with [purgeExpiredOutstanding] (fence==null
     * + session!=null unexpired stays live/defer, never promote); B2 sealed-dead
     * short-circuit lives inside the shared helper so the two can never fork.
     */
    private fun peekOutstandingLive(sessionId: String): Boolean {
        purgeExpiredOutstanding()
        val entry = outstandingTeardowns[sessionId] ?: return false
        return outstandingLiveness(entry)
    }

    /**
     * R10-2 writer-dead proof: sealed AND no pending admission AND no unacked
     * cores AND no non-sticky terminal-obligation evidence. Best-effort, never throws.
     *
     * R14-1 fourth conjunct: the writer itself must have completed
     * ([ChatSession.isTranscriptWriterCompleted]). Seal + empty pending
     * alone is observable right after a timed-out shutdown (channel closed,
     * pending cleared via interrupt marks) while the writer is still inside
     * a blocking store block whose COMMIT may still land — treating that
     * state as dead writes a false INTERRUPTED. Unknown/completed-proof
     * failures stay fail-closed (false → entry kept).
     *
     * R22 Phase 2 barrier-aware (P1-1/P1-3), R23-1 sticky split: seal + empty
     * UNKNOWN/pending is ALSO observable while a live terminal obligation is
     * still open — a direct/promotion admission window
     * ([ChatSession.hasUnsettledAdmission]), a promotion reservation/ghost
     * ([ChatSession.hasUnsettledPromotion]), a live host
     * ([ChatSession.hasActiveHost]), or a PENDING/CLAIMED receipt
     * ([ChatSession.hasUnsettledCancelReceipts]). Any such evidence forces
     * false (not dead), so the expiry promotion
     * ([tryPromoteExpiredBlock]) keeps deferring and [isWriterAliveEvidence]
     * (dead-first but barrier-aware) still reports alive for the barrier
     * shape. Permanent sticky debt is NOT liveness evidence and never blocks
     * this proof: a close-dropped UNKNOWN/promotion fate
     * ([ChatSession.hasClosedUnsettledAdmission]) and a SEALED-uncompensated
     * receipt ([ChatSession.hasSealedUncompensatedReceipts]) stay conjoined
     * into [ChatSession.flushDurable] (never true-and-dangling) and still trip
     * [isLedgerStreamingOrPending], but a sealed + pending-empty + completed
     * writer with no live obligation above is dead even while they hold —
     * its terminal will never land, so the expired deferred-backfill's single
     * INTERRUPTED is correct. Never clear the sticky flags to reach this
     * (that would read false-clean); they lift only via reopen backfill.
     * All reads share one best-effort try: any failure stays fail-closed
     * (false → entry kept).
     */
    private fun isWriterSealedDead(session: ChatSession): Boolean {
        return try {
            if (!session.isTranscriptSealed()) return false
            if (session.hasPendingAdmission()) return false
            if (session.pendingTranscriptRunIds().isNotEmpty()) return false
            if (session.hasUnsettledAdmission()) return false
            if (session.hasUnsettledPromotion()) return false
            if (session.hasActiveHost()) return false
            if (session.hasUnsettledCancelReceipts()) return false
            session.isTranscriptWriterCompleted()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * R9-3 promotion probe: true only when the ledger's backfill window
     * holds no dangling family — the terminal already landed, so a gated
     * backfill would no-op and the block may lift. Fail-closed (null store
     * or any read failure keeps the block until its deadline). Scans the
     * same [BACKFILL_SCAN_LIMIT] window the factory backfill marks, so the
     * two can never disagree. Cancellation propagates; call from IO (see
     * [gateBackfillForSession]).
     */
    private suspend fun probeLedgerHasNoDangling(sessionId: String): Boolean {
        val backing = sessions.storeOrNull() ?: return false
        return findDanglingRunIds(backing.loadTailEvents(sessionId, BACKFILL_SCAN_LIMIT)).isEmpty()
    }

    /**
     * R6-3 (a) cancel re-park for the endpoint-switch fork fence. Runs on
     * Main under [sessionMutex] (same confinement as [pendingLedgerFence]):
     * the caller consumed the parked fence and detached the mismatched
     * session before its IO section was lifecycle-cancelled, so neither
     * settle completed — park them again for the next fork instead of losing
     * the join evidence and the streaming flag.
     *
     * The just-detached mismatched session has no background teardown yet
     * (its inline settle was interrupted), so it gets one here via
     * [launchCloseThenFlush] on [viewModelScope] (survives the cancelled
     * generation); the prior parked job ([fencedJob], already on
     * [viewModelScope]) keeps running untouched, and the combined fence joins
     * both. Only the mismatched session is re-parked for the next fork's
     * bounded re-check: it owns the previous ledger (the one the next fork
     * snapshots); the older fenced session keeps draining via its own
     * background job, unfenced. `wasStreaming` is OR'd so the next fork still
     * degrades instead of copying a possibly-dangling family.
     *
     * R8-2 owner carry: [ownerSessionId] (the consumed slot owner, falling
     * back to the fork's snapshot target) is re-filled so the next fork keeps
     * the ledger lineage across consecutive transport replacements. The slot
     * must normally be empty here (a concurrent non-null switch with no live
     * session early-returns in [releaseLiveAfterBoundedFlush] instead of
     * parking), but a newer park is never dropped if one appears: same-owner
     * evidence is join-merged (the combined fence awaits every pending job,
     * so an unresolved same-ledger teardown is never lost); a different-owner
     * leftover only background-settles unfenced while the newest owner wins
     * the slot, and `wasStreaming` is always OR'd.
     */
    private fun reparkUnsettledLedger(
        fencedJob: Job?,
        fencedSession: ChatSession?,
        fencedWasStreaming: Boolean,
        ownerSessionId: String?,
        mismatched: ChatSession?,
        mismatchedWasStreaming: Boolean,
    ) {
        val wasStreaming = fencedWasStreaming || mismatchedWasStreaming
        if (pendingLedgerFence == null && pendingLedgerSession == null) {
            val settleJob = mismatched?.let { launchCloseThenFlush(it) }
            pendingLedgerFence = when {
                fencedJob != null && settleJob != null -> compositeLedgerJoin(fencedJob, settleJob)
                fencedJob != null -> fencedJob
                else -> settleJob
            }
            pendingLedgerSession = mismatched ?: fencedSession
            pendingLedgerWasStreaming = wasStreaming
            pendingLedgerOwnerSessionId = ownerSessionId
        } else {
            // Same-owner unresolved teardown must not be dropped: merge the
            // join evidence so the next fork still awaits it. Different-owner
            // leftovers keep draining unfenced; the slot keeps the newest
            // owner while wasStreaming is always OR'd.
            val existingFence = pendingLedgerFence
            val settleJob = mismatched?.let { launchCloseThenFlush(it) }
            val incomingFence = when {
                fencedJob != null && settleJob != null -> compositeLedgerJoin(fencedJob, settleJob)
                fencedJob != null -> fencedJob
                else -> settleJob
            }
            val sameOwner = ownerSessionId != null &&
                pendingLedgerOwnerSessionId != null &&
                pendingLedgerOwnerSessionId == ownerSessionId
            pendingLedgerFence = if (sameOwner && existingFence != null && incomingFence != null) {
                // Same ledger still unsettled: one composite join awaits both
                // so the next fork fences the full teardown.
                compositeLedgerJoin(existingFence, incomingFence)
            } else {
                // Different owner (or nothing new to fence): the newest ledger
                // wins the fence; the displaced job keeps running on
                // viewModelScope to durability, just unfenced.
                incomingFence ?: pendingLedgerFence
            }
            if (mismatched != null || fencedSession != null) {
                pendingLedgerSession = mismatched ?: fencedSession ?: pendingLedgerSession
            }
            pendingLedgerWasStreaming = pendingLedgerWasStreaming || wasStreaming
            if (ownerSessionId != null) pendingLedgerOwnerSessionId = ownerSessionId
        }
    }

    /**
     * R9-2 same-ID reopen abort re-park (idempotent, Main, never takes
     * [sessionMutex] — callers inside `withLock` already hold it). The eager
     * [sameIdTeardown] (viewModelScope close-then-flush, double-close safe)
     * already runs; this only ensures the slot still owns it for the next
     * open's fence. Discard-scoped (NOT generation-scoped): only parks when
     * [entryDiscardEpoch] still matches [explicitDiscardEpoch] — a newer
     * explicit discard (newChat / openSession reset / binding == null)
     * abandons the ledger (teardown keeps running unfenced for durability).
     * Owner is the snapshot target ([ownerSessionId] == prevId for a live
     * same-ID reopen, == retained fence owner for a from-null owner-match)
     * because `_currentSessionId` is already null after the pre-clear. The
     * prior fence ([fencedJob]) merges into one composite join so an
     * unresolved same-ledger teardown is never lost; only [prevLive] is kept
     * for the next fence's bounded re-check (it owns the snapshot-target
     * ledger — the older fenced session keeps draining via its own job,
     * unfenced, mirroring [reparkUnsettledLedger]). For a from-null
     * owner-match [prevLive] is null and the retained [fencedSession] is the
     * snapshot target instead. `wasStreaming` is always OR'd.
     * Unlike [reparkUnsettledLedger] this never launches a second teardown
     * for [prevLive] — exactly one [launchCloseThenFlush] per same-ID reopen
     * (tests assert teardownJobs==1, not closeCalls==1, since one job
     * double-closes).
     */
    private fun reparkSameIdTeardownOnAbort(
        sameIdTeardown: Job?,
        prevLive: ChatSession?,
        fencedJob: Job?,
        fencedSession: ChatSession?,
        combinedWasStreaming: Boolean,
        ownerSessionId: String?,
        entryDiscardEpoch: Long,
    ) {
        if (sameIdTeardown == null && fencedJob == null && prevLive == null && fencedSession == null) return
        if (ownerSessionId == null) return
        if (explicitDiscardEpoch != entryDiscardEpoch) return
        // Sync-area park already owns this ledger: keep it (OR streaming,
        // fill a missing fence). Covers every slow-path abort (auth
        // Denied/Missing/Stale, fast-preload gate at 1008, auth gates at
        // 1015-1025, access-generation gate at 1029, mutex gates at
        // 1032-1037) and slow-IO cancel where the settle never completed.
        // From-null owner-match parks the retained fenced session, so the
        // slot matches either prevLive (live same-ID) or fencedSession
        // (retained fence).
        if ((pendingLedgerSession === prevLive ||
                (fencedSession != null && pendingLedgerSession === fencedSession)
            ) && pendingLedgerOwnerSessionId == ownerSessionId
        ) {
            pendingLedgerWasStreaming = pendingLedgerWasStreaming || combinedWasStreaming
            if (pendingLedgerFence == null) {
                pendingLedgerFence = when {
                    fencedJob != null && sameIdTeardown != null ->
                        compositeLedgerJoin(fencedJob, sameIdTeardown)
                    fencedJob != null -> fencedJob
                    else -> sameIdTeardown
                }
            }
            return
        }
        if (pendingLedgerFence == null && pendingLedgerSession == null) {
            val merged = when {
                fencedJob != null && sameIdTeardown != null ->
                    compositeLedgerJoin(fencedJob, sameIdTeardown)
                fencedJob != null -> fencedJob
                else -> sameIdTeardown
            }
            pendingLedgerFence = merged
            pendingLedgerSession = prevLive ?: fencedSession
            pendingLedgerWasStreaming = combinedWasStreaming
            pendingLedgerOwnerSessionId = ownerSessionId
        } else {
            // Occupied by a newer park (concurrent op after our sync park):
            // same-owner merges join evidence, different-owner preserves the
            // newest slot (our job keeps draining unfenced) while OR-ing
            // streaming evidence conservatively.
            val incomingFence: Job? = when {
                fencedJob != null && sameIdTeardown != null ->
                    compositeLedgerJoin(fencedJob, sameIdTeardown)
                fencedJob != null -> fencedJob
                else -> sameIdTeardown
            }
            val sameOwner = pendingLedgerOwnerSessionId != null &&
                pendingLedgerOwnerSessionId == ownerSessionId
            val existingFence = pendingLedgerFence
            if (sameOwner && existingFence != null && incomingFence != null) {
                pendingLedgerFence = compositeLedgerJoin(existingFence, incomingFence)
                if (prevLive != null) pendingLedgerSession = prevLive
            }
            pendingLedgerWasStreaming = pendingLedgerWasStreaming || combinedWasStreaming
            if (sameOwner && ownerSessionId != null) pendingLedgerOwnerSessionId = ownerSessionId
        }
    }

    /**
     * R6-3 streaming evidence for the incomplete fallback: true while the old
     * session may still be missing its terminal record — live STREAMING /
     * CANCELLED / WAITING_STEERED projection, a pending UNKNOWN admission, a
     * direct admission still in its durable/finalize window or orphan drain
     * (R21-1 Phase 1 [ChatSession.hasUnsettledAdmission]: user row durable
     * but no terminal yet), a promotion reservation/ghost (R22 Phase 1
     * [ChatSession.hasUnsettledPromotion]: named subset of the admission
     * proof, conjoined explicitly exactly like [TurnController.flushDurable],
     * never via the admission branch alone), a live hosted turn (R22 Phase 1
     * [ChatSession.hasActiveHost]: a running host owns a user row with no
     * proven terminal yet), sticky close debt (R22 Phase 1
     * [ChatSession.hasClosedUnsettledAdmission]: close-dropped terminal
     * obligations stay evidence until proven), an unsettled cancel receipt
     * (R19-1 PENDING/CLAIMED: the deferred system/batch write is still owed),
     * a SEALED-but-uncompensated receipt (Phase 1 truth contract: SEALED
     * without proven family-final still dangles), or admitted-but-unacked
     * core runIds.
     * S3-F2: the immediate cancel terminal itself is bounded
     * (TurnController cancelCompensationTimeoutMs): a wedged writer no longer
     * parks the cancelled host — timeout converges via the PENDING receipt
     * (deferred notice-first) or markInterrupted, so this proof keeps
     * returning true (CANCELLED status + unsettled/sealed receipts + pending
     * runIds) until reopen INTERRUPTED backfill lands, never flipping clean
     * on a dangling family.
     * Current (S3-F2): bounded immediate + orphan drain share the 5s
     * production bound (tests inject 1s). Target: single shared ledger
     * budget.
     * Read-failure contract (split, never throws except CancellationException
     * rethrow): the best-effort probes below (uiState projection, sticky
     * close debt, cancel receipts, pending runIds) treat a failed read as
     * "no evidence", never "incomplete" — fail-open toward clean by contract
     * (a wedged read must not pin every fork to incomplete; the
     * fence-failure path via settleOk/durableOk still degrades
     * independently). The three non-sticky obligation probes (admission /
     * promotion / active-host) instead fail closed (see below): a wedged
     * obligation read must never report clean. Per-session ownership: only
     * the passed (source) session's receipts are consulted, so a fresh
     * session never inherits an old session's evidence. Cancellation is
     * never swallowed: every catch below rethrows CancellationException
     * before applying its branch contract.
     */
    private fun isLedgerStreamingOrPending(session: ChatSession?): Boolean {
        if (session == null) return false
        try {
            when (session.uiState.value.status) {
                ChatStatus.STREAMING, ChatStatus.CANCELLED, ChatStatus.WAITING_STEERED -> return true
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        // Stage-3 F-E fail-closed: the three non-sticky obligation probes
        // below (admission / promotion / active-host) return true on read
        // failure (a wedged read must never report clean). Other probes keep
        // their best-effort skip (sticky debt is fail-closed elsewhere via
        // flushDurable conjunction; uiState/pending reads stay no-evidence).
        // Failures are logged once for observability (best-effort Log).
        try {
            if (session.hasPendingAdmission()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        try {
            if (session.hasUnsettledAdmission()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching {
                android.util.Log.w(
                    "ChatViewModel",
                    "isLedgerStreamingOrPending: hasUnsettledAdmission read failed, fail-closed true",
                )
            }
            return true
        }
        try {
            if (session.hasUnsettledPromotion()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching {
                android.util.Log.w(
                    "ChatViewModel",
                    "isLedgerStreamingOrPending: hasUnsettledPromotion read failed, fail-closed true",
                )
            }
            return true
        }
        try {
            if (session.hasActiveHost()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching {
                android.util.Log.w(
                    "ChatViewModel",
                    "isLedgerStreamingOrPending: hasActiveHost read failed, fail-closed true",
                )
            }
            return true
        }
        try {
            if (session.hasClosedUnsettledAdmission()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        try {
            if (session.hasUnsettledCancelReceipts()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        try {
            if (session.hasSealedUncompensatedReceipts()) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        return try {
            session.pendingTranscriptRunIds().isNotEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Detach + bounded close-then-flush for explicit lifecycle resets
     * (newChat / openSession pre-clear) and endpoint switches. Thin wrapper
     * over [detachAndFlushClose]: the live turn is cancelled synchronously
     * and VM state is detached synchronously so the UI updates immediately,
     * while the detached session closes (settle) before its background
     * durable flush (R6-3 order). The teardown job + streaming evidence are
     * parked in [pendingLedgerFence]/[pendingLedgerSession] so the next
     * `ensureSession` fork fences on them before reading the old ledger.
     * Callers cancel lifecycle operations first
     * ([cancelLifecycleOperations] before this call); that order is load
     * bearing — the teardown launch below lives on [viewModelScope], not the
     * just-invalidated lifecycle scope, so it survives the cancel above.
     */
    private fun releaseLiveAfterBoundedFlush() {
        val ownerAtPark = _currentSessionId.value
        val detached = currentSession ?: return
        val wasStreaming = isLedgerStreamingOrPending(detached)
        val fence = detachAndFlushClose(detached)
        // R8-2: park for the next fork's IO fence with the ledger lineage
        // (the snapshot target at detach time). A non-null endpoint switch
        // hands the fence over — it never clears this slot — while only an
        // explicit history discard (clearChat / openSession attach /
        // binding == null) drops it. Same-owner re-parks merge into one
        // composite join so an unresolved same-ledger teardown is never lost;
        // a different-owner park supersedes (the next fork snapshots only the
        // latest previousSessionId's ledger, so the older parked ledger is
        // irrelevant to it). The displaced job is NOT cancelled: it keeps
        // running on viewModelScope to durability (close seals under
        // NonCancellable, flush is bounded), just unfenced — the next fork
        // only awaits the latest ledger.
        if (pendingLedgerFence == null && pendingLedgerSession == null) {
            pendingLedgerFence = fence
            pendingLedgerSession = detached
            pendingLedgerWasStreaming = wasStreaming
            pendingLedgerOwnerSessionId = ownerAtPark
        } else if (pendingLedgerOwnerSessionId != null && pendingLedgerOwnerSessionId == ownerAtPark) {
            val prior = pendingLedgerFence
            // Same ledger parked twice (e.g. re-park racing a late detach):
            // join both teardowns under one fence instead of dropping either.
            val incoming = fence
            pendingLedgerFence = if (prior != null) {
                compositeLedgerJoin(prior, incoming)
            } else {
                incoming
            }
            pendingLedgerSession = detached
            pendingLedgerWasStreaming = pendingLedgerWasStreaming || wasStreaming
        } else {
            pendingLedgerFence = fence
            pendingLedgerSession = detached
            pendingLedgerWasStreaming = wasStreaming
            pendingLedgerOwnerSessionId = ownerAtPark
        }
    }

    private suspend fun loadHistory(sessionId: String): LoadedUiHistory? {
        val backing: SessionStore = sessions.storeOrNull() ?: return LoadedUiHistory(emptyList(), 0)
        if (backing.getSession(sessionId) == null) return null
        // Single-pass streaming replay over the newest tail: backward paging
        // keeps memory O(HISTORY_CAP), never O(session) (sparse seqs safe via
        // `seq <` predicates, never offsets), then restored to oldest-first.
        // out text stays O(HISTORY_CAP); runIdToIndex stays O(CAP) with one
        // index per emitted assistant; pendingMarkers holds only orphan runId
        // short strings, worst case O(HISTORY_SCAN_CAP) entries, far smaller
        // than full events. The raw scan stays bounded by HISTORY_SCAN_CAP.
        // Marker events never render; an assistant event whose runId has a marker
        // restores hasReasoning so resumed reasoning-origin turns still fail
        // closed on tool-dependent / thinking-mode follow-ups (ordinary
        // no-tools follow-ups continue). This replay is display UiMessages;
        // the provider-visible flag rides loadModelHistory into TurnController.
        // S4-F5: when the tail cap omits older rows (dropped>0) the marker scan
        // may have missed an older reasoning marker whose assistant is still
        // kept – every kept assistant is then conservatively resumed with
        // hasReasoning=true, matching Factory loadModelHistory (dropped>0 all
        // true) so UI and model-context agree on truncation (fail-closed).
        // Known gap (follow-up): exact-cap far-marker beyond one extra page
        // (R25-1) is over-flagged only on the model-context path; UI replay
        // stays display-only there and may disagree. Enforcement always uses
        // the model-context flag.
        // Backward scan sees each marker before its
        // assistant (markers append right after), so the pending path is the
        // live one; the patch path stays for reorder tolerance. Cancelled /
        // failed fragments replay with their persisted partial flag (never as
        // completed answers); system markers stay hidden from the chat replay.
        // The message cap counts user/assistant messages only, while the scan
        // bound counts raw scanned events.
        val out = ArrayList<UiMessage>(HISTORY_CAP)
        // Only already-emitted assistants, so a late marker can patch its flag.
        val runIdToIndex = HashMap<String, Int>()
        // Markers seen before their assistant (reorder / cross-page tolerance;
        // in backward order this is the common case: marker seq > assistant seq).
        val pendingMarkers = HashSet<String>()
        var scanned = 0
        var beforeSeq = Long.MAX_VALUE
        outer@ while (out.size < HISTORY_CAP && scanned < HISTORY_SCAN_CAP) {
            val page = backing.loadEventsBefore(sessionId, beforeSeq, HISTORY_PAGE)
            if (page.isEmpty()) break
            for (event in page) {
                if (scanned >= HISTORY_SCAN_CAP) break@outer
                scanned++
                when (event.kind) {
                    // Phase 3: restore the persisted partial flag (cancelled /
                    // failed fragments replay flagged, never as completed
                    // answers).
                    "user" -> if (out.size < HISTORY_CAP) {
                        out.add(UiMessage(id = "hist-${event.seq}", role = event.kind, text = event.text, isPartial = event.isPartial))
                    }
                    "assistant" -> if (out.size < HISTORY_CAP) {
                        val flagged = pendingMarkers.remove(event.runId)
                        out.add(
                            UiMessage(
                                id = "hist-${event.seq}",
                                role = event.kind,
                                text = event.text,
                                isPartial = event.isPartial,
                                hasReasoning = flagged,
                            ),
                        )
                        runIdToIndex.putIfAbsent(event.runId, out.size - 1)
                    }
                    "reasoning" -> {
                        val idx = runIdToIndex[event.runId]
                        if (idx != null) {
                            val cur = out[idx]
                            if (!cur.hasReasoning) out[idx] = cur.copy(hasReasoning = true)
                        } else if (out.size < HISTORY_CAP) {
                            // out still has room, so a later (older) assistant
                            // may consume this marker. When out is full no
                            // further assistant appends happen, so buffering
                            // would leave a dead entry.
                            pendingMarkers.add(event.runId)
                        }
                    }
                }
            }
            val pageMin = page.minOf { it.seq }
            if (pageMin >= beforeSeq) break
            beforeSeq = pageMin
            if (page.size < HISTORY_PAGE) break
        }
        // Exact omitted-row total via one filtered COUNT (O(1) memory).
        val total = backing.countHistoryEvents(sessionId, includePartial = true)
        val dropped = (total - out.size).coerceAtLeast(0)
        // S4-F5 + S4-R2: dropped>0 => conservative all-assistants reasoning
        // flag. The latest prune sweep may have deleted rows with dropped==0
        // (R26-1: cut head family deleted whole) – consult isPruneSuspect
        // best-effort (per-sweep, not sticky; fail-closed: read failure =>
        // suspect true), matching Factory loadModelHistory on the dropped>0 /
        // pruneSuspect paths.
        // Stage-3 honesty (R26-2/F4): the two paths agree on dropped>0 and
        // pruneSuspect, but may disagree on the exact-cap far-marker (R25-1):
        // the model path probes hasReasoningBefore(extraFloor) boundedly and
        // over-flags all kept assistants, while this UI replay stays
        // display-only there (no extra probe; enforcement always uses the
        // model-context flag). COUNT basis also differs by design: the model
        // path counts includePartial=false (provider payload), this replay
        // counts includePartial=true (visible messages).
        val pruneSuspect = try {
            backing.isPruneSuspect(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        if (dropped > 0 || pruneSuspect) {
            for (i in out.indices) {
                val m = out[i]
                if (m.role == "assistant" && !m.hasReasoning) {
                    out[i] = m.copy(hasReasoning = true)
                }
            }
        }
        return LoadedUiHistory(out.asReversed(), dropped)
    }

    override fun onCleared() {
        lifecycleGeneration += 1
        retryInFlight = null
        lifecycleJob.cancel()
        openJob?.cancel()
        // R10-1: drop per-session outstanding teardowns (bounded; viewModelScope
        // teardown cancels their jobs anyway).
        outstandingTeardowns.clear()
        // R10-2: drop pending-writer identity alongside the block it explains.
        pendingBackfillWriter.clear()
        // Synchronous close only: background flush-then-close is infeasible
        // here (viewModelScope is cancelled on teardown), so closeLive()
        // relies on TurnController.close's internal bounded drain.
        closeLive()
        super.onCleared()
    }

    private data class AuthorizedEndpoint(
        val config: EndpointConfig,
        val binding: EndpointSessionBinding,
        val generation: Long,
        // S1 T0 proof: true only when T0 observed ASK plus a bound,
        // generation-matched one-shot consent. Threaded into the factory
        // T1 recheck; every other T0 outcome passes false.
        val allowAsk: Boolean,
    )

    private data class SessionHandle(
        val created: CreatedSession,
        val binding: EndpointSessionBinding,
        val generation: Long,
    )

    private sealed class EndpointAccess {
        data class Ready(val endpoint: AuthorizedEndpoint) : EndpointAccess()
        object Denied : EndpointAccess()
        /** key.read ASK without a valid bound one-shot confirmation. */
        data class ConfirmRequired(
            val binding: EndpointSessionBinding,
            val generation: Long,
        ) : EndpointAccess()
        object Missing : EndpointAccess()
        object Stale : EndpointAccess()
    }
}
