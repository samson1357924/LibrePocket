package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B2 runner 修正測試（Issue #136 B2）：
 * su exit 語義／盡力整組殺 best-effort＋cancel 鉤／Shizuku 包裝／二進位／可信 su／狀態門。
 * 全 fake（假進程＋假存在映射＋假狀態），零真機、零 su、零 binder。
 */
class ElevatedRunnerB2Test {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    /**
     * 終審殘留隔離（Phase 1 P1-1）：每用例首尾歸零 consecutive＋輪詢收斂
     * outstanding/active，避免同 JVM 內熔斷污染後測。
     * （測試隔離重置，非#136-B3修復；不升級 noteClean 可見性。）
     */
    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    // ---- 1. su exit 語義：拒絕/exit≠0 不回 Ok；stderr redacted ----

    @Test fun suDeniedStderrMapsToTypedDenied() {
        // B4（owner P2-7）：`^su:` 前綴可被內層仿造，無獨立可信來源，不再斷言
        // Denied，一律 Failed（不確定失敗）；本用例即內層仿 su 行亦同形，
        // 不得回 Denied（見 suInnerSpoofPrefixIsFailedNotDenied）。
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        assertTrue("$r", r is ShellResult.Failed)
        assertEquals(1, spawns)
        // stderr 保留但已 redacted：私網 IP 不得原文出現。
        val msg = (r as ShellResult.Failed).message
        assertTrue(msg.contains("10.1.2.3").not())
        assertTrue(msg.contains("REDACTED") || msg.contains("denied", ignoreCase = true))
    }

    @Test fun suGenericNonZeroMapsToFailedNotOk() {
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        // P2 精確化：只認 `^su:` 前綴行內命中 denied/rejected/eacces/eperm。
        assertTrue(isSuDenied("su: permission denied".toByteArray(), 1))
        assertTrue(isSuDenied("su: request rejected".toByteArray(), 1))
        assertTrue(isSuDenied("su: EACCES (permission denied)".toByteArray(), 1))
        assertTrue(isSuDenied("  su: permission denied".toByteArray(), 1))
        // exit==0 永不視為拒絕；空白 stderr 亦不視為拒絕。
        assertFalse(isSuDenied("su: permission denied".toByteArray(), 0))
        assertFalse(isSuDenied(ByteArray(0), 1))
        // P2 負向：無前綴即使含關鍵字亦不算拒絕（內層輸出）。
        assertFalse(isSuDenied("Permission denied".toByteArray(), 1))
        assertFalse(isSuDenied("OPERATION NOT PERMITTED".toByteArray(), 1))
        assertFalse(isSuDenied("REQUEST REJECTED".toByteArray(), 1))
        assertFalse(isSuDenied("cat: /x: EACCES".toByteArray(), 1))
    }

