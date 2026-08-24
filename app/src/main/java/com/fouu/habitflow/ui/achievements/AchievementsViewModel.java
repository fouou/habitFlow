package com.fouu.habitflow.ui.achievements;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.fouu.habitflow.data.model.Achievement;
import com.fouu.habitflow.data.repo.AchievementRepository;

import java.util.List;

/**
 * AchievementsViewModel - exposes the live list of achievements and refreshes
 * their progress whenever the page is opened.
 */
public class AchievementsViewModel extends AndroidViewModel {

    private final AchievementRepository repository;

    public AchievementsViewModel(@NonNull Application app) {
        super(app);
        this.repository = new AchievementRepository(app);
    }

    public LiveData<List<Achievement>> getAchievements() {
        return repository.getAllAchievements();
    }

    /** Recompute progress from live user metrics (e.g. on tab resume). */
    public void refresh() {
        repository.refreshProgress();
    }
}
