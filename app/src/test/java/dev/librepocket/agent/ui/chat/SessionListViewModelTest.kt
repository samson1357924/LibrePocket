package dev.librepocket.agent.ui.chat

import androidx.test.core.app.ApplicationProvider
import dev.librepocket.session.LibrePocketDb
import dev.librepocket.session.RoomSessionStore
import dev.librepocket.session.TranscriptEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionListViewModelTest {

    private lateinit var db: LibrePocketDb
    private lateinit var store: RoomSessionStore
    private lateinit var viewModel: SessionListViewModel
    private lateinit var tmpDir: File
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LibrePocketDb.openInMemory(ApplicationProvider.getApplicationContext())
        store = RoomSessionStore(db) { now }
        viewModel = SessionListViewModel(store)
        tmpDir = Files.createTempDirectory("session-vm-test").toFile()
    }

    @After
    fun tearDown() {
        if (this::db.isInitialized) {
            db.close()
        }
        if (this::tmpDir.isInitialized) {
            tmpDir.deleteRecursively()
        }
    }

    private fun event(
        sid: String,
        kind: String = "user",
        text: String = "hello",
        run: String = "run-1",
    ) = TranscriptEvent(
        sessionId = sid,
        runId = run,
        kind = kind,
        text = text,
        createdAt = now,
    )

    @Test
    fun exportSessionJsonl_successWritesStreamAndCleansUp(): Unit = runBlocking {
        val sid = store.createSession("Test Session", "preset/gpt-4o")
        store.appendEvent(event(sid, kind = "user", text = "secret email: user@test.com"))
        store.appendEvent(event(sid, kind = "assistant", text = "reply message"))

        val out = ByteArrayOutputStream()
        val result = viewModel.exportSessionJsonl(sid, out, tmpDir)

        assertTrue("export should succeed", result.isSuccess)
        assertTrue(
            "exportState should be Success",
            viewModel.exportState.value is ExportUiState.Success,
        )

        val exportedBytes = out.toByteArray()
        assertTrue("output should not be empty", exportedBytes.isNotEmpty())
        val lines = exportedBytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue("should redact sensitive info", lines[0].contains("⟦REDACTED:EMAIL⟧"))
        assertFalse("should not contain raw email", lines[0].contains("user@test.com"))

        // Temp files must be completely cleaned up
        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary export files should remain", 0, remaining.size)
    }

    @Test
    fun exportSessionJsonl_nonExistentSession_failsAndCleansUp(): Unit = runBlocking {
        val out = ByteArrayOutputStream()
        val result = viewModel.exportSessionJsonl("non-existent-session-id", out, tmpDir)

        assertTrue("export non-existent session should fail", result.isFailure)
        assertTrue(
            "exportState should be Error",
            viewModel.exportState.value is ExportUiState.Error,
        )

        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary export files should remain on error", 0, remaining.size)
    }

    @Test
    fun exportSessionJsonl_outputStreamError_failsAndCleansUp(): Unit = runBlocking {
        val sid = store.createSession("Test Session", "preset/gpt-4o")
        store.appendEvent(event(sid, kind = "user", text = "hello"))

        val failingOut = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("simulated disk write failure")
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("simulated disk write failure")
            }
        }

        val result = viewModel.exportSessionJsonl(sid, failingOut, tmpDir)
        assertTrue("export on failing stream should fail", result.isFailure)
        assertTrue(
            "exportState should be Error",
            viewModel.exportState.value is ExportUiState.Error,
        )

        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary files should remain on stream write failure", 0, remaining.size)
    }

    @Test
    fun importSessionJsonl_successCreatesSessionRefreshesAndCleansUp(): Unit = runBlocking {
        val jsonlContent = """
            {"seq":1,"runId":"r1","kind":"user","text":"imported question","imagesOmitted":0,"createdAt":1700000000000,"isPartial":false,"isFinal":true}
            {"seq":2,"runId":"r1","kind":"assistant","text":"imported answer","imagesOmitted":0,"createdAt":1700000001000,"isPartial":false,"isFinal":true}
        """.trimIndent()

        val input = ByteArrayInputStream(jsonlContent.toByteArray(Charsets.UTF_8))
        val result = viewModel.importSessionJsonl(input, tmpDir)

        assertTrue("import should succeed", result.isSuccess)
        val newSessionId = result.getOrThrow()
        assertEquals(
            ImportUiState.Success(newSessionId),
            viewModel.importState.value,
        )

        // Session must be in Room store
        val sessionMeta = store.getSession(newSessionId)
        assertTrue("imported session should exist in store", sessionMeta != null)
        assertEquals("imported question", sessionMeta?.title)

        // ViewModel sessions must be refreshed
        assertTrue(
            "sessions flow should contain new session",
            viewModel.sessions.value.any { it.sessionId == newSessionId },
        )

        // Temp file cleaned up
        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary import files should remain", 0, remaining.size)
    }

    @Test
    fun importSessionJsonl_corruptedFile_failsClosedAndCleansUp(): Unit = runBlocking {
        val corruptedContent = """
            {"seq":1,"runId":"r1","kind":"user","text":"valid first line","createdAt":1700000000000,"isPartial":false,"isFinal":true}
            {"seq":2, broken corrupted invalid json
        """.trimIndent()

        val input = ByteArrayInputStream(corruptedContent.toByteArray(Charsets.UTF_8))
        val result = viewModel.importSessionJsonl(input, tmpDir)

        assertTrue("import of corrupted content must fail", result.isFailure)
        assertTrue(
            "importState should be Error",
            viewModel.importState.value is ImportUiState.Error,
        )

        // No sessions created
        assertEquals(0, store.listSessions().size)

        // Temp file cleaned up
        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary import files should remain on error", 0, remaining.size)
    }

    @Test
    fun importSessionJsonl_outOfOrderSeq_failsClosedAndCleansUp(): Unit = runBlocking {
        val outOfOrderContent = """
            {"seq":2,"runId":"r1","kind":"user","text":"first","createdAt":1700000000000,"isPartial":false,"isFinal":true}
            {"seq":1,"runId":"r1","kind":"assistant","text":"second","createdAt":1700000001000,"isPartial":false,"isFinal":true}
        """.trimIndent()

        val input = ByteArrayInputStream(outOfOrderContent.toByteArray(Charsets.UTF_8))
        val result = viewModel.importSessionJsonl(input, tmpDir)

        assertTrue("import with non-monotonic seq must fail", result.isFailure)
        assertTrue(
            "importState should be Error",
            viewModel.importState.value is ImportUiState.Error,
        )

        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary import files should remain on seq error", 0, remaining.size)
    }

    @Test
    fun importSessionJsonl_oversizedFile_failsClosedAndCleansUp(): Unit = runBlocking {
        // Stream that produces more than RoomSessionStore.IMPORT_MAX_BYTES
        val limit = RoomSessionStore.IMPORT_MAX_BYTES.toLong()
        val oversizedStream = object : InputStream() {
            var emitted = 0L
            override fun read(): Int {
                return if (emitted < limit + 1024L) {
                    emitted++
                    'a'.code
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emitted >= limit + 1024L) return -1
                val toRead = minOf(len.toLong(), (limit + 1024L) - emitted).toInt()
                b.fill('a'.code.toByte(), off, off + toRead)
                emitted += toRead
                return toRead
            }
        }

        val result = viewModel.importSessionJsonl(oversizedStream, tmpDir)
        assertTrue("oversized import must fail", result.isFailure)
        assertTrue(
            "importState should be Error",
            viewModel.importState.value is ImportUiState.Error,
        )

        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary files should remain after oversize abort", 0, remaining.size)
    }

    @Test
    fun resetStates_resetsToIdle(): Unit = runBlocking {
        viewModel.exportSessionJsonl("missing", ByteArrayOutputStream(), tmpDir)
        assertTrue(viewModel.exportState.value is ExportUiState.Error)
        viewModel.resetExportState()
        assertEquals(ExportUiState.Idle, viewModel.exportState.value)

        viewModel.importSessionJsonl(ByteArrayInputStream("bad".toByteArray()), tmpDir)
        assertTrue(viewModel.importState.value is ImportUiState.Error)
        viewModel.resetImportState()
        assertEquals(ImportUiState.Idle, viewModel.importState.value)
    }

    private class TrackableOutputStream : OutputStream() {
        var isClosed = false
        override fun write(b: Int) {}
        override fun close() {
            super.close()
            isClosed = true
        }
    }

    private class TrackableInputStream(private val delegate: InputStream) : InputStream() {
        var isClosed = false
        override fun read(): Int = delegate.read()
        override fun close() {
            super.close()
            isClosed = true
        }
    }

    @Test
    fun exportSessionJsonl_closesStreamEvenOnCacheDirAndTempFileFailure(): Unit = runBlocking {
        val blockingFile = File(tmpDir, "blocking_file").apply { writeText("not a directory") }

        // Case 1: mkdirs fails because parent is a regular file
        val uncreatableDir = File(blockingFile, "sub/dir")
        val out1 = TrackableOutputStream()
        val sid = store.createSession("Title", "model")
        val res1 = viewModel.exportSessionJsonl(sid, out1, uncreatableDir)
        assertTrue("export should fail when dir uncreatable", res1.isFailure)
        assertTrue("outputStream must be closed on cacheDir creation failure", out1.isClosed)

        // Case 2: createTempFile fails because cacheDir is a regular file
        val out2 = TrackableOutputStream()
        val res2 = viewModel.exportSessionJsonl(sid, out2, blockingFile)
        assertTrue("export should fail when createTempFile fails", res2.isFailure)
        assertTrue("outputStream must be closed on createTempFile failure", out2.isClosed)
    }

    @Test
    fun importSessionJsonl_closesStreamEvenOnCacheDirAndTempFileFailure(): Unit = runBlocking {
        val blockingFile = File(tmpDir, "blocking_file").apply { writeText("not a directory") }

        // Case 1: mkdirs fails
        val uncreatableDir = File(blockingFile, "sub/dir")
        val in1 = TrackableInputStream(ByteArrayInputStream("{}".toByteArray()))
        val res1 = viewModel.importSessionJsonl(in1, uncreatableDir)
        assertTrue("import should fail when dir uncreatable", res1.isFailure)
        assertTrue("inputStream must be closed on cacheDir creation failure", in1.isClosed)

        // Case 2: createTempFile fails
        val in2 = TrackableInputStream(ByteArrayInputStream("{}".toByteArray()))
        val res2 = viewModel.importSessionJsonl(in2, blockingFile)
        assertTrue("import should fail when createTempFile fails", res2.isFailure)
        assertTrue("inputStream must be closed on createTempFile failure", in2.isClosed)
    }

    @Test
    fun exportSessionJsonl_cancellationException_rethrows(): Unit = runBlocking {
        val cancellingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw CancellationException("export cancelled")
            }
        }
        val sid = store.createSession("Title", "model")
        store.appendEvent(event(sid, kind = "user", text = "hello"))

        var caught: Throwable? = null
        try {
            viewModel.exportSessionJsonl(sid, cancellingStream, tmpDir)
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue("CancellationException must be rethrown on export", caught is CancellationException)
    }

    @Test
    fun importSessionJsonl_cancellationException_rethrows(): Unit = runBlocking {
        val cancellingStream = object : InputStream() {
            override fun read(): Int {
                throw CancellationException("import cancelled")
            }
        }

        var caught: Throwable? = null
        try {
            viewModel.importSessionJsonl(cancellingStream, tmpDir)
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue("CancellationException must be rethrown on import", caught is CancellationException)
    }

    @Test
    fun exportSessionJsonl_errorIsRedacted(): Unit = runBlocking {
        val failingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk failure with key: sk-proj-1234567890abcdef1234567890")
            }
        }
        val sid = store.createSession("Title", "model")
        store.appendEvent(event(sid, kind = "user", text = "hello"))

        val result = viewModel.exportSessionJsonl(sid, failingStream, tmpDir)
        assertTrue(result.isFailure)
        val state = viewModel.exportState.value
        assertTrue(state is ExportUiState.Error)
        val msg = (state as ExportUiState.Error).message
        assertFalse("error message should not contain raw secret", msg.contains("sk-proj-"))
        assertTrue("error message should contain redacted marker", msg.contains("⟦REDACTED"))
    }

    @Test
    fun importSessionJsonl_errorIsRedacted(): Unit = runBlocking {
        val failingStream = object : InputStream() {
            override fun read(): Int {
                throw IOException("network failure with token: Bearer abcdef123456")
            }
        }

        val result = viewModel.importSessionJsonl(failingStream, tmpDir)
        assertTrue(result.isFailure)
        val state = viewModel.importState.value
        assertTrue(state is ImportUiState.Error)
        val msg = (state as ImportUiState.Error).message
        assertFalse("error message should not contain raw token", msg.contains("abcdef123456"))
        assertTrue("error message should contain redacted marker", msg.contains("⟦REDACTED"))
    }
}
