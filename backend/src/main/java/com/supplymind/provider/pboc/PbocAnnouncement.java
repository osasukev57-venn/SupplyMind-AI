package com.supplymind.provider.pboc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed fields from one PBOC announcement; raw HTTP bytes remain outside this projection.
 * The rate map is keyed by the frozen anchor text (sourceFieldKey), e.g. "1美元对人民币".
 * The provider resolves configured targets by their configuration metadata - never by
 * hard-coded itemId sets.
 */
record PbocAnnouncement(String title, String titleBusinessDateRaw, LocalDate businessDate, String sourcePublishedAtRaw,
                        OffsetDateTime sourcePublishedAt, Map<String, String> rateByAnchor) {
    PbocAnnouncement {
        rateByAnchor = Map.copyOf(Objects.requireNonNull(rateByAnchor, "rateByAnchor"));
    }

    /** USD convenience accessor (frozen anchor), kept for the D1-T04 dual-currency contract. */
    String usdRawValue() {
        return Objects.requireNonNull(rateByAnchor.get("1美元对人民币"),
                "PBOC announcement has no USD rate");
    }

    /** EUR convenience accessor (frozen anchor), kept for the D1-T04 dual-currency contract. */
    String eurRawValue() {
        return Objects.requireNonNull(rateByAnchor.get("1欧元对人民币"),
                "PBOC announcement has no EUR rate");
    }
}
