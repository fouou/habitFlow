package com.fouu.habitflow.util;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.util.FrequencyUtil;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * NotificationHelper - schedules and displays habit reminders.
 *
 * Uses AlarmManager for precise scheduling (with SCHEDULE_EXACT_ALARM permission).
 * Creates notification channels for Android O+.
 */
public class NotificationHelper {

    public static final String CHANNEL_HABIT_REMINDER = "habit_reminder_channel";
    public static final String CHANNEL_FOCUS_TIMER = "focus_timer_channel";
    public static final String CHANNEL_ACHIEVEMENT = "achievement_channel";

    private final Context context;
    private final NotificationManager notificationManager;
    private final AlarmManager alarmManager;

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Habit Reminder Channel
            NotificationChannel reminderChannel = new NotificationChannel(
                    CHANNEL_HABIT_REMINDER,
                    context.getString(R.string.channel_reminder_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            reminderChannel.setDescription(context.getString(R.string.channel_reminder_desc));
            // Vibration is part of the reminder experience (the user asked for a buzz).
            reminderChannel.enableVibration(true);
            reminderChannel.setVibrationPattern(new long[]{0, 120, 60, 120});

            // Focus Timer Channel
            NotificationChannel focusChannel = new NotificationChannel(
                    CHANNEL_FOCUS_TIMER,
                    context.getString(R.string.channel_focus_name),
                    NotificationManager.IMPORTANCE_LOW
            );

            // Achievement Channel
            NotificationChannel achievementChannel = new NotificationChannel(
                    CHANNEL_ACHIEVEMENT,
                    context.getString(R.string.channel_achievement_name),
                    NotificationManager.IMPORTANCE_HIGH
            );

            notificationManager.createNotificationChannels(Arrays.asList(
                    reminderChannel, focusChannel, achievementChannel
            ));
        }
    }

    /**
     * Schedule a daily reminder for a habit.
     */
    public void scheduleHabitReminder(Habit habit) {
        if (habit == null || !habit.isReminderEnabled() || habit.getReminderTime() <= 0) return;

        Intent intent = new Intent(context, NotificationReceiver.class);
        intent.putExtra("habit_id", habit.getId());
        intent.putExtra("habit_name", habit.getName());
        // Unique data URI so PendingIntents for different habits never coalesce.
        intent.setData(android.net.Uri.parse("habitreminder://" + habit.getId()));

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                habit.getId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Calculate trigger time
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, (int) (habit.getReminderTime() / 3600000));
        cal.set(Calendar.MINUTE, (int) ((habit.getReminderTime() % 3600000) / 60000));
        cal.set(Calendar.SECOND, 0);

        // If time already passed today, schedule for the next day
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Skip non-target days for the habit's frequency (e.g. weekends for WEEKDAYS).
        // Advance day-by-day until we land on a target day.
        int guard = 0;
        while (!FrequencyUtil.isTargetDay(habit.getFrequency(), cal.getTime()) && guard < 14) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            guard++;
        }

        long triggerAt = cal.getTimeInMillis();

        // Android 12 (S) introduced SCHEDULE_EXACT_ALARM; on Android 13+ it is NOT granted
        // just by declaring it in the manifest — the user must enable "Alarms & reminders"
        // in system settings. Calling setExact* without it throws SecurityException, so we
        // check first and gracefully fall back to an inexact alarm (which still fires, just
        // with some slack) instead of crashing.
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                // Inexact but doze-friendly; the system may delay it, which is acceptable
                // for a habit nudge and never crashes.
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException e) {
            // Permission was revoked between the check and the call (it can be toggled off
            // at any time, and the process is not killed). Fall back rather than crash.
            try {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } catch (Exception ignored) {
                // Nothing more we can do; skip this reminder.
            }
        }
    }

    /**
     * Whether the app may schedule exact alarms right now.
     *
     * Below Android 12 this is always true. From Android 12 the user (or OEM) can revoke it
     * at any time, so this must be re-checked before every exact-alarm call, not cached.
     */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return alarmManager != null && alarmManager.canScheduleExactAlarms();
    }

    /**
     * Intent that opens the system "Alarms & reminders" screen for this app, so the user can
     * grant exact-alarm access. Returns null when the permission concept doesn't exist
     * (pre-Android 12) or is already granted.
     */
    public Intent buildExactAlarmSettingsIntent() {
        if (canScheduleExactAlarms()) return null;
        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        i.setData(android.net.Uri.parse("package:" + context.getPackageName()));
        return i;
    }

    /**
     * Cancel reminder for a habit.
     */
    public void cancelHabitReminder(int habitId) {
        Intent intent = new Intent(context, NotificationReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                habitId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(pendingIntent);
    }

    /**
     * Show immediate notification (for achievements, etc.)
     */
    public void showNotification(int id, String title, String message, String channelId) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        notificationManager.notify(id, builder.build());
    }

    /**
     * Returns the next trigger time (millis) for a habit's reminder, or -1 if reminders
     * are disabled / not configured. Today's time is used if it hasn't passed yet,
     * otherwise the next matching day is found (respecting the habit's frequency rule).
     */
    public long getNextReminderTimeMillis(Habit habit) {
        if (habit == null || !habit.isReminderEnabled() || habit.getReminderTime() <= 0) {
            return -1;
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, (int) (habit.getReminderTime() / 3600000));
        cal.set(Calendar.MINUTE, (int) ((habit.getReminderTime() % 3600000) / 60000));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        int guard = 0;
        while (!FrequencyUtil.isTargetDay(habit.getFrequency(), cal.getTime()) && guard < 14) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            guard++;
        }
        return cal.getTimeInMillis();
    }
}
