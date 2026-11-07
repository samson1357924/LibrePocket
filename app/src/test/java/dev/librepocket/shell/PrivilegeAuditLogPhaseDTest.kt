package dev.librepocket.shell

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase D 回歸（PR #140 Round-2）：
 * - P2-3 argvHash 單射性：長度前綴編碼消除 U+0001 分隔符歧義；
 * - P2-4 audit seq 原子性：單鎖保證 seq 分配＋append 同臨界區。
 *
 * `PrivilegeAuditRecord` 未加欄位，data-class 相等語義不變（本檔另以
 * `copy`／相等斷言鎖定，避免未來加欄位時靜默改語義）。
 */
class PrivilegeAuditLogPhaseDTest {

    // ---- P2-3：單射性 ----

    @Test fun argvHash_separatorInjectionNoLongerCollides() {
        // 舊編碼 joinToString("\u0001") 下兩者序列化同為 "id\u0001foo\u0001bar" 而同 hash。
        val split = listOf("id", "foo", "bar")
        val injected = listOf("id", "foo\u0001bar")
        assertNotEquals(
            "U+0001 注入不得與多項切分同 hash",
            PrivilegeAuditLog.argvHash(split),
            PrivilegeAuditLog.argvHash(injected),
        )
        // 位元組層亦相異（不只 hash 相異，編碼本身單射）。
        assertFalse(
            PrivilegeAuditLog.encodeArgv(split)
                .contentEquals(PrivilegeAuditLog.encodeArgv(injected)),
        )
    }

    @Test fun argvHash_isDeterministicAndHexAndBoundToRecord() {
        val argv = listOf("id", "foo\u0001bar")
        val h1 = PrivilegeAuditLog.argvHash(argv)
        val h2 = PrivilegeAuditLog.argvHash(argv.toList())
        assertEquals(h1, h2)
        assertEquals(64, h1.length)
        assertTrue(h1.matches(Regex("[0-9a-f]{64}")))
        // record 落表即此雜湊（內部編碼已換，recordSnapshot 簽名不變）。
        val audit = PrivilegeAuditLog()
        val row = audit.record(argv, "D09-T", "DENY")
        assertEquals(h1, row.argvHash)
        val snapRow = audit.recordSnapshot(argv, "D09-T", ShellResult.Ok("o", "", 0, false))
        assertEquals(h1, snapRow.argvHash)
        // 與舊歧義編碼脫鉤：不再等於 joinToString 版。
        assertNotEquals(PrivilegeAuditLog.sha256Hex(argv.joinToString("\u0001")), h1)
    }

    @Test fun argvHash_boundaries_emptyEmptyItemNulLong() {
        // 空 argv：僅 4-byte argc=0。
        val emptyEnc = PrivilegeAuditLog.encodeArgv(emptyList())
        assertEquals(4, emptyEnc.size)
        assertEquals(64, PrivilegeAuditLog.argvHash(emptyList()).length)
        // 空 argv vs 單空字串項：編碼／雜湊皆相異（argc 區分）。
        assertNotEquals(
            PrivilegeAuditLog.argvHash(emptyList()),
            PrivilegeAuditLog.argvHash(listOf("")),
        )
        // 空字串項：len=0 零載荷，不抛且可辨。
        assertNotEquals(
            PrivilegeAuditLog.argvHash(listOf("id")),
            PrivilegeAuditLog.argvHash(listOf("id", "")),
        )
        // 含 \u0000：編碼層不抛（上游拒絕由 ShellPolicy 負責），且與去 NUL 版可辨。
        val withNul = listOf("a\u0000b")
        val nulHash = PrivilegeAuditLog.argvHash(withNul) // 不抛即過第一關
        assertEquals(64, nulHash.length)
        assertNotEquals(nulHash, PrivilegeAuditLog.argvHash(listOf("ab")))
        val audit = PrivilegeAuditLog()
        val nulRow = audit.record(withNul, "D09-T", "DENY")
        assertEquals(nulHash, nulRow.argvHash)
        // 超長項（100k）：不抛、確定性、64 hex。
        val longItem = "x".repeat(100_000)
        val longH1 = PrivilegeAuditLog.argvHash(listOf(longItem))
        val longH2 = PrivilegeAuditLog.argvHash(listOf(longItem))
        assertEquals(longH1, longH2)
        assertEquals(64, longH1.length)
        assertNotEquals(longH1, PrivilegeAuditLog.argvHash(listOf("x")))
    }

    @Test fun auditRecord_dataClassEqualityUnchanged() {
        // Phase D 未加欄位：相等語義仍為五欄全等；雜湊不同即列不等。
        val audit = PrivilegeAuditLog()
        val a = audit.record(listOf("id"), "D09-T", "DENY", atMs = 1L)
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, a.copy(argvHash = PrivilegeAuditLog.argvHash(listOf("id", "extra"))))
    }

    // ---- P2-4：單鎖原子性 ----

    private fun seqNum(seqId: String): Long {
        assertTrue("bad seqId: $seqId", seqId.startsWith("priv-"))
        return seqId.removePrefix("priv-").toLong()
    }

    @Test fun concurrentRecord_snapshotSeqStrictlyIncreasingWithoutGaps() {
        val audit = PrivilegeAuditLog()
        val threads = 16
        val perThread = 25
        val total = threads * perThread // 400 < MAX_RECORDS，不觸修剪，專驗排序／空洞
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val returned = Collections.synchronizedList(mutableListOf<PrivilegeAuditRecord>())
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { t ->
                pool.submit {
                    try {
                        start.await()
                        repeat(perThread) { i ->
                            returned += audit.record(listOf("id", "$t-$i"), "D09-T", "OK")
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("concurrent record timed out", done.await(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        val snap = audit.snapshot()
        assertEquals(total, snap.size)
        assertEquals(total, returned.size)
        // 分配集合＝落表集合（seq 無丟失／無重複），且快照內即為遞增 append 順序。
        assertEquals(
            (1L..total.toLong()).toSet(),
            snap.map { seqNum(it.seqId) }.toSet(),
        )
        assertEquals(returned.map { it.seqId }.toSet(), snap.map { it.seqId }.toSet())
        val nums = snap.map { seqNum(it.seqId) }
        assertEquals((1L..total.toLong()).toList(), nums)
    }

    @Test fun concurrentRecord_trimmingDropsOldestSeq() {
        val audit = PrivilegeAuditLog()
        val total = PrivilegeAuditLog.MAX_RECORDS + 200
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val base = total / threads
            val rem = total % threads
            repeat(threads) { t ->
                val n = base + if (t < rem) 1 else 0
                pool.submit {
                    try {
                        start.await()
                        repeat(n) { i ->
                            audit.record(listOf("id", "$t-$i"), "D09-T", "DENY")
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("concurrent record timed out", done.await(60, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        val snap = audit.snapshot()
        assertEquals(PrivilegeAuditLog.MAX_RECORDS, snap.size)
        val nums = snap.map { seqNum(it.seqId) }
        // 修剪恆刪最舊 seq：窗口即最後 MAX 筆，嚴格遞增、連續、無空洞／倒序。
        val expected = ((total - PrivilegeAuditLog.MAX_RECORDS + 1).toLong()..total.toLong()).toList()
        assertEquals(expected, nums)
        assertEquals("priv-${total - PrivilegeAuditLog.MAX_RECORDS + 1}", snap.first().seqId)
        assertEquals("priv-$total", snap.last().seqId)
    }
}
