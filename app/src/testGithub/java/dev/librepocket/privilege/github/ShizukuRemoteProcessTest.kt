package dev.librepocket.privilege.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * P3-B wrap 邊界測試（Issue #136 B2 接線）：
 * null 拒收／Process 直通／缺方法抛／超時重載 primitive 強制。
 * 全 fake 遠端物件，零真機、零 binder、零 su。
 */
class ShizukuRemoteProcessTest {

    private class PassThrough : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() {}
    }

    @Test fun wrapNullThrows() {
        try {
            ShizukuRemoteProcess.wrap(null)
            fail("wrap(null) must throw")
        } catch (_: IllegalStateException) {
            // 預期：fail-closed，不偽造進程。
        }
    }

    @Test fun wrapProcessPassthrough() {
        // 已是 Process 即直回同一實例，不再包裝。
        val process: Process = PassThrough()
        assertSame(process, ShizukuRemoteProcess.wrap(process))
    }

    @Test fun wrapMissingMethodThrows() {
        // 無同名方法的遠端物件：呼叫即抛（UnsupportedOperationException），不靜默誤調。
        val wrapped = ShizukuRemoteProcess.wrap(Any())
        try {
            wrapped.exitValue()
            fail("missing exitValue must throw")
        } catch (_: UnsupportedOperationException) {
            // 預期。
        }
        try {
            wrapped.waitFor()
            fail("missing waitFor must throw")
        } catch (_: UnsupportedOperationException) {
            // 預期。
        }
    }

    /** 宣告 primitive long 的遠端：boxed Long 仍精確匹配（Long.TYPE 傳遞）。 */
    private class FakeLongWait {
        var seenTimeout: Long = -1L
        fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            seenTimeout = timeout
            return true
        }
    }

    /** 宣告 primitive int 的遠端：Number 承接＋coerce 收斂（Integer.TYPE 傳遞）。 */
    private class FakeIntWait {
        var seenTimeout: Int = -1
        fun waitFor(timeout: Int, unit: TimeUnit): Boolean {
            seenTimeout = timeout
            return true
        }
    }

    @Test fun remoteWaitForTimeoutCoercesPrimitive() {
        val longRemote = FakeLongWait()
        val wrappedLong = ShizukuRemoteProcess.wrap(longRemote)
        assertTrue(wrappedLong.waitFor(10L, TimeUnit.MILLISECONDS))
        assertEquals(10L, longRemote.seenTimeout)

        val intRemote = FakeIntWait()
        val wrappedInt = ShizukuRemoteProcess.wrap(intRemote)
        assertTrue(wrappedInt.waitFor(7L, TimeUnit.MILLISECONDS))
        assertEquals(7, intRemote.seenTimeout)
    }
}
