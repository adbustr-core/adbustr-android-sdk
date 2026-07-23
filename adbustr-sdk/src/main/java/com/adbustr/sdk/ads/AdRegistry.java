package com.adbustr.sdk.ads;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands a loaded ad to its presenting Activity.
 *
 * <p>Fullscreen ads hold bitmaps, players and listeners — none of which survive
 * an Intent — so the Intent carries only an id and the object is looked up here.
 */
public final class AdRegistry {

    private static final Map<String, FullscreenAd> ADS = new ConcurrentHashMap<>();

    private AdRegistry() {
    }

    static void put(String adId, FullscreenAd ad) {
        ADS.put(adId, ad);
    }

    /** Null when the ad was already consumed, or the process was restarted. */
    public static FullscreenAd get(String adId) {
        return adId == null ? null : ADS.get(adId);
    }

    public static void remove(String adId) {
        if (adId != null) {
            ADS.remove(adId);
        }
    }
}
