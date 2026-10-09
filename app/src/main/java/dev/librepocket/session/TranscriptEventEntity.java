package dev.librepocket.session;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * {@code transcript_events} table (spec §8.1); {@code text} is stored redacted.
 *
 * <p>Phase 3 Target (no schema change in Phase 2: this database is version 1
 * with no Migration infrastructure, and the JSONL wire format in
 * {@code JsonlCodec} is a closed object — both need a coordinated,
 * device-verified change, so the partial/failed columns stay a Phase 3
 * decision): {@code isPartial INTEGER NOT NULL DEFAULT 0} for cancelled /
 * failed assistant rows plus nullable {@code failureReason TEXT} for failed
 * rows, added via a backward-compatible {@code Migration(1, 2)} using
 * {@code ALTER TABLE transcript_events ADD COLUMN ...} (never a destructive
 * migration), with matching {@code TranscriptEvent} fields, DAO pass-through,
 * and JSONL optional-key decode. Until then partial-ness lives only in UI
 * memory and the in-memory INTERRUPTED marks.
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

    public TranscriptEventEntity(
            long rowId,
            String sessionId,
            long seq,
            String runId,
            String kind,
            String text,
            boolean truncated,
            int imagesOmitted,
            long createdAt) {
        this.rowId = rowId;
        this.sessionId = sessionId;
        this.seq = seq;
        this.runId = runId;
        this.kind = kind;
        this.text = text;
        this.truncated = truncated;
        this.imagesOmitted = imagesOmitted;
        this.createdAt = createdAt;
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
}
