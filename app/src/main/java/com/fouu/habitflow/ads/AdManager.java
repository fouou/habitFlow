package com.fouu.habitflow.ads;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fouu.habitflow.BuildConfig;
import com.fouu.habitflow.R;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;

/**
 * AdManager - Handles all ad-related operations.
 *
 * Ad Types:
 * - Banner: Shown at bottom of non-premium screens
 * - Interstitial: Shown after completing certain actions (every 3rd habit complete)
 * - Rewarded: User chooses to watch for bonus features (e.g., unlock premium insight)
 *
 * Premium users see NO ads.
 */
public class AdManager {

    private static final String TAG = "AdManager";

    // Google official TEST ad unit IDs (used in debug builds).
    private static final String TEST_BANNER = "ca-app-pub-3940256099942544/6300978111";
    private static final String TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917";

    // Real AdMob ad unit IDs from https://apps.admob.com
    private static final String REAL_BANNER = "ca-app-pub-2249836449257664/7089096029";
    private static final String REAL_REWARDED = "ca-app-pub-2249836449257664/1374448927";

    private final String BANNER_AD_UNIT;
    private final String REWARDED_AD_UNIT;

    private static AdManager instance;
    private RewardedAd rewardedAd;
    private String lastError;
    private final Context appContext;

    private AdManager(Context context) {
        this.appContext = context.getApplicationContext();
        // Debug builds use Google test ad IDs so real ads/traffic are never triggered during dev.
        if (BuildConfig.DEBUG) {
            BANNER_AD_UNIT = TEST_BANNER;
            REWARDED_AD_UNIT = TEST_REWARDED;
        } else {
            BANNER_AD_UNIT = REAL_BANNER;
            REWARDED_AD_UNIT = REAL_REWARDED;
        }
        // Initialize MobileAds SDK
        MobileAds.initialize(appContext, initializationStatus -> {
            Log.d(TAG, "AdMob initialized");
            loadRewarded(appContext);
        });
    }

    public static synchronized AdManager getInstance(Context context) {
        if (instance == null) {
            instance = new AdManager(context.getApplicationContext());
        }
        return instance;
    }

    // ===== Banner Ad =====

    /**
     * Load a banner ad into the given AdView. Only meaningful for free users.
     * Caller should hide the AdView (or its container) for premium users.
     */
    /**
     * Load a banner ad into the given AdView. The caller is responsible for setting up an
     * AdListener to control visibility and layout adjustments (e.g. content padding).
     */
    public void loadBanner(com.google.android.gms.ads.AdView adView) {
        if (adView == null) return;
        // NOTE: adUnitId is already set in the layout XML. AdView forbids setting it twice,
        // so we must NOT call setAdUnitId() here again.
        adView.setVisibility(View.GONE);
        adView.loadAd(buildAdRequest());
    }

    /**
     * Load a banner and automatically show it only once an ad has actually loaded
     * (hide on failure). Convenience for pages that don't need to reserve padding.
     */
    public void loadBannerWithAutoShow(com.google.android.gms.ads.AdView adView) {
        if (adView == null) return;
        adView.setVisibility(View.GONE);
        adView.setAdListener(new com.google.android.gms.ads.AdListener() {
            @Override
            public void onAdLoaded() {
                adView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError error) {
                adView.setVisibility(View.GONE);
            }
        });
        adView.loadAd(buildAdRequest());
    }

    public AdRequest buildAdRequest() {
        return new AdRequest.Builder().build();
    }

    public String getBannerAdUnit() {
        return BANNER_AD_UNIT;
    }

    // ===== Rewarded Ad =====

    private void loadRewarded(Context context) {
        loadRewarded(context, null);
    }

    /** Load a rewarded ad. onComplete runs once the load attempt finishes (success OR failure). */
    private void loadRewarded(Context context, Runnable onComplete) {
        AdRequest adRequest = new AdRequest.Builder().build();
        RewardedAd.load(context, REWARDED_AD_UNIT, adRequest,
                new RewardedAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull RewardedAd ad) {
                        rewardedAd = ad;
                        Log.d(TAG, "Rewarded ad loaded");
                        if (onComplete != null) onComplete.run();
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        // Surface the REAL error (code/domain/message) so failures are diagnosable.
                        Log.e(TAG, "Rewarded failed code=" + error.getCode()
                                + " domain=" + error.getDomain() + " msg=" + error.getMessage());
                        lastError = "code=" + error.getCode() + " " + error.getMessage();
                        rewardedAd = null;
                        if (onComplete != null) onComplete.run();
                    }
                });
    }

    public boolean isRewardedReady() {
        return rewardedAd != null;
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Show the rewarded ad. If it isn't loaded yet, kick off a load first and show it as soon as
     * it arrives (so a slow/failed first load doesn't leave the button permanently dead). On
     * failure the real error is surfaced via a toast instead of a canned string.
     */
    public void showRewardedAd(Activity activity, OnRewardEarnedListener listener) {
        if (rewardedAd != null) {
            showActual(activity, listener);
            return;
        }
        if (activity != null) {
            Toast.makeText(activity, R.string.ad_loading, Toast.LENGTH_SHORT).show();
        }
        final Context ctx = activity != null ? activity : appContext;
        loadRewarded(ctx, () -> {
            if (rewardedAd != null) {
                showActual(activity, listener);
            } else if (activity != null) {
                String msg = activity.getString(R.string.ad_load_failed)
                        + (lastError != null ? " (" + lastError + ")" : "");
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showActual(Activity activity, OnRewardEarnedListener listener) {
        rewardedAd.show(activity, rewardItem -> {
            Log.d(TAG, "Reward earned: " + rewardItem.getAmount());
            if (listener != null) listener.onRewardEarned(rewardItem.getAmount());
            loadRewarded(activity != null ? activity : appContext); // reload for next time
        });
    }

    // ===== Listener =====
    public interface OnRewardEarnedListener {
        void onRewardEarned(int amount);
    }
}
