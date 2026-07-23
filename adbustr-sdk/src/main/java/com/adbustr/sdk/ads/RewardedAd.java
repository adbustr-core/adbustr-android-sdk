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
import com.adbustr.sdk.ui.OrdBadgeView;
import com.adbustr.sdk.ui.Ui;

import java.io.File;
import java.util.Locale;

/**
 * A rewarded video ad. The mp4 is fetched to disk at load time so playback
 * starts without buffering.
 *
 * <p>Reward rule: the user earns it by reaching completion. Skipping before the
 * end fires the skip pixel and closes without a reward — the same semantics the
 * Unity SDK uses, so both integrations pay out identically.
 */
public final class RewardedAd extends FullscreenAd {

    /** How often playback progress is sampled for quartile pixels. */
    private static final long PROGRESS_POLL_MILLIS = 250;

    /** Result of preparing a rewarded ad. Delivered on the IO thread. */
    public interface Factory {
        /** Null when the video could not be downloaded. */
        void onPrepared(RewardedAd ad);
    }

    private final String clickUrl;
    private final int declaredDurationSeconds;
    private final int skipAfterSeconds;

    private File videoFile;

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
        this.clickUrl = response.video.clickUrl;
        this.declaredDurationSeconds = response.video.duration;
        this.skipAfterSeconds = response.video.skipAfter;
        this.videoFile = videoFile;
    }

    /**
     * Downloads the video and builds the ad. Calls back with null when the
     * response carries no usable video or the download fails.
     */
    public static void fromResponse(Context context, final AdResponse response,
                                    final Factory factory) {
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
        return videoFile != null && videoFile.exists() && videoFile.length() > 0;
    }

    @Override
    public View onCreateView(final Host host) {
        this.host = host;
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
