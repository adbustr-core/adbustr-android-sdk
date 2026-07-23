package com.adbustr.sdk.core;

import android.util.Log;

/** Logging, silent unless the config enables test mode. Warnings always print. */
public final class SdkLog {

    private static final String TAG = "AdbustrSdk";

    private static volatile boolean enabled;

    private SdkLog() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void d(String message) {
        if (enabled) {
            Log.d(TAG, message);
        }
    }

    public static void w(String message) {
        Log.w(TAG, message);
    }

    public static void w(String message, Throwable t) {
        Log.w(TAG, message, t);
    }

    public static void e(String message, Throwable t) {
        Log.e(TAG, message, t);
    }
}
