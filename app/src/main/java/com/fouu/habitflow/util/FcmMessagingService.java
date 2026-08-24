package com.fouu.habitflow.util;

import android.util.Log;

import com.fouu.habitflow.R;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * FcmMessagingService - handles Firebase push notifications.
 *
 * Use cases:
 * - Re-engage inactive users ("You're on a 5-day streak! Don't break it!")
 * - Announce new features
 * - Send personalized AI insights
 */
public class FcmMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FcmMessagingService";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Log.d(TAG, "From: " + remoteMessage.getFrom());

        String title = null;
        String body = null;

        // Handle data payload (preferred; works even when app is backgrounded/killed)
        if (remoteMessage.getData().size() > 0) {
            title = remoteMessage.getData().get("title");
            body = remoteMessage.getData().get("body");
            Log.d(TAG, "Data: " + title + " - " + body);
        }

        // Handle notification payload (falls back to this when no data title/body)
        if (remoteMessage.getNotification() != null) {
            if (title == null) title = remoteMessage.getNotification().getTitle();
            if (body == null) body = remoteMessage.getNotification().getBody();
            Log.d(TAG, "Notification: " + title + " - " + body);
        }

        // Actually show the push notification
        if (body != null) {
            showFcmNotification(title, body);
        }
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "New FCM token: " + token);
        // Send token to your server for targeted push notifications
    }

    // ===== Inner class for building notifications from FCM data =====
    private void showFcmNotification(String title, String body) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        String channelId = "habitflow_fcm_channel";

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, "HabitFlow Notifications",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
            );
            if (nm != null) nm.createNotificationChannel(channel);
        }

        androidx.core.app.NotificationCompat.Builder builder =
                new androidx.core.app.NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title != null ? title : getString(R.string.app_name))
                        .setContentText(body)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true);

        if (nm != null) nm.notify((int) System.currentTimeMillis(), builder.build());
    }
}
