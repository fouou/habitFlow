package com.fouu.habitflow.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized SharedPreferences manager.
 * Handles theme, language, premium status, and user preferences.
 */
public class PreferenceManager {

    private static final String PREF_THEME_MODE = "theme_mode";       // 0=system, 1=light, 2=dark
    private static final String PREF_DYNAMIC_COLOR = "dynamic_color";  // boolean
    private static final String PREF_IS_PREMIUM = "is_premium";       // boolean
    private static final String PREF_USER_ID = "user_id";              // Firebase UID
    private static final String PREF_USER_EMAIL = "user_email";        // email
    private static final String PREF_HABIT_LIMIT = "habit_limit";      // max free habits
    private static final String PREF_AD_FREE = "ad_free";              // boolean
    private static final String PREF_ONBOARDING_DONE = "onboarding_done"; // boolean
    private static final String PREF_NOTIFICATION_TIME = "notification_time"; // millis
    private static final String PREF_LAST_SYNC = "last_sync_time";   // epoch millis of last successful sync
    private static final String PREF_SYNC_ERROR = "last_sync_error"; // last sync error message (empty = ok)
    private static final String PREF_SKIP_LOGIN = "skip_login";         // user chose "skip" on auth screen
    private static final String PREF_HABIT_DATA_DIRTY = "habit_data_dirty"; // set when another process (widget) changed habit data

    private static PreferenceManager instance;
    private final SharedPreferences prefs;

