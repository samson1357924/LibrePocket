package dev.librepocket.privilege.github

import dev.librepocket.redact.Redactor
import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellQuota
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase3 安全加固測試（TOCTOU 收斂，不改 Phase1/2 語義）：
 *
 * - spawn 前重求值：准入已占席、配額未消耗之際重求 grant＋activation＋bridge
 *   （Shizuku 側另重估 `status()`），任一翻轉即按早拒同錯誤碼 Denied 且零 spawn；
 *   慢門阻塞中翻轉即拒，斷零新增 spawn（grant／bridge／activation 各一 pin，
 *   參照同形 gate 阻塞翻轉；首次 su `exists`／`status` 在准入前、重驗在准入後、
 *   配額在重驗後，故慢門阻塞不耗配額）；
 * - 重拒不耗配額＋席釋放：tight quota 下重拒後同 quota 仍可放行一次，
 *   且重拒後 activeCalls 歸零、tryEnterReserved 可再入（防 leaveCall 漏）；
 * - su 解析到執行 TOCTOU：自動解析路徑 spawn 前重做 `exists`＋`isTrusted`，
 *   翻轉即 Failed 且零 spawn；顯式 suBinary 沿既有語義跳過存在性門，
 *   由 isTrusted 字串門 fail-closed（非 allowlist 即 Failed 零 spawn，
 *   存在性不注，見 rootExplicitSuBinaryUntrustedFailsClosedWithoutSpawn）；
 * - 撕裂讀契約：混代（新 provider＋舊 revision 或反之）一律 Invalid；
 * - Redactor 路徑覆蓋邊界 pin（保守保留 redact，僅鎖定邊界）；
 * - P3-1 pin：serviceVersion／TTL 不參與有效性（現行為 Valid，B3 另綁）。
 *
 * 全 fake（假進程＋假 su 映射＋假狀態＋計數 spawn），零真機、零真 su、零真 binder。
 * 背景執行緒只為驅動「慢門阻塞中翻轉」；latch 上界 10s，超界即失敗，不懸掛。
 */
class ElevatedRunnerPhase3Test {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    private fun request() = ElevatedRequest(listOf("id"), reasonCode = "P3-TOCTOU-T")

    private fun runInBackground(
        block: () -> ElevatedBinaryResult,
    ): Pair<Thread, () -> ElevatedBinaryResult?> {
        var result: ElevatedBinaryResult? = null
        val bg = Thread { result = block() }.also { it.isDaemon = true; it.start() }
        return bg to { result }
    }

    // ---- Root：慢 suExists 阻塞中翻轉 grant/bridge ----

