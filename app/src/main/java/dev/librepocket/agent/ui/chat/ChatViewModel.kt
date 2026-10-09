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
import dev.librepocket.chat.TurnStart
import dev.librepocket.chat.UiMessage
import dev.librepocket.policy.PolicyStore
import dev.librepocket.provider.ProviderProtocol
import dev.librepocket.policy.Verdict
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
 * Per-phase budget for the bounded pre-close ledger drain (Phase 2
 * conversation-ledger wiring): newChat / openSession / endpoint-switch /
 * transport-replace flush the detached live session before closing it, so
 * admitted transcript events are not lost with the session.
 * [ChatSession.flush] spends one such budget on the close shutdown-join and
 * another on the admitted-event drain, so one flush-then-close waits up to
 * ~2x this value after close (~1x when nothing was closed yet). The UI never
 * waits on it: VM state is detached synchronously and the flush itself is
 * bounded — a stalled store only delays the background close, never user
 * interaction. Mirrors
 * [dev.librepocket.chat.OrderedTranscriptSink.DEFAULT_FLUSH_TIMEOUT_MS].
 */
private const val SESSION_FLUSH_TIMEOUT_MS = 5_000L

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
        invalidateLifecycle(clearChat = true)
        val requestedGeneration = lifecycleGeneration
        openJob = lifecycleScope.launch {
            var candidate: CreatedSession? = null
            var attached = false
            var cancellation: CancellationException? = null
            try {
                val loaded = try {
                    withContext(Dispatchers.IO) { loadHistory(sessionId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (!isGenerationCurrent(requestedGeneration)) return@launch
                if (loaded == null) {
                    _notice.value = "UNKNOWN_SESSION"
                    return@launch
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
                    val latest = readEndpoint()
                    if (!isGenerationCurrent(requestedGeneration)) return@withLock
                    if (bindingOf(latest) != access.binding || latest == null) {
                        reconcileReadBinding(bindingOf(latest))
                        return@withLock
                    }

                    val creationGeneration = requestedGeneration
                    try {
                        withContext(Dispatchers.IO) {
                            candidate = sessions.open(latest, sessionId, allowAsk = access.allowAsk) {
                                isBindingCurrent(access.binding, creationGeneration)
                            }
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

                    _history.value = loaded
                    attach(created, access.binding, creationGeneration)
                    attached = true
                    candidate = null
                    _notice.value = null
                }
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                throw cancelled
            } catch (_: Exception) {
                setNoticeIfCurrent(requestedGeneration, "NO_ENDPOINT")
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
                            candidate = sessions.open(latest, sessionId, allowAsk = access.allowAsk) {
                                isBindingCurrent(access.binding, creationGeneration)
                            }
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
        if (currentSession != null) {
            val mismatched = currentSession
            val queuedBeforeReplace: List<dev.librepocket.chat.QueuedIntent> = try {
                mismatched?.drainQueued() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            lifecycleGeneration += 1
            generation = lifecycleGeneration
            // F2: keep visible transcript when the transport is replaced.
            snapshotLiveToHistory()
            // Bounded flush-then-close in the background (never a bare
            // closeLive: admitted events must drain, as on every other
            // detach path).
            if (mismatched != null) detachAndFlushClose(mismatched)
            _sessionState.value = EMPTY_SESSION_STATE
            absorbDrainedQueued(queuedBeforeReplace, fillInput = false, includePending = false)
        }

        val previousSessionId = _currentSessionId.value
        if (previousSessionId != null) {
            val replay = withContext(Dispatchers.IO) { loadHistory(previousSessionId) }
            if (!isGenerationCurrent(generation)) return@withLock null
            val afterHistory = readEndpoint()
            if (!isGenerationCurrent(generation)) return@withLock null
            if (afterHistory == null || bindingOf(afterHistory) != authorized.binding) {
                reconcileReadBinding(bindingOf(afterHistory))
                return@withLock null
            }
            if (replay != null) _history.value = replay
        }

        val creationGeneration = generation
        var candidate: CreatedSession? = null
        var attached = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                candidate = sessions.create(endpoint, title, allowAsk = authorized.allowAsk) {
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
        if (binding == null) {
            _history.value = emptyList()
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
            _history.value = emptyList()
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
        try {
            currentSession?.close()
        } catch (_: Exception) {
        }
        currentSession = null
        currentBinding = null
        currentSessionGeneration = null
    }

    /**
     * Detach + bounded flush-then-close for explicit lifecycle resets
     * (newChat / openSession pre-clear), endpoint switches, transport
     * replacement, and attach-time incumbent replacement. The live turn is
     * cancelled synchronously (prompt stop, IO-free, a no-op when idle) and
     * VM state is detached synchronously so the UI updates immediately; the
     * detached session is then flushed and closed on [viewModelScope] —
     * never the per-generation lifecycle scope, so a later invalidation
     * cannot cancel another generation's teardown — strictly in that order.
     * No outer timeout wraps the flush: [ChatSession.flush] is already
     * bounded (one [SESSION_FLUSH_TIMEOUT_MS] budget for the close
     * shutdown-join plus one for the admitted-event drain, ~2x total after
     * close). [close] runs under [NonCancellable] so teardown always seals
     * the session even if the background launch is cancelled. A stalled
     * store only delays that background close — the next generation is
     * unaffected because all VM references were already detached above.
     */
    private fun detachAndFlushClose(session: ChatSession) {
        // Prompt stop first: cancel() is synchronous, IO-free, and a no-op
        // when idle, so a running turn never lingers into the flush window.
        try {
            session.cancel()
        } catch (_: Exception) {
        }
        if (currentSession === session) {
            sessionCollectJob?.cancel()
            sessionCollectJob = null
            currentSession = null
            currentBinding = null
            currentSessionGeneration = null
        }
        viewModelScope.launch {
            try {
                session.flush(SESSION_FLUSH_TIMEOUT_MS)
            } catch (_: Exception) {
                // Bounded: timeout or flush failure still falls through to
                // close below. A scope-teardown CancellationException is
                // absorbed here on purpose: close() below is the teardown
                // that matters and still runs under NonCancellable.
            }
            try {
                withContext(NonCancellable) {
                    session.close()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Detach + bounded flush-then-close for explicit lifecycle resets
     * (newChat / openSession pre-clear) and endpoint switches. Thin wrapper
     * over [detachAndFlushClose]: the live turn is cancelled synchronously
     * and VM state is detached synchronously so the UI updates immediately,
     * while the detached session flushes (bounded) before its background
     * close. Callers cancel lifecycle operations first
     * ([cancelLifecycleOperations] before this call); that order is load
     * bearing — the teardown launch below lives on [viewModelScope], not the
     * just-invalidated lifecycle scope, so it survives the cancel above.
     */
    private fun releaseLiveAfterBoundedFlush() {
        val detached = currentSession ?: return
        detachAndFlushClose(detached)
    }

    private suspend fun loadHistory(sessionId: String): List<UiMessage>? {
        val backing: SessionStore = sessions.storeOrNull() ?: return emptyList()
        if (backing.getSession(sessionId) == null) return null
        // Single-pass streaming replay: pages are consumed as they arrive. out
        // text stays O(HISTORY_CAP); runIdToIndex stays O(CAP) with one index
        // per emitted assistant; pendingMarkers holds only orphan runId short
        // strings, worst case O(HISTORY_SCAN_CAP) entries, far smaller than full events.
        // Marker events never render; an assistant event whose runId has a marker
        // restores hasReasoning so resumed reasoning-origin turns still fail
        // closed on tool-dependent / thinking-mode follow-ups (ordinary
        // no-tools follow-ups continue). Resume history here is display-only
        // (rebuilt UiMessages, never fed into provider requests), so the flag
        // is defense-in-depth only. The message cap still counts user/assistant
        // messages only, while the scan bound counts raw scanned events.
        val out = ArrayList<UiMessage>()
        // Only already-emitted assistants, so a late marker can patch its flag.
        val runIdToIndex = HashMap<String, Int>()
        // Markers seen before their assistant (reorder / cross-page tolerance).
        val pendingMarkers = HashSet<String>()
        var scanned = 0
        var afterSeq = 0L
        outer@ while (scanned < HISTORY_SCAN_CAP) {
            val page = backing.loadEvents(sessionId, afterSeq, HISTORY_PAGE)
            if (page.isEmpty()) break
            for (event in page) {
                if (scanned >= HISTORY_SCAN_CAP) break@outer
                scanned++
                when (event.kind) {
                    "user" -> if (out.size < HISTORY_CAP) {
                        out.add(UiMessage(id = "hist-${event.seq}", role = event.kind, text = event.text, isPartial = false))
                    }
                    "assistant" -> if (out.size < HISTORY_CAP) {
                        val flagged = pendingMarkers.remove(event.runId)
                        out.add(
                            UiMessage(
                                id = "hist-${event.seq}",
                                role = event.kind,
                                text = event.text,
                                isPartial = false,
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
                            // out still has room, so a later assistant may consume
                            // this marker. When out is full no further assistant
                            // appends happen, so buffering would leave a dead entry.
                            pendingMarkers.add(event.runId)
                        }
                    }
                }
            }
            afterSeq = page.last().seq
            if (page.size < HISTORY_PAGE) break
            // When out is full no further appends happen, but scanning continues
            // to SCAN_CAP/EOF so late markers still patch already-emitted flags.
        }
        return out
    }

    override fun onCleared() {
        lifecycleGeneration += 1
        retryInFlight = null
        lifecycleJob.cancel()
        openJob?.cancel()
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
