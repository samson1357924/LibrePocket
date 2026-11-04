package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun screenshotCapacityConstantsAre8M() {
        assertEquals(8 * 1024 * 1024, ElevatedProcessIo.BINARY_SCREENSHOT_MAX_BYTES)
        assertEquals(8 * 1024 * 1024, ElevatedProcessIo.BINARY_SCREENSHOT_HARD_CAP_BYTES)
    }

    @Test fun screenshot70KBPassesBothRunners() {
        // 70KB > 通用 64K：通用軌必截斷，截圖軌必須通過（Ok 非 truncated）。
        val png = ByteArray(70 * 1024) { (it and 0xFF).toByte() }
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
