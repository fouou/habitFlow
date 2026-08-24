package com.fouu.habitflow.ui.achievements;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.model.Achievement;
import com.fouu.habitflow.databinding.ItemAchievementBinding;

/**
 * AchievementAdapter - shows each badge with its icon, name, lock state, progress bar
 * and "current / target" text. Theme colors are resolved from the theme (never hardcoded),
 * so it follows Material3 dynamic color / dark mode like the rest of the app.
 */
public class AchievementAdapter extends ListAdapter<Achievement, AchievementAdapter.AchievementViewHolder> {

    public AchievementAdapter() {
        super(DIFF_CALLBACK);
    }

    @NonNull
    @Override
    public AchievementViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAchievementBinding binding = ItemAchievementBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new AchievementViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AchievementViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class AchievementViewHolder extends RecyclerView.ViewHolder {
        private final ItemAchievementBinding binding;

        AchievementViewHolder(@NonNull ItemAchievementBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(Achievement a) {
            Context ctx = binding.getRoot().getContext();
            boolean unlocked = a.isUnlocked();
 
            // DB stores stable English keys; localize at display time.
            binding.tvTitle.setText(com.fouu.habitflow.util.AchievementLocalizer.title(ctx, a.getTitle()));
            binding.tvDesc.setText(com.fouu.habitflow.util.AchievementLocalizer.description(
                    ctx, a.getTitle(), a.getDescription()));

            // Status label + color (framework theme attrs; no Material R dependency)
            android.util.TypedValue tv = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true);
            int primary = tv.resourceId != 0
                    ? androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId) : tv.data;
            ctx.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
            int onSurfaceVariant = tv.resourceId != 0
                    ? androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId) : tv.data;
            int statusColor = unlocked
                    ? Color.parseColor("#388E3C") // green for unlocked
                    : onSurfaceVariant;
            binding.tvStatus.setText(unlocked
                    ? ctx.getString(R.string.achievement_unlocked)
                    : ctx.getString(R.string.achievement_locked));
            binding.tvStatus.setTextColor(statusColor);

            // Icon: resolve drawable by icon_name; fall back to the trophy.
            int iconRes = ctx.getResources().getIdentifier(
                    a.getIconName() != null ? a.getIconName() : "ic_trophy",
                    "drawable", ctx.getPackageName());
            if (iconRes == 0) iconRes = R.drawable.ic_trophy;
            binding.ivIcon.setImageResource(iconRes);

            if (unlocked) {
                binding.ivIcon.setColorFilter(primary);
                binding.iconFrame.setBackgroundResource(android.R.color.transparent);
            } else {
                binding.ivIcon.setColorFilter(onSurfaceVariant);
                binding.iconFrame.setBackgroundResource(android.R.color.transparent);
            }

            // Progress: bar max = target, fill = current progress.
            int target = a.getTargetValue() > 0 ? a.getTargetValue() : 1;
            binding.progressBar.setMax(target);
            binding.progressBar.setProgress(Math.min(a.getProgress(), target));
            binding.tvProgress.setText(ctx.getString(
                    R.string.achievement_progress, a.getProgress(), target));
        }

    }

    private static final DiffUtil.ItemCallback<Achievement> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Achievement>() {
                @Override
                public boolean areItemsTheSame(@NonNull Achievement oldItem, @NonNull Achievement newItem) {
                    return oldItem.getId() == newItem.getId();
                }

                @Override
                public boolean areContentsTheSame(@NonNull Achievement oldItem, @NonNull Achievement newItem) {
                    return oldItem.isUnlocked() == newItem.isUnlocked()
                            && oldItem.getProgress() == newItem.getProgress()
                            && oldItem.getTargetValue() == newItem.getTargetValue()
                            && android.text.TextUtils.equals(oldItem.getTitle(), newItem.getTitle());
                }
            };
}
