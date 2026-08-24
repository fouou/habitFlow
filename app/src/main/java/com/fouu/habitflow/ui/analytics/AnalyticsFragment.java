package com.fouu.habitflow.ui.analytics;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.fouu.habitflow.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.fouu.habitflow.ads.AdManager;
import com.fouu.habitflow.databinding.FragmentAnalyticsBinding;
import com.fouu.habitflow.util.PreferenceManager;

/**
 * AnalyticsFragment - Charts, statistics, and AI insights.
 *
 * Free tier: Basic weekly bar chart + 7-day completion rate.
 * Premium: AI-generated insights, mood correlation, monthly trends,
 *          best time-of-day analysis, personalized recommendations.
 */
public class AnalyticsFragment extends Fragment {

    private FragmentAnalyticsBinding binding;
    private AnalyticsViewModel viewModel;
    private AdManager adManager;
    // One independent animator PER progress bar. A single shared field caused the 2nd animateRate()
    // call to cancel() the 1st bar's animation mid-flight, leaving "today" stuck on its old value
    // (the "delayed / not updating" bug). Keyed by the ProgressBar so each bar animates on its own.
    private final java.util.Map<android.widget.ProgressBar, android.animation.ValueAnimator> rateAnims =
            new java.util.HashMap<>();
    // Default (theme) text color of the percentage labels, captured on first paint so we can
    // restore it when the rate is > 50% (the "normal" color branch).
    private int normalRateColor = -1;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAnalyticsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().getApplication()))
                .get(AnalyticsViewModel.class);

        try {
            adManager = AdManager.getInstance(requireContext());
        } catch (Exception e) {
            adManager = null;
            Log.e("AnalyticsFragment", "AdManager init failed: " + e.getMessage());
        }

        setupPremiumGate();
        observeData();
        setupRefreshButton();
        setupRateInfo();
        setupRangeControls();
    }

    /** Show a dialog explaining how every stat on this page is computed. */
    private void setupRateInfo() {
        binding.btnRateInfo.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.rate_info_title)
                    .setMessage(R.string.rate_info_content)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void setupPremiumGate() {
        boolean isPremium = PreferenceManager.getInstance(requireContext()).isPremium();

        // Free users see locked AI insights section
        binding.premiumOverlay.setVisibility(isPremium ? View.GONE : View.VISIBLE);

        // Premium users: reveal the AI card (its refresh button lives inside it) and
        // auto-fetch insights. Without this, the card stays GONE forever — the only
        // trigger (the in-card button) is unreachable, so insights never appear.
        if (isPremium) {
            binding.aiInsightCard.setVisibility(View.VISIBLE);
            // Auto-generate at most once per day. The ViewModel already seeds the card with
            // the cached insight (if any) so it appears instantly without re-hitting Gemini.
            // Only fetch when today has no cached insight yet (avoids burning the free quota
            // on every tab open / app restart).
            if (PreferenceManager.getInstance(requireContext()).isAiInsightStale()) {
                viewModel.generateAiInsights();
            }
        }

        binding.premiumOverlay.setOnClickListener(v -> {
            Toast.makeText(requireContext(), R.string.upgrade_for_ai_insights, Toast.LENGTH_LONG).show();
        });

        binding.btnUnlockAi.setOnClickListener(v -> {
            // Go to the settings tab via the bottom navigation itself so the tab
            // selection and NavController back stack stay in sync (otherwise tapping
            // other tabs afterwards becomes unresponsive). Fall back to direct
            // navigation only if the bottom nav can't be found.
            if (getActivity() != null) {
                BottomNavigationView nav = getActivity().findViewById(R.id.nav_view);
                if (nav != null) {
                    nav.setSelectedItemId(R.id.nav_settings);
                    return;
                }
            }
            Navigation.findNavController(requireView()).navigate(R.id.nav_settings);
        });

        // Same reward as Settings: watching the full ad grants one day of Premium.
        // showRewardedAd() auto-loads first if not ready, so the button never dead-locks.
        binding.btnUnlockAd.setOnClickListener(v -> {
            if (adManager == null) {
                Toast.makeText(requireContext(), R.string.ad_load_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            adManager.showRewardedAd(requireActivity(), amount -> {
                PreferenceManager.getInstance(requireContext()).grantPremiumDays(1);
                if (binding == null) return;
                binding.premiumOverlay.setVisibility(View.GONE);
                binding.aiInsightCard.setVisibility(View.VISIBLE);
                viewModel.generateAiInsights();
                Toast.makeText(requireContext(), R.string.reward_day_unlocked, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void observeData() {
        // All habit/stats UI is painted from a SINGLE deterministic callback (OnLoadedListener)
        // fired by the ViewModel on the main thread after each loadData() finishes. This avoids
        // LiveData observer-registration / sticky-replay timing quirks: under NavController the
        // Analytics Fragment is replaced (rebuilt) on every tab switch, so relying on LiveData
        // observers to surface the fresh value could lag one screen behind. The callback always
        // carries the LATEST computed snapshot, painted the instant it is ready.
        viewModel.setOnLoadedListener(snapshot -> paintSnapshot(snapshot));

        // AI Insights (premium only) — driven by its own LiveData (network fetch, independent
        // of the local stats reload). Bound to `this` so it survives tab re-entry.
        androidx.lifecycle.LifecycleOwner owner = this;
        viewModel.getAiInsights().observe(owner, insights -> {
            if (binding == null) return;
            if (insights != null && !insights.isEmpty()
                    && PreferenceManager.getInstance(requireContext()).isPremium()) {
                binding.tvAiInsight.setText(insights);
                binding.aiInsightCard.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Paint every local stat from one freshly-computed snapshot. Guarded for the window where
     *  the view is destroyed but the Fragment (and its listener) still lives. */
    private void paintSnapshot(AnalyticsViewModel.AnalyticsSnapshot s) {
        if (binding == null || s == null) return;
        binding.weeklyChart.setData(s.weekly);
        binding.tvTotalHabits.setText(String.valueOf(s.totalHabits));
        animateRate(s.todayRate, binding.progressToday, binding.tvTodayRate);
        animateRate(s.overallRate, binding.progressOverall, binding.tvOverallRate);
        binding.tvCurrentStreak.setText(String.valueOf(s.currentStreak));
        binding.tvBestStreak.setText(String.valueOf(s.bestStreak));
    }

    /** Grow the progress bar + percentage label from 0 to the target every time the page is
     *  painted, so entering Analytics always shows the bar "filling up". (The previous
     *  "animate from current value + skip if equal" logic meant the second loadData()
     *  callback — which fires right after the first on tab re-entry — hit the equal case and
     *  skipped the animation, so the bar just appeared at its final value.) */
    private void animateRate(float rate, ProgressBar bar, TextView label) {
        int targetPct = (int) Math.min(100f, Math.max(0f, rate * 100));
        // Color rule: completion rate <= 50% is "low" (red), > 50% is "normal" (theme gradient).
        // Applied to BOTH the bar drawable and the percentage label.
        if (normalRateColor == -1) normalRateColor = label.getCurrentTextColor();
        boolean low = targetPct <= 50;
        bar.setProgressDrawable(ContextCompat.getDrawable(bar.getContext(),
                low ? R.drawable.progress_bar_red : R.drawable.progress_bar_gradient));
        label.setTextColor(low
                ? ContextCompat.getColor(bar.getContext(), R.color.error)
                : normalRateColor);
        if (targetPct == 0) {
            bar.setProgress(0);
            label.setText("0%");
            return;
        }
        // Cancel only THIS bar's previous animator; never touch the other bar's.
        android.animation.ValueAnimator prev = rateAnims.get(bar);
        if (prev != null) prev.cancel();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, targetPct);
        a.setDuration(350);
        a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        a.addUpdateListener(anim -> {
            int p = (int) anim.getAnimatedValue();
            bar.setProgress(p);
            label.setText(String.format(java.util.Locale.US, "%d%%", p));
        });
        rateAnims.put(bar, a);
        a.start();
    }

    private void setupRefreshButton() {
        binding.btnRefreshInsights.setOnClickListener(v -> {
            viewModel.generateAiInsights();
        });
    }

    /** Week/Month toggle + date picker. The chart reflects the chosen view + anchor date. */
    private void setupRangeControls() {
        // Initialize the toggle to the ViewModel's current mode.
        binding.segRange.check(viewModel.getViewMode() == AnalyticsViewModel.MODE_MONTH
                ? R.id.btn_month : R.id.btn_week);
        updateRangeButtonText();

        binding.segRange.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            int mode = checkedId == R.id.btn_month
                    ? AnalyticsViewModel.MODE_MONTH : AnalyticsViewModel.MODE_WEEK;
            viewModel.setViewMode(mode);
            updateRangeButtonText();
        });

        binding.btnPickDate.setOnClickListener(v -> openDatePicker());
    }

    /** Open a Material date picker; on confirm, set the anchor date and refresh the title. */
    private void openDatePicker() {
        com.google.android.material.datepicker.MaterialDatePicker<Long> picker =
                com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                        .setTitleText(getString(R.string.select_date))
                        .setSelection(viewModel.getAnchorMillis())
                        .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            viewModel.setAnchorDate(selection);
            updateRangeButtonText();
        });
        picker.show(getChildFragmentManager(), "analytics_date_picker");
    }

    /** Show a human-readable label for the current view + anchor on the date button. */
    private void updateRangeButtonText() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(viewModel.getAnchorMillis());
        java.util.Calendar now = java.util.Calendar.getInstance();
        boolean isThis = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                && cal.get(java.util.Calendar.MONTH) == now.get(java.util.Calendar.MONTH);

        if (viewModel.getViewMode() == AnalyticsViewModel.MODE_MONTH) {
            if (isThis) {
                binding.btnPickDate.setText(getString(R.string.this_month));
            } else {
                binding.btnPickDate.setText(new java.text.SimpleDateFormat("yyyy年M月",
                        java.util.Locale.CHINESE).format(cal.getTime()));
            }
        } else {
            // Show the Mon–Sun range of the anchor's week.
            int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int daysSinceMonday = (dow + 5) % 7;
            cal.add(java.util.Calendar.DAY_OF_YEAR, -daysSinceMonday);
            java.util.Date weekStart = cal.getTime();
            cal.add(java.util.Calendar.DAY_OF_YEAR, 6);
            java.util.Date weekEnd = cal.getTime();
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("M/d", java.util.Locale.US);
            String range = fmt.format(weekStart) + " – " + fmt.format(weekEnd);
            if (isThisWeek(weekStart, weekEnd)) {
                binding.btnPickDate.setText(getString(R.string.this_week));
            } else {
                binding.btnPickDate.setText(range);
            }
        }
    }

    private boolean isThisWeek(java.util.Date weekStart, java.util.Date weekEnd) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        now.set(java.util.Calendar.HOUR_OF_DAY, 0); now.set(java.util.Calendar.MINUTE, 0);
        now.set(java.util.Calendar.SECOND, 0); now.set(java.util.Calendar.MILLISECOND, 0);
        long today = now.getTimeInMillis();
        return weekStart.getTime() <= today && today <= weekEnd.getTime();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Always recompute when the tab is shown, so check-offs made on the Habits page are
        // reflected here immediately. The completion-rate observer paints the newest value the
        // instant it arrives (no intro animation, no stale hold), so the bar is never "one beat
        // behind". The bar chart skips re-animating when its targets are unchanged.
        if (viewModel != null) viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        for (android.animation.ValueAnimator a : rateAnims.values()) a.cancel();
        rateAnims.clear();
        super.onDestroyView();
        binding = null;
    }
}
