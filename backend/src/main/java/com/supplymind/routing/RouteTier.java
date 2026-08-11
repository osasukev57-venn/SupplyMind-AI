package com.supplymind.routing;

/** D3-T02 frozen three material source tiers, in controlled-degradation order (DEC-037). */
public enum RouteTier {
    PRIMARY("primary"),
    FREE_PUBLIC("free_public"),
    MANUAL("manual");

    private final String wireValue;

    RouteTier(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
