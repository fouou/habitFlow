package com.fouu.habitflow.data.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

import java.util.Date;

/**
 * Habit Entity - represents a user-defined habit
 *
 * Fields:
 * - id: auto-generated primary key
 * - name: habit title (e.g., "Drink Water", "Read 30 min")
 * - description: optional details
 * - iconName: Material icon identifier
 * - colorHex: user-chosen accent color
 * - frequency: DAILY, WEEKDAYS, WEEKLY, CUSTOM
 * - targetCount: how many times per day
 * - reminderEnabled: boolean for notification
 * - reminderTime: milliseconds from midnight
 * - streak: current consecutive days
 * - bestStreak: all-time best
 * - createdAt: timestamp
 * - isArchived: soft delete flag
 */
@Entity(tableName = "habits")
public class Habit {

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "name")
    private String name;

    @ColumnInfo(name = "description")
    private String description;

    @ColumnInfo(name = "icon_name")
    private String iconName; // e.g., "ic_water", "ic_book"

    @ColumnInfo(name = "color_hex")
    private String colorHex = COLOR_DEFAULT; // "default" => use the M3 surface color (no tint)

    /** Sentinel meaning "use the system default card color (M3 surface), no accent tint". */
    public static final String COLOR_DEFAULT = "default";

    /** Fallback accent used only if a stored color is somehow invalid. */
    public static final String DEFAULT_COLOR_HEX = "#6750A4";

    @ColumnInfo(name = "frequency")
    private String frequency; // DAILY, WEEKDAYS, WEEKLY, CUSTOM

    @ColumnInfo(name = "target_count")
    private int targetCount; // times per day

    @ColumnInfo(name = "reminder_enabled")
    private boolean reminderEnabled;

    @ColumnInfo(name = "reminder_time")
    private long reminderTime; // millis from midnight

    @ColumnInfo(name = "streak")
    private int streak;

    @ColumnInfo(name = "best_streak")
    private int bestStreak;

    @ColumnInfo(name = "created_at")
    private Date createdAt;

    @ColumnInfo(name = "is_archived")
    private boolean isArchived;

    @ColumnInfo(name = "archived_at")
    private Date archivedAt; // when this habit was soft-deleted (null if active)

    @androidx.room.Ignore
    private boolean todayCompleted; // UI-only: whether completed today (not persisted)

    @androidx.room.Ignore
    private int todayCount; // UI-only: how many times checked in today (not persisted)

    // Cloud-sync fields (NOT used by Room as PK). localId is a globally-unique
    // UUID used as the stable Firestore document id, so a newly created local
    // item can never collide with / overwrite an existing server document.
    @ColumnInfo(name = "local_id")
    private String localId;

    @ColumnInfo(name = "updated_at")
    private long updatedAt; // epoch millis; last-write-wins merge key

    // ===== Constructors =====
    public Habit() {
        this.createdAt = new Date();
        this.streak = 0;
        this.bestStreak = 0;
        this.targetCount = 1;
        this.frequency = "DAILY";
        this.reminderEnabled = false;
        this.updatedAt = System.currentTimeMillis();
    }

    // ===== Getters & Setters =====
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIconName() { return iconName; }
    public void setIconName(String iconName) { this.iconName = iconName; }

    public String getColorHex() { return colorHex; }
    public void setColorHex(String colorHex) { this.colorHex = colorHex; }

    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }

    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int targetCount) { this.targetCount = targetCount; }

    public boolean isReminderEnabled() { return reminderEnabled; }
    public void setReminderEnabled(boolean reminderEnabled) { this.reminderEnabled = reminderEnabled; }

    public long getReminderTime() { return reminderTime; }
    public void setReminderTime(long reminderTime) { this.reminderTime = reminderTime; }

    public int getStreak() { return streak; }
    public void setStreak(int streak) { this.streak = streak; }

    public int getBestStreak() { return bestStreak; }
    public void setBestStreak(int bestStreak) { this.bestStreak = bestStreak; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public boolean isArchived() { return isArchived; }
    public void setArchived(boolean archived) { isArchived = archived; }

    public Date getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Date archivedAt) { this.archivedAt = archivedAt; }

    public boolean isTodayCompleted() { return todayCompleted; }
    public void setTodayCompleted(boolean todayCompleted) { this.todayCompleted = todayCompleted; }

    public int getTodayCount() { return todayCount; }
    public void setTodayCount(int todayCount) { this.todayCount = todayCount; }

    // ===== Cloud sync accessors =====
    public String getLocalId() { ensureLocalId(); return localId; }
    public void setLocalId(String localId) { this.localId = localId; }
    /** True only if a stable sync key is already persisted (don't regenerate). */
    public boolean hasLocalId() { return localId != null; }

    /** Guarantee a stable, globally-unique sync key exists. */
    private void ensureLocalId() {
        if (localId == null) localId = java.util.UUID.randomUUID().toString();
    }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** Bump updatedAt to now. Call before any mutation to keep last-write-wins correct. */
    public void touch() { this.updatedAt = System.currentTimeMillis(); }

    /** Serialize for Firestore. localId is used as the document id. */
    public java.util.Map<String, Object> toMap() {        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("localId", getLocalId());
        m.put("name", name);
        m.put("description", description);
        m.put("iconName", iconName);
        m.put("colorHex", colorHex);
        m.put("frequency", frequency);
        m.put("targetCount", targetCount);
        m.put("reminderEnabled", reminderEnabled);
        m.put("reminderTime", reminderTime);
        m.put("streak", streak);
        m.put("bestStreak", bestStreak);
        m.put("createdAt", createdAt != null ? createdAt.getTime() : null);
        m.put("isArchived", isArchived);
        m.put("archivedAt", archivedAt != null ? archivedAt.getTime() : null);
        m.put("updatedAt", updatedAt);
        return m;
    }

    /** Fill fields from a Firestore document map (Long dates -> Date). */
    public void applyFromMap(java.util.Map<String, Object> m) {
        if (m == null) return;
        Object lid = m.get("localId");
        if (lid != null) this.localId = String.valueOf(lid); // accept int (old server) or string
        if (m.get("name") != null) this.name = (String) m.get("name");
        if (m.get("description") != null) this.description = (String) m.get("description");
        if (m.get("iconName") != null) this.iconName = (String) m.get("iconName");
        if (m.get("colorHex") != null) this.colorHex = (String) m.get("colorHex");
        if (m.get("frequency") != null) this.frequency = (String) m.get("frequency");
        if (m.get("targetCount") instanceof Number) this.targetCount = ((Number) m.get("targetCount")).intValue();
        if (m.get("reminderEnabled") instanceof Boolean) this.reminderEnabled = (Boolean) m.get("reminderEnabled");
        if (m.get("reminderTime") instanceof Number) this.reminderTime = ((Number) m.get("reminderTime")).longValue();
        if (m.get("streak") instanceof Number) this.streak = ((Number) m.get("streak")).intValue();
        if (m.get("bestStreak") instanceof Number) this.bestStreak = ((Number) m.get("bestStreak")).intValue();
        if (m.get("createdAt") instanceof Number) this.createdAt = new Date(((Number) m.get("createdAt")).longValue());
        if (m.get("isArchived") instanceof Boolean) this.isArchived = (Boolean) m.get("isArchived");
        if (m.get("archivedAt") instanceof Number) this.archivedAt = new Date(((Number) m.get("archivedAt")).longValue());
        if (m.get("updatedAt") instanceof Number) this.updatedAt = ((Number) m.get("updatedAt")).longValue();
    }
}
