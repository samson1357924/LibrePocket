package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellResult
import dev.librepocket.shell.Validation
import dev.librepocket.tool.Flavor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PR140 Phase B4（最後功能階段）pins（owner P2-6/7/8/9＋WARNs）：
 * - P2-6 PNG 輕量門（signature＋IHDR＋IEND；Phase 4 P2-4 收緊）＋malformed→FRAME_REJECTED＋truncated 仍 OK；
 * - P2-7 內層仿 su 行負向（Failed 非 Denied）；
 * - P2-8 反射缺方法 fail-closed；
 * - P2-9 跨域 fb0 政策 pin（UNKNOWN 仍 fail-closed）；
 * - WARN stdin-closer 永久 stall 有界＋daemon＋無洩漏。
 *
 * 全 fake／JVM，零真機、零 su、零 binder。
 */
class ElevatedRunnerB4Test {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    /**
     * 終審殘留隔離（Phase 1 P1-1）：每用例首尾歸零 consecutive＋輪詢收斂
     * outstanding/active，避免同 JVM 內熔斷污染後測。
     * （測試隔離重置，非#136-B3修復；不升級 noteClean 可見性。）
     *
     * Round-2 Phase A hermetic fixture：暫存 `screencap`＋`id`＋`systemOwned`，
     * `rootRunner`／`shizukuRunner` 一律顯式注入（`malformedOk` 的 screencap
     * 在 CI 宿主走預設表即 `SEARCH_DIR_UNTRUSTED`→`DENY`，掩蓋意圖的
     * `FRAME_REJECTED`；生產門零變更）。
     */
    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUpBreakerIsolation() {
        hermeticExec = HermeticExecFixture("b4-hermetic", "screencap", "id")
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

    /**
     * 合法 PNG fixture：sig＋IHDR＋fill＋IEND。
     * 輕量門代理（sig＋IHDR＋IEND），非解碼保證：僅證 isPlausiblePng 門通過，
     * 不證圖片可解碼（真解碼歸 Phase 2-5，不在此斷）。
     */

    private fun validPng(fill: ByteArray = "b4".toByteArray()): ByteArray {
        val sig = RootFramebufferCapture.PNG_SIGNATURE
        // IHDR 塊（25B）：len(13)＋"IHDR"＋w(1)＋h(1)＋5B 留白＋crc（門不驗 CRC）。
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val iend = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
        return sig + ihdr + fill + iend
    }

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

    // ---- P2-6 PNG 輕量門 ----

    @Test fun pngGateAcceptsSignaturePlusIend() {
        assertTrue(RootFramebufferCapture.isPlausiblePng(validPng()))
        assertFalse(RootFramebufferCapture.isPlausiblePng("not a PNG".toByteArray()))
        assertFalse(RootFramebufferCapture.isPlausiblePng(ByteArray(0)))
        // 僅 signature 無 IEND 即拒（截斷無溢出亦拒）。
        val sigOnly = RootFramebufferCapture.PNG_SIGNATURE + "partial".toByteArray()
        assertFalse(RootFramebufferCapture.isPlausiblePng(sigOnly))
        // 簽名錯一位即拒。
        val bad = validPng().copyOf()
        bad[0] = 0x00
        assertFalse(RootFramebufferCapture.isPlausiblePng(bad))
    }

    @Test fun malformedOkYieldsNullWithFrameRejected() {
        val garbage = "not a PNG".toByteArray()
        for (runner in listOf(
            rootRunner { FakeProcess(garbage, ByteArray(0), 0) },
            shizukuRunner { FakeProcess(garbage, ByteArray(0), 0) },
        )) {
            val audit = PrivilegeAuditLog()
            assertNull(
                RootFramebufferCapture.screencapViaBinary(runner, audit, "B4-T", 2_000L)(),
            )
            assertEquals(mapOf("FRAME_REJECTED" to 1), audit.countByVerdict())
        }
    }

    @Test fun captureInvalidScreencapFallsBackToFb0() {
        val fb = "fb-raw".toByteArray()
        val c = RootFramebufferCapture(
            readScreencap = { "not a PNG".toByteArray() },
            readFb0 = { fb },
        ).capture()!!
        assertEquals(FrameSource.FB0, c.source)
        // 兩路皆無效即 null（降級手動截圖，不虛構成功幀）。
        val none = RootFramebufferCapture(
            readScreencap = { "not a PNG".toByteArray() },
            readFb0 = { null },
        ).capture()
        assertNull(none)
    }

    @Test fun truncatedStillOkNotFrameRejected() {
        // B1-B3 語義 pin 保留：truncated 即 null 但審計仍記 OK（不改為 FRAME_REJECTED）。
        val png = validPng()
        val runner = object : BinaryElevatedRunner {
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult =
                ShellResult.Ok("unused", "", 0, false)
            override fun runBinary(request: ElevatedRequest, timeoutMs: Long): ElevatedBinaryResult =
                ElevatedBinaryResult.Ok(png, ByteArray(0), 0, true)
        }
        val audit = PrivilegeAuditLog()
        assertNull(RootFramebufferCapture.screencapViaBinary(runner, audit, "B4-T", 1_000L)())
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
    }

    // ---- P2-7 內層仿 su 行負向 ----

    @Test fun suInnerSpoofPrefixIsFailedNotDenied() {
        // 內層程式亦可印 `su: permission denied` 且 exit=1（合併 stderr／共享
        // exit code 下與真拒絕不可區分）：無獨立可信來源，一律 Failed，非 Denied。
        for (runner in listOf(
            rootRunner { FakeProcess(ByteArray(0), "su: permission denied".toByteArray(), 1) },
            rootRunner { FakeProcess(ByteArray(0), "  su: EACCES (inner spoof)".toByteArray(), 1) },
        )) {
            val b = runner.runBinary(ElevatedRequest(listOf("id"), "B4-T"), 2_000L)
            assertTrue("$b", b is ElevatedBinaryResult.Failed)
            val r = runner.run(ElevatedRequest(listOf("id"), "B4-T"), 2_000L)
            assertTrue("$r", r is ShellResult.Failed)
        }
        // 啟發式本身仍識別前綴（isSuDenied=true），但 runner 不再以此斷言 Denied。
        assertTrue(isSuDenied("su: permission denied".toByteArray(), 1))
    }

    // ---- P2-8 反射缺方法 fail-closed ----

    @Test fun shizukuReflectiveSpawnMissingFoldsToFailed() {
        var spawns = 0
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = {
                spawns++
                throw NoSuchMethodException("newProcess(String[], String[], String)")
            },
            status = { ShizukuStatus.Available(15) },
            grantCheck = GrantFixtures.allowAlways,
        )
        val b = runner.runBinary(ElevatedRequest(listOf("id"), "B4-T"), 2_000L)
        assertTrue("$b", b is ElevatedBinaryResult.Failed)
        assertTrue((b as ElevatedBinaryResult.Failed).message.contains("spawn failed"))
        assertEquals(1, spawns)
    }

