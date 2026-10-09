package dev.librepocket.agent.ui.chat

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
import dev.librepocket.policy.Verdict
import dev.librepocket.session.SessionStore
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

/**
 * Chat UI state holder wired to the real [ChatSession] plus the transcript store.
 *
 * Exposes replayed history ([messages] = stored history + live session messages),
 * the current transcript session id, and send/cancel/retry/new/open operations.
 * History kinds other than user/assistant (tool/steer/retry/system) are kept in
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
        get() = _sessionState.value.status == ChatStatus.ERROR &&
            (retryableOp != null || retryFallbackTarget != null)

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
                        val handle = ensureSession(access.endpoint, clean)
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
                                    recordAcceptedRetryTarget(opId, clean)
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
        if (_sessionState.value.status != ChatStatus.ERROR) return
        val fallbackTarget = retryFallbackTarget
        // A queued admission may become recoverable after a later promotion
        // denial. If the preferred retry target was explicitly discarded,
        // adopt the fallback only when that exact operation still exists in
        // the outbox; never infer ownership from matching text.
        val op = retryableOp ?: fallbackTarget?.let { target ->
            recoverableOps.firstOrNull { it.opId == target.opId }
        }
        val retryText = op?.text ?: fallbackTarget?.text ?: return
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
                        val handle = ensureSession(access.endpoint, text)
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
                                    recordAcceptedRetryTarget(opId, text)
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
                    withContext(Dispatchers.IO) {
                        candidate = sessions.open(latest, sessionId) {
                            isBindingCurrent(access.binding, creationGeneration)
                        }
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
            return EndpointAccess.Denied
        }
        if (!isGenerationCurrent(authorizedGeneration)) return EndpointAccess.Stale
        // Preserve existing ASK semantics; only an explicit DENY blocks. No
        // synthetic ALLOW is introduced here.
        if (decision.verdict == Verdict.DENY) return EndpointAccess.Denied

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
        return EndpointAccess.Ready(AuthorizedEndpoint(latest, binding, authorizedGeneration))
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
            val queuedBeforeReplace: List<dev.librepocket.chat.QueuedIntent> = try {
                currentSession?.drainQueued() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            lifecycleGeneration += 1
            generation = lifecycleGeneration
            // F2: keep visible transcript when the transport is replaced.
            snapshotLiveToHistory()
            closeLive()
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
                candidate = sessions.create(endpoint, title) {
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
        // Close the replaced incumbent (credential wipe + hosted-turn cancel)
        // instead of orphaning it past cancel()/newChat()/onCleared() reach.
        // Its transcript row is retained: it already received user input.
        val incumbent = currentSession
        if (incumbent != null && incumbent !== created.session) {
            try {
                incumbent.close()
            } catch (_: Exception) {
            }
        }
        currentSession = created.session
        currentBinding = binding
        currentSessionGeneration = generation
        _currentSessionId.value = created.sessionId
        sessionCollectJob?.cancel()
        sessionCollectJob = viewModelScope.launch {
            created.session.uiState.collect { state ->
                if (isGenerationCurrent(generation) && currentSession === created.session) {
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
     * key.read is ASK-by-default (consented at setup); only a DENY rule blocks
     * here. The endpoint revision/origin is revalidated immediately before a
     * provider receives its session-frozen credential snapshot.
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
        observedBinding = binding
        // N1: reclaim queued-but-unstarted intents BEFORE close() drops the
        // FIFO. Queued is not acceptance, so the caller still owns the text:
        // it joins the recoverable outbox below (never auto-resent).
        // Reclaim and closeLive() run back-to-back with no suspension on
        // this Main-confined path, so no admission slips between them; the
        // generation bump below only fences generations.
        val queuedFromSession: List<dev.librepocket.chat.QueuedIntent> = try {
            currentSession?.drainQueued() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        lifecycleGeneration += 1
        if (binding == null) explicitDiscardEpoch += 1
        else endpointReplacementEpoch += 1
        retryFallbackTarget = null
        retryTargetCandidate = null
        latestAcceptedStartedTarget = null
        cancelLifecycleOperations()
        openJob?.cancel()
        openJob = null
        // F2: decouple transport invalidation from transcript display.
        if (binding != null) snapshotLiveToHistory()
        closeLive()
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
            val hadUnaccepted = pendingOps.isNotEmpty() || queuedFromSession.isNotEmpty()
            if (!hadUnaccepted) {
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
        lifecycleGeneration += 1
        if (clearChat) explicitDiscardEpoch += 1
        cancelLifecycleOperations()
        openJob?.cancel()
        openJob = null
        closeLive()
        _sessionState.value = EMPTY_SESSION_STATE
        _notice.value = null
        if (clearChat) {
            _history.value = emptyList()
            _currentSessionId.value = null
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

    // F2: retain visible user/assistant messages before dropping live state,
    // so a model/revision change never blanks the screen until the next send
    // replays. Id-deduped; logout/newChat clearing is preserved elsewhere.
    // Timing note: the VM projection (_sessionState) trails the controller by
    // one collector dispatch. A text already accepted (appended) but not yet
    // projected must still survive: also read the live session truth
    // (synchronous StateFlow value) before closeLive() nulls it. Accepted
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
    private fun recordAcceptedRetryTarget(opId: Long, text: String) {
        // This method is called only for controller-Queued admission. Even if
        // another concurrent op superseded the shared retry candidate, queue
        // admission still replaces this op's former recovery position.
        pendingOps[opId]?.takeIf { it.text == text }?.let {
            pendingOps[opId] = it.copy(recoverySequence = null)
        }
        val candidate = retryTargetCandidate
        if (candidate?.opId == opId && candidate.text == text) {
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
        recordAcceptedRetryTarget(opId, text)
        val started = pendingOps[opId]?.takeIf { it.text == text } ?: return
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

    private suspend fun loadHistory(sessionId: String): List<UiMessage>? {
        val backing: SessionStore = sessions.storeOrNull() ?: return emptyList()
        if (backing.getSession(sessionId) == null) return null
        val out = ArrayList<UiMessage>()
        var afterSeq = 0L
        while (out.size < HISTORY_CAP) {
            val page = backing.loadEvents(sessionId, afterSeq, HISTORY_PAGE)
            if (page.isEmpty()) break
            for (event in page) {
                if (event.kind == "user" || event.kind == "assistant") {
                    out.add(UiMessage(id = "hist-${event.seq}", role = event.kind, text = event.text, isPartial = false))
                }
                afterSeq = event.seq
            }
            if (page.size < HISTORY_PAGE) break
        }
        return out
    }

    override fun onCleared() {
        lifecycleGeneration += 1
        retryInFlight = null
        lifecycleJob.cancel()
        openJob?.cancel()
        closeLive()
        super.onCleared()
    }

    private data class AuthorizedEndpoint(
        val config: EndpointConfig,
        val binding: EndpointSessionBinding,
        val generation: Long,
    )

    private data class SessionHandle(
        val created: CreatedSession,
        val binding: EndpointSessionBinding,
        val generation: Long,
    )

    private sealed class EndpointAccess {
        data class Ready(val endpoint: AuthorizedEndpoint) : EndpointAccess()
        object Denied : EndpointAccess()
        object Missing : EndpointAccess()
        object Stale : EndpointAccess()
    }
}
