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

    @Test fun over64KUnder512KDoesNotOverflowPipe() {
        // 65KB（超 64K mark 但未超 512K 硬讀）：pipe 溢出不置位、未滿；
        // 64K mark 截斷由 runner 側 ShellPolicy.truncate 置位（見容量測試），
        // pipe 層只斷硬上限溢出位，兩層正交。
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(512 * 1024)
        pipe.write(ByteArray(65 * 1024), 65 * 1024)
        assertFalse(pipe.didOverflow())
        assertFalse(pipe.isFull())
        assertFalse(pipe.failed)
    }

    @Test fun exact512KIsFullWithoutOverflow() {
        // 恰 512K==硬讀：滿但不溢出（閾值==硬讀靠溢出位區分，恰上限通過）。
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(512 * 1024)
        pipe.write(ByteArray(512 * 1024), 512 * 1024)
        assertTrue(pipe.isFull())
        assertFalse("恰上限通過不算溢出", pipe.didOverflow())
    }

    @Test fun over512KMarksOverflow() {
        val pipe = ElevatedProcessIo.ElevatedCappedPipe(512 * 1024)
        pipe.write(ByteArray(512 * 1024 + 1), 512 * 1024 + 1)
        assertTrue(pipe.didOverflow())
        assertTrue(pipe.isFull())
    }

    @Test fun stdoutStderrOverflowOrthogonal() {
        // out/err 溢出位正交（U2-B 截圖軌鏡像：out 8M cap＋70KB 完整，err 512K cap＋1MB 溢出）：
        // 一流溢出不污染另一流判定；反向小 cap 亦對稱。
        val out = ElevatedProcessIo.ElevatedCappedPipe(8 * 1024 * 1024)
        val err = ElevatedProcessIo.ElevatedCappedPipe(512 * 1024)
        out.write(ByteArray(70 * 1024), 70 * 1024)
        err.write(ByteArray(1 * 1024 * 1024), 1 * 1024 * 1024)
        assertFalse("stdout 70KB < 8M must not overflow", out.didOverflow())
        assertTrue("stderr 1MB > 512K must overflow", err.didOverflow())
        // 反向：out 溢出、err 恰上限通過。
        val out2 = ElevatedProcessIo.ElevatedCappedPipe(8)
        val err2 = ElevatedProcessIo.ElevatedCappedPipe(4)
        out2.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), 9)
        err2.write(byteArrayOf(1, 2, 3, 4), 4)
        assertTrue(out2.didOverflow())
        assertFalse("exact cap passes without overflow", err2.didOverflow())
    }
}
