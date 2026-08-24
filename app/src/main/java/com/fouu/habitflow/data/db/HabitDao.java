package com.fouu.habitflow.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.fouu.habitflow.data.model.Habit;

import java.util.List;

/**
 * Data Access Object for Habit entity.
 * Provides reactive queries via LiveData for UI observation.
 */
@Dao
public interface HabitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Habit habit);

    @Update
    void update(Habit habit);

    @Delete
    void delete(Habit habit);

    @Query("SELECT * FROM habits WHERE is_archived = 0 ORDER BY created_at DESC")
    LiveData<List<Habit>> getAllActiveHabits();

    @Query("SELECT * FROM habits WHERE is_archived = 0 ORDER BY created_at DESC")
    List<Habit> getAllActiveHabitsSync();

    @Query("SELECT * FROM habits ORDER BY created_at DESC")
    List<Habit> getAllHabitsIncludingArchivedSync();

    @Query("SELECT * FROM habits WHERE id = :habitId")
    LiveData<Habit> getHabitById(int habitId);

    @Query("SELECT * FROM habits WHERE id = :habitId")
    Habit getHabitByIdSync(int habitId);

    @Query("SELECT * FROM habits WHERE local_id = :localId LIMIT 1")
    Habit getHabitByLocalIdSync(String localId);

    @Query("SELECT COUNT(*) FROM habits WHERE is_archived = 0")
    int getActiveHabitCount();

    /** Soft-delete: flip is_archived on and stamp the deletion time, without dropping the
     *  row. Keeps the habit's history (logs + analytics) intact. */
    @Query("UPDATE habits SET is_archived = 1, archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :habitId")
    void archiveHabit(int habitId, long archivedAt, long updatedAt);

    @Query("UPDATE habits SET streak = :streak, best_streak = :bestStreak WHERE id = :habitId")
    void updateStreak(int habitId, int streak, int bestStreak);

    @Query("UPDATE habits SET color_hex = :colorHex WHERE id = :habitId")
    void updateColorOnly(int habitId, String colorHex);

    /**
     * Column-only update for everything the edit dialog can change.
     * Deliberately does NOT touch streak / best_streak / created_at / local_id,
     * so it can never clobber values recomputed on the background executor.
     */
    @Query("UPDATE habits SET name = :name, description = :description, frequency = :frequency, "
            + "target_count = :targetCount, color_hex = :colorHex, reminder_enabled = :reminderEnabled, "
            + "reminder_time = :reminderTime, updated_at = :updatedAt WHERE id = :habitId")
    void updateEditableFields(int habitId, String name, String description, String frequency,
                              int targetCount, String colorHex, boolean reminderEnabled,
                              long reminderTime, long updatedAt);

    @Query("SELECT * FROM habits WHERE reminder_enabled = 1 AND is_archived = 0")
    List<Habit> getHabitsWithReminders();
}
