package dev.librepocket.session;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

/**
 * Transcript DAO (spec §8.1, plus the small extra reads prune/export need).
 *
 * <p>Blocking calls (Java has no suspend); the Kotlin store confines them to
 * {@code Dispatchers.IO}. Written in Java so the plain {@code javac} annotation
 * processor generates the implementation without kapt/KSP.
 */
@Dao
public interface SessionDao {
    @Insert
    void insertSession(SessionEntity s);

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    List<SessionEntity> allSessions();

    @Query("SELECT * FROM sessions WHERE sessionId = :sid")
    SessionEntity sessionById(String sid);

    @Query("UPDATE sessions SET updatedAt = :now WHERE sessionId = :sid")
    void touchSession(String sid, long now);

    @Query("UPDATE sessions SET pinned = :pinned WHERE sessionId = :sid")
    void setPinned(String sid, boolean pinned);

    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM transcript_events WHERE sessionId = :sid")
    long nextSeq(String sid);

    @Insert
    long insertEvent(TranscriptEventEntity e);

    @Query(
            "SELECT * FROM transcript_events WHERE sessionId = :sid AND seq > :after "
                    + "ORDER BY seq ASC LIMIT :limit")
    List<TranscriptEventEntity> eventsAfter(String sid, long after, int limit);

    @Query("SELECT * FROM transcript_events WHERE sessionId = :sid ORDER BY seq ASC")
    List<TranscriptEventEntity> allEvents(String sid);

    @Query("DELETE FROM sessions WHERE sessionId = :sid")
    void deleteSession(String sid); // CASCADE clears events

    @Query("DELETE FROM sessions WHERE sessionId = :sid AND updatedAt < :cutoff "
            + "AND (:keepPinned = 0 OR pinned = 0)")
    int deleteSessionIfStale(String sid, long cutoff, boolean keepPinned);

    @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId = :sid")
    int eventCount(String sid);

    @Query("DELETE FROM transcript_events WHERE sessionId = :sid AND rowId NOT IN "
            + "(SELECT rowId FROM transcript_events WHERE sessionId = :sid "
            + "ORDER BY seq DESC, rowId DESC LIMIT :keep)")
    int deleteEventsBeyondLimit(String sid, int keep);
}
