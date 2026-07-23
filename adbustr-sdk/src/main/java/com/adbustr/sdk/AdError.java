package com.adbustr.sdk;

/**
 * Reasons an ad load or show can fail. The SDK never throws to caller code; it
 * reports one of these instead. Values mirror the Unity SDK's AdError so both
 * integrations report the same taxonomy.
 */
public enum AdError {

    /** No error. */
    NONE("No error."),

    /** {@link AdbustrSdk#initialize} was not called, or failed validation. */
    NOT_INITIALIZED("AdbustrSdk.initialize was not called."),

    /** Zone id is missing, or was rejected by the server (4xx). */
    INVALID_ZONE("Zone id is missing or was rejected by the server."),

    /** Network request failed after the built-in retry. */
    NETWORK_ERROR("Network request failed."),

    /** Request timed out. */
    TIMEOUT("Request timed out."),

    /** Server returned status "nobid" — no ad available for this request. */
    NO_FILL("No ad available for this request."),

    /** Failed to parse the server's JSON response. */
    PARSE_ERROR("Failed to parse the server response."),

    /** Server returned a response that doesn't match the expected contract. */
    INVALID_RESPONSE("Server returned an unexpected response."),

    /** Failed to download one or more required creative assets. */
    CREATIVE_LOAD_FAILED("Failed to download ad creative assets."),

    /** The ad's ttl (seconds since load) has elapsed. */
    EXPIRED("Ad has expired (ttl elapsed)."),

    /** The ad was already shown once. */
    ALREADY_SHOWN("Ad was already shown."),

    /** Rendering failed after the ad was handed to the presenter. */
    DISPLAY_FAILED("Ad failed to display."),

    /** This ad format is not implemented in this SDK version. */
    NOT_SUPPORTED("This ad format is not supported in this SDK version."),

    /** Unclassified failure. */
    UNKNOWN("Unknown error.");

    private final String message;

    AdError(String message) {
        this.message = message;
    }

    /** Short English description, useful for logs and for the mediation layer. */
    public String getMessage() {
        return message;
    }
}
