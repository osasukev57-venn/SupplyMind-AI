package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * DEC-059 frozen material validation rules for one material item. The four P0 series must
 * carry this explicitly in their monitor-series item; a material item without it fails config
 * construction (activation fail-closed). No implicit runtime defaults are ever applied.
 *
 * <p>Current frozen rule set (material-basic-validation-v2): valueMinExclusive="0"
 * (value <= valueMinExclusive is REJECTED), valueMaxInclusive=null (no upper bound),
 * staleThresholdDays=7 (calendarAgeDays > 7 is stale, == 7 is not), canonicalSpecCode
 * ADC12/AZ91D with acceptedSpecAliases=[] (normalized-exact spec matching, no implied
 * aliases). Future rule changes require a new config version plus a new validationVersion.
 */
@JsonPropertyOrder({
        "valueMinExclusive", "valueMaxInclusive", "staleThresholdDays",
        "canonicalSpecCode", "acceptedSpecAliases"
})
public record MaterialValidationConfigV1(
        String valueMinExclusive,
        String valueMaxInclusive,
        int staleThresholdDays,
        String canonicalSpecCode,
        List<String> acceptedSpecAliases
) {
    public MaterialValidationConfigV1 {
        ModelRules.nonBlank(valueMinExclusive, "valueMinExclusive");
        try {
            new BigDecimal(valueMinExclusive);
        } catch (NumberFormatException exception) {
            throw new SchemaValidationException("valueMinExclusive must be a decimal string: " + valueMinExclusive);
        }
        if (valueMaxInclusive != null) {
            ModelRules.nonBlank(valueMaxInclusive, "valueMaxInclusive");
            try {
                new BigDecimal(valueMaxInclusive);
            } catch (NumberFormatException exception) {
                throw new SchemaValidationException("valueMaxInclusive must be a decimal string: " + valueMaxInclusive);
            }
        }
        ModelRules.positive(staleThresholdDays, "staleThresholdDays");
        ModelRules.nonBlank(canonicalSpecCode, "canonicalSpecCode");
        acceptedSpecAliases = ModelRules.immutableList(acceptedSpecAliases, "acceptedSpecAliases");
        for (String alias : acceptedSpecAliases) {
            ModelRules.nonBlank(alias, "acceptedSpecAlias");
        }
    }
}
