package com.adbustr.sdk;

/** Events of a banner / MREC ad view. All callbacks arrive on the main thread. */
public interface BannerAdListener {

    /** Fired once, when the banner view first becomes visible on screen. */
    void onAdImpression();

    /** The user tapped the banner. */
    void onAdClicked();
}
