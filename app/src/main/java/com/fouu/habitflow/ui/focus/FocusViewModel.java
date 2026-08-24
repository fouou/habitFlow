package com.fouu.habitflow.ui.focus;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fouu.habitflow.data.model.FocusSession;
import com.fouu.habitflow.data.repo.FocusRepository;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FocusViewModel - manages Pomodoro session data.
 */
public class FocusViewModel extends AndroidViewModel {

    private final FocusRepository repository;
    private final MutableLiveData<Integer> todaySessionCount = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> totalFocusMinutes = new MutableLiveData<>(0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public FocusViewModel(@NonNull Application app) {
        super(app);
        this.repository = new FocusRepository(app);
        loadStats();
    }

    private void loadStats() {
        executor.execute(() -> {
            // Count today's completed sessions and total focus minutes (all time) from DB
            int count = repository.getTodayCompletedSessionCount();
            int total = repository.getTotalFocusMinutesAllTime();
            todaySessionCount.postValue(count);
            totalFocusMinutes.postValue(total);
        });
    }

    public void saveSession(int durationMinutes, boolean completed, String sessionType, int interruptions) {
        FocusSession session = new FocusSession();
        session.setDurationMinutes(durationMinutes);
        session.setCompleted(completed);
        session.setSessionType(sessionType);
        session.setInterruptions(interruptions);
        repository.insertSession(session);

        // Only FOCUS sessions count toward the "sessions today" / total-minutes stats
        // (breaks are rest, not productivity).
        if ("FOCUS".equals(sessionType)) {
            Integer current = todaySessionCount.getValue();
            todaySessionCount.postValue(current != null ? current + 1 : 1);

            Integer total = totalFocusMinutes.getValue();
            totalFocusMinutes.postValue(total != null ? total + durationMinutes : durationMinutes);
        }
    }

    // ===== Getters =====
    public LiveData<Integer> getTodaySessionCount() { return todaySessionCount; }
    public LiveData<Integer> getTotalFocusMinutes() { return totalFocusMinutes; }
}
