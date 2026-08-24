package com.fouu.habitflow.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

import java.util.Date;

/**
 * FocusSession Entity - tracks Pomodoro / Deep Work sessions
 *
 * Used for productivity analytics and AI-generated insights.
 */
@Entity(tableName = "focus_sessions")
public class FocusSession {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "habit_id")
    private Integer habitId; // optional link to a habit

    @ColumnInfo(name = "start_time")
    private Date startTime;

    @ColumnInfo(name = "end_time")
    private Date endTime;

    @ColumnInfo(name = "duration_minutes")
    private int durationMinutes;

    @ColumnInfo(name = "session_type")
    private String sessionType; // FOCUS, SHORT_BREAK, LONG_BREAK

    @ColumnInfo(name = "is_completed")
    private boolean completed;

    @ColumnInfo(name = "interruptions")
    private int interruptions;

    // Cloud-sync fields
    @ColumnInfo(name = "client_id")
    private String clientId; // stable key = "f_" + startTimeMillis

    @ColumnInfo(name = "updated_at")
    private long updatedAt;

    public FocusSession() {
        this.startTime = new Date();
        this.sessionType = "FOCUS";
        this.completed = false;
        this.updatedAt = System.currentTimeMillis();
    }

    private void ensureClientId() {
        if (clientId == null && startTime != null) {
            clientId = "f_" + startTime.getTime();
        }
    }

    // ===== Getters & Setters =====
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public Integer getHabitId() { return habitId; }
    public void setHabitId(Integer habitId) { this.habitId = habitId; }

    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }

    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }

    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }

    public String getSessionType() { return sessionType; }
    public void setSessionType(String sessionType) { this.sessionType = sessionType; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public int getInterruptions() { return interruptions; }
    public void setInterruptions(int interruptions) { this.interruptions = interruptions; }

    // ===== Cloud sync accessors =====
    public String getClientId() { ensureClientId(); return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public void touch() { this.updatedAt = System.currentTimeMillis(); ensureClientId(); }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        ensureClientId();
        m.put("clientId", clientId);
        m.put("habitId", habitId);
        m.put("startTime", startTime != null ? startTime.getTime() : null);
        m.put("endTime", endTime != null ? endTime.getTime() : null);
        m.put("durationMinutes", durationMinutes);
        m.put("sessionType", sessionType);
        m.put("isCompleted", completed);
        m.put("interruptions", interruptions);
        m.put("updatedAt", updatedAt);
        return m;
    }

    /** Fill fields from a Firestore document map (Long dates -> Date). */
    public void applyFromMap(java.util.Map<String, Object> m) {
        if (m == null) return;
        if (m.get("clientId") != null) this.clientId = (String) m.get("clientId");
        if (m.get("habitId") instanceof Number) this.habitId = ((Number) m.get("habitId")).intValue();
        if (m.get("startTime") instanceof Number) this.startTime = new Date(((Number) m.get("startTime")).longValue());
        if (m.get("endTime") instanceof Number) this.endTime = new Date(((Number) m.get("endTime")).longValue());
        if (m.get("durationMinutes") instanceof Number) this.durationMinutes = ((Number) m.get("durationMinutes")).intValue();
        if (m.get("sessionType") != null) this.sessionType = (String) m.get("sessionType");
        if (m.get("isCompleted") instanceof Boolean) this.completed = (Boolean) m.get("isCompleted");
        if (m.get("interruptions") instanceof Number) this.interruptions = ((Number) m.get("interruptions")).intValue();
        if (m.get("updatedAt") instanceof Number) this.updatedAt = ((Number) m.get("updatedAt")).longValue();
    }
}
