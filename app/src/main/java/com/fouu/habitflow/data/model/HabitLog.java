package com.fouu.habitflow.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.room.Index;

import java.util.Date;

/**
 * HabitLog Entity - records each check-in for a habit
 *
 * One log per habit per day (upsert pattern).
 * Used for streak calculation, analytics, and AI insights.
 */
@Entity(
        tableName = "habit_logs",
        indices = {@Index(value = {"habit_id", "log_date"}, unique = true)}
)
public class HabitLog {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "habit_id")
    private int habitId;

    @ColumnInfo(name = "log_date")
    private Date logDate; // normalized to midnight

    @ColumnInfo(name = "is_completed")
    private boolean completed;

    @ColumnInfo(name = "count")
    private int count; // how many times completed that day

    // Frequency snapshot (DAILY/WEEKDAYS/WEEKLY) captured at write time. Lets analytics
    // compute "should-have-checked-in" days even after the parent habit is hard-deleted
    // (the habits row — and its frequency — is gone, but each log keeps its own copy).
    @ColumnInfo(name = "frequency")
    private String frequency;

    @ColumnInfo(name = "created_at")
    private Date createdAt;

    // Cloud-sync fields
    @ColumnInfo(name = "local_id")
    private String localId; // stable key = habitId + "_" + logDateMillis

    @ColumnInfo(name = "updated_at")
    private long updatedAt; // epoch millis

    public HabitLog() {
        this.createdAt = new Date();
        this.completed = true;
        this.count = 1;
        this.updatedAt = System.currentTimeMillis();
    }

    private void ensureLocalId() {
        if (localId == null && habitId != 0 && logDate != null) {
            localId = habitId + "_" + logDate.getTime();
        }
    }

    // ===== Getters & Setters =====
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getHabitId() { return habitId; }
    public void setHabitId(int habitId) { this.habitId = habitId; }

    public Date getLogDate() { return logDate; }
    public void setLogDate(Date logDate) { this.logDate = logDate; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    // ===== Cloud sync accessors =====
    public String getLocalId() { ensureLocalId(); return localId; }
    public void setLocalId(String localId) { this.localId = localId; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public void touch() { this.updatedAt = System.currentTimeMillis(); ensureLocalId(); }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        ensureLocalId();
        m.put("localId", localId);
        m.put("habitId", habitId);
        m.put("logDate", logDate != null ? logDate.getTime() : null);
        m.put("isCompleted", completed);
        m.put("count", count);
        m.put("frequency", frequency);
        m.put("createdAt", createdAt != null ? createdAt.getTime() : null);
        m.put("updatedAt", updatedAt);
        return m;
    }

    /** Fill fields from a Firestore document map (Long dates -> Date). */
    public void applyFromMap(java.util.Map<String, Object> m) {
        if (m == null) return;
        if (m.get("localId") != null) this.localId = (String) m.get("localId");
        if (m.get("habitId") instanceof Number) this.habitId = ((Number) m.get("habitId")).intValue();
        if (m.get("logDate") instanceof Number) this.logDate = new Date(((Number) m.get("logDate")).longValue());
        if (m.get("isCompleted") instanceof Boolean) this.completed = (Boolean) m.get("isCompleted");
        if (m.get("count") instanceof Number) this.count = ((Number) m.get("count")).intValue();
        if (m.get("frequency") instanceof String) this.frequency = (String) m.get("frequency");

        if (m.get("createdAt") instanceof Number) this.createdAt = new Date(((Number) m.get("createdAt")).longValue());
        if (m.get("updatedAt") instanceof Number) this.updatedAt = ((Number) m.get("updatedAt")).longValue();
    }
}
