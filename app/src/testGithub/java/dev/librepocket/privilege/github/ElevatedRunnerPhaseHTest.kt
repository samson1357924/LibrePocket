package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-3 Phase H pins（P2-6 argv 入站上限＋阻塞 iterator 有界＋P3a 孤立代理，
 * PR #140；P2-5 runner throw 審計在 main 源集 `ShellElevatedPhaseHTest` 鎖定，
 * runner 本體不抛——一切 spawn／門異常皆已收斂為 typed 結果）。
 *
 * - 阻塞 iterator（latch 永不釋放，可中斷）→ 有界（deadline 2s＋grace，
 *   斷言 <10s）fail-closed（`TimedOut`，非 Ok）且零 spawn；門超界即 interrupt，
 *   worker 正常回收（tearDown `outstanding==0` 即證）。
 * - argc／單項／總量超限各一 → `Denied(BLACKLISTED)` 零 spawn（雙 runner）。
 * - 孤立代理（P3a）→ `Denied(BLACKLISTED)` 零 spawn（雙 runner）。
 * - Phase 4 F9 語義保留：拷貝抛仍 `Denied(BLACKLISTED)`＋訊息含 `snapshot failed`。
 *
 * 全 hermetic（注入 fake spawn／status／suBinary，零真機、零 su、零 binder）。
 */
class ElevatedRunnerPhaseHTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    @Before fun setUpBreakerIsolation() {
        ElevatedCleanupTracker.clearUnverified()
        ElevatedCleanupTracker.noteClean()
        pollOutstandingTo(0)
        pollActiveTo(0)
    }

    @After fun tearDownBreakerIsolation() {
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

    private fun blockingArgv(latch: CountDownLatch): List<String> =
        object : AbstractList<String>() {
            override val size: Int get() = 1
            override fun get(index: Int): String = "id"
            override fun iterator(): Iterator<String> {
                latch.await()
                return listOf("id").iterator()
            }
        }

    private fun argcOver(): List<String> = List(ShellPolicy.MAX_ELEVATED_ARGC + 1) { "a" }

    private fun singleOver(): List<String> =
        listOf("id", "x".repeat(ShellPolicy.MAX_ELEVATED_ARG_CHARS + 1))

    private fun totalOver(): List<String> = List(20) { "y".repeat(4000) }

    private fun rootRunner(spawns: AtomicInteger) = RootSuRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        spawn = {
            spawns.incrementAndGet()
            FakeProcess("ok".toByteArray())
        },
        suBinary = "/system/bin/su",
        grantCheck = GrantFixtures.allowAlways,
    )

    private fun shizukuRunner(spawns: AtomicInteger) = ShizukuShellRunner(
        privateRoot = privateRoot,
        bridgeGranted = GrantFixtures.bridgeOn,
        activationCheck = GrantFixtures.activationOn,
        pingBinder = { true },
        spawn = {
            spawns.incrementAndGet()
            FakeProcess("ok".toByteArray())
        },
        status = { ShizukuStatus.Available(15) },
        grantCheck = GrantFixtures.allowAlways,
    )

    // ---- P2-6：阻塞 iterator 有界 fail-closed（雙 runner） ----

    @Test fun rootBlockingArgvBoundedFailClosedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val latch = CountDownLatch(1) // 永不釋放：監督超界靠 interrupt 回收 worker。
        val start = System.currentTimeMillis()
        val r = rootRunner(spawns).runBinary(ElevatedRequest(blockingArgv(latch), "H-P2-6"), 2_000L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
        assertEquals(0, spawns.get())
        assertTrue("must return within deadline bound, elapsed=$elapsed", elapsed < 10_000L)
    }

    @Test fun shizukuBlockingArgvBoundedFailClosedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val start = System.currentTimeMillis()
        val r = shizukuRunner(spawns).runBinary(ElevatedRequest(blockingArgv(latch), "H-P2-6"), 2_000L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("$r", r is ElevatedBinaryResult.TimedOut)
        assertEquals(0, spawns.get())
        assertTrue("must return within deadline bound, elapsed=$elapsed", elapsed < 10_000L)
    }

    // ---- P2-6：argc／單項／總量超限（雙 runner × 三上限） ----

    @Test fun rootArgcOverLimitDeniedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val r = rootRunner(spawns).runBinary(ElevatedRequest(argcOver(), "H-P2-6"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    @Test fun shizukuArgcOverLimitDeniedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val r = shizukuRunner(spawns).runBinary(ElevatedRequest(argcOver(), "H-P2-6"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    @Test fun rootSingleArgOverLimitDeniedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val r = rootRunner(spawns).runBinary(ElevatedRequest(singleOver(), "H-P2-6"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    @Test fun shizukuSingleArgOverLimitDeniedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val r = shizukuRunner(spawns).runBinary(ElevatedRequest(singleOver(), "H-P2-6"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    @Test fun rootTotalBytesOverLimitDeniedWithoutSpawn() {
        // 20×4000＝80K＞64K；argc 20≤64、單項 4000≤4K，唯總量超限。
        assertTrue(totalOver().sumOf { it.toByteArray(Charsets.UTF_8).size } > ShellPolicy.MAX_ELEVATED_ARGV_BYTES)
        val spawns = AtomicInteger(0)
        val r = rootRunner(spawns).runBinary(ElevatedRequest(totalOver(), "H-P2-6"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    @Test fun shizukuTotalBytesOverLimitDeniedWithoutSpawn() {
        assertTrue(totalOver().sumOf { it.toByteArray(Charsets.UTF_8).size } > ShellPolicy.MAX_ELEVATED_ARGV_BYTES)
        val spawns = AtomicInteger(0)
        val r = shizukuRunner(spawns).runBinary(ElevatedRequest(totalOver(), "H-P2-6"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    // ---- P3a：孤立代理入站即拒（雙 runner） ----

    @Test fun rootLoneSurrogateDeniedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val r = rootRunner(spawns).runBinary(ElevatedRequest(listOf("id", "\uD800"), "H-P3a"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    @Test fun shizukuLoneSurrogateDeniedWithoutSpawn() {
        val spawns = AtomicInteger(0)
        val r = shizukuRunner(spawns).runBinary(ElevatedRequest(listOf("id", "\uD800"), "H-P3a"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.BLACKLISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns.get())
    }

    // ---- F9 語義保留：拷貝抛仍 Denied＋snapshot failed 訊息（雙 runner） ----

    private fun throwingArgv(): List<String> = object : AbstractList<String>() {
        override val size: Int get() = 1
        override fun get(index: Int): String = "id"
        override fun iterator(): Iterator<String> =
            throw java.util.ConcurrentModificationException("injected")
    }

    @Test fun rootSnapshotThrowStillDeniedWithSnapshotMessage() {
        val spawns = AtomicInteger(0)
        val r = rootRunner(spawns).runBinary(ElevatedRequest(throwingArgv(), "H-F9"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        r as ElevatedBinaryResult.Denied
        assertEquals(ShellDeny.BLACKLISTED, r.reason)
        assertTrue("message=${r.message}", r.message.contains("snapshot failed"))
        assertEquals(0, spawns.get())
    }

    @Test fun shizukuSnapshotThrowStillDeniedWithSnapshotMessage() {
        val spawns = AtomicInteger(0)
        val r = shizukuRunner(spawns).runBinary(ElevatedRequest(throwingArgv(), "H-F9"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        r as ElevatedBinaryResult.Denied
        assertEquals(ShellDeny.BLACKLISTED, r.reason)
        assertTrue("message=${r.message}", r.message.contains("snapshot failed"))
        assertEquals(0, spawns.get())
    }
}
