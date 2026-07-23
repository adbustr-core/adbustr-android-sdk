package com.adbustr.sdk.ads;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.adbustr.sdk.BannerAdListener;
import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.CreativeCache;
import com.adbustr.sdk.core.Threads;
import com.adbustr.sdk.core.TrackingDispatcher;
import com.adbustr.sdk.ui.OrdBadgeView;
import com.adbustr.sdk.ui.Ui;

/**
 * A banner / MREC ad. Unlike fullscreen formats it has no Activity of its own —
 * the host embeds {@link #getView()} in its own layout.
 *
 * <p>The impression fires when the view is actually attached and visible, not
 * when the ad loads: a banner cached off-screen has not been seen by anyone.
 */
public final class BannerAd {

    /** Result of preparing a banner. Delivered on the IO thread. */
    public interface Factory {
        /** Null when the creative could not be downloaded. */
        void onPrepared(BannerAd ad);
    }

    private final AdResponse.Tracking tracking;
    private final AdResponse.Ord ord;
    private final String clickUrl;
    private final long expiresAtMillis;

    private Bitmap creative;
    private FrameLayout container;
    private BannerAdListener listener;
    private boolean impressionFired;
    private boolean destroyed;

    private BannerAd(AdResponse response, Bitmap creative) {
        this.tracking = response.tracking;
        this.ord = response.ord;
        this.clickUrl = response.image.clickUrl;
        this.creative = creative;
        this.expiresAtMillis = SystemClock.elapsedRealtime() + Math.max(0, response.ttl) * 1000L;
    }

    /**
     * Downloads the creative and builds the banner.
     *
     * @param maxWidthPx  slot width in pixels, used to downsample the creative
     * @param maxHeightPx slot height in pixels
     */
    public static void fromResponse(Context context, final AdResponse response,
                                    int maxWidthPx, int maxHeightPx, final Factory factory) {
        if (response.image == null) {
            factory.onPrepared(null);
            return;
        }

        CreativeCache.loadBitmap(response.image.url, maxWidthPx, maxHeightPx,
                new CreativeCache.BitmapCallback() {
                    @Override
                    public void onResult(Bitmap bitmap) {
                        factory.onPrepared(bitmap == null ? null : new BannerAd(response, bitmap));
                    }
                });
    }

    public void setListener(BannerAdListener listener) {
        this.listener = listener;
    }

    public boolean isExpired() {
        return SystemClock.elapsedRealtime() >= expiresAtMillis;
    }

    /** Ad-marking metadata. Never null. */
    public AdResponse.Ord getOrd() {
        return ord;
    }

    /**
     * The view to embed. Built lazily and cached, so repeated calls return the
     * same instance. Must be called on the main thread.
     */
    public View getView(final Context context) {
        if (container != null) {
            return container;
        }

        container = new FrameLayout(context);
        container.setBackgroundColor(Color.TRANSPARENT);

        ImageView creativeView = new ImageView(context);
        creativeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        creativeView.setImageBitmap(creative);
        creativeView.setContentDescription("Advertisement");
        creativeView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClicked(context);
            }
        });
        container.addView(creativeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (OrdBadgeView.isRequired(ord)) {
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.BOTTOM | Gravity.START;
            int margin = Ui.dp(context, 4);
            badgeParams.setMargins(margin, margin, margin, margin);
            container.addView(new OrdBadgeView(context, ord), badgeParams);
        }

        container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                fireImpressionOnce();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // Nothing: the impression is one-shot for the life of the ad.
            }
        });

        // A view can already be attached by the time the host wires the listener
        // up (MAX adds it to its own container before returning to us).
        if (container.isAttachedToWindow()) {
            fireImpressionOnce();
        }

        return container;
    }

    private void fireImpressionOnce() {
        if (impressionFired || destroyed) {
            return;
        }
        impressionFired = true;
        TrackingDispatcher.fireAll(tracking.impression, true);
        Threads.main(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAdImpression();
                }
            }
        });
    }

    private void onClicked(Context context) {
        if (!Ui.openUrl(context, clickUrl)) {
            return;
        }
        TrackingDispatcher.fireAll(tracking.click, false);
        if (listener != null) {
            listener.onAdClicked();
        }
    }

    /** Detaches the banner and releases the creative. Safe to call twice. */
    public void destroy() {
        destroyed = true;
        listener = null;
        if (container != null) {
            if (container.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) container.getParent()).removeView(container);
            }
            container.removeAllViews();
            container = null;
        }
        creative = null;
    }
}
