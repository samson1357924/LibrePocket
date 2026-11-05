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
        // JVM 單測無 Shizuku 服務：Shizuku 類初始化即失敗，註冊異常由
        // attach/detach 內部 runCatching 收斂，不得外抛；守衛恆 false（失敗不卡死）。
        val events = ShizukuBinderEvents()
        events.attach()
        assertFalse(events.isAttached())
        events.detach()
        assertFalse(events.isAttached())
    }

    @Test fun attachOnJvmNeverRegistersRealListener() {
        // JVM 上 Shizuku 類初始化失敗（android.os.Binder 未 mock）：attach 經
        // runCatching 收斂，守衛恆 false（失敗不卡死，允許重試）；detach 冪等不抛。
        // 與 attachWithoutBinderEnvironmentDoesNotThrow 互補：此處斷言重試語義＋
        // 冪等 detach；本地回呼表語義見 detachResetsGuardAndClearsCallbacks。
        val events = ShizukuBinderEvents()
        events.attach()
        assertFalse("JVM attach must not set guard", events.isAttached())
        events.attach() // 重試不被冪等守衛吞掉（仍失敗但不抛）。
        assertFalse("retry must not stick guard", events.isAttached())
        events.detach() // 空/失敗 detach 冪等安全，不抛。
        assertFalse(events.isAttached())
        events.detach()
        assertFalse(events.isAttached())
    }

    @Test fun detachResetsGuardAndClearsCallbacks() {
        // JVM 單測無 binder：Shizuku 類初始化即失敗，attach 註冊失敗由內部收斂；
        // 舊實作先置守衛會導致失敗後卡死 true，新實作保持 false（允許重試）。
        val events = ShizukuBinderEvents()
        var received = 0
        var dead = 0
        events.onReceived { received++ }
        events.onDead { dead++ }
        events.attach()
        assertFalse(events.isAttached())
        events.attach() // 重試不被冪等守衛吞掉（仍失敗但不抛）
        assertFalse(events.isAttached())
        // 失敗只影響上游註冊，不清本地表：本地回呼仍有效。
        events.emitReceived()
        events.emitDead()
        assertEquals(1, received)
        assertEquals(1, dead)
        // detach 清本地表＋復位守衛，空 detach 冪等安全。
        events.detach()
        assertFalse(events.isAttached())
        events.emitReceived()
        events.emitDead()
        assertEquals(1, received)
        assertEquals(1, dead)
        events.detach()
        assertFalse(events.isAttached())
    }

    @Test fun attachDetachAttachDoesNotAccumulateCallbacks() {
        // attach→detach→attach：detach 清本地表，重建後重新訂閱只分發一次，
        // 無殘留重複。上游靜態表無法在 JVM 斷言（Shizuku 類初始化即失敗）；
        // 上游不累積由 detach 內 remove*（同一引用反註冊）保證，見 KDoc。
        val events = ShizukuBinderEvents()
        var calls = 0
        events.onReceived { calls++ }
        events.attach()
        assertFalse(events.isAttached()) // JVM 下註冊失敗，守衛保持 false
        events.detach()
        assertFalse(events.isAttached())
        events.emitReceived()
        assertEquals(0, calls) // detach 已清空本地表
        events.attach() // 重建：守衛未卡死，重試不抛
        events.onReceived { calls++ }
        events.emitReceived()
        assertEquals(1, calls) // 只觸發一次
        events.detach()
        events.emitReceived()
        assertEquals(1, calls)
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
