package dev.librepocket.session;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * {@code transcript_events} table (spec §8.1); {@code text} is stored redacted.
 *
 * <p>Phase 3 (implemented): {@code isPartial INTEGER NOT NULL DEFAULT 0} marks
 * cancelled / failed assistant rows (they keep their partial text with the flag
 * set) and nullable {@code failureReason TEXT} carries the sanitized error next
 * to that partial text. Both arrived via the backward-compatible
 * {@code MIGRATION_1_2} ({@code ALTER TABLE ... ADD COLUMN}, never a
 * destructive migration); pre-migration rows read back as
 * {@code isPartial=false} / {@code failureReason=null}.
 *
 * <p>Stage C (implemented): nullable {@code parentRunId TEXT} links each
 * attempt-bound assistant terminal back to its logical turn
 * ({@code runId=attemptRunId}, {@code parentRunId=logicalTurnId}; the first
 * attempt reuses the logical id so its parent stays null), and nullable
 * {@code attemptIndex INTEGER} carries the 0-based attempt number. Both
 * arrived via {@code MIGRATION_2_3} (ALTER ADD COLUMN only, never a rebuild);
 * pre-migration rows read back as {@code parentRunId=null} /
 * {@code attemptIndex=null} (single-id legacy semantics).
 *
 * <p>Stage F (implemented): {@code isFinal INTEGER NOT NULL DEFAULT 1}
 * distinguishes a logical-turn final result from a per-attempt intermediate
 * partial. Success, terminal-failure and cancel assistant rows are final;
 * a retryable failure's intermediate fragment (written before its retry
 * notice) is non-final ({@code isFinal=0}). Arrived via {@code MIGRATION_3_4}
 * (ALTER ADD COLUMN only); pre-migration rows read back as final.
 *
 * <p>Written in Java so the plain {@code javac} annotation processor (already declared
 * in the build) generates the Room implementation; the store and tests stay in Kotlin.
 */
@Entity(
        tableName = "transcript_events",
        foreignKeys =
                @ForeignKey(
                        entity = SessionEntity.class,
                        parentColumns = "sessionId",
                        childColumns = "sessionId",
                        onDelete = ForeignKey.CASCADE),
        indices = {@Index({"sessionId", "seq"}), @Index("runId")})
public class TranscriptEventEntity {
    @PrimaryKey(autoGenerate = true)
    private long rowId;

    private String sessionId;
    // Monotonic per session; assigned as (max+1) inside one transaction.
    private long seq;
    private String runId;
    // user|assistant|tool|steer|retry|system
    private String kind;
    // Redacted; single TEXT column, capped at MAX_TEXT_CHARS (spec §8.1).
    private String text;
    private boolean truncated;
    private int imagesOmitted;
    private long createdAt;
    // Phase 3: cancelled / failed assistant fragment marker. The field is named
    // without the `is` prefix (mirroring `truncated` / `isTruncated()`) so the
    // Room processor binds the getter unambiguously; the column keeps the
    // spec §8.1 name `isPartial`.
    @ColumnInfo(name = "isPartial", defaultValue = "0")
    private boolean partial;
    // Phase 3: sanitized failure reason for failed rows; null otherwise.
    @ColumnInfo(name = "failureReason")
    private String failureReason;
    // Stage C: logical turn id for attempt-bound assistant terminals; null for
    // user/retry rows and all pre-C rows (single-id legacy semantics).
    @ColumnInfo(name = "parentRunId")
    private String parentRunId;
    // Stage C: 0-based attempt number for attempt rows; null for user/legacy rows.
    @ColumnInfo(name = "attemptIndex")
    private Integer attemptIndex;
    // Stage F: logical-turn final vs per-attempt intermediate partial. Success,
    // terminal-failure and cancel assistant rows are final (1); a retryable
    // failure's intermediate fragment is non-final (0). The field keeps a
    // non-keyword name (mirroring `partial` / `isPartial()`) so the Room
    // processor binds the getter unambiguously; the column keeps the spec
    // §8.1 name `isFinal`.
    @ColumnInfo(name = "isFinal", defaultValue = "1")
    private boolean finalFlag;

    public TranscriptEventEntity(
            long rowId,
            String sessionId,
            long seq,
            String runId,
            String kind,
            String text,
            boolean truncated,
            int imagesOmitted,
            long createdAt,
            boolean partial,
            String failureReason,
            String parentRunId,
            Integer attemptIndex,
            boolean finalFlag) {
        this.rowId = rowId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.runId = runId;
        this.kind = kind;
        this.text = text;
        this.truncated = truncated;
        this.imagesOmitted = imagesOmitted;
        this.createdAt = createdAt;
        this.partial = partial;
        this.failureReason = failureReason;
        this.parentRunId = parentRunId;
        this.attemptIndex = attemptIndex;
        this.finalFlag = finalFlag;
    }

    // Backward-compatible 13-arg form (pre-F call sites): old rows and old
    // callers default to final (single-terminal legacy semantics).
    @Ignore
    public TranscriptEventEntity(
            long rowId,
            String sessionId,
            long seq,
            String runId,
            String kind,
            String text,
            boolean truncated,
            int imagesOmitted,
            long createdAt,
            boolean partial,
            String failureReason,
            String parentRunId,
            Integer attemptIndex) {
        this(rowId, sessionId, seq, runId, kind, text, truncated, imagesOmitted,
                createdAt, partial, failureReason, parentRunId, attemptIndex, true);
    }

    public long getRowId() {
        return rowId;
    }

    public void setRowId(long rowId) {
        this.rowId = rowId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public int getImagesOmitted() {
        return imagesOmitted;
    }

    public void setImagesOmitted(int imagesOmitted) {
        this.imagesOmitted = imagesOmitted;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isPartial() {
        return partial;
    }

    public void setPartial(boolean partial) {
        this.partial = partial;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public void setParentRunId(String parentRunId) {
        this.parentRunId = parentRunId;
    }

    public Integer getAttemptIndex() {
        return attemptIndex;
    }

    public void setAttemptIndex(Integer attemptIndex) {
        this.attemptIndex = attemptIndex;
    }

    public boolean isFinalFlag() {
        return finalFlag;
    }

    public void setFinalFlag(boolean finalFlag) {
        this.finalFlag = finalFlag;
    }
}
