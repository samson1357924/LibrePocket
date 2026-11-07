package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R1-A 雙軌容量測試（Issue #136 R1-A，owner P1-2）：
 * 截圖二進位 8M 軌 vs 通用命令 64K/512K 軌。
 *
 * 全 fake（假進程＋假狀態），零真機、零 su、零 binder。
 * 9M 大陣列僅配置一次（見 [screenshotOver8MLargeTruncatedToNull]），避免 CI OOM。
 */
class ElevatedRunnerScreenshotCapacityTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    /**
     * 終審殘留隔離（Phase 1 P1-1 模式，Round-2 Phase A 補）：每用例首尾歸零
     * consecutive＋輪詢收斂 outstanding/active，避免同 JVM 內熔斷污染後測。
     *
     * Hermetic fixture：暫存 `screencap`＋`cat`＋`id`＋`systemOwned`
     * （5 項 screencap 失敗皆 `SEARCH_DIR_UNTRUSTED` 在先；生產門零變更）。
     */
    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUpBreakerIsolation() {
        hermeticExec = HermeticExecFixture("cap-hermetic", "screencap", "cat", "id")
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

    private fun shizukuRunner(spawn: (List<String>) -> Process) = ShizukuShellRunner(
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

    private fun rootRunner(spawn: (List<String>) -> Process) = RootSuRunner(
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

    @Test fun screenshotCapacityConstantsAre8M() {
        assertEquals(8 * 1024 * 1024, ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES)
        assertEquals(8 * 1024 * 1024, ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES)
    }

    /** Phase 4 P2-4: patterned screenshot arrays patch a minimal IHDR header
      * (len=13 + "IHDR" + w/h=1) at offset 8 so they pass the strengthened gate;
      * remaining pattern bytes are untouched. CRC is not verified by the gate. */
    private fun patchScreenshotIhdr(target: ByteArray) {
        byteArrayOf(
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x01,
        ).copyInto(target, 8)
    }

    @Test fun screenshot70KBPassesBothRunners() {
        // 70KB > 通用 64K：通用軌必截斷，截圖軌必須通過（Ok 非 truncated）。
        // B4：screencap 側須過 PNG 輕量門，故首 8B 置 signature、尾 12B 置 IEND。
        val png = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
        RootFramebufferCapture.PNG_SIGNATURE.copyInto(png, 0)
        patchScreenshotIhdr(png)
        byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        ).copyInto(png, png.size - 12)
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(png, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(png, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "R1A-T"), 2_000L)
            assertTrue("$name 70KB screencap -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name truncated=${b.truncated}", !b.truncated)
            assertTrue("$name content", b.stdout.contentEquals(png))
            // capture 不回 null（完整幀可摘要）。
            val audit = PrivilegeAuditLog()
            val bytes = RootFramebufferCapture.screencapViaBinary(runner, audit, "R1A-T", 2_000L)()
            assertTrue("$name capture null", bytes != null && bytes.contentEquals(png))
        }
    }

    @Test fun screenshot600KBPassesBothRunnersFb0() {
        // 600KB > 通用硬讀 512K：通用軌必截斷，截圖軌（fb0）必須通過。
        val fb = ByteArray(600 * 1024) { ((it * 31) and 0xFF).toByte() }
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(fb, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(fb, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("cat", "/dev/graphics/fb0"), "R1A-T"), 2_000L)
            assertTrue("$name 600KB fb0 -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name truncated=${b.truncated}", !b.truncated)
            assertTrue("$name content", b.stdout.contentEquals(fb))
            val audit = PrivilegeAuditLog()
            val bytes = RootFramebufferCapture.fb0ViaBinary(runner, audit, "R1A-T", 2_000L)()
            assertTrue("$name capture null", bytes != null && bytes.contentEquals(fb))
        }
    }

    @Test fun screenshot8MExactPassesBothRunners() {
        // 恰 8M==硬讀上限：pipe 未溢出（閾值==硬讀靠溢出位區分恰過／超限），
        // truncate(8M, 8M) 不截斷 → Ok(truncated=false)＋capture 非 null（完整幀可摘要）。
        // 單一 8M 陣列兩 runner 共用（不重配，復大陣列模式）。
        // B4：screencap 側須過 PNG 輕量門，故首 8B 置 signature、尾 12B 置 IEND。
        val exact = ByteArray(8 * 1024 * 1024) { (it and 0xFF).toByte() }
        RootFramebufferCapture.PNG_SIGNATURE.copyInto(exact, 0)
        patchScreenshotIhdr(exact)
        byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        ).copyInto(exact, exact.size - 12)
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(exact, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(exact, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "R1A-T"), 5_000L)
            assertTrue("$name 8M exact -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertFalse("$name must not truncate", b.truncated)
            assertTrue("$name content", b.stdout.contentEquals(exact))
            val audit = PrivilegeAuditLog()
            val bytes = RootFramebufferCapture.screencapViaBinary(runner, audit, "R1A-T", 5_000L)()
            assertTrue("$name capture must be non-null", bytes != null && bytes.contentEquals(exact))
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }

    @Test fun screenshot8MPlusOneTruncatesToNull() {
        // 8M+1：pipe 溢出一位（超硬讀即丟棄，溢出位→truncated）→ Ok(truncated=true)＋
        // capture null（呼叫方降級手動截圖；截斷部分幀不摘要）。單一 8M+1 陣列兩 runner 共用。
        val over = ByteArray(8 * 1024 * 1024 + 1) { (it and 0xFF).toByte() }
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(over, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(over, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "R1A-T"), 5_000L)
            assertTrue("$name 8M+1 -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name must truncate", b.truncated)
            val audit = PrivilegeAuditLog()
            assertNull(
                "$name capture must be null",
                RootFramebufferCapture.screencapViaBinary(runner, audit, "R1A-T", 5_000L)(),
            )
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }

    @Test fun screenshotOver8MLargeTruncatedToNull() {
        // >8M 唯一大配置：9M 假位元組僅一次，兩 runner 共用同一陣列（不重配）。
        val big = ByteArray(9 * 1024 * 1024)
        // 填少許 PNG 頭，證明是二進位（內容不影響容量判定）。
        big[0] = 0x89.toByte()
        big[1] = 0x50
        big[2] = 0x4E
        big[3] = 0x47
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(big, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(big, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "R1A-T"), 5_000L)
            assertTrue("$name 9M -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name must truncate", b.truncated)
            // capture：Ok.truncated 即 null（呼叫方降級手動截圖），審計仍記 OK。
            val audit = PrivilegeAuditLog()
            assertNull(
                "$name capture must be null",
                RootFramebufferCapture.screencapViaBinary(runner, audit, "R1A-T", 5_000L)(),
            )
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }

    @Test fun screenshotLargeStderrTruncatedToNull() {
        // U2-B stderr 獨立小 cap（owner P2）：截圖 stdout 小（70KB，stdout 軌
        // 通過），stderr 1MB（>通用硬讀 512K）→ err 溢出即 raw.truncated →
        // Ok.truncated → capture null。舊碼 stderr 同 stdout 8M cap 時 1MB
        // 不截斷；本測試鎖定 stderr 獨立 cap 生效。
        // 記憶體：1MB 單陣列，兩 runner 共用（不重配）。
        // B4：stdout 70KB 須過 PNG 輕量門（本用例 trunc 於 stderr，PNG 門不影響
        // verdict，但置合法 PNG 保語義一致）。
        val png = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
        RootFramebufferCapture.PNG_SIGNATURE.copyInto(png, 0)
        patchScreenshotIhdr(png)
        byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        ).copyInto(png, png.size - 12)
        val bigErr = ByteArray(1 * 1024 * 1024) { 0x45.toByte() }
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(png, bigErr, 0) },
            "root" to rootRunner { _ -> FakeProcess(png, bigErr, 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "U2B-T"), 5_000L)
            assertTrue("$name 1MB stderr -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name stderr must truncate", b.truncated)
            // stdout 本身完整（70KB <8M），stderr 被切到通用軌 64K。
            assertTrue("$name stdout intact", b.stdout.contentEquals(png))
            assertEquals("$name stderr capped at 64K", ShellPolicy.MAX_OUTPUT_BYTES, b.stderr.size)
            // capture：Ok.truncated 即 null（呼叫方降級手動截圖），審計仍記 OK。
            val audit = PrivilegeAuditLog()
            assertNull(
                "$name capture must be null",
                RootFramebufferCapture.screencapViaBinary(runner, audit, "U2B-T", 5_000L)(),
            )
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }

    @Test fun generic65KBTruncatesByMarkWithoutPipeOverflow() {
        // 65KB：超通用 64K mark（truncated 置位）但未超 512K 硬讀（pipe 溢出不置位：
        // clean overflow 無殘留即 flag false；stdout 被切到 64K）。雙 runner。
        val over64k = ByteArray(65 * 1024) { (it and 0xFF).toByte() }
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(over64k, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(over64k, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "R1A-T"), 2_000L)
            assertTrue("$name generic 65KB -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name must truncate (64K mark)", b.truncated)
            assertEquals(ShellPolicy.MAX_OUTPUT_BYTES, b.stdout.size)
            assertFalse("$name clean mark must not flag", b.cleanupIncomplete)
        }
        // drain 層正交：65KB 未觸 pipe 溢出（Drained.truncated=false；64K mark 由
        // runner 側 ShellPolicy.truncate 置位，見上 runner 斷言）。
        val d = ElevatedProcessIo.drain(
            FakeProcess(over64k, ByteArray(0), 0),
            2_000L,
        )
        assertFalse(d.timedOut)
        assertFalse("65KB must not trip pipe overflow", d.truncated)
        assertFalse(d.readFailed)
    }

    @Test fun generic512KExactTruncatesByMarkWithoutPipeOverflow() {
        // 恰 512K==硬讀：pipe 溢出不置位（恰上限通過），但 64K mark 必截斷 →
        // Ok(truncated=true)，stdout 64K。雙 runner 共用單一 512K 陣列。
        val exact = ByteArray(512 * 1024) { (it and 0xFF).toByte() }
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(exact, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(exact, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "R1A-T"), 2_000L)
            assertTrue("$name generic 512K exact -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name must truncate (64K mark)", b.truncated)
            assertEquals(ShellPolicy.MAX_OUTPUT_BYTES, b.stdout.size)
        }
        val d = ElevatedProcessIo.drain(
            FakeProcess(exact, ByteArray(0), 0),
            2_000L,
        )
        assertFalse(d.timedOut)
        assertFalse("512K exact must not trip pipe overflow", d.truncated)
    }

    @Test fun drainOutErrOverflowOrthogonal() {
        // drain 直測 out/err 溢出正交（Drained.truncated = 任一流溢出 OR）：
        // - out 600KB（超 512K）＋err 空 → truncated（out 溢出）；
        // - out 空＋err 600KB → truncated（err 溢出，與 out 無關）；
        // - 截圖軌 out 70KB＋err 1MB（err 超 512K 小 cap）→ truncated（err 溢出正交）；
        // - 截圖軌 out 70KB＋err 空 → 非 truncated（任一流皆未溢出）。
        // （64K mark 截斷由 runner 側置位，不在本層；本層只斷 pipe 溢出位。）
        val out600k = ByteArray(600 * 1024) { (it and 0xFF).toByte() }
        val err600k = ByteArray(600 * 1024) { 0x45.toByte() }
        val outOverflow = ElevatedProcessIo.drain(FakeProcess(out600k, ByteArray(0), 0), 2_000L)
        assertTrue(outOverflow.truncated)
        val errOverflow = ElevatedProcessIo.drain(FakeProcess(ByteArray(0), err600k, 0), 2_000L)
        assertTrue(errOverflow.truncated)
        val png70k = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
        val err1m = ByteArray(1 * 1024 * 1024) { 0x45.toByte() }
        val shotErrOverflow = ElevatedProcessIo.drain(
            FakeProcess(png70k, err1m, 0),
            2_000L,
            ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES,
            ShellPolicy.HARD_READ_CAP_BYTES,
        )
        assertTrue(shotErrOverflow.truncated)
        val shotClean = ElevatedProcessIo.drain(
            FakeProcess(png70k, ByteArray(0), 0),
            2_000L,
            ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES,
            ShellPolicy.HARD_READ_CAP_BYTES,
        )
        assertFalse(shotClean.truncated)
        assertFalse(shotClean.readFailed)
    }

    @Test fun generic70KBStillTruncated() {
        // 通用命令 70KB 仍走 64K 軌：Ok.truncated==true（舊測試已覆蓋，此處鎖定不回歸）。
        val big = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(big, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(big, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "R1A-T"), 2_000L)
            assertTrue("$name generic 70KB -> $b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name generic must truncate", b.truncated)
            // 截斷上限仍為 64K（stdout 被切到 MAX_OUTPUT_BYTES）。
            assertEquals(ShellPolicy.MAX_OUTPUT_BYTES, b.stdout.size)
        }
    }
}
