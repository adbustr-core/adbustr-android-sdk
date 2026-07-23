package com.adbustr.sdk.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent user id (SharedPreferences-backed), per-process session id and the
 * request-depth counter. Mirrors the Unity SDK's UserIdentity so the same
 * device reports a consistent shape to the ad server from either integration.
 */
public final class UserIdentity {

    private static final String PREFS_NAME = "adbustr_sdk";
    private static final String KEY_UID = "adbustr_uid";

    private static final AtomicInteger DEPTH = new AtomicInteger();

    private static volatile String uid;
    private static volatile String sessionId;

    private UserIdentity() {
    }

    /** Stable across launches. Generated on first use. */
    public static String getOrCreateUid(Context context) {
        String cached = uid;
        if (cached != null) {
            return cached;
        }
        synchronized (UserIdentity.class) {
            if (uid == null) {
                SharedPreferences prefs =
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String stored = prefs.getString(KEY_UID, null);
                if (stored == null || stored.isEmpty()) {
                    stored = UUID.randomUUID().toString();
                    prefs.edit().putString(KEY_UID, stored).apply();
                }
                uid = stored;
            }
            return uid;
        }
    }

    /** Stable for the lifetime of the process. */
    public static String getSessionId() {
        String cached = sessionId;
        if (cached != null) {
            return cached;
        }
        synchronized (UserIdentity.class) {
            if (sessionId == null) {
                sessionId = UUID.randomUUID().toString();
            }
            return sessionId;
        }
    }

    /** Increments and returns the ad-request depth for the current session. */
    public static int nextDepth() {
        return DEPTH.incrementAndGet();
    }
}
