package dev.librepocket.agent.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.librepocket.redact.Redactor
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.JsonlImportLimitException
import dev.librepocket.session.JsonlExportLimitException
import dev.librepocket.session.SessionMeta
import dev.librepocket.session.SessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

fun importErrorMessage(error: Throwable): String = if (error is JsonlImportLimitException) {
    "備份超過目前的匯入限制，舊版備份也可能受影響。請保留原檔，將備份分割或縮小後再試。"
} else {
    Redactor.redactError(error.message ?: "匯入失敗")
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

    private val stateLock = Any()
    private var exporting = false
    private var importing = false
    private var refreshGeneration = 0L

    fun resetExportState() {
        synchronized(stateLock) { if (!exporting) _exportState.value = ExportUiState.Idle }
    }

    fun resetImportState() {
        synchronized(stateLock) { if (!importing) _importState.value = ImportUiState.Idle }
    }

    fun refresh() {
        viewModelScope.launch {
            loadSessions()
        }
    }

    /** Read-only, bounded page; never attaches the session to chat or a provider. */
    suspend fun readTranscriptPage(sessionId: String, afterSeq: Long): List<TranscriptEvent> =
        withContext(Dispatchers.IO) {
            require(store.getSession(sessionId) != null) { "找不到對話" }
            store.loadEvents(sessionId, afterSeq, 100)
        }

    private suspend fun loadSessions() {
        val generation = synchronized(stateLock) { ++refreshGeneration }
        val sessions = try {
            withContext(Dispatchers.IO) { store.listSessions() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
        synchronized(stateLock) { if (refreshGeneration == generation) _sessions.value = sessions }
    }

    private suspend fun export(
        sessionId: String,
        cacheDir: File,
        supplied: OutputStream? = null,
        open: () -> OutputStream,
    ): Result<Unit> {
        val acquired = synchronized(stateLock) {
            if (exporting) false else { exporting = true; _exportState.value = ExportUiState.Exporting; true }
        }
        if (!acquired) {
            return try { closeSupplied(supplied); Result.failure(IllegalStateException("匯出進行中")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
        var opened = false
        var preparationFailure: Throwable? = null
        try {
            val result = try { withContext(Dispatchers.IO) {
                var tempFile: File? = null
                try {
                    check(cacheDir.isDirectory || cacheDir.mkdirs()) { "無法建立暫存目錄" }
                    tempFile = File.createTempFile("export_", ".jsonl", cacheDir)
                    require(store.getSession(sessionId) != null) { "Session not found: $sessionId" }
                    store.exportJsonl(sessionId, tempFile)
                    currentCoroutineContext().ensureActive()
                    val stream = open()
                    opened = true
                    val once = object : FilterOutputStream(stream) {
                        private val closed = java.util.concurrent.atomic.AtomicBoolean()
                        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                        // FilterOutputStream.close() flushes first and can deadlock behind a blocked write.
                        override fun close() { if (closed.compareAndSet(false, true)) out.close() }
                    }
                    withCancellationClose(once) { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                        out.flush()
                    }
                    Result.success(Unit)
                } finally {
                    tempFile?.let {
                        it.delete()
                        File(it.parentFile, it.name + ".tmp").delete()
                    }
                }
            } } catch (e: Throwable) {
                preparationFailure = e
                throw e
            } finally {
                if (!opened) try { closeSupplied(supplied) } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (preparationFailure != null) preparationFailure.addSuppressed(e) else throw e
                }
            }
            _exportState.value = ExportUiState.Success
            return result
        } catch (e: CancellationException) {
            _exportState.value = ExportUiState.Idle
            throw e
        } catch (e: Exception) {
            _exportState.value = ExportUiState.Error(if (e is JsonlExportLimitException) {
                "對話超過目前的匯出限制，無法匯出。請縮小對話內容後再試。"
            } else {
                Redactor.redactError(e.message ?: "匯出失敗")
            })
            return Result.failure(e)
        } finally {
            synchronized(stateLock) { exporting = false }
        }
    }

    suspend fun exportSessionJsonl(sessionId: String, outputStream: OutputStream, cacheDir: File): Result<Unit> {
        // The caller already opened this stream; close it even if preparation fails.
        return export(sessionId, cacheDir, outputStream) { outputStream }
    }

    private suspend fun import(supplied: InputStream? = null, open: () -> InputStream): Result<String> {
        val acquired = synchronized(stateLock) {
            if (importing) false else { importing = true; _importState.value = ImportUiState.Importing; true }
        }
        if (!acquired) {
            return try { closeSupplied(supplied); Result.failure(IllegalStateException("匯入進行中")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
        var opened = false
        var preparationFailure: Throwable? = null
        try {
            val id = try { withContext(Dispatchers.IO) {
                val stream = open()
                opened = true
                // EOF closes the provider before Room begins its transaction.
                val closeAtEof = object : FilterInputStream(stream) {
                    private val closed = java.util.concurrent.atomic.AtomicBoolean()
                    @Volatile private var eof = false
                    override fun close() { if (closed.compareAndSet(false, true)) super.close() }
                    override fun read(): Int {
                        if (eof) return -1
                        return super.read().also { if (it == -1) { eof = true; close() } }
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (eof) return -1
                        return super.read(b, off, len).also { if (it == -1) { eof = true; close() } }
                    }
                }
                withCancellationClose(closeAtEof) { store.importJsonl(it) }
            } } catch (e: Throwable) {
                preparationFailure = e
                throw e
            } finally {
                if (!opened) try { closeSupplied(supplied) } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (preparationFailure != null) preparationFailure.addSuppressed(e) else throw e
                }
            }
            loadSessions()
            _importState.value = ImportUiState.Success(id)
            return Result.success(id)
        } catch (e: CancellationException) {
            _importState.value = ImportUiState.Idle
            throw e
        } catch (e: Exception) {
            _importState.value = ImportUiState.Error(importErrorMessage(e))
            return Result.failure(e)
        } finally {
            synchronized(stateLock) { importing = false }
        }
    }

    private suspend fun closeSupplied(stream: Closeable?) {
        if (stream != null) withContext(NonCancellable + Dispatchers.IO) { stream.close() }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun importSessionJsonl(inputStream: InputStream, cacheDir: File): Result<String> {
        return import(inputStream) { inputStream }
    }


    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T, S : Closeable> withCancellationClose(stream: S, block: suspend (S) -> T): T {
        val context = currentCoroutineContext()
        val registration = context[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            // Provider close/flush can block: never run it on the cancelling thread.
            if (cause != null) Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                try { stream.close() } catch (_: Exception) { }
            })
        }
        var failure: Throwable? = null
        try {
            return block(stream)
        } catch (e: Throwable) {
            failure = e
            context.ensureActive()
            throw e
        } finally {
            registration?.dispose()
            try { stream.close() } catch (e: Exception) {
                context.ensureActive()
                if (failure != null) failure.addSuppressed(e) else throw e
            }
        }
    }

    fun exportUri(
        sessionId: String, uri: Uri, contentResolver: ContentResolver, cacheDir: File,
        onSuccess: (() -> Unit)? = null, onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            export(sessionId, cacheDir) {
                contentResolver.openOutputStream(uri) ?: error("無法開啟輸出檔案")
            }.onSuccess { onSuccess?.invoke() }.onFailure { onError?.invoke(it) }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun importUri(
        uri: Uri, contentResolver: ContentResolver, cacheDir: File,
        onSuccess: ((String) -> Unit)? = null, onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            import {
                contentResolver.openInputStream(uri) ?: error("無法開啟匯入檔案")
            }.onSuccess { onSuccess?.invoke(it) }.onFailure { onError?.invoke(it) }
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
