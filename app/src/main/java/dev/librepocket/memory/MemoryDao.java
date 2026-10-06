package dev.librepocket.memory;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

/**
 * D02 记忆 DAO。阻塞调用（Java 无 suspend），Kotlin 侧统一约束到
 * {@code Dispatchers.IO}（见 {@link MemoryStore}）。
 */
@Dao
public interface MemoryDao {
    @Insert
    long insert(MemoryEntity e);

    @Query("SELECT * FROM memories WHERE rowId = :id")
    MemoryEntity byId(long id);

    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit")
    List<MemoryEntity> recent(int limit);

    @Query("SELECT * FROM memories WHERE kind = :kind ORDER BY createdAt DESC LIMIT :limit")
    List<MemoryEntity> byKind(String kind, int limit);

    /** FTS 主路径：MATCH 语法错误由 MemoryStore 捕获后降级到 {@link #searchLike}。 */
    @Query(
            "SELECT m.* FROM memories AS m JOIN memories_fts ON m.rowId = memories_fts.rowid"
                    + " WHERE memories_fts MATCH :query LIMIT :limit")
    List<MemoryEntity> searchFts(String query, int limit);

    /** LIKE 起步路径（FTS 不可用/语法错误时的降级；调用方先转义通配符）。 */
    @Query(
            "SELECT * FROM memories WHERE text LIKE '%' || :token || '%' ESCAPE '\\'"
                    + " ORDER BY createdAt DESC LIMIT :limit")
    List<MemoryEntity> searchLike(String token, int limit);

    /** 物理删除；FTS 触发器同步清除索引行（级联删除）。 */
    @Query("DELETE FROM memories WHERE rowId = :id")
    int deleteById(long id);

    @Query("SELECT COUNT(*) FROM memories")
    int count();

    @Query("SELECT COUNT(*) FROM memories_fts WHERE memories_fts MATCH :query")
    int ftsCount(String query);
}
