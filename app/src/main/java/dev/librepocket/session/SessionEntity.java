package dev.librepocket.session;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * {@code sessions} table (spec §8.1).
 *
 * <p>Written in Java so the plain {@code javac} annotation processor (already declared
 * in the build) generates the Room implementation; the store and tests stay in Kotlin.
 */
@Entity(tableName = "sessions")
public class SessionEntity {
    @PrimaryKey @NonNull private String sessionId;
    private String title;
    private String model;
    private long createdAt;
    private long updatedAt;
    private boolean pinned;

    public SessionEntity(
            @NonNull String sessionId,
            String title,
            String model,
            long createdAt,
            long updatedAt,
            boolean pinned) {
        this.sessionId = sessionId;
        this.title = title;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.pinned = pinned;
    }

    @NonNull
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(@NonNull String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }
}
