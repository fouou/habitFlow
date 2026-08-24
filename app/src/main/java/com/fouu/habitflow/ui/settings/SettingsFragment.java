package com.fouu.habitflow.ui.settings;

import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fouu.habitflow.BuildConfig;
import com.fouu.habitflow.R;
import com.fouu.habitflow.auth.AuthManager;
import com.fouu.habitflow.data.remote.SyncManager;
import com.google.firebase.auth.FirebaseUser;
import com.fouu.habitflow.billing.BillingManager;
import com.fouu.habitflow.databinding.FragmentSettingsBinding;
import com.fouu.habitflow.util.PreferenceManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * SettingsFragment - All app settings in one place.
 *
 * Features:
 * - Theme: System / Light / Dark + Dynamic Color toggle
 * - Language selection (35 locales)
 * - Premium upgrade
 * - Rate app (Google Play)
 * - Feedback (email)
 * - Join QQ group
 * - Privacy policy
 * - About
 * - Sign out
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private PreferenceManager prefs;
    private AuthManager authManager;
    private BillingManager billingManager;
    private SyncManager syncManager;
    private com.fouu.habitflow.ads.AdManager adManager;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> updateNotificationStatus());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = PreferenceManager.getInstance(requireContext());
        authManager = AuthManager.getInstance(requireContext());
        billingManager = BillingManager.getInstance(requireContext());
        syncManager = SyncManager.getInstance(requireContext());
        adManager = com.fouu.habitflow.ads.AdManager.getInstance(requireContext());

        setupThemeSection();
        setupPremiumSection();
        setupAboutSection();
        setupActionsSection();
        setupAccountSection();
        setupSyncStatus();
        setupNotificationPermission();
        updatePremiumUI();
    }

    // ===== Theme =====

    private void setupThemeSection() {
        // Theme mode chips
        int mode = prefs.getThemeMode();
        binding.chipSystem.setChecked(mode == 0);
        binding.chipLight.setChecked(mode == 1);
        binding.chipDark.setChecked(mode == 2);

        binding.chipSystem.setOnCheckedChangeListener((b, c) -> { if (c) setThemeMode(0); });
        binding.chipLight.setOnCheckedChangeListener((b, c) -> { if (c) setThemeMode(1); });
        binding.chipDark.setOnCheckedChangeListener((b, c) -> { if (c) setThemeMode(2); });

        // Dynamic color toggle
        binding.switchDynamicColor.setChecked(prefs.isDynamicColorEnabled());
        binding.switchDynamicColor.setOnCheckedChangeListener((b, checked) -> {
            prefs.setDynamicColorEnabled(checked);
            Toast.makeText(requireContext(), R.string.restart_to_apply, Toast.LENGTH_SHORT).show();
        });
    }

    private void setThemeMode(int mode) {
        prefs.setThemeMode(mode);
        switch (mode) {
            case 1: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
            case 2: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    // ===== Premium =====

    private void setupPremiumSection() {
        binding.btnUpgradeMonthly.setOnClickListener(v ->
                billingManager.launchPurchaseFlow(requireActivity(), BillingManager.SKU_PREMIUM_MONTHLY)
        );

        binding.btnUpgradeYearly.setOnClickListener(v ->
                billingManager.launchPurchaseFlow(requireActivity(), BillingManager.SKU_PREMIUM_YEARLY)
        );

        binding.btnRemoveAds.setOnClickListener(v ->
                billingManager.launchPurchaseFlow(requireActivity(), BillingManager.SKU_REMOVE_ADS)
        );

        // Watch a rewarded ad to unlock 1 day of Premium (free users).
        // showRewardedAd() auto-loads first if the ad isn't ready yet, so the button
        // never dead-locks on a slow/failed initial load.
        binding.btnRewardDay.setOnClickListener(v -> {
            if (adManager == null) {
                Toast.makeText(requireContext(), R.string.ad_load_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            adManager.showRewardedAd(requireActivity(), amount -> {
                prefs.grantPremiumDays(1);
                updatePremiumUI();
                Toast.makeText(requireContext(), R.string.reward_day_unlocked, Toast.LENGTH_SHORT).show();
            });
        });

        binding.btnPremiumInfo.setOnClickListener(v -> showPremiumFeatures());
    }

    private void showPremiumFeatures() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.premium_features_title)
                .setMessage(R.string.premium_features_body)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void updatePremiumUI() {
        boolean isPremium = prefs.isPremium();
        // The premium card uses ONE colorful gradient (blue→purple) for BOTH states
        // (unlocked and locked). White text/buttons keep it legible on the vivid background.
        binding.premiumCardContent.setBackgroundResource(R.drawable.premium_gradient_colorful);

        if (isPremium) {
            // Member: crown, VIP badge, perks, white text.
            binding.ivPremiumCrown.setVisibility(View.VISIBLE);
            binding.tvPremiumVip.setVisibility(View.VISIBLE);
            binding.tvPremiumTitle.setText(R.string.premium_member_title);
            binding.premiumStatus.setText(R.string.premium_active);
            binding.tvPremiumPerks.setVisibility(View.VISIBLE);
            // Already a member → no point showing upgrade CTAs.
            binding.upgradeButtons.setVisibility(View.GONE);
        } else {
            // Invite-to-upgrade: no crown/VIP/perks; the upgrade CTAs are shown.
            binding.ivPremiumCrown.setVisibility(View.GONE);
            binding.tvPremiumVip.setVisibility(View.GONE);
            binding.tvPremiumTitle.setText(R.string.premium_upgrade_title);
            binding.premiumStatus.setText(R.string.premium_inactive);
            binding.tvPremiumPerks.setVisibility(View.GONE);
            binding.upgradeButtons.setVisibility(View.VISIBLE);
        }
        // The rewarded-ad "1 day of Premium" button is only meaningful for free users.
        binding.btnRewardDay.setVisibility(isPremium ? View.GONE : View.VISIBLE);
    }

    // ===== About =====

    private void setupAboutSection() {
        binding.tvVersion.setText(BuildConfig.VERSION_NAME);

        binding.tvPrivacyPolicy.setOnClickListener(v -> openUrl("https://sites.google.com/view/fouu-habit-flow"));
        binding.tvTerms.setOnClickListener(v -> openUrl("https://sites.google.com/view/fouu-habit-flow"));
    }

    // ===== Notification permission =====

    private void setupNotificationPermission() {
        updateNotificationStatus();
        binding.btnNotification.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Pre-13: notifications are granted at install; the OS has no runtime toggle.
                // Open the app's system settings page so the user can review/disable it.
                openAppSettings();
                return;
            }
            if (ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                // Already granted — can't revoke from inside the app; point to system settings.
                Toast.makeText(requireContext(), R.string.notification_already_on, Toast.LENGTH_LONG).show();
                openAppSettings();
            } else {
                // Not granted yet — ask for it.
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            }
        });
    }

    /** Reflect the current notification-permission state in the Settings row. */
    private void updateNotificationStatus() {
        boolean granted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted = ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        } else {
            granted = true; // pre-13: always allowed
        }
        binding.tvNotificationStatus.setText(granted ? R.string.notification_on : R.string.notification_off);
        binding.tvNotificationStatus.setTextColor(
                androidx.core.content.ContextCompat.getColor(requireContext(),
                        granted ? R.color.habit_green : R.color.error));
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ===== Action Buttons =====

    private void setupActionsSection() {
        // Rate app → Google Play
        binding.btnRateApp.setOnClickListener(v -> {
            String packageName = requireContext().getPackageName();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + packageName)));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
            }
        });

        // Feedback → Email
        binding.btnFeedback.setOnClickListener(v -> {
            Intent email = new Intent(Intent.ACTION_SENDTO);
            email.setData(Uri.parse("mailto:sizhengxu666@gmail.com"));
            email.putExtra(Intent.EXTRA_SUBJECT, "HabitFlow Feedback");
            try {
                startActivity(Intent.createChooser(email, getString(R.string.send_feedback)));
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.no_email_app, Toast.LENGTH_SHORT).show();
            }
        });

        // Join QQ Group
        binding.btnJoinQq.setOnClickListener(v -> {
            // 优先：官方加群卡片（需要 key）
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DuDZN_S_fvfeCAmmwXVUbZItWRguGrI37"));
            try {
                startActivity(intent);
            } catch (Exception e) {
                // 降级：群资料页（不需要 key）
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1009749374&card_type=group")));
                } catch (Exception ex) {
                    // 都没装 QQ：打开网页版
                    openUrl("https://qm.qq.com/cgi-bin/qm/qr?k=uDZN_S_fvfeCAmmwXVUbZItWRguGrI37");
                }
            }
        });

        // Add home-screen widget
        binding.btnAddWidget.setOnClickListener(v -> pinWidget());

        // Force full cloud sync now
        binding.btnSyncNow.setOnClickListener(v -> {
            if (!authManager.isSignedIn()) {
                Toast.makeText(requireContext(), R.string.cloud_sync_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            binding.tvLastSync.setText(R.string.syncing);
            binding.tvLastSync.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.habit_green));
            syncManager.syncNowForceAll();
        });

        // Sign out logic is handled in setupAccountSection()
    }

    /** Offer to pin the habit widget onto the home screen (API 26+), or instruct the user
     *  how to add it manually on launchers that don't support direct pinning. */
    private void pinWidget() {
        Context ctx = requireContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            ComponentName provider = new ComponentName(ctx, com.fouu.habitflow.widget.HabitWidgetProvider.class);
            if (awm.isRequestPinAppWidgetSupported()) {
                awm.requestPinAppWidget(provider, null, null);
                return;
            }
        }
        Toast.makeText(ctx, R.string.widget_pin_manual, Toast.LENGTH_LONG).show();
    }

    // ===== Account =====

    private void setupAccountSection() {
        // Sign in / Sign up → launch AuthActivity
        Intent authIntent = new Intent(requireContext(), com.fouu.habitflow.auth.AuthActivity.class);

        binding.btnSignIn.setOnClickListener(v -> {
            authIntent.putExtra("mode", "sign_in");
            startActivity(authIntent);
        });

        binding.btnSignUp.setOnClickListener(v -> {
            authIntent.putExtra("mode", "sign_up");
            startActivity(authIntent);
        });

        // Sign out
        binding.btnSignOut.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sign_out)
                    .setMessage(R.string.sign_out_confirm)
                    .setPositiveButton(R.string.yes, (d, w) -> {
                        // Delete cloud backup (and then sign out) before refreshing the UI.
                        authManager.signOut(() -> {
                            // Clear "skip login" so the next launch shows the auth screen again
                            prefs.setSkipLogin(false);
                            Snackbar.make(binding.getRoot(), R.string.signed_out, Snackbar.LENGTH_SHORT).show();
                            updatePremiumUI();
                            updateAccountUI();
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        // Delete account: tapping the whole Danger Zone card wipes local + cloud + auth user
        // (irreversible). The inner button was removed; the card itself is the trigger.
        binding.dangerZoneCard.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.clear_data_confirm_title)
                    .setMessage(R.string.clear_data_confirm_message)
                    .setPositiveButton(R.string.yes, (d, w) -> {
                        authManager.deleteAccount(() -> {
                            prefs.setSkipLogin(false);
                            Snackbar.make(binding.getRoot(), R.string.account_deleted, Snackbar.LENGTH_SHORT).show();
                            // Everything is wiped locally; return to the auth/launch screen.
                            Intent intent = new Intent(requireContext(), com.fouu.habitflow.auth.AuthActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                            requireActivity().finishAffinity();
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        updateAccountUI();
    }

    // ===== Cloud Sync =====

    private void setupSyncStatus() {
        // Cloud sync runs automatically on every local change and on login/startup,
        // so there are no manual controls — just keep the status text fresh.
        syncManager.setSyncCompleteCallback(() -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(this::updateCloudSyncUI);
            }
        });
        updateCloudSyncUI();
    }

    private void updateCloudSyncUI() {
        if (authManager.isSignedIn()) {
            binding.cloudSyncStatus.setText(R.string.cloud_sync_enabled);
            binding.cloudSyncStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.habit_green));
        } else {
            binding.cloudSyncStatus.setText(R.string.cloud_sync_disabled);
            binding.cloudSyncStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error));
        }
        // Last sync time / error
        String lastErr = prefs.getLastSyncError();
        long lastSync = prefs.getLastSyncTime();
        if (!lastErr.isEmpty()) {
            // Show the REAL error (e.g. Firestore PERMISSION_DENIED / network), not a canned
            // "sync failed" string, so the user can see exactly why the upload didn't go through.
            binding.tvLastSync.setText(getString(R.string.sync_failed_detail, lastErr));
            binding.tvLastSync.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error));
        } else if (lastSync > 0) {
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance();
            binding.tvLastSync.setText(getString(R.string.last_synced, df.format(new java.util.Date(lastSync))));
            binding.tvLastSync.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.habit_green));
        } else {
            binding.tvLastSync.setText(R.string.never_synced);
            binding.tvLastSync.setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_surface_variant));
        }
    }

    private void updateAccountUI() {
        boolean signedIn = authManager.isSignedIn();
        if (signedIn) {
            binding.accountSignedIn.setVisibility(View.VISIBLE);
            binding.accountSignedOut.setVisibility(View.GONE);
            FirebaseUser user = authManager.getCurrentUser();
            String email = user != null && user.getEmail() != null ? user.getEmail() : prefs.getUserEmail();
            binding.tvAccountEmail.setText(email != null && !email.isEmpty() ? email : getString(R.string.signed_in));
        } else {
            binding.accountSignedIn.setVisibility(View.GONE);
            binding.accountSignedOut.setVisibility(View.VISIBLE);
        }
        // Sign-out now lives inside the account card (shown only when signed in).
        // The Danger Zone card (tap to clear data / delete account) is gated on being signed in.
        binding.btnSignOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.dangerZoneCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        setAccountStatus(signedIn);
        updateCloudSyncUI();
    }

    /** Show a colored login-status pill (green "已登录" / grey "未登录"). */
    private void setAccountStatus(boolean signedIn) {
        Context ctx = requireContext();
        binding.tvAccountStatus.setBackgroundResource(R.drawable.bg_status_pill);
        if (signedIn) {
            binding.tvAccountStatus.getBackground().setTint(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.habit_green));
            binding.tvAccountStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, android.R.color.white));
            binding.tvAccountStatus.setText(R.string.account_status_signed_in);
        } else {
            // Signed-out: low-emphasis pill — light translucent fill with a muted text color
            // (NOT a dark fill with a dark text, which renders as an unreadable black blob).
            binding.tvAccountStatus.getBackground().setTint(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.surface_variant));
            binding.tvAccountStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.on_surface_variant));
            binding.tvAccountStatus.setText(R.string.account_status_signed_out);
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        syncManager.setSyncCompleteCallback(null);
        super.onDestroyView();
        binding = null;
    }
}
