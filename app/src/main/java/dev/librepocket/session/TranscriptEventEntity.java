package dev.librepocket.session;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
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
            String failureReason) {
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
}
