package dev.librepocket.memory;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * D02 记忆库（独立于会话库 {@code librepocket.db}，只动 memory 包）。
 */
@Database(
        entities = {MemoryEntity.class, MemoryFts.class},
        version = 1,
        exportSchema = false)
public abstract class MemoryDb extends RoomDatabase {
    public abstract MemoryDao memoryDao();

    public static final String NAME = "memory.db";

    /** 产品库。 */
    public static MemoryDb open(Context context) {
        return Room.databaseBuilder(context, MemoryDb.class, NAME).build();
    }

    /** 测试用内存库（单元测试允许主线程查询）。 */
    public static MemoryDb openInMemory(Context context) {
        return Room.inMemoryDatabaseBuilder(context, MemoryDb.class)
                .allowMainThreadQueries()
                .build();
    }
}
