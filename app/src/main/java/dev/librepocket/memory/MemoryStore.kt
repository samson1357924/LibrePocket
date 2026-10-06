package dev.librepocket.memory

import dev.librepocket.redact.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D02 情节+语义记忆存储（Room FTS4 主路径，SQLite LIKE 降级）。
 *
 * 写路径保证：
 * - 每次写入前过 [MemoryGate.WRITE]，读取/删除分别过 READ/DELETE；
 * - 正文先经 [Redactor.redact] 脱敏再落盘，超长截断；
 * - 写入强制标注来源（sourceSessionId/sourceRunId 二者至少其一，语义偏好
 *   允许标注 `"user-profile"` 类稳定来源）。
 *
 * 读路径：[search] 先走 FTS MATCH，遇语法错误/驱动不支持即降级为 LIKE
 * （多 token 取交集），空查询直接返回空集（避免 `MATCH ''` 抛错）。
 */
class MemoryStore(
    private val db: MemoryDb,
    private val gate: MemoryGate = AllowMemoryGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao: MemoryDao get() = db.memoryDao()

    /** 写入一条记忆，返回 rowId。 */
    suspend fun save(
        kind: MemoryKind,
        text: String,
        sourceSessionId: String? = null,
        sourceRunId: String? = null,
        createdAt: Long = clock(),
    ): Long {
        gate.require(MemoryAction.WRITE)
        require(text.isNotBlank()) { "memory text must not be blank" }
        require(!sourceSessionId.isNullOrBlank() || !sourceRunId.isNullOrBlank()) {
            "memory write must carry a source (session or run)"
        }
        val redacted = Redactor.redact(text).text
        val stored = if (redacted.length > MAX_TEXT_CHARS) redacted.take(MAX_TEXT_CHARS) else redacted
        val now = clock()
        return withContext(Dispatchers.IO) {
            dao.insert(
                MemoryEntity(
                    0,
                    kind.serial,
                    stored,
                    sourceSessionId,
                    sourceRunId,
                    createdAt,
                    now,
                ),
            )
        }
    }

    /** 全文检索（FTS 主路径，失败降级 LIKE）。 */
    suspend fun search(query: String, limit: Int = 20, kind: MemoryKind? = null): List<MemoryItem> {
        gate.require(MemoryAction.READ)
        require(limit > 0) { "limit must be positive" }
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            // FTS 主路径 + LIKE 补齐后取并集（按 rowId 去重，FTS 命中优先）：
            // FTS 按分词匹配，LIKE 按子串匹配，两者互补，保证 CJK/连字词等
            // 在各种分词器下都有稳定的子串召回下限。
            val fts = try {
                dao.searchFts(toFtsQuery(q), limit * FTS_OVERFETCH)
            } catch (_: Exception) {
                null
            }
            val like = likeFallback(q, limit * FTS_OVERFETCH)
            val merged = if (fts == null) {
                like
            } else {
                val seen = HashSet<Long>(fts.size + like.size)
                val out = ArrayList<MemoryEntity>(fts.size + like.size)
                for (row in fts) {
                    if (seen.add(row.rowId)) out.add(row)
                }
                for (row in like) {
                    if (seen.add(row.rowId)) out.add(row)
                }
                out
            }
            merged
                .let { list -> if (kind == null) list else list.filter { it.kind == kind.serial } }
                .take(limit)
                .map { it.toItem() }
        }
    }

    suspend fun get(id: Long): MemoryItem? {
        gate.require(MemoryAction.READ)
        return withContext(Dispatchers.IO) { dao.byId(id)?.toItem() }
    }

    suspend fun recent(limit: Int = 20, kind: MemoryKind? = null): List<MemoryItem> {
        gate.require(MemoryAction.READ)
        require(limit > 0) { "limit must be positive" }
        return withContext(Dispatchers.IO) {
            if (kind == null) dao.recent(limit).map { it.toItem() }
            else dao.byKind(kind.serial, limit).map { it.toItem() }
        }
    }

    /** 删除单条；FTS 触发器同步清除索引（级联删除），返回是否删过。 */
    suspend fun delete(id: Long): Boolean {
        gate.require(MemoryAction.DELETE)
        return withContext(Dispatchers.IO) { dao.deleteById(id) > 0 }
    }

    suspend fun count(): Int {
        gate.require(MemoryAction.READ)
        return withContext(Dispatchers.IO) { dao.count() }
    }

    // ---- LIKE 降级：首 token 走索引友好的 LIKE，其余 token 在 Kotlin 取交集 ----

    private fun likeFallback(query: String, limit: Int): List<MemoryEntity> {
        val tokens = query.split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val first = dao.searchLike(escapeLike(tokens.first()), limit * LIKE_OVERFETCH)
        if (tokens.size == 1) return first.take(limit)
        val rest = tokens.drop(1).map { it.lowercase() }
        return first
            .filter { row -> rest.all { row.text.lowercase().contains(it) } }
            .take(limit)
    }

    companion object {
        const val MAX_TEXT_CHARS = 20_000
        // FTS 按相关性外的插入序返回时多取再按 kind 过滤，保证 kind 过滤后仍够数。
        const val FTS_OVERFETCH = 5
        const val LIKE_OVERFETCH = 5
        private val WHITESPACE = Regex("\\s+")

        /** 转义 LIKE 通配符（DAO 已配 `ESCAPE '\'`）。 */
        internal fun escapeLike(token: String): String =
            token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * 把用户查询逐 token 包成 FTS phrase（双引号）：`-`/`*`/`OR` 等在
         * MATCH 中是保留运算符，不加引号会改变语义（如 `kw-alpha` 被解析为
         * `kw NOT alpha`）。引号转义后仍是字面匹配。
         */
        internal fun toFtsQuery(query: String): String =
            query.split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
    }
}
