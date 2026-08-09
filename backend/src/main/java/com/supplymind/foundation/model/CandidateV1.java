package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Immutable normalized candidate. It first appears at PARSED+PENDING. */
@JsonPropertyOrder({
        "itemId", "businessDate", "value", "currency", "unit", "providerType", "actualSourceName",
        "accessMethod", "normalizationVersion"
})
public record CandidateV1(
        String itemId,
        String businessDate,
        String value,
        String currency,
        String unit,
        ProviderType providerType,
        String actualSourceName,
        AccessMethod accessMethod,
        String normalizationVersion
) {
    public CandidateV1 {
        ModelRules.id(itemId, "candidate.itemId");
        ModelRules.isoDateText(businessDate, "candidate.businessDate");
        value = DecimalText.canonical(value, "candidate.value");
        ModelRules.nonBlank(currency, "candidate.currency");
        ModelRules.nonBlank(unit, "candidate.unit");
        ModelRules.required(providerType, "candidate.providerType");
        ModelRules.nonBlank(actualSourceName, "candidate.actualSourceName");
        ModelRules.required(accessMethod, "candidate.accessMethod");
        ModelRules.providerPair(providerType, accessMethod);
        ModelRules.nonBlank(normalizationVersion, "candidate.normalizationVersion");
    }
}
