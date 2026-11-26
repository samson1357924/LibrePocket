package dev.librepocket.privilege.github

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.router.ElevatedWiring
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * #136-B4 READ 工具接線前置契約（github，純 JVM／fake spawn；零真 su／零 binder／零裝置）。
 *
 * 本檔只補 B1–B3 既有套件未覆蓋的 github runner × wiring  intersect 缺口：
 * - 確認後 grant 撤權／endpoint revision 翻轉 → 零 spawn（B3 已有 fake runner 版，
 *   此處以真 github runner ＋假 spawn 再次釘住 production runner 邊界）；
 * - symlink 逃逸在 wiring ＋真 github runner 下仍零 spawn（`ShellPhysicalGateTest`
 *   只驗 `ShellPolicy` 詞法／實體門，未走接線）；
 * - 二進位輸出不得經文字相容殼當作 READ 文字結果（`ElevatedBinaryResult.toShellResult`
 *   lossy；`ElevatedRunnerB4Test` 只驗 PNG 門，未驗 READ 通道選擇）；
 * - 正向控制：完整 grant ＋有效快照才允許 READ Ok。
 *
 * 既有覆蓋（刻意不重複）：bridge off／空白 reasonCode／ASK 不升 ALLOW／`..` 黑名單／
 * su exit≠0 見 `ElevatedWiringB3Test`；grant lifecycle／撤權後續 call 見
 * `ElevatedRunnerGrantTest`；activation default-deny 見 `ElevatedActivationContractTest`。
 *
 * **不是** production READ 工具 round-trip：`shell.elevated` 仍 `executionReady=false`、
 * `ELEVATED_RUNNERS_READY=false`、無 production dispatcher caller、無 fast lane。
 */
