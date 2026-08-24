package com.fouu.habitflow.data.repo;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.db.HabitDao;
import com.fouu.habitflow.data.db.HabitLogDao;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.model.HabitLog;
import com.fouu.habitflow.data.remote.SyncManager;
import com.fouu.habitflow.util.FrequencyUtil;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for Habit-related data operations.
 *
 * Acts as a clean API layer between ViewModels and data sources (Room + Remote).
 * Uses AsyncTask for simplicity (in production, consider RxJava or Coroutines).
 */
public class HabitRepository {

    private final Context appContext;
    private final HabitDao habitDao;
    private final HabitLogDao habitLogDao;
    private final SyncManager sync;
    private final AchievementRepository achievementRepo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Bumped on every explicit refresh() so the habit list re-queries even when Room's
    // in-process invalidation doesn't fire (e.g. another process — the home-screen widget's
    // launcher process — wrote to the DB; Room's InvalidationTracker is per-process only).
    // STATIC: shared across all HabitRepository instances AND the Analytics ViewModel, so a
    // habit toggle (which writes on a background thread) can signal the analytics page to
    // re-read AFTER the write commits — without this, the analytics read (on its own thread)
    // could race ahead of the write and show a stale value until the next tab switch.
    private static final MutableLiveData<Long> refreshSignal = new MutableLiveData<>(0L);

    // Preset accent colors (mirror habit_* in colors.xml). Assigned automatically to
    // new habits so their chips are not all the same purple fallback.
    private static final String[] HABIT_COLORS = {
            "#1976D2", // habit_blue
            "#388E3C", // habit_green
            "#F57C00", // habit_orange
            "#D32F2F", // habit_red
            "#00897B", // habit_teal
            "#C2185B", // habit_pink
            "#303F9F", // habit_indigo
            "#6750A4", // habit_purple
    };

    private String pickColor(int index) {
        return HABIT_COLORS[index % HABIT_COLORS.length];
    }

    public HabitRepository(Application application) {
        this.appContext = application.getApplicationContext();
        AppDatabase db = AppDatabase.getInstance(application);
        this.habitDao = db.habitDao();
        this.habitLogDao = db.habitLogDao();
        this.sync = SyncManager.getInstance(application);
        this.achievementRepo = new AchievementRepository(application);
    }

    // ===== Habit CRUD =====
    public LiveData<List<Habit>> getAllActiveHabits() {
        return habitDao.getAllActiveHabits();
    }

    /**
     * Returns active habits with today's completion status attached.
     * Wraps the Room LiveData so the UI checkbox reflects the DB, not memory.
     */
    public LiveData<List<Habit>> getActiveHabitsWithToday() {
        LiveData<List<Habit>> habitsSrc = habitDao.getAllActiveHabits();
        LiveData<List<HabitLog>> logsSrc = habitLogDao.getAllLogsLive();
        androidx.lifecycle.MediatorLiveData<List<Habit>> result =
                new androidx.lifecycle.MediatorLiveData<>();
        // Recompute whenever EITHER the habit list OR the log table changes. The log-table
        // source is what makes the in-app list refresh after a widget/other-process toggle
        // (which writes habit_logs directly, bypassing this repository's toggle path).
        androidx.lifecycle.Observer<List<Habit>> habitsObserver = list -> recompute(result, habitsSrc, executor, habitLogDao);
        androidx.lifecycle.Observer<List<HabitLog>> logsObserver = logs -> recompute(result, habitsSrc, executor, habitLogDao);
        androidx.lifecycle.Observer<Long> refreshObserver = sig -> recompute(result, habitsSrc, executor, habitLogDao);
        result.addSource(habitsSrc, habitsObserver);
        result.addSource(logsSrc, logsObserver);
        // refreshSignal lets an explicit refresh() (e.g. triggered by a cross-process widget
        // toggle broadcast) force a re-query even when Room's per-process invalidation is silent.
        result.addSource(refreshSignal, refreshObserver);
        return result;
    }

    /** Force the habit list to re-query immediately (used after a cross-process change). */
    public void triggerRefresh() {
        refreshSignal.setValue(System.currentTimeMillis());
    }

    /** Global signal fired after any local habit/log write commits. The Analytics page
     *  observes this to re-read the DB (guaranteed to happen AFTER the write, so it always
     *  sees the fresh value). Static because it is shared process-wide. */
    public static LiveData<Long> getRefreshSignal() {
        return refreshSignal;
    }

