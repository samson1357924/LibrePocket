package dev.librepocket.privilege.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y1 [ElevatedCappedPipe] 隔離單測（owner 新 P2＋PocketGuard CappedPipe 可測性備註）：
 * 原內嵌 `private CappedPipe` 更名提升為同檔 internal，行為零變更；本檔只斷
 * 管本體語義（溢出位、截斷前綴、failed 單調、bytes 拷貝），不碰 drain/runner。
 * 零真機、零 su、零 binder。
 */
class ElevatedCappedPipeTest {

    @Test fun underCapKeepsBytesWithoutOverflow() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(8)
        pipe.write(byteArrayOf(1, 2, 3), 3)
        assertFalse(pipe.didOverflow())
        assertFalse(pipe.isFull())
        assertFalse(pipe.failed)
        assertTrue(pipe.bytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun exactCapIsFullWithoutOverflow() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(4)
        pipe.write(byteArrayOf(1, 2, 3, 4), 4)
        assertTrue(pipe.isFull())
        assertFalse("恰上限通過不算溢出", pipe.didOverflow())
        assertTrue(pipe.bytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun overCapTruncatesPrefixAndMarksOverflow() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(4)
        pipe.write(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
        assertTrue(pipe.didOverflow())
        assertTrue(pipe.isFull())
        assertTrue(pipe.bytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun overflowIsStickyAcrossWrites() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(2)
        pipe.write(byteArrayOf(1, 2, 3), 3)
        assertTrue(pipe.didOverflow())
        pipe.write(byteArrayOf(4, 5), 2)
        assertTrue("溢出位單調不清零", pipe.didOverflow())
        assertTrue(pipe.bytes().contentEquals(byteArrayOf(1, 2)))
    }

    @Test fun failedIsMonotonic() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(4)
        assertFalse(pipe.failed)
        pipe.markFailed()
        assertTrue(pipe.failed)
        pipe.markFailed()
        assertTrue("failed 單調不清除", pipe.failed)
    }

    @Test fun bytesReturnsDefensiveCopy() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(8)
        pipe.write(byteArrayOf(9, 8, 7), 3)
        val first = pipe.bytes()
        first[0] = 0
        assertTrue(pipe.bytes().contentEquals(byteArrayOf(9, 8, 7)))
    }
}