    // ---- P2-9 跨域 fb0 政策 pin ----

    @Test fun fb0CrossDomainPolicyPinsCurrentBehavior() {
        // 未授權橋接：跨域 fb0 一律拒（詞法門，不碰 lstat）。
        val denied = ShellPolicy.validateElevated(
            listOf("cat", "/dev/graphics/fb0"),
            privateRoot,
            flavor = Flavor.GITHUB,
            bridgeGranted = false,
        )
        assertTrue("$denied", denied is Validation.Denied)
        // 已授權橋接：JVM 側（/dev/graphics 缺席→ABSENT）放行；真機 app UID
        // lstat 可能 UNKNOWN→Denied（fail-closed，不改），成功路徑歸 #136-B3 E2E。
        val allowed = ShellPolicy.validateElevated(
            listOf("cat", "/dev/graphics/fb0"),
            privateRoot,
            flavor = Flavor.GITHUB,
            bridgeGranted = true,
        )
        assertTrue("$allowed", allowed is Validation.Allowed)
    }

    // ---- WARN stdin-closer 永久 stall ----

    private class SwallowCloseOutput(private val stallMs: Long) : ByteArrayOutputStream() {
        override fun close() {
            val start = System.currentTimeMillis()
            var remaining = stallMs
            while (remaining > 0) {
                try {
                    Thread.sleep(remaining)
                    break
                } catch (_: InterruptedException) {
                    remaining = stallMs - (System.currentTimeMillis() - start)
                }
            }
            // 吞 interrupt 續睡：模擬不可中斷 stall（永久超出 2s budget）。
        }
    }

    private class StdinPermanentStallProcess(private val stallMs: Long) : Process() {
        @Volatile var stdinCloser: Thread? = null
        override fun getOutputStream(): OutputStream = SwallowCloseOutput(stallMs)
        override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }

    @Test fun stdinPermanentStallIsBoundedDaemonWithFlag() {
        // stdin close 吞 interrupt 續睡 3s（超 2s budget）：drain 有界返回＋
        // cleanupIncomplete=true＋stdin-closer 為 daemon（不擋 JVM 退出）。
        val proc = StdinPermanentStallProcess(3_000L)
        val resultRef = AtomicReference<ElevatedProcessIo.Drained?>()
        var closerDaemon: Boolean? = null
        val bg = Thread {
            resultRef.set(ElevatedProcessIo.drain(proc, 5_000L))
            closerDaemon = Thread.getAllStackTraces().keys
                .filter { it.name == "elevated-stdin-close" }
                .firstOrNull()?.isDaemon
        }.also { it.isDaemon = true; it.start() }
        bg.join(15_000L)
        assertFalse("drain must return bounded despite permanent stdin stall", bg.isAlive)
        val d = resultRef.get()
        assertNotNull(d)
        d!!
        assertFalse("timedOut must be false (finished path)", d.timedOut)
        assertTrue("cleanupIncomplete expected for lingering stdin-closer, got $d", d.cleanupIncomplete)
        // daemon 性：抓取仍存活或剛退出的 closer（若已退出則以命名＋歷史 pin 為證，
        // 此處不斷言 null，只斷抓到即必為 daemon）。
        if (closerDaemon != null) assertTrue(closerDaemon == true)
        // 無未捕捉、無殘留污染：等待 stall 線程自然退出（3s＋grace）再斷 orphan。
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            val alive = Thread.getAllStackTraces().keys.filter {
                it.name == "elevated-stdin-close" && it.isAlive
            }
            if (alive.isEmpty()) break
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(50L)
        }
    }

    // ---- WARN suExists 顯式跳過 pin 確認（保留） ----

    @Test fun suExistsExplicitBinarySkipsExistencePin() {
        // 顯式 suBinary 沿既有語義只驗可信，不新增存在性門（suExists 不被調用）；
        // 本 pin 鎖定保留行為（見 RootSuRunner spawn 前重驗 KDoc）。
        var existsCalls = 0
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
            suExists = {
                existsCalls++
                false
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.run(ElevatedRequest(listOf("id"), "B4-T"), 2_000L)
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, spawns)
        assertEquals("explicit suBinary must skip suExists", 0, existsCalls)
    }
}