    @Test fun suBinaryMapsToFailedRedacted() {
        val secretStderr = "E: fail bearer ABCDEFGHIJ123456 ip=10.9.9.9".toByteArray()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        var kills = 0
        var lastVictim: Process? = null
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        // P2-1 可恢復 timeout 不斷 breaker：clean killer 即 outstanding 歸零且不熔斷。
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun cancelHookKillsAndUnblocks() {
        // B2 P1-4：常量已取消在入口即零 spawn（不建進程故不調 killer），仍回 TimedOut
        // 且有界（不等滿 10s）；mid-drain 取消殺路徑見 `cancelMidDrainKillsAndUnblocks`。
        var kills = 0
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                spawns++
                FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true)
            },
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
        assertEquals(0, spawns)
        assertEquals(0, kills)
        // 已取消即拒，不等滿 10s（留 4s 餘量給 CI 抖動）。
        assertTrue("elapsed=$elapsed", elapsed < 4_000L)
    }

    @Test fun cancelMidDrainKillsAndUnblocks() {
        // B2 P1-4：spawn 時未取消、drain 輪詢中取消 → 建後殺（kills==1）回 TimedOut。
        // 閾值 >4：runner 三點預檢（入口＋配額前＋配額後）各耗一次鉤調用，drain 輪詢
        // 才命中，避免預檢提前翻轉致零 spawn（見 P1-4 KDoc）。
        var kills = 0
        var polls = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = { ++polls > 4 },
            grantCheck = GrantFixtures.allowAlways,
        )
        val start = System.currentTimeMillis()
        val r = runner.run(ElevatedRequest(listOf("id"), "B2-T"), 10_000L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(1, kills)
        assertTrue("polls=$polls", polls > 4)
        assertTrue("elapsed=$elapsed", elapsed < 4_000L)
    }

    // ---- 3. ShizukuRemoteProcess 包裝（勿直 cast）＋二進位直達 ----

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
        // Phase 1 註記：9B 非 UTF-8 用例斷 runner 位元組直達（runBinary 原樣回傳），
        // 非 capture 語義（capture 另有 PNG 輕量門＋truncated→null，不在此斷）。
        // PNG 頭＋0xFF/0x00：經 String 往返必損毀。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0xFF.toByte(), 0x00, 0x01)
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = { FakeProcess(png, ByteArray(0), 0) },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val rb = root.runBinary(ElevatedRequest(listOf("screencap", "-p"), "B2-T"), 2_000L)
        assertTrue("$rb", rb is ElevatedBinaryResult.Ok)
        assertTrue((rb as ElevatedBinaryResult.Ok).stdout.contentEquals(png))
    }

    @Test fun framebufferPrefersBinaryChannel() {
        // B4：screencap 側須過 PNG 輕量門，故用合法 PNG（sig＋fill＋IEND）。
        // 輕量門代理（sig＋IEND），非解碼保證：僅證門通過＋位元組直達，不證圖片可解碼。
        val png = RootFramebufferCapture.PNG_SIGNATURE +
            byteArrayOf(0xFF.toByte(), 0x00) +
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4E, 0x44,
                0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
            )
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        // 全大寫拒絕訊號仍命中（Locale.ROOT 折疊，不隨裝置語系漂移；須帶 ^su: 前綴）。
        assertTrue(isSuDenied("SU: PERMISSION DENIED".toByteArray(), 1))
        assertTrue(isSuDenied("Su: Permission Denied".toByteArray(), 1))
        assertTrue(isSuDenied("su: REQUEST REJECTED".toByteArray(), 1))
        assertTrue(isSuDenied("su: Eacces".toByteArray(), 1))
    }

    // ---- P3-A 補強：超時/cancel/注入異常/門禁邊界（只斷 verdict＋計數＋位元組，不斷耗時） ----

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

    @Test fun shizukuTimeoutKillsAndReturnsBinaryTimedOut() {
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        val partial = "part-bytes".toByteArray()
        var kills = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        // P2-1 可恢復 timeout 不斷 breaker。
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())
    }

    @Test fun shizukuCancelKillsAndUnblocks() {
        // B2 P1-4：閾值 >4（runner 三點預檢各耗一次，drain 輪詢才命中；>2 會在
        // 配額後預檢即翻轉致零 spawn，見 Root 側同形註解）。
        var kills = 0
        var polls = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            status = { ShizukuStatus.Available(15) },
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = { ++polls > 4 },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 10_000L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
        assertTrue("polls=$polls", polls > 4)
    }

    @Test fun isCancelledThrowFoldsToNotCancelled() {
        // B2 P1-4：runner 層抛鉤 fail-closed（未建即視為已取消，零 spawn 回 TimedOut；
        // drain 層內抛仍折為未取消，見 P2 `drainThrowingIsCancelledFoldsToNotCancelled`）。
        var shizukuSpawns = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                shizukuSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            status = { ShizukuStatus.Available(15) },
            isCancelled = { throw IllegalStateException("cancel boom") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = shizuku.runBinary(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(0, shizukuSpawns)

        var rootSpawns = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                rootSpawns++
                FakeProcess("ok".toByteArray(), ByteArray(0), 0)
            },
            suBinary = "/system/bin/su",
            isCancelled = { throw IllegalStateException("cancel boom") },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = root.run(ElevatedRequest(listOf("id"), "P3-T"), 2_000L)
        assertTrue("$r", r is ShellResult.TimedOut)
        assertEquals(0, rootSpawns)
    }

    @Test fun killerThrowStillReturnsTimedOut() {
        var kills = 0
        val runner = RootSuRunner(
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOff,
            activationCheck = GrantFixtures.activationOn,
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
        // Phase 1 註記：/sdcard 路徑先命中 validate 跨域拒（BLACKLISTED），未走到橋門；
        // 橋門文案 "privilege bridge off" 由 GrantTest 無路徑命令（id/getprop）鎖定，此處只斷 Denied＋零 spawn。
        assertEquals(0, shizukuSpawns)

        var rootSpawns = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOff,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        var shizukuKills = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        // P1-3（PR140 Phase 2）：timeoutMs=0 即 budget 全無，入口 preflight 零 spawn
        //（舊碼快路徑仍建進程再由 drain 殺，kills==1；現 worker 從未啟動，kills==0、
        // 無 late 缺口故 cleanupIncomplete=false）。斷 0 privileged process。
        assertEquals(0, shizukuKills)
        assertFalse((sb as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
        assertEquals(0L, (sb as ElevatedBinaryResult.TimedOut).timeoutMs)
        // P2-1 可恢復 timeout 不斷 breaker。
        pollOutstandingTo(0)
        assertFalse(ElevatedCleanupTracker.isSaturated())
        assertFalse(ElevatedCleanupTracker.isBreakerOpen())

        var rootKills = 0
        val root = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
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
        // P1-3（同 shizuku 側）：零 spawn，kills==0，旗標 false。
        assertEquals(0, rootKills)
        assertFalse((rb as ElevatedBinaryResult.TimedOut).cleanupIncomplete)
    }

    // ---- R3-A Job 取消（owner P2-5）：真 kotlinx.coroutines Job 驅動清理 ----

    @Test fun coroutineJobCancelDrivesCleanup() {
        // R3-A：預設 isCancelled 只看 Thread 中斷，coroutine 呼叫方須顯式傳
        // `isCancelled = { !job.isActive }`；此處以前 N 次 false 後讀 job 狀態
        // 的鉤模擬「輪詢中被取消」：hang FakeProcess 保證非超時路徑（10s 上界）。
        // B2 P1-4：閾值 6（runner 三點預檢各耗一次：入口1＋配額前2＋配額後3，
        // drain 輪詢 4/5 後第 6 次才 cancel，保證 spawn 已發生；==3 會在配額後
        // 預檢即翻轉致零 spawn）。零真機/零真掛起：hang 的 waitFor 切片可被 killer 立即解除。
        val job = Job()
        var polls = 0
        var kills = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            suBinary = "/system/bin/su",
            killer = {
                kills++
                it.destroy()
            },
            isCancelled = {
                ++polls
                if (polls == 6) job.cancel()
                !job.isActive
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "R3A-T"), 10_000L)
        assertTrue("$b", b is ElevatedBinaryResult.TimedOut)
        assertEquals(1, kills)
        assertTrue("polls=$polls", polls >= 6)
        assertTrue(job.isCancelled)

        // 同一 Job 語義走 Shizuku 側亦回 TimedOut（兩 runner 同源 drain）。
        val shizukuJob = Job()
        var shizukuPolls = 0
        var shizukuKills = 0
        val shizuku = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
            status = { ShizukuStatus.Available(15) },
            killer = {
                shizukuKills++
                it.destroy()
            },
            isCancelled = {
                ++shizukuPolls
                if (shizukuPolls == 6) shizukuJob.cancel()
                !shizukuJob.isActive
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val sb = shizuku.runBinary(ElevatedRequest(listOf("id"), "R3A-T"), 10_000L)
        assertTrue("$sb", sb is ElevatedBinaryResult.TimedOut)
        assertEquals(1, shizukuKills)
        assertTrue("shizukuPolls=$shizukuPolls", shizukuPolls >= 6)
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
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
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
                bridgeGranted = GrantFixtures.bridgeOn,
                activationCheck = GrantFixtures.activationOn,
                pingBinder = { true },
                spawn = { FakeProcess("x".toByteArray(), ByteArray(0), 0, hang = true) },
                status = { ShizukuStatus.Available(15) },
                killer = killer,
                isCancelled = isCancelled,
                grantCheck = GrantFixtures.allowAlways,
            )
        }
    }

    // ---- V2 gobbler 讀取失敗旗標（owner 新 P1-2 correctness）：部分流不當完整幀 ----

    /**
     * 先給 N bytes 後抛 IOException 的假流：模擬 gobbler 中途 EIO。
     * `read(b, off, len)` 前綴耗盡後即抛，`read()` 同語義。
     */
    private class FailingReadStream(private val prefix: ByteArray) : InputStream() {
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

    /** 自訂流假進程：waitFor 即 true、exit 0（finished 路徑），流由呼叫方注入。 */
    private class FailingStreamProcess(
        private val stdoutStream: InputStream,
        private val stderrStream: InputStream,
        private val code: Int = 0,
    ) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = code
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun rootStdoutReadFailureMapsToFailedAndCaptureNull() {
        // screencap -p stdout 中途 EIO：waitFor true＋exit 0，但流不完整 → Failed＋capture null。
        val partial = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                FailingStreamProcess(
                    FailingReadStream(partial),
                    java.io.ByteArrayInputStream(ByteArray(0)),
                    0,
                )
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }

    @Test fun rootStderrReadFailureMapsToFailedAndCaptureNull() {
        // screencap -p stdout 完整但 stderr 中途 EIO：err readFailed 亦 Failed＋capture null。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01, 0x02)
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = {
                FailingStreamProcess(
                    java.io.ByteArrayInputStream(png),
                    FailingReadStream("warn".toByteArray()),
                    0,
                )
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }

    @Test fun shizukuStdoutReadFailureMapsToFailedAndCaptureNull() {
        // shizuku 側：stdout 中途 EIO → Failed＋capture null。
        val partial = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                FailingStreamProcess(
                    FailingReadStream(partial),
                    java.io.ByteArrayInputStream(ByteArray(0)),
                    0,
                )
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }

    @Test fun shizukuStderrReadFailureMapsToFailedAndCaptureNull() {
        // shizuku 側：stdout 完整但 stderr 中途 EIO → Failed＋capture null。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x01, 0x02)
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                FailingStreamProcess(
                    java.io.ByteArrayInputStream(png),
                    FailingReadStream("warn".toByteArray()),
                    0,
                )
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("screencap", "-p"), "V2-T"), 5_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "V2-T", 5_000L)())
    }
}
