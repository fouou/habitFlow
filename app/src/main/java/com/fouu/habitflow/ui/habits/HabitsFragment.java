package com.fouu.habitflow.ui.habits;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.databinding.FragmentHabitsBinding;
import com.fouu.habitflow.util.PreferenceManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Calendar;

/**
 * HabitsFragment - Main screen showing all active habits.
 *
 * Features:
 * - M3 card list with streak indicators
 * - Pull-to-refresh
 * - Quick add FAB
 * - Tap to toggle today's completion
 * - Long press for edit/delete
 * - Ad banner at bottom (free users only)
 */
public class HabitsFragment extends Fragment {

    private FragmentHabitsBinding binding;
    private HabitsViewModel viewModel;
    private HabitAdapter adapter;
    private BroadcastReceiver dataChangedReceiver;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Register the runtime notification-permission launcher up front (must happen before
        // onStart). On Android 13+ POST_NOTIFICATIONS is a dangerous permission and the system
        // suppresses every notify() unless the user grants it — that is why reminders "do nothing".
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { /* scheduling already done; this just unblocks notify() */ });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHabitsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    /** Ask for POST_NOTIFICATIONS at runtime if we don't already have it (Android 13+). */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().getApplication()))
                .get(HabitsViewModel.class);

        setupRecyclerView();
        setupFab();
        setupSwipeRefresh();
        observeHabits();
        observeActiveCount();
        handleHighlightIntent();
        registerDataChangedReceiver();
    }

    /** Listen for the cross-process broadcast fired when the home-screen widget toggles a habit.
     *  The widget runs in the launcher process and writes the DB directly, which Room's
     *  per-process invalidation does not see — so we re-query on this signal. */
    private void registerDataChangedReceiver() {
        dataChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                if ("com.fouu.habitflow.ACTION_HABIT_DATA_CHANGED".equals(intent.getAction())) {
                    if (viewModel != null) viewModel.refresh();
                }
            }
        };
        IntentFilter filter = new IntentFilter("com.fouu.habitflow.ACTION_HABIT_DATA_CHANGED");
        // API33+ requires an explicit export flag. We only listen for our own in-app broadcast
        // (widget process -> app process), so NOT_EXPORTED is correct and safer.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(dataChangedReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(dataChangedReceiver, filter);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // The widget runs in the launcher process and writes the DB directly; if a toggle
        // happened while this fragment was backgrounded (and the real-time broadcast was
        // missed), a persistent dirty flag lets us catch up as soon as we return to foreground.
        com.fouu.habitflow.util.PreferenceManager prefs =
                com.fouu.habitflow.util.PreferenceManager.getInstance(requireContext());
        if (prefs.isHabitDataDirty()) {
            if (viewModel != null) viewModel.refresh();
            prefs.setHabitDataDirty(false);
        }
    }

    private void setupRecyclerView() {
        adapter = new HabitAdapter(
                new HabitAdapter.OnHabitToggleListener() {
                    @Override
                    public void onCheckIn(Habit habit) {
                        // Direct check-in: no forced popup. Streak celebration still fires.
                        viewModel.getOverallStreakAsync(before -> {
                            viewModel.checkInHabit(habit);
                            viewModel.getOverallStreakAsync(after -> maybeCelebrate(before, after));
                        });
                    }
                    @Override
                    public void onUndo(Habit habit) {
                        viewModel.uncheckHabit(habit);
                    }
                },
                habit -> showEditDialog(habit),
                habit -> showDeleteDialog(habit)

        );

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerView.setAdapter(adapter);
    }

    /** Fire full-screen confetti when the overall streak crosses a milestone. */
    private void maybeCelebrate(int before, int after) {
        if (after <= before) return;
        int[] milestones = {7, 14, 21, 30, 50, 100, 200, 365};
        for (int m : milestones) {
            if (before < m && after >= m) {
                if (requireActivity() instanceof com.fouu.habitflow.ui.main.MainActivity) {
                    com.fouu.habitflow.ui.main.MainActivity main =
                            (com.fouu.habitflow.ui.main.MainActivity) requireActivity();
                    main.celebrateStreak();
                }
                Toast.makeText(requireContext(),
                        getString(R.string.streak_milestone, after), Toast.LENGTH_SHORT).show();
                break;
            }
        }
    }

    private void setupFab() {
        binding.fabAddHabit.setOnClickListener(v -> showAddHabitDialog());
    }

    private void setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener(() -> {
            viewModel.refresh();
            binding.swipeRefresh.setRefreshing(false);
        });
    }

    private void observeHabits() {
        viewModel.getHabits().observe(getViewLifecycleOwner(), habits -> {
            if (habits == null) habits = new java.util.ArrayList<>();
            adapter.submitList(habits);
            binding.emptyState.setVisibility(habits.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void observeActiveCount() {
        viewModel.getActiveCountLive().observe(getViewLifecycleOwner(), count -> {
            // cached for FAB free-tier limit check; no DB access on main thread
        });
    }

    private void handleHighlightIntent() {
        Bundle args = getArguments();
        if (args != null && args.containsKey("highlight_habit_id")) {
            int habitId = args.getInt("highlight_habit_id");
            // Scroll to and highlight the habit
            binding.recyclerView.post(() -> adapter.highlightHabit(habitId));
        }
    }

    // ===== Add/Edit Dialog =====

    private void showAddHabitDialog() {
        PreferenceManager prefs = PreferenceManager.getInstance(requireContext());

        // Check the free-tier limit against the REAL active count (off the main thread),
        // so adding/deleting habits is always reflected and the 3-habit cap can't be
        // bypassed with a stale cached count.
        viewModel.getActiveHabitCountAsync(count -> {
            if (count >= prefs.getHabitLimit() && !prefs.isPremium()) {
                showPremiumPrompt();
                return;
            }
            showHabitDialog(null);
        });
    }

    private void showEditDialog(Habit habit) {
        showHabitDialog(habit);
    }

    /**
     * Unified dialog for add/edit habit.
     * Uses M3 MaterialAlertDialog with custom view.
     */
    private void showHabitDialog(Habit existing) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_habit_edit, null);

        TextInputEditText etName = dialogView.findViewById(R.id.et_habit_name);
        TextInputEditText etDesc = dialogView.findViewById(R.id.et_habit_desc);
        ChipGroup chipGroup = dialogView.findViewById(R.id.chip_group_frequency);
        Chip chipDaily = dialogView.findViewById(R.id.chip_daily);
        Chip chipWeekdays = dialogView.findViewById(R.id.chip_weekdays);
        Chip chipWeekly = dialogView.findViewById(R.id.chip_weekly);

        // Daily target-count stepper
        TextView btnTargetMinus = dialogView.findViewById(R.id.btn_target_minus);
        TextView btnTargetPlus = dialogView.findViewById(R.id.btn_target_plus);
        TextView tvTargetValue = dialogView.findViewById(R.id.tv_target_value);

        // Reminder controls
        MaterialSwitch switchReminder = dialogView.findViewById(R.id.switch_reminder);
        MaterialButton btnReminderTime = dialogView.findViewById(R.id.btn_reminder_time);

        boolean isEdit = existing != null;
        if (isEdit) {
            etName.setText(existing.getName());
            etDesc.setText(existing.getDescription());
            // Restore the saved frequency selection via ChipGroup.check() so the
            // single-selection state stays consistent. Calling setChecked() directly on a
            // Chip inside a singleSelection ChipGroup desyncs the group's internal state,
            // which made other chips intermittently unselectable.
            String savedFreq = existing.getFrequency();
            if (com.fouu.habitflow.util.FrequencyUtil.WEEKDAYS.equals(savedFreq)) {
                chipGroup.check(R.id.chip_weekdays);
            } else if (com.fouu.habitflow.util.FrequencyUtil.WEEKLY.equals(savedFreq)) {
                chipGroup.check(R.id.chip_weekly);
            } else {
                chipGroup.check(R.id.chip_daily);
            }
        } else {
            // New habit defaults to daily.
            chipGroup.check(R.id.chip_daily);
        }

        // Daily target count (times per day). Defaults to 1 for new habits.
        final int[] targetCount = { isEdit && existing != null ? Math.max(1, existing.getTargetCount()) : 1 };
        tvTargetValue.setText(String.valueOf(targetCount[0]));
        btnTargetMinus.setOnClickListener(v -> {
            if (targetCount[0] > 1) {
                targetCount[0]--;
                tvTargetValue.setText(String.valueOf(targetCount[0]));
            }
        });
        btnTargetPlus.setOnClickListener(v -> {
            if (targetCount[0] < 10) {
                targetCount[0]++;
                tvTargetValue.setText(String.valueOf(targetCount[0]));
            }
        });

        // Reminder state (default: enabled at 20:00 so testers see it quickly)
        final boolean[] reminderOn = { !isEdit || (existing != null && existing.isReminderEnabled()) };
        final int[] reminderHour = { isEdit && existing != null && existing.getReminderTime() > 0
                ? (int) (existing.getReminderTime() / 3600000) : 20 };
        final int[] reminderMinute = { isEdit && existing != null && existing.getReminderTime() > 0
                ? (int) ((existing.getReminderTime() % 3600000) / 60000) : 0 };

        switchReminder.setChecked(reminderOn[0]);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        btnReminderTime.setText(getString(R.string.reminder_time) + ": " + sdf.format(
                new java.util.Date(0, 0, 0, reminderHour[0], reminderMinute[0])));
        btnReminderTime.setEnabled(reminderOn[0]);

        switchReminder.setOnCheckedChangeListener((button, checked) -> {
            reminderOn[0] = checked;
            btnReminderTime.setEnabled(checked);
            // Turning reminders on is the natural moment to ask for exact-alarm access.
            if (checked) maybePromptExactAlarmPermission();
            // And to ask for notification access so the reminder actually shows up.
            if (checked) maybeRequestNotificationPermission();
        });

        btnReminderTime.setOnClickListener(v -> {
            android.app.TimePickerDialog tpd = new android.app.TimePickerDialog(
                    requireContext(), (view, hourOfDay, minute) -> {
                        reminderHour[0] = hourOfDay;
                        reminderMinute[0] = minute;
                        btnReminderTime.setText(getString(R.string.reminder_time) + ": " + sdf.format(
                                new java.util.Date(0, 0, 0, hourOfDay, minute)));
                    }, reminderHour[0], reminderMinute[0], true);
            tpd.show();
        });

        // Build the color swatches; the chosen color is written back via selectedColor[0].
        final String[] selectedColor = new String[1];
        setupColorPalette(dialogView, existing, selectedColor);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(isEdit ? R.string.edit_habit : R.string.add_habit)
                .setView(dialogView)
                .setPositiveButton(isEdit ? R.string.save : R.string.add, (dialog, which) -> {
                    String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                    String desc = etDesc.getText() != null ? etDesc.getText().toString().trim() : "";

                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.habit_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // IMPORTANT: never mutate `existing` directly. It is the same object the
                    // RecyclerView currently holds, so editing it in place makes DiffUtil see
                    // old == new (frequency/color already changed) and skip the rebind — the
                    // card then keeps showing the previous frequency. Always edit a detached copy.
                    Habit habit = isEdit ? copyOf(existing) : new Habit();
                    habit.setName(name);
                    habit.setDescription(desc);
                    // Persist the selected frequency
                    String frequency;
                    if (chipWeekdays.isChecked()) {
                        frequency = com.fouu.habitflow.util.FrequencyUtil.WEEKDAYS;
                    } else if (chipWeekly.isChecked()) {
                        frequency = com.fouu.habitflow.util.FrequencyUtil.WEEKLY;
                    } else {
                        frequency = com.fouu.habitflow.util.FrequencyUtil.DAILY;
                    }
                    habit.setFrequency(frequency);
                    // Persist the daily target count (times per day).
                    habit.setTargetCount(targetCount[0]);
                    // Persist the chosen accent color (drives the card + chips).
                    habit.setColorHex(selectedColor[0]);

                    // Persist reminder settings
                    habit.setReminderEnabled(reminderOn[0]);
                    habit.setReminderTime(reminderHour[0] * 3600000L + reminderMinute[0] * 60000L);

                    // If reminders are on, make sure we can actually post the notification.
                    if (reminderOn[0]) maybeRequestNotificationPermission();

                    com.fouu.habitflow.util.NotificationHelper helper =
                            new com.fouu.habitflow.util.NotificationHelper(requireContext());

                    if (isEdit) {
                        // Column-only write so a concurrent streak recomputation can't
                        // overwrite the frequency/color the user just picked.
                        viewModel.updateHabitFromEditor(habit);
                        helper.scheduleHabitReminder(habit);
                    } else {
                        // Schedule only AFTER the row is inserted and Room has assigned the
                        // real id — scheduling with the default id 0 would make the receiver
                        // look up habit_id 0 (no such habit) and silently drop the reminder.
                        viewModel.addHabit(habit, id -> helper.scheduleHabitReminder(habit));
                    }
                })
                .setNegativeButton(R.string.cancel, null);

        if (isEdit) {
            builder.setNeutralButton(R.string.delete, (dialog, which) -> showDeleteDialog(existing));
        }

        builder.show();
    }

    /**
     * If the OS won't let us set exact alarms, offer a one-tap route to the system screen
     * that grants it. Reminders still fire without it (inexact fallback in NotificationHelper),
     * so this is informational, never blocking.
     */
    private void maybePromptExactAlarmPermission() {
        com.fouu.habitflow.util.NotificationHelper helper =
                new com.fouu.habitflow.util.NotificationHelper(requireContext());
        android.content.Intent settingsIntent = helper.buildExactAlarmSettingsIntent();
        if (settingsIntent == null) return; // already granted, or pre-Android 12

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.exact_alarm_title)
                .setMessage(R.string.exact_alarm_message)
                .setPositiveButton(R.string.exact_alarm_go_settings, (d, w) -> {
                    try {
                        startActivity(settingsIntent);
                    } catch (Exception e) {
                        // Some OEM ROMs don't implement this settings screen.
                        android.widget.Toast.makeText(requireContext(),
                                e.toString(), android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.exact_alarm_later, null)
                .show();
    }

    /**
     * Detached copy of a habit for editing.
     *
     * The Habit instances emitted by the ViewModel are the exact objects the adapter's
     * current list holds. Mutating one in place means DiffUtil compares the object with
     * itself, concludes "nothing changed", and never rebinds the card — which is why
     * switching DAILY / WEEKDAYS / WEEKLY sometimes appeared to do nothing.
     */
    private Habit copyOf(Habit src) {
        Habit c = new Habit();
        c.setId(src.getId());
        c.setName(src.getName());
        c.setDescription(src.getDescription());
        c.setIconName(src.getIconName());
        c.setColorHex(src.getColorHex());
        c.setFrequency(src.getFrequency());
        c.setTargetCount(src.getTargetCount());
        c.setReminderEnabled(src.isReminderEnabled());
        c.setReminderTime(src.getReminderTime());
        c.setStreak(src.getStreak());
        c.setBestStreak(src.getBestStreak());
        c.setCreatedAt(src.getCreatedAt());
        c.setArchived(src.isArchived());
        c.setTodayCompleted(src.isTodayCompleted());
        if (src.hasLocalId()) c.setLocalId(src.getLocalId());
        c.setUpdatedAt(src.getUpdatedAt());
        return c;
    }

    /**
     * Build the color swatches inside the edit dialog and track the selected one.
     * The chosen hex is written to selectedColor[0] so the caller can persist it.
     */
    private void setupColorPalette(View dialogView, Habit existing, String[] selectedColor) {
        LinearLayout palette = dialogView.findViewById(R.id.color_palette);
        if (palette == null) return;

        final String DEFAULT = com.fouu.habitflow.data.model.Habit.COLOR_DEFAULT;
        final String[] paletteColors = {
                DEFAULT,    // "default" => original M3 surface card, listed + selected first
                "#6750A4", // purple
                "#1976D2", // blue
                "#388E3C", // green
                "#F57C00", // orange
                "#D32F2F", // red
                "#00897B", // teal
                "#C2185B", // pink
                "#303F9F", // indigo
        };

        // Existing habit: use its stored color. New habit (or one with no/empty color): default.
        String current;
        if (existing != null && existing.getColorHex() != null
                && !existing.getColorHex().trim().isEmpty()
                && !existing.getColorHex().equalsIgnoreCase(DEFAULT)) {
            current = existing.getColorHex().trim();
        } else {
            current = DEFAULT; // default selected for new habits
        }
        selectedColor[0] = current;

        final View[] selectedView = new View[1];
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (40 * density);
        int gap = (int) (10 * density);

        for (final String hex : paletteColors) {
            View swatch = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            swatch.setLayoutParams(lp);
            swatch.setTag(hex);
            swatch.setBackground(makeSwatch(hex, hex.equalsIgnoreCase(current)));
            if (hex.equalsIgnoreCase(current)) selectedView[0] = swatch;

            swatch.setOnClickListener(v -> {
                if (selectedView[0] != null) {
                    selectedView[0].setBackground(makeSwatch(getHex(selectedView[0]), false));
                }
                swatch.setBackground(makeSwatch(hex, true));
                selectedView[0] = swatch;
                selectedColor[0] = hex;
            });
            palette.addView(swatch);
        }
    }

    /** Read the color hex stored as a tag on the swatch view. */
    private String getHex(View swatch) {
        Object tag = swatch.getTag();
        return tag instanceof String ? (String) tag : com.fouu.habitflow.data.model.Habit.COLOR_DEFAULT;
    }

    /** Circular swatch drawable; the "default" sentinel renders as the M3 surface color
     *  (neutral, no tint) with a dashed-ish ring so it reads as "use system color". */
    private android.graphics.drawable.Drawable makeSwatch(String hex, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        if (com.fouu.habitflow.data.model.Habit.COLOR_DEFAULT.equals(hex)) {
            // Resolve colorSurface from the theme so the swatch previews the REAL default card
            // color (dynamic colors / night mode), matching HabitAdapter's default branch.
            android.util.TypedValue tv = new android.util.TypedValue();
            requireContext().getTheme().resolveAttribute(
                    com.google.android.material.R.attr.colorSurface, tv, true);
            int surface = tv.resourceId != 0
                    ? androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
                    : tv.data;
            d.setColor(surface);
            d.setStroke((int) (2 * getResources().getDisplayMetrics().density),
                    androidx.core.content.ContextCompat.getColor(requireContext(),
                            R.color.primary));
        } else {
            d.setColor(Color.parseColor(hex));
        }
        if (selected) {
            float density = getResources().getDisplayMetrics().density;
            d.setStroke((int) (3 * density), Color.WHITE);
        } else if (!com.fouu.habitflow.data.model.Habit.COLOR_DEFAULT.equals(hex)) {
            d.setStroke(0, Color.TRANSPARENT);
        }
        // The hex is kept on the view tag (see setupColorPalette) so the click
        // handler can restore the unselected drawable; GradientDrawable has no hex getter.
        return d;
    }

    private void showDeleteDialog(Habit habit) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_habit)
                .setMessage(getString(R.string.delete_habit_confirm, habit.getName()))
                .setPositiveButton(R.string.delete, (d, w) -> viewModel.deleteHabit(habit))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showPremiumPrompt() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.premium_locked_title)
                .setMessage(R.string.premium_locked_message)
                .setPositiveButton(R.string.upgrade, (d, w) -> {
                    // Use the bottom nav so the selected tab stays in sync and
                    // other tabs remain tappable afterwards.
                    if (getActivity() != null) {
                        BottomNavigationView nav = getActivity().findViewById(R.id.nav_view);
                        if (nav != null) {
                            nav.setSelectedItemId(R.id.nav_settings);
                            return;
                        }
                    }
                    Navigation.findNavController(requireView()).navigate(R.id.nav_settings);
                })
                .setNegativeButton(R.string.not_now, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        if (dataChangedReceiver != null) {
            try {
                requireContext().unregisterReceiver(dataChangedReceiver);
            } catch (Exception e) {
                // already unregistered
            }
            dataChangedReceiver = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
