package com.adbustr.sdk.ads;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.adbustr.sdk.AdError;
import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.CreativeCache;
import com.adbustr.sdk.core.SdkLog;
import com.adbustr.sdk.core.Threads;
import com.adbustr.sdk.core.TrackingDispatcher;
import com.adbustr.sdk.ui.HtmlCreativeView;
import com.adbustr.sdk.ui.OrdBadgeView;
import com.adbustr.sdk.ui.Ui;

import java.io.File;
import java.util.Locale;

/**
 * A rewarded ad in one of three kinds, whichever the auction returned:
 * <ul>
 *   <li><b>video</b> — VAST mp4, fetched to disk at load time so playback starts
 *       without buffering;</li>
 *   <li><b>display</b> — an HTML creative;</li>
 *   <li><b>playable</b> — an MRAID HTML5 mini-game.</li>
 * </ul>
 *
 * <p>Reward rule for video: the user earns it by reaching completion. Skipping
 * before the end fires the skip pixel and closes without a reward — the same
 * semantics the Unity SDK uses, so both integrations pay out identically.
 *
 * <p>Reward rule for display / playable: the creative must stay on screen for
 * {@code reward_after} seconds (counted only while the app is in the
 * foreground). Until then there is no way out — no close button, back is
 * blocked, {@code mraid.close()} is ignored; after that the reward is granted
 * and the close button appears.
 */
public final class RewardedAd extends FullscreenAd {

    /** How often playback progress is sampled for quartile pixels. */
    private static final long PROGRESS_POLL_MILLIS = 250;

    /** Display/playable fallback when the server sent no reward_after. */
    private static final int DEFAULT_HTML_REWARD_SECONDS = 15;

    /** Result of preparing a rewarded ad. Delivered on the IO thread. */
    public interface Factory {
        /** Null when the video could not be downloaded. */
        void onPrepared(RewardedAd ad);
    }

    private final String clickUrl;
    private final int declaredDurationSeconds;
    private final int skipAfterSeconds;

    private File videoFile;

    /** Set for display/playable creatives; video fields are unused then. */
    private final AdResponse.Html html;
    private HtmlCreativeView htmlView;
    private Runnable countdownTask;
    private int rewardSecondsLeft;

    private VideoView videoView;
    private TextView skipButton;
    private Runnable progressTask;
    private Host host;

    private boolean firstQuartileFired;
    private boolean midpointFired;
    private boolean thirdQuartileFired;
    private boolean completed;
    private boolean rewarded;
    private boolean skipAllowed;

    private RewardedAd(AdResponse response, File videoFile) {
        super(response);
        this.html = response.video == null ? response.html : null;
        this.clickUrl = response.video == null ? "" : response.video.clickUrl;
        this.declaredDurationSeconds = response.video == null ? 0 : response.video.duration;
        this.skipAfterSeconds = response.video == null ? 0 : response.video.skipAfter;
        this.videoFile = videoFile;
        this.rewardSecondsLeft = html == null || html.rewardAfter <= 0
                ? DEFAULT_HTML_REWARD_SECONDS
                : html.rewardAfter;
    }

    /**
     * Downloads the video and builds the ad. Calls back with null when the
     * response carries no usable video or the download fails.
     */
    public static void fromResponse(Context context, final AdResponse response,
                                    final Factory factory) {
        if (response.video == null && response.html != null) {
            // Display / playable: the markup is inline and runs at show time,
            // so the DSP's own impression pixels don't fire early.
            factory.onPrepared(new RewardedAd(response, null));
            return;
        }
        if (response.video == null) {
            factory.onPrepared(null);
            return;
        }

        CreativeCache.loadVideoFile(context, response.video.url, new CreativeCache.FileCallback() {
            @Override
            public void onResult(File file) {
                factory.onPrepared(file == null ? null : new RewardedAd(response, file));
            }
        });
    }

    @Override
    protected boolean isReadyToRender() {
        if (html != null) {
            return true;
        }
        return videoFile != null && videoFile.exists() && videoFile.length() > 0;
    }

