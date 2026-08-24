package com.fouu.habitflow.ui.analytics;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.model.HabitLog;
import com.fouu.habitflow.data.remote.ApiService;
import com.fouu.habitflow.util.DateUtil;
import com.fouu.habitflow.util.FrequencyUtil;
import com.fouu.habitflow.util.PreferenceManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AnalyticsViewModel - provides chart data and AI insights.
 */
public class AnalyticsViewModel extends AndroidViewModel {

    private final MutableLiveData<List<WeeklyDataPoint>> weeklyData = new MutableLiveData<>();
    private final MutableLiveData<Integer> totalHabits = new MutableLiveData<>(0);
    private final MutableLiveData<Float> todayCompletionRate = new MutableLiveData<>(0f);
    private final MutableLiveData<Float> overallCompletionRate = new MutableLiveData<>(0f);
    // Serial executor so concurrent refresh() calls never race / post out of order.
    private final java.util.concurrent.ExecutorService loadExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final MutableLiveData<Integer> currentStreak = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> bestStreak = new MutableLiveData<>(0);
    private final MutableLiveData<String> aiInsights = new MutableLiveData<>();
    private volatile boolean generating = false; // prevent concurrent/duplicate Gemini calls
    private volatile boolean loaded = false;     // becomes true after the first loadData() finishes
    // One-shot callback fired on the MAIN thread after each loadData() completes. The Fragment
    // registers it in onViewCreated and paints directly from the passed snapshot. This is fully
    // deterministic: unlike observing LiveData (which has sticky-replay / observer-registration
    // timing quirks that, under NavController's Fragment replace-on-tab-switch, made the bar show
    // the previous screen's value until a second tab-in), the callback always paints the LATEST
    // computed snapshot the moment it is ready — no "one beat behind" lag.
    private volatile OnLoadedListener onLoadedListener;
    private final ApiService apiService;
    private final AppDatabase db;
    private final PreferenceManager prefs;

    // Chart view mode + the anchor date the user picked (defaults to today).
    // WEEK shows the natural week (Mon–Sun) containing anchorDate;
    // MONTH shows the whole calendar month containing anchorDate.
    public static final int MODE_WEEK = 1;
    public static final int MODE_MONTH = 2;
    private volatile int viewMode = MODE_WEEK;
    private volatile long anchorMillis = DateUtil.getDaysAgo(0).getTime();
    // Re-read whenever ANY habit/log write commits (fired from HabitRepository after the DB
    // write finishes). Because it fires strictly AFTER the write, loadData() here always reads
    // the fresh value — eliminating the cross-thread race where the analytics read could
    // complete before the habit toggle's write did, leaving a stale value on screen.
    private final androidx.lifecycle.Observer<Long> refreshObserver = v -> loadData();

    public AnalyticsViewModel(@NonNull Application app) {
        super(app);
        this.db = AppDatabase.getInstance(app);
        this.apiService = ApiService.getInstance(app);
        this.prefs = PreferenceManager.getInstance(app);
        // Seed with the cached insight (if any) so the card shows instantly without
        // re-hitting the Gemini API on every screen open.
        String cached = prefs.getCachedAiInsight();
        if (cached != null) aiInsights.setValue(cached);
        // Observe the shared refresh signal for the lifetime of this ViewModel.
        com.fouu.habitflow.data.repo.HabitRepository.getRefreshSignal().observeForever(refreshObserver);
        loadData();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        com.fouu.habitflow.data.repo.HabitRepository.getRefreshSignal().removeObserver(refreshObserver);
    }

    /**
     * Recompute all stats from the database. Called on creation and whenever the
     * user returns to the analytics tab (via {@link #refresh()}), so habit
     * additions / deletions / check-offs are reflected immediately.
     */
    public void refresh() {
        loadData();
    }