    @Test fun rootPreSpawnGrantFlipDuringSlowSuExistsDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val grantValid = AtomicBoolean(true)
        val spawn = SpawnCounter()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = spawn::spawn,
            suExists = {
                entered.countDown()
                assertTrue("suExists gate timed out", release.await(10, TimeUnit.SECONDS))
                true
            },
            grantCheck = {
                if (grantValid.get()) GrantCheck.Valid
                else GrantCheck.Invalid(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED)
            },
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // 早段 grant 已過（Valid），慢 suExists 阻塞中翻轉；spawn 前重求值須拒。
        grantValid.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertEquals(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED, r.message)
        assertEquals(0, spawn.calls)
    }

    @Test fun rootPreSpawnBridgeFlipDuringSlowSuExistsDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridged = AtomicBoolean(true)
        val spawn = SpawnCounter()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = { bridged.get() },
            activationCheck = GrantFixtures.activationOn,
            spawn = spawn::spawn,
            suExists = {
                entered.countDown()
                assertTrue("suExists gate timed out", release.await(10, TimeUnit.SECONDS))
                true
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        bridged.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertTrue("$r", r.message.contains("privilege bridge off"))
        assertEquals(0, spawn.calls)
    }

    @Test fun rootPreSpawnActivationFlipDuringSlowSuExistsDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activated = AtomicBoolean(true)
        val spawn = SpawnCounter()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = { activated.get() },
            spawn = spawn::spawn,
            suExists = {
                entered.countDown()
                assertTrue("suExists gate timed out", release.await(10, TimeUnit.SECONDS))
                true
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // 早段 activation 已過（true），慢 suExists 阻塞中翻轉；spawn 前重求值須拒。
        activated.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertTrue("$r", r.message.contains("activation not ready"))
        assertEquals(0, spawn.calls)
    }

    // ---- Root：suExists 在 resolve 後翻轉 ----

    @Test fun rootSuExistsFlipAfterResolveDeniesWithoutSpawn() {
        val calls = AtomicInteger(0)
        val spawn = SpawnCounter()
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = spawn::spawn,
            // resolve 時首 call 回 true（命中首候選），spawn 前重驗回 false。
            suExists = { calls.incrementAndGet() <= 1 },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.runBinary(request(), 2_000L)
        check(r is ElevatedBinaryResult.Failed) { "$r" }
        assertTrue("$r", r.message.contains("su unavailable"))
        // 舊碼此處會 spawn（解析到執行無重驗）；重驗後必須零 spawn。
        assertEquals(0, spawn.calls)
    }

    // ---- Shizuku：慢 status 阻塞中翻轉 grant/bridge/status ----

    @Test fun shizukuPreSpawnGrantFlipDuringSlowStatusDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val grantValid = AtomicBoolean(true)
        val spawn = SpawnCounter()
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = spawn::spawn,
            status = {
                entered.countDown()
                assertTrue("status gate timed out", release.await(10, TimeUnit.SECONDS))
                ShizukuStatus.Available(15)
            },
            grantCheck = {
                if (grantValid.get()) GrantCheck.Valid
                else GrantCheck.Invalid(ShizukuGrantPolicy.REASON_NEVER_GRANTED)
            },
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        grantValid.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertEquals(ShizukuGrantPolicy.REASON_NEVER_GRANTED, r.message)
        assertEquals(0, spawn.calls)
    }

    @Test fun shizukuPreSpawnBridgeFlipDuringSlowStatusDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridged = AtomicBoolean(true)
        val spawn = SpawnCounter()
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = { bridged.get() },
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = spawn::spawn,
            status = {
                entered.countDown()
                assertTrue("status gate timed out", release.await(10, TimeUnit.SECONDS))
                ShizukuStatus.Available(15)
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        bridged.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertTrue("$r", r.message.contains("privilege bridge off"))
        assertEquals(0, spawn.calls)
    }

    @Test fun shizukuPreSpawnActivationFlipDuringSlowStatusDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activated = AtomicBoolean(true)
        val spawn = SpawnCounter()
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = { activated.get() },
            pingBinder = { true },
            spawn = spawn::spawn,
            status = {
                entered.countDown()
                assertTrue("status gate timed out", release.await(10, TimeUnit.SECONDS))
                ShizukuStatus.Available(15)
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        // 早段 activation 已過（true），慢 status 阻塞中翻轉；spawn 前重求值須拒。
        activated.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertTrue("$r", r.message.contains("activation not ready"))
        assertEquals(0, spawn.calls)
    }

    @Test fun shizukuPreSpawnStatusFlipDeniesWithoutSpawn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binderAlive = AtomicBoolean(true)
        val statusCalls = AtomicInteger(0)
        val spawn = SpawnCounter()
        val runner = ShizukuShellRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            pingBinder = { true },
            spawn = spawn::spawn,
            status = {
                if (statusCalls.incrementAndGet() == 1) {
                    entered.countDown()
                    assertTrue("status gate timed out", release.await(10, TimeUnit.SECONDS))
                    // 首評固定 Available（早段門通過）；翻轉只影響 spawn 前重估。
                    ShizukuStatus.Available(15)
                } else {
                    if (binderAlive.get()) ShizukuStatus.Available(15)
                    else ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN)
                }
            },
            grantCheck = GrantFixtures.allowAlways,
        )
        val (bg, get) = runInBackground { runner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        binderAlive.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val r = get()
        check(r is ElevatedBinaryResult.Denied) { "$r" }
        assertEquals(ShellDeny.NOT_WHITELISTED, r.reason)
        assertTrue("$r", r.message.contains(ShizukuStatus.REASON_BINDER_DOWN))
        // 重估確實執行（求值兩次）且零 spawn：無重估則此處必 spawn。
        assertEquals(2, statusCalls.get())
        assertEquals(0, spawn.calls)
    }

    // ---- 重拒不耗配額＋席釋放（tight quota＋tryEnterReserved 可再入） ----

    @Test fun rootPreSpawnDenyConsumesNoQuotaAndReleasesSeat() {
        // tight quota（maxCalls=1）下先經慢 suExists 門重拒一次（grant 翻轉→Denied
        // 零 spawn），再以同 quota 正常放行一次（Ok）證首拒未耗配額；首拒後
        // activeCalls 歸零且 tryEnterReserved 可再入，證 finally 釋席無洩漏。
        val tightQuota = ShellQuota(maxCalls = 1, windowMs = 60_000L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val grantValid = AtomicBoolean(true)
        val spawn1 = SpawnCounter()
        val denyRunner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            quota = tightQuota,
            spawn = spawn1::spawn,
            suExists = {
                entered.countDown()
                assertTrue("suExists gate timed out", release.await(10, TimeUnit.SECONDS))
                true
            },
            grantCheck = {
                if (grantValid.get()) GrantCheck.Valid
                else GrantCheck.Invalid(ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED)
            },
        )
        val (bg, get) = runInBackground { denyRunner.runBinary(request(), 10_000L) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        grantValid.set(false)
        release.countDown()
        bg.join(10_000L)
        assertFalse("runner must finish", bg.isAlive)
        val denied = get()
        check(denied is ElevatedBinaryResult.Denied) { "$denied" }
        assertEquals(ShellDeny.NOT_WHITELISTED, denied.reason)
        assertEquals(0, spawn1.calls)
        // 席已釋放：無進行中呼叫，且可再准入（防 leaveCall 漏，否則此處即拒）。
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        assertEquals(ElevatedCleanupTracker.Admission.OK, ElevatedCleanupTracker.tryEnterReserved())
        ElevatedCleanupTracker.leaveCall()
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
        // 配額未耗：同 quota 正常呼叫仍可放行（若首拒已耗配額，此處即 QUOTA_EXCEEDED）。
        val spawn2 = SpawnCounter()
        val okRunner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            quota = tightQuota,
            spawn = spawn2::spawn,
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val ok = okRunner.runBinary(request(), 2_000L)
        check(ok is ElevatedBinaryResult.Ok) { "$ok" }
        assertEquals(1, spawn2.calls)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    // ---- 顯式 suBinary fail-closed pin（存在性門跳過，由 isTrusted 承擔） ----

    @Test fun rootExplicitSuBinaryUntrustedFailsClosedWithoutSpawn() {
        // 顯式 suBinary 沿既有語義跳過存在性門（不注 suExists，既有單測顯式路徑皆如此，
        // 保持相容），但仍過字串正規化＋isTrusted allowlist 門；非表內路徑即 Failed
        // 且零 spawn（fail-closed，不建子進程）。此 pin 鎖定該推導，防未來放寬可信門。
        val spawn = SpawnCounter()
        var existsCalls = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = GrantFixtures.activationOn,
            spawn = spawn::spawn,
            suBinary = "/data/local/tmp/su-evil",
            suExists = { existsCalls++; true },
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.runBinary(request(), 2_000L)
        check(r is ElevatedBinaryResult.Failed) { "$r" }
        assertTrue("$r", r.message.contains("untrusted"))
        assertEquals(0, spawn.calls)
        // 顯式路徑未觸存在性門（設計如此，見 RootSuRunner KDoc）：缺席的 fail-closed
        // 由 isTrusted 字串門承擔，而非 suExists。
        assertEquals(0, existsCalls)
        assertEquals(0, ElevatedCleanupTracker.activeCalls())
    }

    // ---- 撕裂讀契約：混代一律 Invalid ----

    @Test fun grantMixedGenerationTornReadFailsClosed() {
        // B3 須以 snapshotWithRevisionStrict 同 emission 供給 provider＋revision
        //（見 ShizukuGrantStore.check KDoc）；混代組合經 policy 仍為 Invalid，
        // runner 早拒／重求值任一混代即拒，禁止放行重試。
        val grant = ShizukuGrantPolicy.grant("provider-a", 1L, 15)
        // endpoint 切換中撕裂：provider 已新、revision 仍舊 → provider 不等即拒。
        val tornNewProvider = ShizukuGrantPolicy.check(grant, "provider-b", 1L)
        assertTrue("$tornNewProvider", tornNewProvider is GrantCheck.Invalid)
        assertEquals(
            ShizukuGrantPolicy.REASON_ENDPOINT_CHANGED,
            (tornNewProvider as GrantCheck.Invalid).reason,
        )
        // 反向撕裂：provider 仍舊、revision 已新 → revision 不等即拒。
        val tornNewRevision = ShizukuGrantPolicy.check(grant, "provider-a", 2L)
        assertTrue("$tornNewRevision", tornNewRevision is GrantCheck.Invalid)
        assertEquals(
            ShizukuGrantPolicy.REASON_REVISION_STALE,
            (tornNewRevision as GrantCheck.Invalid).reason,
        )
    }

    // ---- Redactor 路徑覆蓋邊界 pin（保守保留 redact，僅鎖定邊界） ----

    @Test fun redactorPathBoundaryPinsBehavior() {
        // su 可信路徑常量屬公開 allowlist（見 RootSuProbe.SU_BINARIES），
        // 不在 ANDROID_PATH 覆蓋內，原文保留可接受；鎖定此邊界，防未來
        // Redactor 改動靜默改變審計格式（B2 紅線）。
        val sys = Redactor.redactError("su failed at /system/bin/su exit=1")
        assertTrue("sys=$sys", sys.contains("/system/bin/su"))
        val vendor = Redactor.redactError("su failed at /vendor/bin/su exit=1")
        assertTrue("vendor=$vendor", vendor.contains("/vendor/bin/su"))
        val adb = Redactor.redactError("su missing at /data/adb/magisk code 1")
        assertTrue("adb=$adb", adb.contains("/data/adb/magisk"))
        // 私有域路徑仍被剝離（對照組：覆蓋內路徑行為不變）。
        val priv = Redactor.redactError("failed at /data/data/dev.librepocket.agent/files/x code 1")
        assertTrue("priv=$priv", !priv.contains("/data/data/"))
        assertTrue("priv=$priv", priv.contains("REDACTED"))
    }

    // ---- P3-1 pin：serviceVersion／TTL 不參與有效性（現行為 Valid） ----

    @Test fun grantServiceVersionAndTtlNotEnforced_pinsCurrentBehavior() {
        // P3-1 現狀 pin（行為不變）：serviceVersion 變更不影響有效性；
        // 無 TTL（grantedAtMs 任意值皆 Valid）。B3 另行綁定 serviceVersion／TTL
        // 前不得假設本層會過期舊授權；未來加綁定時須同步更新本測試。
        val old = ShizukuGrantPolicy.grant("p", 1L, 11, atMs = 0L)
        assertTrue("${ShizukuGrantPolicy.check(old, "p", 1L)}", ShizukuGrantPolicy.check(old, "p", 1L) is GrantCheck.Valid)
        val newer = ShizukuGrantPolicy.grant("p", 1L, 99, atMs = Long.MAX_VALUE)
        assertTrue("${ShizukuGrantPolicy.check(newer, "p", 1L)}", ShizukuGrantPolicy.check(newer, "p", 1L) is GrantCheck.Valid)
    }
}
