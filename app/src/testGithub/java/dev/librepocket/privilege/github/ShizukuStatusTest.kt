package dev.librepocket.privilege.github

import dev.librepocket.shell.ElevatedRequest
import dev.librepocket.shell.ShellDeny
import dev.librepocket.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 Shizuku 型別化狀態機測試（Issue #136 B1）：
 * binder/授權/版本三維判定＋拒絕即 typed 不執行。
 * 真實 Shizuku 全注入假 lambda，不碰 binder/su/真連線。
 */
class ShizukuStatusTest {

    @Test fun binderDownShortCircuitsToUnreachable() {
        var permissionTouched = false
        var versionTouched = false
        val status = ShizukuStatusProbe.status(
            pingBinder = { false },
            checkPermission = { permissionTouched = true; true },
            serviceVersion = { versionTouched = true; 15 },
        )
        assertTrue("$status", status is ShizukuStatus.BinderUnreachable)
        assertFalse(permissionTouched)
        assertFalse(versionTouched)
        assertFalse(status.canExecute)
    }

    @Test fun versionBoundaryIsFailClosed() {
        // 未知版本（異常→null）即不相容。
        val unknown = ShizukuStatus.evaluate(true, true, null)
        assertTrue("$unknown", unknown is ShizukuStatus.VersionUnsupported)
        // 下限：MIN 放行，MIN-1 拒絕。
        val min = ShizukuStatus.MIN_SERVICE_VERSION
        assertTrue(ShizukuStatus.evaluate(true, true, min) is ShizukuStatus.Available)
        assertTrue(ShizukuStatus.evaluate(true, true, min - 1) is ShizukuStatus.VersionUnsupported)
    }

    @Test fun permissionDeniedIsTyped() {
        val status = ShizukuStatus.evaluate(
            binderAlive = true,
            permissionGranted = false,
            serviceVersion = 15,
        )
        assertTrue("$status", status is ShizukuStatus.PermissionDenied)
        assertFalse(status.canExecute)
    }

    @Test fun probeExceptionsFoldToFailClosed() {
        // checkSelfPermission 抛＝未授權；getVersion 抛＝未知版本（皆不可執行）。
        val denied = ShizukuStatusProbe.status(
            pingBinder = { true },
            checkPermission = { throw SecurityException("no binder") },
            serviceVersion = { 15 },
        )
        assertTrue("$denied", denied is ShizukuStatus.PermissionDenied)
        val unsupported = ShizukuStatusProbe.status(
            pingBinder = { true },
            checkPermission = { true },
            serviceVersion = { throw IllegalStateException("no service") },
        )
        assertTrue("$unsupported", unsupported is ShizukuStatus.VersionUnsupported)
        // ping 本身抛＝binder 不可達。
        val down = ShizukuStatusProbe.status(
            pingBinder = { throw RuntimeException("dead") },
            checkPermission = { true },
            serviceVersion = { 15 },
        )
        assertTrue("$down", down is ShizukuStatus.BinderUnreachable)
    }