    /** A plain snapshot of every stat, computed synchronously on the caller's thread.
     *  Used by the UI to paint immediately on tab resume (no one-frame-late postValue
     *  delay that made the progress bar look "one beat behind" the habit page). */
    public static class AnalyticsSnapshot {
        public final int totalHabits;
        public final List<WeeklyDataPoint> weekly;
        public final float todayRate;
        public final float overallRate;
        public final int currentStreak;
        public final int bestStreak;
        AnalyticsSnapshot(int t, List<WeeklyDataPoint> w, float today, float r, int c, int b) {
            totalHabits = t; weekly = w; todayRate = today; overallRate = r; currentStreak = c; bestStreak = b;
        }
    }

    public AnalyticsSnapshot computeSync() {
        List<Habit> activeHabits = db.habitDao().getAllActiveHabitsSync();
        // All habits incl. soft-deleted ones — used for lifetime rate & chart history so a
        // deleted habit's past check-ins still count (window ends at its archived_at).
        List<Habit> allHabits = db.habitDao().getAllHabitsIncludingArchivedSync();

        // Load every log once and index by (habitId, dayKey) so the per-day / per-habit
        // loops below are O(1) lookups instead of one DB round-trip per day.
        List<HabitLog> allLogs = db.habitLogDao().getAllSync();
        java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay = new java.util.HashMap<>();
        for (HabitLog l : allLogs) {
            if (l.getLogDate() == null) continue;
            Calendar c = Calendar.getInstance();
            c.setTime(l.getLogDate());
            com.fouu.habitflow.util.FrequencyUtil.zeroTime(c);
            long key = c.getTimeInMillis();
            logsByHabitDay
                    .computeIfAbsent(l.getHabitId(), k -> new java.util.HashMap<>())
                    .put(key, l);
        }

        Date today = DateUtil.getDaysAgo(0);
        Calendar cToday = Calendar.getInstance();
        cToday.setTime(today);
        com.fouu.habitflow.util.FrequencyUtil.zeroTime(cToday);
        long todayKey = cToday.getTimeInMillis();

        // ===== Today's completion rate =====
        // Denominator = active habits whose frequency targets TODAY (independent of whether
        // they've been checked in). Numerator = those with a completed log for today.
        // Fixes the old "only counts habits that have a log today" bug that made the rate
        // 100% whenever every checked-in habit happened to be done.
        int todayTarget = 0;
        int todayDone = 0;
        for (Habit h : activeHabits) {
            String freq = h.getFrequency() != null ? h.getFrequency() : "DAILY";
            if (!com.fouu.habitflow.util.FrequencyUtil.isTargetDay(freq, today)) continue;
            todayTarget++;
            java.util.Map<Long, HabitLog> dayMap = logsByHabitDay.get(h.getId());
            if (dayMap != null) {
                HabitLog log = dayMap.get(todayKey);
                if (log != null && log.isCompleted()) todayDone++;
            }
        }
        float todayRate = todayTarget > 0 ? (float) todayDone / todayTarget : 0f;

        // ===== Lifetime cumulative completion rate =====
        // For each active habit, "should-check" days = every day from its created_at up to
        // today on which its frequency targets a check-in. "done" days = those with a
        // completed log. Previously this was derived from the log span alone, which skipped
        // every day with no log (i.e. every missed day) and inflated the rate toward 100%.
        int targetDaysTotal = 0;
        int completedTargetLogs = 0;
        // Include archived habits so a deleted habit's past check-ins still count toward the
        // lifetime rate. Each archived habit's "should-check" window ends the day BEFORE its
        // archived_at (effectiveEnd), so the deletion day itself is not counted.
        for (Habit h : allHabits) {
            Date end = effectiveEnd(h, today);
            int[] sd = shouldAndDone(h, h.getCreatedAt(), end, logsByHabitDay);
            targetDaysTotal += sd[0];
            completedTargetLogs += sd[1];
        }
        float overall = targetDaysTotal > 0 ? (float) completedTargetLogs / targetDaysTotal : 0f;
        overall = Math.min(1f, overall);

        List<WeeklyDataPoint> points = buildChartPoints(allHabits, logsByHabitDay);

        int overallStreak = computeOverallStreak(today);
        int bestOverall = computeBestOverallStreak();
        return new AnalyticsSnapshot(activeHabits.size(), points, todayRate, overall, overallStreak, bestOverall);
    }

