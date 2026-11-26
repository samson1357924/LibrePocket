package dev.librepocket.privilege.github

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.Verdict
import dev.librepocket.router.ElevatedWiring
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #136-B4 × #178 等價前置契約（test-only seam，**非** production caller／非 fast lane）。
 *
 * 未來 production READ 接線 PR 必須在副作用前完成下列等價閘；目前倉庫沒有該接線，
 * 本檔以一個測試協調器（seam）釘住驗收契約，實際執行委派到 [ElevatedWiring.execute]
 * ＋真 github runner（假 spawn），因此不是自我測試的假 executor：
 *
 * - 不可變快照綁六維 `(tool, identity, args, policyRevision, sessionId, turnIndex)`；
 * - 每次執行以新鮮 live state 重投影／重驗，任一維翻轉即 typed skip、零執行；
 * - policy 非 ALLOW（含 ASK）即 typed skip，ASK 絕不升級 ALLOW；
 * - audit sink 失敗 ⇒ typed skip、零執行（**先審計後副作用**）；
 * - 正向控制：六維全相符＋ALLOW＋audit 成功才執行一次。
 *
 * 現況誠實揭露：production [dev.librepocket.shell.ShellExecTool.executeElevatedFrozen]
 * 目前是 runner 執行**後**才 `audit.recordSnapshot`，故「audit 失敗 ⇒ 零執行」在
 * 現有 production 路徑尚未成立；它是接線 PR 的 hard gate，本 seam 只釘目標契約，
 * 不宣稱 production 已滿足。詳見 THREAT_MODEL #136-B4 段。
 */
class ElevatedB4SnapshotAuditSeamTest {

    private data class ReadSnapshot(
        val tool: String,
        val identity: String,
        val args: List<String>,
        val policyRevision: Long,
        val sessionId: String,
        val turnIndex: Int,
    )

    private data class LiveState(
        val tool: String,
        val identity: String,
        val args: List<String>,
        val policyRevision: Long,
        val sessionId: String,
        val turnIndex: Int,
    )

    private class AuditSinkFailure : RuntimeException("synthetic audit sink failure")

    private var hermeticExec: HermeticExecFixture? = null

    @Before fun setUp() {
        hermeticExec = HermeticExecFixture("b4-snapshot-seam", "id")
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollActiveTo(0)
    }

