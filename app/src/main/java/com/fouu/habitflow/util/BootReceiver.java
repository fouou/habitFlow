package com.fouu.habitflow.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.model.Habit;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BootReceiver - reschedules alarms after device reboot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                "android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {

            Log.d(TAG, "Boot completed - rescheduling alarms");

            executor.execute(() -> {
                AppDatabase db = AppDatabase.getInstance(context);
                List<Habit> habits = db.habitDao().getHabitsWithReminders();
                NotificationHelper helper = new NotificationHelper(context);

                for (Habit habit : habits) {
                    helper.scheduleHabitReminder(habit);
                }
            });
        }
    }
}
