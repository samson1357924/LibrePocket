package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 5 A4 activation 接線契約（僅 KDoc＋負向釘死，行為不變）：
 * - 默認 `activationCheck`（讀 [PrivilegeActivation.ELEVATED_RUNNERS_READY]，
 *   現 `false`）即 default-deny：零 spawn 拒 `Denied(NOT_WHITELISTED)`；
 * - 傳 `{ true }` 常數即繞過本旗（翻旗無法關閉 runner）：本測試釘死該繞過，
 *   產品禁止此寫法（須傳 `{ ELEVATED_RUNNERS_READY }` 讀值，每次重驗）。
 *
 * 全 fake（假 spawn＋假 su），零真機、零 su、零 binder。
 */
class ElevatedActivationContractTest {

    private val privateRoot = "/data/data/dev.librepocket.agent/files"

    @Before fun setUp() {
        ElevatedCleanupTracker.noteClean()
        pollActiveTo(0)
    }

    @After fun tearDown() {
        ElevatedCleanupTracker.noteClean()
        pollActiveTo(0)
    }

    @Test fun defaultActivationCheckIsDefaultDeny() {
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            // 默認即 { PrivilegeActivation.ELEVATED_RUNNERS_READY }（false）。
            spawn = { _ ->
                spawns++
                FakeProcess("ok".toByteArray())
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.runBinary(ElevatedRequest(listOf("id"), "A4-T"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Denied)
        assertEquals(ShellDeny.NOT_WHITELISTED, (r as ElevatedBinaryResult.Denied).reason)
        assertEquals(0, spawns)
    }

    @Test fun constantTrueBypassesFlag_warningPin_productMustNotUse() {
        // 告警釘死：`{ true }` 繞過 default-deny（本旗翻轉無法關閉 runner）。
        // 產品禁止傳 `{ true }`，須傳 `{ ELEVATED_RUNNERS_READY }` 讀值。
        var spawns = 0
        val runner = RootSuRunner(
            privateRoot = privateRoot,
            bridgeGranted = GrantFixtures.bridgeOn,
            activationCheck = { true },
            spawn = { _ ->
                spawns++
                FakeProcess("ok".toByteArray())
            },
            suBinary = "/system/bin/su",
            grantCheck = GrantFixtures.allowAlways,
        )
        val r = runner.runBinary(ElevatedRequest(listOf("id"), "A4-T"), 2_000L)
        assertTrue("$r", r is ElevatedBinaryResult.Ok)
        assertEquals(1, spawns)
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
}
