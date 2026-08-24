package com.fouu.habitflow.ui.habits;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.repo.HabitRepository;

import java.util.List;

/**
 * HabitsViewModel - manages UI state for HabitsFragment.
 *
 * Uses AndroidViewModel for Application context access (Repository needs it).
 */
public class HabitsViewModel extends AndroidViewModel {

    private final HabitRepository repository;
    private final LiveData<List<Habit>> habits;
    private final MutableLiveData<Integer> activeCount = new MutableLiveData<>(0);

    public HabitsViewModel(@NonNull Application app) {
        super(app);
        repository = new HabitRepository(app);
        habits = repository.getActiveHabitsWithToday();
        // Recompute streaks from real logs so the habit-card chips match reality
        // (covers lapses, new days, and widget toggles that bypass toggleHabitForToday).
        repository.recomputeAllStreaks();
        // Cache the active count off the main thread so the FAB check never hits DB on UI thread.
        new android.os.AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                return repository.getActiveHabitCountSync();
            }

            @Override
            protected void onPostExecute(Integer count) {
                activeCount.setValue(count);
            }
        }.execute();
    }

    public LiveData<List<Habit>> getHabits() {
        return habits;
    }

    public int getActiveCount() {
        // Return cached value; never query DB on main thread.
        Integer cached = activeCount.getValue();
        return cached != null ? cached : repository.getActiveHabitCountSync();
    }

    public LiveData<Integer> getActiveCountLive() {
        return activeCount;
    }

    /** Query the real active-habit count off the main thread (used by the free-tier limit). */
    public void getActiveHabitCountAsync(androidx.core.util.Consumer<Integer> callback) {
        new android.os.AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                return repository.getActiveHabitCountSync();
            }

            @Override
            protected void onPostExecute(Integer count) {
                callback.accept(count != null ? count : 0);
            }
        }.execute();
    }

    public void addHabit(Habit habit) {
        repository.insertHabit(habit, null);
    }

    /** Insert a new habit, then run `onInserted` on the background thread once the real
     *  row id is assigned — used to schedule the reminder with the correct id. */
    public void addHabit(Habit habit, com.fouu.habitflow.data.repo.HabitRepository.OnHabitInsertedListener onInserted) {
        repository.insertHabit(habit, onInserted);
    }

    public void updateHabit(Habit habit) {
        repository.updateHabit(habit);
    }

    /** Save only the edit-dialog fields (name/desc/frequency/color/reminder) via column updates. */
    public void updateHabitFromEditor(Habit habit) {
        repository.updateHabitEditableFields(habit);
    }

    public void deleteHabit(Habit habit) {
        repository.deleteHabit(habit);
    }

    public void toggleHabit(Habit habit, boolean completed) {
        repository.toggleHabitForToday(habit.getId(), completed);
    }

    /** Record a check-in (increment today's count). */
    public void checkInHabit(Habit habit) {
        repository.checkInHabit(habit.getId());
    }

    /** Undo today's completion for a habit. */
    public void uncheckHabit(Habit habit) {
        repository.uncheckHabit(habit.getId());
    }

    /** Overall consecutive-day streak (>=1 habit checked in per day). */
    public int getOverallStreak() {
        return repository.getOverallStreak();
    }

    /** Async version of {@link #getOverallStreak()} to avoid main-thread DB access. */
    public void getOverallStreakAsync(androidx.core.util.Consumer<Integer> callback) {
        new android.os.AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... voids) {
                return repository.getOverallStreak();
            }

            @Override
            protected void onPostExecute(Integer result) {
                callback.accept(result);
            }
        }.execute();
    }

    public void refresh() {
        // Re-query the habit list (today's completion) immediately. Needed so a change made by
        // the home-screen widget (which runs in the launcher process and writes the DB directly)
        // is reflected in-app without waiting for Room's per-process invalidation to fire.
        repository.triggerRefresh();
        // Refresh streak chips too, so pull-to-refresh keeps them in sync.
        repository.recomputeAllStreaks();
    }
}
