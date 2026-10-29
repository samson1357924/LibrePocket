package dev.librepocket.agent.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.redact.Redactor
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Exporting : ExportUiState
    data object Success : ExportUiState
    data class Error(val message: String) : ExportUiState
}

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Importing : ImportUiState
    data class Success(val sessionId: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

/**
 * Transcript session list for the drawer. The store has no observable query,
 * so the list is refreshed explicitly (drawer open, turn finished, new/open/logout).
 */
class SessionListViewModel(
    private val store: SessionStore,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    fun resetExportState() {
        _exportState.value = ExportUiState.Idle
    }

    fun resetImportState() {
        _importState.value = ImportUiState.Idle
    }

    fun refresh() {
        viewModelScope.launch {
            loadSessions()
        }
    }

    private suspend fun loadSessions() {
        _sessions.value = try {
            withContext(Dispatchers.IO) { store.listSessions() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    suspend fun exportSessionJsonl(
        sessionId: String,
        outputStream: OutputStream,
        cacheDir: File,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        _exportState.value = ExportUiState.Exporting
        outputStream.use { out ->
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                val err = IllegalStateException("無法建立暫存目錄: ${cacheDir.path}")
                val safeMsg = Redactor.redactError(err.message ?: "建立暫存目錄失敗")
                _exportState.value = ExportUiState.Error(safeMsg)
                return@withContext Result.failure(err)
            }
            val tempFile = try {
                File.createTempFile("export_", ".jsonl", cacheDir).apply { delete() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val safeMsg = Redactor.redactError(e.message ?: "建立暫存檔失敗")
                _exportState.value = ExportUiState.Error(safeMsg)
                return@withContext Result.failure(e)
            }
            try {
                require(store.getSession(sessionId) != null) { "Session not found: $sessionId" }
                store.exportJsonl(sessionId, tempFile)
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
                out.flush()
                _exportState.value = ExportUiState.Success
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val safeMsg = Redactor.redactError(e.message ?: "匯出失敗")
                _exportState.value = ExportUiState.Error(safeMsg)
                Result.failure(e)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                val siblingTmp = File(tempFile.parentFile, tempFile.name + ".tmp")
                if (siblingTmp.exists()) {
                    siblingTmp.delete()
                }
            }
        }
    }

    suspend fun importSessionJsonl(
        inputStream: InputStream,
        cacheDir: File,
    ): Result<String> = withContext(Dispatchers.IO) {
        _importState.value = ImportUiState.Importing
        inputStream.use { input ->
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                val err = IllegalStateException("無法建立暫存目錄: ${cacheDir.path}")
                val safeMsg = Redactor.redactError(err.message ?: "建立暫存目錄失敗")
                _importState.value = ImportUiState.Error(safeMsg)
                return@withContext Result.failure(err)
            }
            val tempFile = try {
                File.createTempFile("import_", ".jsonl", cacheDir)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val safeMsg = Redactor.redactError(e.message ?: "建立暫存檔失敗")
                _importState.value = ImportUiState.Error(safeMsg)
                return@withContext Result.failure(e)
            }
            try {
                var totalBytes = 0L
                val maxBytes = RoomSessionStore.IMPORT_MAX_BYTES
                tempFile.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        require(totalBytes <= maxBytes) { "import too large" }
                        out.write(buffer, 0, bytesRead)
                    }
                    out.flush()
                }
                val newSessionId = store.importJsonl(tempFile)
                loadSessions()
                _importState.value = ImportUiState.Success(newSessionId)
                Result.success(newSessionId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val safeMsg = Redactor.redactError(e.message ?: "匯入失敗")
                _importState.value = ImportUiState.Error(safeMsg)
                Result.failure(e)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    fun exportUri(
        sessionId: String,
        uri: Uri,
        contentResolver: ContentResolver,
        cacheDir: File,
        onSuccess: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val stream = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val safeMsg = Redactor.redactError(e.message ?: "無法開啟輸出檔案")
                _exportState.value = ExportUiState.Error(safeMsg)
                onError?.invoke(e)
                return@launch
            }
            if (stream == null) {
                val err = IllegalStateException("無法開啟輸出檔案")
                val safeMsg = Redactor.redactError(err.message ?: "無法開啟輸出檔案")
                _exportState.value = ExportUiState.Error(safeMsg)
                onError?.invoke(err)
                return@launch
            }
            val result = exportSessionJsonl(sessionId, stream, cacheDir)
            result.onSuccess {
                onSuccess?.invoke()
            }.onFailure { e ->
                onError?.invoke(e)
            }
        }
    }

    fun importUri(
        uri: Uri,
        contentResolver: ContentResolver,
        cacheDir: File,
        onSuccess: ((String) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val stream = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val safeMsg = Redactor.redactError(e.message ?: "無法開啟匯入檔案")
                _importState.value = ImportUiState.Error(safeMsg)
                onError?.invoke(e)
                return@launch
            }
            if (stream == null) {
                val err = IllegalStateException("無法開啟匯入檔案")
                val safeMsg = Redactor.redactError(err.message ?: "無法開啟匯入檔案")
                _importState.value = ImportUiState.Error(safeMsg)
                onError?.invoke(err)
                return@launch
            }
            val result = importSessionJsonl(stream, cacheDir)
            result.onSuccess { newId ->
                onSuccess?.invoke(newId)
            }.onFailure { e ->
                onError?.invoke(e)
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
