package dev.librepocket.privilege.github

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.router.ElevatedWiring
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import dev.librepocket.shell.ShellQuota
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #136-B3 接線負向 JVM 測試（github 側，真 runner＋假 spawn；裝置 E2E 明確不做）。
 *
 * 經 [ElevatedWiring]（接線形狀）→[dev.librepocket.router.ElevatedDispatch]→
 * 真 `RootSuRunner`／`ShizukuShellRunner`（假 spawn／假狀態／假 su）全鏈路：
 * su 拒絕／exit≠0 不回 Ok；timeout 殺子孫；取消解除 blocking IO；輸出截斷；
 * 路徑逃逸；quota；空白 reasonCode；ASK 不變 ALLOW；確認後參數變更作廢；
 * audit 無明文；flavor／bridge off 零 spawn。
 *
 * `executionReady=false` 不翻旗由 main 側 `ElevatedWiringTest` pin；本檔只證
 * 接線形狀下 runner 語義不變（全 fake，零真機／真 su／真 binder）。
 */
class ElevatedWiringB3Test {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUp() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
        hermeticExec = HermeticExecFixture("wiring-b3", "id", "getprop", "dumpsys", "ls")
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

    private fun allowPolicy() = InMemoryPolicyStore(
        initialRules = listOf(
            PolicyRule("*:*", Verdict.ASK, 0),
            PolicyRule("${ElevatedWiring.POLICY_ACTION}:${ElevatedWiring.TOOL_NAME}", Verdict.ALLOW, 10),
        ),
    )

    private fun elevatedCtx() = ProjectionContext(
        flavor = Flavor.GITHUB,
        userSwitches = mapOf("privilege_bridge" to true),
    )

    private fun grantValid(): () -> ElevatedWiring.ElevatedGrant =
        { ElevatedWiring.ElevatedGrant.Valid }

    private fun grantInvalid(reason: String): () -> ElevatedWiring.ElevatedGrant =
        { ElevatedWiring.ElevatedGrant.Invalid(reason) }

    private fun mapGrant(check: GrantCheck): ElevatedWiring.ElevatedGrant = when (check) {
        is GrantCheck.Valid -> ElevatedWiring.ElevatedGrant.Valid
        is GrantCheck.Invalid -> ElevatedWiring.ElevatedGrant.Invalid(check.reason)
    }

