package com.fouu.habitflow.ui.main;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.NavigationUI;

import com.fouu.habitflow.R;
import com.fouu.habitflow.databinding.ActivityMainBinding;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;

/**
 * MainActivity - Single Activity hosting all fragments via Bottom Navigation.
 *
 * Architecture:
 * - Single Activity (this) + Multiple Fragments
 * - Bottom Navigation (M3 style) with 4 destinations:
 *   1. Habits (home) - list & manage habits
 *   2. Analytics - charts & AI insights
 *   3. Focus - Pomodoro timer
 *   4. Settings - theme, premium, about
 *
 * Dynamic Color (Material You) is applied via DynamicColors API.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply Material You dynamic colors BEFORE super.onCreate
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Login is optional: users can enter the app without signing in.
        // (AuthActivity provides a "Skip" button that lands here directly.)

        setupBackPressHandler();
        setupWindowInsets();
    }

    /**
     * Handle system bar insets manually via WindowInsetsCompat so that:
     * - The fragment container absorbs the top inset (status bar) -> content starts below it
     * - The bottom navigation absorbs the bottom inset (navigation bar)
     */
    private void setupWindowInsets() {
        // Fragment container handles top inset (status bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot().findViewById(R.id.nav_host_fragment), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return insets;
        });

        // BottomNav handles bottom inset (navigation bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // NavController is guaranteed ready here (NavHostFragment fully created)
        setupNavigation();
        // Handle notification tap → navigate to specific habit
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (navController == null) return;
        if (intent != null && intent.hasExtra("habit_id")) {
            int habitId = intent.getIntExtra("habit_id", -1);
            if (habitId != -1) {
                // Navigate to habits tab and pass habit ID
                Bundle args = new Bundle();
                args.putInt("highlight_habit_id", habitId);
                navController.navigate(R.id.nav_habits, args);
            }
        }
    }

    private void setupNavigation() {
        BottomNavigationView navView = binding.navView;

        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupWithNavController(navView, navController);
    }

    private void setupBackPressHandler() {
        // Double-tap back to exit
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            private long lastBackPress;

            @Override
            public void handleOnBackPressed() {
                if (navController.getCurrentDestination() != null &&
                        navController.getCurrentDestination().getId() != R.id.nav_habits) {
                    // If not on home tab, go to home
                    navController.navigate(R.id.nav_habits);
                } else if (System.currentTimeMillis() - lastBackPress < 2000) {
                    finish();
                } else {
                    lastBackPress = System.currentTimeMillis();
                    Toast.makeText(MainActivity.this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    /** Trigger the full-screen confetti celebration (called from fragments). */
    public void celebrateStreak() {
        binding.confetti.celebrate();
        buzz();
    }

    /** Short haptic buzz to accompany the confetti celebration. */
    private void buzz() {
        try {
            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(120);
            }
        } catch (Exception ignored) {
            // vibration is a nice-to-have; never let it crash the celebration
        }
    }

    // ===== Confetti bridge for background-triggered celebrations (achievements) =====
    private static java.lang.ref.WeakReference<MainActivity> sVisible;

    @Override
    protected void onStart() {
        super.onStart();
        sVisible = new java.lang.ref.WeakReference<>(this);
    }

    @Override
    protected void onDestroy() {
        if (sVisible != null && sVisible.get() == this) sVisible = null;
        super.onDestroy();
    }

    /** Fire full-screen confetti if MainActivity is currently visible (safe from any thread). */
    public static void fireConfettiIfVisible() {
        MainActivity activity = sVisible == null ? null : sVisible.get();
        if (activity != null && !activity.isFinishing()) {
            activity.celebrateStreak();
        }
    }
}
