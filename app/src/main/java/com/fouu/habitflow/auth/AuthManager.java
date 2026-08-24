package com.fouu.habitflow.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;

import com.fouu.habitflow.R;
import com.fouu.habitflow.data.remote.SyncManager;
import com.fouu.habitflow.util.PreferenceManager;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

/**
 * AuthManager - Handles all authentication flows via Firebase.
 *
 * Supported methods:
 * - Email/Password sign in & sign up
 * - Google Sign-In (with Firebase credential exchange)
 *
 * Architecture note: Firebase Auth state is the single source of truth.
 * PreferenceManager mirrors UID/email for offline access.
 */
public class AuthManager {

    private static final String TAG = "AuthManager";
    private static AuthManager instance;

    private final FirebaseAuth firebaseAuth;
    private final PreferenceManager prefs;
    private final SyncManager sync;
    private final Context appContext;
    private GoogleSignInClient googleSignInClient;

    private AuthManager(Context context) {
        this.firebaseAuth = FirebaseAuth.getInstance();
        this.prefs = PreferenceManager.getInstance(context);
        this.sync = SyncManager.getInstance(context);
        this.appContext = context.getApplicationContext();
        setupGoogleSignIn(context);
    }

    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context.getApplicationContext());
        }
        return instance;
    }

    // ===== Google Sign-In Setup =====
    private void setupGoogleSignIn(Context context) {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(context.getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        this.googleSignInClient = GoogleSignIn.getClient(context, gso);
    }

    public Intent getGoogleSignInIntent() {
        return googleSignInClient.getSignInIntent();
    }

    // ===== Email/Password Auth =====

    public void signUpWithEmail(String email, String password, AuthCallback callback) {
        firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> handleAuthResult(task, callback));
    }

    public void signInWithEmail(String email, String password, AuthCallback callback) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> handleAuthResult(task, callback));
    }

    // ===== Google Sign-In Result =====
    public void handleGoogleSignInResult(Intent data, AuthCallback callback) {
        try {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            GoogleSignInAccount account = task.getResult(ApiException.class);
            firebaseAuthWithGoogle(account.getIdToken(), callback);
        } catch (ApiException e) {
            Log.e(TAG, "Google sign-in failed: " + e.getStatusCode());
            callback.onError("Google sign-in failed: " + e.getMessage());
        }
    }

    private void firebaseAuthWithGoogle(String idToken, AuthCallback callback) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        firebaseAuth.signInWithCredential(credential)
                .addOnCompleteListener(task -> handleAuthResult(task, callback));
    }

    // ===== Sign Out =====
    public void signOut() {
        signOut(null);
    }

    /**
     * Sign out AND permanently delete the user's cloud backup. The Firestore deletion is issued
     * while the user is still authenticated (so it has permission to delete), and the actual
     * auth sign-out happens only AFTER the cloud wipe completes — otherwise the request would be
     * rejected as unauthorized. onComplete runs on the main thread.
     */
    public void signOut(Runnable onComplete) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        String uid = user != null ? user.getUid() : null;
        if (uid != null) {
            sync.deleteAccountData(uid, () -> finishSignOut(onComplete));
        } else {
            finishSignOut(onComplete);
        }
    }

    private void finishSignOut(Runnable onComplete) {
        sync.stop(); // halt any further sync work
        firebaseAuth.signOut();
        googleSignInClient.signOut();
        prefs.clearUserData();
        if (onComplete != null) onComplete.run();
    }

    // ===== Delete account (irreversible) =====

    /**
     * Permanently delete the account: wipe the cloud backup, delete the Firebase Auth user,
     * then wipe the local Room database + user prefs. Runs on background threads as needed
     * (Firestore delete, Auth user.delete, Room clearAllTables must not run on the UI thread).
     * onComplete runs on the MAIN thread when everything is finished.
     */
    public void deleteAccount(Runnable onComplete) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        String uid = user != null ? user.getUid() : null;
        if (uid != null) {
            // Delete cloud data FIRST (while still authenticated), then delete the auth user.
            sync.deleteAccountData(uid, () -> deleteAuthUser(onComplete));
        } else {
            deleteAuthUser(onComplete);
        }
    }

    private void deleteAuthUser(final Runnable onComplete) {
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user != null) {
            user.delete().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    // user.delete() can fail server-side (e.g. FirebaseError REQUIRES_RECENT_LOGIN
                    // when the last sign-in was a while ago). The cloud wipe already ran before this
                    // step, so we must still force a local sign-out — otherwise getCurrentUser() stays
                    // non-null and AuthActivity bounces the user straight back into MainActivity,
                    // making it look like the account was never deleted.
                    Log.w(TAG, "Firebase user.delete() failed: "
                            + (task.getException() != null ? task.getException().getMessage() : "unknown"));
                }
                // Always clear the local Firebase session so the next screen shows logged-out state
                // regardless of whether the server-side delete succeeded.
                firebaseAuth.signOut();
                clearLocalData(onComplete);
            });
        } else {
            clearLocalData(onComplete);
        }
    }

    /** Wipe the local Room DB + user prefs. Must run off the UI thread (clearAllTables). */
    private void clearLocalData(final Runnable onComplete) {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                com.fouu.habitflow.data.db.AppDatabase.getInstance(appContext).clearAllTables();
            } catch (Exception e) {
                Log.w(TAG, "clear local tables failed: " + e.getMessage());
            }
            sync.stop();
            prefs.clearUserData();
            // Return to the main thread for the completion callback (UI work).
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (onComplete != null) onComplete.run();
            });
        });
    }

    // ===== Common Result Handler =====
    private void handleAuthResult(Task<AuthResult> task, AuthCallback callback) {
        if (task.isSuccessful() && task.getResult() != null) {
            FirebaseUser user = task.getResult().getUser();
            if (user != null) {
                prefs.setUserId(user.getUid());
                prefs.setUserEmail(user.getEmail() != null ? user.getEmail() : "");
                sync.resume();
                sync.syncNow(); // pull remote backup first (restore after reinstall), then push delta
                callback.onSuccess(user);
            }
        } else {
            String error = task.getException() != null ? task.getException().getMessage() : "Unknown error";
            Log.e(TAG, "Auth failed: " + error);
            callback.onError(error);
        }
    }

    // ===== Getters =====
    public FirebaseUser getCurrentUser() {
        return firebaseAuth.getCurrentUser();
    }

    public boolean isSignedIn() {
        return firebaseAuth.getCurrentUser() != null;
    }

    // ===== Callback Interface =====
    public interface AuthCallback {
        void onSuccess(FirebaseUser user);
        void onError(String error);
    }
}
