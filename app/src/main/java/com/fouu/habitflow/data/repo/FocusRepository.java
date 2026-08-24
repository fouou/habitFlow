package com.fouu.habitflow.data.repo;

import android.app.Application;

import androidx.lifecycle.LiveData;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.db.FocusSessionDao;
import com.fouu.habitflow.data.model.FocusSession;
import com.fouu.habitflow.data.remote.SyncManager;
import com.fouu.habitflow.util.DateUtil;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for Focus/Pomodoro session data.
 */
public class FocusRepository {

    private final FocusSessionDao sessionDao;
    private final SyncManager sync;
    private final AchievementRepository achievementRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FocusRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        this.sessionDao = db.focusSessionDao();
        this.sync = SyncManager.getInstance(application);
        this.achievementRepo = new AchievementRepository(application);
    }

    public LiveData<List<FocusSession>> getThisWeekSessions() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        Date startOfWeek = cal.getTime();
        Date now = new Date();
        return sessionDao.getSessionsByDateRange(startOfWeek, now);
    }

    public void insertSession(FocusSession session) {
        executor.execute(() -> {
            sessionDao.insert(session);
            sync.notifyFocusChanged();
            // A completed focus session drives the Focus Novice / Focus Master achievements.
            achievementRepo.refreshProgress();
        });
    }

    public void updateSession(FocusSession session) {
        executor.execute(() -> {
            sessionDao.update(session);
            sync.notifyFocusChanged();
        });
    }

    public int getTotalFocusMinutesThisWeek() {
        // Note: This returns 0 synchronously; use LiveData for reactive UI
        // This is a simplified version - in production use AsyncTask or coroutines
        return 0;
    }

    // ===== Aggregate stats (run on a background thread) =====

    /** Completed focus minutes accumulated today (start of day). */
    public int getTodayFocusMinutes() {
        Integer v = sessionDao.getTotalFocusMinutesSince(DateUtil.getDaysAgo(0));
        return v != null ? v : 0; // SUM() returns null when no rows match
    }

    /** Completed focus session count for today (start of day). */
    public int getTodayCompletedSessionCount() {
        return sessionDao.getCompletedSessionCountSince(DateUtil.getDaysAgo(0));
    }

    /** Completed focus minutes across all time. */
    public int getTotalFocusMinutesAllTime() {
        Integer v = sessionDao.getTotalFocusMinutesSince(new Date(0));
        return v != null ? v : 0; // SUM() returns null when no rows match
    }
}
