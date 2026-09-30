package com.adbustr.sdk.ui;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.adbustr.sdk.core.AdResponse;
import com.adbustr.sdk.core.SdkLog;
import com.adbustr.sdk.core.Threads;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Renders an HTML/JS creative (the DSP's adm) in a WebView, with an MRAID 3.0
 * container for playables and rich media.
 *
 * <p>The markup runs untouched, so the DSP's own impression pixels, verification
 * scripts (DoubleVerify, IAS…) and JS click trackers all fire exactly as they
 * would on the web.
 *
 * <p>Every top-level navigation — and {@code mraid.open()} — is intercepted: one
 * that follows a real tap is a click and goes to the external browser; one
 * without a tap is an auto-redirect and is dropped, so a creative can never
 * hijack the app.
 */
public final class HtmlCreativeView {

    /** Creative → SDK events. Always called on the main thread. */
    public interface Listener {

        /** User-initiated click-through to {@code url}. */
        void onClick(String url);

        /** The creative asked to be dismissed ({@code mraid.close()}). */
        void onCloseRequested();
    }

    /** MRAID placement type: fullscreen formats vs banners. */
    public enum Placement {
        INTERSTITIAL("interstitial"),
        INLINE("inline");

        final String mraidValue;

        Placement(String mraidValue) {
            this.mraidValue = mraidValue;
        }
    }

    /**
     * How long after a tap a navigation still counts as its result. Creatives
     * commonly fire their click trackers first and redirect a few hundred ms
     * later.
     */
    private static final long CLICK_WINDOW_MILLIS = 3000;

    private static final String BRIDGE_NAME = "AdbustrMraidBridge";

    private final WebView webView;
    private final Listener listener;
    private final String mraidScript;

    private long lastTapAt;
    private boolean pageLoaded;
    private boolean viewable;
    private boolean destroyed;

    private HtmlCreativeView(WebView webView, Placement placement, Listener listener) {
        this.webView = webView;
        this.listener = listener;
        this.mraidScript = mraidScript(placement);
    }

    /**
     * Builds the WebView and starts loading the creative. Returns null when the
     * system WebView is unavailable (missing, or mid-update) — the caller treats
     * that as a display failure. Must be called on the main thread.
     *
     * @param fillScreen true for fullscreen formats: the creative is scaled up to
     *                   fit; banners are only ever scaled down into the slot
     */
    public static HtmlCreativeView create(Context context, AdResponse.Html html,
                                          boolean fillScreen, Placement placement,
                                          Listener listener) {
        WebView webView;
        try {
            webView = new WebView(context);
        } catch (Throwable t) {
            SdkLog.e("WebView unavailable — cannot render HTML creative", t);
            return null;
        }
        HtmlCreativeView view = new HtmlCreativeView(webView, placement, listener);
        view.setUp(html, fillScreen);
        return view;
    }

    public View getView() {
        return webView;
    }

    /**
     * Tells the creative whether it is on screen (MRAID {@code viewableChange} /
     * {@code exposureChange}). Fullscreen ads follow the Activity's resume state,
     * banners their window attachment.
     */
    public void setViewable(boolean viewable) {
        if (destroyed || this.viewable == viewable) {
            return;
        }
        this.viewable = viewable;
        if (viewable) {
            webView.onResume();
        } else {
            webView.onPause();
        }
        pushMraidState();
    }

    /** Stops scripts and frees the renderer. Safe to call twice. */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.stopLoading();
            webView.removeJavascriptInterface(BRIDGE_NAME);
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Throwable t) {
            SdkLog.d("WebView destroy failed: " + t);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility"})
    private void setUp(AdResponse.Html html, boolean fillScreen) {
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false); // window.open → our interceptor
        settings.setMediaPlaybackRequiresUserGesture(false); // playables autoplay muted video
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // http trackers
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);

        webView.addJavascriptInterface(new Bridge(), BRIDGE_NAME);

        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    lastTapAt = SystemClock.elapsedRealtime();
                }
                return false; // the page still gets the touch
            }
        });

        webView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    pushMraidState();
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url, true);
            }

            @Override
            @TargetApi(Build.VERSION_CODES.N)
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // A creative living in an iframe (RTB House, Sovrn…) often
                // click-throughs by navigating that iframe, not the top page:
                // a subframe navigation carrying a user gesture is a click too.
                return intercept(request.getUrl().toString(),
                        request.isForMainFrame() || request.hasGesture());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return serveMraid(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                return serveMraid(request.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("about:")) {
                    return;
                }
                pageLoaded = true;
                pushMraidState(); // loading → default, fires mraid "ready"
            }
        });

        webView.loadDataWithBaseURL(html.baseUrl, document(html, fillScreen, mraidScript),
                "text/html", "utf-8", null);
    }

    private boolean intercept(String url, boolean mainFrameOrGesture) {
        if (url == null || url.startsWith("about:") || url.startsWith("data:")
                || url.startsWith("javascript:")) {
            return false;
        }
        if (!mainFrameOrGesture) {
            return false; // iframes (verification, rich media) load freely
        }
        openIfTapped(url);
        return true;
    }

    private void openIfTapped(String url) {
        if (destroyed) {
            return;
        }
        boolean tapped = SystemClock.elapsedRealtime() - lastTapAt <= CLICK_WINDOW_MILLIS;
        if (tapped) {
            lastTapAt = 0; // one tap, one click-through
            listener.onClick(url);
        } else {
            SdkLog.w("blocked auto-redirect from creative: " + url);
        }
    }

    /** The creative's {@code <script src="mraid.js">} gets our container. */
    private WebResourceResponse serveMraid(String url) {
        if (url == null) {
            return null;
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.endsWith("/mraid.js") && !path.equals("mraid.js")) {
            return null;
        }
        byte[] body = mraidScript.getBytes(Charset.forName("UTF-8"));
        return new WebResourceResponse("application/javascript", "UTF-8",
                new ByteArrayInputStream(body));
    }

    /** Sizes (dp) and viewability → {@code mraid._set}. No-op before load. */
    private void pushMraidState() {
        if (destroyed || !pageLoaded) {
            return;
        }
        DisplayMetrics metrics = webView.getResources().getDisplayMetrics();
        float density = metrics.density <= 0 ? 1f : metrics.density;
        String js = String.format(Locale.US,
                "window.mraid&&mraid._set&&mraid._set({w:%d,h:%d,sw:%d,sh:%d,v:%b});",
                Math.round(webView.getWidth() / density),
                Math.round(webView.getHeight() / density),
                Math.round(metrics.widthPixels / density),
                Math.round(metrics.heightPixels / density),
                viewable);
        try {
            webView.evaluateJavascript(js, null);
        } catch (Throwable t) {
            SdkLog.d("mraid state push failed: " + t);
        }
    }

    /** JS → native. Runs on the WebView's JavaBridge thread, so hop to main. */
    private final class Bridge {

        @JavascriptInterface
        public void open(final String url) {
            Threads.main(new Runnable() {
                @Override
                public void run() {
                    // mraid.open must be user-initiated, same rule as navigation.
                    if (url != null && !url.isEmpty()) {
                        openIfTapped(url);
                    }
                }
            });
        }

        @JavascriptInterface
        public void close() {
            Threads.main(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) {
                        listener.onCloseRequested();
                    }
                }
            });
        }
    }

    /**
     * Wraps the adm in a page that loads the MRAID container first, centres the
     * creative and scales it to the view. A full HTML document keeps its own
     * layout; only the container is injected.
     */
    static String document(AdResponse.Html html, boolean fillScreen, String mraidScript) {
        String markup = html.markup;
        String mraidTag = "<script>" + mraidScript + "</script>";
        String lower = markup.trim().toLowerCase(Locale.US);
        if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
            int head = markup.toLowerCase(Locale.US).indexOf("<head");
            int headEnd = head < 0 ? -1 : markup.indexOf('>', head);
            if (headEnd >= 0) {
                return markup.substring(0, headEnd + 1) + mraidTag + markup.substring(headEnd + 1);
            }
            return mraidTag + markup;
        }

        int w = html.width;
        int h = html.height;
        StringBuilder page = new StringBuilder(markup.length() + mraidTag.length() + 1024);
        page.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,")
                .append("maximum-scale=1,user-scalable=no\">")
                .append(mraidTag)
                .append("<style>html,body{margin:0;padding:0;width:100%;height:100%;")
                .append("overflow:hidden;background:transparent}")
                .append("body{display:flex;align-items:center;justify-content:center}")
                .append("#adbustr-ad{position:relative;flex:none;transform-origin:center center;");
        if (w > 0 && h > 0) {
            page.append("width:").append(w).append("px;height:").append(h).append("px;");
        } else {
            page.append("width:100%;height:100%;");
        }
        page.append("}</style></head><body><div id=\"adbustr-ad\">")
                .append(markup)
                .append("</div>");
        if (w > 0 && h > 0) {
            // Fit the declared size into the view: banners only shrink (never
            // blur-upscale into a larger slot), fullscreen grows to fill. The
            // page is parsed before the WebView is laid out (viewport 0×0) and
            // no resize event follows, so retry until a size exists.
            page.append("<script>(function(){var w=").append(w).append(",h=").append(h)
                    .append(",grow=").append(fillScreen ? "true" : "false")
                    .append(",tries=0;function fit(){var vw=window.innerWidth,vh=window.innerHeight;")
                    .append("if(!vw||!vh){if(tries++<100)setTimeout(fit,50);return}")
                    .append("var s=Math.min(vw/w,vh/h);if(!grow&&s>1)s=1;")
                    .append("document.getElementById('adbustr-ad').style.transform='scale('+s+')';}")
                    .append("fit();window.addEventListener('resize',fit);")
                    .append("window.addEventListener('load',fit);})();</script>");
        }
        page.append("</body></html>");
        return page.toString();
    }

    /**
     * MRAID 3.0 container. Idempotent: the inline copy and a creative's own
     * {@code <script src="mraid.js">} may both run. Unsupported features
     * (expand, resize, calendar, storePicture) report an MRAID error, as the
     * spec asks, instead of silently doing nothing.
     */
    static String mraidScript(Placement placement) {
        return "(function(){if(window.mraid)return;"
                + "var B=window." + BRIDGE_NAME + ",L={},state='loading',viewable=false,"
                + "placement='" + placement.mraidValue + "',p={w:0,h:0,sw:0,sh:0,v:false},"
                + "ep={width:0,height:0,useCustomClose:false,isModal:true},"
                + "op={allowOrientationChange:true,forceOrientation:'none'},rp={};"
                + "function fire(e,a){var l=L[e];if(!l)return;l=l.slice();"
                + "for(var i=0;i<l.length;i++){try{l[i].apply(null,a||[])}catch(x){}}}"
                + "function err(m,a){fire('error',[m,a])}"
                + "function rect(){return{x:0,y:0,width:p.w,height:p.h}}"
                + "function copy(d,s){if(s)for(var k in s)d[k]=s[k]}"
                + "var m={"
                + "getVersion:function(){return'3.0'},"
                + "getState:function(){return state},"
                + "getPlacementType:function(){return placement},"
                + "isViewable:function(){return viewable},"
                + "addEventListener:function(e,f){if(typeof f==='function')(L[e]=L[e]||[]).push(f)},"
                + "removeEventListener:function(e,f){if(!L[e])return;if(!f){delete L[e];return}"
                + "L[e]=L[e].filter(function(g){return g!==f})},"
                + "open:function(u){if(B)B.open(String(u))},"
                + "close:function(){if(B)B.close()},"
                + "unload:function(){if(B)B.close()},"
                + "expand:function(u){if(u){m.open(u)}else{err('expand is not supported','expand')}},"
                + "resize:function(){err('resize is not supported','resize')},"
                + "useCustomClose:function(){},"
                + "getExpandProperties:function(){return ep},"
                + "setExpandProperties:function(o){copy(ep,o)},"
                + "getResizeProperties:function(){return rp},"
                + "setResizeProperties:function(o){rp=o||{}},"
                + "getOrientationProperties:function(){return op},"
                + "setOrientationProperties:function(o){copy(op,o)},"
                + "getCurrentPosition:rect,getDefaultPosition:rect,"
                + "getSize:function(){return{width:p.w,height:p.h}},"
                + "getMaxSize:function(){return{width:p.w,height:p.h}},"
                + "getScreenSize:function(){return{width:p.sw,height:p.sh}},"
                + "getCurrentAppOrientation:function(){return{orientation:p.sw>p.sh?'landscape':'portrait',locked:false}},"
                + "getLocation:function(){return -1},"
                + "supports:function(f){return f==='inlineVideo'},"
                + "playVideo:function(u){m.open(u)},"
                + "storePicture:function(){err('storePicture is not supported','storePicture')},"
                + "createCalendarEvent:function(){err('createCalendarEvent is not supported','createCalendarEvent')},"
                + "_set:function(n){var sized=n.w!==p.w||n.h!==p.h;p=n;"
                + "if(state==='loading'){state='default';fire('ready');fire('stateChange',[state])}"
                + "else if(sized){fire('sizeChange',[p.w,p.h])}"
                + "if(viewable!==n.v){viewable=n.v;fire('viewableChange',[viewable])}"
                + "fire('exposureChange',[viewable?100:0,viewable?rect():null,null])}"
                + "};window.mraid=m;})();";
    }
}
