package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 runner 修正測試（Issue #136 B2）：
 * su exit 語義／盡力整組殺 best-effort＋cancel 鉤／Shizuku 包裝／二進位／可信 su／狀態門。
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Failed)
    }

    // ---- 2. process-group kill best-effort＋cancel 鉤 ----

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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
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

    @Test fun suResolverExistsThrowFoldsToNull() {
        // 存在性檢查抛＝缺席（fail-closed），整表掃完回 null，不外抛。
        assertEquals(null, SuBinaryResolver.resolve { throw SecurityException("denied") })
        assertEquals(null, SuBinaryResolver.resolve { throw RuntimeException("i/o") })
    }

    @Test fun suExistsThrowFoldsToUnavailable() {
        // RootSuRunner 側 suExists 抛→解析收斂為 null→typed Failed，不崩且零 spawn。
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray())
            },
            suExists = { throw SecurityException("denied") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(0, spawns)
        val rb = runner.runBinary(ElevatedRequest(listOf("id"), "B2-T"), 2_000L)
        assertTrue("$rb", rb is ElevatedBinaryResult.Failed)
        assertEquals(0, spawns)
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
                grantCheck = GrantFixtures.allowAlways,
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
            grantCheck = GrantFixtures.allowAlways,
        )
        assertTrue(runner.run(ElevatedRequest(listOf("id"), "B2-T"), 2_000L) is ShellResult.Ok)
        assertEquals(1, spawns)
    }

    // ---- 5. 二進位邊界：超時 partial／capture 回落 null／大小寫 ----

    @Test fun binaryTimedOutPreservesPartialBytes() {
        val partial = "partial-bytes".toByteArray()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess(partial, ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "B2-T"), 120L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        val timedOut = b as ElevatedBinaryResult.TimedOut
        assertTrue(timedOut.partialStdout.contentEquals(partial))
        assertTrue(timedOut.partialStderr.isEmpty())
        // 文字相容殼同樣攜帶 partial（lossy 解碼，純 ASCII 往返無損）。
        val text = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 120L)
        assertTrue("$text", text is ShellResult.TimedOut)
        assertEquals("partial-bytes", (text as ShellResult.TimedOut).partialStdout)
    }

    /** 定值二進位 runner：capture 回落語義專用替身。 */
    private class CannedBinaryRunner(
        private val result: ElevatedBinaryResult,
    ) : BinaryElevatedRunner {
        override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
            result.toShellResult()
        override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult =
            result
    }

    @Test fun binaryDeniedAndFailedYieldNull() {
        // capture 側：非 Ok（Denied/Failed/TimedOut）一律回 null，呼叫方降級手動截圖。
        val audit = dev.librepocket.shell.PrivilegeAuditLog()
        for (result in listOf(
            ElevatedBinaryResult.Denied(ShellDeny.NOT_WHITELISTED, "nope"),
            ElevatedBinaryResult.Failed("boom"),
            ElevatedBinaryResult.TimedOut(ByteArray(0), ByteArray(0), 100L, false),
        )) {
            val runner = CannedBinaryRunner(result)
            assertNull("$result", RootFramebufferCapture.screencapViaBinary(runner, audit, "B2-T", 1_000L)())
            assertNull("$result", RootFramebufferCapture.fb0ViaBinary(runner, audit, "B2-T", 1_000L)())
        }
    }

    @Test fun suDeniedCaseInsensitive() {
        // 全大寫拒絕訊號仍命中（Locale.ROOT 折疊，不隨裝置語系漂移）。
        assertTrue(isSuDenied("PERMISSION DENIED".toByteArray(), 1))
        assertTrue(isSuDenied("Su: Permission Denied".toByteArray(), 1))
        assertTrue(isSuDenied("OPERATION NOT PERMITTED".toByteArray(), 1))
        assertTrue(isSuDenied("REQUEST REJECTED".toByteArray(), 1))
    }

    // ---- P3-A 補強：超時/cancel/注入異常/門禁邊界（只斷 verdict＋計數＋位元組，不斷耗時） ----

    @Test fun shizukuTimeoutKillsAndReturnsBinaryTimedOut() {
        val partial = "part-bytes".toByteArray()
        var kills = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess(partial, ByteArray(0), 0, hang = true) },
            status = { ShizukuStatus.Available(15) },
            killer = {
                kills++
                it.destroy()
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 150L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
        b as ElevatedBinaryResult.TimedOut
        assertEquals(150L, b.timeoutMs)
        assertTrue(b.partialStdout.contentEquals(partial))
    }

    @Test fun shizukuCancelKillsAndUnblocks() {
        var kills = 0
        var polls = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            status = { ShizukuStatus.Available(15) },
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = { ++polls > 2 },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 10_000L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
        assertTrue("polls=$polls", polls > 2)
    }

    @Test fun isCancelledThrowFoldsToNotCancelled() {
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess("ok".toByteArray(), ByteArray(0), 0) },
            status = { ShizukuStatus.Available(15) },
            isCancelled = { throw IllegalStateException("cancel boom") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = shizuku.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Ok)

        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("ok".toByteArray(), ByteArray(0), 0) },
            suBinary = "/system/bin/su",
            isCancelled = { throw IllegalStateException("cancel boom") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = root.run(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Ok)
    }

    @Test fun killerThrowStillReturnsTimedOut() {
        var kills = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                kills++
                throw IllegalStateException("kill boom")
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 150L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
    }

    @Test fun rootQuotaExceededDeniesWithoutSpawn() {
        var spawns = 0
        val quota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        assertTrue(quota.tryAcquire())
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            quota = quota,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (r as ShellResult.Denied).reason)
        assertEquals(0, spawns)
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.QUOTA_EXCEEDED, (b as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns)
    }

    @Test fun rootBlankReasonDeniedWithoutSpawn() {
        for (reason in listOf("", "   ")) {
            var spawns = 0
            val runner = RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = true,
                spawn = {
                    spawns++
                    FakeProcess("ok".toByteArray(), ByteArray(0), 0)
                },
                suBinary = "/system/bin/su",
                grantCheck = GrantFixtures.allowAlways,
            )
            val r = runner.run(ElevatedRequest(listOf("id"), reason), 2_000L)
            assertTrue("reason='$reason' -> $r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BAD_ARGUMENT, (r as ShellResult.Denied).reason)
            assertEquals(0, spawns)
        }
    }

    @Test fun rootEmptyArgvDenied() {
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(emptyList(), "P3-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, spawns)
        val b = runner.runBinary(ElevatedRequest(emptyList(), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertEquals(0, spawns)
    }

    @Test fun shizukuSpawnThrowMapsToFailedRedacted() {
        val secret = "boom bearer ABCDEFGHIJ123456 ip=10.9.9.9"
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { throw IllegalStateException(secret) },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val msg = (b as ElevatedBinaryResult.Failed).message
        assertTrue("msg=$msg", msg.contains("spawn failed"))
        assertTrue("msg=$msg", !msg.contains("ABCDEFGH"))
        assertTrue("msg=$msg", !msg.contains("10.9.9.9"))
    }

    @Test fun rootSpawnThrowMapsToFailedRedacted() {
        val secret = "boom bearer ABCDEFGHIJ123456 ip=10.9.9.9"
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { throw IllegalStateException(secret) },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val msg = (b as ElevatedBinaryResult.Failed).message
        assertTrue("msg=$msg", msg.contains("spawn failed"))
        assertTrue("msg=$msg", !msg.contains("ABCDEFGH"))
        assertTrue("msg=$msg", !msg.contains("10.9.9.9"))
    }

    @Test fun statusThrowFoldsToBinderDownWithoutSpawn() {
        var spawns = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = { throw IllegalStateException("status boom") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (r as ShellResult.Denied).reason)
        assertTrue(r.message.contains(ShizukuStatus.REASON_BINDER_DOWN))
        assertEquals(0, spawns)
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (b as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns)
    }

    @Test fun playFlavorCrossDomainDenied() {
        val argv = listOf("cat", "/sdcard/Download/x.txt")
        var shizukuSpawns = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            flavor = Flavor.PLAY,
            pingBinder = { true },
            spawn = {
                shizukuSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sr = shizuku.run(ElevatedRequest(argv, "P3-T"), 2_000L)
        assertTrue("$sr", sr is ShellResult.Denied)
        assertEquals(0, shizukuSpawns)

        var rootSpawns = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            flavor = Flavor.PLAY,
            spawn = {
                rootSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val rr = root.run(ElevatedRequest(argv, "P3-T"), 2_000L)
        assertTrue("$rr", rr is ShellResult.Denied)
        assertEquals(0, rootSpawns)
    }

    @Test fun bridgeGrantedFalseDeniesWithoutSpawn() {
        val argv = listOf("cat", "/sdcard/Download/x.txt")
        var shizukuSpawns = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = false,
            flavor = Flavor.GITHUB,
            pingBinder = { true },
            spawn = {
                shizukuSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sr = shizuku.run(ElevatedRequest(argv, "P3-T"), 2_000L)
        assertTrue("$sr", sr is ShellResult.Denied)
        assertEquals(0, shizukuSpawns)

        var rootSpawns = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = false,
            flavor = Flavor.GITHUB,
            spawn = {
                rootSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val rr = root.run(ElevatedRequest(argv, "P3-T"), 2_000L)
        assertTrue("$rr", rr is ShellResult.Denied)
        assertEquals(0, rootSpawns)
    }

    @Test fun suUntrustedExplicitPathFailsWithoutSpawn() {
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = {
                spawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/tmp/evil/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(0, spawns)
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        assertEquals(0, spawns)
    }

    @Test fun suAutoResolvePrefersFirstInTableOrder() {
        var lastArgv: List<String> = emptyList()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { argv ->
                lastArgv = argv
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suExists = { it == "/system/bin/su" || it == "/sbin/su" },
            grantCheck = GrantFixtures.allowAlways,
        )
        assertTrue(runner.run(ElevatedRequest(listOf("id"), "P3-T"), 2_000L) is ShellResult.Ok)
        assertEquals(RootSuProbe.SU_BINARIES[0], lastArgv[0])
        assertEquals("/system/bin/su", lastArgv[0])
    }

    @Test fun zeroTimeoutReturnsTimedOut() {
        var shizukuKills = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            status = { ShizukuStatus.Available(15) },
            killer = {
                shizukuKills++
                it.destroy()
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sb = shizuku.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 0L)
        assertTrue("$sb", sb is ElevatedBinaryResult.TimedOut)
        assertEquals(1, shizukuKills)
        assertEquals(0L, (sb as ElevatedBinaryResult.TimedOut).timeoutMs)

        var rootKills = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                rootKills++
                it.destroy()
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val rb = root.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 0L)
        assertTrue("$rb", rb is ElevatedBinaryResult.TimedOut)
        assertEquals(1, rootKills)
    }

    // ---- R3-A Job 取消（owner P2-5）：真 kotlinx.coroutines Job 驅動清理 ----

    @Test fun coroutineJobCancelDrivesCleanup() {
        // R3-A：預設 isCancelled 只看 Thread 中斷，coroutine 呼叫方須顯式傳
        // `isCancelled = { !job.isActive }`；此處以前 N 次 false 後讀 job 狀態
        // 的鉤模擬「輪詢中被取消」：hang FakeProcess 保證非超時路徑（10s 上界），
        // 第 3 次輪詢時 job.cancel()，之後鉤讀到 !isActive 即盡力殺整組 best-effort 回 TimedOut。
        // 零真機/零真掛起：hang FakeProcess 的 waitFor 切片可被 killer 立即解除。
        val job = Job()
        var polls = 0
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
            isCancelled = {
                ++polls
                if (polls == 3) job.cancel()
                !job.isActive
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "R3A-T"), 10_000L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
        assertTrue("polls=$polls", polls >= 3)
        assertTrue(job.isCancelled)

        // 同一 Job 語義走 Shizuku 側亦回 TimedOut（兩 runner 同源 drain）。
        val shizukuJob = Job()
        var shizukuPolls = 0
        var shizukuKills = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = true,
            pingBinder = { true },
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            status = { ShizukuStatus.Available(15) },
            killer = {
                shizukuKills++
                it.destroy()
            },
            isCancelled = {
                ++shizukuPolls
                if (shizukuPolls == 3) shizukuJob.cancel()
                !shizukuJob.isActive
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sb = shizuku.runBinary(ElevatedRequest(listOf("id"), "R3A-T"), 10_000L)
        assertTrue("$sb", sb is ElevatedBinaryResult.TimedOut)
        assertEquals(1, shizukuKills)
        assertTrue("shizukuPolls=$shizukuPolls", shizukuPolls >= 3)
        assertTrue(shizukuJob.isCancelled)
    }

    // ---- S2-A 真 cancel racing（owner P2-3）：跨執行緒 job.cancel()，不在 callback 內 cancel ----

    @Test fun crossThreadJobCancelRacesDrainToTimedOut() {
        // S2-A：drain 跑背景執行緒（hang FakeProcess 阻塞在 waitFor 切片），主執行緒
        // sleep 200ms 後 job.cancel()（真 Job，isCancelled 鉤僅讀 !job.isActive，
        // 不在 callback 內 cancel，跨執行緒 racing；舊測試在鉤內計數到第 N 次即 cancel
        // 屬人為同步取消，不算 racing）。斷言背景 drain 回 TimedOut＋killer 被調。
        // 有界（每路 join 上界 4s，兩路＋sleep 合計 <10s；超界即失敗），
        // 不斷言精確耗時，只斷 verdict/kill，不 flaky。
        fun runOneRound(
            runnerOf: (
                killer: (Process) -> Unit,
                isCancelled: () -> Boolean,
            ) -> BinaryElevatedRunner,
        ) {
            val job = Job()
            val kills = AtomicInteger(0)
            val runner = runnerOf(
                {
                    kills.incrementAndGet()
                    it.destroy()
                },
                { !job.isActive },
            )
            val result = AtomicReference<ElevatedBinaryResult?>()
            val bg = Thread {
                result.set(runner.runBinary(ElevatedRequest(listOf("id"), "S2A-T"), 10_000L))
            }.also { it.isDaemon = true; it.start() }
            Thread.sleep(200L)
            job.cancel()
            bg.join(4_000L)
            assertFalse("background drain should finish within bound", bg.isAlive)
            val b = result.get()
            assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
            assertEquals(1, kills.get())
            assertTrue(job.isCancelled)
        }
        runOneRound { killer, isCancelled ->
            RootSuRunner(
                privateRoot = privateRoot,
                bridgeGranted = true,
                spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
                suBinary = "/system/bin/su",
                killer = killer,
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
        runOneRound { killer, isCancelled ->
            ShizukuShellRunner(
                privateRoot = privateRoot,
                bridgeGranted = true,
                pingBinder = { true },
                spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
                status = { ShizukuStatus.Available(15) },
                killer = killer,
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }
}
