package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;

/** Compact provenance object embedded in one daily CSV inputRefs cell. */
@JsonPropertyOrder({"runId", "rawRef", "recordVersion"})
public record DailyInputRefV1(String runId, String rawRef, int recordVersion) {
    public static final Comparator<DailyInputRefV1> ORDER = Comparator
            .comparing(DailyInputRefV1::runId)
            .thenComparing(DailyInputRefV1::rawRef)
            .thenComparingInt(DailyInputRefV1::recordVersion);

    public DailyInputRefV1 {
        ModelRules.id(runId, "daily input runId");
        ModelRules.relativeDataRef(rawRef, "daily input rawRef");
        if (!rawRef.startsWith("raw/")) {
            throw new SchemaValidationException("daily input rawRef must refer to a raw receipt");
        }
        if (recordVersion != 4) {
            throw new SchemaValidationException("daily inputRefs may only target PUBLISHED recordVersion=4");
        }
    }
}
