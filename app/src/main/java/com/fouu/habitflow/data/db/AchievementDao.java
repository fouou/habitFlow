package com.fouu.habitflow.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import com.fouu.habitflow.data.model.Achievement;

import java.util.List;

/**
 * Data Access Object for Achievement entity.
 */
@Dao
public interface AchievementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(Achievement achievement);

    @Update
    void update(Achievement achievement);

    @Query("DELETE FROM achievements WHERE title = :title")
    void deleteByTitle(String title);

    @Query("SELECT * FROM achievements ORDER BY is_unlocked DESC, progress DESC")
    LiveData<List<Achievement>> getAllAchievements();

    @Query("SELECT * FROM achievements WHERE is_unlocked = 0 AND progress >= target_value LIMIT 1")
    Achievement getReadyToUnlock();

    @Query("SELECT COUNT(*) FROM achievements WHERE is_unlocked = 1")
    int getUnlockedCount();

    @Query("SELECT COUNT(*) FROM achievements")
    int getCount();

    @Query("SELECT * FROM achievements WHERE local_id = :localId LIMIT 1")
    Achievement getByLocalId(String localId);

    @Query("SELECT * FROM achievements")
    List<Achievement> getAllSync();
}
