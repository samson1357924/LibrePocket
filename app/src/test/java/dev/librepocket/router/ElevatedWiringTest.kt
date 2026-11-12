package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyRule
import dev.librepocket.policy.PolicyStore
import dev.librepocket.policy.Verdict
import dev.librepocket.shell.DenyingElevatedRunner
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ElevatedShellRunner
import dev.librepocket.shell.InMemoryPrivilegeRevokeStore
import dev.librepocket.shell.PrivilegeAuditLog
import dev.librepocket.shell.PrivilegeBridgeState
import dev.librepocket.shell.ShellResult
import dev.librepocket.tool.CapabilityLevel
import dev.librepocket.tool.Flavor
import dev.librepocket.tool.ProjectionContext
import dev.librepocket.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #136-B3 接線形狀 JVM 測試（main 側；裝置 E2E 明確不做，記錄 pending）。
 *
 * - `shell.elevated` 維持 `executionReady=false`（B3 不翻旗）；
 * - 每次 call 重新投影＋fresh policy＋ASK 確認（不可默認 ALLOW）＋不可變快照＋
 *   確認後 spawn 前重驗；
 * - 持久收回形狀：`revoke(store, withdrawScope)`（依賴 #166，B1 持久版為準）。
 */
class ElevatedWiringTest {

    private companion object {
        const val TEST_PROVIDER_ID = "preset:openai"
        const val TEST_REVISION = 3L
    }

    private fun validGrant(
        providerId: String = TEST_PROVIDER_ID,
        revision: Long = TEST_REVISION,
    ) = ElevatedWiring.ElevatedGrant.Valid(providerId, revision)

    private fun allowPolicy(): InMemoryPolicyStore = InMemoryPolicyStore(
        initialRules = listOf(
            PolicyRule("*:*", Verdict.ASK, 0),
            PolicyRule("${ElevatedWiring.POLICY_ACTION}:${ElevatedWiring.TOOL_NAME}", Verdict.ALLOW, 10),
        ),
    )

    private fun elevatedCtx(
        flavor: Flavor = Flavor.GITHUB,
        bridgeOn: Boolean = true,
    ) = ProjectionContext(
        flavor = flavor,
        userSwitches = mapOf("privilege_bridge" to bridgeOn),
    )