class ElevatedB4ReadContractTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUp() {
        hermeticExec = HermeticExecFixture("b4-read-contract", "id", "cat")
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDown() {
        hermeticExec?.close()
        hermeticExec = null
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    private fun pollOutstandingTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.outstanding() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("outstanding=${ElevatedCleanupTracker.outstanding()} expected=$expected")
            }
            Thread.sleep(50L)
        }
    }

    private fun pollActiveTo(expected: Int, boundMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + boundMs
        while (ElevatedCleanupTracker.activeCalls() != expected) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("active=${ElevatedCleanupTracker.activeCalls()} expected=$expected")
            }
            Thread.sleep(20L)
        }
    }

    private fun allowPolicy() = InMemoryPolicyStore(
        initialRules = listOf(
            PolicyRule("*:*", Verdict.ASK, 0),
            PolicyRule("${ElevatedWiring.POLICY_ACTION}:${ElevatedWiring.TOOL_NAME}", Verdict.ALLOW, 10),
        ),
    )

    private fun ctx() = ProjectionContext(
        flavor = Flavor.GITHUB,
        userSwitches = mapOf("privilege_bridge" to true),
    )

    private fun validGrant(): () -> ElevatedWiring.ElevatedGrant = {
        ElevatedWiring.ElevatedGrant.Valid("preset:openai", 3L)
    }

    private fun rootRunner(spawn: SpawnCounter) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private fun shizukuRunner(spawn: SpawnCounter) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private suspend fun wire(
        argv: List<String>,
        runner: dev.librepocket.shell.ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        grant: () -> ElevatedWiring.ElevatedGrant = validGrant(),
        reasonCode: String = "B4-READ-T",
    ): ShellResult = ElevatedWiring.execute(
        argv = argv,
        reasonCode = reasonCode,
        confirmed = true,
        ctxSupplier = { ctx() },
        policyStore = allowPolicy(),
        runner = runner,
        audit = audit,
        grantCheck = grant,
        bridgeGranted = GrantFixtures.bridgeOn,
        timeoutMs = 2_000L,
    )

    @Test fun readOkOnlyWithFullGrantAndValidSnapshot() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter("READ-OK".toByteArray())
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = wire(argv = listOf("id"), runner = runner, audit = audit)
            assertTrue("$r", r is ShellResult.Ok)
            assertEquals("READ-OK", (r as ShellResult.Ok).stdout)
            assertEquals(1, spawn.calls)
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }

    @Test fun grantRevokedAfterConfirmZeroSpawn() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter()
            var checks = 0
            // 前驗 valid、重驗（spawn 前）Invalid：撤權必須贏，零 spawn。
            val revokedOnRecheck = {
                checks++
                if (checks == 1) {
                    ElevatedWiring.ElevatedGrant.Valid("preset:openai", 3L)
                } else {
                    ElevatedWiring.ElevatedGrant.Invalid("revoked")
                }
            }
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = wire(argv = listOf("id"), runner = runner, audit = audit, grant = revokedOnRecheck)
            assertTrue("$r", r is ShellResult.Denied)
            assertFalse("$r must not be Ok", r is ShellResult.Ok)
            assertEquals("grant must be checked pre and on reverify", 2, checks)
            assertEquals(0, spawn.calls)
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        }
    }

    @Test fun snapshotPolicyRevisionRotationAfterConfirmZeroSpawn() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter()
            var checks = 0
            // 同 providerId 但 configRevision 翻轉：舊快照即過期，不得沿用。
            val rotatedOnRecheck = {
                checks++
                ElevatedWiring.ElevatedGrant.Valid("preset:openai", if (checks == 1) 3L else 4L)
            }
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = wire(argv = listOf("id"), runner = runner, audit = audit, grant = rotatedOnRecheck)
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(2, checks)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun symlinkEscapeZeroSpawnThroughWiring() = runBlocking {
        assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val scope = Files.createTempDirectory("b4-read-scope").toFile().canonicalFile
        val outside = Files.createTempDirectory("b4-read-outside").toFile().canonicalFile
        try {
            val victim = File(outside, "secret.txt").apply { writeText("synthetic-secret") }
            val link = File(scope, "leak.txt").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            for (isRoot in listOf(true, false)) {
                val spawn = SpawnCounter()
                val audit = PrivilegeAuditLog()
                val runner = if (isRoot) {
                    RootSuRunner(
                        privateRoot = scope.absolutePath,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        spawn = spawn::spawn,
                        suBinary = "/system/bin/su",
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = hermeticExec!!.searchDirs,
                        execAllowedRoots = hermeticExec!!.allowedRoots,
                        execIsWritable = hermeticExec!!.systemOwned,
                    )
                } else {
                    ShizukuShellRunner(
                        privateRoot = scope.absolutePath,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        pingBinder = { true },
                        spawn = spawn::spawn,
                        status = { ShizukuStatus.Available(15) },
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = hermeticExec!!.searchDirs,
                        execAllowedRoots = hermeticExec!!.allowedRoots,
                        execIsWritable = hermeticExec!!.systemOwned,
                    )
                }
                val r = wire(argv = listOf("cat", link.toString()), runner = runner, audit = audit)
                assertTrue("$r", r is ShellResult.Denied)
                assertEquals(0, spawn.calls)
            }
        } finally {
            scope.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun binaryOutputNeverBecomesTextReadOk() {
        // 非文字位元組（含 0xff）經 UTF-8 字串殼即不可逆失真；READ 二進位必須走
        // runBinary/ElevatedBinaryResult，不得把 String 再 toByteArray 當結果。
        val raw = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x00, 0xff.toByte())
        val spawn = SpawnCounter(stdout = raw)
        for (runner in listOf(rootRunner(spawn), shizukuRunner(spawn))) {
            val request = ElevatedRequest(listOf("id"), "B4-READ-T")
            val binary = runner.runBinary(request, 2_000L)
            assertTrue("$binary", binary is ElevatedBinaryResult.Ok)
            assertTrue("binary READ must keep raw bytes", (binary as ElevatedBinaryResult.Ok).stdout.contentEquals(raw))

            val text = runner.run(request, 2_000L)
            assertTrue("$text", text is ShellResult.Ok)
            val roundTrip = (text as ShellResult.Ok).stdout.toByteArray(Charsets.UTF_8)
            assertFalse("text shell must not be used as binary READ channel", roundTrip.contentEquals(raw))
        }
    }
}