    /** Count (shouldCheckDays, completedDays) for one habit within [start, end] (inclusive,
     *  both normalized to midnight). The habit's created_at bounds the start so days before
     *  the habit existed are not counted. Completion is read from the indexed logs. */
    private int[] shouldAndDone(Habit h, Date start, Date end,
                                java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay) {
        String freq = h.getFrequency() != null ? h.getFrequency() : "DAILY";
        Calendar cStart = Calendar.getInstance();
        cStart.setTime(start);
        com.fouu.habitflow.util.FrequencyUtil.zeroTime(cStart);
        Calendar cEnd = Calendar.getInstance();
        cEnd.setTime(end);
        com.fouu.habitflow.util.FrequencyUtil.zeroTime(cEnd);
        // Don't count days before the habit was created.
        if (h.getCreatedAt() != null) {
            Calendar created = Calendar.getInstance();
            created.setTime(h.getCreatedAt());
            com.fouu.habitflow.util.FrequencyUtil.zeroTime(created);
            if (created.after(cStart)) cStart.setTime(created.getTime());
        }
        if (cStart.after(cEnd)) return new int[]{0, 0};
        int should = 0, done = 0;
        Calendar c = (Calendar) cStart.clone();
        int guard = 0;
        while (!c.after(cEnd) && guard < 10000) {
            Date day = c.getTime();
            if (com.fouu.habitflow.util.FrequencyUtil.isTargetDay(freq, day)) {
                should++;
                long key = c.getTimeInMillis();
                java.util.Map<Long, HabitLog> dayMap = logsByHabitDay.get(h.getId());
                if (dayMap != null) {
                    HabitLog log = dayMap.get(key);
                    if (log != null && log.isCompleted()) done++;
                }
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
            guard++;
        }
        return new int[]{should, done};
    }

    /**
     * Build the bar-chart points for the currently selected view mode + anchor date.
     * WEEK → the natural week (Mon–Sun) containing anchorDate (7 bars, weekday labels).
     * MONTH → the whole calendar month containing anchorDate (28–31 bars, day-of-month labels).
     * Each bar's height is that day's completion rate (done that day ÷ should-do that day).
     */
    private List<WeeklyDataPoint> buildChartPoints(
            List<Habit> activeHabits,
            java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay) {
        List<WeeklyDataPoint> points = new ArrayList<>();

        Calendar anchor = Calendar.getInstance();
        anchor.setTimeInMillis(anchorMillis);
        com.fouu.habitflow.util.FrequencyUtil.zeroTime(anchor);

        // Determine [start, endExclusive)
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(anchor.getTimeInMillis());
        Calendar end = Calendar.getInstance();
        if (viewMode == MODE_MONTH) {
            start.set(Calendar.DAY_OF_MONTH, 1);
            end.setTimeInMillis(start.getTimeInMillis());
            end.add(Calendar.MONTH, 1);
        } else { // WEEK: Monday-based
            int dow = start.get(Calendar.DAY_OF_WEEK); // SUNDAY=1 .. SATURDAY=7
            int daysSinceMonday = (dow + 5) % 7;
            start.setTimeInMillis(start.getTimeInMillis());
            start.add(Calendar.DAY_OF_YEAR, -daysSinceMonday);
            end.setTimeInMillis(start.getTimeInMillis());
            end.add(Calendar.DAY_OF_YEAR, 7);
        }

        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(start.getTimeInMillis());
        boolean isMonth = viewMode == MODE_MONTH;
        while (d.getTime().before(end.getTime())) {
            Date dayStart = d.getTime();
            Calendar c = Calendar.getInstance();
            c.setTime(dayStart);
            com.fouu.habitflow.util.FrequencyUtil.zeroTime(c);
            long key = c.getTimeInMillis();

            // For this day, the denominator is every habit that already existed on this day
            // (created_at <= day) AND was not yet archived (archived_at > day) AND whose
            // frequency targets it. A habit with no log that day counts as not-done.
            // Archived habits are still included for the days before they were deleted, so
            // their history remains visible in the chart.
            int shouldDo = 0;
            int done = 0;
            for (Habit h : activeHabits) {
                if (h.getCreatedAt() != null) {
                    Calendar created = Calendar.getInstance();
                    created.setTime(h.getCreatedAt());
                    com.fouu.habitflow.util.FrequencyUtil.zeroTime(created);
                    if (created.getTimeInMillis() > key) continue;
                }
                // Skip days on/after the habit was archived (deleted) — it no longer counts.
                if (h.isArchived() && h.getArchivedAt() != null) {
                    Calendar archived = Calendar.getInstance();
                    archived.setTime(h.getArchivedAt());
                    com.fouu.habitflow.util.FrequencyUtil.zeroTime(archived);
                    if (key >= archived.getTimeInMillis()) continue;
                }
                String freq = h.getFrequency() != null ? h.getFrequency() : "DAILY";
                if (!com.fouu.habitflow.util.FrequencyUtil.isTargetDay(freq, dayStart)) continue;
                shouldDo++;
                java.util.Map<Long, HabitLog> dayMap = logsByHabitDay.get(h.getId());
                if (dayMap != null && dayMap.containsKey(key)) {
                    HabitLog log = dayMap.get(key);
                    if (log != null && log.isCompleted()) done++;
                }
            }
            float rate = shouldDo > 0 ? (float) done / shouldDo : 0f;
            String label = isMonth
                    ? String.valueOf(dayStart.getDate())
                    : DateUtil.getWeekdayShort(dayStart);
            points.add(new WeeklyDataPoint(label, rate));

            d.add(Calendar.DAY_OF_YEAR, 1);
        }
        return points;
    }

    /** Switch between weekly / monthly chart view. Triggers a recompute. */
    public void setViewMode(int mode) {
        if (mode != MODE_WEEK && mode != MODE_MONTH) return;
        if (mode == viewMode) return;
        viewMode = mode;
        loadData();
    }

    /** Set the anchor date (a point inside the week/month to display). Triggers a recompute. */
    public void setAnchorDate(long millis) {
        if (millis == anchorMillis) return;
        anchorMillis = millis;
        loadData();
    }

    public int getViewMode() { return viewMode; }
    public long getAnchorMillis() { return anchorMillis; }

    private void loadData() {
        // Single serial executor: every refresh() queues behind the previous one, so results
        // are always pushed in order (no out-of-order "jumping"). We deliberately do NOT use
        // a volatile `loading` guard here — that guard could drop a legit second refresh when
        // the user tabs back in, which is exactly what made the rate look "one refresh behind".
        loadExecutor.execute(() -> {
            AnalyticsSnapshot s = computeSync();
            // Switch to the main thread and setValue() (NOT postValue()): postValue() is
            // asynchronous and, combined with LiveData's sticky last-value, caused the bar to
            // show the previous screen's value until a second tab-in. setValue() on the main
            // thread makes the new value land immediately, in order.
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                totalHabits.setValue(s.totalHabits);
                weeklyData.setValue(s.weekly);
                todayCompletionRate.setValue(s.todayRate);
                overallCompletionRate.setValue(s.overallRate);
                currentStreak.setValue(s.currentStreak);
                bestStreak.setValue(s.bestStreak);
                loaded = true; // mark that a real load completed, so the UI can play the intro animation
                // Fire the deterministic callback LAST so the Fragment paints a fully-consistent
                // snapshot in one go.
                if (onLoadedListener != null) onLoadedListener.onLoaded(s);
            });
        });
    }

    /** Days in a row (ending today, or yesterday if today not yet checked in)
     *  where at least one habit was completed. */
    private int computeOverallStreak(Date today) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(today);

        // If nothing done today, start counting from yesterday so the streak
        // isn't reset before the user checks in today.
        if (!hasAnyCompletion(cal.getTime())) {
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }

        int streak = 0;
        while (hasAnyCompletion(cal.getTime())) {
            streak++;
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return streak;
    }

    /** Longest run of consecutive days (across all history) with >=1 completion. */
    private int computeBestOverallStreak() {
        List<HabitLog> all = db.habitLogDao().getAllSync();
        if (all.isEmpty()) return 0;

        // Collect distinct midnight-normalized dates that have a completed log.
        java.util.Set<Long> doneDays = new java.util.HashSet<>();
        for (HabitLog l : all) {
            if (l.isCompleted()) {
                Calendar c = Calendar.getInstance();
                c.setTime(l.getLogDate());
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                doneDays.add(c.getTimeInMillis());
            }
        }
        if (doneDays.isEmpty()) return 0;

        long earliest = Long.MAX_VALUE;
        for (long d : doneDays) earliest = Math.min(earliest, d);

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(earliest);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        int best = 0, run = 0;
        Date today = DateUtil.getDaysAgo(0);
        while (cal.getTime().getTime() <= today.getTime()) {
            if (doneDays.contains(cal.getTimeInMillis())) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return best;
    }

    /** Whether at least one habit was completed on the given (already zeroed) day. */
    private boolean hasAnyCompletion(Date day) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(day);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        List<HabitLog> logs = db.habitLogDao().getAllLogsByDate(cal.getTime());
        for (HabitLog l : logs) {
            if (l.isCompleted()) return true;
        }
        return false;
    }

    /**
     * Request AI-generated insights (premium feature).
     * Calls remote API with anonymized habit stats.
     */
    public void generateAiInsights() {
        if (!prefs.isPremium()) {
            aiInsights.postValue(getApplication().getString(com.fouu.habitflow.R.string.premium_required));
            return;
        }
        // Guard against concurrent / duplicate calls (e.g. rapid tab switches).
        if (generating) return;
        generating = true;

        // The payload now reads the DB (per-habit detail + 7/30-day trends + weekday
        // patterns), so build it on the serial executor — never on the main thread.
        loadExecutor.execute(() -> {
            String json = buildInsightStatsJson();
            apiService.generateInsights(prefs.getUserId(), json, new ApiService.ApiCallback<String>() {
                @Override
                public void onSuccess(String result) {
                    generating = false;
                    // The localized prompt dictates the reply language (system language),
                    // so we just prefix with a spark.
                    String text = "💡 " + result;
                    prefs.setCachedAiInsight(text);
                    prefs.setAiInsightDate(todayYmd());
                    aiInsights.postValue(text);
                }

                @Override
                public void onError(String error) {
                    generating = false;
                    // Surface the REAL error so the user can see exactly what went wrong
                    // (e.g. "HTTP 429", the network exception message, "解析AI响应失败")
                    // instead of a canned "insights unavailable / contact support" line.
                    String msg = (error != null && !error.isEmpty()) ? error : "未知错误";
                    aiInsights.postValue("⚠️ " + msg);
                    // Lock further auto-retries for today on rate-limit so we don't hammer the API.
                    String lower = msg.toLowerCase();
                    if (lower.contains("429") || lower.contains("too many")
                            || lower.contains("resource_exhausted") || lower.contains("quota")) {
                        prefs.setAiInsightDate(todayYmd());
                    }
                }
            });
        });
    }

    /** Build the detailed, anonymized-but-actionable stats payload for the AI call.
     *  Must run off the main thread (does several DB reads). The model gets per-habit
     *  detail + recent trends + weekday patterns so it can name specific habits and give
     *  concrete advice instead of generic "keep it up" text. */
    private String buildInsightStatsJson() {
        List<Habit> allHabits = db.habitDao().getAllHabitsIncludingArchivedSync();
        List<Habit> active = db.habitDao().getAllActiveHabitsSync();
        List<HabitLog> allLogs = db.habitLogDao().getAllSync();
        java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay = indexLogs(allLogs);
        Date today = DateUtil.getDaysAgo(0);
        Calendar cToday = Calendar.getInstance();
        cToday.setTime(today);
        com.fouu.habitflow.util.FrequencyUtil.zeroTime(cToday);
        long todayKey = cToday.getTimeInMillis();

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("habit_count", active.size());
        stats.put("overall_rate", round2(nullOr0(overallCompletionRate.getValue())));
        stats.put("rate_7d", round2(windowRate(allHabits, logsByHabitDay, todayKey, 7)));
        stats.put("rate_30d", round2(windowRate(allHabits, logsByHabitDay, todayKey, 30)));
        stats.put("current_streak", nullOr0(currentStreak.getValue()));
        stats.put("weekday_rates", weekdayRates(allHabits, logsByHabitDay, todayKey, 30));

        java.util.List<java.util.Map<String, Object>> habitList = new java.util.ArrayList<>();
        for (Habit h : active) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", h.getName());
            m.put("freq", h.getFrequency());
            m.put("streak", h.getStreak());
            m.put("best", h.getBestStreak());
            int[] sd = shouldAndDone(h, h.getCreatedAt(), effectiveEnd(h, today), logsByHabitDay);
            m.put("rate", sd[0] > 0 ? round2((float) sd[1] / sd[0]) : 0f);
            m.put("last_done_days_ago", daysSinceLastDone(h.getId(), logsByHabitDay, todayKey));
            habitList.add(m);
        }
        stats.put("habits", habitList);
        return new com.google.gson.Gson().toJson(stats);
    }

    private java.util.Map<Integer, java.util.Map<Long, HabitLog>> indexLogs(List<HabitLog> allLogs) {
        java.util.Map<Integer, java.util.Map<Long, HabitLog>> map = new java.util.HashMap<>();
        for (HabitLog l : allLogs) {
            if (l.getLogDate() == null) continue;
            Calendar c = Calendar.getInstance();
            c.setTime(l.getLogDate());
            com.fouu.habitflow.util.FrequencyUtil.zeroTime(c);
            long key = c.getTimeInMillis();
            map.computeIfAbsent(l.getHabitId(), k -> new java.util.HashMap<>()).put(key, l);
        }
        return map;
    }

    /** A soft-deleted habit stops counting from the day BEFORE its archived_at onward,
     *  i.e. the deletion day itself is excluded — consistent with buildChartPoints, which
     *  skips days with key >= archived_at. So its history counts [created_at, archived_at-1]. */
    private Date effectiveEnd(Habit h, Date today) {
        if (h.isArchived() && h.getArchivedAt() != null) {
            Calendar c = Calendar.getInstance();
            c.setTime(h.getArchivedAt());
            com.fouu.habitflow.util.FrequencyUtil.zeroTime(c);
            c.add(Calendar.DAY_OF_YEAR, -1);
            return c.getTime();
        }
        return today;
    }

    private float windowRate(List<Habit> habits,
                             java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay,
                             long todayKey, int days) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(todayKey);
        start.add(Calendar.DAY_OF_YEAR, -(days - 1));
        int should = 0, done = 0;
        Date startDate = start.getTime();
        for (Habit h : habits) {
            int[] sd = shouldAndDone(h, startDate, effectiveEnd(h, new Date(todayKey)), logsByHabitDay);
            should += sd[0];
            done += sd[1];
        }
        return should > 0 ? (float) done / should : 0f;
    }

    /** Per-weekday completion rate over the last `days` days (Mon..Sun). */
    private java.util.Map<String, Float> weekdayRates(
            List<Habit> habits,
            java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay,
            long todayKey, int days) {
        String[] order = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        int[] should = new int[7];
        int[] done = new int[7];
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(todayKey);
        start.add(Calendar.DAY_OF_YEAR, -(days - 1));
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(todayKey);
        Calendar d = (Calendar) start.clone();
        while (!d.after(end)) {
            int dow = d.get(Calendar.DAY_OF_WEEK); // SUNDAY=1 .. SATURDAY=7
            int idx = (dow + 5) % 7;               // Monday=0 .. Sunday=6
            Date day = d.getTime();
            for (Habit h : habits) {
                Date hEnd = effectiveEnd(h, day);
                if (hEnd.before(day)) continue;     // archived before this day
                if (h.getCreatedAt() != null && h.getCreatedAt().after(day)) continue;
                String freq = h.getFrequency() != null ? h.getFrequency() : "DAILY";
                if (!com.fouu.habitflow.util.FrequencyUtil.isTargetDay(freq, day)) continue;
                should[idx]++;
                java.util.Map<Long, HabitLog> dayMap = logsByHabitDay.get(h.getId());
                if (dayMap != null) {
                    HabitLog log = dayMap.get(d.getTimeInMillis());
                    if (log != null && log.isCompleted()) done[idx]++;
                }
            }
            d.add(Calendar.DAY_OF_YEAR, 1);
        }
        java.util.Map<String, Float> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            result.put(order[i], should[i] > 0 ? round2((float) done[i] / should[i]) : 0f);
        }
        return result;
    }

    private int daysSinceLastDone(int habitId,
                                  java.util.Map<Integer, java.util.Map<Long, HabitLog>> logsByHabitDay,
                                  long todayKey) {
        java.util.Map<Long, HabitLog> dayMap = logsByHabitDay.get(habitId);
        if (dayMap == null) return -1;
        long best = -1;
        for (java.util.Map.Entry<Long, HabitLog> e : dayMap.entrySet()) {
            if (e.getValue().isCompleted() && e.getKey() <= todayKey && e.getKey() > best) {
                best = e.getKey();
            }
        }
        return best < 0 ? -1 : (int) ((todayKey - best) / 86400000L);
    }

    private static float nullOr0(Float v) { return v != null ? v : 0f; }
    private static int nullOr0(Integer v) { return v != null ? v : 0; }
    private static float round2(float v) { return Math.round(v * 100f) / 100f; }

    private static String todayYmd() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
    }

    // ===== Data classes =====
    public static class WeeklyDataPoint {
        public final String dayLabel;
        public final float completionRate; // 0.0 - 1.0

        public WeeklyDataPoint(String day, float rate) {
            this.dayLabel = day;
            this.completionRate = rate;
        }
    }

    // ===== Getters =====
    public LiveData<List<WeeklyDataPoint>> getWeeklyData() { return weeklyData; }
    public LiveData<Integer> getTotalHabits() { return totalHabits; }
    public LiveData<Float> getTodayCompletionRate() { return todayCompletionRate; }
    public LiveData<Float> getOverallCompletionRate() { return overallCompletionRate; }
    public boolean isLoaded() { return loaded; }
    public LiveData<Integer> getCurrentStreak() { return currentStreak; }
    public LiveData<Integer> getBestStreak() { return bestStreak; }
    public LiveData<String> getAiInsights() { return aiInsights; }

    /** Callback fired on the main thread after every {@link #loadData()} completes. */
    public interface OnLoadedListener {
        void onLoaded(AnalyticsSnapshot snapshot);
    }

    public void setOnLoadedListener(OnLoadedListener l) { this.onLoadedListener = l; }
}
