package com.fouu.habitflow.util;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.ui.main.MainActivity;
import com.fouu.habitflow.R;

import java.util.concurrent.Executors;

/**
 * NotificationReceiver - handles alarm broadcasts and shows reminders.
 * After firing, it reschedules the next occurrence, skipping non-target days
 * for WEEKDAYS habits (e.g. no reminder on weekends).
 */
public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int habitId = intent.getIntExtra("habit_id", -1);
        if (habitId == -1) return;

        // Load the habit (off the main thread) to know its frequency + name.
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(context);
            Habit habit = db.habitDao().getHabitByIdSync(habitId);
            if (habit == null) return;

            String name = habit.getName();

            // Reschedule the next occurrence first.
            new NotificationHelper(context).scheduleHabitReminder(habit);

            // Only show the notification if today is actually a target day
            // (e.g. WEEKDAYS habits don't remind on weekends).
            if (!FrequencyUtil.isTargetDay(habit.getFrequency(), new java.util.Date())) {
                return;
            }

            showNotification(context, habitId, name);
        });
    }

    /** Buzz the vibrator directly so the reminder is felt even when the notification
     *  channel's cached vibration setting (unchangeable once created) is off. */
    private void buzz(Context context) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(200);
            }
        } catch (Exception ignored) {
            // Vibration is a nice-to-have; never let it break the notification.
        }
    }

    private void showNotification(Context context, int habitId, String habitName) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("habit_id", habitId);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                habitId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, NotificationHelper.CHANNEL_HABIT_REMINDER
        )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_reminder_title))
                .setContentText(context.getString(R.string.notification_reminder_text, habitName))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVibrate(new long[]{0, 120, 60, 120});

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(habitId, builder.build());
        // Drive the motor directly — independent of the notification channel's cached
        // vibration setting, which may have been created without vibration enabled.
        buzz(context);
    }
}
