package com.adbustr.sdk;

import android.content.Context;

import com.adbustr.sdk.ads.BannerAd;
import com.adbustr.sdk.ads.InterstitialAd;
import com.adbustr.sdk.ads.RewardedAd;
import com.adbustr.sdk.core.AdClient;
import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.SdkLog;
import com.adbustr.sdk.core.Threads;

/**
 * Entry point of the Adbustr Ads SDK.
 *
 * <pre>
 * AdbustrSdk.initialize(context, new AdbustrConfig.Builder()
 *         .baseUrl("https://rtb.adbustr.com")
 *         .apiKey(BuildConfig.ADBUSTR_KEY)
 *         .siteId("1234")
 *         .build());
 *
 * AdbustrSdk.loadRewarded(context, "menu_reward", callback);
 * </pre>
 *
 * <p>All load callbacks are delivered on the main thread. The SDK never throws
 * to caller code — failures arrive as an {@link AdError}.
 */
public final class AdbustrSdk {

    /** SDK version, sent to the ad server as {@code app.sdk}. */
    public static final String VERSION = BuildConfig.SDK_VERSION;

    private static volatile boolean initialized;
    private static volatile AdClient client;
    private static volatile Context appContext;

    private AdbustrSdk() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Initializes the SDK. Safe to call more than once — a repeat call with a
     * valid config replaces the previous one, which is what a mediation adapter
     * does when the publisher changes server parameters.
     */
    public static synchronized void initialize(Context context, AdbustrConfig config) {
        if (context == null || config == null) {
            SdkLog.w("initialize failed: context and config are required");
            return;
        }
        if (!config.isValid()) {
            SdkLog.w("initialize failed: baseUrl, apiKey and siteId are all required");
            return;
        }

        appContext = context.getApplicationContext();
        SdkLog.setEnabled(config.isTestMode());
        client = new AdClient(appContext, config);
        initialized = true;

        SdkLog.d("initialized v" + VERSION + " sid=" + config.getSiteId()
                + " baseUrl=" + config.getBaseUrl());
    }

    /** Requests a fullscreen image interstitial. */
    public static void loadInterstitial(final Context context, String zone,
                                        final AdLoadCallback<InterstitialAd> callback) {
        if (!validate(zone, callback)) {
            return;
        }

        client.requestAd(AdFormat.INTERSTITIAL, zone, 0, 0, new AdClient.Callback() {
            @Override
            public void onResponse(AdResponse response) {
                InterstitialAd.fromResponse(context, response, new InterstitialAd.Factory() {
                    @Override
                    public void onPrepared(InterstitialAd ad) {
                        if (ad == null) {
                            fail(callback, AdError.CREATIVE_LOAD_FAILED);
                        } else {
                            succeed(callback, ad);
                        }
                    }
                });
            }

            @Override
            public void onError(AdError error) {
                fail(callback, error);
            }
        });
    }

    /** Requests a rewarded video. */
    public static void loadRewarded(final Context context, String zone,
                                    final AdLoadCallback<RewardedAd> callback) {
        if (!validate(zone, callback)) {
            return;
        }

        client.requestAd(AdFormat.REWARDED, zone, 0, 0, new AdClient.Callback() {
            @Override
            public void onResponse(AdResponse response) {
                RewardedAd.fromResponse(context, response, new RewardedAd.Factory() {
                    @Override
                    public void onPrepared(RewardedAd ad) {
                        if (ad == null) {
                            fail(callback, AdError.CREATIVE_LOAD_FAILED);
                        } else {
                            succeed(callback, ad);
                        }
                    }
                });
            }

            @Override
            public void onError(AdError error) {
                fail(callback, error);
            }
        });
    }

    /**
     * Requests a banner or MREC sized to the given slot.
     *
     * @param widthPx  slot width in pixels
     * @param heightPx slot height in pixels
     */
    public static void loadBanner(final Context context, String zone,
                                  final int widthPx, final int heightPx,
                                  final AdLoadCallback<BannerAd> callback) {
        if (!validate(zone, callback)) {
            return;
        }

        client.requestAd(AdFormat.BANNER, zone, widthPx, heightPx, new AdClient.Callback() {
            @Override
            public void onResponse(AdResponse response) {
                BannerAd.fromResponse(context, response, widthPx, heightPx,
                        new BannerAd.Factory() {
                            @Override
                            public void onPrepared(BannerAd ad) {
                                if (ad == null) {
                                    fail(callback, AdError.CREATIVE_LOAD_FAILED);
                                } else {
                                    succeed(callback, ad);
                                }
                            }
                        });
            }

            @Override
            public void onError(AdError error) {
                fail(callback, error);
            }
        });
    }

    private static boolean validate(String zone, AdLoadCallback<?> callback) {
        if (!initialized || client == null) {
            fail(callback, AdError.NOT_INITIALIZED);
            return false;
        }
        if (zone == null || zone.trim().isEmpty()) {
            fail(callback, AdError.INVALID_ZONE);
            return false;
        }
        return true;
    }

    private static <T> void succeed(final AdLoadCallback<T> callback, final T ad) {
        Threads.main(new Runnable() {
            @Override
            public void run() {
                callback.onAdLoaded(ad);
            }
        });
    }

    private static void fail(final AdLoadCallback<?> callback, final AdError error) {
        if (callback == null) {
            return;
        }
        Threads.main(new Runnable() {
            @Override
            public void run() {
                callback.onAdFailedToLoad(error);
            }
        });
    }
}
