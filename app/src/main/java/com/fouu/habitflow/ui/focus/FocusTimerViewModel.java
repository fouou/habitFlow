package com.fouu.habitflow.ui.focus;

import androidx.lifecycle.ViewModel;

/**
 * Activity-scoped timer state for the focus Pomodoro.
 *
 * The FocusFragment is destroyed/recreated when the user switches bottom-nav Tabs.
 * If the countdown lived in the Fragment's fields, coming back would reset it.
 * Moving the state here (survives Fragment recreation and config changes) keeps the
 * timer running across Tab switches.
 */
public class FocusTimerViewModel extends ViewModel {

    private long timeRemaining = 0L;   // millis left in the current phase
    private long phaseDuration = 0L;   // full duration of the current phase (for progress)
    private boolean isRunning = false; // currently ticking
    private boolean isBreak = false;   // current phase is a break
    private int focusMinutes = 25;     // custom focus length (Premium); Free fixed at 25
    private boolean initialized = false;
    private boolean isLongBreak = false; // current break is a long break (vs short)
    private int interruptions = 0;       // times the user was interrupted this focus session

    public long getTimeRemaining() { return timeRemaining; }
    public void setTimeRemaining(long v) { timeRemaining = v; }

    public long getPhaseDuration() { return phaseDuration; }
    public void setPhaseDuration(long v) { phaseDuration = v; }

    public boolean isRunning() { return isRunning; }
    public void setRunning(boolean v) { isRunning = v; }

    public boolean isBreak() { return isBreak; }
    public void setBreak(boolean v) { isBreak = v; }

    public int getFocusMinutes() { return focusMinutes; }
    public void setFocusMinutes(int v) { focusMinutes = v; }

    public boolean isLongBreak() { return isLongBreak; }
    public void setLongBreak(boolean v) { isLongBreak = v; }

    public int getInterruptions() { return interruptions; }
    public void setInterruptions(int v) { interruptions = v; }

    public boolean isInitialized() { return initialized; }
    public void setInitialized(boolean v) { initialized = v; }
}
