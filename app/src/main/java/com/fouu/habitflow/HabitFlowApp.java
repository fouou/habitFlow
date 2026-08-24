package com.fouu.habitflow;

import android.app.Application;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.remote.SyncManager;
import com.fouu.habitflow.data.repo.AchievementRepository;
import com.fouu.habitflow.util.PreferenceManager;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * HabitFlow Application Class
 *
 * Responsibilities:
 * - Initialize Firebase
 * - Set up Crashlytics
 * - Configure default theme mode (light/dark/dynamic)
 * - Initialize Room database instance
 *
 * Dynamic Color (Material You) is declared in AndroidManifest and
 * applied in MainActivity via DynamicColors API.
 */
public class HabitFlowApp extends Application {

    private static final String TAG = "HabitFlowApp";
    private static HabitFlowApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 1. Initialize Firebase (required for Auth, Firestore, Analytics, Crashlytics)
        FirebaseApp.initializeApp(this);

        // 2. Configure Crashlytics
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);

        // 3. Apply saved theme preference on app start
        PreferenceManager prefs = PreferenceManager.getInstance(this);
        int themeMode = prefs.getThemeMode(); // 0=system, 1=light, 2=dark
        AppCompatDelegate.setDefaultNightMode(mapThemeMode(themeMode));

        // 4. Pre-warm Room database (lazy singleton handles actual creation)
        // Accessing the DB instance early prevents first-launch lag
        AppDatabase.getInstance(this);

        // 4b. Seed default achievements on first launch. The seed method is idempotent
        // (checks the table is empty first), so calling it every startup is safe. Without
        // this, the achievements feature never had any data and showed up empty / never synced.
        new AchievementRepository(this).seedDefaultAchievements();

        // 5. Cloud sync: if a user is already signed in (Firebase persists auth),
        //    restore their data from Firestore on startup.
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            SyncManager.getInstance(this).syncNow();
        }

        Log.d(TAG, "HabitFlow initialized successfully");
    }

    private int mapThemeMode(int mode) {
        switch (mode) {
            case 1: return AppCompatDelegate.MODE_NIGHT_NO;
            case 2: return AppCompatDelegate.MODE_NIGHT_YES;
            default: return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    public static HabitFlowApp getInstance() {
        return instance;
    }
}
