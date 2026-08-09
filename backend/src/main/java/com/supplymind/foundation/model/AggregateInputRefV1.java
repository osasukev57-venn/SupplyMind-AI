package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;

/** Compact provenance object embedded in one aggregate CSV inputRefs cell. */
@JsonPropertyOrder({"dailyFileRef", "businessDate", "validationVersion", "fileSha256"})
public record AggregateInputRefV1(
        String dailyFileRef,
        String businessDate,
        String validationVersion,
        String fileSha256
) {
    public static final Comparator<AggregateInputRefV1> ORDER = Comparator
            .comparing(AggregateInputRefV1::businessDate)
            .thenComparing(AggregateInputRefV1::dailyFileRef)
            .thenComparing(AggregateInputRefV1::validationVersion)
            .thenComparing(AggregateInputRefV1::fileSha256);

    public AggregateInputRefV1 {
        ModelRules.relativeDataRef(dailyFileRef, "aggregate input dailyFileRef");
        if (!dailyFileRef.startsWith("processed/daily/")) {
            throw new SchemaValidationException("aggregate input dailyFileRef must refer to a daily CSV");
        }
        ModelRules.isoDateText(businessDate, "aggregate input businessDate");
        ModelRules.nonBlank(validationVersion, "aggregate input validationVersion");
        ModelRules.sha256(fileSha256, "aggregate input fileSha256");
    }
}
