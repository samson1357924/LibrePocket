package dev.librepocket.router

import dev.librepocket.policy.InMemoryPolicyStore
import dev.librepocket.policy.PolicyDecision
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.AbstractMap
import java.util.AbstractSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #136-B3 接線形狀 JVM 測試（main 側；裝置 E2E 明確不做，記錄 pending）。
 *
 * - `shell.elevated` 維持 `executionReady=false`（B3 不翻旗）；
 * - 每次 call 重新投影＋fresh policy＋ASK 確認（不可默認 ALLOW）＋不可變快照＋
 *   確認後 spawn 前重驗；
 * - 持久收回形狀：`revoke(store, withdrawScope)`（依賴 #166，B1 持久版為準；
 *   持久／跨域 best-effort 由 `revokePersisted` two-step 執行，見 PrivilegeBridgeState）。
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

    /** 免 spawn：Denied＋零 spawn＋一筆 DENY 審計（freeze／邊界拒絕的統一斷言）。 */
    private fun assertDeniedZeroSpawn(
        r: ShellResult,
        runner: SpawnCounter,
        audit: PrivilegeAuditLog,
    ) {
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    /** userSwitches entries iterator 拋 CancellationException 的 hostile Map（doFreeze 路徑 CE 測針）。 */
    private fun ceThrowingSwitches(): Map<String, Boolean> =
        object : AbstractMap<String, Boolean>() {
            override val entries: MutableSet<MutableMap.MutableEntry<String, Boolean>>
                get() = object : AbstractSet<MutableMap.MutableEntry<String, Boolean>>() {
                    override val size: Int get() = 1
                    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Boolean>> =
                        throw CancellationException("freeze iterator cancelled")
                }
        }

    /** userSwitches entries iterator 拋 ConcurrentModificationException 的 hostile Map。 */
    private fun cmeThrowingSwitches(): Map<String, Boolean> =
        object : AbstractMap<String, Boolean>() {
            override val entries: MutableSet<MutableMap.MutableEntry<String, Boolean>>
                get() = object : AbstractSet<MutableMap.MutableEntry<String, Boolean>>() {
                    override val size: Int get() = 1
                    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Boolean>> =
                        throw java.util.ConcurrentModificationException("concurrent modification")
                }
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

    @Test fun unconfirmedCheapDenyDoesNotTouchArgv() = runBlocking {
        var touched = false
        val argv = object : AbstractList<String>() {
            override val size: Int get() { touched = true; return 1 }
            override fun get(index: Int): String { touched = true; return "id" }
            override fun iterator(): MutableIterator<String> {
                touched = true
                return mutableListOf("id").iterator()
            }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = argv,
            reasonCode = "B3-T",
            confirmed = false,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(0, runner.calls)
        assertEquals(false, touched)
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

    @Test fun blockingGrantCheckFailsClosedNoSpawn() = runBlocking {
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
            grantCheck = {
                Thread.sleep(5_000L)
                validGrant()
            },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("grant check failed"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
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

    @Test fun adversarialEqualsMutationDetectedBySafeRecheck() = runBlocking {
        // F3: untrusted argv must not be compared via uncontrolled equals/iterator; bounded re-freeze detects mutation.
        class EvilArgv(private val backing: MutableList<String>) : AbstractList<String>() {
            override val size: Int get() = backing.size
            override fun get(index: Int): String = backing[index]
            override fun iterator(): MutableIterator<String> = backing.iterator()
            override fun equals(other: Any?): Boolean = true // adversarial
        }
        val backing = mutableListOf("id")
        val argv = EvilArgv(backing)
        var calls = 0
        val ctxSupplier: () -> ProjectionContext = {
            calls++
            if (calls == 2) backing.add("evil")
            elevatedCtx()
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = argv,
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
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
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
        state.audit.record(listOf("id"), "B3-x", "DENY")
        val failing = object : dev.librepocket.shell.PrivilegeRevokeStore {
            override suspend fun clear() {
                throw IllegalStateException("store boom")
            }
        }
        try {
            state.revoke(failing)
            assertTrue("must throw", false)
        } catch (_: IllegalStateException) {
            // memory-first：已先落記憶體收回（fail-closed，不殘留可用旗標/審計）。
            assertEquals(false, state.bridgeSwitchOn)
            assertEquals(false, state.bridgeGranted)
            assertEquals(0, state.auditCount())
        }
    }

    @Test fun persistentRevokeFailClosedOnWithdrawScopeThrow() = runBlocking {
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        val store = InMemoryPrivilegeRevokeStore()
        try {
            state.revoke(store) { throw IllegalStateException("scope boom") }
            assertTrue("must throw", false)
        } catch (_: IllegalStateException) {
            assertEquals(1, store.clearCalls)
            assertEquals(false, state.bridgeSwitchOn)
            assertEquals(false, state.bridgeGranted)
        }
    }

    @Test fun persistentRevokeCancellationMemoryFirstThenRethrow() = runBlocking {
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        val store = InMemoryPrivilegeRevokeStore()
        try {
            state.revoke(store) { throw CancellationException("cancelled") }
            assertTrue("must throw", false)
        } catch (_: CancellationException) {
            assertEquals(1, store.clearCalls)
            assertEquals(false, state.bridgeSwitchOn)
            assertEquals(false, state.bridgeGranted)
        }
    }

    @Test fun persistentRevokeStoreClearCancellationWithdrawScopeStillInvoked() = runBlocking {
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B3-x", "DENY")
        var scopeCalls = 0
        var storeClearCalls = 0
        val store = object : dev.librepocket.shell.PrivilegeRevokeStore {
            override suspend fun clear() {
                storeClearCalls++
                throw CancellationException("store cancelled")
            }
        }
        try {
            state.revoke(store) { scopeCalls++ }
            assertTrue("must throw", false)
        } catch (_: CancellationException) {
            // store.clear() 抛 CE 不得短路跨域撤銷：withdrawScope 仍須執行。
            assertEquals(1, storeClearCalls)
            assertEquals(1, scopeCalls)
            assertEquals(false, state.bridgeSwitchOn)
            assertEquals(false, state.bridgeGranted)
            assertEquals(0, state.auditCount())
        }
    }

    @Test fun persistentRevokeFailureMatrixStoreExceptionCrossScope() = runBlocking {
        // {store: Exception|CE} × {scope: Exception|CE} 2×2 = 4 段：兩動作皆須被嘗試；
        // 記憶體旗標已關、auditCount==0、失敗彙整重拋（Exception 或 CE）；
        // primary＝store 先拋者（先到為準），scope 失敗以 suppressed 掛入（每段皆 pin）。
        data class Combo(val storeThrowsCe: Boolean, val scopeThrowsCe: Boolean)
        val combos = listOf(
            Combo(storeThrowsCe = false, scopeThrowsCe = false), // store=Exception, scope=Exception
            Combo(storeThrowsCe = false, scopeThrowsCe = true),  // store=Exception, scope=CE
            Combo(storeThrowsCe = true, scopeThrowsCe = false),  // store=CE, scope=Exception
            Combo(storeThrowsCe = true, scopeThrowsCe = true),   // store=CE, scope=CE
        )
        for (combo in combos) {
            val state = PrivilegeBridgeState(
                bridgeSwitchOn = true,
                bridgeGranted = true,
            )
            state.audit.record(listOf("id"), "B3-x", "DENY")
            var storeCalls = 0
            var scopeCalls = 0
            val storeFailure: Throwable = if (combo.storeThrowsCe) {
                CancellationException("store cancelled")
            } else {
                IllegalStateException("store boom")
            }
            val scopeFailure: Throwable = if (combo.scopeThrowsCe) {
                CancellationException("scope cancelled")
            } else {
                IllegalStateException("scope boom")
            }
            val store = object : dev.librepocket.shell.PrivilegeRevokeStore {
                override suspend fun clear() {
                    storeCalls++
                    throw storeFailure
                }
            }
            try {
                state.revoke(store) {
                    scopeCalls++
                    throw scopeFailure
                }
                assertTrue("must throw for $combo", false)
            } catch (t: Throwable) {
                // CE 或 Exception 皆視為失敗並彙整重拋；記憶體已先收回、審計已清。
                assertTrue("expected Exception for $combo, got $t", t is Exception)
                assertEquals("store must be attempted for $combo", 1, storeCalls)
                assertEquals("scope must be attempted for $combo", 1, scopeCalls)
                // primary＝store 先拋者（先到為準）；scope 失敗以 suppressed 掛入。
                assertSame("primary must be the store failure for $combo", storeFailure, t)
                assertTrue(
                    "suppressed must contain the scope failure for $combo",
                    t.suppressed.contains(scopeFailure),
                )
                assertEquals(false, state.bridgeSwitchOn)
                assertEquals(false, state.bridgeGranted)
                assertEquals(0, state.auditCount())
            }
        }
    }

    @Test fun persistentRevokeStoreExceptionScopeCancellationAggregatesBoth() = runBlocking {
        // store=CE × scope=Exception 的反向順序：兩失敗皆納入彙整（primary=store CE，
        // scope Exception 以 suppressed 掛入），記憶體已收回、審計清空。
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        state.audit.record(listOf("id"), "B3-x", "DENY")
        val store = object : dev.librepocket.shell.PrivilegeRevokeStore {
            override suspend fun clear() { throw CancellationException("store cancelled") }
        }
        val scopeFailure = IllegalStateException("scope boom")
        try {
            state.revoke(store) { throw scopeFailure }
            assertTrue("must throw", false)
        } catch (t: CancellationException) {
            assertTrue(t.suppressed.contains(scopeFailure))
            assertEquals(false, state.bridgeSwitchOn)
            assertEquals(false, state.bridgeGranted)
            assertEquals(0, state.auditCount())
        }
    }

    @Test fun persistentRevokeSurvivesCallerCancellationNonCancellable() = runBlocking {
        // caller 協程在撤權期間被取消：NonCancellable 清理仍必須執行
        // （store.clear 與 withdrawScope 都被呼叫），不依賴 caller 存活。
        val state = PrivilegeBridgeState(
            bridgeSwitchOn = true,
            bridgeGranted = true,
        )
        val entered = CountDownLatch(1)
        var clearCalls = 0
        var scopeCalls = 0
        val store = object : dev.librepocket.shell.PrivilegeRevokeStore {
            override suspend fun clear() {
                entered.countDown()
                delay(50)
                clearCalls++
            }
        }
        val job = launch {
            state.revoke(store) {
                delay(50)
                scopeCalls++
            }
        }
        // 以 latch 確認 store.clear() 已進入清理區才取消 caller（取代固定 sleep 猜測）；
        // 阻塞 await 移至 IO 緒，避免卡住 runBlocking event loop（子協程永遠等不到世代）。
        val enteredOk = withContext(Dispatchers.IO) {
            entered.await(5_000L, TimeUnit.MILLISECONDS)
        }
        assertTrue("store.clear must be entered within 5s", enteredOk)
        job.cancel()
        val joined = withTimeoutOrNull(5_000) { job.join() }
        assertNotNull("撤權清理在 5s 內未完成", joined)
        assertEquals(1, clearCalls)
        assertEquals(1, scopeCalls)
        assertEquals(false, state.bridgeSwitchOn)
        assertEquals(false, state.bridgeGranted)
    }

    @Test fun cancellationPropagatesWithoutDenyAudit() = runBlocking {
        // 取消必須重拋 CE，不得轉 Denied＋DENY 審計（各 supplier／policy／freeze 全覆）。
        val cancellingPolicy = object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String) =
                throw CancellationException("policy cancelled")
        }
        for (name in listOf("ctxSupplier", "bridgeGranted", "grantCheck", "evaluateFresh", "freezeCtxIterator")) {
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
                    "freezeCtxIterator" -> ElevatedWiring.execute(
                        argv = listOf("id"),
                        reasonCode = "B3-T",
                        confirmed = true,
                        // freeze 路徑：hostile userSwitches iterator 拋 CE ⇒ 重拋 CE（不得轉 null→Denied）。
                        ctxSupplier = {
                            ProjectionContext(
                                flavor = Flavor.GITHUB,
                                userSwitches = ceThrowingSwitches(),
                            )
                        },
                        policyStore = allowPolicy(),
                        runner = runner,
                        audit = audit,
                        grantCheck = { validGrant() },
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

    // --- MINOR-1：供應池＋freezeCtx 直接單元測試（有界性釘住） ---

    @Test fun nonCooperativeContextSupplierFailsClosedBounded() = runBlocking {
        // 非協作 ctxSupplier（忽略 interrupt 的 spin）：caller 恆有界（< 2s；實作上界 ~1.2s）；
        // stop 旗標讓殘留 worker 於測試尾聲立即退出（避免跨測試佔用池槽）。
        val stop = AtomicBoolean(false)
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        try {
            val start = System.currentTimeMillis()
            val r = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = {
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!stop.get() && System.nanoTime() < end) { /* spin, ignore interrupt */ }
                    elevatedCtx()
                },
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = { validGrant() },
                bridgeGranted = { true },
            )
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ShellResult.Denied)
            assertTrue("caller must be bounded, elapsed=${elapsed}ms", elapsed < 2_000)
            assertEquals(0, runner.calls)
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        } finally {
            stop.set(true)
        }
    }

    @Test fun nonCooperativeGrantCheckFailsClosedBounded() = runBlocking {
        // 非協作 grantCheck（忽略 interrupt 的 spin）：caller 恆有界（< 2s；實作上界 ~1.2s）；一筆 DENY、零 spawn。
        val stop = AtomicBoolean(false)
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        try {
            val start = System.currentTimeMillis()
            val r = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx() },
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = {
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (!stop.get() && System.nanoTime() < end) { /* spin, ignore interrupt */ }
                    validGrant()
                },
                bridgeGranted = { true },
            )
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ShellResult.Denied)
            assertTrue("caller must be bounded, elapsed=${elapsed}ms", elapsed < 2_000)
            assertEquals(0, runner.calls)
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        } finally {
            stop.set(true)
        }
    }

    @Test fun supplierPoolSaturationFailsClosedFast() = runBlocking {
        // 池飽和：兩個 execute 併發卡住 ctx supplier（佔滿 ElevatedSupplierPool 兩槽）；
        // 第三個 execute 立即 fail-closed（池滿 trySubmit→null 路徑，elapsed < 500ms）。
        val entered = CountDownLatch(2)
        val stop = AtomicBoolean(false)
        val stuckCtx: () -> ProjectionContext = {
            entered.countDown()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            // 非協作：吞掉 interrupt（pool shutdown cancel(true)），只認 stop 旗標。
            while (!stop.get() && System.nanoTime() < end) {
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) { /* non-cooperative: ignore */ }
            }
            elevatedCtx()
        }
        val auditA = PrivilegeAuditLog()
        val auditB = PrivilegeAuditLog()
        val auditC = PrivilegeAuditLog()
        val runnerA = SpawnCounter()
        val runnerB = SpawnCounter()
        val runnerC = SpawnCounter()
        try {
            val deferredA = async {
                ElevatedWiring.execute(
                    argv = listOf("id"),
                    reasonCode = "B3-T",
                    confirmed = true,
                    ctxSupplier = stuckCtx,
                    policyStore = allowPolicy(),
                    runner = runnerA,
                    audit = auditA,
                    grantCheck = { validGrant() },
                    bridgeGranted = { true },
                )
            }
            val deferredB = async {
                ElevatedWiring.execute(
                    argv = listOf("id"),
                    reasonCode = "B3-T",
                    confirmed = true,
                    ctxSupplier = stuckCtx,
                    policyStore = allowPolicy(),
                    runner = runnerB,
                    audit = auditB,
                    grantCheck = { validGrant() },
                    bridgeGranted = { true },
                )
            }
            // 兩個 parked worker 都已進入世代（12s 上限容忍其他測試的殘留 worker 暫佔槽；正常 ms 級命中）；
            // 阻塞式 latch await 移至 IO 緒，避免卡住 runBlocking event loop（子協程永遠等不到世代）。
            val enteredOk = withContext(Dispatchers.IO) {
                entered.await(12_000L, TimeUnit.MILLISECONDS)
            }
            assertTrue("both parked suppliers must enter pool workers", enteredOk)
            // 第三個 execute：池滿 → trySubmit 即時 null → Denied（不排隊、零 spawn）。
            val start = System.currentTimeMillis()
            val r = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx() },
                policyStore = allowPolicy(),
                runner = runnerC,
                audit = auditC,
                grantCheck = { validGrant() },
                bridgeGranted = { true },
            )
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ShellResult.Denied)
            assertTrue("pool-full must fail closed immediately, elapsed=${elapsed}ms", elapsed < 500)
            assertEquals(0, runnerC.calls)
            assertEquals(mapOf("DENY" to 1), auditC.countByVerdict())
            // 前兩個 parked caller 逾時於 1s（< 1.2s）即 fail-closed（與第三個無關）：
            // 此時供應者仍 park（stop=false），caller get(1s) 逾時 → Denied；
            // 阻塞 await 含 withTimeoutOrNull 保險（worker 殘留於池中，隨 finally 退出）。
            val resultA = withTimeoutOrNull(12_000L) { deferredA.await() }
            val resultB = withTimeoutOrNull(12_000L) { deferredB.await() }
            assertTrue("A must fail closed (Denied), got $resultA", resultA is ShellResult.Denied)
            assertTrue("B must fail closed (Denied), got $resultB", resultB is ShellResult.Denied)
        } finally {
            stop.set(true)
        }
    }

    @Test fun freezeSwitchesOverLimitDenied() = runBlocking {
        // userSwitches 65 筆 > MAX_FROZEN_SWITCHES(64) → freeze null → Denied＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = (1..65).associate { "k$it" to true })
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun freezeKeyOver128BytesDenied() = runBlocking {
        // 單一 key 129 UTF-8 bytes > MAX_FROZEN_STRING_BYTES(128) → Denied＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = mapOf("k".repeat(129) to true))
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun freezeNonBooleanValueDenied() = runBlocking {
        // value 非 Boolean（raw Map 經 supplier 回傳）→ 類型不符 → Denied＋DENY、零 spawn。
        @Suppress("UNCHECKED_CAST")
        val rawSwitches = mapOf("privilege_bridge" to 1) as Map<String, Boolean>
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = rawSwitches)
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun freezeKeyMultiByteOverLimitDenied() = runBlocking {
        // userSwitches key＝「中」×43：43 UTF-16 units ≤ MAX_FROZEN_STRING_BYTES(128) 過字元門；
        // 43×3＝129 UTF-8 bytes > 128 → 被 byte 門擋下 → Denied＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = mapOf("中".repeat(43) to true))
        assertEquals(43, "中".repeat(43).length)
        assertEquals(129, "中".repeat(43).toByteArray(Charsets.UTF_8).size)
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun freezeEntriesThrowsCmeDenied() = runBlocking {
        // entries 迭代拋 ConcurrentModificationException → freeze null → Denied＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = cmeThrowingSwitches())
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun grantedPermissionsOverLimitDenied() = runBlocking {
        // grantedPermissions 65 筆 > MAX_FROZEN_PERMISSIONS(64) → Denied＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(grantedPermissions = (1..65).map { "perm$it" }.toSet())
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun grantedPermissionsOver128BytesDenied() = runBlocking {
        // grantedPermissions 單一字串 129 UTF-8 bytes → Denied＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(grantedPermissions = setOf("p".repeat(129)))
        val w = wiringArgs(runner = runner, audit = audit, ctx = ctx)
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
        assertDeniedZeroSpawn(r, runner, audit)
    }

    @Test fun grantedPermissionsFlipOnReverifyVoids() = runBlocking {
        // 重驗一致性：grantedPermissions 翻轉（前驗 {a}、重驗 {a,b}）→ 作廢、零 spawn、一筆 DENY。
        var calls = 0
        val ctxSupplier: () -> ProjectionContext = {
            calls++
            elevatedCtx().copy(
                grantedPermissions = if (calls == 1) setOf("A") else setOf("A", "B"),
            )
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
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

    // --- Policy TIMEOUT/ERROR 標籤 pin（前驗／重驗 × 掛起／拋出） ---

    @Test fun policyHangOnPrecheckDeniesWithVerdictTimeoutTag() = runBlocking {
        // evaluateFresh 永不回傳（suspendCancellableCoroutine 無 resume）：
        // withTimeoutOrNull(1s) 協作式逾時於 ~1s 後回 null 且 block 未返回 → verdict=TIMEOUT。
        val hangPolicy = object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                suspendCancellableCoroutine { }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = hangPolicy,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("verdict=TIMEOUT"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun policyThrowOnPrecheckDeniesWithVerdictErrorTag() = runBlocking {
        val boomPolicy = object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                throw IllegalStateException("boom")
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = boomPolicy,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("verdict=ERROR"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun policyHangOnReverifyDeniesWithVerdictTimeoutTag() = runBlocking {
        // 前驗 ALLOW（第一次呼叫）、重驗掛起（第二次呼叫永不回傳）→ verdict=TIMEOUT。
        val hangOnSecond = object : PolicyStore by allowPolicy() {
            private var calls = 0
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                if (++calls == 1) {
                    allowPolicy().evaluateFresh(action, resource)
                } else {
                    suspendCancellableCoroutine { }
                }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = hangOnSecond,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("verdict=TIMEOUT"))
        assertTrue("$r", r.message.contains("policy denied on reverify"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun policyThrowOnReverifyDeniesWithVerdictErrorTag() = runBlocking {
        // 前驗 ALLOW（第一次呼叫）、重驗拋出（第二次呼叫 throw）→ verdict=ERROR。
        val boomOnSecond = object : PolicyStore by allowPolicy() {
            private var calls = 0
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                if (++calls == 1) {
                    allowPolicy().evaluateFresh(action, resource)
                } else {
                    throw IllegalStateException("boom")
                }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = boomOnSecond,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("verdict=ERROR"))
        assertTrue("$r", r.message.contains("policy denied on reverify"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    // --- 正向邊界 pin：上限恰滿（64 筆／128 byte）仍須走 happy path（防 off-by-one） ---

    @Test fun sixtyFourSwitchesBoundaryHappyPath() = runBlocking {
        // userSwitches 恰 64 筆（含 privilege_bridge）＝ MAX_FROZEN_SWITCHES 到頂 → Ok。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(
            userSwitches = mapOf("privilege_bridge" to true) + (1..63).associate { "switch$it" to true },
        )
        assertEquals(64, ctx.userSwitches.size)
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { ctx },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        val row = audit.snapshot().single()
        assertTrue("argvHash must be set", row.argvHash.isNotEmpty())
        assertEquals("OK", audit.snapshot().single().verdict)
    }

    @Test fun exactly128ByteKeyBoundaryHappyPath() = runBlocking {
        // 單一 key 恰 128 UTF-8 bytes＝ MAX_FROZEN_STRING_BYTES 到頂 → Ok。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = mapOf("privilege_bridge" to true, "k".repeat(128) to true))
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { ctx },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        val row = audit.snapshot().single()
        assertTrue("argvHash must be set", row.argvHash.isNotEmpty())
        assertEquals("OK", audit.snapshot().single().verdict)
    }

    @Test fun sixtyFourGrantedPermissionsBoundaryHappyPath() = runBlocking {
        // grantedPermissions 恰 64 筆（短字串）＝ MAX_FROZEN_PERMISSIONS 到頂 → Ok。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(grantedPermissions = (1..64).map { "perm$it" }.toSet())
        assertEquals(64, ctx.grantedPermissions.size)
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { ctx },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        val row = audit.snapshot().single()
        assertTrue("argvHash must be set", row.argvHash.isNotEmpty())
        assertEquals("OK", audit.snapshot().single().verdict)
    }

    @Test fun exactly128BytePermissionBoundaryHappyPath() = runBlocking {
        // 單一 permission 恰 128 UTF-8 bytes＝ MAX_FROZEN_STRING_BYTES 到頂 → Ok。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(grantedPermissions = setOf("p".repeat(128)))
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { ctx },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        val row = audit.snapshot().single()
        assertTrue("argvHash must be set", row.argvHash.isNotEmpty())
        assertEquals("OK", audit.snapshot().single().verdict)
    }
}
