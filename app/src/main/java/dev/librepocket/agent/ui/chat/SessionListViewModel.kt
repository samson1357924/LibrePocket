package dev.librepocket.agent.ui.chat

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transcript session list for the drawer. The store has no observable query,
 * so the list is refreshed explicitly (drawer open, turn finished, new/open/logout).
 */
class SessionListViewModel(
    private val store: SessionStore,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = try {
                withContext(Dispatchers.IO) { store.listSessions() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

class SessionListViewModelFactory(app: Application) : ViewModelProvider.Factory {
    private val store: SessionStore by lazy { RoomSessionStore(SessionDbHolder.get(app)) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SessionListViewModel(store) as T
    }
}
