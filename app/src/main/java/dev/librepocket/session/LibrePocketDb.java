package dev.librepocket.session;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * P1 database (spec §8): {@code librepocket.db}, version 1, no migrations yet.
 *
 * <p>Written in Java so the plain {@code javac} annotation processor (already declared
 * in the build) generates the implementation; the store and tests stay in Kotlin.
 */
@Database(
        entities = {SessionEntity.class, TranscriptEventEntity.class},
        version = 1,
        exportSchema = false)
public abstract class LibrePocketDb extends RoomDatabase {
    public abstract SessionDao sessionDao();

    public static final String NAME = "librepocket.db";

    /** Product database. */
    public static LibrePocketDb open(Context context) {
        return Room.databaseBuilder(context, LibrePocketDb.class, NAME).build();
    }

    /** Test-only in-memory database (main-thread queries allowed for unit tests). */
    public static LibrePocketDb openInMemory(Context context) {
        return Room.inMemoryDatabaseBuilder(context, LibrePocketDb.class)
                .allowMainThreadQueries()
                .build();
    }
}
