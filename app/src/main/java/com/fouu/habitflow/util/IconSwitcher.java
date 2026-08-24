package com.fouu.habitflow.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.os.LocaleListCompat;

/**
 * Swaps the app's launcher icon between the default and the premium (members-only)
 * version by enabling/disabling the corresponding activity-alias in the manifest.
 *
 * Only toggles a component when its state actually needs to change, so calling this
 * repeatedly (e.g. every time the settings screen is opened) won't reshuffle the home
 * screen unnecessarily.
 */
public class IconSwitcher {

    private static final String ALIAS_DEFAULT =
            "com.fouu.habitflow.auth.AuthActivity.Default";
    private static final String ALIAS_PREMIUM =
            "com.fouu.habitflow.auth.AuthActivity.Premium";

    public static void applyPremiumIcon(Context context, boolean isPremium) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName def = new ComponentName(context, ALIAS_DEFAULT);
            ComponentName prem = new ComponentName(context, ALIAS_PREMIUM);

            int defState = isPremium
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            int premState = isPremium
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

            if (pm.getComponentEnabledSetting(def) != defState) {
                pm.setComponentEnabledSetting(def, defState, PackageManager.DONT_KILL_APP);
            }
            if (pm.getComponentEnabledSetting(prem) != premState) {
                pm.setComponentEnabledSetting(prem, premState, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception e) {
            // Swapping the launcher icon can make Google Play services re-validate the
            // package on some devices/ROMs and throw SecurityException. Never let that
            // crash the app — premium features themselves keep working regardless.
            android.util.Log.w("IconSwitcher", "Launcher icon swap skipped: " + e.getMessage());
        }
    }
}
