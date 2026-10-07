package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * S3 提權執行器測試（BACKLOG D09）：Shizuku/Root 雙 Runner
 *（假子進程注入，不碰真實 binder/su）+ Shizuku 探測。
 *
 * - 校驗先行：拒絕時不建子進程；
 * - 白名單外僅提權通道放行，黑名單 `rm -rf` 仍拒；
 * - 超時截斷配額沿用主線語義（此處斷言配額 + 截斷標記）。
 */
class ElevatedRunnerTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private class FakeProcess(
        private val stdout: ByteArray,
        private val stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
    ) : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(stdout)
        override fun getErrorStream() = ByteArrayInputStream(stderr)
        override fun waitFor(): Int = exit
        override fun exitValue(): Int = exit
        override fun destroy() {
            destroyed = true
        }
    }

    private class SpawnCounter(
        private val stdout: ByteArray = "ok".toByteArray(),
    ) {
        var calls = 0
        var lastArgv: List<String> = emptyList()
        fun spawn(argv: List<String>): Process {
            calls++
            lastArgv = argv
            return FakeProcess(stdout)
        }
    }

    private fun shizukuRunner(
        spawn: SpawnCounter,
        quota: ShellQuota = ShellQuota(),
        bridgeGranted: Boolean = true,
    ) = ShizukuShellRunner(
        privateRoot = privateRoot,
        quota = quota,
        bridgeGranted = bridgeGranted,
        pingBinder = { true },
        spawn = spawn::spawn,
    )

    private fun rootRunner(
        spawn: SpawnCounter,
        quota: ShellQuota = ShellQuota(),
        bridgeGranted: Boolean = true,
    ) = RootSuRunner(
        privateRoot = privateRoot,
        quota = quota,
        bridgeGranted = bridgeGranted,
        spawn = spawn::spawn,
    )

    @Test fun nonWhitelistedBinaryPassesOnlyViaElevated() {
        for (runner in listOf(shizukuRunner(SpawnCounter()), rootRunner(SpawnCounter()))) {
            val r = runner.run(ElevatedRequest(listOf("dumpsys", "activity"), reasonCode = "D09-T"))
            assertTrue("$r", r is ShellResult.Ok)
        }
        val spawn = SpawnCounter()
        // Root 走 su -c 傳送層：內層 argv 原樣包覆，外層 cwd 釘死（cd + exec 雙保險）。
        rootRunner(spawn).run(ElevatedRequest(listOf("dumpsys", "activity"), reasonCode = "D09-T"))
        assertEquals(
            listOf("su", "-c", "cd $privateRoot && exec dumpsys activity"),
            spawn.lastArgv,
        )
    }

    @Test fun blacklistDeniedWithoutSpawnOnBothRunners() {
        for (argv in listOf(listOf("rm", "-rf", "/"), listOf("su", "-c", "id"))) {
            for (factory in listOf<(SpawnCounter) -> Any>({ s -> shizukuRunner(s) }, { s -> rootRunner(s) })) {
                val spawn = SpawnCounter()
                @Suppress("UNCHECKED_CAST")
                val runner = factory(spawn) as dev.librepocket.shell.ElevatedShellRunner
                val r = runner.run(ElevatedRequest(argv, reasonCode = "D09-T"))
                assertTrue("$argv -> $r", r is ShellResult.Denied)
                assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
                assertEquals(0, spawn.calls)
            }
        }
    }

    @Test fun reasonCodeRequiredAndQuotaApplies() {
        val spawn = SpawnCounter()
        val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        val runner = shizukuRunner(spawn, quota)
        val noReason = runner.run(ElevatedRequest(listOf("id"), reasonCode = ""))
        assertTrue("$noReason", noReason is ShellResult.Denied)
        assertEquals(0, spawn.calls)
        assertTrue(runner.run(ElevatedRequest(listOf("id"), reasonCode = "D09-T")) is ShellResult.Ok)
        val over = runner.run(ElevatedRequest(listOf("id"), reasonCode = "D09-T"))
        assertTrue("$over", over is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (over as ShellResult.Denied).reason)
        assertEquals(1, spawn.calls)
    }

    @Test fun binderDownFallsBackWithoutSpawn() {
        val spawn = SpawnCounter()
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { false },
            spawn = spawn::spawn,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), reasonCode = "D09-T"))
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(0, spawn.calls)
    }

    @Test fun outputTruncationFlagged() {
        val big = ByteArray(dev.librepocket.shell.ShellPolicy.MAX_OUTPUT_BYTES + 8)
        val spawn = SpawnCounter(big)
        val r = shizukuRunner(spawn).run(ElevatedRequest(listOf("getprop"), reasonCode = "D09-T"))
        assertTrue("$r", r is ShellResult.Ok)
        assertTrue((r as ShellResult.Ok).truncated)
    }

    @Test fun quoteArgWrapsQuotesAndParensForSingleSuParse() {
        // su -c 傳送層在裝置側必經一次字串解析：安全字元直行，
        // 其餘（含引號/括號/空白）一律單引號包覆，內嵌單引號轉義。
        assertEquals("dumpsys", RootSuRunner.quoteArg("dumpsys"))
        assertEquals("/dev/graphics/fb0", RootSuRunner.quoteArg("/dev/graphics/fb0"))
        assertEquals("'a b'", RootSuRunner.quoteArg("a b"))
        assertEquals("'a(b)'", RootSuRunner.quoteArg("a(b)"))
        assertEquals("'it'\\''s'", RootSuRunner.quoteArg("it's"))
        assertEquals("'a\"b'", RootSuRunner.quoteArg("a\"b"))
    }

    // ---- Shizuku 探測：pingBinder runCatching → false ----

    @Test fun shizukuProbePing() {
        assertTrue(ShizukuProbe.ping { true })
        assertEquals(false, ShizukuProbe.ping { false })
        assertEquals(false, ShizukuProbe.ping { throw SecurityException("no binder") })
    }

    @Test fun shizukuProbeDualChannel() {
        val results = ShizukuProbe.probe(shizukuProbe = { true }, suProbe = { false })
        assertEquals(2, results.size)
        assertTrue(results.any { it.privilege == dev.librepocket.shell.Privilege.SHIZUKU && it.available })
    }
}
