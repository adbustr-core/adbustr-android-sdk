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
import com.adbustr.sdk.core.SdkLog;
import com.adbustr.sdk.core.Threads;
import com.adbustr.sdk.core.TrackingDispatcher;
import com.adbustr.sdk.ui.HtmlCreativeView;
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
    /** Set for markup creatives; {@link #creative} is null then. */
    private final AdResponse.Html html;

    private Bitmap creative;
    private HtmlCreativeView htmlView;
    private FrameLayout container;
    private BannerAdListener listener;
    private boolean impressionFired;
    private boolean destroyed;

    private BannerAd(AdResponse response, Bitmap creative) {
        this.tracking = response.tracking;
        this.ord = response.ord;
        this.clickUrl = response.image == null ? "" : response.image.clickUrl;
        this.html = response.html;
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
        if (response.html != null) {
            // Nothing to prefetch: the markup is inline, and running it early
            // would fire the DSP's own impression pixels before anyone sees it.
            factory.onPrepared(new BannerAd(response, null));
            return;
        }
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

        View creativeView = html != null ? createHtmlView(context) : createImageView(context);
        if (creativeView != null) {
            container.addView(creativeView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

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
                if (htmlView != null) {
                    htmlView.setViewable(true);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // The impression is one-shot for the life of the ad; only the
                // creative's MRAID viewability follows attachment.
                if (htmlView != null) {
                    htmlView.setViewable(false);
                }
            }
        });

        // A view can already be attached by the time the host wires the listener
        // up (MAX adds it to its own container before returning to us).
        if (container.isAttachedToWindow()) {
            fireImpressionOnce();
            if (htmlView != null) {
                htmlView.setViewable(true);
            }
        }

        return container;
    }

    private View createImageView(final Context context) {
        ImageView creativeView = new ImageView(context);
        creativeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        creativeView.setImageBitmap(creative);
        creativeView.setContentDescription("Advertisement");
        creativeView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClicked(context, clickUrl);
            }
        });
        return creativeView;
    }

    private View createHtmlView(final Context context) {
        htmlView = HtmlCreativeView.create(context, html, false,
                HtmlCreativeView.Placement.INLINE, new HtmlCreativeView.Listener() {
                    @Override
                    public void onClick(String url) {
                        onClicked(context, url);
                    }

                    @Override
                    public void onCloseRequested() {
                        // An inline banner has nothing to close into.
                    }
                });
        if (htmlView == null) {
            SdkLog.w("banner HTML creative not rendered");
            return null;
        }
        return htmlView.getView();
    }

    private void fireImpressionOnce() {
        if (impressionFired || destroyed) {
            return;
        }
        if (html != null && htmlView == null) {
            return; // nothing was rendered, so nothing was seen
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

    private void onClicked(Context context, String url) {
        if (destroyed || !Ui.openUrl(context, url)) {
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
        if (htmlView != null) {
            htmlView.destroy();
            htmlView = null;
        }
        creative = null;
    }
}
