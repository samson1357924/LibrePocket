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

    @Query("SELECT COALESCE(MAX(seq), 0) FROM transcript_events WHERE sessionId = :sid")
    long maxSeq(String sid);

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

    @Query("SELECT sessionId FROM sessions WHERE pinned = 0 AND updatedAt < :cutoff")
    List<String> staleSessionIds(long cutoff);

    @Query("DELETE FROM transcript_events WHERE sessionId = :sid AND seq <= :throughSeq")
    int deleteEventsThrough(String sid, long throughSeq);

    @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId = :sid")
    int eventCount(String sid);
}
