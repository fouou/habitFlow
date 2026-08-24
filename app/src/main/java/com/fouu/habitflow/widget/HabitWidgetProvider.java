package com.fouu.habitflow.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.widget.RemoteViews;

import com.fouu.habitflow.ui.main.MainActivity;
import com.fouu.habitflow.R;
import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.db.HabitDao;
import com.fouu.habitflow.data.db.HabitLogDao;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.model.HabitLog;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Home-screen widget showing today's habits in a SCROLLABLE list (ListView collection widget).
 * Each row shows a check icon + habit name; tapping a row toggles its completion for today.
 *
 * Why a ListView (not a hand-built LinearLayout): RemoteViews do not support ScrollView, and a
 * plain LinearLayout of rows cannot scroll, so tall habit lists get clipped. The collection
 * ListView is the only widget element the launcher lets scroll.
 */
public class HabitWidgetProvider extends AppWidgetProvider {

    static final String ACTION_TOGGLE = "com.fouu.habitflow.widget.TOGGLE";
    static final String EXTRA_HABIT_ID = "habit_id";
    /** Broadcast fired after a widget toggle so the in-app UI (a different process than the
     *  launcher hosting the widget) can re-read the DB and stay in sync. Room's invalidation
     *  tracker is per-process, so the app process won't otherwise learn the widget changed data. */
    static final String ACTION_DATA_CHANGED = "com.fouu.habitflow.ACTION_HABIT_DATA_CHANGED";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();

        if (ACTION_TOGGLE.equals(action)) {
            int habitId = intent.getIntExtra(EXTRA_HABIT_ID, -1);
            if (habitId != -1) {
                // Refresh happens inside toggleToday after the DB write commits.
                toggleToday(context, habitId);
            }
        } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)
                || "android.intent.action.UI_MODE_CHANGED".equals(action)) {
            // System dark/light change: re-render (collection items rebuild via
            // notifyAppWidgetViewDataChanged; root background via updateAppWidget).
            refreshAllWidgets(context);
        }
    }

    private static void refreshAllWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, HabitWidgetProvider.class));
        for (int id : ids) {
            updateAppWidget(context, mgr, id);
        }
    }

    private void toggleToday(Context context, int habitId) {
        AppDatabase db = AppDatabase.getInstance(context);
        HabitDao habitDao = db.habitDao();
        HabitLogDao logDao = db.habitLogDao();

        // Run DB ops off the main thread. After the write commits, refresh the widget
        // so the new state is reflected — doing this before the write finishes would
        // re-read the stale (pre-toggle) data and make the tap look like it didn't work.
        new Thread(() -> {
            Habit habit = habitDao.getHabitByIdSync(habitId);
            if (habit == null) return;
            Date today = startOfToday();
            HabitLog existing = logDao.getLogByDate(habitId, today);
            if (existing != null) {
                // Toggle: remove today's completion
                logDao.delete(existing);
            } else {
                HabitLog log = new HabitLog();
                log.setHabitId(habitId);
                log.setLogDate(today);
                log.setCompleted(true);
                log.setCreatedAt(new Date());
                log.setLocalId(java.util.UUID.randomUUID().toString());
                logDao.insert(log);
            }
            // Refresh only after the DB write has completed, on the main looper.
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                refreshAllWidgets(context);
                // Tell the in-app UI (separate process) to re-read the DB. The broadcast covers
                // the case where the app is in the foreground; the dirty flag covers the case
                // where it's backgrounded and misses the broadcast (onResume re-checks it).
                context.sendBroadcast(new Intent(ACTION_DATA_CHANGED));
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .edit().putBoolean("habit_data_dirty", true).apply();
            });
        }).start();
    }

    private static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        // NOTE: updateAppWidget() REPLACES the whole widget; everything must be set on ONE
        // RemoteViews instance and pushed once.
        //
        // updateAppWidget() + notifyAppWidgetViewDataChanged() MUST run on the MAIN thread;
        // calling them from a worker thread makes the data-changed broadcast unreliable, so
        // toggling a row would not refresh (looked "stuck"). So the DB count read runs on a
        // worker thread, then we post the widget push to the main looper.
        new Thread(() -> {
            int activeCount = 0;
            AppDatabase db = AppDatabase.getInstance(context);
            List<Habit> habits = db.habitDao().getAllActiveHabitsSync();
            if (habits != null) activeCount = habits.size();

            final int finalActiveCount = activeCount;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_habit);

                // Widgets render in the launcher's process and can't read this app's Material3
                // dynamic theme. Apply concrete light/dark colors so the widget follows the
                // device dark mode instead of staying pure white.
                boolean dark = isDarkMode(context);
                // Theme-resolved colors (colorOnSurface etc.) are unreliable in the launcher
                // process, so use fixed high-contrast colors per dark/light mode.
                int headerAccent = dark ? 0xFFEADDFF : 0xFF6750A4;
                int bodyText     = dark ? 0xFFE6E1EC : 0xFF1C1B1F;
                views.setInt(R.id.widget_root, "setBackgroundResource",
                        dark ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);
                views.setTextColor(R.id.widget_title, headerAccent);
                views.setTextColor(R.id.widget_count, bodyText);

                // Open the app when tapping the header
                Intent openApp = new Intent(context, MainActivity.class);
                PendingIntent openPi = PendingIntent.getActivity(context, 0, openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                views.setOnClickPendingIntent(R.id.widget_header, openPi);

                // Bind the habit list via the RemoteViewsService (this is what makes the list
                // scrollable). The intent MUST carry a unique data URI per widget id.
                Intent svc = new Intent(context, HabitWidgetService.class);
                svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                svc.setData(android.net.Uri.parse("habitwidget://widget/" + appWidgetId));
                views.setRemoteAdapter(R.id.widget_list, svc);

                // Collection-widget click plumbing: the template is a "shell" PendingIntent that
                // supplies the action + target component. Each row (in HabitWidgetService.getViewAt)
                // fills in its own extras (habit id / appWidgetId) via setOnClickFillInIntent.
                // This is the ONLY supported way to make ListView rows in an app widget clickable.
                // The template MUST be MUTABLE: setOnClickFillInIntent merges the habit id into the
                // Intent at click time, and FLAG_IMMUTABLE (API31+) refuses that merge → broadcast
                // arrives without data → toggle never fires. (Verified on a Huawei ALI-AN00: fill-in
                // clicks DO fire correctly, so ListView+fill-in is fine here.)
                Intent template = new Intent(context, HabitWidgetProvider.class);
                template.setAction(ACTION_TOGGLE);
                template.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                template.setData(android.net.Uri.parse("habitwidget://toggle/" + appWidgetId));
                PendingIntent templatePi = PendingIntent.getBroadcast(
                        context, 0, template,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                views.setPendingIntentTemplate(R.id.widget_list, templatePi);

                views.setEmptyView(R.id.widget_list, R.id.widget_empty);
                views.setTextViewText(R.id.widget_count,
                        context.getString(R.string.widget_count, finalActiveCount));

                appWidgetManager.updateAppWidget(appWidgetId, views);
                // Re-run the factory so rows reflect the latest data / dark mode.
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list);
            });
        }).start();
    }

    private static Date startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    /** True when the system is currently in night (dark) mode. */
    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
