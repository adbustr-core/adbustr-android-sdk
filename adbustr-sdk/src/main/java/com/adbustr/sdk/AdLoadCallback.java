package com.adbustr.sdk;

/**
 * Result of an ad request. Exactly one method is invoked, always on the main
 * thread.
 *
 * @param <T> loaded ad type
 */
public interface AdLoadCallback<T> {

    void onAdLoaded(T ad);

    void onAdFailedToLoad(AdError error);
}
