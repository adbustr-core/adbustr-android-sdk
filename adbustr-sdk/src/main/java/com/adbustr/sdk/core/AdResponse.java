package com.adbustr.sdk.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parsed ad-server response. Field names mirror the wire contract shared with
 * the Unity SDK — do not rename without updating the server.
 */
public final class AdResponse {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_NOBID = "nobid";

    public final String status;
    public final String requestId;
    public final String format;
    /** Seconds the fill stays valid after load. */
    public final int ttl;

    public final Video video;
    public final Image image;
    /** Raw HTML/JS creative (the DSP's adm), rendered in a WebView. */
    public final Html html;
    public final NativeAssets nativeAssets;
    public final Tracking tracking;
    public final Ord ord;

    private AdResponse(String status, String requestId, String format, int ttl,
                       Video video, Image image, Html html, NativeAssets nativeAssets,
                       Tracking tracking, Ord ord) {
        this.status = status;
        this.requestId = requestId;
        this.format = format;
        this.ttl = ttl;
        this.video = video;
        this.image = image;
        this.html = html;
        this.nativeAssets = nativeAssets;
        this.tracking = tracking;
        this.ord = ord;
    }

    public boolean isOk() {
        return STATUS_OK.equals(status);
    }

    public boolean isNoBid() {
        return STATUS_NOBID.equals(status);
    }

