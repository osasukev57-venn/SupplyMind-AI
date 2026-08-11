package com.supplymind.validation;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Objects;

/**
 * D4-T01 material basic validation, version `material-basic-validation-v1`. The version name
 * follows the existing `pboc-basic-validation-v1` naming convention and is a distinct material
 * rule set: pboc-basic-validation-v1 is NEVER reused for materials (DEC-057 §6; DEC-050 scope
 * is PBOC-only). Frozen scope (DEC-057 §7): item/spec legality via the active item, unit,
 * currency, businessDate/future date, source-field consistency, declared-source preservation,
 * duplicate, revision and conflict, with deterministic verdicts VERIFIED /
 * VERIFIED_WITH_NOTICE / REJECTED / CONFLICT.
 *
 * <p>Deterministic check order: mode, item identity, provider identity, source-field
 * consistency (declared name inside the immutable payload vs raw vs candidate), field
 * integrity, unit, currency, future business date, then duplicate/conflict against other
 * observations of the same business key and source. Provider ingress identity
 * (providerType/accessMethod) is configuration-owned: a declared source name can never turn a
 * Manual/LocalImport/FreePublic record into an official source, and a FreePublic label is
 * never auto-trusted.
 *
 * <p>Business-key adjudication for materials (frozen key = itemId + businessDate + provider
 * type + accessMethod + declared actualSourceName): same key with the same value is a
 * duplicate observation (VERIFIED_WITH_NOTICE); same key with a different value is a value
 * conflict (CONFLICT, per GD-03 "相同业务唯一键但值冲突的记录"); a revision that changes the
 * declared source is a different business key and validates independently while all older
 * versions stay immutable.
 *
 * <p>Not implemented here because no frozen numeric values exist in the plan / DEC-057 /
 * DEC-050 scope for materials: numeric value range, staleness threshold and spec
 * comparability (EXT-02 remains OPEN_EXTERNAL). Inventing thresholds is forbidden; those
 * verdicts require a business decision and are reported as the D4-T01 decision gap.
 */
public final class MaterialCandidateValidator {

    public static final String VALIDATION_VERSION = "material-basic-validation-v1";

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

        boolean duplicate = false;
        for (CandidateV1 other : otherObservations) {
            int comparison = new BigDecimal(other.value()).compareTo(new BigDecimal(candidate.value()));
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
     * Source-field consistency (来源字段一致性): the declared actual source name preserved in
     * the immutable payload must equal the raw and candidate actualSourceName. Binary payloads
     * (CSV byte spans, XLSX full-file bytes) do not decode as JSON; for those the raw-level
     * identity was derived from the same declared field at intake, so the check is limited to
     * JSON-decodable payloads (Manual submissions and structured imports).
     */
    private static boolean sourceFieldsConsistent(RawReceiptV1 raw, CandidateV1 candidate) {
        if (!raw.actualSourceName().equals(candidate.actualSourceName())) {
            return false;
        }
        try {
            byte[] payloadBytes = Base64.getDecoder().decode(raw.payloadBase64());
            JsonNode payload = JsonV1Codec.mapper().readTree(
                    new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8));
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

    /** Frozen schema (docs/01 8.4.2): Manual/LocalImport require sourceReference; HTTP providers require sourceUrl+httpStatus instead. */
    private static boolean requiresSourceReference(com.supplymind.foundation.model.ProviderType providerType) {
        return providerType == com.supplymind.foundation.model.ProviderType.MANUAL
                || providerType == com.supplymind.foundation.model.ProviderType.LOCAL_IMPORT;
    }
}
