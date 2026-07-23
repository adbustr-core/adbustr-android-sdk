package com.adbustr.sdk;

/**
 * Configuration required to initialize {@link AdbustrSdk}. Build it once and
 * hand it to {@link AdbustrSdk#initialize}.
 */
public final class AdbustrConfig {

    private static final int DEFAULT_TIMEOUT_SECONDS = 2;

    private final String baseUrl;
    private final String apiKey;
    private final String siteId;
    private final int timeoutSeconds;
    private final boolean testMode;

    private AdbustrConfig(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.siteId = builder.siteId;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.testMode = builder.testMode;
    }

    /** Base URL of the Adbustr ad server, without a trailing slash. */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** Publisher API key, sent as the {@code X-Adbustr-Key} header. */
    public String getApiKey() {
        return apiKey;
    }

    /** Site id assigned by Adbustr for this app. */
    public String getSiteId() {
        return siteId;
    }

    /** Ad request timeout in seconds. */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /** When true the SDK logs verbosely. Does not change network behavior. */
    public boolean isTestMode() {
        return testMode;
    }

    /** True when every field the ad server requires is present. */
    public boolean isValid() {
        return !isBlank(baseUrl) && !isBlank(apiKey) && !isBlank(siteId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static final class Builder {
        private String baseUrl;
        private String apiKey;
        private String siteId;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private boolean testMode;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? null : stripTrailingSlashes(baseUrl.trim());
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder siteId(String siteId) {
            this.siteId = siteId;
            return this;
        }

        /** Clamped to at least 1 second. */
        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = Math.max(1, timeoutSeconds);
            return this;
        }

        public Builder testMode(boolean testMode) {
            this.testMode = testMode;
            return this;
        }

        public AdbustrConfig build() {
            return new AdbustrConfig(this);
        }

        private static String stripTrailingSlashes(String url) {
            int end = url.length();
            while (end > 0 && url.charAt(end - 1) == '/') {
                end--;
            }
            return url.substring(0, end);
        }
    }
}
