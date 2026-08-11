package com.supplymind.routing;

/**
 * D3-T02 per-candidate availability outcome. Mirrors the frozen D3-T02 test categories
 * (legal API, member restrictions, no public interface, anti-scraping, missing credentials)
 * plus capability and route-boundary reasons. Only {@link #AVAILABLE} may become active.
 */
public enum CandidateUnavailability {
    AVAILABLE("available"),
    CREDENTIALS_MISSING("credentials_missing"),
    NOT_AUTHORIZED("not_authorized"),
    MEMBER_ONLY("member_only"),
    NO_PUBLIC_INTERFACE("no_public_interface"),
    ANTI_SCRAPING("anti_scraping"),
    CAPABILITY_MISMATCH("capability_mismatch"),
    TIER_TYPE_MISMATCH("tier_type_mismatch"),
    SYNTHETIC_NOT_FORMAL("synthetic_not_formal");

    private final String wireValue;

    CandidateUnavailability(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public boolean isAvailable() {
        return this == AVAILABLE;
    }
}
