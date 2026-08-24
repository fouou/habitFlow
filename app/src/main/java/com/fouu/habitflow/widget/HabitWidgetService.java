package com.fouu.habitflow.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.db.HabitDao;
import com.fouu.habitflow.data.db.HabitLogDao;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.model.HabitLog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import androidx.core.content.ContextCompat;

/**
 * Backing service for the habit widget's ListView. Builds one RemoteViews per habit row.
 * Each row fills in a click intent (setOnClickFillInIntent) carrying its habit id; the
 * provider supplies the template PendingIntent (MUTABLE) that the launcher merges it into.
 */
public class HabitWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new HabitViewsFactory(this.getApplicationContext(), intent);
    }

    static class HabitViewsFactory implements RemoteViewsService.RemoteViewsFactory {

        private final Context context;
        private final int appWidgetId;
        private List<Habit> habits = new ArrayList<>();
        private android.util.SparseBooleanArray doneMap = new android.util.SparseBooleanArray();
        private boolean dark;

        HabitViewsFactory(Context context, Intent intent) {
            this.context = context;
            this.appWidgetId = intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
                    android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        @Override
        public void onCreate() {
            dark = isDarkMode(context);
            // onCreate may be called before onDataSetChanged; do an async preload so the
            // first getViewAt isn't hopelessly empty, but the authoritative (blocking) load
            // happens in onDataSetChanged.
            loadDataAsync();
        }

        @Override
        public void onDataSetChanged() {
            dark = isDarkMode(context);
            // The system waits for onDataSetChanged() to return before calling getViewAt(),
            // so we block until the DB query finishes. This guarantees getViewAt sees the
            // real habit list instead of an empty one (which showed "no habits").
            loadDataBlocking();
        }

        @Override
        public void onDestroy() {
            habits = new ArrayList<>();
            doneMap = new android.util.SparseBooleanArray();
        }

        @Override
        public int getCount() {
            return habits.size();
        }

        /** Block until the habit list is loaded (used by onDataSetChanged, which the system
         *  waits on before fetching views). A collection widget MUST NOT return from
         *  onDataSetChanged() before its data is ready — so we block until the load finishes
         *  instead of using a short timeout. The old 5-second cap caused the widget to go
         *  blank after the phone slept: overnight the app process is killed, and the next
         *  morning the widget is refreshed from a COLD start where opening the Room DB
         *  (schema check + WAL replay) can take longer than 5s, so the latch timed out with
         *  an empty list and the launcher showed "no habits". */
        private void loadDataBlocking() {
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.ExecutorService ex =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            ex.execute(() -> {
                try {
                    doLoad();
                } finally {
                    latch.countDown();
                    ex.shutdown();
                }
            });
            try {
                // Wait without a tight cap; the system itself bounds how long onDataSetChanged
                // may block. 30s is only a safety valve against a genuinely wedged DB.
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Fire-and-forget variant (used by onCreate preload). */
        private void loadDataAsync() {
            final java.util.concurrent.ExecutorService ex =
                    java.util.concurrent.Executors.newSingleThreadExecutor();
            ex.execute(() -> {
                try {
                    doLoad();
                } finally {
                    ex.shutdown();
                }
            });
        }

        /** Actual DB work: fill habits + doneMap on the calling (background) thread.
         *  Wrapped so a transient failure (e.g. a cold-start DB hiccup) is logged instead of
         *  silently leaving the widget blank. */
        private void doLoad() {
            try {
                AppDatabase db = AppDatabase.getInstance(context);
                List<Habit> loaded = db.habitDao().getAllActiveHabitsSync();
                if (loaded == null) loaded = new ArrayList<>();
                android.util.SparseBooleanArray map = new android.util.SparseBooleanArray();
                HabitLogDao logDao = db.habitLogDao();
                Calendar tomorrowCal = Calendar.getInstance();
                Date todayStart = startOfToday();
                tomorrowCal.setTime(todayStart);
                tomorrowCal.add(Calendar.DAY_OF_YEAR, 1);
                Date tomorrowStart = tomorrowCal.getTime();
                for (Habit h : loaded) {
                    HabitLog log = logDao.getLogByDayRange(h.getId(), todayStart, tomorrowStart);
                    map.put(h.getId(), log != null && log.isCompleted());
                }
                HabitViewsFactory.this.habits = loaded;
                HabitViewsFactory.this.doneMap = map;
                if (loaded.isEmpty()) {
                    android.util.Log.w("HabitWidget", "widget reload returned 0 habits "
                            + "(appWidgetId=" + appWidgetId + ")");
                }
            } catch (Exception e) {
                android.util.Log.w("HabitWidget", "widget reload failed: " + e.getMessage());
            }
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= habits.size()) {
                return new RemoteViews(context.getPackageName(), R.layout.widget_habit_item);
            }
            Habit habit = habits.get(position);
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_habit_item);
            row.setTextViewText(R.id.widget_name, habit.getName());

            boolean done = doneMap.get(habit.getId(), false);

            // --- Same color logic as the in-app habit card (HabitAdapter) ---
            // M3 dynamic wallpaper color is unreachable in the launcher process, so we fall
            // back to the same algorithm + default M3 palette (exact match on non-dynamic devices).
            String colorHex = habit.getColorHex();
            boolean isDefault = colorHex == null || colorHex.isEmpty()
                    || Habit.COLOR_DEFAULT.equals(colorHex);

            int accent;
            int deepColor;
            if (isDefault) {
                accent = parseColorSafe(Habit.DEFAULT_COLOR_HEX);
                deepColor = accent;
            } else {
                accent = parseColorSafe(colorHex);
                deepColor = dark
                        ? mix(accent, 0xFFFFFFFF, 0.30f)
                        : mix(accent, 0xFF000000, 0.12f);
            }

            // High-contrast text per dark/light mode (theme colorOnSurface is unreliable in the
            // launcher process, so we use fixed values that match the app's on-surface tones).
            int nameColor = dark ? 0xFFE6E1EC : 0xFF1C1B1F;

            row.setInt(R.id.widget_row, "setBackgroundResource",
                    dark ? R.drawable.widget_row_bg_dark : R.drawable.widget_row_bg_light);

            // Checkbox: default → brand tick; colored habit → deep accent tick.
            int tickColor = isDefault ? 0xFF6750A4 : deepColor;
            row.setImageViewResource(R.id.widget_check,
                    done ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_outline);
            row.setInt(R.id.widget_check, "setColorFilter", tickColor);
            // Color the habit dot with the accent, matching the app card.
            row.setInt(R.id.widget_dot, "setColorFilter", accent);
            row.setTextColor(R.id.widget_name, nameColor);

            // Strike-through on the name when completed (per request).
            row.setInt(R.id.widget_name, "setPaintFlags",
                    done
                            ? (android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                                | android.graphics.Paint.ANTI_ALIAS_FLAG)
                            : android.graphics.Paint.ANTI_ALIAS_FLAG);

            // Per-row click: fill in the habit id so the provider's template PendingIntent
            // can route the toggle to the right habit.
            Intent fillIn = new Intent();
            fillIn.putExtra(HabitWidgetProvider.EXTRA_HABIT_ID, habit.getId());
            fillIn.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            row.setOnClickFillInIntent(R.id.widget_row, fillIn);

            return row;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            if (position < 0 || position >= habits.size()) return -1;
            return habits.get(position).getId();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        private static boolean isDarkMode(Context ctx) {
            return (ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }

        private static Date startOfToday() {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTime();
        }

        private static int parseColorSafe(String hex) {
            try {
                return Color.parseColor(hex);
            } catch (Exception e) {
                return Color.parseColor("#6750A4");
            }
        }

        private static int mix(int a, int b, float t) {
            int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
            int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
            int r = Math.round(ar + (br - ar) * t);
            int g = Math.round(ag + (bg - ag) * t);
            int bl = Math.round(ab + (bb - ab) * t);
            return Color.rgb(r, g, bl);
        }
    }
}