    /** Returns null when the payload is not valid JSON or has no status field. */
    public static AdResponse parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            String status = root.optString("status", null);
            if (status == null || status.isEmpty()) {
                return null;
            }
            return new AdResponse(
                    status,
                    root.optString("req_id", ""),
                    root.optString("format", ""),
                    root.optInt("ttl", 0),
                    Video.from(root.optJSONObject("video")),
                    Image.from(root.optJSONObject("image")),
                    Html.from(root.optJSONObject("html")),
                    NativeAssets.from(root.optJSONObject("native")),
                    Tracking.from(root.optJSONObject("tracking")),
                    Ord.from(root.optJSONObject("ord")));
        } catch (JSONException e) {
            SdkLog.d("response parse failed: " + e);
            return null;
        }
    }

    public static final class Video {

        public final String url;
        public final int width;
        public final int height;
        /** Creative duration in seconds, as declared by the server. */
        public final int duration;
        /** Seconds before skip is allowed; 0 or less means skippable immediately. */
        public final int skipAfter;
        public final String clickUrl;

        private Video(String url, int width, int height, int duration, int skipAfter, String clickUrl) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.skipAfter = skipAfter;
            this.clickUrl = clickUrl;
        }

        static Video from(JSONObject json) {
            if (json == null) {
                return null;
            }
            String url = json.optString("url", "");
            if (url.isEmpty()) {
                return null;
            }
            return new Video(
                    url,
                    json.optInt("w", 0),
                    json.optInt("h", 0),
                    json.optInt("duration", 0),
                    json.optInt("skip_after", 0),
                    json.optString("click_url", ""));
        }
    }

    public static final class Image {

        public final String url;
        public final int width;
        public final int height;
        public final String clickUrl;

        private Image(String url, int width, int height, String clickUrl) {
            this.url = url;
            this.width = width;
            this.height = height;
            this.clickUrl = clickUrl;
        }

        static Image from(JSONObject json) {
            if (json == null) {
                return null;
            }
            String url = json.optString("url", "");
            if (url.isEmpty()) {
                return null;
            }
            return new Image(
                    url,
                    json.optInt("w", 0),
                    json.optInt("h", 0),
                    json.optString("click_url", ""));
        }
    }

    /**
     * A markup creative. The SDK must execute it as-is: DSP HTML carries its own
     * impression pixels, verification scripts and JS click trackers, and
     * extracting a bare image out of it loses all of them.
     */
    public static final class Html {

        /** Base URL for protocol-relative and relative references in the markup. */
        static final String DEFAULT_BASE_URL = "https://rtb.adbustr.com/";

        public final String markup;
        /** Creative size in CSS pixels (dp); 0 when the DSP did not declare it. */
        public final int width;
        public final int height;
        public final String baseUrl;
        /**
         * Rewarded only: seconds the creative must be on screen before the
         * reward is granted and the close control appears.
         */
        public final int rewardAfter;

        private Html(String markup, int width, int height, String baseUrl, int rewardAfter) {
            this.markup = markup;
            this.width = width;
            this.height = height;
            this.baseUrl = baseUrl;
            this.rewardAfter = rewardAfter;
        }

        static Html from(JSONObject json) {
            if (json == null) {
                return null;
            }
            String markup = json.optString("adm", "");
            if (markup.trim().isEmpty()) {
                return null;
            }
            String baseUrl = json.optString("base_url", "");
            return new Html(
                    markup,
                    json.optInt("w", 0),
                    json.optInt("h", 0),
                    baseUrl.isEmpty() ? DEFAULT_BASE_URL : baseUrl,
                    json.optInt("reward_after", 0));
        }
    }

    public static final class NativeAssets {

        public final String title;
        public final String description;
        public final String cta;
        public final String domain;
        public final Image image;
        public final Image icon;
        public final String clickUrl;

        private NativeAssets(String title, String description, String cta, String domain,
                             Image image, Image icon, String clickUrl) {
            this.title = title;
            this.description = description;
            this.cta = cta;
            this.domain = domain;
            this.image = image;
            this.icon = icon;
            this.clickUrl = clickUrl;
        }

        static NativeAssets from(JSONObject json) {
            if (json == null) {
                return null;
            }
            return new NativeAssets(
                    json.optString("title", ""),
                    json.optString("desc", ""),
                    json.optString("cta", ""),
                    json.optString("domain", ""),
                    Image.from(json.optJSONObject("image")),
                    Image.from(json.optJSONObject("icon")),
                    json.optString("click_url", ""));
        }
    }

    /** Pixel URLs per event. Every array is non-null, possibly empty. */
    public static final class Tracking {

        private static final String[] EMPTY = new String[0];

        public final String[] impression;
        public final String[] click;
        public final String[] start;
        public final String[] firstQuartile;
        public final String[] midpoint;
        public final String[] thirdQuartile;
        public final String[] complete;
        public final String[] skip;
        public final String[] reward;

        private Tracking(JSONObject json) {
            this.impression = array(json, "imp");
            this.click = array(json, "click");
            this.start = array(json, "start");
            this.firstQuartile = array(json, "q1");
            this.midpoint = array(json, "mid");
            this.thirdQuartile = array(json, "q3");
            this.complete = array(json, "complete");
            this.skip = array(json, "skip");
            this.reward = array(json, "reward");
        }

        static Tracking from(JSONObject json) {
            return new Tracking(json);
        }

        private static String[] array(JSONObject json, String key) {
            if (json == null) {
                return EMPTY;
            }
            JSONArray raw = json.optJSONArray(key);
            if (raw == null || raw.length() == 0) {
                return EMPTY;
            }
            String[] urls = new String[raw.length()];
            int count = 0;
            for (int i = 0; i < raw.length(); i++) {
                String url = raw.optString(i, null);
                if (url != null && !url.isEmpty()) {
                    urls[count++] = url;
                }
            }
            if (count == urls.length) {
                return urls;
            }
            String[] trimmed = new String[count];
            System.arraycopy(urls, 0, trimmed, 0, count);
            return trimmed;
        }
    }

    /**
     * Russian ad-marking (ORD) metadata. When {@link #marked} is false the SDK
     * must render the mandatory "Реклама · {advertiser}" badge over the creative.
     */
    public static final class Ord {

        public final String erid;
        public final String advertiser;
        public final String inn;
        public final boolean marked;

        private Ord(String erid, String advertiser, String inn, boolean marked) {
            this.erid = erid;
            this.advertiser = advertiser;
            this.inn = inn;
            this.marked = marked;
        }

        static Ord from(JSONObject json) {
            if (json == null) {
                return new Ord("", "", "", false);
            }
            return new Ord(
                    json.optString("erid", ""),
                    json.optString("advertiser", ""),
                    json.optString("inn", ""),
                    json.optInt("marked", 0) == 1);
        }
    }
}
