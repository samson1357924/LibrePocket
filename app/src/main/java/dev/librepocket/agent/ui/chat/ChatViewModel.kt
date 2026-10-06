package dev.librepocket.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isResponding: Boolean = false,
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var nextId = 1L
    private var responseJob: Job? = null

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun prefill(text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(input = text) }
    }

    // TODO(P1): wire to TurnController/ChatSession streaming; echo is demo-only (P1_SPEC).
    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.isResponding) return
        val userMsg = ChatMessage(id = nextId++, isUser = true, text = text)
        _uiState.update { it.copy(messages = it.messages + userMsg, input = "", isResponding = true) }
        responseJob?.cancel()
        val captured = text
        responseJob = viewModelScope.launch {
            // Placeholder echo until the real agent loop is wired.
            delay(600)
            val reply = ChatMessage(
                id = nextId++,
                isUser = false,
                text = "收到：「$captured」\n（Demo 回覆，之後會接上真正的 Agent）",
            )
            _uiState.update { it.copy(messages = it.messages + reply, isResponding = false) }
        }
    }

    fun newChat() {
        responseJob?.cancel()
        responseJob = null
        _uiState.update { ChatUiState() }
    }

    override fun onCleared() {
        responseJob?.cancel()
        super.onCleared()
    }
}