    /** Recompute today's completion for every habit and push a fresh list to `result`.
     *  Shared by both MediatorLiveData sources (habits table + logs table) so a change in
     *  EITHER table triggers a refresh of the in-app habit list. */
    private void recompute(
            androidx.lifecycle.MediatorLiveData<List<Habit>> result,
            LiveData<List<Habit>> habitsSrc,
            java.util.concurrent.Executor executor,
            HabitLogDao habitLogDao) {
        List<Habit> list = habitsSrc.getValue();
        if (list == null) { result.setValue(null); return; }
        executor.execute(() -> {
            // Recompute "today" on every emission: the cached value would go stale after
            // midnight and mark the new day as already completed.
            Date today = normalizeToMidnight(new Date());
            // Emit a fresh ArrayList so ListAdapter/DiffUtil always sees a different list
            // instance than the one it currently holds (submitList() no-ops on identity).
            java.util.List<Habit> out = new java.util.ArrayList<>(list.size());
            for (Habit h : list) {
                HabitLog log = habitLogDao.getLogByDate(h.getId(), today);
                h.setTodayCompleted(log != null && log.isCompleted());
                h.setTodayCount(log != null ? log.getCount() : 0);
                out.add(h);
            }
            result.postValue(out);
        });
    }

    /**
     * Overall streak = consecutive days (ending today or yesterday) on which the
     * user checked in at least one habit.
     */
    public int getOverallStreak() {
        Date today = normalizeToMidnight(new Date());
        Calendar cal = Calendar.getInstance();
        cal.setTime(today);

        // If nothing done today, count from yesterday.
        if (!hasAnyCompletion(today)) {
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        int streak = 0;
        while (hasAnyCompletion(cal.getTime())) {
            streak++;
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return streak;
    }

    private boolean hasAnyCompletion(Date day) {
        List<HabitLog> logs = habitLogDao.getAllLogsByDate(day);
        for (HabitLog l : logs) {
            if (l.isCompleted()) return true;
        }
        return false;
    }

    public void insertHabit(Habit habit, OnHabitInsertedListener listener) {
        executor.execute(() -> {
            // Ensure a stable, globally-unique sync key exists before persisting,
            // so the Firestore document id is fixed (no duplicate docs on push).
            if (habit.getLocalId() == null) {
                habit.setLocalId(java.util.UUID.randomUUID().toString());
            }
            // Give every new habit a distinct accent color so its chips aren't all purple.
            if (habit.getColorHex() == null || habit.getColorHex().trim().isEmpty()) {
                habit.setColorHex(pickColor(habitDao.getActiveHabitCount()));
            }
            long id = habitDao.insert(habit);
            sync.notifyHabitChanged();
            // A new habit (or deleting one) affects the "Habit Collector" achievement.
            achievementRepo.refreshProgress();
            if (listener != null) listener.onInserted((int) id);
        });
    }

    public void updateHabit(Habit habit) {
        executor.execute(() -> {
            habitDao.update(habit);
            sync.notifyHabitChanged();
        });
    }

    /**
     * Save only the fields the edit dialog owns (name / desc / frequency / color / reminder).
     *
     * Why not {@link #updateHabit(Habit)}: the dialog used to mutate the very Habit instance
     * held by the RecyclerView list, and a full-row @Update also wrote back a possibly stale
     * streak. Writing column-by-column from a detached copy keeps the streak recomputation
     * (background executor) and the user's edit from overwriting each other, which was making
     * frequency changes (DAILY / WEEKDAYS / WEEKLY) silently fail some of the time.
     */
    public void updateHabitEditableFields(Habit habit) {
        executor.execute(() -> {
            habitDao.updateEditableFields(
                    habit.getId(),
                    habit.getName(),
                    habit.getDescription(),
                    habit.getFrequency(),
                    habit.getTargetCount(),
                    habit.getColorHex(),
                    habit.isReminderEnabled(),
                    habit.getReminderTime(),
                    System.currentTimeMillis());
            // Frequency drives the streak rule, so recompute right after the write.
            Habit fresh = habitDao.getHabitByIdSync(habit.getId());
            if (fresh != null) recomputeStreakIfChanged(fresh);
            sync.notifyHabitChanged();
        });
    }

    public void deleteHabit(Habit habit) {
        executor.execute(() -> {
            // Soft delete: keep the row so the habit's check-in history survives (analytics,
            // charts, lifetime rate). Flip is_archived and stamp archived_at; the habit then
            // drops out of every is_archived = 0 query (list, widget, today rate) but its
            // past logs are still counted up to the deletion day.
            long now = System.currentTimeMillis();
            habit.setArchived(true);
            habit.setArchivedAt(new Date(now));
            habitDao.archiveHabit(habit.getId(), now, now);
            sync.notifyHabitChanged();
            // Deleting a habit affects the "Habit Collector" achievement.
            achievementRepo.refreshProgress();
        });
    }

    public int getActiveHabitCountSync() {
        return habitDao.getActiveHabitCount();
    }

    // ===== Habit Logs =====
    public LiveData<List<HabitLog>> getLogsByHabitId(int habitId) {
        return habitLogDao.getLogsByHabitId(habitId);
    }

    /**
     * Toggle habit completion for today.
     * If log exists, flip it; otherwise create new log.
     */
    public void toggleHabitForToday(int habitId, boolean completed) {
        executor.execute(() -> {
            Date today = normalizeToMidnight(new Date());
            HabitLog existing = habitLogDao.getLogByDate(habitId, today);

            if (existing != null) {
                existing.setCompleted(completed);
                habitLogDao.update(existing);
            } else {
                HabitLog log = new HabitLog();
                log.setHabitId(habitId);
                log.setLogDate(today);
                log.setCompleted(completed);
                // Snapshot the habit's frequency so analytics can still compute
                // should-check-in days after this habit is hard-deleted.
                Habit h = habitDao.getHabitByIdSync(habitId);
                log.setFrequency(h != null ? h.getFrequency() : null);
                habitLogDao.insert(log);
            }

            // Update streak
            updateStreak(habitId);

            // Cloud sync: habit streak changed + a log was inserted/updated
            sync.notifyHabitChanged();
            sync.notifyLogChanged();

            // Streak/check-in achievements (Week Warrior, Month Master, ...) unlock as soon
            // as the milestone is reached, not only when the achievements page is opened.
            achievementRepo.refreshProgress();

            // Notify the home-screen widget to re-read the DB and reflect the new toggle.
            // notifyAppWidgetViewDataChanged must run on the main thread.
            final int id = habitId;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    refreshWidgetForHabit(id));

            // Signal any listeners (e.g. the Analytics page) that the DB changed, so they
            // re-read AFTER this write commits. postValue is safe from a background thread.
            refreshSignal.postValue(System.currentTimeMillis());

            // A check-in changes streaks + total completed check-ins, which drive several
            // achievements (Week Warrior, Month Master, Century Club).
            achievementRepo.refreshProgress();
        });
    }

    /**
     * Record one check-in for today (increment the day's completion count, capped at the
     * habit's targetCount). Used by the habit card checkbox; for targetCount>1 each tap
     * adds one repetition.
     */
    public void checkInHabit(int habitId) {
        executor.execute(() -> {
            Date today = normalizeToMidnight(new Date());
            Habit habit = habitDao.getHabitByIdSync(habitId);
            int target = habit != null ? Math.max(1, habit.getTargetCount()) : 1;

            HabitLog existing = habitLogDao.getLogByDate(habitId, today);
            if (existing != null) {
                int newCount = Math.min(existing.getCount() + 1, target);
                existing.setCount(newCount);
                existing.setCompleted(true);
                existing.touch();
                habitLogDao.update(existing);
            } else {
                HabitLog log = new HabitLog();
                log.setHabitId(habitId);
                log.setLogDate(today);
                log.setCompleted(true);
                log.setCount(1);
                log.setFrequency(habit != null ? habit.getFrequency() : null);
                log.touch();
                habitLogDao.insert(log);
            }

            updateStreak(habitId);
            sync.notifyHabitChanged();
            sync.notifyLogChanged();
            achievementRepo.refreshProgress();
            final int id = habitId;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    refreshWidgetForHabit(id));
            refreshSignal.postValue(System.currentTimeMillis());
        });
    }

