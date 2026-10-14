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

    // D15-5: raw MAX (no +1 here) so append paths can fail closed when the
    // seq space is exhausted (imported Long.MAX_VALUE) instead of inserting
    // an overflowed/wrapped row the non-unique index would not catch.
    @Query("SELECT COALESCE(MAX(seq), 0) FROM transcript_events WHERE sessionId = :sid")
    long maxSeq(String sid);

    @Insert
    long insertEvent(TranscriptEventEntity e);

    @Query(
            "SELECT * FROM transcript_events WHERE sessionId = :sid AND seq > :after "
                    + "ORDER BY seq ASC LIMIT :limit")
    List<TranscriptEventEntity> eventsAfter(String sid, long after, int limit);

    @Query(
            "SELECT * FROM transcript_events WHERE sessionId = :sid "
                    + "ORDER BY seq DESC LIMIT :limit")
    List<TranscriptEventEntity> eventsTail(String sid, int limit);

    @Query(
            "SELECT * FROM transcript_events WHERE sessionId = :sid AND seq < :before "
                    + "ORDER BY seq DESC LIMIT :limit")
    List<TranscriptEventEntity> eventsBefore(String sid, long before, int limit);

    @Query("SELECT * FROM transcript_events WHERE sessionId = :sid ORDER BY seq ASC")
    List<TranscriptEventEntity> allEvents(String sid);

    @Query("DELETE FROM sessions WHERE sessionId = :sid")
    void deleteSession(String sid); // CASCADE clears events

    @Query("DELETE FROM sessions WHERE sessionId = :sid AND updatedAt < :cutoff "
            + "AND (:keepPinned = 0 OR pinned = 0)")
    int deleteSessionIfStale(String sid, long cutoff, boolean keepPinned);

    @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId = :sid")
    int eventCount(String sid);

    @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId = :sid AND kind IN ('user','assistant')")
    int countUiHistory(String sid);

    @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId = :sid AND kind IN ('user','assistant') AND isPartial = 0")
    int countModelHistory(String sid);

    @Query("SELECT COUNT(*) FROM transcript_events WHERE sessionId = :sid AND seq < :before AND kind = 'reasoning'")
    int countReasoningBefore(String sid, long before);

    @Query("DELETE FROM transcript_events WHERE sessionId = :sid AND rowId NOT IN "
            + "(SELECT rowId FROM transcript_events WHERE sessionId = :sid "
            + "ORDER BY seq DESC, rowId DESC LIMIT :keep)")
    int deleteEventsBeyondLimit(String sid, int keep);

    /**
     * R26-1 family-aware prune boundary (oldest user/assistant, ascending).
     * Backs the post-delete head-orphan fixup: the kept window must start at
     * a user boundary, never a user-less assistant. Ordering mirrors
     * {@code deleteEventsBeyondLimit} (seq, then rowId) so the head row the
     * fixup inspects is the same row the keep-newest cut left behind.
     *
     * <p>Shape assumptions (production write order: user, then its
     * marker-first rows, then the terminal assistant; {@code runId} unique
     * per attempt, logical family {@code parentRunId ?: runId}): the head
     * family delete is exact. Crafted ledgers that reuse one {@code runId}
     * across unrelated turns or interleave rows out of order over-delete
     * (every remaining row sharing the head family id goes, even mid-ledger
     * rows) — conservative fail-closed direction, pinned by test.
     *
     * <p>Partial caveat: no {@code isPartial} filter here, so a crafted head
     * partial row counts as the boundary family; readers exclude partials
     * from model context anyway, so the fixup may delete a family the model
     * path would have ignored — again conservative, disclosed.
     */
    @Query("SELECT * FROM transcript_events WHERE sessionId = :sid AND kind IN ('user','assistant') "
            + "ORDER BY seq ASC, rowId ASC LIMIT :limit")
    List<TranscriptEventEntity> oldestHistory(String sid, int limit);

    /**
     * R26-1 whole-family delete (logical family {@code parentRunId ?: runId}):
     * removes every remaining row of one cut family so a prune cut never
     * strands a user-less assistant (marker-first implies user-less, as the
     * user row is older than its marker).
     */
    @Query("DELETE FROM transcript_events WHERE sessionId = :sid AND (runId = :fid OR parentRunId = :fid)")
    int deleteFamily(String sid, String fid);
}
