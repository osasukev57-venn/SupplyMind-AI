package com.supplymind.manual;

import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.SchemaValidationException;

/**
 * D3-T04 mechanical normalization result (manual-material-normalization-v1). A rejected
 * outcome is a mechanical intake-gate failure (missing required fields, unknown/non-manual
 * item, unparseable date, non-decimal value) - never a material business ValidationStatus
 * verdict (those are DEFERRED_TO_D4_T01).
 */
public record ManualNormalizationOutcome(
        boolean accepted,
        String businessDate,
        String value,
        String unit,
        String currency,
        String reason
) {
    public static ManualNormalizationOutcome accepted(
            String businessDate, String value, String unit, String currency
    ) {
        return new ManualNormalizationOutcome(true, businessDate, value, unit, currency, null);
    }

    public static ManualNormalizationOutcome rejected(String reason) {
        return new ManualNormalizationOutcome(false, null, null, null, null, reason);
    }

    public CandidateV1 candidate(
            String itemId,
            String actualSourceName,
            com.supplymind.foundation.model.ProviderType providerType,
            com.supplymind.foundation.model.AccessMethod accessMethod
    ) {
        if (!accepted) {
            throw new SchemaValidationException("a rejected normalization cannot produce a CandidateV1");
        }
        return new CandidateV1(itemId, businessDate, value, currency, unit,
                providerType, actualSourceName, accessMethod, ManualMaterialNormalizer.NORMALIZATION_VERSION);
    }
}
