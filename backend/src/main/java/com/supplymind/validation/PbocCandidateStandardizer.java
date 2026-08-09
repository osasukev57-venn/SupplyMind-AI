package com.supplymind.validation;

import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * D2-T01 PBOC standardization: raw fields become the unified CandidateV1.
 * Fail-closed: any missing or unparseable standardization field maps to STANDARDIZATION_FAILED
 * so the lifecycle becomes RECEIVED+REJECTED with candidate=null.
 */
public final class PbocCandidateStandardizer {

    public static final String NORMALIZATION_VERSION = "pboc-standardization-v1";

    public StandardizationResult standardize(RawReceiptV1 raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.sourceBusinessDate() == null || raw.sourcePublishedAt() == null
                || raw.rawValue() == null || raw.rawUnit() == null || raw.rawCurrency() == null) {
            return new StandardizationResult(null, ValidationReasonCodes.STANDARDIZATION_FAILED);
        }
        try {
            new BigDecimal(raw.rawValue());
        } catch (NumberFormatException exception) {
            return new StandardizationResult(null, ValidationReasonCodes.STANDARDIZATION_FAILED);
        }
        try {
            CandidateV1 candidate = new CandidateV1(
                    raw.itemId(),
                    raw.sourceBusinessDate(),
                    raw.rawValue(),
                    raw.rawCurrency(),
                    raw.rawUnit(),
                    raw.providerType(),
                    raw.actualSourceName(),
                    raw.accessMethod(),
                    NORMALIZATION_VERSION);
            return new StandardizationResult(candidate, null);
        } catch (RuntimeException exception) {
            return new StandardizationResult(null, ValidationReasonCodes.STANDARDIZATION_FAILED);
        }
    }
}
