package com.adbustr.sdk;

/** Ad format requested from the server. */
public enum AdFormat {

    NATIVE("native"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    BANNER("banner");

    private final String wireValue;

    AdFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Exact value expected by the request's {@code format} field. */
    public String getWireValue() {
        return wireValue;
    }
}
