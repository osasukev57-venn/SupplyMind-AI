package com.supplymind.validation;

import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * D4-T01 material standardization for intake rows that arrive without a CandidateV1 (LocalImport
 * CSV/XLSX rows and any future material source rows): raw fields become the unified CandidateV1.
 * The normalization version name follows the existing `manual-material-normalization-v1`
 * convention (DEC-057 §5 kept the Manual version unchanged). Fail-closed: any missing or
 * unparseable standardization field maps to STANDARDIZATION_FAILED so the lifecycle becomes
 * RECEIVED+REJECTED with candidate=null. Mechanical only: no value range, staleness or spec
 * judgment here (those require the frozen material rules; see the D4-T01 decision gap).
 */
public final class MaterialCandidateStandardizer {

    public static final String NORMALIZATION_VERSION = "local-import-material-normalization-v1";

    public StandardizationResult standardize(RawReceiptV1 raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.sourceBusinessDate() == null || raw.rawValue() == null
                || raw.rawUnit() == null || raw.rawCurrency() == null) {
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
