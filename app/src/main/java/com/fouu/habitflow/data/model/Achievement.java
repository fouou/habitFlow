package com.fouu.habitflow.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

import java.util.Date;

/**
 * Achievement Entity - gamification badges
 *
 * Examples:
 * - "Week Warrior" - 7-day streak
 * - "Month Master" - 30-day streak
 * - "Century Club" - 100-day streak
 * - "Focus Pro" - 10 hours total focus time
 */
@Entity(tableName = "achievements")
public class Achievement {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "title")
    private String title;

    @ColumnInfo(name = "description")
    private String description;

    @ColumnInfo(name = "icon_name")
    private String iconName;

    @ColumnInfo(name = "unlocked_at")
    private Date unlockedAt;

    @ColumnInfo(name = "is_unlocked")
    private boolean unlocked;

    @ColumnInfo(name = "progress")
    private int progress; // 0-100

    @ColumnInfo(name = "target_value")
    private int targetValue;

    // Cloud-sync fields
    @ColumnInfo(name = "local_id")
    private String localId; // stable key = title (achievements are seeded by title)

    @ColumnInfo(name = "updated_at")
    private long updatedAt;

    public Achievement() {
        this.unlocked = false;
        this.progress = 0;
        this.updatedAt = System.currentTimeMillis();
    }

    private void ensureLocalId() {
        if (localId == null && title != null) localId = title;
    }

    // ===== Getters & Setters =====
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIconName() { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public Date getUnlockedAt() { return unlockedAt; }
    public void setUnlockedAt(Date unlockedAt) { this.unlockedAt = unlockedAt; }

    public boolean isUnlocked() { return unlocked; }
    public void setUnlocked(boolean unlocked) { this.unlocked = unlocked; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public int getTargetValue() { return targetValue; }
    public void setTargetValue(int targetValue) { this.targetValue = targetValue; }

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
        m.put("title", title);
        m.put("description", description);
        m.put("iconName", iconName);
        m.put("unlockedAt", unlockedAt != null ? unlockedAt.getTime() : null);
        m.put("isUnlocked", unlocked);
        m.put("progress", progress);
        m.put("targetValue", targetValue);
        m.put("updatedAt", updatedAt);
        return m;
    }

    /** Fill fields from a Firestore document map (Long dates -> Date). */
    public void applyFromMap(java.util.Map<String, Object> m) {
        if (m == null) return;
        if (m.get("localId") != null) this.localId = (String) m.get("localId");
        if (m.get("title") != null) this.title = (String) m.get("title");
        if (m.get("description") != null) this.description = (String) m.get("description");
        if (m.get("iconName") != null) this.iconName = (String) m.get("iconName");
        if (m.get("unlockedAt") instanceof Number) this.unlockedAt = new Date(((Number) m.get("unlockedAt")).longValue());
        if (m.get("isUnlocked") instanceof Boolean) this.unlocked = (Boolean) m.get("isUnlocked");
        if (m.get("progress") instanceof Number) this.progress = ((Number) m.get("progress")).intValue();
        if (m.get("targetValue") instanceof Number) this.targetValue = ((Number) m.get("targetValue")).intValue();
        if (m.get("updatedAt") instanceof Number) this.updatedAt = ((Number) m.get("updatedAt")).longValue();
    }
}
