package dev.librepocket.memory;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Fts4;

/**
 * D02 FTS4 索引表：内容同步自 {@link MemoryEntity}（仅同步 {@code text} 列）。
 *
 * <p>Room 自动建触发器维护同步：删 {@code memories} 单条时 FTS 行同步消失
 * （级联删除），见 {@code MemoryFtsTest.delete_removesFromIndex}。
 */
@Fts4(contentEntity = MemoryEntity.class)
@Entity(tableName = "memories_fts")
public class MemoryFts {
    @ColumnInfo(name = "text")
    private String text;

    public MemoryFts(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
