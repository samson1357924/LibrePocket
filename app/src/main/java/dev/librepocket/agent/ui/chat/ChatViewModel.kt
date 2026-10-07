package dev.librepocket.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.setup.EndpointConfig
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
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

    // Lifecycle state is main-thread confined. The generation is also read by
    // the provider's suspending key callback, so it is volatile across IO.
    @Volatile
    private var lifecycleGeneration = 0L
    private var lifecycleJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var lifecycleScope = CoroutineScope(viewModelScope.coroutineContext + lifecycleJob)
    private val sessionMutex = Mutex()
    private var currentSession: ChatSession? = null
    private var currentBinding: EndpointSessionBinding? = null
    private var currentSessionGeneration: Long? = null
    private var sessionCollectJob: Job? = null
    private var openJob: Job? = null
    private var lastUserText: String? = null
    // R2: unsent texts are owned per send/steer operation, never by a single
    // global String. Cleared only when the controller accepts that exact op
    // (startTurn return / FIFO queue); pre-accept endpoint invalidation drains
    // every entry into recoverableOps (never auto-sent to the new binding).
    private data class PendingOp(val opId: Long, val generation: Long, val text: String)
    private var nextOpId = 0L
    private val pendingOps = LinkedHashMap<Long, PendingOp>()
    // Endpoint-cancelled but never accepted: surfaced one-by-one through the
    // input box after each explicit send; discarded by newChat/open/logout.
    private val recoverableOps = ArrayDeque<PendingOp>()
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
        get() = _sessionState.value.status == ChatStatus.ERROR && lastUserText != null

    /** In-flight or follow-up pending: a new turn must steer, never send. */
    private fun isBusy(status: ChatStatus): Boolean =
        status == ChatStatus.STREAMING || status == ChatStatus.WAITING_STEERED

    fun onInputChange(value: String) {
        _input.value = value
    }

    /**
     * S2 系統 STT 入口：辨識正文以 prefill 進入輸入框（不自動送出），
     * 由使用者確認後送出；寫庫前經 Redactor（Room 寫路徑）。
     */
    fun prefill(text: String) {
        if (text.isBlank()) return
        _input.value = text
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        _input.value = ""
        if (isBusy(_sessionState.value.status)) {
            steer(text)
        } else {
            sendText(text)
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
        _input.value = ""
        sendText(clean)
    }

    /** Queue an instruction for the next round; never preempts the live turn. */
    fun steer(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        lastUserText = clean
        val startedAt = lifecycleGeneration
        val opId = ++nextOpId
        pendingOps[opId] = PendingOp(opId, startedAt, clean)
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
                        pendingOps[opId] = PendingOp(opId, handle.generation, clean)
                        // Q1: single atomic admission. startOrEnqueue decides
                        // start-vs-queue under the controller's lock, so the
                        // verdict is never stale: Started owns the text
                        // (remove + join), Queued owns it in the FIFO
                        // (remove + return). Anything thrown before either
                        // verdict leaves the op for invalidate/restore.
                        // Never treat a bare steer() return as acceptance:
                        // its idle path is fire-and-forget pre-gate.
                        val started = try {
                            handle.created.session.startOrEnqueue(clean)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalStateException) {
                            // Closed session: the teardown path (invalidation
                            // drain or explicit discard) owns this op.
                            return@launch
                        } catch (_: SecurityException) {
                            // Fresh chat.send deny is projected by the
                            // controller; keep the text recoverable.
                            setNoticeIfCurrent(operationGeneration, "POLICY_DENIED")
                            restoreOpForFailedSend(opId, "POLICY_DENIED")
                            return@launch
                        }
                        pendingOps.remove(opId)
                        val host = (started as? TurnStart.Started)?.host ?: return@launch
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
        val text = lastUserText ?: return
        if (_sessionState.value.status != ChatStatus.ERROR) return
        sendText(text)
    }

    private fun sendText(text: String) {
        lastUserText = text
        _notice.value = null
        val startedAt = lifecycleGeneration
        val opId = ++nextOpId
        pendingOps[opId] = PendingOp(opId, startedAt, text)
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
                        pendingOps[opId] = PendingOp(opId, handle.generation, text)
                        // Q1: single atomic admission (see steer() above).
                        // Started owns the text (remove + join); Queued owns
                        // it in the FIFO (remove + return). Anything thrown
                        // before either verdict leaves the op for
                        // invalidate/restore. Never treat a bare steer()
                        // return as acceptance.
                        val started = try {
                            handle.created.session.startOrEnqueue(text)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalStateException) {
                            // Closed session: the teardown path (invalidation
                            // drain or explicit discard) owns this op.
                            return@launch
                        } catch (_: SecurityException) {
                            // Fresh chat.send deny is projected by the
                            // controller; keep the text recoverable instead of
                            // swallowing it with no notice.
                            setNoticeIfCurrent(operationGeneration, "POLICY_DENIED")
                            restoreOpForFailedSend(opId, "POLICY_DENIED")
                            return@launch
                        }
                        pendingOps.remove(opId)
                        val host = (started as? TurnStart.Started)?.host ?: return@launch
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
            }
        }
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
        if (currentSession != null) {
            lifecycleGeneration += 1
            generation = lifecycleGeneration
            // F2: keep visible transcript when the transport is replaced.
            snapshotLiveToHistory()
            closeLive()
            _sessionState.value = EMPTY_SESSION_STATE
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
                    _sessionState.value = state
                }
            }
        }
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
        lifecycleGeneration += 1
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
            lastUserText = null
            pendingOps.clear()
            recoverableOps.clear()
            _input.value = ""
            _notice.value = "NO_ENDPOINT"
        } else {
            // R2: fail-closed sends keep EVERY unaccepted op recoverable,
            // never auto-sent to the new binding. The oldest fills an empty
            // box; newer typing is never overwritten; the rest wait in
            // recoverableOps and surface one-by-one after each explicit send.
            if (pendingOps.isNotEmpty()) {
                for ((_, op) in pendingOps) recoverableOps.addLast(op)
                pendingOps.clear()
                if (_input.value.isBlank()) {
                    recoverableOps.removeFirstOrNull()?.let { _input.value = it.text }
                }
                _notice.value = "SEND_CANCELLED_ENDPOINT_CHANGED"
            } else {
                _notice.value = null
            }
        }
    }

    private fun invalidateLifecycle(clearChat: Boolean) {
        lifecycleGeneration += 1
        cancelLifecycleOperations()
        openJob?.cancel()
        openJob = null
        closeLive()
        _sessionState.value = EMPTY_SESSION_STATE
        _notice.value = null
        if (clearChat) {
            _history.value = emptyList()
            _currentSessionId.value = null
            lastUserText = null
            pendingOps.clear()
            recoverableOps.clear()
        }
    }

    private fun isGenerationCurrent(generation: Long): Boolean =
        lifecycleGeneration == generation

    private fun cancelLifecycleOperations() {
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
    private fun snapshotLiveToHistory() {
        val live = _sessionState.value.messages.filter { it.role == "user" || it.role == "assistant" }
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
    private fun restoreOpForFailedSend(opId: Long, noticeValue: String) {
        val op = pendingOps.remove(opId) ?: return
        if (!isGenerationCurrent(op.generation)) return
        if (_input.value.isNotBlank()) {
            recoverableOps.addLast(op)
            return
        }
        _input.value = op.text
        _notice.value = noticeValue
    }

    // R2: surface the next endpoint-cancelled text after an explicit send
    // completes, so every unaccepted op is recoverable through the input box
    // without auto-sending anything to the new binding.
    private fun drainNextRecoverableToInput() {
        if (_input.value.isNotBlank()) return
        val next = recoverableOps.removeFirstOrNull() ?: return
        _input.value = next.text
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
