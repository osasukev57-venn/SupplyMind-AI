package com.supplymind.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * D4-T01 material basic validation V2 (DEC-059), version `material-basic-validation-v2` -
 * the current official material rule set. The previous `material-basic-validation-v1`
 * implementation ({@link MaterialCandidateValidator}) is preserved untouched as history and
 * is never used for new validation or publication.
 *
 * <p>Frozen V2 checks (DEC-059), deterministic order: explicit materialValidation config
 * presence (fail-closed CONFIG_MISSING), mode, item identity, provider identity,
 * source-field consistency, field integrity, unit, currency, future business date, spec
 * normalized-exact, value (value &lt;= valueMinExclusive REJECTED; valueMaxInclusive=null
 * means no upper bound), staleness (calendarAgeDays &gt; staleThresholdDays -&gt;
 * VERIFIED_WITH_NOTICE), then duplicate/conflict.
 *
 * <p>Spec identity is item-owned: the frozen record schemas carry no per-record spec field,
 * so the declared spec is the item's externalCode normalized via Unicode NFKC, trim and ASCII
 * uppercase, compared exactly against the configured canonicalSpecCode; acceptedSpecAliases
 * is [] so no alias is ever implied. Value and staleness rules come exclusively from the
 * explicit per-item config - never from implicit defaults and never from the PBOC DEC-050
 * 30-day rule.
 */
public final class MaterialCandidateValidatorV2 {

    public static final String VALIDATION_VERSION = "material-basic-validation-v2";

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

        MaterialValidationConfigV1 rules = item.materialValidation();
        if (rules == null) {
            return reject(ValidationReasonCodes.CONFIG_MISSING);
        }

        if (raw.mode() != mode) {
            return reject(ValidationReasonCodes.SOURCE_MISMATCH);
        }
        if (!candidate.itemId().equals(item.itemId())
                || candidate.providerType() != item.providerType()
                || candidate.accessMethod() != item.accessMethod()) {
            return reject(ValidationReasonCodes.SOURCE_MISMATCH);
        }

        if (!sourceFieldsConsistent(raw, candidate)) {
            return reject(ValidationReasonCodes.SOURCE_MISMATCH);
        }
        if (!payloadIntegrity(raw)) {
            return reject(ValidationReasonCodes.FIELD_INVALID);
        }
        if (requiresSourceReference(raw.providerType())
                && (raw.sourceReference() == null || raw.sourceReference().isBlank())) {
            return reject(ValidationReasonCodes.FIELD_INVALID);
        }

        if (!candidate.unit().equals(item.unit())) {
            return reject(ValidationReasonCodes.UNIT_MISMATCH);
        }
        if (!candidate.currency().equals(item.currency())) {
            return reject(ValidationReasonCodes.CURRENCY_MISMATCH);
        }

        LocalDate businessDate;
        try {
            businessDate = LocalDate.parse(candidate.businessDate());
        } catch (RuntimeException exception) {
            return reject(ValidationReasonCodes.FIELD_INVALID);
        }
        if (businessDate.isAfter(today)) {
            return reject(ValidationReasonCodes.FUTURE_BUSINESS_DATE);
        }

        if (!specMatches(rules, item.externalCode())) {
            return reject(ValidationReasonCodes.SPEC_MISMATCH);
        }

        BigDecimal value;
        try {
            value = new BigDecimal(candidate.value());
        } catch (NumberFormatException exception) {
            return reject(ValidationReasonCodes.FIELD_INVALID);
        }
        if (value.compareTo(new BigDecimal(rules.valueMinExclusive())) <= 0) {
            return reject(ValidationReasonCodes.OUT_OF_RANGE);
        }
        if (rules.valueMaxInclusive() != null
                && value.compareTo(new BigDecimal(rules.valueMaxInclusive())) > 0) {
            return reject(ValidationReasonCodes.OUT_OF_RANGE);
        }

        long calendarAgeDays = ChronoUnit.DAYS.between(businessDate, today);
        if (calendarAgeDays > rules.staleThresholdDays()) {
            return new ValidationVerdict(ValidationStatus.VERIFIED_WITH_NOTICE,
                    ValidationReasonCodes.STALE_BUSINESS_DATE);
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

    /**
     * DEC-059 normalized-exact spec matching: Unicode NFKC, trim surrounding whitespace, ASCII
     * uppercase, then exact comparison with the configured canonicalSpecCode. acceptedSpecAliases
     * is [] in the frozen config, so no alias equivalence is ever implied.
     */
    private static boolean specMatches(MaterialValidationConfigV1 rules, String declaredSpec) {
        String canonical = normalizeSpec(rules.canonicalSpecCode());
        String declared = normalizeSpec(declaredSpec);
        if (declared.equals(canonical)) {
            return true;
        }
        for (String alias : rules.acceptedSpecAliases()) {
            if (declared.equals(normalizeSpec(alias))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSpec(String spec) {
        return Normalizer.normalize(spec, Normalizer.Form.NFKC).trim().toUpperCase(Locale.ROOT);
    }

    private static boolean sourceFieldsConsistent(RawReceiptV1 raw, CandidateV1 candidate) {
        if (!raw.actualSourceName().equals(candidate.actualSourceName())) {
            return false;
        }
        try {
            byte[] payloadBytes = Base64.getDecoder().decode(raw.payloadBase64());
            JsonNode payload = JsonV1Codec.mapper().readTree(new String(payloadBytes, StandardCharsets.UTF_8));
            if (payload.isObject() && payload.has("actualSourceName")) {
                String declared = payload.get("actualSourceName").asText();
                return declared != null && declared.equals(raw.actualSourceName());
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Non-JSON payload (CSV/XLSX): raw-level consistency is by construction.
        }
        return true;
    }

    private static boolean payloadIntegrity(RawReceiptV1 raw) {
        try {
            byte[] payload = Base64.getDecoder().decode(raw.payloadBase64());
            return raw.payloadSha256().equals(JsonV1Codec.sha256LowerHex(payload));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static ValidationVerdict reject(String reasonCode) {
        return new ValidationVerdict(ValidationStatus.REJECTED, reasonCode);
    }

    private static boolean requiresSourceReference(com.supplymind.foundation.model.ProviderType providerType) {
        return providerType == com.supplymind.foundation.model.ProviderType.MANUAL
                || providerType == com.supplymind.foundation.model.ProviderType.LOCAL_IMPORT;
    }
}
