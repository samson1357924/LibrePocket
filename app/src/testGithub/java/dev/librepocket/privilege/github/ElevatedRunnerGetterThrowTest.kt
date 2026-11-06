package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X1 getter-throw 回歸（owner 新 P1 lifecycle，W1 語義保留）：
 * `process.inputStream`／`errorStream` getter 本身可抛（`ShizukuRemoteProcess`
 * 的 ISE/UOE/ITE）或可阻塞（同步 Binder IPC，見 BlockedGetterTest）。
 * X1 將 getter 移回 gobbler 內整段 try/catch：throw 即 markFailed（W1 語義保留：
 * 任一流 throw 即 readFailed=true，兩 runner 回同一固定字串 Failed，不帶
 * remote 原文；無未捕捉例外），block 則 caller 不受影響照常進 deadline/poll/kill
 * （有界，不卡死呼叫執行緒；kill 只關已取流，不重調 getter）。
 *
 * 全 fake（局部自訂 getter-throw 假進程，共用 fixture 不支援 getter throw
 * 故不動它），零真機、零 su、零 binder。4 測：兩 runner ×（stdout throw／
 * stderr throw，exit 0＋waitFor true）。每測另設
 * `defaultUncaughtExceptionHandler` 記錄並斷言無未捕捉例外（測完恢復）。
 */
class ElevatedRunnerGetterThrowTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    /**
     * 局部 getter-throw 假進程（W1 專用，不動共用 fixture）：
     * `throwStdoutGetter`／`throwStderrGetter` 為 true 的流 getter 即抛
     * `IllegalStateException`（模擬 ShizukuRemoteProcess 委派漂移，訊息帶
     * distinctive marker 供「不帶 remote 原文」斷言）；其餘 exit 0＋waitFor 即 true。
     */
    private class GetterThrowProcess(
        private val stdoutBytes: ByteArray = ByteArray(0),
        private val throwStdoutGetter: Boolean = false,
        private val throwStderrGetter: Boolean = false,
    ) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream =
            if (throwStdoutGetter) {
                throw IllegalStateException("simulated remote getInputStream drift W1-MARKER")
            } else {
                ByteArrayInputStream(stdoutBytes)
            }
        override fun getErrorStream(): InputStream =
            if (throwStderrGetter) {
                throw IllegalStateException("simulated remote getErrorStream drift W1-MARKER")
            } else {
                ByteArrayInputStream(ByteArray(0))
            }
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    private fun shizukuRunner(spawn: (List<String>) -> Process) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun rootRunner(spawn: (List<String>) -> Process) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
    )

    /**
     * 未捕捉例外守衛：設 handler 記錄 daemon thread 逃逸，跑完小睡讓舊碼
     * gobbler（若有）有時間觸發，再斷言零未捕捉，最後恢復原 handler。
     */
    private fun runWithUncaughtGuard(block: (uncaught: MutableList<Throwable>) -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught: MutableList<Throwable> = mutableListOf()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            synchronized(uncaught) { uncaught.add(e) }
        }
        try {
            block(uncaught)
            // 舊碼 gobbler 求值 getter 即抛（ms 級）；留 500ms 讓逃逸有時間觸發，
            // 修復後根本無 gobbler 啟動，此 sleep 只防「逃逸太慢」誤綠。
            Thread.sleep(500L)
            synchronized(uncaught) {
                assertTrue("uncaught thread exceptions: $uncaught", uncaught.isEmpty())
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun shizukuStdoutGetterThrowMapsToFailedAndCaptureNull() {
        val runner = shizukuRunner { GetterThrowProcess(throwStdoutGetter = true) }
        runWithUncaughtGuard { uncaught ->
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "W1-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            b as ElevatedBinaryResult.Failed
            assertEquals("elevated IO read failed (incomplete stream)", b.message)
            assertTrue("must not leak remote text: ${b.message}", !b.message.contains("W1-MARKER"))
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "W1-T", 5_000L)())
            assertTrue(uncaught.isEmpty())
        }
    }

    @Test fun shizukuStderrGetterThrowMapsToFailedAndCaptureNull() {
        // stdout PNG 完整，但 stderr getter 抛 → 仍 Failed（非 Ok），capture null。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01, 0x02)
        val runner = shizukuRunner { GetterThrowProcess(png, throwStderrGetter = true) }
        runWithUncaughtGuard { uncaught ->
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "W1-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            b as ElevatedBinaryResult.Failed
            assertEquals("elevated IO read failed (incomplete stream)", b.message)
            assertTrue("must not leak remote text: ${b.message}", !b.message.contains("W1-MARKER"))
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "W1-T", 5_000L)())
            assertTrue(uncaught.isEmpty())
        }
    }

    @Test fun rootStdoutGetterThrowMapsToFailedAndCaptureNull() {
        val runner = rootRunner { GetterThrowProcess(throwStdoutGetter = true) }
        runWithUncaughtGuard { uncaught ->
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "W1-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            b as ElevatedBinaryResult.Failed
            assertEquals("elevated IO read failed (incomplete stream)", b.message)
            assertTrue("must not leak remote text: ${b.message}", !b.message.contains("W1-MARKER"))
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "W1-T", 5_000L)())
            assertTrue(uncaught.isEmpty())
        }
    }

    @Test fun rootStderrGetterThrowMapsToFailedAndCaptureNull() {
        // stdout PNG 完整，但 stderr getter 抛 → 仍 Failed（非 Ok），capture null。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01, 0x02)
        val runner = rootRunner { GetterThrowProcess(png, throwStderrGetter = true) }
        runWithUncaughtGuard { uncaught ->
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "W1-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            b as ElevatedBinaryResult.Failed
            assertEquals("elevated IO read failed (incomplete stream)", b.message)
            assertTrue("must not leak remote text: ${b.message}", !b.message.contains("W1-MARKER"))
            val audit = PrivilegeAuditLog()
            assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "W1-T", 5_000L)())
            assertTrue(uncaught.isEmpty())
        }
    }
}
