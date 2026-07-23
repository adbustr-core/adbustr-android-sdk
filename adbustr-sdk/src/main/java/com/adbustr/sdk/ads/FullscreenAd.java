package com.adbustr.sdk.ads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;

import com.adbustr.sdk.AdError;
import com.adbustr.sdk.FullscreenAdListener;
import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.SdkLog;
import com.adbustr.sdk.core.Threads;
import com.adbustr.sdk.core.TrackingDispatcher;
import com.adbustr.sdk.ui.AdbustrAdActivity;
import com.adbustr.sdk.ui.Ui;

import java.util.UUID;

/**
 * Shared machinery of interstitial and rewarded ads: ttl, one-shot show,
 * listener dispatch and the click / impression plumbing. Subclasses only supply
 * the creative's view.
 *
 * <p>Callback ordering the mediation layer depends on is enforced here:
 * {@code onAdShown} exactly once, {@code onAdClosed} exactly once and always
 * last.
 */
public abstract class FullscreenAd {

    /** Implemented by the presenting Activity. */
    public interface Host {

        Activity getActivity();

        /** Dismisses the ad. Safe to call more than once. */
        void closeAd();
    }

    private final String adId = UUID.randomUUID().toString();
    private final long expiresAtMillis;

    protected final AdResponse.Tracking tracking;
    protected final AdResponse.Ord ord;

    private FullscreenAdListener listener;
    private boolean shown;
    private boolean shownReported;
    private boolean closedReported;

    protected FullscreenAd(AdResponse response) {
        this.tracking = response.tracking;
        this.ord = response.ord;
        this.expiresAtMillis = SystemClock.elapsedRealtime() + Math.max(0, response.ttl) * 1000L;
    }

    /** Id the presenting Activity uses to find this ad. */
    public final String getAdId() {
        return adId;
    }

    /** Ad-marking metadata. Never null. */
    public final AdResponse.Ord getOrd() {
        return ord;
    }

    public final boolean isExpired() {
        return SystemClock.elapsedRealtime() >= expiresAtMillis;
    }

    /** True once {@link #show} has been called; an ad may only be shown once. */
    public final boolean isShown() {
        return shown;
    }

    public final void setListener(FullscreenAdListener listener) {
        this.listener = listener;
    }

    /**
     * Presents the ad fullscreen. Returns the reason it could not be presented,
     * or {@link AdError#NONE} when the Activity was launched — display failures
     * after that point arrive via {@link FullscreenAdListener#onAdShowFailed}.
     */
    public final AdError show(Context context) {
        if (shown) {
            return AdError.ALREADY_SHOWN;
        }
        if (isExpired()) {
            return AdError.EXPIRED;
        }
        if (!isReadyToRender()) {
            return AdError.DISPLAY_FAILED;
        }

        shown = true;
        AdRegistry.put(adId, this);

        try {
            Intent intent = new Intent(context, AdbustrAdActivity.class);
            intent.putExtra(AdbustrAdActivity.EXTRA_AD_ID, adId);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return AdError.NONE;
        } catch (Throwable t) {
            // Almost always a missing <activity> entry in the host app's merged
            // manifest — worth a loud log, because it fails 100% of the time.
            SdkLog.e("cannot start AdbustrAdActivity — is the SDK manifest merged?", t);
            AdRegistry.remove(adId);
            return AdError.DISPLAY_FAILED;
        }
    }

    /** Subclasses report whether their creative actually made it into memory. */
    protected abstract boolean isReadyToRender();

    /** Builds the creative's view hierarchy. Called on the main thread. */
    public abstract View onCreateView(Host host);

    /** Whether the back button may dismiss the ad right now. */
    public boolean isCloseAllowed() {
        return true;
    }

    /** Called when the presenting Activity goes away. Releases native resources. */
    public void onHostDestroyed() {
        AdRegistry.remove(adId);
        reportClosed();
    }

    // ---- events -----------------------------------------------------------

    /** Fires the impression pixel and reports the show. Idempotent. */
    protected final void reportShown() {
        if (shownReported) {
            return;
        }
        shownReported = true;
        TrackingDispatcher.fireAll(tracking.impression, true);
        Threads.main(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAdShown();
                }
            }
        });
    }

    protected final void reportShowFailed(final AdError error) {
        SdkLog.w("show failed: " + error + " — " + error.getMessage());
        Threads.main(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAdShowFailed(error);
                }
            }
        });
    }

    /** Opens the landing page, fires click pixels and reports the click. */
    protected final void handleClick(Context context, String clickUrl) {
        if (!Ui.openUrl(context, clickUrl)) {
            return;
        }
        TrackingDispatcher.fireAll(tracking.click, false);
        Threads.main(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAdClicked();
                }
            }
        });
    }

    protected final void reportRewarded() {
        TrackingDispatcher.fireAll(tracking.reward, true);
        Threads.main(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onUserRewarded();
                }
            }
        });
    }

    private void reportClosed() {
        if (closedReported) {
            return;
        }
        closedReported = true;
        Threads.main(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAdClosed();
                }
            }
        });
    }
}
