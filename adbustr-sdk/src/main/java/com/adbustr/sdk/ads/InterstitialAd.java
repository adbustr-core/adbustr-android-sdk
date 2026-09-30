package com.adbustr.sdk.ads;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.adbustr.sdk.AdError;
import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.CreativeCache;
import com.adbustr.sdk.core.Threads;
import com.adbustr.sdk.ui.HtmlCreativeView;
import com.adbustr.sdk.ui.OrdBadgeView;
import com.adbustr.sdk.ui.Ui;

/**
 * A fullscreen interstitial: an image downloaded at load time (so
 * {@code show()} renders instantly), or an HTML creative run in a WebView at
 * show time.
 *
 * <p>The close button is withheld for {@link #CLOSE_DELAY_MILLIS} — long enough
 * to count as a real impression, short enough not to trip MAX's user-experience
 * rules.
 */
public final class InterstitialAd extends FullscreenAd {

    private static final long CLOSE_DELAY_MILLIS = 3000;

    /** Result of preparing an interstitial. Delivered on the IO thread. */
    public interface Factory {
        /** Null when the creative could not be downloaded. */
        void onPrepared(InterstitialAd ad);
    }

    private final String clickUrl;
    /** Set for markup creatives; {@link #creative} is null then. */
    private final AdResponse.Html html;

    private Bitmap creative;
    private HtmlCreativeView htmlView;
    private Runnable revealCloseTask;
    private boolean closeAllowed;

    private InterstitialAd(AdResponse response, Bitmap creative) {
        super(response);
        this.clickUrl = response.image == null ? "" : response.image.clickUrl;
        this.html = response.html;
        this.creative = creative;
    }

    /**
     * Downloads the creative and builds the ad. Calls back with null when the
     * response carries no usable image or the download fails — the caller maps
     * that to {@code CREATIVE_LOAD_FAILED}.
     */
    public static void fromResponse(Context context, final AdResponse response,
                                    final Factory factory) {
        if (response.html != null) {
            // Rendered at show time: running the markup now would fire the DSP's
            // own impression pixels for an ad nobody has seen yet.
            factory.onPrepared(new InterstitialAd(response, null));
            return;
        }
        if (response.image == null) {
            factory.onPrepared(null);
            return;
        }

        int[] screen = com.adbustr.sdk.core.DeviceInfo.screenSizePixels();
        CreativeCache.loadBitmap(response.image.url, screen[0], screen[1],
                new CreativeCache.BitmapCallback() {
                    @Override
                    public void onResult(Bitmap bitmap) {
                        factory.onPrepared(bitmap == null
                                ? null
                                : new InterstitialAd(response, bitmap));
                    }
                });
    }

    @Override
    protected boolean isReadyToRender() {
        if (html != null) {
            return true;
        }
        return creative != null && !creative.isRecycled();
    }

    @Override
    public View onCreateView(final Host host) {
        final Context context = host.getActivity();

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        View creativeView;
        if (html != null) {
            htmlView = HtmlCreativeView.create(context, html, true,
                    HtmlCreativeView.Placement.INTERSTITIAL, new HtmlCreativeView.Listener() {
                        @Override
                        public void onClick(String url) {
                            handleClick(context, url);
                        }

                        @Override
                        public void onCloseRequested() {
                            // mraid.close() obeys the same minimum on-screen time
                            // as our own close button.
                            if (closeAllowed) {
                                host.closeAd();
                            }
                        }
                    });
            if (htmlView == null) {
                reportShowFailed(AdError.DISPLAY_FAILED);
                Threads.main(new Runnable() {
                    @Override
                    public void run() {
                        host.closeAd();
                    }
                });
                return root;
            }
            creativeView = htmlView.getView();
        } else {
            ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setImageBitmap(creative);
            imageView.setContentDescription("Advertisement");
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    handleClick(context, clickUrl);
                }
            });
            creativeView = imageView;
        }
        root.addView(creativeView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView closeButton = Ui.overlayButton(context, "✕");
        closeButton.setVisibility(View.GONE);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.closeAd();
            }
        });

        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        int margin = Ui.dp(context, 12);
        closeParams.setMargins(margin, margin, margin, margin);
        root.addView(closeButton, closeParams);

        if (OrdBadgeView.isRequired(ord)) {
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.BOTTOM | Gravity.START;
            badgeParams.setMargins(margin, margin, margin, margin);
            root.addView(new OrdBadgeView(context, ord), badgeParams);
        }

        revealCloseTask = new Runnable() {
            @Override
            public void run() {
                closeAllowed = true;
                closeButton.setVisibility(View.VISIBLE);
            }
        };
        Threads.mainDelayed(revealCloseTask, CLOSE_DELAY_MILLIS);

        reportShown();
        return root;
    }

    @Override
    public void onHostResumed() {
        if (htmlView != null) {
            htmlView.setViewable(true);
        }
    }

    @Override
    public void onHostPaused() {
        if (htmlView != null) {
            htmlView.setViewable(false);
        }
    }

    @Override
    public boolean isCloseAllowed() {
        // Back mirrors the close button: blocked until the button appears.
        return closeAllowed;
    }

    @Override
    public void onHostDestroyed() {
        if (revealCloseTask != null) {
            Threads.cancelMain(revealCloseTask);
            revealCloseTask = null;
        }
        // Dropped, not recycled: the ImageView can still draw one frame during
        // the closing transition, and drawing a recycled bitmap crashes.
        creative = null;
        if (htmlView != null) {
            htmlView.destroy();
            htmlView = null;
        }
        super.onHostDestroyed();
    }
}
