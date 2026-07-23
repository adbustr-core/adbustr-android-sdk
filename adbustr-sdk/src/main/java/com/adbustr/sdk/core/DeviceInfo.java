package com.adbustr.sdk.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Builds the {@code device} block of the ad request.
 *
 * <p>The Google advertising id is resolved reflectively so the SDK carries no
 * play-services dependency: publishers who bundle it get a real ifa, the rest
 * get an empty one instead of a build failure. That lookup is a blocking IPC
 * call, so it must run on the IO thread — never on the caller's.
 */
public final class DeviceInfo {

    /** Cached because the ifa lookup costs an IPC round-trip per ad request. */
    private static volatile String cachedIfa;
    private static volatile boolean cachedLmt;
    private static volatile boolean ifaResolved;

    private DeviceInfo() {
    }

    /** Must be called from {@link Threads#io}. */
    public static JSONObject collect(Context context) throws JSONException {
        resolveAdvertisingIdOnce(context);

        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();

        JSONObject device = new JSONObject();
        device.put("ifa", cachedIfa == null ? "" : cachedIfa);
        device.put("lmt", cachedLmt ? 1 : 0);
        device.put("os", "android");
        device.put("osv", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
        device.put("make", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        device.put("model", Build.MODEL == null ? "" : Build.MODEL);
        device.put("w", metrics.widthPixels);
        device.put("h", metrics.heightPixels);
        device.put("lang", language());
        device.put("con", connectionType(context));
        device.put("battery", batteryLevel(context));
        return device;
    }

    /** Screen size in pixels, for the request's top-level w/h. */
    public static int[] screenSizePixels() {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    private static void resolveAdvertisingIdOnce(Context context) {
        if (ifaResolved) {
            return;
        }
        synchronized (DeviceInfo.class) {
            if (ifaResolved) {
                return;
            }
            try {
                Class<?> clientClass =
                        Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient");
                Method getInfo = clientClass.getMethod("getAdvertisingIdInfo", Context.class);
                Object info = getInfo.invoke(null, context.getApplicationContext());
                if (info != null) {
                    Method getId = info.getClass().getMethod("getId");
                    Method isLat = info.getClass().getMethod("isLimitAdTrackingEnabled");
                    Object id = getId.invoke(info);
                    Object lat = isLat.invoke(info);
                    cachedIfa = id == null ? "" : String.valueOf(id);
                    cachedLmt = Boolean.TRUE.equals(lat);
                }
            } catch (Throwable t) {
                // No Play Services, no GMS on the device, or the app didn't bundle
                // the identifier library. Degrade to an empty ifa — the request
                // must still go out.
                SdkLog.d("advertising id unavailable: " + t);
                cachedIfa = "";
                cachedLmt = false;
            } finally {
                ifaResolved = true;
            }
        }
    }

    private static String language() {
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        return language == null || language.isEmpty() ? "en" : language;
    }

    /** IAB-style connection type: 0 unknown, 2 wifi/lan, 3 cellular. */
    private static int connectionType(Context context) {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return 0;
            }
            NetworkInfo active = manager.getActiveNetworkInfo();
            if (active == null || !active.isConnected()) {
                return 0;
            }
            switch (active.getType()) {
                case ConnectivityManager.TYPE_WIFI:
                case ConnectivityManager.TYPE_ETHERNET:
                    return 2;
                case ConnectivityManager.TYPE_MOBILE:
                    return 3;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            // Missing ACCESS_NETWORK_STATE on an odd host app must not kill the request.
            return 0;
        }
    }

    /** Battery percentage, or 100 when it can't be read. */
    private static int batteryLevel(Context context) {
        try {
            Intent status = context.getApplicationContext()
                    .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (status == null) {
                return 100;
            }
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return 100;
            }
            return Math.round(level * 100f / scale);
        } catch (Throwable t) {
            return 100;
        }
    }
}
