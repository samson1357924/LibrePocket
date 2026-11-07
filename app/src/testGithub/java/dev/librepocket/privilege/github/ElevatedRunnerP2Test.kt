package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.tool.Flavor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2 可觀測契約與 API hygiene 回歸（Phase2，不碰 Phase1 併發核心語義）：
 * Failed/Denied 清理旗標穿透、isSuDenied 精確化、quoteArg internal、
 * destroy 對稱、吞例外計數、spawn-null 早拒、toString/size、toNanos 夾頂、
 * truncated 語義、exit -1 註記、isScreenshot 共用、caller 中斷級聯。
 * 全 fake，零真機、零 su、零 binder。
 */
class ElevatedRunnerP2Test {

    private val privateRoot = "/data/data/files"

    /**
     * 終審殘留隔離（Phase 1 P1-1）：每用例首尾歸零 consecutive＋輪詢收斂
     * outstanding/active，避免同 JVM 內熔斷污染後測。
     * （測試隔離重置，非#136-B3修復；不升級 noteClean 可見性。）
     *
     * Round-2 Phase A hermetic fixture：暫存 `screencap`＋`id`＋`cat`＋
     * `systemOwned`（`failedCarries…`／`isScreenshotArgv…` 的 screencap 在
     * CI 宿主走預設表即 `SEARCH_DIR_UNTRUSTED`；生產門零變更）。
     */
    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUpBreakerIsolation() {
        hermeticExec = HermeticExecFixture("p2-hermetic", "screencap", "id", "cat")
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        hermeticExec?.close()
        hermeticExec = null
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

    private fun makeRoot(spawn: (List<String>) -> Process): RootSuRunner = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private fun makeShizuku(spawn: (List<String>) -> Process): ShizukuShellRunner =
        ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = spawn,
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
            execSearchDirs = hermeticExec!!.searchDirs,
            execAllowedRoots = hermeticExec!!.allowedRoots,
            execIsWritable = hermeticExec!!.systemOwned,
        )

    // ---- 1. Failed/Denied 清理旗標穿透 ----

