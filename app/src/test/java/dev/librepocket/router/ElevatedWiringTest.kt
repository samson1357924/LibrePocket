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
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    // ---- T5-1 pin：ElevatedDispatch／ShellExecTool 的 @JvmSynthetic internal 成員「完整 diff 驗證」----
    // （外部（如 Java 透過 mangled 名稱 `execute$app`）呼叫面在編譯期封死；
    //  反射驗證 ACC_SYNTHETIC——防註解被靜默移除而不觸發任何測試。
    //  T5-1 將舊 pin（各類≥4 個、皆 synthetic、target 非空）強化為「完整集合」斷言：
    //  掃描兩個類別**所有**宣告的公開方法（instance＋static、含 `$default` bridges），
    //  證明整個 diff 沒有任何非 synthetic 公開成員從這兩個類別逃逸——不只四個被點名的
    //  gate 成員受檢，任何新增的公開成員（無論名稱）都會讓本測試失敗並點名。
    //  既有 shell.exec 公開 API（`ShellExecTool.execute`、`ShellExecTool.checkSwitch`）
    //  為刻意保留、非提權面的非 synthetic 公開成員，以具名允許清單固定；除此之外
    //  一律要求 synthetic（ElevatedDispatch 零外部 API）。）

    /** 方法簽名（宣告類名＋方法名＋參數簡名），供失敗訊息精確點名。 */
    private fun publicMethodSignature(method: java.lang.reflect.Method): String =
        "${method.declaringClass.simpleName}.${method.name}(" +
            method.parameterTypes.joinToString(",") { it.simpleName } + ")"

    @Test fun internalGateMembersAreJvmSynthetic() {
        val dispatch = dev.librepocket.router.ElevatedDispatch::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
        val execTool = dev.librepocket.shell.ShellExecTool::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
        // shell.exec 的既有公開 API（刻意保留、非提權面）：非 synthetic；其餘一律須 synthetic。
        val allowedNonSynthetic = setOf(
            "ShellExecTool.execute",   // shell.exec 受限執行入口（公開 API）
            "ShellExecTool.checkSwitch", // shell.exec 開關檢查（公開 API）
        )

        // 1) 完整集合斷言（名字全集、精確到簽名）：兩類別宣告的公開方法名稱集合就是此 pin 的
        //    期望值——任何新增／移除的公開成員都會在此失敗並點名（含未來新增的 `$default` bridge）。
        val observedNames = (dispatch + execTool).map { publicMethodSignature(it) }.toSet()
        val expectedNames = setOf(
            "ElevatedDispatch.execute\$app(ToolDef,boolean,Projection,ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long)",
            "ElevatedDispatch.execute\$app\$default(ElevatedDispatch,ToolDef,boolean,Projection,ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long,int,Object)",
            "ElevatedDispatch.executeWithFrozen\$app(ToolDef,ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long)",
            "ElevatedDispatch.executeWithFrozen\$app\$default(ElevatedDispatch,ToolDef,ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long,int,Object)",
            "ShellExecTool.checkSwitch(boolean)",
            "ShellExecTool.execute(List,RestrictedShell,boolean,long)",
            "ShellExecTool.execute\$default(ShellExecTool,List,RestrictedShell,boolean,long,int,Object)",
            "ShellExecTool.executeElevated\$app(ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long)",
            "ShellExecTool.executeElevated\$app\$default(ShellExecTool,ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long,int,Object)",
            "ShellExecTool.executeElevatedFrozen\$app(ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long)",
            "ShellExecTool.executeElevatedFrozen\$app\$default(ShellExecTool,ElevatedRequest,ElevatedShellRunner,PrivilegeAuditLog,long,int,Object)",
        )
        val missing = expectedNames - observedNames
        val unexpected = observedNames - expectedNames
        assertTrue(
            "declared public methods must be exactly the pinned set; missing: $missing; unexpected: $unexpected",
            missing.isEmpty() && unexpected.isEmpty(),
        )

        // 2) 除具名允許清單外，所有公開成員一律 JVM synthetic（Member.isSynthetic ≡ ACC_SYNTHETIC）。
        val nonSynthetic = (dispatch + execTool)
            .filter { !it.isSynthetic && publicMethodSignature(it).substringBefore('(') !in allowedNonSynthetic }
            .map { publicMethodSignature(it) }
        assertTrue(
            "every public method outside the shell.exec allowlist must be JVM synthetic; escaped: $nonSynthetic",
            nonSynthetic.isEmpty(),
        )

        // 3) 名稱形狀：所有公開成員名稱（去掉 $<module>／$default 後綴）必須對應 gate 路徑
        //    （execute 開頭）或具名允許清單（checkSwitch）；不允許任何意外名稱。
        val shapeViolations = (dispatch + execTool)
            .map { it.name }
            .filter { name -> name.substringBefore('$').let { root ->
                !root.startsWith("execute") && root != "checkSwitch"
            } }
        assertTrue(
            "unexpected public member names (must map to gate paths 'execute*' or the allowlist): $shapeViolations",
            shapeViolations.isEmpty(),
        )

        // 4) 四個 gate 名稱必須存在（含 `$<module>`／`$default` 後綴），且其本體與 bridge 皆 synthetic。
        val gateRoots = listOf("execute", "executeWithFrozen", "executeElevated", "executeElevatedFrozen")
        val strippedRoots = (dispatch + execTool).map { it.name.substringBefore('$') }
        for (gate in gateRoots) {
            assertTrue(
                "missing gate member '$gate' among declared public methods: ${(dispatch + execTool).map { it.name }.sorted()}",
                gate in strippedRoots,
            )
        }
        val gateMembers = (dispatch + execTool)
            .filter { it.name.substringBefore('$') in gateRoots }
            .filter { !(it.declaringClass.simpleName == "ShellExecTool" && it.name == "execute") } // shell.exec 公開 API，非提權 gate
        val nonSyntheticGates = gateMembers.filter { !it.isSynthetic }.map { publicMethodSignature(it) }
        assertTrue(
            "all gate-bearing members must be JVM synthetic: $nonSyntheticGates",
            nonSyntheticGates.isEmpty(),
        )
    }

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

    private fun denyPolicy(): InMemoryPolicyStore = InMemoryPolicyStore(
        initialRules = listOf(
            PolicyRule("*:*", Verdict.DENY, 0),
            PolicyRule("${ElevatedWiring.POLICY_ACTION}:${ElevatedWiring.TOOL_NAME}", Verdict.DENY, 10),
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
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertEquals(1, audit.size())
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
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
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
        assertEquals("grant invalid (fail-closed, no spawn)", (r as ShellResult.Denied).message)
        assertFalse("grant supplier reason must not be echoed",
            r.message.contains("NEVER_GRANTED"))
        assertEquals(0, runner.calls)
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertEquals(1, audit.size())
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
        // 執行期間竄改 caller 保留的 mutable list：執行與審計仍須用入口快照（非竄改後值）。
        val argv = mutableListOf("id")
        val runner = object : ElevatedShellRunner {
            var calls = 0
            var lastArgv: List<String>? = null
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                lastArgv = request.argv.toList()
                argv[0] = "rm" // runner 執行期間竄改 caller list（模擬 TOCTOU 嘗試）
                return ShellResult.Ok("ok", "", 0, false)
            }
        }
        val audit = PrivilegeAuditLog()
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
        // 竄改確實發生（caller 保留的 list 已被改寫），但快照不受污染。
        assertEquals(listOf("rm"), argv)
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

    // --- T2-3：policy 超限 ALLOW 一律視為逾時（monotonic deadline 權威門；withTimeoutOrNull 為配合作式） ---

    /** sleep>budget 後仍回傳 ALLOW 的非協作 policy（永不掛起；Blocking 體）。 */
    private fun slowAllowPolicy(sleepMillis: Long): PolicyStore =
        object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
                Thread.sleep(sleepMillis)
                return PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
            }
        }

    @Test fun policyPrecheckOverBudgetAllowDeniedAsTimeout() = runBlocking {
        // 非協作 policy：永不掛起（Thread.sleep 1500ms > 1000ms budget）後仍回 ALLOW。
        // withTimeoutOrNull 為配合作式，block 跑完才有返回值——超出 budget 的 ALLOW
        // 不可信：monotonic deadline 權威門一律視為逾時 →
        // Denied「policy denied (verdict=TIMEOUT…)」、零 spawn、恰一筆 DENY、verdict DENY。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = slowAllowPolicy(1_500L),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("verdict=TIMEOUT"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun policyPrecheckUnderBudgetAllowPasses() = runBlocking {
        // 上界內（sleep 50ms < 1000ms budget）回 ALLOW：Ok、恰一筆 OK 審計、runner 恰一次。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = slowAllowPolicy(50L),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
    }

    @Test fun policyReverifyOverBudgetAllowDeniedAsTimeout() = runBlocking {
        // 重驗點（fresh policy 第二次呼叫）：前驗快速 ALLOW、重驗 sleep 1500ms 後仍回 ALLOW
        // → 超出 budget 的 ALLOW 不可信 → Denied「policy denied on reverify (verdict=TIMEOUT…)」、
        // 零 spawn、恰一筆 DENY、verdict DENY、審計 argv 為快照 argv。
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val countingPolicy = object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision {
                if (calls.incrementAndGet() == 1) {
                    return PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
                }
                Thread.sleep(1_500L)
                return PolicyDecision(Verdict.ALLOW, null, System.currentTimeMillis())
            }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = countingPolicy,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("verdict=TIMEOUT"))
        assertTrue("$r", r.message.contains("reverify"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    @Test fun policyReverifyUnderBudgetAllowPasses() = runBlocking {
        // 兩點皆在 budget 內（各 sleep 50ms）回 ALLOW：Ok、runner 恰一次。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = slowAllowPolicy(50L),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
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

    @Test fun nonCooperativeSupplierDoesNotPoisonSharedWorkers() = runBlocking {
        // pin（T2-5，誠實範圍）：ctxSupplier 忽略 interrupt、spin 超過 supplier 預算被拒
        // （有界 wall-clock、零 spawn、恰一筆 DENY）；其後 cooperative call 完成 Ok——
        // 證明非協作區塊殘留期間，後續 execute 無需等待該區塊退出、pool／IO worker 仍能服務。
        // **不釘** `clearSharedWorkerInterruptFlag()` 本身：interrupt flag 是 Dispatchers.IO
        // 共享 worker 的 thread-local，caller（test 線程）無從觀察；該 helper 的保證由
        // finally 位置＋helper KDoc 提供，且殘旗後果僅影響下一個共享 worker 任務——
        // 本測試以「後續 call 完成」作為間接、非決定性觀測。釘死 helper 需內部可見性，不值得。
        val stop = AtomicBoolean(false)
        val runner1 = SpawnCounter()
        val audit1 = PrivilegeAuditLog()
        try {
            val start = System.currentTimeMillis()
            val r1 = ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = {
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    // 非協作：吞掉 interrupt（pool cancel(true)），只認 stop 旗標。
                    while (!stop.get() && System.nanoTime() < end) { /* spin, ignore interrupt */ }
                    elevatedCtx()
                },
                policyStore = allowPolicy(),
                runner = runner1,
                audit = audit1,
                grantCheck = { validGrant() },
                bridgeGranted = { true },
            )
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r1", r1 is ShellResult.Denied)
            assertTrue("caller must be bounded, elapsed=${elapsed}ms", elapsed < 2_000)
            assertEquals(0, runner1.calls)
            assertTrue("audit must have records", audit1.size() >= 1)
            assertEquals(mapOf("DENY" to 1), audit1.countByVerdict())
        } finally {
            stop.set(true)
        }
        // 後續 cooperative call：無需等待殘留區塊退出即可完成。
        val runner2 = SpawnCounter()
        val audit2 = PrivilegeAuditLog()
        val r2 = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner2,
            audit = audit2,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("subsequent call must complete Ok (shared workers not poisoned): $r2", r2 is ShellResult.Ok)
        assertEquals(1, runner2.calls)
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
        // 第三個 execute 立即 fail-closed（池滿 trySubmit→null 路徑；實作上界 ~1.2s，
        // 故上限放寬為 2s 防慢速 CI 誤判）。
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
            assertTrue("pool-full must fail closed fast, elapsed=${elapsed}ms", elapsed < 2_000)
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

    @Test fun cancelledCallerDuringPoolSaturationThrowsCancellationNotDenial() = runBlocking {
        // 池飽和＋已取消 caller 的確定性 pin：兩個 parked supplier 佔滿有界池（同 supplierPoolSaturationFailsClosedFast 骨架），
        // 第三個 caller 於 execute() 內即確認 context 已取消（入口 ensureActive 重拋 CE，不是 Denied、零審計、零 spawn）。
        // 誠實盤點（為何是這個 pin 而非虛構交錯）：boundedSupplierCall 進池是非掛起的原子 trySubmit
        // （accept 或 reject 原子生效、池滿即 null），「取消與進池賽跑」沒有可觀測中間態；
        // 池滿拒絕路徑本身無掛起點，CE 只可能在 ensureActive（入口）或供應者面顯現。
        // body 內自取消保證進入 execute 時 context 已取消：ensureActive 首行確定性重拋 CE。
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
            // 兩個 parked worker 都已進入世代（bounded 12s；blocking await 移至 IO 緒）。
            val enteredOk = withContext(Dispatchers.IO) {
                entered.await(12_000L, TimeUnit.MILLISECONDS)
            }
            assertTrue("both parked suppliers must enter pool workers", enteredOk)
            // 第三個 caller：池飽和下，入口 ensureActive 即重拋 CE（不是 Denied）。
            // 形：先進 async 再於 body 內取消自己的 job → resume 到 execute() 時 dispatch 檢查必拋；
            // execute() 首行 coroutineContext.ensureActive() 亦因 job 已取消而重拋 CE。
            val cancelledJob = kotlinx.coroutines.Job()
            val third = async(cancelledJob) {
                cancelledJob.cancel()
                ElevatedWiring.execute(
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
            }
            try {
                third.await()
                assertTrue("cancelled caller must throw CancellationException, not not return", false)
            } catch (_: CancellationException) {
                // 預期：CE 重拋（不是 Denied）；零 spawn、零審計。
            }
            assertEquals(0, runnerC.calls)
            assertEquals(0, auditC.size())
            // 前兩個 parked caller 逾時於 1s 即 fail-closed（與第三個無關）。
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

    /**
     * userSwitches entries 迭代拋 ClassCastException 的 hostile Map（raw-cast 失敗針）。
     *
     * 註：字面非 Map 物件無法經 Kotlin/Java 位元組碼塞入 `Map` 型別欄位——任何進入
     * Map 型別位置的轉型都會發出 checkcast（已實測：`smuggle<T>` 擦除走私在指派點
     * 即 CCE）。故以迭代拋 ClassCastException 的 hostile Map 釘住同一失敗出口：
     * doFreeze 內 `catch (_: Throwable) → null` → fail-closed。
     */
    private fun cceThrowingSwitches(): Map<String, Boolean> =
        object : AbstractMap<String, Boolean>() {
            override val entries: MutableSet<MutableMap.MutableEntry<String, Boolean>>
                get() = object : AbstractSet<MutableMap.MutableEntry<String, Boolean>>() {
                    override val size: Int get() = 1
                    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Boolean>> =
                        throw ClassCastException("switch entry cast failed")
                }
        }

    /** grantedPermissions iterator 拋 ClassCastException 的 hostile Set（raw-cast 失敗針）。 */
    private fun cceThrowingPermissions(): Set<String> =
        object : AbstractSet<String>() {
            override val size: Int get() = 1
            override fun iterator(): MutableIterator<String> =
                throw ClassCastException("permission element cast failed")
        }

    @Test fun cceThrowingUserSwitchesFailClosedZeroSpawn() = runBlocking {
        // userSwitches 迭代拋 ClassCastException → doFreeze catch(Throwable) → null
        // → Denied("projection context not freezable")＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(userSwitches = cceThrowingSwitches())
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
        assertTrue("$r", (r as ShellResult.Denied).message.contains("projection context not freezable"))
    }

    @Test fun cceThrowingGrantedPermissionsFailClosedZeroSpawn() = runBlocking {
        // grantedPermissions 迭代拋 ClassCastException → doFreeze catch(Throwable) → null
        // → Denied("projection context not freezable")＋DENY、零 spawn。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val ctx = elevatedCtx().copy(grantedPermissions = cceThrowingPermissions())
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
        assertTrue("$r", (r as ShellResult.Denied).message.contains("projection context not freezable"))
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

    // --- Phase 2（PR #168）：執行期間竄改／重驗翻轉／dispatch 直測／取消與快照失敗 pin ---

    @Test fun sameSizeArgvContentSwapRejectedOnReverify() = runBlocking {
        // argv[0] 同長度同元素數替換（"id00"→"evil"）：size 與單項字元數皆不變，
        // 唯內容重驗（argvMatchesSnapshot）可擋——證明比對不被 size/長度捷徑繞過。
        val mutable = mutableListOf("id00")
        assertEquals(4, "id00".length)
        assertEquals(4, "evil".length)
        var calls = 0
        val ctxSupplier: () -> ProjectionContext = {
            calls++
            if (calls == 2) mutable[0] = "evil"
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
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertEquals("evil", mutable[0])
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id00")), row.argvHash)
    }

    @Test fun reverifyFlavorFlipDeniedZeroSpawnPinsSnapshotHash() = runBlocking {
        // 前驗 GITHUB、重驗 PLAY：flavor 翻轉即作廢（context changed），零 spawn、一筆 DENY、
        // argvHash 記 snapshot argv（非空 argv）。
        var calls = 0
        val ctxSupplier: () -> ProjectionContext = {
            calls++
            if (calls == 1) elevatedCtx(Flavor.GITHUB) else elevatedCtx(Flavor.PLAY)
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
        assertTrue("$r", (r as ShellResult.Denied).message.contains("context changed after confirm"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    @Test fun reverifyUserSwitchFlipDeniedZeroSpawn() = runBlocking {
        // 重驗側 privilege_bridge 開關翻轉（userSwitches ctx 開關面；與 bridgeGranted
        // 供應者翻轉、與 grant 身份翻轉皆不同的一個面）→ context changed，零 spawn。
        var calls = 0
        val ctxSupplier: () -> ProjectionContext = {
            calls++
            if (calls == 1) elevatedCtx(bridgeOn = true) else elevatedCtx(bridgeOn = false)
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
        assertTrue("$r", (r as ShellResult.Denied).message.contains("context changed after confirm"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    @Test fun reverifyPolicyAllowToDenyDeniedZeroSpawn() = runBlocking {
        // 前驗 ALLOW（首調）、重驗 DENY（次調）：policy 翻轉即作廢，零 spawn、一筆 DENY。
        val denyOnSecond = object : PolicyStore by allowPolicy() {
            private var calls = 0
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                if (++calls == 1) {
                    allowPolicy().evaluateFresh(action, resource)
                } else {
                    denyPolicy().evaluateFresh(action, resource)
                }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = denyOnSecond,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("policy denied on reverify"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    // --- executeWithFrozen 直測（同模組 internal，單一凍結快速路徑防禦門） ---

    @Test fun executeWithFrozenReasonTooLongDeniedWithoutSpawnOrRunner() = runBlocking {
        // reasonCode 512B+1 → BLACKLISTED Denied、空 argv＋空 reason 一筆 DENY、runner 不被呼叫。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val tooLong = "a".repeat(513)
        assertTrue(PrivilegeAuditLog.isReasonCodeTooLong(tooLong))
        val r = ElevatedDispatch.executeWithFrozen(
            tool = ToolRegistry.find("shell.elevated")!!,
            frozen = ElevatedRequest(listOf("id"), tooLong),
            runner = runner,
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertTrue(r.message.contains("reasonCode too long"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(emptyList()), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex(""), row.reasonHash)
    }

    @Test fun executeWithFrozenNormalFrozenExecutesAuditsOnce() = runBlocking {
        // 正常 frozen（shell.elevated ToolDef＋合法 argv/reason）：runner 恰呼叫一次、
        // 恰一筆審計、verdict OK、argvHash 綁定快照 argv。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedDispatch.executeWithFrozen(
            tool = ToolRegistry.find("shell.elevated")!!,
            frozen = ElevatedRequest(listOf("id"), "M8-OK"),
            runner = runner,
            audit = audit,
        )
        assertTrue("$r", r is ShellResult.Ok)
        assertEquals(1, runner.calls)
        assertEquals(listOf("id"), runner.lastArgv)
        assertEquals(1, audit.size())
        assertEquals(mapOf("OK" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex("M8-OK"), row.reasonHash)
    }

    @Test fun executeWithFrozenRejectsNonElevatedTool() {
        // tool.name 非 shell.elevated → require 拋 IllegalArgumentException。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val shellExec = ToolRegistry.find("shell.exec")!!
        assertThrows(IllegalArgumentException::class.java) {
            ElevatedDispatch.executeWithFrozen(
                tool = shellExec,
                frozen = ElevatedRequest(listOf("id"), "M8-X"),
                runner = runner,
                audit = audit,
            )
        }
        assertEquals(0, runner.calls)
        assertEquals(0, audit.size())
    }

    // --- cheap-gate／前驗異常注入（!confirmed、bridge/grant supplier 直拋） ---

    @Test fun unconfirmedCtxSupplierThrowsDeniesProjectionUnavailableZeroSpawn() = runBlocking {
        // !confirmed 且 ctxSupplier 直拋 IllegalStateException → evalBounded 收斂 null →
        // Denied（projection unavailable）、零 spawn、一筆 DENY。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = false,
            ctxSupplier = { throw IllegalStateException("ctx boom") },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("projection unavailable"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun bridgeGrantedThrowsDeniedZeroSpawn() = runBlocking {
        // bridgeGranted 直拋 IllegalStateException（非逾時；Throwable catch→null 路徑，
        // 與 blockingGrantCheck 的 sleep→逾時路徑區分）→ Denied，零 spawn、一筆 DENY。
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
            bridgeGranted = { throw IllegalStateException("bridge boom") },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("bridge check failed"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun grantCheckThrowsDeniedZeroSpawn() = runBlocking {
        // grantCheck 直拋 IllegalStateException → Denied（grant check failed），零 spawn、一筆 DENY。
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
            grantCheck = { throw IllegalStateException("grant boom") },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("grant check failed"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    // --- 重驗階段失敗注入（prefix ALLOW/true，第二次翻面） ---

    @Test fun reverifyGrantInvalidDeniedZeroSpawn() = runBlocking {
        // 前驗 Valid、重驗 Invalid("SCOPE_REVOKED") → Denied（Invalid reason 不回顯，固定字串），
        // 零 spawn、一筆 DENY、argvHash 記 snapshot argv。
        var calls = 0
        val grantCheck: () -> ElevatedWiring.ElevatedGrant = {
            calls++
            if (calls == 1) validGrant() else ElevatedWiring.ElevatedGrant.Invalid("SCOPE_REVOKED")
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
            grantCheck = grantCheck,
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals("grant invalid (fail-closed, no spawn)", (r as ShellResult.Denied).message)
        assertFalse("grant supplier reason must not be echoed",
            r.message.contains("SCOPE_REVOKED"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    @Test fun reverifyGrantBlankProviderIdMalformedDeniedZeroSpawn() = runBlocking {
        // 前驗 Valid("preset:openai")，重驗 Valid(blank providerId) → Denied
        // 「grant identity malformed on reverify」，零 spawn、一筆 DENY。
        var calls = 0
        val grantCheck: () -> ElevatedWiring.ElevatedGrant = {
            calls++
            if (calls == 1) validGrant() else ElevatedWiring.ElevatedGrant.Valid("  ", TEST_REVISION)
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
            grantCheck = grantCheck,
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("grant identity malformed"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), audit.snapshot().single().argvHash)
    }

    @Test fun reverifyBridgeThrowsDeniedZeroSpawn() = runBlocking {
        // 前驗 true，重驗 bridgeGranted 直拋 IllegalStateException → Denied
        // 「privilege bridge recheck failed」，零 spawn、一筆 DENY。
        var calls = 0
        val bridgeGranted: () -> Boolean = {
            calls++
            if (calls == 1) true else throw IllegalStateException("bridge reverify boom")
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
            bridgeGranted = bridgeGranted,
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("privilege bridge recheck failed"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), audit.snapshot().single().argvHash)
    }

    @Test fun elevatedSnapshotBlockingArgvDeniedZeroSpawnBounded() = runBlocking {
        // 自訂 AbstractList：size()／iterator() 永久阻塞（吞中斷、hold 於測試結尾釋放）→
        // frozenOrNull 有界 ~1.2s（1s get＋200ms grace）→ Denied(BLACKLISTED, "elevated snapshot failed")、
        // 零 spawn、一筆 DENY（空 argv）。
        val hold = CountDownLatch(1) // 測試中不釋放：模擬永久阻塞（釋放於 finally）
        val blockingArgv: List<String> = object : AbstractList<String>() {
            override val size: Int get() {
                while (hold.count > 0) {
                    try {
                        hold.await()
                    } catch (_: InterruptedException) {
                        // 吞中斷：模擬自訂 List 不響應中斷的永久阻塞。
                    }
                }
                return 1
            }
            override fun get(index: Int): String = "id"
            override fun iterator(): MutableIterator<String> {
                while (hold.count > 0) {
                    try {
                        hold.await()
                    } catch (_: InterruptedException) {
                        // 同上。
                    }
                }
                return mutableListOf("id").iterator()
            }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        try {
            val start = System.currentTimeMillis()
            val r = ElevatedWiring.execute(
                argv = blockingArgv,
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx() },
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = { validGrant() },
                bridgeGranted = { true },
            )
            val elapsed = System.currentTimeMillis() - start
            assertTrue("$r", r is ShellResult.Denied)
            assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
            assertTrue(r.message.contains("elevated snapshot failed"))
            assertEquals(0, runner.calls)
            assertEquals(1, audit.size())
            assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
            val row = audit.snapshot().single()
            assertEquals(PrivilegeAuditLog.argvHash(emptyList()), row.argvHash)
            assertTrue("blocked path must be reached (elapsed=${elapsed}ms)", elapsed >= 900L)
            // 有界 ~1.2s（1s get＋200ms grace），下界 900ms 錨住阻塞路徑確實執行，上限 5s 為 backstop。
            assertTrue("frozenOrNull 有界 ~1.2s（1s get＋200ms grace），elapsed=${elapsed}ms", elapsed < 5_000)
        } finally {
            hold.countDown() // 釋放阻塞 worker，避免池槽永久殘留
        }
    }

    @Test fun reverifyCancellationPropagatesWithoutDenyAudit() = runBlocking {
        // 取消於「重驗（第二次）呼叫」：policy／bridge／grant 各自的第二次呼叫拋 CE →
        // execute 重拋 CE、零 spawn、audit 無一筆（第一次呼叫皆須成功）。
        val secondCallCePolicy = object : PolicyStore by allowPolicy() {
            private var calls = 0
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                if (++calls == 1) {
                    allowPolicy().evaluateFresh(action, resource)
                } else {
                    throw CancellationException("policy reverify cancelled")
                }
        }
        for (name in listOf("evaluateFresh", "bridgeGranted", "grantCheck")) {
            val audit = PrivilegeAuditLog()
            val runner = SpawnCounter()
            try {
                when (name) {
                    "evaluateFresh" -> ElevatedWiring.execute(
                        argv = listOf("id"),
                        reasonCode = "B3-T",
                        confirmed = true,
                        ctxSupplier = { elevatedCtx() },
                        policyStore = secondCallCePolicy,
                        runner = runner,
                        audit = audit,
                        grantCheck = { validGrant() },
                        bridgeGranted = { true },
                    )
                    "bridgeGranted" -> {
                        var calls = 0
                        val bridge: () -> Boolean = {
                            calls++
                            if (calls == 1) true else throw CancellationException("bridge reverify cancelled")
                        }
                        ElevatedWiring.execute(
                            argv = listOf("id"),
                            reasonCode = "B3-T",
                            confirmed = true,
                            ctxSupplier = { elevatedCtx() },
                            policyStore = allowPolicy(),
                            runner = runner,
                            audit = audit,
                            grantCheck = { validGrant() },
                            bridgeGranted = bridge,
                        )
                    }
                    else -> {
                        var calls = 0
                        val grant: () -> ElevatedWiring.ElevatedGrant = {
                            calls++
                            if (calls == 1) validGrant() else throw CancellationException("grant reverify cancelled")
                        }
                        ElevatedWiring.execute(
                            argv = listOf("id"),
                            reasonCode = "B3-T",
                            confirmed = true,
                            ctxSupplier = { elevatedCtx() },
                            policyStore = allowPolicy(),
                            runner = runner,
                            audit = audit,
                            grantCheck = grant,
                            bridgeGranted = { true },
                        )
                    }
                }
                assertTrue("$name reverify must throw CE", false)
            } catch (_: CancellationException) {
                // 預期：重拋，不轉 Denied。
            }
            assertEquals("$name must not record DENY", 0, audit.size())
            assertEquals("$name must not spawn", 0, runner.calls)
        }
    }

    @Test fun reverifyArgvRecheckCancellationDeniedZeroSpawnPin() = runBlocking {
        // pin 實際行為（非 CE 重拋）：重驗時 argvMatchesSnapshot 的重凍結經
        // frozenOrNull→copyArgvBounded，iterator 拋 CE 被池 worker 內 `catch (_: Throwable)`
        // 收斂為 null → argvMatchesSnapshot 回 false → Denied("changed after confirm")、
        // 零 spawn、一筆 DENY（argvHash＝snapshot）。註：argvMatchesSnapshot 的外層
        // catch (_: Throwable) 亦接得住（frozenOrNull 全收斂，CE 不可達）——
        // hostile list 的 CE 在 copyArgvBounded 內即被吞（fail-closed）；CE 只在
        // supplier／policy／freeze 路徑重拋（見 cancellationPropagatesWithoutDenyAudit／本檔 reverify CE）。
        var iteratorCalls = 0
        val adversarialArgv: List<String> = object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): MutableIterator<String> {
                if (++iteratorCalls >= 2) {
                    throw CancellationException("recheck iterator cancelled")
                }
                return mutableListOf("id").iterator()
            }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = adversarialArgv,
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("changed after confirm"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    // --- reasonCode 超限（接線層 cheap gate） ---

    @Test fun reasonCodeTooLongDeniedEmptyArgvNoSpawn() = runBlocking {
        // 512B+1（ASCII 'a'×513）→ BLACKLISTED Denied「reasonCode too long」、
        // 零 spawn、一筆 DENY（空 argv＋空 reason 記錄，不哈希無界輸入）。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "a".repeat(513),
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ShellResult.Denied).reason)
        assertTrue(r.message.contains("reasonCode too long"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(emptyList()), row.argvHash)
        assertEquals(PrivilegeAuditLog.sha256Hex(""), row.reasonHash)
    }

    // --- M7：撤權於執行中生效的 pin（bridgeGranted supplier 共享可變狀態） ---

    @Test fun privilegeBridgeRevokedMidRunBlocksNextCall() = runBlocking {
        // 共享 AtomicBoolean 由 runner 於執行期間翻 false（模擬撤權於執行中生效）：
        // 當輪已過門→執行；次一 call 的前驗 bridgeGranted 即見 false → Denied
        // 「privilege bridge off」、零 spawn、一筆 DENY。
        val bridgeOn = AtomicBoolean(true)
        val runner = object : ElevatedShellRunner {
            var calls = 0
            var lastArgv: List<String>? = null
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                lastArgv = request.argv.toList()
                bridgeOn.set(false) // 撤權於執行中生效
                return ShellResult.Ok("ok", "", 0, false)
            }
        }
        val audit = PrivilegeAuditLog()
        val first = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { bridgeOn.get() },
        )
        assertTrue("$first", first is ShellResult.Ok)
        // 撤權已生效：次一 call 立即被拒（bridge supplier 見 false）。
        val second = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { bridgeOn.get() },
        )
        assertTrue("$second", second is ShellResult.Denied)
        assertTrue("$second", (second as ShellResult.Denied).message.contains("privilege bridge off"))
        assertEquals(1, runner.calls) // 次一 call 未 spawn
        assertEquals(2, audit.size())
        assertEquals(mapOf("OK" to 1, "DENY" to 1), audit.countByVerdict())
    }

    // --- runner 拋 CancellationException 的實際語義（以實碼為準 pin） ---

    @Test fun runnerCancellationMapsFailedAndAuditsOnce() = runBlocking {
        // pin 實際行為：runner 拋 CE 被 ShellExecTool.executeElevatedFrozen 的
        // `catch (t: Throwable)` 收斂（CE 非 VirtualMachineError/ThreadDeath）→
        // ShellResult.Failed（訊息含 "CancellationException"）＋單筆 FAILED 審計；
        // CE 不外拋、不映 DENY。
        val runner = object : ElevatedShellRunner {
            var calls = 0
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                throw CancellationException("runner cancelled")
            }
        }
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
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Failed)
        assertTrue("$r", (r as ShellResult.Failed).message.contains("CancellationException"))
        assertEquals(1, runner.calls) // 已 spawn（門禁已過）
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    @Test fun runnerVirtualMachineErrorPropagatesWithSingleFailedAudit() = runBlocking {
        // runner 拋 VirtualMachineError（OOM）：executeElevatedFrozen 的 catch (t: Throwable)
        // 收斂路徑先建 Failed＋記一筆 FAILED 審計，隨後 `t is VirtualMachineError` →
        // 原樣重拋——VM 錯誤不得被包成 Denied、不得被吞、不得被映 FAILED 返回。
        // 經 runInterruptible/withContext 透傳 unwrapped（Error 不被協程機制攔截），
        // 由本測試 pin：OOM 外洩至 caller、恰一筆 FAILED 審計、runner 恰一次。
        val runner = object : ElevatedShellRunner {
            var calls = 0
            override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                calls++
                throw OutOfMemoryError("simulated")
            }
        }
        val audit = PrivilegeAuditLog()
        var caught: Throwable? = null
        try {
            ElevatedWiring.execute(
                argv = listOf("id"),
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx() },
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = { validGrant() },
                bridgeGranted = { true },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("OOM must propagate out of execute() unwrapped, got $caught", caught is OutOfMemoryError)
        assertEquals("simulated", (caught as OutOfMemoryError).message)
        assertEquals(1, runner.calls) // 已 spawn（門禁已過）
        assertEquals(1, audit.size())
        assertEquals(mapOf("FAILED" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }

    // --- 重驗 grantCheck 期間竄改 caller mutable argv（clear） ---

    @Test fun reverifyGrantRecheckMutationRejectedFinalCheck() = runBlocking {
        // grant2（重驗）期間清空 caller 的 mutable argv：clear() 發生於重驗入口 argv
        // 比對之後、且發生在最終 argv 防線之前——grant2 身份確認後、dispatch 前的最終
        // 受監督比對捕捉竄改 → fail-closed Denied、零 spawn、恰一筆 DENY、零 Ok。
        // （runner 只吃不可變快照；此封閉「確認→改→spawn」語義缺口，見檔頂 KDoc 兩重驗點。）
        val mutable = mutableListOf("id")
        var grantCalls = 0
        val grantCheck: () -> ElevatedWiring.ElevatedGrant = {
            grantCalls++
            if (grantCalls == 2) mutable.clear()
            validGrant()
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = mutable,
            reasonCode = "B3-OK",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = allowPolicy(),
            runner = runner,
            audit = audit,
            grantCheck = grantCheck,
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertEquals("elevated args changed after confirm (final check, fail-closed, no spawn)", (r as ShellResult.Denied).message)
        assertEquals(0, mutable.size) // 竄改確實發生（caller 保留的 list 已被清空）
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        val row = audit.snapshot().single()
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), row.argvHash)
    }
    // --- timeoutMs 契約（#1：正數超上界即夾至 MAX_ELEVATED_TIMEOUT_MS(60s)；零／負預算 fail-closed 拒絕，不 spawn） ---

    @Test fun timeoutMsPositiveClampedToMaxForRunner() = runBlocking {
        // runner 收到的 timeoutMs：Long.MAX_VALUE → 60_000；合法值原樣透傳（僅正數 clamp，不拒絕）。
        // 斷言正常執行（Ok）＋每筆恰一筆審計。上界常量本身亦釘死（防 MAX 被調大而偵測不到）。
        assertEquals(60_000L, ShellPolicy.MAX_ELEVATED_TIMEOUT_MS)
        val cases = listOf(
            Long.MAX_VALUE to ShellPolicy.MAX_ELEVATED_TIMEOUT_MS,
            5_000L to 5_000L,
        )
        for ((input, expected) in cases) {
            var captured = -1L
            val runner = object : ElevatedShellRunner {
                override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                    captured = timeoutMs
                    return ShellResult.Ok("ok", "", 0, false)
                }
            }
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
                bridgeGranted = { true },
                timeoutMs = input,
            )
            assertTrue("input=$input -> $r", r is ShellResult.Ok)
            assertEquals("input=$input", expected, captured)
            assertEquals("input=$input", 1, audit.size())
        }
    }

    @Test fun timeoutMsNonPositiveRejected() = runBlocking {
        // 零／負預算：fail-closed 拒絕（對齊 github runner 入口 `safeTimeoutMs<=0 →
        // preSpawnExpired` 語義）——TimedOut（empty partial、cleanupIncomplete=false），
        // 零 runner invocation、恰一筆 TIMEOUT 審計、不觸碰 argv 迭代。
        assertEquals(60_000L, ShellPolicy.MAX_ELEVATED_TIMEOUT_MS)
        for (input in listOf(0L, -5L, Long.MIN_VALUE)) {
            var touched = false
            val argv = object : AbstractList<String>() {
                override val size: Int get() { touched = true; return 1 }
                override fun get(index: Int): String { touched = true; return "id" }
                override fun iterator(): MutableIterator<String> {
                    touched = true
                    return mutableListOf("id").iterator()
                }
            }
            var runnerCalls = 0
            val runner = object : ElevatedShellRunner {
                override fun run(request: ElevatedRequest, timeoutMs: Long): ShellResult {
                    runnerCalls++
                    return ShellResult.Ok("ok", "", 0, false)
                }
            }
            val audit = PrivilegeAuditLog()
            val r = ElevatedWiring.execute(
                argv = argv,
                reasonCode = "B3-T",
                confirmed = true,
                ctxSupplier = { elevatedCtx() },
                policyStore = allowPolicy(),
                runner = runner,
                audit = audit,
                grantCheck = { validGrant() },
                bridgeGranted = { true },
                timeoutMs = input,
            )
            assertTrue("input=$input -> $r", r is ShellResult.TimedOut)
            val timedOut = r as ShellResult.TimedOut
            assertEquals("input=$input", input, timedOut.timeoutMs)
            assertEquals("input=$input", "", timedOut.partialStdout)
            assertEquals("input=$input", "", timedOut.partialStderr)
            assertEquals("input=$input", false, timedOut.truncated)
            assertEquals("input=$input", false, timedOut.cleanupIncomplete)
            assertEquals("input=$input must not spawn", 0, runnerCalls)
            assertEquals("input=$input", false, touched)
            assertEquals("input=$input", 1, audit.size())
            assertEquals("input=$input", mapOf("TIMEOUT" to 1), audit.countByVerdict())
            val row = audit.snapshot().single()
            assertEquals("input=$input", PrivilegeAuditLog.argvHash(emptyList()), row.argvHash)
            assertEquals("input=$input", PrivilegeAuditLog.sha256Hex("B3-T"), row.reasonHash)
        }
    }

    // --- !confirmed × projection UNAVAILABLE（#9：projection-unavailable 分類優先於 NeedConfirm） ---

    @Test fun unconfirmedProjectionUnavailableDeniedZeroSpawn() = runBlocking {
        // !confirmed 且 privilege_bridge 開關關 → 投影 UNAVAILABLE：gate 歸類 projection-unavailable
        // （絕不變 Allowed），Denied、零 spawn、恰一筆 DENY、message 含 "privilege gate blocked"。
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val w = wiringArgs(runner = runner, audit = audit, ctx = elevatedCtx(bridgeOn = false))
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
        assertTrue("$r", (r as ShellResult.Denied).message.contains("privilege gate blocked"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    // --- policy 面 AssertionError 收斂（#3：Error 不穿出 execute()，fail-closed＋一筆 DENY） ---

    @Test fun policyAssertionErrorFailsClosedWithDenyAudit() = runBlocking {
        // 前驗 evaluateFresh 直拋 AssertionError（Error，非 Exception）→ 收斂為 verdict=ERROR →
        // Denied、零 spawn、恰一筆 DENY；execute() 不拋 AssertionError。
        val boomPolicy = object : PolicyStore by allowPolicy() {
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                throw AssertionError("boom")
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
        assertTrue("$r", (r as ShellResult.Denied).message.contains("policy denied (verdict=ERROR"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
    }

    @Test fun policyAssertionErrorOnReverifyFailsClosedWithDenyAudit() = runBlocking {
        // 前驗 ALLOW（首調）、重驗 AssertionError（次調）→ Denied（verdict=ERROR），
        // 零 spawn、恰一筆 DENY、argvHash 記 snapshot argv。
        val assertOnSecond = object : PolicyStore by allowPolicy() {
            private var calls = 0
            override suspend fun evaluateFresh(action: String, resource: String): PolicyDecision =
                if (++calls == 1) {
                    allowPolicy().evaluateFresh(action, resource)
                } else {
                    throw AssertionError("boom")
                }
        }
        val runner = SpawnCounter()
        val audit = PrivilegeAuditLog()
        val r = ElevatedWiring.execute(
            argv = listOf("id"),
            reasonCode = "B3-T",
            confirmed = true,
            ctxSupplier = { elevatedCtx() },
            policyStore = assertOnSecond,
            runner = runner,
            audit = audit,
            grantCheck = { validGrant() },
            bridgeGranted = { true },
        )
        assertTrue("$r", r is ShellResult.Denied)
        assertTrue("$r", (r as ShellResult.Denied).message.contains("policy denied on reverify (verdict=ERROR"))
        assertEquals(0, runner.calls)
        assertEquals(1, audit.size())
        assertEquals(mapOf("DENY" to 1), audit.countByVerdict())
        assertEquals(PrivilegeAuditLog.argvHash(listOf("id")), audit.snapshot().single().argvHash)
    }
}
