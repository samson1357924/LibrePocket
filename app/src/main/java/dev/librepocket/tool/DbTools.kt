package dev.librepocket.tool

/**
 * S1-B `db.query` (READ) + `db.exec` (WRITE)：應用私有庫的受限 SQL 面。
 *
 * - `db.query`：只讀 SELECT（僅 `SELECT` 開頭，單語句；`WITH` 開頭一律拒絕，
 *   避免 `WITH … DELETE/UPDATE` 繞過），
 *   行數上限（`limit` 預設 50，硬頂 [MAX_ROWS]=200，超限截斷標記）；
 * - `db.exec`：WRITE，需呼叫方確認（`confirmed=true`，否則 [ExecOutcome.NeedConfirm]），
 *   詞法禁 `ATTACH / DETACH / DROP`（fail-closed：字串字面量內命中亦拒絕）；
 * - 兩者皆限單語句（除結尾分號外不得再含 `;`）、SQL 長度上限 [MAX_SQL_CHARS]；
 * - 開關 `database` 預設 false。
 *
 * 執行縫 [Connection] 為最小抽象（Room/SQLite 實接為後續工作；單測用假實作）。
 * 純 JVM（無 Android API），可在單元測試直接執行。
 */
object DbTools {

    const val QUERY_NAME = "db.query"
    const val EXEC_NAME = "db.exec"
    const val SWITCH = "database"
    const val SWITCH_DEFAULT = false

    const val DEFAULT_LIMIT = 50
    const val MAX_ROWS = 200
    const val MAX_SQL_CHARS = 8_192

    const val FALLBACK_HINT = "inspect the database via the app's export or debug path manually"

    /** `db.exec` 詞法黑名單（fail-closed，含 DETACH 以封死 ATTACH 配對）。 */
    private val forbiddenExec = Regex("\\b(attach|detach|drop)\\b", RegexOption.IGNORE_CASE)

    private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    private val firstWord = Regex("^[A-Za-z]+")

    /** 最小執行縫：呼叫方保證 [query] 遵守 `limit`；此處仍防禦性再截斷。 */
    interface Connection {
        fun query(sql: String, limit: Int): List<Map<String, Any?>>
        fun exec(sql: String): Int
    }

    sealed interface QueryOutcome {
        data class Ok(val rows: List<Map<String, Any?>>, val truncated: Boolean) : QueryOutcome
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : QueryOutcome
    }

    sealed interface ExecOutcome {
        data class Ok(val rowsAffected: Int) : ExecOutcome
        data class NeedConfirm(val toolName: String) : ExecOutcome
        data class Denied(val reason: DenyReason, val detail: String, val message: String) : ExecOutcome
    }

    fun limitOf(raw: Int?): Int = (raw ?: DEFAULT_LIMIT).coerceIn(1, MAX_ROWS)

    /** 去註釋（行註釋 `--`、塊註釋 `/*…*/`）；字串字面量內誤傷方向為 fail-closed。 */
    fun stripComments(sql: String): String {
        val noBlock = blockComment.replace(sql, " ")
        return noBlock.lines().joinToString("\n") { line ->
            val i = line.indexOf("--")
            if (i >= 0) line.substring(0, i) else line
        }
    }

    /** 除結尾分號外是否僅含單語句。 */
    fun isSingleStatement(sql: String): Boolean {
        val clean = stripComments(sql).trim()
        if (clean.isEmpty()) return false
        val body = if (clean.endsWith(";")) clean.dropLast(1) else clean
        return ';' !in body
    }

    /** 是否只讀 SELECT（僅 `SELECT` 開頭；`WITH` 開頭一律拒絕，避免 SQLite `WITH … DELETE/UPDATE` 繞過）。 */
    fun isReadOnlySelect(sql: String): Boolean {
        if (sql.length > MAX_SQL_CHARS || sql.isBlank()) return false
        if (!isSingleStatement(sql)) return false
        val head = stripComments(sql).trimStart()
        val keyword = firstWord.find(head)?.value?.uppercase() ?: return false
        return keyword == "SELECT"
    }

    /** `db.query` 守衛：null 表示放行，否則為拒絕細分碼。 */
    fun queryVeto(sql: String): String? {
        if (sql.isBlank()) return "EMPTY_SQL"
        if (sql.length > MAX_SQL_CHARS) return "SQL_TOO_LONG"
        if (!isSingleStatement(sql)) return "MULTI_STATEMENT"
        if (!isReadOnlySelect(sql)) return "NOT_SELECT"
        return null
    }

    /** `db.exec` 守衛：null 表示放行（仍需確認），否則為拒絕細分碼。 */
    fun execVeto(sql: String): String? {
        if (sql.isBlank()) return "EMPTY_SQL"
        if (sql.length > MAX_SQL_CHARS) return "SQL_TOO_LONG"
        if (!isSingleStatement(sql)) return "MULTI_STATEMENT"
        val hit = forbiddenExec.find(stripComments(sql))
        if (hit != null) return "FORBIDDEN_KEYWORD:${hit.value.uppercase()}"
        return null
    }

    fun query(sql: String, limit: Int = DEFAULT_LIMIT, conn: Connection): QueryOutcome {
        val veto = queryVeto(sql)
        if (veto != null) return denied("查詢（$veto）", veto)
        val cap = limitOf(limit)
        val rows = conn.query(sql, cap + 1)
        val truncated = rows.size > cap
        return QueryOutcome.Ok(rows.take(cap), truncated)
    }

    fun exec(sql: String, confirmed: Boolean, conn: Connection): ExecOutcome {
        if (!confirmed) return ExecOutcome.NeedConfirm(EXEC_NAME)
        val veto = execVeto(sql)
        if (veto != null) {
            val denied = denied("執行資料庫寫入（$veto）", veto)
            return ExecOutcome.Denied(denied.reason, denied.detail, denied.message)
        }
        return ExecOutcome.Ok(conn.exec(sql))
    }

    private fun denied(what: String, detail: String): QueryOutcome.Denied =
        QueryOutcome.Denied(
            reason = DenyReason.NO_PRIVILEGE,
            detail = detail,
            message = S1bFallback.message(
                what = what,
                reason = DenyReason.NO_PRIVILEGE,
                detail = detail,
                alternative = FALLBACK_HINT,
                needFromUser = "改用唯讀查詢或手動匯出資料庫處理",
            ),
        )
}
