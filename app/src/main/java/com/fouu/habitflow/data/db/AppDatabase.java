package com.fouu.habitflow.data.db;

import android.content.Context;
import android.util.Log;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.fouu.habitflow.data.db.converter.DateConverter;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.model.HabitLog;
import com.fouu.habitflow.data.model.FocusSession;
import com.fouu.habitflow.data.model.Achievement;

/**
 * Room Database for HabitFlow
 *
 * Entities:
 * - Habit: User-defined habits (name, icon, color, frequency, reminder)
 * - HabitLog: Daily check-in records for each habit
 * - FocusSession: Pomodoro/Deep work sessions
 * - Achievement: Gamification badges
 *
 * Version: 5 (schema changed: added frequency snapshot column to habit_logs, so analytics
 * can compute "should-have-checked-in" days from the log alone after a habit is hard-deleted)
 */
@Database(entities = {Habit.class, HabitLog.class, FocusSession.class, Achievement.class}, version = 6, exportSchema = false)
@TypeConverters({DateConverter.class})
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG = "AppDatabase";
    private static final String DB_NAME = "habitflow.db";
    private static volatile AppDatabase instance;

    /** v5 → v6: add archived_at column for soft-deleted habits (deletion timestamp). */
    private static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE habits ADD COLUMN archived_at INTEGER");
        }
    };

    // DAOs
    public abstract HabitDao habitDao();
    public abstract HabitLogDao habitLogDao();
    public abstract FocusSessionDao focusSessionDao();
    public abstract AchievementDao achievementDao();

    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    Log.d(TAG, "Creating database instance");
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DB_NAME
                            )
                            .addMigrations(MIGRATION_5_6)
                            .fallbackToDestructiveMigrationOnDowngrade() // only wipe on version downgrade
                            .fallbackToDestructiveMigration() // wipe & recreate on version upgrade during dev
                            .build();
                }
            }
        }
        return instance;
    }
}
