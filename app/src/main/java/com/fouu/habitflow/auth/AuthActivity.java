package com.fouu.habitflow.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fouu.habitflow.ui.main.MainActivity;
import com.fouu.habitflow.R;
import com.fouu.habitflow.databinding.ActivityAuthBinding;

/**
 * AuthActivity - Sign in / Sign up screen.
 *
 * Modes:
 * - Email/Password (sign in or create account)
 * - Google Sign-In (one-tap)
 *
 * On success → navigate to MainActivity.
 */
public class AuthActivity extends AppCompatActivity {

    private ActivityAuthBinding binding;
    private AuthManager authManager;
    private boolean isSignUpMode = false;

    // Google Sign-In launcher
    private ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getData() != null) {
                            authManager.handleGoogleSignInResult(result.getData(), new AuthManager.AuthCallback() {
                                @Override
                                public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                                    navigateToMain();
                                }

                                @Override
                                public void onError(String error) {
                                    Toast.makeText(AuthActivity.this, error, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If already signed in, skip auth
        authManager = AuthManager.getInstance(this);
        if (authManager.isSignedIn()) {
            navigateToMain();
            return;
        }

        // If user previously chose "skip", don't show auth again on cold start.
        // But if launched explicitly from Settings ("mode" extra set), always show.
        String launchMode = getIntent().getStringExtra("mode");
        boolean explicitLaunch = launchMode != null;
        if (!explicitLaunch
                && com.fouu.habitflow.util.PreferenceManager.getInstance(this).isSkipLogin()) {
            navigateToMain();
            return;
        }

        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize mode from caller intent (e.g. Settings "Sign In" / "Create Account")
        String mode = getIntent().getStringExtra("mode");
        if ("sign_up".equals(mode)) {
            isSignUpMode = true;
        }

        setupUI();
        setupWindowInsets();
        playEnterAnimations();
    }

    /**
     * Marketing-style entrance animations:
     * logo scales+fades in, hero text slides up, form card slides up last.
     */
    private void playEnterAnimations() {
        long startDelay = 120;

        // Logo: scale + fade in
        binding.logoContainer.setAlpha(0f);
        binding.logoContainer.setScaleX(0.6f);
        binding.logoContainer.setScaleY(0.6f);
        binding.logoContainer.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(500)
                .setStartDelay(startDelay)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();

        // Hero text + features: fade + slide up
        binding.tvTitle.setAlpha(0f);
        binding.tvTitle.setTranslationY(24f);
        binding.tvTitle.animate().alpha(1f).translationY(0f)
                .setDuration(450).setStartDelay(startDelay + 150).start();

        binding.tvSubtitle.setAlpha(0f);
        binding.tvSubtitle.setTranslationY(24f);
        binding.tvSubtitle.animate().alpha(1f).translationY(0f)
                .setDuration(450).setStartDelay(startDelay + 250).start();

        binding.features.setAlpha(0f);
        binding.features.setTranslationY(24f);
        binding.features.animate().alpha(1f).translationY(0f)
                .setDuration(450).setStartDelay(startDelay + 350).start();

        // Form card: slide up from bottom
        binding.formCard.setAlpha(0f);
        binding.formCard.setTranslationY(80f);
        binding.formCard.animate().alpha(1f).translationY(0f)
                .setDuration(550).setStartDelay(startDelay + 450)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * Handle system bar insets: scroll content absorbs top inset (status bar)
     * and bottom inset (navigation bar) so nothing is hidden behind the bars.
     */
    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void setupUI() {
        // Apply initial mode text
        binding.btnSubmit.setText(isSignUpMode ? R.string.create_account : R.string.sign_in);
        binding.tvToggleMode.setText(isSignUpMode ? R.string.have_account_sign_in : R.string.no_account_sign_up);

        // Toggle Sign In / Sign Up mode
        binding.tvToggleMode.setOnClickListener(v -> {
            isSignUpMode = !isSignUpMode;
            binding.btnSubmit.setText(isSignUpMode ? R.string.create_account : R.string.sign_in);
            binding.tvToggleMode.setText(isSignUpMode ? R.string.have_account_sign_in : R.string.no_account_sign_up);
        });

        // Submit button
        binding.btnSubmit.setOnClickListener(v -> {
            String email = binding.etEmail.getText() != null ? binding.etEmail.getText().toString().trim() : "";
            String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString() : "";

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show();
                return;
            }

            if (password.length() < 6) {
                Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show();
                return;
            }

            binding.btnSubmit.setEnabled(false);

            AuthManager.AuthCallback callback = new AuthManager.AuthCallback() {
                @Override
                public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                    navigateToMain();
                }

                @Override
                public void onError(String error) {
                    binding.btnSubmit.setEnabled(true);
                    Toast.makeText(AuthActivity.this, error, Toast.LENGTH_LONG).show();
                }
            };

            if (isSignUpMode) {
                authManager.signUpWithEmail(email, password, callback);
            } else {
                authManager.signInWithEmail(email, password, callback);
            }
        });

        // Google Sign-In button
        binding.btnGoogleSignIn.setOnClickListener(v -> {
            Intent signInIntent = authManager.getGoogleSignInIntent();
            googleSignInLauncher.launch(signInIntent);
        });

        // Skip: login is optional, go straight to the app (remember choice)
        binding.btnSkip.setOnClickListener(v -> {
            com.fouu.habitflow.util.PreferenceManager.getInstance(AuthActivity.this).setSkipLogin(true);
            navigateToMain();
        });
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
