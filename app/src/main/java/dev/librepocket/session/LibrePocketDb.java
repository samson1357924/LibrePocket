package dev.librepocket.session;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * P1 database (spec §8): {@code librepocket.db}, version 4.
 *
 * <p>Phase 3 (implemented): version 2 adds {@code isPartial INTEGER NOT NULL
 * DEFAULT 0} and nullable {@code failureReason TEXT} to
 * {@code transcript_events} via the backward-compatible
 * {@code MIGRATION_1_2} ({@code ALTER TABLE ... ADD COLUMN}, never a
 * destructive migration). Pre-migration rows keep their text and read back
 * with {@code isPartial=false} / {@code failureReason=null}.
 *
 * <p>Stage C (implemented): version 3 adds nullable {@code parentRunId TEXT}
 * and nullable {@code attemptIndex INTEGER} via {@code MIGRATION_2_3}
 * (ALTER ADD COLUMN only, never a table rebuild). Pre-migration rows read
 * back with {@code parentRunId=null} / {@code attemptIndex=null} (single-id
 * legacy semantics: family = {@code runId}).
 *
 * <p>Stage F (implemented): version 4 adds {@code isFinal INTEGER NOT NULL
 * DEFAULT 1} via {@code MIGRATION_3_4} (ALTER ADD COLUMN only). Success,
 * terminal-failure and cancel assistant rows are final; retryable-failed
 * intermediate partials are non-final ({@code isFinal=0}). Pre-migration rows
 * read back as final (old single-terminal semantics).
 *
 * <p>Written in Java so the plain {@code javac} annotation processor (already declared
 * in the build) generates the implementation; the store and tests stay in Kotlin.
 */
@Database(
        entities = {SessionEntity.class, TranscriptEventEntity.class},
        version = 4,
        exportSchema = false)
public abstract class LibrePocketDb extends RoomDatabase {
    public abstract SessionDao sessionDao();

    public static final String NAME = "librepocket.db";

    /**
     * Backward-compatible 1→2: existing rows are preserved; the new columns
     * arrive with safe defaults (partial=false via {@code DEFAULT 0},
     * reason=null).
     */
    public static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE transcript_events "
                                    + "ADD COLUMN isPartial INTEGER NOT NULL DEFAULT 0");
                    db.execSQL("ALTER TABLE transcript_events ADD COLUMN failureReason TEXT");
                }
            };

    /**
     * Backward-compatible 2→3: existing rows are preserved; the new nullable
     * linkage columns arrive as null (legacy single-id semantics).
     */
    public static final Migration MIGRATION_2_3 =
            new Migration(2, 3) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL("ALTER TABLE transcript_events ADD COLUMN parentRunId TEXT");
                    db.execSQL("ALTER TABLE transcript_events ADD COLUMN attemptIndex INTEGER");
                }
            };

    /**
     * Backward-compatible 3→4: existing rows are preserved; the new final
     * flag arrives as 1 (old single-terminal semantics: every pre-F row
     * counts as final).
     */
    public static final Migration MIGRATION_3_4 =
            new Migration(3, 4) {
                @Override
                public void migrate(SupportSQLiteDatabase db) {
                    db.execSQL(
                            "ALTER TABLE transcript_events "
                                    + "ADD COLUMN isFinal INTEGER NOT NULL DEFAULT 1");
                }
            };

    /** Product database. */
    public static LibrePocketDb open(Context context) {
        return Room.databaseBuilder(context, LibrePocketDb.class, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build();
    }

    /** Test-only in-memory database (main-thread queries allowed for unit tests). */
    public static LibrePocketDb openInMemory(Context context) {
        return Room.inMemoryDatabaseBuilder(context, LibrePocketDb.class)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build();
    }
}
