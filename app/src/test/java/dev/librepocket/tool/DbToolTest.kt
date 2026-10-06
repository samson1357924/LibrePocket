package dev.librepocket.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1-B `db.query` / `db.exec` 測試：SELECT-only、行數上限、寫入需確認、
 * ATTACH/DROP 詞法禁令、開關預設關。執行縫用記憶體假實作，不碰真實資料庫。
 */
class DbToolTest {

    private fun fakeDb(rows: List<Map<String, Any?>> = emptyList()): DbTools.Connection =
        object : DbTools.Connection {
            var lastLimit: Int = -1
            override fun query(sql: String, limit: Int): List<Map<String, Any?>> {
                lastLimit = limit
                return rows
            }

            override fun exec(sql: String): Int = 1
        }

    // ---- db.query：只讀 SELECT ----

    @Test fun selectVariantsAreReadOnly() {
        assertTrue(DbTools.isReadOnlySelect("SELECT * FROM t"))
        assertTrue(DbTools.isReadOnlySelect("  select a, b from t where x = 1;"))
        assertTrue(DbTools.isReadOnlySelect("-- comment\nSELECT 1"))
        // BLOCKER-1：`WITH` 開頭一律拒絕（SQLite 允許 `WITH … DELETE/UPDATE`），即使尾隨 SELECT 亦然。
        assertFalse(DbTools.isReadOnlySelect("/* c */ WITH cte AS (SELECT 1) SELECT * FROM cte"))
    }

    @Test fun withDeleteBypassIsNotSelect() {
        val bypass = "WITH x AS (SELECT 1) DELETE FROM t"
        assertFalse(bypass, DbTools.isReadOnlySelect(bypass))
        assertEquals("NOT_SELECT", DbTools.queryVeto(bypass))
    }

    @Test fun nonSelectIsRejectedFromQuery() {
        for (sql in listOf(
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "DROP TABLE t",
            "ATTACH DATABASE 'x' AS y",
            "PRAGMA table_info(t)",
            "SELECT 1; DELETE FROM t",
            "WITH x AS (SELECT 1) DELETE FROM t",
            "WITH cte AS (SELECT 1) UPDATE t SET a = 1",
            "WITH cte AS (SELECT 1) SELECT * FROM cte",
            "",
            "   ",
        )) {
            assertFalse(sql, DbTools.isReadOnlySelect(sql))
            assertTrue(sql, DbTools.queryVeto(sql) != null)
        }
    }

    @Test fun queryDenialUsesMatrixTemplate() {
        val outcome = DbTools.query("DROP TABLE t", 10, fakeDb())
        assertTrue(outcome is DbTools.QueryOutcome.Denied)
        val denied = outcome as DbTools.QueryOutcome.Denied
        assertEquals(DenyReason.NO_PRIVILEGE, denied.reason)
        assertEquals("NOT_SELECT", denied.detail)
        assertTrue(denied.message.contains("做不到") && denied.message.contains("NO_PRIVILEGE"))
    }

    @Test fun queryRowCapTruncates() {
        val big = List(250) { mapOf<String, Any?>("id" to it) }
        val outcome = DbTools.query("SELECT * FROM t", 50, fakeDb(big))
        assertTrue(outcome is DbTools.QueryOutcome.Ok)
        val ok = outcome as DbTools.QueryOutcome.Ok
        assertEquals(50, ok.rows.size)
        assertTrue(ok.truncated)
    }

    @Test fun queryLimitClampedToMaxRows() {
        assertEquals(DbTools.MAX_ROWS, DbTools.limitOf(10_000))
        assertEquals(1, DbTools.limitOf(0))
        assertEquals(DbTools.DEFAULT_LIMIT, DbTools.limitOf(null))
    }

    // ---- db.exec：需確認 + 禁 ATTACH/DROP ----

    @Test fun execWithoutConfirmNeedsConfirm() {
        val outcome = DbTools.exec("INSERT INTO t VALUES (1)", confirmed = false, fakeDb())
        assertTrue(outcome is DbTools.ExecOutcome.NeedConfirm)
        assertEquals(DbTools.EXEC_NAME, (outcome as DbTools.ExecOutcome.NeedConfirm).toolName)
    }

    @Test fun execBansAttachAndDropCaseInsensitively() {
        for (sql in listOf(
            "ATTACH DATABASE '/x' AS y",
            "attach database '/x' as y",
            "DROP TABLE t",
            "drop table t",
            // 無內部分號 → 單語句，直達關鍵字禁令（有分號版歸 MULTI_STATEMENT，見下）。
            "CREATE TRIGGER x BEFORE DELETE ON t BEGIN DROP TABLE u END",
        )) {
            val veto = DbTools.execVeto(sql)
            assertTrue(sql, veto != null && veto.startsWith("FORBIDDEN_KEYWORD"))
        }
        assertEquals(
            "MULTI_STATEMENT",
            DbTools.execVeto("CREATE TRIGGER x BEFORE DELETE ON t BEGIN DROP TABLE u; END"),
        )
    }

    @Test fun execDenialCarriesForbiddenKeyword() {
        // 多語句同時命中：MULTI_STATEMENT 優先於關鍵字（先定界再定性）。
        val multi = DbTools.exec("DROP TABLE a; DROP TABLE b", confirmed = true, fakeDb())
        assertTrue(multi is DbTools.ExecOutcome.Denied)
        assertEquals("MULTI_STATEMENT", (multi as DbTools.ExecOutcome.Denied).detail)
        val single = DbTools.exec("DROP TABLE t", confirmed = true, fakeDb())
        assertTrue(single is DbTools.ExecOutcome.Denied)
        assertTrue((single as DbTools.ExecOutcome.Denied).detail.contains("DROP"))
    }

    @Test fun confirmedBenignWriteExecutes() {
        val outcome = DbTools.exec("INSERT INTO t VALUES (1)", confirmed = true, fakeDb())
        assertTrue(outcome is DbTools.ExecOutcome.Ok)
        assertEquals(1, (outcome as DbTools.ExecOutcome.Ok).rowsAffected)
    }

    // ---- 能力投影（開關預設關） ----

    @Test fun databaseSwitchDefaultsOff() {
        val ctx = ProjectionContext(flavor = Flavor.PLAY)
        for (name in listOf(DbTools.QUERY_NAME, DbTools.EXEC_NAME)) {
            val projected = ToolRegistry.projectAll(ctx)[name]!!
            assertEquals(name, CapabilityLevel.UNAVAILABLE, projected.level)
            assertEquals(DenyReason.USER_DISABLED, projected.reason)
        }
    }

    @Test fun databaseOptInBecomesNative() {
        val ctx = ProjectionContext(
            flavor = Flavor.PLAY,
            userSwitches = mapOf(DbTools.SWITCH to true),
        )
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(ctx)[DbTools.QUERY_NAME]!!.level)
        assertEquals(CapabilityLevel.NATIVE, ToolRegistry.projectAll(ctx)[DbTools.EXEC_NAME]!!.level)
    }

    @Test fun execIsWriteTier_queryIsReadTier() {
        assertEquals(SideEffect.WRITE, ToolRegistry.find(DbTools.EXEC_NAME)!!.sideEffect)
        assertEquals(SideEffect.READ, ToolRegistry.find(DbTools.QUERY_NAME)!!.sideEffect)
    }
}
