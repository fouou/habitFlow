package com.fouu.habitflow.billing;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.fouu.habitflow.util.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * BillingManager - Google Play Billing integration.
 *
 * Products (configure in Play Console):
 * - "premium_monthly" → $2.99/month subscription
 * - "premium_yearly"  → $14.99/year subscription (save 58%)
 * - "remove_ads"      → $4.99 one-time
 *
 * Subscription unlocks:
 * - Unlimited habits (free = 3 max)
 * - AI-powered insights
 * - Advanced analytics & charts
 * - All themes & widgets
 * - No ads
 */
public class BillingManager implements PurchasesUpdatedListener {

    private static final String TAG = "BillingManager";

    // Product IDs (must match Play Console)
    public static final String SKU_PREMIUM_MONTHLY = "premium_monthly";
    public static final String SKU_PREMIUM_YEARLY = "premium_yearly";
    public static final String SKU_REMOVE_ADS = "remove_ads";

    private static BillingManager instance;

    private final BillingClient billingClient;
    private final Context context;
    private Activity currentActivity;
    private ProductDetails monthlyDetails;
    private ProductDetails yearlyDetails;
    private ProductDetails removeAdsDetails;

    private BillingManager(Context context) {
        this.context = context.getApplicationContext();
        this.billingClient = BillingClient.newBuilder(this.context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .build();
        connect();
    }

    public static synchronized BillingManager getInstance(Context context) {
        if (instance == null) {
            instance = new BillingManager(context);
        }
        return instance;
    }

    // ===== Connection =====
    private void connect() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing connected");
                    queryProductDetails();
                    checkExistingPurchases();
                } else {
                    Log.e(TAG, "Billing setup failed: " + result.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected - retrying...");
                connect(); // Retry
            }
        });
    }

    // ===== Product Details =====
    private void queryProductDetails() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_PREMIUM_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_PREMIUM_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_REMOVE_ADS)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();

        billingClient.queryProductDetailsAsync(params, (result, productDetailsResult) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                List<ProductDetails> productDetailsList = productDetailsResult.getProductDetailsList();
                for (ProductDetails details : productDetailsList) {
                    switch (details.getProductId()) {
                        case SKU_PREMIUM_MONTHLY: monthlyDetails = details; break;
                        case SKU_PREMIUM_YEARLY: yearlyDetails = details; break;
                        case SKU_REMOVE_ADS: removeAdsDetails = details; break;
                    }
                }
                Log.d(TAG, "Product details loaded: " + productDetailsList.size() + " items");
            }
        });
    }

    // ===== Launch Purchase Flow =====
    public void launchPurchaseFlow(Activity activity, String productId) {
        this.currentActivity = activity;
        if (billingClient == null || !billingClient.isReady()) {
            Log.e(TAG, "Billing not ready yet");
            if (activity != null) {
                Toast.makeText(activity, "商店服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        ProductDetails details = getProductDetails(productId);
        if (details == null) {
            Log.e(TAG, "Product not found: " + productId);
            if (activity != null) {
                Toast.makeText(activity, "订阅商品未就绪：请确认已在 Play Console 创建并发布（" + productId + "）", Toast.LENGTH_LONG).show();
            }
            return;
        }

        List<BillingFlowParams.ProductDetailsParams> productParams = new ArrayList<>();

        if (details.getProductType().equals(BillingClient.ProductType.SUBS)) {
            // Subscription
            ProductDetails.SubscriptionOfferDetails offer = details.getSubscriptionOfferDetails().get(0);
            productParams.add(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .setOfferToken(offer.getOfferToken())
                    .build());
        } else {
            // One-time
            productParams.add(BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .build());
        }

        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productParams)
                .build();

        billingClient.launchBillingFlow(activity, params);
    }

    private ProductDetails getProductDetails(String productId) {
        switch (productId) {
            case SKU_PREMIUM_MONTHLY: return monthlyDetails;
            case SKU_PREMIUM_YEARLY: return yearlyDetails;
            case SKU_REMOVE_ADS: return removeAdsDetails;
            default: return null;
        }
    }

    // ===== Purchase Handling =====
    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else {
            Log.e(TAG, "Purchase failed: " + result.getDebugMessage());
            if (currentActivity != null) {
                Toast.makeText(currentActivity, result.getDebugMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            // Acknowledge if needed
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.getPurchaseToken())
                        .build();
                billingClient.acknowledgePurchase(params, result -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        unlockPremium(purchase);
                    }
                });
            } else {
                unlockPremium(purchase);
            }
        }
    }

    private void unlockPremium(Purchase purchase) {
        PreferenceManager prefs = PreferenceManager.getInstance(context);
        prefs.setPremium(true);
        prefs.setAdFree(true);
        Log.d(TAG, "Premium unlocked! Purchase: " + purchase.getProducts());
    }

    // ===== Check Existing Purchases =====
    private void checkExistingPurchases() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();
        billingClient.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                for (Purchase purchase : purchases) {
                    if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                        unlockPremium(purchase);
                    }
                }
            }
            // Also check in-app purchases
            QueryPurchasesParams inappParams = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build();
            billingClient.queryPurchasesAsync(inappParams, (r2, p2) -> {
                if (r2.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    for (Purchase p : p2) {
                        if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            unlockPremium(p);
                        }
                    }
                }
            });
        });
    }

    // ===== Getters for UI =====
    public String getMonthlyPrice() {
        return monthlyDetails != null ?
                monthlyDetails.getSubscriptionOfferDetails().get(0)
                        .getPricingPhases().getPricingPhaseList().get(0)
                        .getFormattedPrice() : "$2.99";
    }

    public String getYearlyPrice() {
        return yearlyDetails != null ?
                yearlyDetails.getSubscriptionOfferDetails().get(0)
                        .getPricingPhases().getPricingPhaseList().get(0)
                        .getFormattedPrice() : "$14.99";
    }

    public void destroy() {
        if (billingClient != null && billingClient.isReady()) {
            billingClient.endConnection();
        }
    }
}
