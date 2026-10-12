package dev.librepocket.agent.ui.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Serial Main dispatcher so callbacks from IO return to a stable, Main-like thread. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatTestMainDispatcher {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "librepocket-chat-test-main").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    fun install() {
        Dispatchers.setMain(dispatcher)
    }

    suspend fun run(block: () -> Unit) {
        withContext(dispatcher) { block() }
    }

    fun clearViewModels(store: ChatTestViewModelStore) {
        runBlocking(dispatcher) { store.clearAndJoin() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun resetAndClose() {
        Dispatchers.resetMain()
        dispatcher.close()
    }
}

/** Ensures each test-owned ViewModel receives onCleared() before Main is reset. */
internal class ChatTestViewModelStore {
    private val store = ViewModelStore()
    private val viewModelJobs = ArrayList<Job>()
    private var nextKey = 0

    fun <T : ChatViewModel> own(viewModel: T): T {
        viewModelJobs += checkNotNull(viewModel.viewModelScope.coroutineContext[Job])
        store.put("chat-test-${nextKey++}", viewModel)
        return viewModel
    }

    suspend fun clearAndJoin() {
        store.clear()
        val finished = withTimeoutOrNull(10_000L) {
            viewModelJobs.joinAll()
            true
        }
        if (finished == null) {
            throw IllegalStateException(
                "ChatTestViewModelStore teardown timed out after 10000ms waiting for viewModelJobs to join",
            )
        }
    }
}

/** Cancel and join DataStore scopes while their dispatchers are still available. */
internal fun cancelAndJoinChatTestScopes(scopes: List<CoroutineScope>) {
    val jobs = scopes.mapNotNull { it.coroutineContext[Job] }
    scopes.forEach { it.cancel() }
    runBlocking {
        val finished = withTimeoutOrNull(10_000L) {
            jobs.joinAll()
            true
        }
        if (finished == null) {
            throw IllegalStateException(
                "cancelAndJoinChatTestScopes teardown timed out after 10000ms waiting for scopes to join",
            )
        }
    }
}
