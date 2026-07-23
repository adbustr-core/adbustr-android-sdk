package com.applovin.mediation.adapters;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;

import com.adbustr.mediation.applovin.BuildConfig;
import com.adbustr.sdk.AdError;
import com.adbustr.sdk.AdLoadCallback;
import com.adbustr.sdk.AdbustrConfig;
import com.adbustr.sdk.AdbustrSdk;
import com.adbustr.sdk.BannerAdListener;
import com.adbustr.sdk.FullscreenAdListener;
import com.adbustr.sdk.ads.BannerAd;
import com.adbustr.sdk.ads.InterstitialAd;
import com.adbustr.sdk.ads.RewardedAd;
import com.applovin.mediation.MaxAdFormat;
import com.applovin.mediation.adapter.MaxAdViewAdapter;
import com.applovin.mediation.adapter.MaxAdapterError;
import com.applovin.mediation.adapter.MaxInterstitialAdapter;
import com.applovin.mediation.adapter.MaxRewardedAdapter;
import com.applovin.mediation.adapter.listeners.MaxAdViewAdapterListener;
import com.applovin.mediation.adapter.listeners.MaxInterstitialAdapterListener;
import com.applovin.mediation.adapter.listeners.MaxRewardedAdapterListener;
import com.applovin.mediation.adapter.parameters.MaxAdapterInitializationParameters;
import com.applovin.mediation.adapter.parameters.MaxAdapterResponseParameters;
import com.applovin.sdk.AppLovinSdk;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AppLovin MAX custom SDK network adapter for Adbustr.
 *
 * <p>Register it in the MAX dashboard under Manage ▸ Networks ▸ Create Custom
 * Network with this exact fully-qualified class name; MAX instantiates it
 * reflectively, so the dashboard entry and this class must match character for
 * character.
 *
 * <p>Server parameters expected on the ad unit:
 * <ul>
 *   <li>{@code base_url} — Adbustr ad server, defaults to production</li>
 *   <li>{@code api_key} — publisher key sent as X-Adbustr-Key</li>
 *   <li>{@code sid} — Adbustr site id for this app</li>
 *   <li>{@code timeout_seconds} — optional, defaults to 2</li>
 * </ul>
 * The zone comes from the ad unit's third-party placement id.
 */