    @Override
    public View onCreateView(final Host host) {
        this.host = host;
        if (html != null) {
            return createHtmlView(host);
        }
        final Context context = host.getActivity();

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        videoView = new VideoView(context);
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        videoParams.gravity = Gravity.CENTER;
        root.addView(videoView, videoParams);

        // A transparent overlay takes the click: VideoView swallows touches
        // inconsistently across OEM builds, and a click-through that only works
        // on some devices is worse than none.
        View clickCatcher = new View(context);
        clickCatcher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleClick(context, clickUrl);
            }
        });
        root.addView(clickCatcher, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        skipButton = Ui.overlayButton(context, skipLabel(skipAfterSeconds));
        skipButton.setEnabled(skipAfterSeconds <= 0);
        skipAllowed = skipAfterSeconds <= 0;
        skipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSkipPressed();
            }
        });

        FrameLayout.LayoutParams skipParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        skipParams.gravity = Gravity.TOP | Gravity.END;
        int margin = Ui.dp(context, 12);
        skipParams.setMargins(margin, margin, margin, margin);
        root.addView(skipButton, skipParams);

        if (OrdBadgeView.isRequired(ord)) {
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.BOTTOM | Gravity.START;
            badgeParams.setMargins(margin, margin, margin, margin);
            root.addView(new OrdBadgeView(context, ord), badgeParams);
        }

        startPlayback();
        return root;
    }

    // ---- display / playable ----------------------------------------------

    private View createHtmlView(final Host host) {
        final Context context = host.getActivity();

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        htmlView = HtmlCreativeView.create(context, html, true,
                HtmlCreativeView.Placement.INTERSTITIAL, new HtmlCreativeView.Listener() {
                    @Override
                    public void onClick(String url) {
                        handleClick(context, url);
                    }

                    @Override
                    public void onCloseRequested() {
                        // A playable's own "close" at the end of the game counts
                        // only once the reward is earned; before that the user
                        // would lose it, so we keep the ad up.
                        if (skipAllowed) {
                            closeHost();
                        } else {
                            SdkLog.d("mraid.close() before reward — ignored");
                        }
                    }
                });
        if (htmlView == null) {
            reportShowFailed(AdError.DISPLAY_FAILED);
            Threads.main(new Runnable() {
                @Override
                public void run() {
                    closeHost();
                }
            });
            return root;
        }
        root.addView(htmlView.getView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        skipButton = Ui.overlayButton(context, rewardLabel(rewardSecondsLeft));
        skipButton.setEnabled(false);
        skipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (skipAllowed) {
                    closeHost();
                }
            }
        });
        int margin = Ui.dp(context, 12);
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.gravity = Gravity.TOP | Gravity.END;
        buttonParams.setMargins(margin, margin, margin, margin);
        root.addView(skipButton, buttonParams);

        if (OrdBadgeView.isRequired(ord)) {
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            badgeParams.gravity = Gravity.BOTTOM | Gravity.START;
            badgeParams.setMargins(margin, margin, margin, margin);
            root.addView(new OrdBadgeView(context, ord), badgeParams);
        }

        reportShown();
        TrackingDispatcher.fireAll(tracking.start, false);
        return root;
    }

    /** Ticks once a second while the app is in the foreground. */
    private final class CountdownTask implements Runnable {

        @Override
        public void run() {
            if (countdownTask != this || skipButton == null) {
                return;
            }
            rewardSecondsLeft--;
            if (rewardSecondsLeft > 0) {
                skipButton.setText(rewardLabel(rewardSecondsLeft));
                Threads.mainDelayed(this, 1000);
                return;
            }
            countdownTask = null;
            onRewardTimeReached();
        }
    }

    private void onRewardTimeReached() {
        completed = true;
        skipAllowed = true;
        TrackingDispatcher.fireAll(tracking.complete, true);
        grantReward();
        if (skipButton != null) {
            skipButton.setText("✕");
            skipButton.setEnabled(true);
        }
    }

    private void startCountdown() {
        if (completed || countdownTask != null) {
            return;
        }
        countdownTask = new CountdownTask();
        Threads.mainDelayed(countdownTask, 1000);
    }

    private void stopCountdown() {
        if (countdownTask != null) {
            Threads.cancelMain(countdownTask);
            countdownTask = null;
        }
    }

    private static String rewardLabel(int remainingSeconds) {
        return String.format(Locale.getDefault(), "Награда через %d", remainingSeconds);
    }

    @Override
    public void onHostResumed() {
        if (htmlView != null) {
            htmlView.setViewable(true);
            startCountdown();
        }
    }

    @Override
    public void onHostPaused() {
        if (htmlView != null) {
            // The reward is for time actually on screen, not time in the background.
            stopCountdown();
            htmlView.setViewable(false);
        }
    }

    // ---- video ------------------------------------------------------------

    private void startPlayback() {
        videoView.setVideoURI(Uri.fromFile(videoFile));

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer player) {
                player.setLooping(false);
                videoView.start();

                reportShown();
                TrackingDispatcher.fireAll(tracking.start, false);

                progressTask = new ProgressTask();
                Threads.mainDelayed(progressTask, PROGRESS_POLL_MILLIS);
            }
        });

        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer player) {
                onVideoCompleted();
            }
        });

        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer player, int what, int extra) {
                SdkLog.w("video playback error what=" + what + " extra=" + extra);
                reportShowFailed(AdError.DISPLAY_FAILED);
                closeHost();
                return true;
            }
        });
    }

    /** Polls playback to fire quartiles and drive the skip countdown. */
    private final class ProgressTask implements Runnable {

        @Override
        public void run() {
            if (videoView == null) {
                return;
            }

            int positionMillis;
            int durationMillis;
            try {
                positionMillis = videoView.getCurrentPosition();
                durationMillis = videoView.getDuration();
            } catch (IllegalStateException e) {
                // Player torn down between the post and this tick.
                return;
            }

            if (durationMillis <= 0) {
                durationMillis = declaredDurationSeconds * 1000;
            }

            if (durationMillis > 0) {
                float progress = (float) positionMillis / durationMillis;
                if (!firstQuartileFired && progress >= 0.25f) {
                    firstQuartileFired = true;
                    TrackingDispatcher.fireAll(tracking.firstQuartile, false);
                }
                if (!midpointFired && progress >= 0.5f) {
                    midpointFired = true;
                    TrackingDispatcher.fireAll(tracking.midpoint, false);
                }
                if (!thirdQuartileFired && progress >= 0.75f) {
                    thirdQuartileFired = true;
                    TrackingDispatcher.fireAll(tracking.thirdQuartile, false);
                }
            }

            updateSkipButton(positionMillis / 1000);

            if (progressTask != null) {
                Threads.mainDelayed(progressTask, PROGRESS_POLL_MILLIS);
            }
        }
    }

    private void updateSkipButton(int elapsedSeconds) {
        if (skipButton == null) {
            return;
        }
        int remaining = skipAfterSeconds - elapsedSeconds;
        if (remaining > 0) {
            skipButton.setText(skipLabel(remaining));
            skipButton.setEnabled(false);
            skipAllowed = false;
        } else if (!skipAllowed) {
            skipAllowed = true;
            skipButton.setText("Пропустить ✕");
            skipButton.setEnabled(true);
        }
    }

    private static String skipLabel(int remainingSeconds) {
        return remainingSeconds > 0
                ? String.format(Locale.getDefault(), "Пропустить через %d", remainingSeconds)
                : "Пропустить ✕";
    }

    private void onVideoCompleted() {
        if (completed) {
            return;
        }
        completed = true;
        stopProgressPolling();

        // A short video can finish before the 75% tick lands; fire the missing
        // quartiles so the DSP sees a well-formed sequence.
        if (!firstQuartileFired) {
            firstQuartileFired = true;
            TrackingDispatcher.fireAll(tracking.firstQuartile, false);
        }
        if (!midpointFired) {
            midpointFired = true;
            TrackingDispatcher.fireAll(tracking.midpoint, false);
        }
        if (!thirdQuartileFired) {
            thirdQuartileFired = true;
            TrackingDispatcher.fireAll(tracking.thirdQuartile, false);
        }

        TrackingDispatcher.fireAll(tracking.complete, true);
        grantReward();
        closeHost();
    }

    private void onSkipPressed() {
        if (!skipAllowed) {
            return;
        }
        TrackingDispatcher.fireAll(tracking.skip, false);
        closeHost();
    }

    private void grantReward() {
        if (rewarded) {
            return;
        }
        rewarded = true;
        reportRewarded();
    }

    private void closeHost() {
        if (host != null) {
            host.closeAd();
        }
    }

    private void stopProgressPolling() {
        if (progressTask != null) {
            Threads.cancelMain(progressTask);
            progressTask = null;
        }
    }

    @Override
    public boolean isCloseAllowed() {
        return skipAllowed;
    }

    @Override
    public void onHostDestroyed() {
        stopProgressPolling();
        stopCountdown();
        if (htmlView != null) {
            htmlView.destroy();
            htmlView = null;
        }
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Throwable t) {
                SdkLog.d("stopPlayback failed: " + t);
            }
            videoView = null;
        }
        skipButton = null;
        host = null;
        videoFile = null;
        super.onHostDestroyed();
    }
}
