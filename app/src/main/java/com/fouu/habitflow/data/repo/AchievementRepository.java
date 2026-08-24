package com.fouu.habitflow.data.repo;

import android.app.Application;
import android.content.Context;

import androidx.lifecycle.LiveData;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.db.AchievementDao;
import com.fouu.habitflow.data.model.Achievement;
import com.fouu.habitflow.data.remote.SyncManager;
import com.fouu.habitflow.util.NotificationHelper;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for Achievement/gamification data.
 *
 * Owns the gamification logic: it computes each achievement's current progress
 * from real user metrics (streaks, check-ins, focus sessions, habit count) and
 * unlocks + notifies when a target is met. Call {@link #refreshProgress()} after
 * any habit/log/focus write (and when the achievements page is opened) so the
 * badges stay in sync.
 */
public class AchievementRepository {

    private final Context appContext;
    private final AchievementDao achievementDao;
    private final SyncManager sync;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AchievementRepository(Application application) {
        this.appContext = application.getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(application);
        this.achievementDao = db.achievementDao();
        this.sync = SyncManager.getInstance(application);
    }

    public LiveData<List<Achievement>> getAllAchievements() {
        return achievementDao.getAllAchievements();
    }

    public void insertAchievement(Achievement achievement) {
        executor.execute(() -> {
            achievementDao.insert(achievement);
            sync.notifyAchievementChanged();
        });
    }

    public void updateAchievement(Achievement achievement) {
        executor.execute(() -> {
            achievementDao.update(achievement);
            sync.notifyAchievementChanged();
        });
    }

    public void seedDefaultAchievements() {
        executor.execute(() -> {
            // Remove the retired "First Step" achievement from any device that already has
            // it (it was the original top item; the user asked to drop it). Idempotent — safe
            // to call on every launch even after it's already gone.
            achievementDao.deleteByTitle("First Step");

            // Check if already seeded. Use the TOTAL row count, not getUnlockedCount():
            // the default achievements are all still locked (is_unlocked = 0), so the
            // unlocked count is 0 and would never prevent re-seeding → duplicate rows on
            // every app launch.
            if (achievementDao.getCount() == 0) {
                Achievement[] defaults = {
                        createAchievement("Week Warrior", "Achieve a 7-day streak", "ic_fire", 7),
                        createAchievement("Month Master", "Achieve a 30-day streak", "ic_crown", 30),
                        createAchievement("Century Club", "Achieve a 100-day streak", "ic_diamond", 100),
                        createAchievement("Focus Novice", "Complete 5 focus sessions", "ic_timer", 5),
                        createAchievement("Focus Master", "Complete 50 focus sessions", "ic_brain", 50),
                        createAchievement("Habit Collector", "Create 3 different habits", "ic_star", 3),
                };

                for (Achievement a : defaults) {
                    achievementDao.insert(a);
                }
                sync.notifyAchievementChanged();
            } else {
                // Migration: "Habit Collector" target was lowered 10 → 3 to match the free
                // 3-habit cap, so existing installs need their old row updated (idempotent).
                List<Achievement> all = achievementDao.getAllSync();
                if (all != null) {
                    for (Achievement a : all) {
                        if ("Habit Collector".equals(a.getTitle()) && a.getTargetValue() != 3) {
                            a.setTargetValue(3);
                            a.touch();
                            achievementDao.update(a);
                        }
                    }
                }
            }
        });
    }

    /**
     * Recompute every achievement's progress from live user metrics, persist it,
     * then unlock + notify any that just reached their target. Safe to call from
     * any thread — the DB work runs on this repository's single-thread executor.
     */
    public void refreshProgress() {
        executor.execute(this::refreshProgressSync);
    }

    private void refreshProgressSync() {
        AppDatabase db = AppDatabase.getInstance(appContext);

        // ===== Collect the live metrics used by the achievements =====
        // Overall streak (consecutive days with >=1 check-in) — drives the streak badges.
        int overallStreak = new HabitRepository((Application) appContext.getApplicationContext())
                .getOverallStreak();
        // Total completed focus sessions across all time.
        int focusSessions = db.focusSessionDao().getCompletedSessionCountSince(new Date(0));
        // Number of currently active habits (Habit Collector).
        int activeHabits = db.habitDao().getActiveHabitCount();

        List<Achievement> all = achievementDao.getAllSync();
        if (all == null) return;

        // ===== Push the freshly computed progress into each achievement =====
        for (Achievement a : all) {
            int metric;
            switch (a.getTitle()) {
                case "Week Warrior":     metric = overallStreak;   break;
                case "Month Master":     metric = overallStreak;   break;
                case "Century Club":     metric = overallStreak;   break;
                case "Focus Novice":     metric = focusSessions;   break;
                case "Focus Master":     metric = focusSessions;   break;
                case "Habit Collector":  metric = activeHabits;    break;
                default:                 metric = 0;              break;
            }
            int progress = Math.min(metric, a.getTargetValue());
            boolean reached = metric >= a.getTargetValue();

            // Never downgrade an already-unlocked badge.
            if (!a.isUnlocked()) {
                a.setProgress(progress);
                a.touch();
                achievementDao.update(a);
            }
        }
        sync.notifyAchievementChanged();

        // ===== Unlock + reward + celebrate anything that just met its target =====
        while (true) {
            Achievement ready = achievementDao.getReadyToUnlock();
            if (ready == null) break;
            ready.setUnlocked(true);
            ready.setUnlockedAt(new Date());
            ready.setProgress(ready.getTargetValue());
            ready.touch();
            achievementDao.update(ready);
            sync.notifyAchievementChanged();

            // Reward: grant premium membership days (if this achievement carries one).
            int rewardDays = com.fouu.habitflow.util.AchievementLocalizer.rewardDays(ready.getTitle());
            if (rewardDays > 0) {
                com.fouu.habitflow.util.PreferenceManager.getInstance(appContext).grantPremiumDays(rewardDays);
            }

            showUnlockNotification(ready);
            celebrateAchievement(ready, rewardDays);
        }
    }

    /** Confetti + toast for an unlocked achievement; runs on the main thread. */
    private void celebrateAchievement(Achievement a, int rewardDays) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            com.fouu.habitflow.ui.main.MainActivity.fireConfettiIfVisible();
            String title = com.fouu.habitflow.util.AchievementLocalizer.title(appContext, a.getTitle());
            String text = rewardDays > 0
                    ? appContext.getString(com.fouu.habitflow.R.string.achievement_reward_msg, title, rewardDays)
                    : appContext.getString(com.fouu.habitflow.R.string.achievement_unlocked_msg, title);
            android.widget.Toast.makeText(appContext, text, android.widget.Toast.LENGTH_LONG).show();
        });
    }

    /** Fire a high-importance notification the moment an achievement is unlocked. */
    private void showUnlockNotification(Achievement a) {
        String title = appContext.getString(com.fouu.habitflow.R.string.achievement_unlocked_title);
        String localized = com.fouu.habitflow.util.AchievementLocalizer.title(appContext, a.getTitle());
        int rewardDays = com.fouu.habitflow.util.AchievementLocalizer.rewardDays(a.getTitle());
        String msg = rewardDays > 0
                ? appContext.getString(com.fouu.habitflow.R.string.achievement_reward_msg, localized, rewardDays)
                : appContext.getString(com.fouu.habitflow.R.string.achievement_unlocked_msg, localized);
        int id = (a.getId() != 0 ? a.getId() : (int) System.currentTimeMillis()) + 7000;
        new NotificationHelper(appContext).showNotification(
                id, title, msg, NotificationHelper.CHANNEL_ACHIEVEMENT);
    }

    private Achievement createAchievement(String title, String desc, String icon, int target) {
        Achievement a = new Achievement();
        a.setTitle(title);
        a.setDescription(desc);
        a.setIconName(icon);
        a.setTargetValue(target);
        return a;
    }
}