public class AdbustrMediationAdapter extends MediationAdapterBase
        implements MaxInterstitialAdapter, MaxRewardedAdapter, MaxAdViewAdapter {

    private static final String DEFAULT_BASE_URL = "https://rtb.adbustr.com";

    private static final String PARAM_BASE_URL = "base_url";
    private static final String PARAM_API_KEY = "api_key";
    private static final String PARAM_SID = "sid";
    private static final String PARAM_TIMEOUT = "timeout_seconds";

    private static final int DEFAULT_TIMEOUT_SECONDS = 2;

    /** MAX may create several adapter instances; the SDK is initialized once. */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private InterstitialAd interstitialAd;
    private RewardedAd rewardedAd;
    private BannerAd bannerAd;

    public AdbustrMediationAdapter(final AppLovinSdk sdk) {
        super(sdk);
    }

    // ---- MaxAdapter -------------------------------------------------------

    @Override
    public void initialize(final MaxAdapterInitializationParameters parameters,
                           final Activity activity,
                           final OnCompletionListener onCompletionListener) {
        Context context = resolveContext(activity);
        if (context != null && INITIALIZED.compareAndSet(false, true)) {
            AdbustrSdk.initialize(context,
                    toConfig(parameters.getServerParameters(), parameters.isTesting()));
        }
        // Always report success: with no context yet we initialize lazily on the
        // first load, and failing here would drop us out of the waterfall for
        // the whole session.
        onCompletionListener.onCompletion(InitializationStatus.INITIALIZED_SUCCESS, null);
    }

    @Override
    public String getSdkVersion() {
        return AdbustrSdk.VERSION;
    }

    @Override
    public String getAdapterVersion() {
        return BuildConfig.ADAPTER_VERSION;
    }

    @Override
    public void onDestroy() {
        if (interstitialAd != null) {
            interstitialAd.setListener(null);
            interstitialAd = null;
        }
        if (rewardedAd != null) {
            rewardedAd.setListener(null);
            rewardedAd = null;
        }
        if (bannerAd != null) {
            bannerAd.destroy();
            bannerAd = null;
        }
    }

    // ---- Interstitial -----------------------------------------------------

    @Override
    public void loadInterstitialAd(final MaxAdapterResponseParameters parameters,
                                   final Activity activity,
                                   final MaxInterstitialAdapterListener listener) {
        final String zone = parameters.getThirdPartyAdPlacementId();
        log("loading interstitial for zone " + zone);

        if (!ensureInitialized(activity, parameters)) {
            listener.onInterstitialAdLoadFailed(MaxAdapterError.INVALID_CONFIGURATION);
            return;
        }

        AdbustrSdk.loadInterstitial(resolveContext(activity), zone,
                new AdLoadCallback<InterstitialAd>() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        interstitialAd = ad;
                        listener.onInterstitialAdLoaded();
                    }

                    @Override
                    public void onAdFailedToLoad(AdError error) {
                        log("interstitial load failed: " + error);
                        listener.onInterstitialAdLoadFailed(toMaxError(error));
                    }
                });
    }

    @Override
    public void showInterstitialAd(final MaxAdapterResponseParameters parameters,
                                   final Activity activity,
                                   final MaxInterstitialAdapterListener listener) {
        final Context context = resolveContext(activity);
        if (interstitialAd == null || context == null) {
            log("interstitial not ready to show");
            listener.onInterstitialAdDisplayFailed(MaxAdapterError.AD_NOT_READY);
            return;
        }
        if (interstitialAd.isExpired()) {
            log("interstitial expired before show");
            listener.onInterstitialAdDisplayFailed(MaxAdapterError.AD_EXPIRED);
            return;
        }

        interstitialAd.setListener(new FullscreenAdListener.Adapter() {
            @Override
            public void onAdShown() {
                listener.onInterstitialAdDisplayed();
            }

            @Override
            public void onAdShowFailed(AdError error) {
                listener.onInterstitialAdDisplayFailed(toMaxError(error));
            }

            @Override
            public void onAdClicked() {
                listener.onInterstitialAdClicked();
            }

            @Override
            public void onAdClosed() {
                listener.onInterstitialAdHidden();
            }
        });

        AdError error = interstitialAd.show(context);
        if (error != AdError.NONE) {
            listener.onInterstitialAdDisplayFailed(toMaxError(error));
        }
    }

    // ---- Rewarded ---------------------------------------------------------

    @Override
    public void loadRewardedAd(final MaxAdapterResponseParameters parameters,
                               final Activity activity,
                               final MaxRewardedAdapterListener listener) {
        final String zone = parameters.getThirdPartyAdPlacementId();
        log("loading rewarded for zone " + zone);

        if (!ensureInitialized(activity, parameters)) {
            listener.onRewardedAdLoadFailed(MaxAdapterError.INVALID_CONFIGURATION);
            return;
        }

        AdbustrSdk.loadRewarded(resolveContext(activity), zone,
                new AdLoadCallback<RewardedAd>() {
                    @Override
                    public void onAdLoaded(RewardedAd ad) {
                        rewardedAd = ad;
                        listener.onRewardedAdLoaded();
                    }

                    @Override
                    public void onAdFailedToLoad(AdError error) {
                        log("rewarded load failed: " + error);
                        listener.onRewardedAdLoadFailed(toMaxError(error));
                    }
                });
    }

    @Override
    public void showRewardedAd(final MaxAdapterResponseParameters parameters,
                               final Activity activity,
                               final MaxRewardedAdapterListener listener) {
        final Context context = resolveContext(activity);
        if (rewardedAd == null || context == null) {
            log("rewarded not ready to show");
            listener.onRewardedAdDisplayFailed(MaxAdapterError.AD_NOT_READY);
            return;
        }
        if (rewardedAd.isExpired()) {
            log("rewarded expired before show");
            listener.onRewardedAdDisplayFailed(MaxAdapterError.AD_EXPIRED);
            return;
        }

        // The reward itself comes from the mediation layer — the publisher
        // configures its currency and amount in the MAX dashboard, not us.
        configureReward(parameters);

        rewardedAd.setListener(new FullscreenAdListener() {

            private boolean rewardEarned;

            @Override
            public void onAdShown() {
                listener.onRewardedAdDisplayed();
            }

            @Override
            public void onAdShowFailed(AdError error) {
                listener.onRewardedAdDisplayFailed(toMaxError(error));
            }

            @Override
            public void onAdClicked() {
                listener.onRewardedAdClicked();
            }

            @Override
            public void onUserRewarded() {
                rewardEarned = true;
            }

            @Override
            public void onAdClosed() {
                // MAX requires onUserRewarded strictly before onRewardedAdHidden.
                if (rewardEarned || shouldAlwaysRewardUser()) {
                    listener.onUserRewarded(getReward());
                }
                listener.onRewardedAdHidden();
            }
        });

        AdError error = rewardedAd.show(context);
        if (error != AdError.NONE) {
            listener.onRewardedAdDisplayFailed(toMaxError(error));
        }
    }

    // ---- Banner / MREC ----------------------------------------------------

    @Override
    public void loadAdViewAd(final MaxAdapterResponseParameters parameters,
                             final MaxAdFormat adFormat,
                             final Activity activity,
                             final MaxAdViewAdapterListener listener) {
        final String zone = parameters.getThirdPartyAdPlacementId();
        log("loading " + adFormat.getLabel() + " for zone " + zone);

        if (!ensureInitialized(activity, parameters)) {
            listener.onAdViewAdLoadFailed(MaxAdapterError.INVALID_CONFIGURATION);
            return;
        }

        final Context context = resolveContext(activity);
        int widthPx = dpToPx(context, adFormat.getSize().getWidth());
        int heightPx = dpToPx(context, adFormat.getSize().getHeight());

        AdbustrSdk.loadBanner(context, zone, widthPx, heightPx, new AdLoadCallback<BannerAd>() {
            @Override
            public void onAdLoaded(BannerAd ad) {
                bannerAd = ad;
                ad.setListener(new BannerAdListener() {
                    @Override
                    public void onAdImpression() {
                        listener.onAdViewAdDisplayed();
                    }

                    @Override
                    public void onAdClicked() {
                        listener.onAdViewAdClicked();
                    }
                });

                View view = ad.getView(context);
                listener.onAdViewAdLoaded(view);
            }

            @Override
            public void onAdFailedToLoad(AdError error) {
                log("ad view load failed: " + error);
                listener.onAdViewAdLoadFailed(toMaxError(error));
            }
        });
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Initializes the SDK if {@link #initialize} could not (no context at the
     * time). Returns false only when there is no context or the ad unit's server
     * parameters are unusable — the caller then reports a configuration error
     * rather than silently stalling the waterfall.
     */
    private boolean ensureInitialized(Activity activity, MaxAdapterResponseParameters parameters) {
        if (AdbustrSdk.isInitialized()) {
            return true;
        }
        Context context = resolveContext(activity);
        if (context == null) {
            return false;
        }
        AdbustrSdk.initialize(context,
                toConfig(parameters.getServerParameters(), parameters.isTesting()));
        INITIALIZED.set(AdbustrSdk.isInitialized());
        return AdbustrSdk.isInitialized();
    }

    /** The call's Activity when MAX supplies one, otherwise the app context. */
    private Context resolveContext(Activity activity) {
        return activity != null ? activity : getApplicationContext();
    }

    private static AdbustrConfig toConfig(Bundle serverParameters, boolean testing) {
        Bundle params = serverParameters == null ? new Bundle() : serverParameters;
        return new AdbustrConfig.Builder()
                .baseUrl(string(params, PARAM_BASE_URL, DEFAULT_BASE_URL))
                .apiKey(string(params, PARAM_API_KEY, ""))
                .siteId(string(params, PARAM_SID, ""))
                .timeoutSeconds(integer(params, PARAM_TIMEOUT, DEFAULT_TIMEOUT_SECONDS))
                .testMode(testing)
                .build();
    }

    private static String string(Bundle bundle, String key, String fallback) {
        String value = bundle.getString(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /**
     * Custom-network parameters are typed by whoever filled the dashboard form,
     * so a number may arrive as a String. Read defensively.
     */
    private static int integer(Bundle bundle, String key, int fallback) {
        Object raw = bundle.get(key);
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof String) {
            try {
                return Integer.parseInt(((String) raw).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int dpToPx(Context context, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }

    /**
     * Maps our error taxonomy onto MAX's. NO_FILL is the important one: it tells
     * MAX to move to the next waterfall line immediately instead of waiting out
     * the load timeout on a request we already know has no ad.
     */
    private static MaxAdapterError toMaxError(AdError error) {
        switch (error) {
            case NO_FILL:
                return MaxAdapterError.NO_FILL;
            case TIMEOUT:
                return MaxAdapterError.TIMEOUT;
            case NETWORK_ERROR:
                return MaxAdapterError.NO_CONNECTION;
            case INVALID_ZONE:
                return MaxAdapterError.INVALID_CONFIGURATION;
            case NOT_INITIALIZED:
                return MaxAdapterError.NOT_INITIALIZED;
            case PARSE_ERROR:
            case INVALID_RESPONSE:
                return MaxAdapterError.SERVER_ERROR;
            case CREATIVE_LOAD_FAILED:
                return MaxAdapterError.NO_FILL;
            case EXPIRED:
                return MaxAdapterError.AD_EXPIRED;
            case ALREADY_SHOWN:
                return MaxAdapterError.INVALID_LOAD_STATE;
            case DISPLAY_FAILED:
                return MaxAdapterError.INTERNAL_ERROR;
            case NOT_SUPPORTED:
                return MaxAdapterError.INVALID_CONFIGURATION;
            default:
                return MaxAdapterError.UNSPECIFIED;
        }
    }
}