    @Suppress("DEPRECATION")
    @Test fun gateDeniesTypedWithoutSpawn() {
        val cases = listOf(
            ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN),
            ShizukuStatus.PermissionDenied(15),
            ShizukuStatus.VersionUnsupported(-1),
            ShizukuStatus.VersionUnsupported(10),
        )
        for (status in cases) {
            var spawns = 0
            val result = ShizukuStatusGate.runIfAvailable(
                status,
                ElevatedRequest(listOf("id"), "B1-T"),
                1_000L,
            ) { _, _ ->
                spawns++
                ShellResult.Ok("x", "", 0, false)
            }
            assertTrue("$status -> $result", result is ShellResult.Denied)
            assertEquals(ShellDeny.NOT_WHITELISTED, (result as ShellResult.Denied).reason)
            assertEquals(0, spawns)
        }
        assertNull(ShizukuStatusGate.denyFor(ShizukuStatus.Available(15)))
    }

    @Test fun gateDenialCarriesReasonCode() {
        val denied = ShizukuStatusGate.denyFor(ShizukuStatus.PermissionDenied(15))!!
        assertTrue(denied.message.contains(ShizukuStatus.REASON_PERMISSION_DENIED))
        val down = ShizukuStatusGate.denyFor(
            ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN),
        )!!
        assertTrue(down.message.contains(ShizukuStatus.REASON_BINDER_DOWN))
        val old = ShizukuStatusGate.denyFor(ShizukuStatus.VersionUnsupported(10))!!
        assertTrue(old.message.contains(ShizukuStatus.REASON_VERSION_UNSUPPORTED))
    }

    @Suppress("DEPRECATION")
    @Test fun availableDelegatesToSpawn() {
        var spawns = 0
        val result = ShizukuStatusGate.runIfAvailable(
            ShizukuStatus.Available(15),
            ElevatedRequest(listOf("id"), "B1-T"),
            1_000L,
        ) { _, _ ->
            spawns++
            ShellResult.Ok("ok", "", 0, false)
        }
        assertTrue("$result", result is ShellResult.Ok)
        assertEquals(1, spawns)
    }

    @Test fun binderEventsDispatchWithFaultIsolation() {
        val events = ShizukuBinderEvents()
        val seen = mutableListOf<String>()
        events.onReceived { seen += "a" }
        events.onReceived { throw IllegalStateException("bad callback") }
        events.onReceived { seen += "b" }
        events.emitReceived()
        assertEquals(listOf("a", "b"), seen)
        var dead = 0
        events.onDead { dead++ }
        events.emitDead()
        assertEquals(1, dead)
    }

    @Test fun attachWithoutBinderEnvironmentDoesNotThrow() {
        // JVM 單測無 Shizuku 服務：註冊異常由 attach 內部收斂，不得外抛。
        ShizukuBinderEvents().attach()
    }

    @Test fun evaluateExtremeVersions() {
        // 極大版本照常放行；-1/null 一律 fail-closed 為不相容。
        val huge = ShizukuStatus.evaluate(true, true, Int.MAX_VALUE)
        assertTrue("$huge", huge is ShizukuStatus.Available)
        val negative = ShizukuStatus.evaluate(true, true, -1)
        assertTrue("$negative", negative is ShizukuStatus.VersionUnsupported)
        val unknown = ShizukuStatus.evaluate(true, true, null)
        assertTrue("$unknown", unknown is ShizukuStatus.VersionUnsupported)
        assertFalse(negative.canExecute)
        assertFalse(unknown.canExecute)
    }

    @Test fun canExecuteMatrix() {
        // 僅 Available 可執行，其餘三態全不可。
        assertTrue(ShizukuStatus.Available(15).canExecute)
        assertFalse(ShizukuStatus.PermissionDenied(15).canExecute)
        assertFalse(ShizukuStatus.VersionUnsupported(-1).canExecute)
        assertFalse(ShizukuStatus.VersionUnsupported(10).canExecute)
        assertFalse(ShizukuStatus.BinderUnreachable(ShizukuStatus.REASON_BINDER_DOWN).canExecute)
    }

    @Test fun serviceVersionNullFoldsToUnsupported() {
        // getVersion 抛→null→VersionUnsupported（serviceVersion 攜帶 -1），不可執行。
        val status = ShizukuStatusProbe.status(
            pingBinder = { true },
            checkPermission = { true },
            serviceVersion = { throw IllegalStateException("no service") },
        )
        assertTrue("$status", status is ShizukuStatus.VersionUnsupported)
        assertEquals(-1, (status as ShizukuStatus.VersionUnsupported).serviceVersion)
        assertFalse(status.canExecute)
    }

    @Test fun deadCallbackFaultIsolated() {
        // dead 回呼抛異常被隔離，後續回呼照常分發（與 received 側對稱）。
        val events = ShizukuBinderEvents()
        val seen = mutableListOf<String>()
        events.onDead { seen += "a" }
        events.onDead { throw IllegalStateException("bad dead callback") }
        events.onDead { seen += "b" }
        events.emitDead()
        assertEquals(listOf("a", "b"), seen)
    }
}
