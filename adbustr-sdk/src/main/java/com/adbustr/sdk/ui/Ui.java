package com.adbustr.sdk.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.adbustr.sdk.core.SdkLog;

/**
 * View helpers shared by the fullscreen ad renderers. Everything is built in
 * code — the SDK ships no resources, so a host app never has to merge our
 * layouts, styles or drawables into theirs.
 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    /**
     * The pill-shaped translucent control used for close / skip. Disabled state
     * is still visible (it shows the countdown), just not clickable.
     */
    public static TextView overlayButton(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);

        int paddingH = dp(context, 16);
        int paddingV = dp(context, 10);
        view.setPadding(paddingH, paddingV, paddingH, paddingV);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(180, 0, 0, 0));
        background.setCornerRadius(dp(context, 20));
        background.setStroke(dp(context, 1), Color.argb(90, 255, 255, 255));
        view.setBackground(background);

        return view;
    }

    /**
     * Opens the landing page. Returns false when nothing can handle the URL, so
     * the caller can skip firing the click pixel on a click that went nowhere.
     */
    public static boolean openUrl(Context context, String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String target = url.trim();
        try {
            Intent intent;
            if (target.startsWith("intent:")) {
                // Store links from HTML creatives. Never let markup address a
                // specific component — only a plain browsable VIEW.
                intent = Intent.parseUri(target, Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(intent);
                    return true;
                } catch (android.content.ActivityNotFoundException e) {
                    // Target app not installed — the creative's own web fallback.
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    return fallback != null && !fallback.startsWith("intent:")
                            && openUrl(context, fallback);
                }
            }
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            SdkLog.w("cannot open click url: " + url, t);
            return false;
        }
    }

    /** Immersive fullscreen without pulling in AndroidX. */
    @SuppressWarnings("deprecation")
    public static void applyImmersive(View decorView) {
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
