package com.fouu.habitflow.ui.focus;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.fouu.habitflow.R;
import com.fouu.habitflow.databinding.FragmentFocusBinding;
import com.fouu.habitflow.util.NotificationHelper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * FocusFragment - Pomodoro timer for deep work sessions.
 *
 * Features:
 * - 25-min focus / 5-min break cycles
 * - Circular progress indicator (M3 style)
 * - Session history
 * - Free: max 3 sessions/day
 * - Premium: unlimited + custom durations
 */
public class FocusFragment extends Fragment {

    private FragmentFocusBinding binding;
    private FocusViewModel viewModel;
    private FocusTimerViewModel timerVm; // Activity-scoped: survives Tab switches
    private CountDownTimer timer;
    private com.fouu.habitflow.util.PreferenceManager prefs;

    // Durations
    private static final long SHORT_BREAK = 5 * 60 * 1000L;
    private static final long LONG_BREAK = 15 * 60 * 1000L;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentFocusBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().getApplication()))
                .get(FocusViewModel.class);
        // Activity-scoped timer state survives Tab switches (Fragment recreation).
        timerVm = new ViewModelProvider(requireActivity()).get(FocusTimerViewModel.class);

        setupControls();
        observeViewModel();

        // Premium users can set a custom focus length; Free users stay at the fixed 25 min.
        prefs = com.fouu.habitflow.util.PreferenceManager.getInstance(requireContext());
        boolean premium = prefs.isPremium();
        int fm = premium ? prefs.getFocusDurationMin() : 25;
        timerVm.setFocusMinutes(fm);
        if (!timerVm.isInitialized()) {
            // First creation only: seed the countdown from the configured duration.
            // Subsequent Tab switches keep the running state instead of resetting.
            timerVm.setTimeRemaining((long) fm * 60 * 1000L);
            timerVm.setPhaseDuration((long) fm * 60 * 1000L);
            timerVm.setBreak(false);
            timerVm.setRunning(false);
            timerVm.setInitialized(true);
        }
        // Premium users can customize the idle timer; free users are sent to Settings.
        binding.tvTimer.setClickable(true);
        binding.tvTimer.setFocusable(true);
        binding.tvTimer.setOnClickListener(v -> {
            if (!prefs.isPremium()) {
                BottomNavigationView nav = requireActivity().findViewById(R.id.nav_view);
                if (nav != null) nav.setSelectedItemId(R.id.nav_settings);
            } else if (!timerVm.isRunning()) {
                showDurationPicker();
            }
        });

        // Restore UI from persisted timer state; do NOT reset on Tab switch.
        restoreTimerUi();
        if (timerVm.isRunning()) resumeTimer();
    }

    /** Premium: choose a custom focus length via a vertical NumberPicker dialog. */
    private void showDurationPicker() {
        final int min = 5, max = 240, step = 5;
        final int count = (max - min) / step + 1;
        final int[] values = new int[count];
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = min + i * step;
            labels[i] = getString(R.string.focus_duration_format, values[i]);
        }
        int currentIndex = Math.max(0, Math.min(count - 1, (timerVm.getFocusMinutes() - min) / step));

        android.widget.NumberPicker picker = new android.widget.NumberPicker(requireContext());
        picker.setMinValue(0);
        picker.setMaxValue(count - 1);
        picker.setValue(currentIndex);
        picker.setDisplayedValues(labels);
        picker.setWrapSelectorWheel(false);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.focus_duration_title)
                .setView(picker)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    timerVm.setFocusMinutes(values[picker.getValue()]);
                    prefs.setFocusDurationMin(timerVm.getFocusMinutes());
                    resetTimer(); // reflect the new length on the countdown
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Current focus length in millis (custom for Premium, fixed 25 for Free). */
    private long focusMs() {
        return (long) timerVm.getFocusMinutes() * 60 * 1000L;
    }

    private void setupControls() {
        // Start/Pause button
        binding.btnStartPause.setOnClickListener(v -> {
            if (timerVm.isRunning()) pauseTimer();
            else startTimer();
        });

        // Reset button
        binding.btnReset.setOnClickListener(v -> resetTimer());

        // Log an interruption during a focus session
        binding.btnInterrupt.setOnClickListener(v -> {
            timerVm.setInterruptions(timerVm.getInterruptions() + 1);
            updateInterruptUi();
        });

        // Session count info
        viewModel.getTodaySessionCount().observe(getViewLifecycleOwner(), count -> {
            binding.tvSessionCount.setText(getString(R.string.sessions_today, count));
            if (count >= 3 && !com.fouu.habitflow.util.PreferenceManager
                    .getInstance(requireContext()).isPremium()) {
                binding.tvLimitWarning.setVisibility(View.VISIBLE);
                binding.btnStartPause.setEnabled(false);
            }
        });
    }

    private void observeViewModel() {
        viewModel.getTotalFocusMinutes().observe(getViewLifecycleOwner(), mins -> {
            binding.tvTotalFocus.setText(getString(R.string.total_focus_minutes, mins));
        });
    }

    private void startTimer() {
        timerVm.setRunning(true);
        binding.btnStartPause.setText(R.string.pause);
        binding.btnStartPause.setIconResource(R.drawable.ic_pause);
        if (timer != null) timer.cancel();

        timer = new CountDownTimer(timerVm.getTimeRemaining(), 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timerVm.setTimeRemaining(millisUntilFinished);
                updateTimerDisplay();
                updateProgressCircle();
            }

            @Override
            public void onFinish() {
                onTimerComplete();
            }
        }.start();
        updateInterruptUi();
    }

    private void pauseTimer() {
        timerVm.setRunning(false);
        binding.btnStartPause.setText(R.string.start);
        binding.btnStartPause.setIconResource(R.drawable.ic_play);
        if (timer != null) timer.cancel();
        updateInterruptUi();
    }

    /** Resume ticking from the persisted remaining time after returning to this Tab. */
    private void resumeTimer() {
        if (timer != null) timer.cancel();
        timer = new CountDownTimer(timerVm.getTimeRemaining(), 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timerVm.setTimeRemaining(millisUntilFinished);
                updateTimerDisplay();
                updateProgressCircle();
            }

            @Override
            public void onFinish() {
                onTimerComplete();
            }
        }.start();
    }

    private void resetTimer() {
        pauseTimer();
        timerVm.setBreak(false);
        timerVm.setInterruptions(0); // abandon the current focus session's interruptions
        timerVm.setTimeRemaining(focusMs());
        timerVm.setPhaseDuration(focusMs());
        updateTimerDisplay();
        updateProgressCircle();
        updateInterruptUi();
    }

    private void onTimerComplete() {
        timerVm.setRunning(false);

        if (!timerVm.isBreak()) {
            // Focus session done → save actual elapsed minutes (not the hardcoded 25)
            int elapsedMin = Math.max(1, (int) Math.round((focusMs() - timerVm.getTimeRemaining()) / 60000.0));
            viewModel.saveSession(elapsedMin, true, "FOCUS", timerVm.getInterruptions());

            // Offer a break (short or long). The chosen type is recorded when the break ends.
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.focus_complete)
                    .setMessage(R.string.take_break_prompt)
                    .setPositiveButton(R.string.break_short, (d, w) -> startBreak(false))
                    .setNeutralButton(R.string.break_long, (d, w) -> startBreak(true))
                    .setNegativeButton(R.string.close, null)
                    .show();

        } else {
            // Break done → save it (short or long) and return to a fresh focus phase.
            long breakMs = timerVm.isLongBreak() ? LONG_BREAK : SHORT_BREAK;
            int breakMin = Math.max(1, (int) Math.round(breakMs / 60000.0));
            String type = timerVm.isLongBreak() ? "LONG_BREAK" : "SHORT_BREAK";
            viewModel.saveSession(breakMin, true, type, 0);

            timerVm.setBreak(false);
            timerVm.setLongBreak(false);
            timerVm.setInterruptions(0); // next focus session starts fresh
            timerVm.setTimeRemaining(focusMs());
            timerVm.setPhaseDuration(focusMs());
            updateInterruptUi();
            Toast.makeText(requireContext(), R.string.break_over, Toast.LENGTH_SHORT).show();
        }

        updateTimerDisplay();
    }

    /** Begin a break phase of the given length and refresh the UI (hide interruption controls). */
    private void startBreak(boolean longBreak) {
        timerVm.setBreak(true);
        timerVm.setLongBreak(longBreak);
        long dur = longBreak ? LONG_BREAK : SHORT_BREAK;
        timerVm.setTimeRemaining(dur);
        timerVm.setPhaseDuration(dur);
        updateInterruptUi();
        updateTimerDisplay();
        startTimer();
    }

    /** Show the interruption counter + "Log Interruption" button only during a running focus
     *  session; hide both while on a break or when idle. */
    private void updateInterruptUi() {
        if (timerVm.isBreak() || !timerVm.isRunning()) {
            binding.btnInterrupt.setVisibility(View.GONE);
            binding.tvInterruptions.setVisibility(timerVm.isBreak() ? View.GONE : View.VISIBLE);
        } else {
            binding.btnInterrupt.setVisibility(View.VISIBLE);
            binding.tvInterruptions.setVisibility(View.VISIBLE);
        }
        binding.tvInterruptions.setText(
                getString(R.string.interruptions_format, timerVm.getInterruptions()));
    }

    private void updateTimerDisplay() {
        long totalSeconds = timerVm.getTimeRemaining() / 1000;
        long mins = totalSeconds / 60;
        long secs = totalSeconds % 60;
        binding.tvTimer.setText(String.format(java.util.Locale.US, "%02d:%02d", mins, secs));

        if (timerVm.isBreak()) {
            binding.tvTimerLabel.setText(R.string.break_time);
        } else {
            binding.tvTimerLabel.setText(R.string.focus_time);
        }
    }

    private void updateProgressCircle() {
        long total = timerVm.isBreak() ? SHORT_BREAK : focusMs();
        float progress = 1f - ((float) timerVm.getTimeRemaining() / total);
        // CircularProgressIndicator uses setProgress(int) with 0-100 range
        binding.progressCircle.setProgress((int) (progress * 100f));
    }

    /** Repaint clock/progress/buttons from the persisted timer state (no reset). */
    private void restoreTimerUi() {
        if (timerVm.isRunning()) {
            binding.btnStartPause.setText(R.string.pause);
            binding.btnStartPause.setIconResource(R.drawable.ic_pause);
        } else {
            binding.btnStartPause.setText(R.string.start);
            binding.btnStartPause.setIconResource(R.drawable.ic_play);
        }
        updateTimerDisplay();
        updateProgressCircle();
        updateInterruptUi();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (timer != null) timer.cancel();
        binding = null;
    }
}
