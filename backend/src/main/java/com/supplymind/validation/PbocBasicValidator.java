package com.supplymind.validation;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * D2-T01 PBOC basic validation with deterministic check order:
 * source identity, field integrity, unit, currency, future date, staleness, value range,
 * then duplicate/conflict against other observations of the same business key and source.
 * DEC-050 frozen PBOC basic-validation-v1 rule: stale when the business date is older than
 * 30 calendar days (exactly 30 days is valid); the valid numeric range is (0,100]
 * (0 and negative values are invalid, 100 is valid). Scope: D2-T01 USD/CNY and EUR/CNY only.
 */
public final class PbocBasicValidator {

    public static final String VALIDATION_VERSION = "pboc-basic-validation-v1";
    static final BigDecimal MAX_RATE = new BigDecimal("100");
    static final int STALE_AFTER_DAYS = 30;

    public ValidationVerdict validate(
            RawReceiptV1 raw,
            CandidateV1 candidate,
            MonitorSeriesItemV1 item,
            Mode mode,
            LocalDate today,
            List<CandidateV1> otherObservations
    ) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(otherObservations, "otherObservations");

        if (!candidate.itemId().equals(item.itemId())
                || candidate.providerType() != item.providerType()
                || candidate.accessMethod() != item.accessMethod()
                || !candidate.actualSourceName().equals(item.actualSourceName())
                || raw.mode() != mode) {
            return reject(ValidationReasonCodes.SOURCE_MISMATCH);
        }

        if (raw.httpStatus() == null || raw.httpStatus() != 200
                || raw.contentType() == null
                || !raw.contentType().toLowerCase(Locale.ROOT).startsWith("text/html")
                || raw.sourcePublishedAt() == null
                || !raw.payloadSha256().equals(JsonV1Codec.sha256LowerHex(Base64.getDecoder().decode(raw.payloadBase64())))) {
            return reject(ValidationReasonCodes.FIELD_INVALID);
        }

        if (!candidate.unit().equals(item.unit())) {
            return reject(ValidationReasonCodes.UNIT_MISMATCH);
        }
        if (!candidate.currency().equals(item.currency())) {
            return reject(ValidationReasonCodes.CURRENCY_MISMATCH);
        }

        LocalDate businessDate = LocalDate.parse(candidate.businessDate());
        if (businessDate.isAfter(today)) {
            return reject(ValidationReasonCodes.FUTURE_BUSINESS_DATE);
        }
        if (businessDate.isBefore(today.minusDays(STALE_AFTER_DAYS))) {
            return reject(ValidationReasonCodes.STALE_BUSINESS_DATE);
        }

        BigDecimal value = new BigDecimal(candidate.value());
        if (value.signum() <= 0 || value.compareTo(MAX_RATE) > 0) {
            return reject(ValidationReasonCodes.OUT_OF_RANGE);
        }

        boolean duplicate = false;
        for (CandidateV1 other : otherObservations) {
            int comparison = new BigDecimal(other.value()).compareTo(value);
            if (comparison != 0) {
                return new ValidationVerdict(ValidationStatus.CONFLICT, ValidationReasonCodes.VALUE_CONFLICT);
            }
            duplicate = true;
        }
        if (duplicate) {
            return new ValidationVerdict(ValidationStatus.VERIFIED_WITH_NOTICE,
                    ValidationReasonCodes.DUPLICATE_OBSERVATION);
        }
        return new ValidationVerdict(ValidationStatus.VERIFIED, null);
    }

    private static ValidationVerdict reject(String reasonCode) {
        return new ValidationVerdict(ValidationStatus.REJECTED, reasonCode);
    }
}