    private fun rootRunner(
        spawn: SpawnCounter,
        quota: ShellQuota = ShellQuota(),
        bridge: () -> Boolean = GrantFixtures.bridgeOn,
        grant: () -> GrantCheck = GrantFixtures.allowAlways,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) = RootSuRunner(
        privateRoot = privateRoot,
        quota = quota,
        bridgeGranted = bridge,
        activationCheck = GrantFixtures.activationOn,
        spawn = spawn::spawn,
        suBinary = "/system/bin/su",
        isCancelled = isCancelled,
        grantCheck = grant,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private fun shizukuRunner(
        spawn: SpawnCounter,
        quota: ShellQuota = ShellQuota(),
        bridge: () -> Boolean = GrantFixtures.bridgeOn,
        grant: () -> GrantCheck = GrantFixtures.allowAlways,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) = ShizukuShellRunner(
        privateRoot = privateRoot,
        quota = quota,
        bridgeGranted = bridge,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = spawn::spawn,
        status = { ShizukuStatus.Available(15) },
        isCancelled = isCancelled,
        grantCheck = grant,
        execSearchDirs = hermeticExec!!.searchDirs,
        execAllowedRoots = hermeticExec!!.allowedRoots,
        execIsWritable = hermeticExec!!.systemOwned,
    )

    private suspend fun wireExecute(
        argv: List<String>,
        reasonCode: String = "B3-T",
        confirmed: Boolean = true,
        runner: dev.librepocket.shell.ElevatedShellRunner,
        audit: PrivilegeAuditLog,
        grant: () -> ElevatedWiring.ElevatedGrant = grantValid(),
        bridge: () -> Boolean = GrantFixtures.bridgeOn,
        ctx: ProjectionContext = elevatedCtx(),
        timeoutMs: Long = 2_000L,
    ): ShellResult = ElevatedWiring.execute(
        argv = argv,
        reasonCode = reasonCode,
        confirmed = confirmed,
        ctxSupplier = { ctx },
        policyStore = allowPolicy(),
        runner = runner,
        audit = audit,
        grantCheck = grant,
        bridgeGranted = bridge,
        timeoutMs = timeoutMs,
    )

    @Test fun executionReadyStaysFalse() {
        assertEquals(false, ToolRegistry.find("shell.elevated")!!.executionReady)
    }

    @Test fun suExitNonZeroNeverOk() = runBlocking {
        for (isRoot in listOf(true, false)) {
            // exit=1（su 拒絕／內層失敗）：一律 Failed，不回 Ok。
            val deniedProcess = object : java.lang.Process() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream("ok".toByteArray())
                override fun getErrorStream(): InputStream = ByteArrayInputStream("su: denied".toByteArray())
                override fun waitFor(): Int = 1
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
                override fun exitValue(): Int = 1
                override fun destroy() = Unit
                override fun destroyForcibly(): java.lang.Process = this
            }
            var calls = 0
            val failingSpawn: (List<String>) -> java.lang.Process = {
                calls++
                deniedProcess
            }
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) {
                RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = failingSpawn,
                    suBinary = "/system/bin/su",
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = hermeticExec!!.searchDirs,
                    execAllowedRoots = hermeticExec!!.allowedRoots,
                    execIsWritable = hermeticExec!!.systemOwned,
                )
            } else {
                ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = failingSpawn,
                    status = { ShizukuStatus.Available(15) },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = hermeticExec!!.searchDirs,
                    execAllowedRoots = hermeticExec!!.allowedRoots,
                    execIsWritable = hermeticExec!!.systemOwned,
                )
            }
            val r = wireExecute(argv = listOf("id"), runner = runner, audit = audit)
            assertTrue("$r", r is ShellResult.Failed)
            assertFalse("$r must not be Ok", r is ShellResult.Ok)
            assertEquals(1, calls)
            assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
        }
    }

    @Test fun timeoutKillsDescendants() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val latch = CountDownLatch(1)
            var destroyed = false
            val hang = object : java.lang.Process() {
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 0
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    try {
                        latch.await(timeout, unit)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    return destroyed
                }
                override fun exitValue(): Int = 0
                override fun destroy() {
                    destroyed = true
                    latch.countDown()
                }
                override fun destroyForcibly(): java.lang.Process {
                    destroyed = true
                    latch.countDown()
                    return this
                }
            }
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) {
                rootRunner(SpawnCounter(), isCancelled = { false }).let {
                    RootSuRunner(
                        privateRoot = privateRoot,
                        bridgeGranted = GrantFixtures.bridgeOn,
                        activationCheck = GrantFixtures.activationOn,
                        spawn = { hang },
                        suBinary = "/system/bin/su",
                        killer = {
                            destroyed = true
                            latch.countDown()
                            it.destroy()
                        },
                        isCancelled = { false },
                        grantCheck = GrantFixtures.allowAlways,
                        execSearchDirs = hermeticExec!!.searchDirs,
                        execAllowedRoots = hermeticExec!!.allowedRoots,
                        execIsWritable = hermeticExec!!.systemOwned,
                    )
                }
            } else {
                ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { hang },
                    status = { ShizukuStatus.Available(15) },
                    killer = {
                        destroyed = true
                        latch.countDown()
                        it.destroy()
                    },
                    isCancelled = { false },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = hermeticExec!!.searchDirs,
                    execAllowedRoots = hermeticExec!!.allowedRoots,
                    execIsWritable = hermeticExec!!.systemOwned,
                )
            }
            try {
                val r = wireExecute(argv = listOf("id"), runner = runner, audit = audit, timeoutMs = 300L)
                assertTrue("$r", r is ShellResult.TimedOut)
                assertTrue("killer must run, destroyed=$destroyed", destroyed)
                assertEquals(mapOf("TIMEOUT" to 1), audit.countByVerdict())
            } finally {
                latch.countDown()
                pollOutstandingTo(0)
                pollActiveTo(0)
            }
        }
    }

    @Test fun cancelReleasesBlockingIo() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val cancelled = AtomicBoolean(false)
            // hang 直到被 killer 銷毀（drain 的 isCancelled 輪詢觸發 kill，解除 blocking IO）。
            val hang = FakeProcess("ok".toByteArray(), hang = true)
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) {
                RootSuRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    spawn = { hang },
                    suBinary = "/system/bin/su",
                    isCancelled = { cancelled.get() },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = hermeticExec!!.searchDirs,
                    execAllowedRoots = hermeticExec!!.allowedRoots,
                    execIsWritable = hermeticExec!!.systemOwned,
                )
            } else {
                ShizukuShellRunner(
                    privateRoot = privateRoot,
                    bridgeGranted = GrantFixtures.bridgeOn,
                    activationCheck = GrantFixtures.activationOn,
                    pingBinder = { true },
                    spawn = { hang },
                    status = { ShizukuStatus.Available(15) },
                    isCancelled = { cancelled.get() },
                    grantCheck = GrantFixtures.allowAlways,
                    execSearchDirs = hermeticExec!!.searchDirs,
                    execAllowedRoots = hermeticExec!!.allowedRoots,
                    execIsWritable = hermeticExec!!.systemOwned,
                )
            }
            try {
                var result: ShellResult? = null
                val bg = Thread {
                    result = runBlocking {
                        wireExecute(argv = listOf("id"), runner = runner, audit = audit, timeoutMs = 10_000L)
                    }
                }.also { it.isDaemon = true; it.start() }
                // 等 drain 進入阻塞再取消（無 sleep 猜測：輪詢 spawn 側 hang 存活）。
                Thread.sleep(300L)
                cancelled.set(true)
                bg.join(15_000L)
                assertFalse(bg.isAlive)
                // 取消解除 blocking IO：drain 受監督 kill 後回 TimedOut（非 Ok、非 hang）。
                assertTrue("$result", result is ShellResult.TimedOut)
                assertTrue("cancel must destroy hanging process", hang.destroyed)
            } finally {
                pollOutstandingTo(0)
                pollActiveTo(0)
            }
        }
    }

    @Test fun largeOutputTruncates() = runBlocking {
        val big = ByteArray(ShellPolicy.MAX_OUTPUT_BYTES + 8) { 'x'.code.toByte() }
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter(stdout = big)
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = wireExecute(argv = listOf("id"), runner = runner, audit = audit)
            assertTrue("$r", r is ShellResult.Ok)
            assertTrue("$r", (r as ShellResult.Ok).truncated)
            assertEquals(1, audit.size())
            assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        }
    }

    @Test fun pathEscapeZeroSpawn() = runBlocking {
        // 路徑逃逸／黑名單：提權仍拒（`..` 穿越＋`rm -rf /` 黑名單），零 spawn。
        // 註：跨域絕對路徑（`/data/data/other/...`）在 bridge 已授權時經提權橋合法，
        // 故不屬逃逸向量（見 CrossDomainBridgeTest）；此處只 pin 真拒絕向量。
        val vectors = listOf(
            listOf("ls", "../../etc/passwd"),
            listOf("rm", "-rf", "/"),
        )
        for (argv in vectors) {
            for (isRoot in listOf(true, false)) {
                val spawn = SpawnCounter()
                val audit = PrivilegeAuditLog()
                val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
                val r = wireExecute(argv = argv, runner = runner, audit = audit)
                assertTrue("$argv -> $r", r is ShellResult.Denied)
                assertEquals(0, spawn.calls)
            }
        }
    }

    @Test fun quotaExceededZeroSpawn() = runBlocking {
        // 配額耗盡（maxCalls=1，首 call 佔用後次 call 即拒；此處以 maxCalls=0
        // 直接耗盡，零 spawn 語義同）。
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter()
            val audit = PrivilegeAuditLog()
            val exhausted = ShellQuota(maxCalls = 0, windowMs = 60_000L)
            val runner = if (isRoot) rootRunner(spawn, quota = exhausted) else shizukuRunner(spawn, quota = exhausted)
            val r = wireExecute(argv = listOf("id"), runner = runner, audit = audit)
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(ShellDeny.QUOTA_EXCEEDED, (r as ShellResult.Denied).reason)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun wiringBlankReasonCodeZeroSpawn() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter()
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = wireExecute(argv = listOf("id"), reasonCode = "  ", runner = runner, audit = audit)
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun wiringAskNeverAllow() = runBlocking {
        // ASK 政策（預設無 ALLOW 規則）經接線即拒，不觸 runner。
        val askPolicy = InMemoryPolicyStore()
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter()
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx() },
                policyStore = askPolicy,
                runner = runner,
                audit = audit,
                grantCheck = grantValid(),
                bridgeGranted = GrantFixtures.bridgeOn,
            )
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun wiringPostConfirmMutationVoids() = runBlocking {
        for (isRoot in listOf(true, false)) {
            val mutable = mutableListOf("id")
            var n = 0
            val ctxSupplier: () -> ProjectionContext = {
                n++
                if (n == 2) mutable.add("evil")
                elevatedCtx()
            }
            val spawn = SpawnCounter()
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) rootRunner(spawn) else shizukuRunner(spawn)
            val r = ElevatedWiring.execute(
                argv = mutable,
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = ctxSupplier,
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = grantValid(),
                bridgeGranted = GrantFixtures.bridgeOn,
            )
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun wiringAuditHasNoPlaintext() = runBlocking {
        val spawn = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val runner = rootRunner(spawn)
        val secretReason = "B3-SECRET-xyz"
        val r = wireExecute(argv = listOf("id"), reasonCode = secretReason, runner = runner, audit = audit)
        assertTrue("$r", r is ShellResult.Ok)
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex(secretReason), row.reasonHash)
        val rowStr = row.toString()
        assertFalse(rowStr, rowStr.contains("SECRET"))
        assertFalse(rowStr, rowStr.contains(secretReason))
        // runner 回執不攜 reason 明文（Failed／Ok 訊息僅 redacted 輸出）。
        assertFalse("$r", "$r".contains(secretReason))
    }

    @Test fun wiringFlavorAndBridgeOffZeroSpawn() = runBlocking {
        // flavor PLAY：投影 FLAVOR_BLOCKED，零 spawn。
        run {
            val spawn = SpawnCounter()
            val audit = PrivilegeAuditLog()
            val runner = rootRunner(spawn)
            val r = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx().copy(flavor = Flavor.PLAY) },
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = grantValid(),
                bridgeGranted = GrantFixtures.bridgeOn,
            )
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(0, spawn.calls)
        }
        // bridge off：接線前驗即拒，零 spawn（兩 runner）。
        for (isRoot in listOf(true, false)) {
            val spawn = SpawnCounter()
            val audit = PrivilegeAuditLog()
            val runner = if (isRoot) {
                rootRunner(spawn, bridge = GrantFixtures.bridgeOff)
            } else {
                shizukuRunner(spawn, bridge = GrantFixtures.bridgeOff)
            }
            val r = wireExecute(
                argv = listOf("id"),
                runner = runner,
                audit = audit,
                bridge = GrantFixtures.bridgeOff,
            )
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(0, spawn.calls)
        }
    }

    @Test fun wiringGrantMapsGithubCheck() {
        // 最小抽象對映：github GrantCheck ↔ 接線 ElevatedGrant（rebase 後以 B1 為準）。
        assertEquals(
            ElevatedWiring.ElevatedGrant.Valid,
            mapGrant(GrantCheck.Valid),
        )
        val invalid = mapGrant(GrantCheck.Invalid(ShizukuGrantPolicy.REASON_NEVER_GRANTED))
        assertTrue("$invalid", invalid is ElevatedWiring.ElevatedGrant.Invalid)
        assertEquals(
            ShizukuGrantPolicy.REASON_NEVER_GRANTED,
            (invalid as ElevatedWiring.ElevatedGrant.Invalid).reason,
        )
    }
}
