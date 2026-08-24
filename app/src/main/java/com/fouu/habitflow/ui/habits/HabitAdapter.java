package com.fouu.habitflow.ui.habits;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.databinding.ItemHabitCardBinding;
import com.fouu.habitflow.util.FrequencyUtil;
import com.fouu.habitflow.util.NotificationHelper;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * HabitAdapter - M3 card-style list with streak indicator.
 *
 * Each card shows:
 * - Habit name & description
 * - Current streak (fire icon)
 * - Best streak
 * - Today's completion checkbox (pure M3 default, no custom styling)
 */
public class HabitAdapter extends ListAdapter<Habit, HabitAdapter.HabitViewHolder> {

    private final OnHabitToggleListener toggleListener;
    private final OnHabitClickListener clickListener;
    private final OnHabitLongClickListener longClickListener;

    public HabitAdapter(
            OnHabitToggleListener toggleListener,
            OnHabitClickListener clickListener,
            OnHabitLongClickListener longClickListener
    ) {
        super(DIFF_CALLBACK);
        this.toggleListener = toggleListener;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public HabitViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHabitCardBinding binding = ItemHabitCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new HabitViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull HabitViewHolder holder, int position) {
        Habit habit = getItem(position);
        holder.bind(habit);
    }

    public void highlightHabit(int habitId) {
        for (int i = 0; i < getCurrentList().size(); i++) {
            if (getItem(i).getId() == habitId) {
                notifyItemChanged(i);
                break;
            }
        }
    }

    class HabitViewHolder extends RecyclerView.ViewHolder {
        private final ItemHabitCardBinding binding;