    @After fun tearDown() {
        hermeticExec?.close()
        hermeticExec = null
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollActiveTo(0)
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

    private fun live() = LiveState(
        tool = ElevatedWiring.TOOL_NAME,
        identity = "preset:openai",
        args = listOf("id"),
        policyRevision = 3L,
        sessionId = "session-B4",
        turnIndex = 2,
    )

    private fun snapshotOf(live: LiveState) = ReadSnapshot(
        tool = live.tool,
        identity = live.identity,
        args = live.args,
        policyRevision = live.policyRevision,
        sessionId = live.sessionId,
        turnIndex = live.turnIndex,
    )

    /**
     * 測試協調器：六維比對 → fresh policy ALLOW-only → audit sink → 才交給 wiring 執行。
     * [executions] 只在本協調器決定放行後遞增，用來證明 typed skip 真的是零執行。
     */
    private suspend fun runSeam(
        snapshot: ReadSnapshot,
        current: () -> LiveState,
        policyStore: InMemoryPolicyStore,
        auditSink: (ReadSnapshot) -> Unit,
        executions: () -> Unit,
        wiring: suspend () -> ShellResult,
    ): ShellResult {
        val now = current()
        val rebound = ReadSnapshot(
            tool = now.tool,
            identity = now.identity,
            args = now.args,
            policyRevision = now.policyRevision,
            sessionId = now.sessionId,
            turnIndex = now.turnIndex,
        )
        if (snapshot != rebound) {
            return ShellResult.Denied(ShellDeny.BLACKLISTED, "stale read snapshot (typed skip, no spawn)")
        }
        val verdict = policyStore.evaluateFresh(ElevatedWiring.POLICY_ACTION, snapshot.tool).verdict
        if (verdict != Verdict.ALLOW) {
            return ShellResult.Denied(ShellDeny.NOT_WHITELISTED, "policy not ALLOW (typed skip, no spawn)")
        }
        try {
            auditSink(snapshot)
        } catch (_: AuditSinkFailure) {
            return ShellResult.Denied(ShellDeny.BLACKLISTED, "audit failed (typed skip, no spawn)")
        }
        executions()
        return wiring()
    }

    /** 真 github runner ＋假 spawn；seam 通過才會到達 wiring → runner → spawn。 */
    private fun runWithSeam(
        snapshot: ReadSnapshot,
        current: () -> LiveState = ::live,
        policyStore: InMemoryPolicyStore = allowPolicy(),
        failAudit: Boolean = false,
    ): Pair<ShellResult, Int> = runBlocking {
        val spawn = SpawnCounter("READ-OK".toByteArray())
        val runner = RootSuRunner(
            privateRoot = "/data/data/dev.librepocket.agent/files",
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = spawn::spawn,
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
            execSearchDirs = hermeticExec!!.searchDirs,
            execAllowedRoots = hermeticExec!!.allowedRoots,
            execIsWritable = hermeticExec!!.systemOwned,
        )
        var executions = 0
        var auditCalls = 0
        val result = runSeam(
            snapshot = snapshot,
            current = current,
            policyStore = policyStore,
            auditSink = {
                auditCalls++
                if (failAudit) throw AuditSinkFailure()
            },
            executions = { executions++ },
            wiring = {
                ElevatedWiring.execute(
                    argv = snapshot.args,
                    reasonCode = "B4-SEAM-T",
                    confirmed = true,
                    ctxSupplier = { ctx() },
                    policyStore = allowPolicy(),
                    runner = runner,
                    audit = PrivilegeAuditLog(),
                    grantCheck = {
                        ElevatedWiring.ElevatedGrant.Valid(snapshot.identity, snapshot.policyRevision)
                    },
                    bridgeGranted = GrantFixtures.bridgeOn,
                    timeoutMs = 2_000L,
                )
            },
        )
        val expectedAuditCalls = if (failAudit || executions == 1) 1 else 0
        assertEquals("audit sink must be called only on the allowed path (once)", expectedAuditCalls, auditCalls)
        assertEquals("seam executions must match runner spawns", executions, spawn.calls)
        result to spawn.calls
    }

    @Test fun snapshotBindsAllSixDimensions() {
        val baseline = live()
        val cases = listOf(
            "tool" to baseline.copy(tool = "shell.write"),
            "identity" to baseline.copy(identity = "preset:other"),
            "args" to baseline.copy(args = listOf("id", "extra")),
            "policyRevision" to baseline.copy(policyRevision = 4L),
            "sessionId" to baseline.copy(sessionId = "session-OTHER"),
            "turnIndex" to baseline.copy(turnIndex = 3),
        )
        for ((label, stale) in cases) {
            // 快照為舊值、live 為新值：任一維不符即 typed skip（零 seam 執行／零 spawn）。
            val (result, spawns) = runWithSeam(
                snapshot = snapshotOf(baseline),
                current = { stale },
            )
            assertTrue("$label mismatch: $result", result is ShellResult.Denied)
            assertFalse("$label mismatch must not be Ok", result is ShellResult.Ok)
            assertEquals("$label mismatch must be zero spawn", 0, spawns)
        }
    }

    @Test fun liveFlipAfterMintIsRecheckedBeforeSideEffect() {
        val snapshot = snapshotOf(live())
        val (result, spawns) = runWithSeam(snapshot, current = { live().copy(turnIndex = 9) })
        assertTrue("$result", result is ShellResult.Denied)
        assertEquals(0, spawns)
    }

    @Test fun auditSinkFailureIsTypedSkipWithZeroExecution() {
        val (result, spawns) = runWithSeam(
            snapshot = snapshotOf(live()),
            failAudit = true,
        )
        assertTrue("$result", result is ShellResult.Denied)
        assertTrue("typed skip must name audit failure", (result as ShellResult.Denied).message.contains("audit"))
        assertEquals(0, spawns)
    }

    @Test fun askNeverUpgradedToAllow() {
        val askPolicy = InMemoryPolicyStore(initialRules = listOf(PolicyRule("*:*", Verdict.ASK, 0)))
        val (result, spawns) = runWithSeam(
            snapshot = snapshotOf(live()),
            policyStore = askPolicy,
        )
        assertTrue("$result", result is ShellResult.Denied)
        assertEquals(0, spawns)
    }

    @Test fun positiveControlExecutesOnlyWhenAllBindingsMatch() {
        val (result, spawns) = runWithSeam(snapshot = snapshotOf(live()))
        assertTrue("$result", result is ShellResult.Ok)
        assertEquals("READ-OK", (result as ShellResult.Ok).stdout)
        assertEquals(1, spawns)
    }
}
