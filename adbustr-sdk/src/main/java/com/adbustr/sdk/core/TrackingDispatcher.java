package com.adbustr.sdk.core;

/**
 * Fires tracking pixels as fire-and-forget GETs. Critical events (impression,
 * complete, reward) get retries with linear backoff because they are what the
 * publisher gets paid on; everything else is single-attempt best effort.
 */
public final class TrackingDispatcher {

    private static final int CRITICAL_MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 500;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private TrackingDispatcher() {
    }

    /** Fires every URL independently. No-op on null/empty input. */
    public static void fireAll(final String[] urls, final boolean critical) {
        if (urls == null || urls.length == 0) {
            return;
        }
        for (final String url : urls) {
            if (url == null || url.isEmpty()) {
                continue;
            }
            Threads.io(new Runnable() {
                @Override
                public void run() {
                    fireOne(url, critical);
                }
            });
        }
    }

    private static void fireOne(String url, boolean critical) {
        int maxAttempts = critical ? CRITICAL_MAX_ATTEMPTS : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Http.ping(url, REQUEST_TIMEOUT_SECONDS)) {
                SdkLog.d("pixel ok: " + url);
                return;
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(RETRY_BASE_DELAY_MILLIS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        SdkLog.d("pixel failed after " + maxAttempts + " attempt(s): " + url);
    }
}
