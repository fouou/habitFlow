package com.fouu.habitflow.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.fouu.habitflow.data.model.FocusSession;

import java.util.Date;
import java.util.List;

/**
 * Data Access Object for FocusSession entity.
 */
@Dao
public interface FocusSessionDao {

    @Insert
    long insert(FocusSession session);

    @Update
    void update(FocusSession session);

    @Delete
    void delete(FocusSession session);

    @Query("SELECT * FROM focus_sessions WHERE start_time BETWEEN :startDate AND :endDate ORDER BY start_time DESC")
    LiveData<List<FocusSession>> getSessionsByDateRange(Date startDate, Date endDate);

    @Query("SELECT SUM(duration_minutes) FROM focus_sessions WHERE start_time >= :startDate AND is_completed = 1 AND session_type = 'FOCUS'")
    Integer getTotalFocusMinutesSince(Date startDate);

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE start_time >= :startDate AND is_completed = 1 AND session_type = 'FOCUS'")
    int getCompletedSessionCountSince(Date startDate);

    @Query("SELECT * FROM focus_sessions WHERE client_id = :clientId LIMIT 1")
    FocusSession getByClientId(String clientId);

    @Query("SELECT * FROM focus_sessions")
    List<FocusSession> getAllSync();
}
