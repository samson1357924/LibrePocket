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
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
    fun exportLimitShowsActionableMessageWithoutWriting(): Unit = runBlocking {
        val sid = store.createSession("test", "p/m")
        val limitedStore = object : dev.librepocket.session.SessionStore by store {
            override suspend fun exportJsonl(sessionId: String, destFile: File) {
                throw dev.librepocket.session.JsonlExportLimitException("export line too large")
            }
        }
        val vm = SessionListViewModel(limitedStore)
        val out = ByteArrayOutputStream()
        val result = vm.exportSessionJsonl(sid, out, tmpDir)
        assertTrue(result.exceptionOrNull() is dev.librepocket.session.JsonlExportLimitException)
        assertEquals("對話超過目前的匯出限制，無法匯出。請縮小對話內容後再試。", (vm.exportState.value as ExportUiState.Error).message)
        assertEquals(0, out.size())
        assertTrue(tmpDir.listFiles()!!.isEmpty())
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
            {"seq":1,"runId":"r1","kind":"user","text":"valid first line","imagesOmitted":0,"createdAt":1700000000000,"isPartial":false,"isFinal":true}
            {"seq":2, broken corrupted invalid json
        """.trimIndent()

        assertEquals(1L, dev.librepocket.session.JsonlCodec.decode(1, corruptedContent.lines()[0]).seq)

        val input = ByteArrayInputStream(corruptedContent.toByteArray(Charsets.UTF_8))
        val result = viewModel.importSessionJsonl(input, tmpDir)

        assertTrue("import of corrupted content must fail", result.isFailure)
        assertEquals("import failed at line 2", result.exceptionOrNull()?.message)
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
            {"seq":2,"runId":"r1","kind":"user","text":"first","imagesOmitted":0,"createdAt":1700000000000,"isPartial":false,"isFinal":true}
            {"seq":1,"runId":"r1","kind":"assistant","text":"second","imagesOmitted":0,"createdAt":1700000001000,"isPartial":false,"isFinal":true}
        """.trimIndent()

        assertEquals(listOf(2L, 1L), outOfOrderContent.lines().mapIndexed { i, line ->
            dev.librepocket.session.JsonlCodec.decode(i + 1, line).seq
        })

        val input = ByteArrayInputStream(outOfOrderContent.toByteArray(Charsets.UTF_8))
        val result = viewModel.importSessionJsonl(input, tmpDir)

        assertTrue("import with non-monotonic seq must fail", result.isFailure)
        assertEquals("import failed at line 2", result.exceptionOrNull()?.message)
        assertEquals(0, store.listSessions().size)
        assertTrue(
            "importState should be Error",
            viewModel.importState.value is ImportUiState.Error,
        )

        val remaining = tmpDir.listFiles() ?: emptyArray()
        assertEquals("no temporary import files should remain on seq error", 0, remaining.size)
    }

    @Test
    fun importSessionJsonl_oversizedFile_failsClosedAndCleansUp(): Unit = runBlocking {
        // Blank lines bypass line/content/row limits so only the byte cap can reject this stream.
        val limit = RoomSessionStore.IMPORT_MAX_BYTES.toLong()
        val oversizedStream = object : InputStream() {
            var emitted = 0L
            override fun read(): Int {
                return if (emitted < limit + 1024L) {
                    emitted++
                    '\n'.code
                } else {
                    -1
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emitted >= limit + 1024L) return -1
                val toRead = minOf(len.toLong(), (limit + 1024L) - emitted).toInt()
                b.fill('\n'.code.toByte(), off, off + toRead)
                emitted += toRead
                return toRead
            }
        }

        val result = viewModel.importSessionJsonl(oversizedStream, tmpDir)
        assertTrue("oversized import must fail", result.isFailure)
        assertTrue(oversizedStream.emitted > limit)
        assertTrue(result.exceptionOrNull() is dev.librepocket.session.JsonlImportLimitException)
        assertEquals("import too large", result.exceptionOrNull()?.message)
        assertEquals(0, store.listSessions().size)
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
    fun importDoesNotUseCacheAndCloseFailureDoesNotCommit(): Unit = runBlocking {
        val invalidCache = File(tmpDir, "not-a-directory").apply { writeText("x") }
        val json = """{"seq":1,"runId":"r1","kind":"user","text":"user@test.com","imagesOmitted":0,"createdAt":1}"""
        val input = object : ByteArrayInputStream(json.toByteArray()) {
            override fun close() { throw IOException("close failed") }
        }
        val result = viewModel.importSessionJsonl(input, invalidCache)
        assertTrue(result.isFailure)
        assertTrue(viewModel.importState.value is ImportUiState.Error)
        assertTrue(store.listSessions().isEmpty())
        assertEquals("x", invalidCache.readText())
    }

    @Test
    fun importWithoutFinalNewlineDoesNotReadClosedProviderAgain(): Unit = runBlocking {
        val json = """{"seq":1,"runId":"r1","kind":"user","text":"hello","imagesOmitted":0,"createdAt":1}"""
        val input = object : InputStream() {
            private val delegate = ByteArrayInputStream(json.toByteArray())
            private var closed = false
            override fun read(): Int { check(!closed); return delegate.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(!closed)
                return delegate.read(b, off, len)
            }
            override fun close() { closed = true }
        }
        assertTrue(viewModel.importSessionJsonl(input, tmpDir).isSuccess)
        assertEquals(1, store.listSessions().size)
    }

    @Test
    fun cancellationDoesNotRunBlockingCloseOnCancellingThread(): Unit = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cancelReturned = java.util.concurrent.CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw IOException("released")
            }
            override fun close() {
                closeEntered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val job = async(kotlinx.coroutines.Dispatchers.Default) { viewModel.importSessionJsonl(input, tmpDir) }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val thread = Thread { job.cancel(); cancelReturned.countDown() }
        thread.start()
        try {
            assertTrue(closeEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("cancel must not wait for close", cancelReturned.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            thread.join(10_000)
        }
        try { job.await(); throw AssertionError("expected cancellation") } catch (_: CancellationException) { }
        assertTrue(store.listSessions().isEmpty())
    }

    @Test
    fun exportCloseFailureIsNotSuccess(): Unit = runBlocking {
        val sid = store.createSession("Title", "model")
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw IOException("close failed") }
        }
        assertTrue(viewModel.exportSessionJsonl(sid, output, tmpDir).isFailure)
        assertTrue(viewModel.exportState.value is ExportUiState.Error)
        assertTrue(tmpDir.listFiles()!!.isEmpty())
    }

    @Test
    fun concurrentImportsRejectSecondWithoutChangingFirstState(): Unit = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val json = """{"seq":1,"runId":"r1","kind":"user","text":"hello","imagesOmitted":0,"createdAt":1}"""
        val input = object : ByteArrayInputStream(json.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return super.read(b, off, len)
            }
        }
        val first = async(kotlinx.coroutines.Dispatchers.Default) {
            viewModel.importSessionJsonl(input, tmpDir)
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val second = viewModel.importSessionJsonl(ByteArrayInputStream(json.toByteArray()), tmpDir)
            assertTrue(second.isFailure)
            assertEquals(ImportUiState.Importing, viewModel.importState.value)
        } finally {
            release.countDown()
        }
        assertTrue(first.await().isSuccess)
        assertEquals(1, store.listSessions().size)
    }

    @Test
    fun cancellationClosesBlockedInputAndReleasesGateEvenWhenCloseThrows(): Unit = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(closed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw IOException("closed")
            }
            override fun close() {
                closed.countDown()
                throw IOException("close failed")
            }
        }
        val job = async(kotlinx.coroutines.Dispatchers.Default) {
            viewModel.importSessionJsonl(input, tmpDir)
        }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        viewModel.resetImportState()
        assertEquals(ImportUiState.Importing, viewModel.importState.value)
        job.cancel()
        try { job.await(); throw AssertionError("expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(ImportUiState.Idle, viewModel.importState.value)
        val valid = """{"seq":1,"runId":"r1","kind":"user","text":"hello","imagesOmitted":0,"createdAt":1}"""
        assertTrue(viewModel.importSessionJsonl(ByteArrayInputStream(valid.toByteArray()), tmpDir).isSuccess)
        assertEquals(1, store.listSessions().size)
    }

    @Test
    fun cancellationClosesBlockedOutputAndReleasesGate(): Unit = runBlocking {
        val sid = store.createSession("Title", "model")
        store.appendEvent(event(sid))
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(b: Int) {
                entered.countDown()
                check(closed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw IOException("closed")
            }
            override fun close() { closed.countDown(); throw IOException("close failed") }
        }
        val job = async(kotlinx.coroutines.Dispatchers.Default) {
            viewModel.exportSessionJsonl(sid, output, tmpDir)
        }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        viewModel.resetExportState()
        assertEquals(ExportUiState.Exporting, viewModel.exportState.value)
        job.cancel()
        try { job.await(); throw AssertionError("expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(ExportUiState.Idle, viewModel.exportState.value)
        assertTrue(viewModel.exportSessionJsonl(sid, ByteArrayOutputStream(), tmpDir).isSuccess)
    }

    @Test
    fun cancellationClosesOutputWithoutFlushingBlockedWriter(): Unit = runBlocking {
        val sid = store.createSession("Title", "model")
        store.appendEvent(event(sid))
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(b: Int) {
                entered.countDown()
                check(closed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw IOException("closed")
            }
            override fun flush() { check(closed.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            override fun close() { closed.countDown() }
        }
        val job = async(kotlinx.coroutines.Dispatchers.Default) { viewModel.exportSessionJsonl(sid, output, tmpDir) }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        assertTrue("close must not wait for flush", closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        try { job.await(); throw AssertionError("expected cancellation") } catch (_: CancellationException) { }
    }

    @Test
    fun rejectedSuppliedStreamsCloseOffCallerThread(): Unit = runBlocking {
        val sid = store.createSession("Title", "model")
        store.appendEvent(event(sid))
        val readEntered = java.util.concurrent.CountDownLatch(1)
        val writeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val firstInput = object : InputStream() {
            override fun read(): Int { readEntered.countDown(); releaseFirst.await(); return -1 }
        }
        val firstOutput = object : OutputStream() {
            override fun write(b: Int) { writeEntered.countDown(); releaseFirst.await() }
        }
        val firstImport = async(kotlinx.coroutines.Dispatchers.Default) { viewModel.importSessionJsonl(firstInput, tmpDir) }
        val firstExport = async(kotlinx.coroutines.Dispatchers.Default) { viewModel.exportSessionJsonl(sid, firstOutput, tmpDir) }
        val closeEntered = java.util.concurrent.CountDownLatch(2)
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            assertTrue(readEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(writeEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val rejectedInput = object : InputStream() {
                override fun read() = -1
                override fun close() { closeEntered.countDown(); releaseClose.await() }
            }
            val rejectedOutput = object : OutputStream() {
                override fun write(b: Int) {}
                override fun close() { closeEntered.countDown(); releaseClose.await() }
            }
            val secondImport = async(caller) { viewModel.importSessionJsonl(rejectedInput, tmpDir) }
            val secondExport = async(caller) { viewModel.exportSessionJsonl(sid, rejectedOutput, tmpDir) }
            assertTrue(closeEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val responsive = java.util.concurrent.CountDownLatch(1)
            caller.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { responsive.countDown() })
            assertTrue("caller thread must remain responsive", responsive.await(2, java.util.concurrent.TimeUnit.SECONDS))
            releaseClose.countDown()
            assertTrue(secondImport.await().isFailure)
            assertTrue(secondExport.await().isFailure)
        } finally {
            releaseClose.countDown()
            releaseFirst.countDown()
            caller.close()
        }
        firstImport.await()
        firstExport.await()
    }

    @Test
    fun oldOversizedBackupShowsUnderstandableLimitError(): Unit = runBlocking {
        val oldBackup = "{\"seq\":1,\"runId\":\"r\",\"kind\":\"user\",\"text\":\"" +
            "x".repeat(RoomSessionStore.IMPORT_MAX_LINE_CHARS) +
            "\",\"imagesOmitted\":0,\"createdAt\":1}"
        val result = viewModel.importSessionJsonl(oldBackup.byteInputStream(), tmpDir)
        assertTrue(result.isFailure)
        val message = (viewModel.importState.value as ImportUiState.Error).message
        assertTrue(message.contains("舊版備份"))
        assertTrue(message.contains("保留原檔"))
        assertEquals(message, importErrorMessage(result.exceptionOrNull()!!))
        assertTrue(store.listSessions().isEmpty())
    }

    @Test
    fun cancelledBeforeOpeningSuppliedOutputClosesOffCallerThread(): Unit = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowStore = object : dev.librepocket.session.SessionStore by store {
            override suspend fun getSession(sessionId: String): dev.librepocket.session.SessionMeta? {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = SessionListViewModel(slowStore)
        val closeEntered = java.util.concurrent.CountDownLatch(1)
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val output = object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() { closeEntered.countDown(); releaseClose.await() }
        }
        val job = async(caller) { vm.exportSessionJsonl("id", output, tmpDir) }
        try {
            entered.await()
            job.cancel()
            assertTrue(closeEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val responsive = java.util.concurrent.CountDownLatch(1)
            caller.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { responsive.countDown() })
            assertTrue(responsive.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            releaseClose.countDown()
            caller.close()
        }
        try { job.await(); throw AssertionError("expected cancellation") } catch (_: CancellationException) { }
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
            override fun close() { throw IOException("close failed") }
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
