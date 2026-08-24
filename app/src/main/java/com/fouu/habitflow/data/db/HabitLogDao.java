package com.fouu.habitflow.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.fouu.habitflow.data.model.HabitLog;

import java.util.Date;
import java.util.List;

/**
 * Data Access Object for HabitLog entity.
 * Handles daily check-ins and streak calculations.
 */
@Dao
public interface HabitLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(HabitLog log);

    @Update
    void update(HabitLog log);

    @Delete
    void delete(HabitLog log);

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId ORDER BY log_date DESC")
    LiveData<List<HabitLog>> getLogsByHabitId(int habitId);

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId AND log_date BETWEEN :startDate AND :endDate ORDER BY log_date ASC")
    List<HabitLog> getLogsByDateRange(int habitId, Date startDate, Date endDate);

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId AND log_date = :date LIMIT 1")
    HabitLog getLogByDate(int habitId, Date date);

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId " +
           "AND log_date >= :startDate AND log_date < :endDate LIMIT 1")
    HabitLog getLogByDayRange(int habitId, Date startDate, Date endDate);

    @Query("SELECT COUNT(*) FROM habit_logs WHERE habit_id = :habitId AND is_completed = 1 AND log_date >= :startDate")
    int getCompletedCountSince(int habitId, Date startDate);

    @Query("SELECT * FROM habit_logs WHERE log_date = :date")
    List<HabitLog> getAllLogsByDate(Date date);

    @Query("SELECT * FROM habit_logs WHERE local_id = :localId LIMIT 1")
    HabitLog getLogByLocalId(String localId);

    @Query("SELECT * FROM habit_logs")
    List<HabitLog> getAllSync();

    @Query("SELECT COUNT(*) FROM habit_logs WHERE is_completed = 1")
    int getCompletedLogCount();

    @Query("SELECT * FROM habit_logs")
    LiveData<List<HabitLog>> getAllLogsLive();
}