    /** 吞 interrupt 的阻塞 stdin close（釋放前永不返回），供 finished 殘留旗標。 */
    private class BlockingStdinCloseOutput(private val latch: CountDownLatch) : ByteArrayOutputStream() {
        override fun close() {
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    // 吞 interrupt 續等，尾收 grace 無法解開，flag true。
                }
            }
            super.close()
        }
    }

    private class FailingReadStream(private val prefix: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos < prefix.size) return prefix[pos++].toInt() and 0xFF
            throw java.io.IOException("simulated EIO")
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < prefix.size) {
                val n = minOf(len, prefix.size - pos)
                System.arraycopy(prefix, pos, b, off, n)
                pos += n
                return n
            }
            throw java.io.IOException("simulated EIO")
        }
    }

    /** finished＋stdin 阻塞（lingering）＋另流 FailingRead：Failed 仍攜 flag。 */
    private class BlockedStdinFailingStdoutProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        override fun getOutputStream(): OutputStream = BlockingStdinCloseOutput(latch)
        override fun getInputStream(): InputStream =
            FailingReadStream(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    /** finished＋su-denied stderr＋stdin 阻塞：Denied 仍攜 flag。 */
    private class BlockedStdinDeniedProcess(
        private val latch: CountDownLatch,
    ) : Process() {
        override fun getOutputStream(): OutputStream = BlockingStdinCloseOutput(latch)
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream =
            ByteArrayInputStream("su: permission denied".toByteArray())
        override fun waitFor(): Int = 1
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 1
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun failedCarriesCleanupIncompleteWhenLingeringPlusReadFailed() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val runner = makeRoot { BlockedStdinFailingStdoutProcess(latch) }
        try {
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "P2-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            b as ElevatedBinaryResult.Failed
            assertTrue("Failed must carry flag", b.cleanupIncomplete)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
        }
        // 同語義走 Shizuku 側。
        val latch2 = CountDownLatch(1)
        val shizuku = makeShizuku { BlockedStdinFailingStdoutProcess(latch2) }
        try {
            val b = shizuku.runBinary(ElevatedRequest(listOf("screencap", "-p"), "P2-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            assertTrue((b as ElevatedBinaryResult.Failed).cleanupIncomplete)
        } finally {
            latch2.countDown()
            pollOutstandingTo(0)
        }
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun deniedCarriesCleanupIncompleteWhenSuDeniedPlusLingering() {
        // B4（owner P2-7）：su 前綴可仿造，不再斷言 Denied，一律 Failed；
        // 清理旗標穿透語義不變（Failed 仍攜 flag）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val latch = CountDownLatch(1)
        val runner = makeRoot { BlockedStdinDeniedProcess(latch) }
        try {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 5_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            b as ElevatedBinaryResult.Failed
            assertTrue("Failed must carry flag", b.cleanupIncomplete)
        } finally {
            latch.countDown()
            pollOutstandingTo(0)
        }
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    // ---- 3. isSuDenied 負向：內層 EACCES 無前綴 → Failed 非 Denied ----

    @Test fun innerEaccesWithoutSuPrefixMapsToFailed() {
        val runner = makeRoot {
            FakeProcess(
                ByteArray(0),
                "cat: /data/misc/x: EACCES (permission denied)".toByteArray(),
                1,
            )
        }
        val b = runner.runBinary(ElevatedRequest(listOf("cat", "/data/data/files/x.txt"), "P2-T"), 2_000L)
        // 內層 EACCES 無 su: 前綴 → Failed，非 Denied。
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        assertFalse((b as ElevatedBinaryResult.Failed).cleanupIncomplete)
    }

    // ---- 4. quoteArg internal 同包可見 ----

    @Test fun quoteArgInternalStillAccessibleInPackage() {
        assertEquals("dumpsys", RootSuRunner.quoteArg("dumpsys"))
        assertEquals("'a b'", RootSuRunner.quoteArg("a b"))
    }

    // ---- 5. destroy 對稱：缺方法 Fake 不抛 ----

    /** 缺 destroy 方法的遠端：僅 streams/wait/exit，無 destroy。 */
    private class NoDestroyRemote {
        fun getInputStream(): InputStream = ByteArrayInputStream("hi".toByteArray())
        fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        fun waitFor(): Int = 0
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        fun exitValue(): Int = 0
    }

    @Test fun destroyMissingMethodBestEffortNoThrow() {
        val wrapped = ShizukuRemoteProcess.wrap(NoDestroyRemote())
        // 缺方法即 best-effort 吞，永不外抛（不崩）。
        wrapped.destroy()
        val back = wrapped.destroyForcibly()
        assertTrue(back === wrapped)
    }

    // ---- 6. 吞例外訊號：恒抛鉤計數翻轉且 verdict 不變 ----

    @Test fun alwaysThrowIsCancelledCountsOnceAndVerdictUnchanged() {
        // B2 P1-4：runner 層抛鉤 fail-closed（未建即視為已取消，零 spawn 回 TimedOut；
        // drain 層內抛仍折為未取消，見 `drainThrowingIsCancelledFoldsToNotCancelled`）。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedProcessIo.swallowedIsCancelledThrows.get()
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            isCancelled = { throw IllegalStateException("cancel boom") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(0, spawns)
        val after = ElevatedProcessIo.swallowedIsCancelledThrows.get()
        assertTrue("counter must flip at least once: $before->$after", after > before)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun drainThrowingIsCancelledFoldsToNotCancelled() {
        // drain 層語義不變：已建進程的輪詢中抛折為未取消（不誤殺），健康即 finished。
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val d = ElevatedProcessIo.drain(
            FakeProcess("ok".toByteArray(), ByteArray(0), 0),
            2_000L,
            { ElevatedProcessKiller.killTree(it) },
            { throw IllegalStateException("cancel boom") },
        )
        assertFalse(d.timedOut)
        assertEquals(0, d.exitCode)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun alwaysThrowKillerCountsAndTimedOutUnchanged() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val before = ElevatedProcessIo.swallowedKillerThrows.get()
        var kills = 0
        val okRunner = makeRoot { FakeProcess("ok".toByteArray(), ByteArray(0), 0) }
        // 直接覆寫 killer 為恒抛：經 drain 監督 worker 計數，仍回 TimedOut。
        val throwingRunner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                kills++
                throw IllegalStateException("kill boom")
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        // 用 throwingRunner 跑一次（hang＋恒抛 killer）：killer 抛即吞計數，仍 TimedOut。
        val b = throwingRunner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 300L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
        val after = ElevatedProcessIo.swallowedKillerThrows.get()
        assertTrue("killer counter must flip: $before->$after", after > before)
        // 原 runner 不受影響（隔離）。
        assertTrue(okRunner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 2_000L) is ElevatedBinaryResult.Ok)
        pollOutstandingTo(0)
        // 恒抛 killer 的監督 worker 仍正常完成（吞後釋放），不殘留。
        assertFalse(ElevatedCleanupTracker.isSaturated())
    }

    // ---- 7. 預設 spawn-null 早拒零觸 ----

    @Test fun rootSpawnNullPlayFlavorEarlyRejectWithoutFsTouch() {
        // spawn=null＋suExists 恒抛：PLAY 早拒應在 su 解析前返回，不觸 FS。
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            flavor = Flavor.PLAY,
            spawn = null,
            suExists = { throw AssertionError("must not touch FS") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertTrue((b as ElevatedBinaryResult.Denied).message.contains("github-only"))
    }

    @Test fun rootSpawnNullBlankReasonEarlyRejectWithoutFsTouch() {
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            flavor = Flavor.GITHUB,
            spawn = null,
            suExists = { throw AssertionError("must not touch FS") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf(""), "P2-T"), 2_000L)
        // 空 argv 即 validate 拒（不觸 suExists）；另測 blank reason。
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        val b2 = runner.runBinary(ElevatedRequest(listOf("id"), "   "), 2_000L)
        assertTrue("$b2", b2 is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BAD_ARGUMENT, (b2 as ElevatedBinaryResult.Denied).reason)
    }

    @Test fun shizukuSpawnNullPlayFlavorEarlyRejectWithoutBinderTouch() {
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            flavor = Flavor.PLAY,
            pingBinder = { throw AssertionError("must not touch Binder") },
            spawn = null,
            status = { throw AssertionError("must not touch Binder") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertTrue((b as ElevatedBinaryResult.Denied).message.contains("github-only"))
    }

    @Test fun shizukuSpawnNullBlankReasonEarlyRejectWithoutBinderTouch() {
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            flavor = Flavor.GITHUB,
            pingBinder = { throw AssertionError("must not touch Binder") },
            spawn = null,
            status = { throw AssertionError("must not touch Binder") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "   "), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BAD_ARGUMENT, (b as ElevatedBinaryResult.Denied).reason)
    }

    // ---- 8. 小修 ----

    @Test fun toStringPrintsSizeNotBytes() {
        val ok = ElevatedBinaryResult.Ok("secret-bytes".toByteArray(), ByteArray(0), 0, false)
        val s = ok.toString()
        assertTrue("toString=$s", s.contains("stdout.size=12"))
        assertTrue("toString must not leak bytes: $s", !s.contains("secret-bytes"))
        // ByteArray 內容字串不得出現（[B@ 亦不應出現位元組轉儲）。
        val timed = ElevatedBinaryResult.TimedOut("abc".toByteArray(), ByteArray(0), 100L, false)
        val ts = timed.toString()
        assertTrue("toString=$ts", ts.contains("partialStdout.size=3"))
        assertTrue("toString=$ts", !ts.contains("abc"))
    }

    @Test fun toNanosOverflowClampedNotHang() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        // Long.MAX 不懸掛：健康進程應快速回 Ok（非即時超時 TimedOut）。
        val start = System.currentTimeMillis()
        val d = ElevatedProcessIo.drain(
            FakeProcess("ok".toByteArray(), ByteArray(0), 0),
            Long.MAX_VALUE,
        )
        val elapsed = System.currentTimeMillis() - start
        assertFalse(d.timedOut)
        assertEquals(0, d.exitCode)
        assertTrue("elapsed=$elapsed", elapsed < 5_000L)
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun overflowStillOkTruncatedFlagLocked() {
        // 通用 70KB 溢出：成功讀取仍回 Ok(truncated=true)，flag false（無殘留）。
        val big = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
        for ((name, runner) in listOf(
            "root" to makeRoot { FakeProcess(big, ByteArray(0), 0) },
            "shizuku" to makeShizuku { FakeProcess(big, ByteArray(0), 0) },
        )) {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 2_000L)
            assertTrue("$name $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name must truncate", b.truncated)
            assertFalse("$name clean overflow must not flag", b.cleanupIncomplete)
        }
    }

    @Test fun exitValueThrowMapsToFailedNotTimedOut() {
        // finished 但 exitValue 抛 → exit -1＋timedOut false → runner Failed（非 TimedOut）。
        val proc = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = throw IllegalStateException("exit drift")
            override fun destroy() = Unit
            override fun destroyForcibly(): Process = this
        }
        val d = ElevatedProcessIo.drain(proc, 2_000L)
        assertFalse(d.timedOut)
        assertEquals(-1, d.exitCode)
        // 經 runner 即 Failed（exit!=0 分支），非 TimedOut。
        val runner = makeRoot { proc }
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P2-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
    }

    @Test fun isScreenshotArgvCommonCoversVariants() {
        assertTrue(ElevatedRunnerCommon.isScreenshotArgv(listOf("screencap", "-p")))
        assertTrue(ElevatedRunnerCommon.isScreenshotArgv(listOf("cat", "/dev/graphics/fb0")))
        assertFalse(ElevatedRunnerCommon.isScreenshotArgv(listOf("screencap")))
        assertFalse(ElevatedRunnerCommon.isScreenshotArgv(listOf("screencap", "-p", "extra")))
        assertFalse(ElevatedRunnerCommon.isScreenshotArgv(listOf("cat", "/dev/graphics/fb0", "extra")))
        assertFalse(ElevatedRunnerCommon.isScreenshotArgv(listOf("cat", "/sdcard/x.png")))
        assertFalse(ElevatedRunnerCommon.isScreenshotArgv(listOf("id")))
        // 兩 runner 共用同一判定：截圖走 8M 軌（70KB 通過），變體走通用軌（70KB 截斷）。
        val png70 = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
        for (make in listOf(::makeRoot, ::makeShizuku)) {
            val screen = make { FakeProcess(png70, ByteArray(0), 0) }
                .runBinary(ElevatedRequest(listOf("screencap", "-p"), "P2-T"), 2_000L)
            assertTrue("$screen", screen is ElevatedBinaryResult.Ok)
            assertFalse((screen as ElevatedBinaryResult.Ok).truncated)
            // 變體 argv（多一參）走通用軌：同 70KB 即截斷。
            val variant = make { FakeProcess(png70, ByteArray(0), 0) }
                .runBinary(ElevatedRequest(listOf("screencap", "-p", "extra"), "P2-T"), 2_000L)
            // "screencap -p extra" 仍過 validate？若被拒則為 Denied（同樣非截圖軌）；
            // 此處只斷非截圖軌語義：若為 Ok 則必截斷，若為 Denied 亦證明未走截圖軌。
            if (variant is ElevatedBinaryResult.Ok) {
                assertTrue("variant must truncate on generic track", variant.truncated)
            } else {
                assertTrue("$variant", variant is ElevatedBinaryResult.Denied)
            }
        }
    }

    // ---- 9. caller 中斷級聯（drain 層語義，非 runner 應 spawn） ----
    //
    // B2 P1-4 分工 pin：本用例僅斷 drain 層不吞中斷（入口清除＋健康收尾＋出口恢復），
    // 不代表 runner 層已取消應建子進程——runner 層已取消一律零 spawn 回 TimedOut
    // （見新 B2 P1-4 取消測試 `...PreSpawnCancel...`），兩層語義分開，不衝突。

    @Test fun drainPresetCallerInterruptClearedAndRestored_healthDrainsClean_doesNotImplyRunnerShouldSpawn() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        Thread.currentThread().interrupt()
        try {
            val d = ElevatedProcessIo.drain(
                FakeProcess("ok".toByteArray(), ByteArray(0), 0),
                2_000L,
            )
            // 預置中斷已在入口清除：健康進程仍 finished（非超時），無殘留。
            assertFalse(d.timedOut)
            assertEquals(0, d.exitCode)
            pollOutstandingTo(0)
            assertFalse(ElevatedCleanupTracker.isSaturated())
            assertFalse(ElevatedCleanupTracker.isBreakerOpen())
        } finally {
            // 出口已恢復一次，此處清除殘留中斷，避免污染後續測試。
            Thread.interrupted()
        }
        // 清除後全域須乾淨。
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }
}