        HabitViewHolder(@NonNull ItemHabitCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Habit habit) {
            Context ctx = binding.getRoot().getContext();

            // Name & description
            binding.tvHabitName.setText(habit.getName());
            binding.tvHabitDesc.setText(habit.getDescription());

            // Color: "default" (or null/empty) means use the M3 surface color, i.e. the card's
            // original look. Otherwise tint with a light container shade + deeper accent controls.
            String colorHex = habit.getColorHex();
            boolean isDefault = colorHex == null || colorHex.isEmpty()
                    || com.fouu.habitflow.data.model.Habit.COLOR_DEFAULT.equals(colorHex);

            final int accent;
            final int cardColor;
            final int deepColor;

            if (isDefault) {
                // Original M3 card: surface background, default dark text, default checkbox.
                // Resolve colorSurface from the THEME, never the static R.color.surface: the app
                // uses Theme.Material3.DynamicColors.DayNight, so on Android 12+ the real surface
                // is derived from the user's wallpaper and the static #FEF7FF would repaint the
                // card a hardcoded pinkish white instead of restoring the true system color.
                cardColor = resolveColor(ctx, com.google.android.material.R.attr.colorSurface);
                accent = parseColorSafe(com.fouu.habitflow.data.model.Habit.DEFAULT_COLOR_HEX);
                deepColor = accent;
            } else {
                accent = parseColorSafe(colorHex);
                // Blend the user-picked accent toward a base tone. That base is white in light
                // mode (pale pastel card) and a dark surface at night (@color/habit_card_tint_base
                // in values-night), otherwise the pastel cards glare against a dark UI.
                int tintBase = androidx.core.content.ContextCompat.getColor(
                        ctx, R.color.habit_card_tint_base);
                cardColor = mix(accent, tintBase, 0.82f);
                // The "deep" tone must contrast against the card. At night the card is dark, so
                // go brighter than the accent instead of darker.
                deepColor = night(ctx)
                        ? mix(accent, Color.WHITE, 0.30f)
                        : mix(accent, Color.BLACK, 0.12f);
            }

            binding.getRoot().setCardBackgroundColor(cardColor);
            binding.getRoot().setStrokeWidth(0);

            // Text color. The default card uses the theme surface, and at night the tinted card
            // is a dark blend too -- in both of those cases black would be unreadable, so only
            // the light-mode pastel card gets black text.
            int textColor = (isDefault || night(ctx))
                    ? resolveColor(ctx, com.google.android.material.R.attr.colorOnSurface)
                    : Color.BLACK;
            binding.tvHabitName.setTextColor(textColor);
            binding.tvHabitDesc.setTextColor(applyAlpha(textColor, 0x8A));
            binding.tvNextReminder.setTextColor(applyAlpha(textColor, 0x8A));

            if (isDefault) {
                // Reset chips to the default M3 Assist style (no custom tint).
                resetChip(binding.chipFrequency);
                resetChip(binding.chipStreak);
                resetChip(binding.chipBest);
                // Reset checkbox to M3 default tick color.
                binding.cbToday.setButtonTintList(null);
            } else {
                // Chips: no black border, light fill + deep text
                setupChip(binding.chipFrequency, deepColor, cardColor);
                setupChip(binding.chipStreak, deepColor, cardColor);
                setupChip(binding.chipBest, deepColor, cardColor);

                // Checkbox tick: a bit deeper than the card background (same hue)
                binding.cbToday.setButtonTintList(
                        android.content.res.ColorStateList.valueOf(deepColor));
            }

            // Frequency chip
            String freq = habit.getFrequency() != null ? habit.getFrequency() : FrequencyUtil.DAILY;
            int freqLabel;
            if (FrequencyUtil.WEEKDAYS.equals(freq)) freqLabel = R.string.weekdays;
            else if (FrequencyUtil.WEEKLY.equals(freq)) freqLabel = R.string.weekly;
            else freqLabel = R.string.daily;
            binding.chipFrequency.setText(ctx.getString(freqLabel));

            // Daily target: the card no longer shows the count/target progress chip,
            // but the checkbox "done" state still reflects reaching today's target.
            int target = habit.getTargetCount();
            int todayCount = habit.getTodayCount();
            boolean done = todayCount >= Math.max(1, target);
            binding.chipStreak.setText("🔥 " + habit.getStreak());
            binding.chipBest.setText("🏆 " + habit.getBestStreak());

            // Today's checkbox — pure M3 default, no custom tint / padding / background.
            // For multi-target habits, `done` means the daily target is reached, not just
            // one check-in; the progress chip above shows todayCount / target.
            binding.cbToday.setOnCheckedChangeListener(null);
            binding.cbToday.setChecked(done);

            // Next reminder time (so testers can see when the alarm is set for)
            long nextReminder = new NotificationHelper(ctx).getNextReminderTimeMillis(habit);
            if (nextReminder > 0) {
                binding.tvNextReminder.setVisibility(android.view.View.VISIBLE);
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                binding.tvNextReminder.setText(
                        ctx.getString(R.string.next_reminder_at, sdf.format(new java.util.Date(nextReminder))));
            } else {
                binding.tvNextReminder.setVisibility(android.view.View.GONE);
            }

            // Today's completion: tap the checkbox to complete one more repetition (or undo
            // when already at the daily target). We use onClick (not onCheckedChange) and set
            // the visual state immediately, so a multi-target habit never flickers "checked"
            // then back to unchecked, and tapping twice can't accidentally undo the day.
            binding.cbToday.setOnClickListener(v -> {
                int t = habit.getTargetCount();
                int c = habit.getTodayCount();
                if (c >= t) {
                    // Already done for today → undo (resets the day's log).
                    binding.cbToday.setChecked(false);
                    toggleListener.onUndo(habit);
                } else {
                    // Complete one more repetition; reflect the new state right away.
                    binding.cbToday.setChecked(Math.min(c + 1, t) >= t);
                    playCompleteAnimation(binding, deepColor);
                    toggleListener.onCheckIn(habit);
                }
            });

            // Click & long click
            binding.getRoot().setOnClickListener(v -> clickListener.onClick(habit));
            binding.getRoot().setOnLongClickListener(v -> {
                longClickListener.onLongClick(habit);
                return true;
            });
        }

        /**
         * Successful-check feedback: card pops + streak chip glows.
         * Uses only Material theme colors, never overrides the checkbox style
         * (so the M3 default near-black unchecked border stays intact).
         */
        private void playCompleteAnimation(ItemHabitCardBinding b, int deepColor) {
            // 1) Card pop: scale 1 → 1.04 → 1
            b.getRoot().animate()
                    .scaleX(1.04f).scaleY(1.04f)
                    .setDuration(120)
                    .withEndAction(() -> b.getRoot().animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(160)
                            .start())
                    .start();

            // 2) Streak chip glow — deeper hue flash
            b.chipStreak.setChipBackgroundColor(
                    android.content.res.ColorStateList.valueOf(deepColor));
            b.chipStreak.setTextColor(Color.WHITE);
            b.chipStreak.animate()
                    .alpha(0.55f).setDuration(140)
                    .withEndAction(() -> b.chipStreak.animate()
                            .alpha(1f).setDuration(220).start())
                    .start();
        }

