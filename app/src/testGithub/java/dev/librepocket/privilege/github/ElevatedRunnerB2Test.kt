package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 runner 修正測試（Issue #136 B2）：
 * su exit 語義／整組殺＋cancel 鉤／Shizuku 包裝／二進位／可信 su／狀態門。
 * 全 fake（假進程＋假存在映射＋假狀態），零真機、零 su、零 binder。
 */
class ElevatedRunnerB2Test {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private class FakeProcess(
        private val stdout: ByteArray,
        private val stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val hang: Boolean = false,
    ) : Process() {
        var destroyed = false
        var forciblyDestroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(stderr)
        override fun waitFor(): Int = exit
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (destroyed) return true
            if (hang) {
                try {
                    Thread.sleep(unit.toMillis(timeout))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                return destroyed
            }
            return true
        }

        override fun exitValue(): Int = exit
        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            destroyed = true
            return this
        }
    }

    // ---- 1. su exit 語義：拒絕/exit≠0 不回 Ok；stderr redacted ----

    @Test fun suDeniedStderrMapsToTypedDenied() {
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { _ ->
                spawns++
                FakeProcess(
                    stdout = ByteArray(0),
                    stderr = "su: permission denied\nip=10.1.2.3".toByteArray(),
                    exit = 1,
                )
            },
            suBinary = "/system/bin/su",
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (r as ShellResult.Denied).reason)
        assertEquals(1, spawns)
        // stderr 保留但已 redacted：私網 IP 不得原文出現。
        assertTrue(r.message.contains("10.1.2.3").not())
        assertTrue(r.message.contains("REDACTED") || r.message.contains("denied", ignoreCase = true))
    }

    @Test fun suGenericNonZeroMapsToFailedNotOk() {
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("".toByteArray(), "E: something broke".toByteArray(), 2) },
            suBinary = "/system/bin/su",
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Failed)
    }

    @Test fun suDeniedBareSuPrefixWithoutSignalIsNotDenied() {
        // 補註：bare "su: " 前綴過寬（任何 su 錯誤行都命中），只認明確拒絕關鍵字。
        // 無關鍵字者走 Failed 方向（isSuDenied=false），不誤判為 Denied。
        assertFalse(isSuDenied("su: unknown option -- foo".toByteArray(), 1))
        assertFalse(isSuDenied("su: ".toByteArray(), 1))
        assertFalse(isSuDenied("su: cannot exec".toByteArray(), 2))
    }

    @Test fun suDeniedKnownSignalsStillDenied() {
        // su 側拒絕本來就帶明確關鍵字，維持 Denied。
        assertTrue(isSuDenied("su: permission denied".toByteArray(), 1))
        assertTrue(isSuDenied("Permission denied".toByteArray(), 1))
        assertTrue(isSuDenied("su: request rejected".toByteArray(), 1))
        // exit==0 永不視為拒絕；空白 stderr 亦不視為拒絕。
        assertFalse(isSuDenied("su: permission denied".toByteArray(), 0))
        assertFalse(isSuDenied(ByteArray(0), 1))
    }

    @Test fun suBinaryMapsToFailedRedacted() {
        val secretStderr = "E: fail bearer ABCDEFGHIJ123456 ip=10.9.9.9".toByteArray()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess(ByteArray(0), secretStderr, 3) },
            suBinary = "/system/bin/su",
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val msg = (b as ElevatedBinaryResult.Failed).message
        // redactError 收斂：bearer token 與私網 IP 不得原文出現。
        assertTrue("msg=$msg", !msg.contains("ABCDEFGH"))
        assertTrue("msg=$msg", !msg.contains("10.9.9.9"))
    }

    @Test fun suZeroStillOk() {
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("ok".toByteArray(), ByteArray(0), 0) },
            suBinary = "/system/bin/su",
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(0, (r as ShellResult.Ok).exitCode)
    }

    @Test fun shizukuNonZeroMapsToFailedNotOk() {
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess("".toByteArray(), "boom".toByteArray(), 1) },
            status = { ShizukuStatus.Available(15) },
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Failed)
    }

    // ---- 2. process-group kill＋cancel 鉤 ----

    @Test fun timeoutKillsWholeGroupViaKiller() {
        var kills = 0
        var lastVictim: Process? = null
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                kills++
                lastVictim = it
                it.destroy()
            },
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 120L)
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(1, kills)
        assertTrue((lastVictim as? FakeProcess)?.destroyed == true)
    }

    @Test fun cancelHookKillsAndUnblocks() {
        var kills = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = { true },
        )
        val start = System.currentTimeMillis()
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 10_000L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(1, kills)
        // cancel 即殺，不等滿 10s（留 4s 餘量給 CI 抖動）。
        assertTrue("elapsed=$elapsed", elapsed < 4_000L)
    }

    // ---- 3. ShizukuRemoteProcess 包裝（勿直 cast）＋二進位直達 ----

    /** 非 Process 的遠端假物件：同名方法，直 cast 必 CCE。 */
    private class FakeRemote(
        private val stdout: ByteArray,
        private val stderr: ByteArray = ByteArray(0),
        private val exit: Int = 0,
    ) {
        var destroyed = false
        fun getInputStream(): InputStream = ByteArrayInputStream(stdout)
        fun getErrorStream(): InputStream = ByteArrayInputStream(stderr)
        fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        fun waitFor(): Int = exit
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        fun exitValue(): Int = exit
        fun destroy() {
            destroyed = true
        }
    }

    @Test fun shizukuRemoteWrapsWithoutCast() {
        val remote = FakeRemote("hi".toByteArray(), ByteArray(0), 0)
        // 直 cast 應失敗（證明非 Process，包裝才有意義）。
        var cce = false
        try {
            @Suppress("UNCHECKED_CAST")
            ((remote as Any) as Process)
        } catch (_: ClassCastException) {
            cce = true
        }
        assertTrue(cce)
        val wrapped = ShizukuRemoteProcess.wrap(remote)
        assertTrue(wrapped is ShizukuRemoteProcess)
        assertEquals(0, wrapped.waitFor())
        assertEquals(0, wrapped.exitValue())
        assertTrue(wrapped.waitFor(10L, TimeUnit.MILLISECONDS))
        assertEquals("hi", wrapped.inputStream.readBytes().toString(Charsets.UTF_8))
        wrapped.destroy()
        assertTrue(remote.destroyed)
    }

    @Test fun binaryChannelPreservesNonUtf8() {
        // PNG 頭＋0xFF/0x00：經 String 往返必損毀。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0xFF.toByte(), 0x00, 0x01)
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess(png, ByteArray(0), 0) },
            status = { ShizukuStatus.Available(15) },
        )
        val b = shizuku.runBinary(ElevatedRequest(listOf("screencap", "-p"), "B2-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Ok)
        assertTrue((b as ElevatedBinaryResult.Ok).stdout.contentEquals(png))
        // 舊 String 路徑已損毀（對照組：lossy 解碼再編碼 ≠ 原位元組）。
        val lossy = String(png, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        assertTrue(!lossy.contentEquals(png))

        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess(png, ByteArray(0), 0) },
            suBinary = "/system/bin/su",
        )
        val rb = root.runBinary(ElevatedRequest(listOf("screencap", "-p"), "B2-T"), 2_000L)
        assertTrue("$rb", rb is ElevatedBinaryResult.Ok)
        assertTrue((rb as ElevatedBinaryResult.Ok).stdout.contentEquals(png))
    }

    @Test fun framebufferPrefersBinaryChannel() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0xFF.toByte(), 0x00)
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess(png, ByteArray(0), 0) },
            status = { ShizukuStatus.Available(15) },
        )
        val audit = dev.librepocket.shell.PrivilegeAuditLog()
        val read = RootFramebufferCapture.screencapViaElevated(runner, audit, "B2-T", 2_000L)
        val bytes = read()!!
        assertTrue(bytes.contentEquals(png))
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
    }

    // ---- 4. su 可信絕對路徑＋狀態門先行 ----

    @Test fun suUsesTrustedAbsolutePath() {
        var lastArgv: List<String> = emptyList()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { argv ->
                lastArgv = argv
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
        )
        assertTrue(runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L) is ShellResult.Ok)
        assertTrue(lastArgv.size == 3)
        assertEquals("/system/bin/su", lastArgv[0])
        assertTrue(lastArgv[0].startsWith("/"))
    }

    @Test fun suAutoResolvePicksFirstPresent() {
        var lastArgv: List<String> = emptyList()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { argv ->
                lastArgv = argv
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suExists = { it == "/system/xbin/su" },
        )
        assertTrue(runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L) is ShellResult.Ok)
        assertEquals("/system/xbin/su", lastArgv[0])
    }

    @Test fun suMissingFailsWithoutSpawn() {
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray())
            },
            suExists = { false },
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(0, spawns)
    }

    @Test fun suResolverOnlyTrustsKnownPaths() {
        assertEquals("/system/bin/su", SuBinaryResolver.resolve { it == "/system/bin/su" })
        assertEquals(null, SuBinaryResolver.resolve { false })
        assertTrue(SuBinaryResolver.isTrusted("/system/bin/su"))
        assertTrue(!SuBinaryResolver.isTrusted("/tmp/evil/su"))
    }

    @Test fun shizukuGateBlocksSpawnWhenNotAvailable() {
        for (status in listOf(
            ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN),
            ShizukuStatus.PermissionDenied(15),
            ShizukuStatus.VersionUnsupported(10),
        )) {
            var spawns = 0
            val runner = ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = true,
                pingBinder = { true },
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray())
                },
                status = { status },
            )
            val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
            assertTrue("$status -> $r", r is ShellResult.Denied)
            assertEquals(0, spawns)
            val rb = runner.runBinary(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
            assertTrue("$status -> $rb", rb is ElevatedBinaryResult.Denied)
            assertEquals(0, spawns)
        }
    }

    @Test fun shizukuGateAvailableAllowsSpawn() {
        var spawns = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray())
            },
            status = { ShizukuStatus.Available(15) },
        )
        assertTrue(runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L) is ShellResult.Ok)
        assertEquals(1, spawns)
    }
}
