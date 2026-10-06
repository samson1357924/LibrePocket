package dev.librepocket.memory;

/**
 * D02 记忆三层：FTS 内容表（真相来源）。
 *
 * <p>纯 Java 实体：沿用 {@code session} 包做法，走 {@code javac} 注解处理器生成
 * Room 实现，不引入 kapt/KSP。{@code text} 落盘前已由
 * {@link dev.librepocket.redact.Redactor} 脱敏（见 {@link MemoryStore}）。
 */
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "memories",
        indices = {@Index("kind"), @Index("createdAt")})
public class MemoryEntity {
    @PrimaryKey(autoGenerate = true)
    private long rowId;

    // "episodic" | "semantic"（见 MemoryKind.serial）
    @NonNull private String kind;
    // 已脱敏正文；FTS 同步列
    @NonNull private String text;
    // 写入来源标注（ARCH §10.3：写入需标注来源轮次）
    private String sourceSessionId;
    private String sourceRunId;
    private long createdAt;
    private long updatedAt;

    public MemoryEntity(
            long rowId,
            @NonNull String kind,
            @NonNull String text,
            String sourceSessionId,
            String sourceRunId,
            long createdAt,
            long updatedAt) {
        this.rowId = rowId;
        this.kind = kind;
        this.text = text;
        this.sourceSessionId = sourceSessionId;
        this.sourceRunId = sourceRunId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getRowId() {
        return rowId;
    }

    public void setRowId(long rowId) {
        this.rowId = rowId;
    }

    @NonNull
    public String getKind() {
        return kind;
    }

    public void setKind(@NonNull String kind) {
        this.kind = kind;
    }

    @NonNull
    public String getText() {
        return text;
    }

    public void setText(@NonNull String text) {
        this.text = text;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public void setSourceRunId(String sourceRunId) {
        this.sourceRunId = sourceRunId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