        private int applyAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        /** Linear blend of two colors. t=0 → a, t=1 → b. */
        private int mix(int a, int b, float t) {
            int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
            int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
            int r = Math.round(ar + (br - ar) * t);
            int g = Math.round(ag + (bg - ag) * t);
            int bl = Math.round(ab + (bb - ab) * t);
            return Color.rgb(r, g, bl);
        }

        /** Chip on a tinted card: no outline (no black border), subtle fill + contrasting text. */
        private void setupChip(com.google.android.material.chip.Chip chip, int deepColor, int cardColor) {
            chip.setChipStrokeWidth(0f);
            chip.setChipStrokeColor(null);
            // Blend toward the same base as the card so the chip sits just above the card tone
            // in both themes (mixing toward white at night would make chips glow).
            int chipBase = androidx.core.content.ContextCompat.getColor(
                    chip.getContext(), R.color.habit_card_tint_base);
            chip.setChipBackgroundColor(
                    android.content.res.ColorStateList.valueOf(mix(deepColor, chipBase, 0.80f)));
            chip.setTextColor(deepColor);
        }

        /** True when the app is currently rendering in dark mode. */
        private boolean night(Context ctx) {
            return ctx.getResources().getBoolean(R.bool.is_night_mode);
        }

        /** Chip in default (no-tint) card mode: neutral, no black border. */
        private void resetChip(com.google.android.material.chip.Chip chip) {
            chip.setChipStrokeWidth(0f);
            chip.setChipStrokeColor(null);
            int variant = resolveColor(chip.getContext(), android.R.attr.colorBackground);
            chip.setChipBackgroundColor(
                    android.content.res.ColorStateList.valueOf(applyAlpha(variant, 0x1F)));
            // Match the default card's text color (theme-driven, not hardcoded black) so the
            // chips stay legible when dynamic colors / night mode give a dark surface.
            chip.setTextColor(
                    resolveColor(chip.getContext(), com.google.android.material.R.attr.colorOnSurface));
        }

        private int parseColorSafe(String hex) {
            try {
                return Color.parseColor(hex != null ? hex : "#6750A4");
            } catch (Exception e) {
                return Color.parseColor("#6750A4");
            }
        }

        private int resolveColor(Context ctx, int attr) {
            android.util.TypedValue tv = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(attr, tv, true);
            return tv.resourceId != 0 ? androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
                    : tv.data;
        }
    }

    // ===== DiffUtil =====
    private static final DiffUtil.ItemCallback<Habit> DIFF_CALLBACK = new DiffUtil.ItemCallback<Habit>() {
        @Override
        public boolean areItemsTheSame(@NonNull Habit oldItem, @NonNull Habit newItem) {
            return oldItem.getId() == newItem.getId();
        }

        @Override
        public boolean areContentsTheSame(@NonNull Habit oldItem, @NonNull Habit newItem) {
            return oldItem.getStreak() == newItem.getStreak() &&
                   oldItem.getBestStreak() == newItem.getBestStreak() &&
                   android.text.TextUtils.equals(oldItem.getName(), newItem.getName()) &&
                   android.text.TextUtils.equals(oldItem.getDescription(), newItem.getDescription()) &&
                   android.text.TextUtils.equals(oldItem.getColorHex(), newItem.getColorHex()) &&
                   android.text.TextUtils.equals(oldItem.getFrequency(), newItem.getFrequency()) &&
                   android.text.TextUtils.equals(oldItem.getColorHex(), newItem.getColorHex()) &&
                   oldItem.isTodayCompleted() == newItem.isTodayCompleted() &&
                   oldItem.getTodayCount() == newItem.getTodayCount() &&
                   oldItem.isArchived() == newItem.isArchived() &&
                   oldItem.isReminderEnabled() == newItem.isReminderEnabled() &&
                   oldItem.getReminderTime() == newItem.getReminderTime();
        }
    };

    // ===== Listeners =====
    public interface OnHabitToggleListener {
        /** User tapped the checkbox to complete one more repetition today. */
        void onCheckIn(Habit habit);
        /** User tapped the checkbox when already done today → undo the day. */
        void onUndo(Habit habit);
    }
    public interface OnHabitClickListener { void onClick(Habit habit); }
    public interface OnHabitLongClickListener { void onLongClick(Habit habit); }
}
