package com.adbustr.sdk.ads;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.CreativeCache;
import com.adbustr.sdk.core.Threads;
import com.adbustr.sdk.ui.OrdBadgeView;
import com.adbustr.sdk.ui.Ui;

/**
 * A fullscreen image interstitial. The creative is downloaded at load time, so
 * {@code show()} renders instantly.
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

    private Bitmap creative;
    private Runnable revealCloseTask;
    private boolean closeAllowed;

    private InterstitialAd(AdResponse response, Bitmap creative) {
        super(response);
        this.clickUrl = response.image.clickUrl;
        this.creative = creative;
    }

    /**
     * Downloads the creative and builds the ad. Calls back with null when the
     * response carries no usable image or the download fails — the caller maps
     * that to {@code CREATIVE_LOAD_FAILED}.
     */
    public static void fromResponse(Context context, final AdResponse response,
                                    final Factory factory) {
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
        return creative != null && !creative.isRecycled();
    }

    @Override
    public View onCreateView(final Host host) {
        final Context context = host.getActivity();

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        ImageView creativeView = new ImageView(context);
        creativeView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        creativeView.setImageBitmap(creative);
        creativeView.setContentDescription("Advertisement");
        creativeView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleClick(context, clickUrl);
            }
        });
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
        super.onHostDestroyed();
    }
}
