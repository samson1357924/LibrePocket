package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * P3-B wrap 邊界測試（Issue #136 B2 接線）：
 * null 拒收／Process 直通／缺方法抛／超時重載 primitive 強制。
 * 全 fake 遠端物件，零真機、零 binder、零 su。
 */
class ShizukuRemoteProcessTest {

    /**
     * 終審殘留隔離（Phase 1 P1-1 模式，Round-2 Phase A 補）：每用例首尾歸零
     * consecutive＋輪詢收斂 outstanding/active，避免同 JVM 內熔斷污染後測。
     *
     * Hermetic fixture：暫存 `screencap`＋`systemOwned`（3 項 runner 層失敗皆
     * `SEARCH_DIR_UNTRUSTED` 在先、到不了遠端流 EIO／getter-throw 分支；
     * 生產門零變更）。
     */
    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUpBreakerIsolation() {
        hermeticExec = HermeticExecFixture("remote-hermetic", "screencap")
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        hermeticExec?.close()
        hermeticExec = null
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected",
                )
            }
            Thread.sleep(50L)
        }
    }

    private fun pollActiveTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.activeCalls() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "active=${ElevatedCleanupTracker.activeCalls()} expected=$expected",
                )
            }
            Thread.sleep(20L)
        }
    }

    private class PassThrough : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    @Test fun wrapNullThrows() {
        try {
            ShizukuRemoteProcess.wrap(null)
            fail("wrap(null) must throw")
        } catch (_: IllegalStateException) {
            // 預期：fail-closed，不偽造進程。
        }
    }

    @Test fun wrapProcessPassthrough() {
        // 已是 Process 即直回同一實例，不再包裝。
        val process: Process = PassThrough()
        assertSame(process, ShizukuRemoteProcess.wrap(process))
    }

    @Test fun wrapMissingMethodThrows() {
        // 無同名方法的遠端物件：呼叫即抛（UnsupportedOperationException），不靜默誤調。
        val wrapped = ShizukuRemoteProcess.wrap(Any())
        try {
            wrapped.exitValue()
            fail("missing exitValue must throw")
        } catch (_: UnsupportedOperationException) {
            // 預期。
        }
        try {
            wrapped.waitFor()
            fail("missing waitFor must throw")
        } catch (_: UnsupportedOperationException) {
            // 預期。
        }
    }

    /** 宣告 primitive long 的遠端：boxed Long 仍精確匹配（Long.TYPE 傳遞）。 */
    private class FakeLongWait {
        var seenTimeout: Long = -1L
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            seenTimeout = timeout
            return true
        }
    }

    /** 宣告 primitive int 的遠端：Number 承接＋coerce 收斂（Integer.TYPE 傳遞）。 */
    private class FakeIntWait {
        var seenTimeout: Int = -1
        fun waitFor(timeout: Int, unit: TimeUnit): Boolean {
            seenTimeout = timeout
            return true
        }
    }

    @Test fun remoteWaitForTimeoutCoercesPrimitive() {
        val longRemote = FakeLongWait()
        val wrappedLong = ShizukuRemoteProcess.wrap(longRemote)
        assertTrue(wrappedLong.waitFor(10L, TimeUnit.MILLISECONDS))
        assertEquals(10L, longRemote.seenTimeout)

        val intRemote = FakeIntWait()
        val wrappedInt = ShizukuRemoteProcess.wrap(intRemote)
        assertTrue(wrappedInt.waitFor(7L, TimeUnit.MILLISECONDS))
        assertEquals(7, intRemote.seenTimeout)
    }

    // ---- V2 對等：遠端流中途 EIO／getter 抛經 wrap 仍對等（參 B2 V2 FailingReadStream） ----

    /** 前綴可讀後抛 EIO 的遠端流（對標 B2 V2 `FailingReadStream`）。 */
    private class FailingRemoteStream(private val prefix: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos < prefix.size) return prefix[pos++].toInt() and 0xFF
            throw IOException("simulated EIO")
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < prefix.size) {
                val n = minOf(len, prefix.size - pos)
                System.arraycopy(prefix, pos, b, off, n)
                pos += n
                return n
            }
            throw IOException("simulated EIO")
        }
    }

    /**
     * 非 Process 的 EIO 遠端假物件：stdout/stderr 各自可配完整（EOF）或中途 EIO
     * （前綴可讀後抛），或 getter 直接抛（模擬委派漂移，訊息帶 REMOTE-MARKER 供對等斷言）。
     * 同名方法非 Process 子類，直 cast 必 CCE（見 B2 wrap 契約）。
     */
    private class FailingRemote(
        private val outPrefix: ByteArray = ByteArray(0),
        private val errPrefix: ByteArray = ByteArray(0),
        private val outEio: Boolean = true,
        private val errEio: Boolean = true,
        private val throwGetter: Boolean = false,
    ) {
        var destroyed = false
        fun getInputStream(): InputStream {
            if (throwGetter) throw IllegalStateException("simulated remote getInputStream drift REMOTE-MARKER")
            return if (outEio) FailingRemoteStream(outPrefix) else ByteArrayInputStream(outPrefix)
        }
        fun getErrorStream(): InputStream {
            if (throwGetter) throw IllegalStateException("simulated remote getErrorStream drift REMOTE-MARKER")
            return if (errEio) FailingRemoteStream(errPrefix) else ByteArrayInputStream(errPrefix)
        }
        fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        fun waitFor(): Int = 0
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        fun exitValue(): Int = 0
        fun destroy() {
            destroyed = true
        }
    }

    private fun assertEioAfterPrefix(stream: InputStream, prefix: ByteArray) {
        val buf = ByteArray(prefix.size + 16)
        var got = 0
        try {
            while (got < buf.size) {
                val n = stream.read(buf, got, buf.size - got)
                if (n < 0) fail("stream must throw EIO, not EOF")
                got += n
            }
            fail("stream must throw EIO after prefix")
        } catch (e: IOException) {
            assertTrue("EIO message=${e.message}", e.message?.contains("simulated EIO") == true)
        }
        assertTrue(
            "prefix must match: ${buf.copyOf(prefix.size).toList()} vs ${prefix.toList()}",
            buf.copyOf(prefix.size).contentEquals(prefix),
        )
    }

    @Test fun remoteStdoutMidEioParity() {
        // stdout 中途 EIO：遠端直讀與 wrap 後讀取對等（同前綴＋同 EIO；stderr 完整不干擾）。
        val partial = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val direct = FailingRemote(outPrefix = partial, outEio = true, errEio = false)
        assertEioAfterPrefix(direct.getInputStream(), partial)
        val wrapped = ShizukuRemoteProcess.wrap(
            FailingRemote(outPrefix = partial, outEio = true, errEio = false),
        )
        assertEioAfterPrefix(wrapped.inputStream, partial)
    }

    @Test fun remoteStderrMidEioParity() {
        // stderr 中途 EIO：同上對等（stdout 完整 EOF，不影響 stderr 失敗判定，見 runner 層）。
        val warn = "warn".toByteArray()
        val direct = FailingRemote(outPrefix = ByteArray(0), outEio = false, errPrefix = warn, errEio = true)
        assertEioAfterPrefix(direct.getErrorStream(), warn)
        val wrapped = ShizukuRemoteProcess.wrap(
            FailingRemote(outPrefix = ByteArray(0), outEio = false, errPrefix = warn, errEio = true),
        )
        assertEioAfterPrefix(wrapped.errorStream, warn)
    }

    @Test fun remoteGetterThrowParity() {
        // getter 抛：遠端直調與 wrap 後皆抛同一底層失敗（wrap 經反射多一層
        // InvocationTargetException 包裝，cause 即原文；drain 側 catch Exception
        // 即 markFailed，verdict 對等，見下 runner 層）。
        val direct = FailingRemote(throwGetter = true)
        try {
            direct.getInputStream()
            fail("direct getter must throw")
        } catch (e: IllegalStateException) {
            assertTrue("${e.message}", e.message?.contains("REMOTE-MARKER") == true)
        }
        val wrapped = ShizukuRemoteProcess.wrap(FailingRemote(throwGetter = true))
        try {
            wrapped.inputStream
            fail("wrapped getter must throw")
        } catch (e: Exception) {
            val chain = generateSequence(e as Throwable) { it.cause }.joinToString { "${it.message}" }
            assertTrue("chain=$chain", chain.contains("REMOTE-MARKER"))
        }
        try {
            wrapped.errorStream
            fail("wrapped stderr getter must throw")
        } catch (e: Exception) {
            val chain = generateSequence(e as Throwable) { it.cause }.joinToString { "${it.message}" }
            assertTrue("chain=$chain", chain.contains("REMOTE-MARKER"))
        }
    }

    private fun failingRemoteRunner(
        outPrefix: ByteArray = ByteArray(0),
        errPrefix: ByteArray = ByteArray(0),
        outEio: Boolean = true,
        errEio: Boolean = true,
        throwGetter: Boolean = false,
    ): ShizukuShellRunner = ShizukuShellRunner(
        privateRoot = "/data/data/dev.librepocket.agent/files",
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = { ShizukuRemoteProcess.wrap(FailingRemote(outPrefix, errPrefix, outEio, errEio, throwGetter)) },
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    @Test fun wrappedStdoutMidEioMapsToFailedAndCaptureNull() {
        // runner 層對等（B2 V2 rootStdoutReadFailure 之遠端版）：screencap stdout
        // 中途 EIO（stderr 完整 EOF）→ Failed＋capture null。
        val partial = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val runner = failingRemoteRunner(outPrefix = partial, outEio = true, errEio = false)
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }

    @Test fun wrappedStderrMidEioMapsToFailedAndCaptureNull() {
        // stdout 完整但 stderr 中途 EIO → 仍 Failed＋capture null（err readFailed 亦失敗）。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01, 0x02)
        val runner = failingRemoteRunner(
            outPrefix = png,
            outEio = false,
            errPrefix = "warn".toByteArray(),
            errEio = true,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }

    @Test fun wrappedGetterThrowMapsToFailedAndCaptureNull() {
        // stdout getter 抛 → Failed＋capture null（W1 語義：throw 即 markFailed，不帶原文）。
        val runner = failingRemoteRunner(throwGetter = true)
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }
}
