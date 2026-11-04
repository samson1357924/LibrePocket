package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2-B 容量矩陣補充（owner P2-4，JVM 假位元組，零真機）：
 * 未壓縮 RGBA 1080x2400x4 ≈10.37MB 明確不支援（>8M 即 truncated→null，
 * 呼叫方降級手動截圖；文件化 fb0 上限行為）。
 *
 * 記憶體：10.5MB 大陣列僅配置一次（兩 runner 共用同一陣列，不重配；
 * 與 [ElevatedRunnerScreenshotCapacityTest.screenshotOver8MLargeTruncatedToNull]
 * 的 9M 單次配置模式一致，避免 CI OOM）。
 */
class ScreenshotCapacityTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private fun shizukuRunner(spawn: (List<String>) -> Process) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = true,
        pingBinder = { true },
        spawn = spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun rootRunner(spawn: (List<String>) -> Process) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = true,
        spawn = spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
    )

    @Test fun rgba1080x2400Over8MTruncatedToNull() {
        // 未壓縮 RGBA 1080*2400*4 = 10_368_000（≈10.37MB），以 10.5MiB 假位元組
        // 覆蓋之（留 margin，皆 >8M）：唯一大配置，兩 runner 共用同一陣列。
        val big = ByteArray((10.5 * 1024 * 1024).toInt())
        big[0] = 0x89.toByte()
        big[1] = 0x50
        big[2] = 0x4E
        big[3] = 0x47
        val runners: List<Pair<String, BinaryElevatedRunner>> = listOf(
            "shizuku" to shizukuRunner { _ -> FakeProcess(big, ByteArray(0), 0) },
            "root" to rootRunner { _ -> FakeProcess(big, ByteArray(0), 0) },
        )
        for ((name, runner) in runners) {
            val b = runner.runBinary(ElevatedRequest(listOf("cat", "/dev/graphics/fb0"), "S2B-T"), 5_000L)
            assertTrue("$name 10.5M fb0 -> truncated=$b", b is ElevatedBinaryResult.Ok)
            b as ElevatedBinaryResult.Ok
            assertTrue("$name must truncate", b.truncated)
            // capture：Ok.truncated 即 null（呼叫方降級手動截圖），審計仍記 OK。
            val audit = PrivilegeAuditLog()
            assertNull(
                "$name capture must be null",
                RootFramebufferCapture.fb0ViaBinary(runner, audit, "S2B-T", 5_000L)(),
            )
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }
}
