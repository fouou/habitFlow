package com.fouu.habitflow.util;

import android.content.Context;

import com.fouu.habitflow.R;

/**
 * Maps each achievement's stable canonical (English) title to its localized
 * display strings. The DB intentionally stores the English keys so the progress
 * matching in {@code AchievementRepository} stays stable and cloud-sync friendly;
 * only the UI layer localizes them.
 */
public final class AchievementLocalizer {

    private AchievementLocalizer() {}

    /** Localized display title for a canonical achievement key (falls back to the key itself). */
    public static String title(Context ctx, String canonical) {
        int res = titleRes(canonical);
        return res != 0 ? ctx.getString(res) : canonical;
    }

    /** Localized description for a canonical achievement key (falls back to the stored description). */
    public static String description(Context ctx, String canonical, String fallback) {
        int res = descRes(canonical);
        return res != 0 ? ctx.getString(res) : fallback;
    }

    public static int titleRes(String canonical) {
        if ("Week Warrior".equals(canonical)) return R.string.achievement_week_warrior;
        if ("Month Master".equals(canonical)) return R.string.achievement_month_master;
        if ("Century Club".equals(canonical)) return R.string.achievement_century_club;
        if ("Focus Novice".equals(canonical)) return R.string.achievement_focus_novice;
        if ("Focus Master".equals(canonical)) return R.string.achievement_focus_master;
        if ("Habit Collector".equals(canonical)) return R.string.achievement_habit_collector;
        return 0;
    }

    public static int descRes(String canonical) {
        if ("Week Warrior".equals(canonical)) return R.string.achievement_week_warrior_desc;
        if ("Month Master".equals(canonical)) return R.string.achievement_month_master_desc;
        if ("Century Club".equals(canonical)) return R.string.achievement_century_club_desc;
        if ("Focus Novice".equals(canonical)) return R.string.achievement_focus_novice_desc;
        if ("Focus Master".equals(canonical)) return R.string.achievement_focus_master_desc;
        if ("Habit Collector".equals(canonical)) return R.string.achievement_habit_collector_desc;
        return 0;
    }

    /** Premium-membership days awarded for unlocking this achievement (0 = no reward). */
    public static int rewardDays(String canonical) {
        if ("Week Warrior".equals(canonical)) return 3;    // 周冠军
        if ("Month Master".equals(canonical)) return 7;    // 月度大师
        if ("Century Club".equals(canonical)) return 30;   // 百天俱乐部
        if ("Focus Novice".equals(canonical)) return 1;    // 专注新手
        if ("Focus Master".equals(canonical)) return 3;    // 专注大师
        if ("Habit Collector".equals(canonical)) return 1; // 习惯收藏家（建满 3 个习惯领 1 天）
        return 0;
    }
}