    /**
     * Undo today's completion: removes the day's log entirely so the habit shows as not
     * done and its streak is recomputed. Used when the user un-checks a habit.
     */
    public void uncheckHabit(int habitId) {
        executor.execute(() -> {
            Date today = normalizeToMidnight(new Date());
            HabitLog existing = habitLogDao.getLogByDate(habitId, today);
            if (existing != null) {
                habitLogDao.delete(existing);
            }
            updateStreak(habitId);
            sync.notifyHabitChanged();
            sync.notifyLogChanged();
            achievementRepo.refreshProgress();
            final int id = habitId;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    refreshWidgetForHabit(id));
            refreshSignal.postValue(System.currentTimeMillis());
        });
    }

    /** Tell any live HabitWidget instances to reload their data after a habit toggle. */
    private void refreshWidgetForHabit(int habitId) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(appContext);
        int[] ids = mgr.getAppWidgetIds(
                new ComponentName(appContext, com.fouu.habitflow.widget.HabitWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            mgr.notifyAppWidgetViewDataChanged(ids, com.fouu.habitflow.R.id.widget_list);
        }
    }

    /**
     * Calculate and update streak for a habit, honouring its frequency:
     *  - DAILY   : consecutive days completed
     *  - WEEKDAYS: consecutive weekdays completed (weekends skipped)
     *  - WEEKLY  : consecutive weeks with at least one completion
     */
    private void updateStreak(int habitId) {
        Habit habit = habitDao.getHabitByIdSync(habitId);
        if (habit == null) return;

        String freq = habit.getFrequency();
        int streak;
        if (FrequencyUtil.WEEKLY.equals(freq)) {
            streak = computeWeeklyStreak(habitId);
        } else {
            streak = computeDailyStreak(habitId, freq);
        }

        habit.setStreak(streak);
        if (streak > habit.getBestStreak()) {
            habit.setBestStreak(streak);
        }
        habitDao.updateStreak(habit.getId(), habit.getStreak(), habit.getBestStreak());
    }

    /**
     * Recompute every active habit's current + best streak from its actual logs and
     * persist only if changed. Keeps the habit-card streak chips consistent with reality
     * (e.g. after a lapse, a new day, or a widget toggle that bypasses toggleHabitForToday).
     * Must be called off the main thread.
     */
    public void recomputeAllStreaks() {
        executor.execute(() -> {
            List<Habit> habits = habitDao.getAllActiveHabitsSync();
            if (habits == null) return;
            int colorIndex = 0;
            for (Habit h : habits) {
                recomputeStreakIfChanged(h);
                // Backfill: habits with no color default to the M3 surface (no tint),
                // via a column-only update so it never clobbers a color the user set.
                if (h.getColorHex() == null || h.getColorHex().trim().isEmpty()) {
                    habitDao.updateColorOnly(h.getId(), com.fouu.habitflow.data.model.Habit.COLOR_DEFAULT);
                }
                colorIndex++;
            }
        });
    }

    private void recomputeStreakIfChanged(Habit h) {
        int newStreak;
        if (FrequencyUtil.WEEKLY.equals(h.getFrequency())) {
            newStreak = computeWeeklyStreak(h.getId());
        } else {
            newStreak = computeDailyStreak(h.getId(), h.getFrequency());
        }
        int newBest = Math.max(h.getBestStreak(), newStreak);
        // Only write when the value actually changed, otherwise the habits LiveData
        // re-emits forever on load (infinite loop via the MediatorLiveData). Use a
        // column-only update so we never touch color_hex (which the user may have just edited).
        if (newStreak != h.getStreak() || newBest != h.getBestStreak()) {
            habitDao.updateStreak(h.getId(), newStreak, newBest);
        }
    }

    /** Consecutive days completed (WEEKDAYS skips weekends). */
    private int computeDailyStreak(int habitId, String freq) {
        int streak = 0;
        Date current = normalizeToMidnight(new Date());

        // If today wasn't completed yet, start counting from the previous target day.
        HabitLog todayLog = habitLogDao.getLogByDate(habitId, current);
        boolean todayDone = todayLog != null && todayLog.isCompleted();
        if (!todayDone) {
            current = previousTargetDay(freq, current);
        }

        while (true) {
            if (!FrequencyUtil.isTargetDay(freq, current)) {
                current = previousTargetDay(freq, current);
                continue;
            }
            HabitLog log = habitLogDao.getLogByDate(habitId, current);
            if (log != null && log.isCompleted()) {
                streak++;
                current = previousTargetDay(freq, current);
            } else {
                break;
            }
        }
        return streak;
    }

    /** Consecutive weeks (Mon–Sun) with at least one completion. */
    private int computeWeeklyStreak(int habitId) {
        int streak = 0;
        Date weekStart = FrequencyUtil.startOfWeek(normalizeToMidnight(new Date()));

        // If this week has no completion yet, start from the previous week.
        if (!weekHasCompletion(habitId, weekStart)) {
            weekStart = FrequencyUtil.addDays(weekStart, -7);
        }

        while (weekHasCompletion(habitId, weekStart)) {
            streak++;
            weekStart = FrequencyUtil.addDays(weekStart, -7);
        }
        return streak;
    }

    private boolean weekHasCompletion(int habitId, Date weekStart) {
        Date weekEnd = FrequencyUtil.addDays(weekStart, 7);
        List<HabitLog> logs = habitLogDao.getLogsByDateRange(habitId, weekStart, weekEnd);
        for (HabitLog l : logs) {
            if (l.isCompleted()) return true;
        }
        return false;
    }

    /** Move to the most recent target day strictly before `day`. */
    private Date previousTargetDay(String freq, Date day) {
        Date prev = FrequencyUtil.addDays(day, -1);
        while (!FrequencyUtil.isTargetDay(freq, prev)) {
            prev = FrequencyUtil.addDays(prev, -1);
        }
        return prev;
    }

    public List<HabitLog> getLogsByDateRange(int habitId, Date start, Date end) {
        return habitLogDao.getLogsByDateRange(habitId, start, end);
    }

    // ===== Utility =====
    private Date normalizeToMidnight(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    // ===== Listener interface =====
    public interface OnHabitInsertedListener {
        void onInserted(int habitId);
    }
}
