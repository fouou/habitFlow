package com.fouu.habitflow.ui.achievements;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.model.Achievement;
import com.fouu.habitflow.databinding.FragmentAchievementsBinding;
import com.fouu.habitflow.ui.achievements.AchievementsViewModel;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * AchievementsFragment - shows the gamification badges the user has earned / is
 * working toward. Progress is recomputed from live metrics every time the tab is
 * opened, and unlocks arrive via the achievement-unlocked notification channel.
 */
public class AchievementsFragment extends Fragment {

    private FragmentAchievementsBinding binding;
    private AchievementsViewModel viewModel;
    private AchievementAdapter adapter;
    private List<Achievement> achievementsList;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentAchievementsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().getApplication()))
                .get(AchievementsViewModel.class);

        adapter = new AchievementAdapter();
        binding.recyclerAchievements.setAdapter(adapter);

        binding.btnAchievementsHelp.setOnClickListener(v -> showAchievementsHelp());

        viewModel.getAchievements().observe(getViewLifecycleOwner(), this::onAchievementsChanged);
    }

    /** Recompute progress (streaks, check-ins, focus, habit count) on every tab visit. */
    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refresh();
    }

    private void onAchievementsChanged(List<Achievement> list) {
        if (binding == null) return;
        this.achievementsList = list;
        int unlocked = 0;
        if (list != null) {
            for (Achievement a : list) {
                if (a.isUnlocked()) unlocked++;
            }
        }
        binding.tvSummary.setText(getString(R.string.achievement_progress, unlocked,
                list != null ? list.size() : 0));

        boolean empty = list == null || list.isEmpty();
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.recyclerAchievements.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (!empty) adapter.submitList(list);
    }

    /** Circle (?) button: explain what the achievements below are. */
    private void showAchievementsHelp() {
        Context ctx = requireContext();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.achievements_help_intro)).append("\n\n");
        if (achievementsList != null) {
            for (Achievement a : achievementsList) {
                sb.append("• ").append(a.getTitle()).append(" — ").append(a.getDescription());
                sb.append(a.isUnlocked() ? "  ✓\n" : "\n");
            }
        }
        ScrollView scroll = new ScrollView(ctx);
        TextView tv = new TextView(ctx);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        tv.setText(sb.toString());
        scroll.addView(tv);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.achievements)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
