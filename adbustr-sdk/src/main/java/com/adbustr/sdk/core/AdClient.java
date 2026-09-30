package com.adbustr.sdk.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.adbustr.sdk.AdError;
import com.adbustr.sdk.AdFormat;
import com.adbustr.sdk.AdbustrConfig;
import com.adbustr.sdk.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Talks to the Adbustr ad server: builds the request payload, POSTs it with the
 * configured timeout and a single retry on network/5xx failures, and parses the
 * JSON response.
 *
 * <p>Wire contract is shared with the Unity SDK: {@code POST {baseUrl}/sdk/v1/ad}.
 */
public final class AdClient {

    private static final String AD_PATH = "/sdk/v1/ad";

    /** Result of one ad request: a response, or a classified error. */
    public interface Callback {
        void onResponse(AdResponse response);

        void onError(AdError error);
    }

    private final Context appContext;
    private final AdbustrConfig config;

    public AdClient(Context context, AdbustrConfig config) {
        this.appContext = context.getApplicationContext();
        this.config = config;
    }

    /**
     * Requests an ad. The callback fires on the IO thread — the ad objects
     * marshal to the main thread themselves once creatives are ready.
     */
    public void requestAd(final AdFormat format, final String zone,
                          final int width, final int height, final Callback callback) {
        Threads.io(new Runnable() {
            @Override
            public void run() {
                execute(format, zone, width, height, callback);
            }
        });
    }

    private void execute(AdFormat format, String zone, int width, int height, Callback callback) {
        String payload;
        try {
            payload = buildRequest(format, zone, width, height).toString();
        } catch (JSONException e) {
            SdkLog.e("failed to build ad request", e);
            callback.onError(AdError.UNKNOWN);
            return;
        }

        String url = config.getBaseUrl() + AD_PATH;
        SdkLog.d("→ POST " + url + "\n" + payload);

        Http.Result result = Http.postJson(url, payload, config.getApiKey(), config.getTimeoutSeconds());

        // One retry, and only for failures a retry can plausibly fix. A 4xx means
        // a bad key/sid/zone — hammering it just burns the MAX load timeout.
        if (!result.isSuccess() && result.failure != Http.Failure.CLIENT_ERROR) {
            SdkLog.d("retrying after " + result.failure);
            result = Http.postJson(url, payload, config.getApiKey(), config.getTimeoutSeconds());
        }

        if (!result.isSuccess()) {
            SdkLog.d("← failed " + result.failure + " (http " + result.statusCode + ")");
            callback.onError(toAdError(result.failure));
            return;
        }

        String body = result.bodyAsString();
        SdkLog.d("← HTTP " + result.statusCode + " " + body);

        AdResponse response = AdResponse.parse(body);
        if (response == null) {
            callback.onError(AdError.PARSE_ERROR);
            return;
        }
        if (response.isNoBid()) {
            callback.onError(AdError.NO_FILL);
            return;
        }
        if (!response.isOk()) {
            callback.onError(AdError.INVALID_RESPONSE);
            return;
        }
        callback.onResponse(response);
    }

    private static AdError toAdError(Http.Failure failure) {
        switch (failure) {
            case TIMEOUT:
                return AdError.TIMEOUT;
            case CLIENT_ERROR:
                return AdError.INVALID_ZONE;
            case SERVER_ERROR:
            case NETWORK:
            default:
                return AdError.NETWORK_ERROR;
        }
    }

    private JSONObject buildRequest(AdFormat format, String zone, int width, int height)
            throws JSONException {
        int[] screen = DeviceInfo.screenSizePixels();

        JSONObject request = new JSONObject();
        request.put("api", 1);
        request.put("sid", config.getSiteId());
        request.put("zone", zone);
        request.put("format", format.getWireValue());
        // Creative kinds this SDK can render beyond image/video/native. The server
        // only sends raw HTML to clients that declare it — older SDKs and the
        // Unity SDK have no WebView renderer.
        request.put("caps", new JSONArray().put("html").put("mraid"));
        // Slot size in dp (OpenRTB units); device.w/h keep the physical pixels.
        request.put("w", DeviceInfo.pxToDp(width > 0 ? width : screen[0]));
        request.put("h", DeviceInfo.pxToDp(height > 0 ? height : screen[1]));
        request.put("app", appBlock());
        request.put("device", DeviceInfo.collect(appContext));
        request.put("user", new JSONObject().put("uid", UserIdentity.getOrCreateUid(appContext)));
        request.put("session", new JSONObject()
                .put("id", UserIdentity.getSessionId())
                .put("depth", UserIdentity.nextDepth()));
        return request;
    }

    private JSONObject appBlock() throws JSONException {
        String bundle = appContext.getPackageName();
        String name = bundle;
        String version = "";

        try {
            PackageManager packageManager = appContext.getPackageManager();
            PackageInfo info = packageManager.getPackageInfo(bundle, 0);
            version = info.versionName == null ? "" : info.versionName;
            CharSequence label = packageManager.getApplicationLabel(info.applicationInfo);
            if (label != null) {
                name = label.toString();
            }
        } catch (Throwable t) {
            // Package lookup can fail on exotic hosts; bundle id alone is enough
            // for the server to identify the app.
            SdkLog.d("app info unavailable: " + t);
        }

        JSONObject app = new JSONObject();
        app.put("bundle", bundle);
        app.put("name", name);
        app.put("ver", version);
        app.put("sdk", BuildConfig.SDK_VERSION);
        return app;
    }
}
