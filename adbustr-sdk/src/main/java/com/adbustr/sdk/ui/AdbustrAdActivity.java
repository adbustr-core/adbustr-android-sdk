package com.adbustr.sdk.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.adbustr.sdk.ads.AdRegistry;
import com.adbustr.sdk.ads.FullscreenAd;
import com.adbustr.sdk.core.SdkLog;

/**
 * Host Activity for interstitial and rewarded ads. Owns nothing but the window:
 * the ad object builds its own view and handles its own lifecycle events.
 *
 * <p>Declared with {@code configChanges} covering rotation so a device turn
 * never recreates the Activity mid-video — a recreation would lose the player
 * state and, worse, replay the impression.
 */
public final class AdbustrAdActivity extends Activity implements FullscreenAd.Host {

    public static final String EXTRA_AD_ID = "com.adbustr.sdk.AD_ID";

    private FullscreenAd ad;
    private String adId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        adId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_AD_ID);
        ad = AdRegistry.get(adId);

        if (ad == null) {
            // The process was restarted, or the ad was already consumed. There is
            // nothing to render and no listener left to notify.
            SdkLog.w("no ad found for id " + adId + " — closing");
            finish();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.applyImmersive(getWindow().getDecorView());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View content = ad.onCreateView(this);
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The system restores the bars after a click-through returns; re-hide.
            Ui.applyImmersive(getWindow().getDecorView());
        }
    }

    @Override
    public Activity getActivity() {
        return this;
    }

    @Override
    public void closeAd() {
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        // A rewarded video is not dismissible before its skip point; letting back
        // through would hand out a free close and break the reward contract.
        if (ad == null || ad.isCloseAllowed()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (ad != null) {
            ad.onHostDestroyed();
            ad = null;
        }
        AdRegistry.remove(adId);
        super.onDestroy();
    }
}