    private class SpawnCounter(var result: ShellResult = ShellResult.Ok("ok", "", 0, false)) : ElevatedShellRunner {
        var calls = 0
        var lastArgv: List<String>? = null
        override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
            calls++
            lastArgv = request.argv.toList()
            return result
        }
    }

    private fun wiringArgs(
        policy: PolicyStore = allowPolicy(),
        runner: ElevatedShellRunner = SpawnCounter(),
        audit: PrivilegeAuditLog = PrivilegeAuditLog(),
        ctx: ProjectionContext = elevatedCtx(),
        grant: ElevatedWiring.ElevatedGrant = validGrant(),
        bridge: Boolean = true,
    ) = object {
        val policyStore: PolicyStore = policy
        val r: ElevatedShellRunner = runner
        val a: PrivilegeAuditLog = audit
        val ctxSupplier: () -> ProjectionContext = { ctx }
        val grantCheck: () -> ElevatedWiring.ElevatedGrant = { grant }
        val bridgeGranted: () -> Boolean = { bridge }
    }

    @Test fun elevatedStaysNotExecutionReady() {
        val tool = ToolRegistry.find("shell.elevated")!!
        assertEquals(false, tool.executionReady)
        // 非模型可見：即使投影 NATIVE，visibleTools 仍不含（executionReady 雙閘）。
        val visible = ToolRegistry.visibleTools(elevatedCtx()).map { it.name }
        assertTrue("shell.elevated must stay out of visibleTools, got $visible", "shell.elevated" !in visible)
        // 投影本身 NATIVE（開關開＋github），證明接線形狀不斷言投影不可用。
        assertEquals(CapabilityLevel.NATIVE, tool.project(elevatedCtx()).level)
    }

    @Test fun needsConfirmWithoutSecondConfirm() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val w = wiringArgs(runner = runner, audit = audit)
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = false,
            ctxSupplier = w.ctxSupplier,
            policyStore = w.policyStore,
            runner = w.r,
            audit = w.a,
            grantCheck = w.grantCheck,
            bridgeGranted = w.bridgeGranted,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun blankReasonCodeNeverAllows() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val w = wiringArgs(runner = runner, audit = audit)
        for (blank in listOf("", "  ", "\t")) {
            val r = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = blank,
                confirmed = true,
                ctxSupplier = w.ctxSupplier,
                policyStore = w.policyStore,
                runner = w.r,
                audit = w.a,
                grantCheck = w.grantCheck,
                bridgeGranted = w.bridgeGranted,
            )
            assertTrue("blank=$blank -> $r", r is ShellResult.Denied)
        }
        assertEquals(0, runner.calls)
        assertEquals(3, audit.size())
    }

    @Test fun askDoesNotBecomeAllow() = runBlocking {
        // 預設規則集無 tool.exec ALLOW（僅 *: * ASK）：ASK 即拒，不默認放行。
        val askPolicy = InMemoryPolicyStore()
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = askPolicy,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("policy denied"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun projectionOffZeroSpawn() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val w = wiringArgs(runner = runner, audit = audit, ctx = elevatedCtx(bridgeOn = false))
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = w.ctxSupplier,
            policyStore = w.policyStore,
            runner = w.r,
            audit = w.a,
            grantCheck = w.grantCheck,
            bridgeGranted = w.bridgeGranted,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun flavorBlockedZeroSpawn() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        // PLAY 投影即 FLAVOR_BLOCKED（接線前驗擋下，不觸 runner）。
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx(flavor = Flavor.PLAY, bridgeOn = true) },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun bridgeOffZeroSpawn() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { false },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("bridge off"))
        assertEquals(0, runner.calls)
    }

    @Test fun grantInvalidZeroSpawn() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { ElevatedWiring.ElevatedGrant.Invalid("NEVER_GRANTED") },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals("NEVER_GRANTED", (r as ShellResult.Denied).message)
        assertEquals(0, runner.calls)
    }

    @Test fun confirmAfterMutationVoids() = runBlocking {
        // 確認後參數變更作廢：supplier 在重驗前竄改原始 MutableList。
        val mutable = mutableListOf("id")
        var calls = 0
        val ctxSupplier: () -> ProjectionContext = {
            calls++
            if (calls == 2) {
                mutable.add("evil")
            }
            elevatedCtx()
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = mutable,
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = ctxSupplier,
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("changed after confirm"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun contextFlipOnReverifyVoids() = runBlocking {
        // 確認後開關翻轉（前驗 true、重驗 false）：重驗即拒，零 spawn。
        var reverify = false
        val flippingBridge: () -> Boolean = {
            if (!reverify) {
                reverify = true
                true
            } else {
                false
            }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = flippingBridge,
        )
        // 前驗 bridge=true（首調），重驗 bridge=false（次調）→ 拒。
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("bridge off on reverify"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun happyPathAuditsSnapshotWithoutPlaintext() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val argv = mutableListOf("id")
        val r = ElevatedWiring.execute(
            argv = argv,
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        assertEquals(listOf("id"), runner.lastArgv)
        assertEquals(1, audit.size())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex("B3-OK"), row.reasonHash)
        assertEquals("OK", row.verdict)
        // 審計無明文：record toString 不攜 argv／reason 原文。
        val rowStr = row.toString()
        assertTrue(rowStr, !rowStr.contains("B3-OK"))
        // 執行期間竄改不污染已快照執行與審計（runner 見快照 ["id"]）。
        assertEquals(listOf("id"), runner.lastArgv)
    }

    @Test fun runnerDenyStillSingleAudit() = runBlocking {
        // 佔位 runner 拒絕（門禁已過）：執行側 DENY 同樣單筆留痕。
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = DenyingElevatedRunner(),
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun persistentRevokeClearsStoreScopeAndMemory() = runBlocking {
        val state = PrivilegeBridgeState(
            audit = PrivilegeAuditLog(),
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B3-x", "DENY")
        val store = InMemoryPrivilegeRevokeStore()
        var scopeClears = 0
        val cleared = state.revoke(store) { scopeClears++ }
        assertEquals(1, cleared)
        assertEquals(1, store.clearCalls)
        assertEquals(1, scopeClears)
        assertEquals(false, state.bridgeSwitchOn)
        assertEquals(false, state.bridgeGranted)
        assertEquals(0, state.auditCount())
    }

    @Test fun persistentRevokeFailClosedOnStoreThrow() = runBlocking {
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        val failing = object : dev.librepocket.shell.PrivilegeRevokeStore {
            override suspend fun clear() {
                throw IllegalStateException("store boom")
            }
        }
        try {
            state.revoke(failing)
            assertTrue("must throw", false)
        } catch (_: IllegalStateException) {
            // 仍先落記憶體收回（fail-closed，不殘留可用旗標）。
            assertEquals(false, state.bridgeSwitchOn)
            assertEquals(false, state.bridgeGranted)
        }
    }

    @Test fun cancellationPropagatesWithoutDenyAudit() = runBlocking {
        // 取消必須重拋 CE，不得轉 Denied＋DENY 審計（各 supplier／policy 全覆）。
        val cancellingPolicy = object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String) =
                throw CancellationException("policy cancelled")
        }
        for (name in listOf("ctxSupplier", "bridgeGranted", "grantCheck", "evaluateFresh")) {
            val audit = PrivilegeAuditLog()
            val runner = SpawnCounter()
            try {
                when (name) {
                    "ctxSupplier" -> ElevatedWiring.execute(
                        argv = listOf("id"),
                        reasonCode = "B3-T",
                        confirmed = true,
                        ctxSupplier = { throw CancellationException("ctx cancelled") },
                        policyStore = allowPolicy(),
                        runner = runner,
                        audit = audit,
                        grantCheck = { validGrant() },
                        bridgeGranted = { true },
                    )
                    "bridgeGranted" -> ElevatedWiring.execute(
                        argv = listOf("id"),
                        reasonCode = "B3-T",
                        confirmed = true,
                        ctxSupplier = { elevatedCtx() },
                        policyStore = allowPolicy(),
                        runner = runner,
                        audit = audit,
                        grantCheck = { validGrant() },
                        bridgeGranted = { throw CancellationException("bridge cancelled") },
                    )
                    "grantCheck" -> ElevatedWiring.execute(
                        argv = listOf("id"),
                        reasonCode = "B3-T",
                        confirmed = true,
                        ctxSupplier = { elevatedCtx() },
                        policyStore = allowPolicy(),
                        runner = runner,
                        audit = audit,
                        grantCheck = { throw CancellationException("grant cancelled") },
                        bridgeGranted = { true },
                    )
                    else -> ElevatedWiring.execute(
                        argv = listOf("id"),
                        reasonCode = "B3-T",
                        confirmed = true,
                        ctxSupplier = { elevatedCtx() },
                        policyStore = cancellingPolicy,
                        runner = runner,
                        audit = audit,
                        grantCheck = { validGrant() },
                        bridgeGranted = { true },
                    )
                }
                assertTrue("$name must throw CE", false)
            } catch (_: CancellationException) {
                // 預期：重拋，不轉 Denied。
            }
            assertEquals("$name must not record DENY", 0, audit.size())
            assertEquals("$name must not spawn", 0, runner.calls)
        }
    }

    @Test fun grantIdentityRotationVoidsOnReverify() = runBlocking {
        // endpoint／revision 輪轉即作廢：前驗 Valid(A,1)、重驗 Valid(B,1) 即拒，零 spawn。
        var calls = 0
        val rotatingGrant: () -> ElevatedWiring.ElevatedGrant = {
            calls++
            if (calls == 1) validGrant("preset:openai", 3L) else validGrant("preset:other", 3L)
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = rotatingGrant,
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("grant identity changed"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun grantRevisionRotationVoidsOnReverify() = runBlocking {
        // revision 推進即作廢：前驗 (A,3)、重驗 (A,4) 即拒，零 spawn。
        var calls = 0
        val rotatingGrant: () -> ElevatedWiring.ElevatedGrant = {
            calls++
            if (calls == 1) validGrant(TEST_PROVIDER_ID, 3L) else validGrant(TEST_PROVIDER_ID, 4L)
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = rotatingGrant,
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("grant identity changed"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun blankGrantProviderFailsClosed() = runBlocking {
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant("", TEST_REVISION) },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("malformed"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }
}