    private PreferenceManager(Context context) {
        this.prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    public static synchronized PreferenceManager getInstance(Context context) {
        if (instance == null) {
            instance = new PreferenceManager(context);
        }
        return instance;
    }

    /** Expose underlying SharedPreferences (e.g. for SyncManager dirty-flag set). */
    public SharedPreferences getPrefs() { return prefs; }

    // ===== Theme =====
    public int getThemeMode() { return prefs.getInt(PREF_THEME_MODE, 0); }
    public void setThemeMode(int mode) { prefs.edit().putInt(PREF_THEME_MODE, mode).apply(); }

    // ===== Dynamic Color (Material You) =====
    public boolean isDynamicColorEnabled() { return prefs.getBoolean(PREF_DYNAMIC_COLOR, true); }
    public void setDynamicColorEnabled(boolean enabled) { prefs.edit().putBoolean(PREF_DYNAMIC_COLOR, enabled).apply(); }

    // ===== Premium / Subscription =====
    // Membership is "premium" if either:
    //  - PREF_IS_PREMIUM is true (paid subscription / debug unlock) → permanent, or
    //  - the reward expiry (PREF_PREMIUM_UNTIL) is still in the future → earned via achievements.
    private static final String PREF_PREMIUM_UNTIL = "premium_until"; // epoch millis; 0 = none

    public boolean isPremium() {
        return prefs.getBoolean(PREF_IS_PREMIUM, false)
                || System.currentTimeMillis() < prefs.getLong(PREF_PREMIUM_UNTIL, 0);
    }
    public void setPremium(boolean premium) {
        prefs.edit().putBoolean(PREF_IS_PREMIUM, premium).apply();
        if (!premium) {
            // Full revoke (used by the debug "cancel membership" button): also clear any
            // reward-granted days so isPremium() truly returns false immediately.
            prefs.edit().remove(PREF_PREMIUM_UNTIL).apply();
        }
    }

    /** Grant N days of membership, extending from the later of now / current expiry. */
    public void grantPremiumDays(int days) {
        if (days <= 0) return;
        long now = System.currentTimeMillis();
        long current = prefs.getLong(PREF_PREMIUM_UNTIL, 0);
        long until = Math.max(now, current) + days * 24L * 3600L * 1000L;
        prefs.edit().putLong(PREF_PREMIUM_UNTIL, until).apply();
    }

    public boolean isAdFree() { return prefs.getBoolean(PREF_AD_FREE, false) || isPremium(); }
    public void setAdFree(boolean adFree) { prefs.edit().putBoolean(PREF_AD_FREE, adFree).apply(); }

    // ===== User =====
    public String getUserId() { return prefs.getString(PREF_USER_ID, ""); }
    public void setUserId(String uid) { prefs.edit().putString(PREF_USER_ID, uid).apply(); }

    public String getUserEmail() { return prefs.getString(PREF_USER_EMAIL, ""); }
    public void setUserEmail(String email) { prefs.edit().putString(PREF_USER_EMAIL, email).apply(); }

    // ===== Habit Limits (Free tier = 3 habits) =====
    public int getHabitLimit() { return isPremium() ? Integer.MAX_VALUE : 3; }

    // ===== Custom focus duration (Premium only; Free tier is fixed at 25 min) =====
    private static final String PREF_FOCUS_DURATION = "focus_duration_min"; // minutes
    public int getFocusDurationMin() { return prefs.getInt(PREF_FOCUS_DURATION, 25); }
    public void setFocusDurationMin(int min) { prefs.edit().putInt(PREF_FOCUS_DURATION, min).apply(); }

    // ===== Onboarding =====
    public boolean isOnboardingDone() { return prefs.getBoolean(PREF_ONBOARDING_DONE, false); }
    public void setOnboardingDone(boolean done) { prefs.edit().putBoolean(PREF_ONBOARDING_DONE, done).apply(); }

    // ===== Notifications =====
    public long getNotificationTime() { return prefs.getLong(PREF_NOTIFICATION_TIME, 28800000); } // 8 AM default
    public void setNotificationTime(long millis) { prefs.edit().putLong(PREF_NOTIFICATION_TIME, millis).apply(); }

    // ===== Clear all (on logout) =====
    /**
     * Clear user identity on logout.
     * IMPORTANT: Premium/AdFree status is intentionally preserved so that a user who
     * paid via Google Play Billing does NOT lose membership just by signing out.
     * It will be re-confirmed by BillingManager.checkExistingPurchases() on next launch.
     */
    public void clearUserData() {
        prefs.edit()
                .remove(PREF_USER_ID)
                .remove(PREF_USER_EMAIL)
                // .remove(PREF_IS_PREMIUM)  // keep — membership tied to Play account, not app login
                // .remove(PREF_AD_FREE)     // keep — same reason
                .apply();
    }

    // ===== Cloud Sync status =====
    public long getLastSyncTime() { return prefs.getLong(PREF_LAST_SYNC, 0); }
    public void setLastSyncTime(long time) { prefs.edit().putLong(PREF_LAST_SYNC, time).apply(); }

    // ===== AI Insights cache (avoid re-hitting Gemini on every screen open) =====
    private static final String PREF_AI_INSIGHT = "ai_insight_cache";
    private static final String PREF_AI_INSIGHT_DATE = "ai_insight_date"; // yyyy-MM-dd of last fetch

    public String getCachedAiInsight() { return prefs.getString(PREF_AI_INSIGHT, null); }
    public void setCachedAiInsight(String s) { prefs.edit().putString(PREF_AI_INSIGHT, s).apply(); }
    public String getAiInsightDate() { return prefs.getString(PREF_AI_INSIGHT_DATE, ""); }
    public void setAiInsightDate(String d) { prefs.edit().putString(PREF_AI_INSIGHT_DATE, d).apply(); }

    /** True when there is no cached insight for today (so a fresh fetch is allowed). */
    public boolean isAiInsightStale() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        return !today.equals(getAiInsightDate());
    }

    // ===== Skip Login (auth screen "skip" choice) =====
    public boolean isSkipLogin() { return prefs.getBoolean(PREF_SKIP_LOGIN, false); }
    public void setSkipLogin(boolean skip) { prefs.edit().putBoolean(PREF_SKIP_LOGIN, skip).apply(); }

    /** Last sync error message; empty string means no error. */
    public String getLastSyncError() { return prefs.getString(PREF_SYNC_ERROR, ""); }
    public void setLastSyncError(String error) {
        prefs.edit().putString(PREF_SYNC_ERROR, error == null ? "" : error).apply();
    }

    // ===== Cross-process habit-data dirty flag =====
    // The home-screen widget runs in the launcher process and writes the DB directly. Room's
    // invalidation is per-process, so the app process never learns about the change on its own.
    // We persist a dirty flag so the app can refresh when it next returns to the foreground,
    // even if it missed the real-time broadcast (e.g. it was backgrounded at toggle time).
    public boolean isHabitDataDirty() { return prefs.getBoolean(PREF_HABIT_DATA_DIRTY, false); }
    public void setHabitDataDirty(boolean dirty) {
        prefs.edit().putBoolean(PREF_HABIT_DATA_DIRTY, dirty).apply();
    }

}
