package dev.librepocket.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.librepocket.agent.ui.setup.EndpointStore
import dev.librepocket.chat.ChatSession
import dev.librepocket.chat.ChatStatus
import dev.librepocket.chat.ChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EMPTY_SESSION_STATE = ChatUiState(
    messages = emptyList(),
    status = ChatStatus.IDLE,
    pendingSteerCount = 0,
    error = null,
)

/**
 * Chat UI state holder wired to the real [ChatSession].
 *
 * Owns the input box text plus a reference to the current session (recreated
 * when the endpoint changes). The message list / status / error all flow from
 * `session.uiState`; this VM only adapts and forwards send/cancel/retry.
 */
class ChatViewModel(
    private val store: EndpointStore,
    private val sessions: ChatSessionFactory,
) : ViewModel() {

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _sessionState = MutableStateFlow(EMPTY_SESSION_STATE)
    val sessionState: StateFlow<ChatUiState> = _sessionState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var currentSession: ChatSession? = null
    private var sessionEndpointId: String? = null
    private var sessionCollectJob: Job? = null
    private var lastUserText: String? = null

    val canRetry: Boolean
        get() = _sessionState.value.status == ChatStatus.ERROR && lastUserText != null

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun prefill(text: String) {
        if (text.isBlank()) return
        _input.value = text
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        if (_sessionState.value.status == ChatStatus.STREAMING) return
        sendText(text)
    }

    fun retry() {
        val text = lastUserText ?: return
        if (_sessionState.value.status != ChatStatus.ERROR) return
        sendText(text)
    }

    private fun sendText(text: String) {
        _input.value = ""
        lastUserText = text
        _notice.value = null
        viewModelScope.launch {
            val session = try {
                withContext(Dispatchers.IO) { ensureSession() }
            } catch (_: Exception) {
                null
            }
            if (session == null) {
                _notice.value = "NO_ENDPOINT"
                return@launch
            }
            try {
                session.send(text)
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
        sessionCollectJob?.cancel()
        sessionCollectJob = null
        try {
            currentSession?.close()
        } catch (_: Exception) {
        }
        currentSession = null
        sessionEndpointId = null
        lastUserText = null
        _sessionState.value = EMPTY_SESSION_STATE
        _notice.value = null
    }

    private suspend fun ensureSession(): ChatSession? {
        val config = store.observe().first() ?: return null
        val current = currentSession
        if (current != null && sessionEndpointId == config.providerId) return current
        try {
            current?.close()
        } catch (_: Exception) {
        }
        val created = sessions.create(config)
        currentSession = created
        sessionEndpointId = config.providerId
        sessionCollectJob?.cancel()
        sessionCollectJob = viewModelScope.launch {
            created.uiState.collect { _sessionState.value = it }
        }
        return created
    }

    override fun onCleared() {
        sessionCollectJob?.cancel()
        try {
            currentSession?.close()
        } catch (_: Exception) {
        }
        currentSession = null
        super.onCleared()
    }
}
