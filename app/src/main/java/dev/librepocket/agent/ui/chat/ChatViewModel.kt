package dev.librepocket.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import dev.librepocket.chat.UiMessage
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val sessions: ChatSessionFactory,
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

    private var currentSession: ChatSession? = null
    private var sessionEndpointId: String? = null
    private var sessionModel: String? = null
    private var sessionCollectJob: Job? = null
    private var openJob: Job? = null
    private var lastUserText: String? = null

    val canRetry: Boolean
        get() = _sessionState.value.status == ChatStatus.ERROR && lastUserText != null

    /** In-flight or follow-up pending: a new turn must steer, never send. */
    private fun isBusy(status: ChatStatus): Boolean =
        status == ChatStatus.STREAMING || status == ChatStatus.WAITING_STEERED

    fun onInputChange(value: String) {
        _input.value = value
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
        viewModelScope.launch {
            if (!keyReadAllowed()) {
                _notice.value = "POLICY_DENIED"
                return@launch
            }
            val session = try {
                withContext(Dispatchers.IO) { ensureSession(clean) }
            } catch (_: Exception) {
                null
            }
            if (session == null) {
                _notice.value = "NO_ENDPOINT"
                return@launch
            }
            try {
                session.session.steer(clean)
            } catch (_: Exception) {
                // Controller projects failures via sessionState; nothing to add.
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
        viewModelScope.launch {
            if (!keyReadAllowed()) {
                _notice.value = "POLICY_DENIED"
                return@launch
            }
            val created = try {
                withContext(Dispatchers.IO) { ensureSession(text) }
            } catch (_: Exception) {
                null
            }
            if (created == null) {
                _notice.value = "NO_ENDPOINT"
                return@launch
            }
            try {
                created.session.send(text)
            } catch (_: IllegalStateException) {
                // Busy: already projected via sessionState.error by the controller.
            } catch (_: SecurityException) {
                // Policy deny: already projected via sessionState.error.
            }
        }
    }

    fun cancel() {
        currentSession?.cancel()
    }

    fun newChat() {
        openJob?.cancel()
        openJob = null
        closeLive()
        _history.value = emptyList()
        _currentSessionId.value = null
        lastUserText = null
        _sessionState.value = EMPTY_SESSION_STATE
        _notice.value = null
    }

    /** Resume an existing transcript session (history replay + live binding). */
    fun openSession(sessionId: String) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { loadHistory(sessionId) }
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                _notice.value = "UNKNOWN_SESSION"
                return@launch
            }
            val created = try {
                withContext(Dispatchers.IO) {
                    val config = store.observe().first() ?: return@withContext null
                    sessions.open(config, sessionId)
                }
            } catch (_: Exception) {
                null
            }
            if (created == null) {
                _notice.value = "NO_ENDPOINT"
                return@launch
            }
            closeLive()
            _history.value = loaded
            attach(created.session, created.sessionId, created.endpointId, created.model)
            _notice.value = null
        }
    }

    private suspend fun ensureSession(title: String): CreatedSession? {
        val config = store.observe().first() ?: return null
        val current = currentSession
        if (current != null &&
            sessionEndpointId == config.providerId &&
            sessionModel == sessions.modelFor(config)
        ) {
            return CreatedSession(_currentSessionId.value, current, config.providerId, sessionModel.orEmpty())
        }
        // Rebuild (endpoint/model switch): the old live messages only exist in
        // the previous session's flow, so replay the previous transcript to
        // avoid visibly dropping history. New turns land in a fresh session.
        val prevId = _currentSessionId.value
        closeLive()
        val created = sessions.create(config, title)
        if (prevId != null) {
            try {
                loadHistory(prevId)?.let { _history.value = it }
            } catch (_: Exception) {
            }
        }
        attach(created.session, created.sessionId, created.endpointId, created.model)
        return created
    }

    private fun attach(session: ChatSession, transcriptId: String?, endpointId: String, model: String) {
        currentSession = session
        sessionEndpointId = endpointId
        sessionModel = model
        _currentSessionId.value = transcriptId
        sessionCollectJob?.cancel()
        sessionCollectJob = viewModelScope.launch {
            session.uiState.collect { _sessionState.value = it }
        }
    }

    /**
     * key.read is ASK-by-default (consented at setup); only a DENY rule blocks
     * here (fail-closed, zero requests on deny). Shared by send and steer paths.
     */
    private suspend fun keyReadAllowed(): Boolean = try {
        withContext(Dispatchers.IO) {
            val endpointId = store.observe().first()?.providerId.orEmpty()
            policy.evaluateFresh("key.read", endpointId).verdict != Verdict.DENY
        }
    } catch (_: Exception) {
        false
    }

    private fun closeLive() {
        sessionCollectJob?.cancel()
        sessionCollectJob = null
        try {
            currentSession?.close()
        } catch (_: Exception) {
        }
        currentSession = null
        sessionEndpointId = null
        sessionModel = null
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
        closeLive()
        super.onCleared()
    }
}
