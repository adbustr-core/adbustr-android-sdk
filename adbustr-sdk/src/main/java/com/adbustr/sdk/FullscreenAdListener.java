package com.adbustr.sdk;

/**
 * Lifecycle of a fullscreen ad (interstitial or rewarded). All callbacks arrive
 * on the main thread.
 *
 * <p>Ordering guarantee the mediation layer relies on: {@link #onAdShown} fires
 * before any other display callback, {@link #onUserRewarded} fires strictly
 * before {@link #onAdClosed}, and {@link #onAdClosed} is the terminal event —
 * it fires exactly once per successful show.
 */
public interface FullscreenAdListener {

    /** The ad became visible; the impression pixel has been fired. */
    void onAdShown();

    /** Rendering failed after {@code show()} returned successfully. Terminal. */
    void onAdShowFailed(AdError error);

    /** The user tapped the creative. */
    void onAdClicked();

    /** Rewarded only: the video reached completion and the reward is earned. */
    void onUserRewarded();

    /** The ad was dismissed (completed, skipped or closed). Terminal. */
    void onAdClosed();

    /** No-op base so callers can override only what they need. */
    abstract class Adapter implements FullscreenAdListener {

        @Override
        public void onAdShown() {
        }

        @Override
        public void onAdShowFailed(AdError error) {
        }

        @Override
        public void onAdClicked() {
        }

        @Override
        public void onUserRewarded() {
        }

        @Override
        public void onAdClosed() {
        }
    }
}
