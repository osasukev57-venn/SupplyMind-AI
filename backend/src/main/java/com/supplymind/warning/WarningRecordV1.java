package com.supplymind.warning;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaValidationException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * D5-T05 one deterministic warning record (frozen schema fields: warningId, ruleId, ruleVersion,
 * itemId, evaluation period, threshold, currentValue, baselineValue, riskLevel, evidenceRefs,
 * dataStatus, evaluated time, inputFingerprint). Only legal published/processed data can ever
 * produce a record; PENDING/REJECTED/CONFLICT/DEMO never do. Same logical inputs yield the same
 * warningId (fingerprint-derived), so re-runs never duplicate business warnings.
 */
@JsonPropertyOrder({
        "schemaVersion", "warningId", "ruleId", "ruleVersion", "itemId", "grain",
        "periodStart", "periodEnd", "businessDate", "threshold", "currentValue", "baselineValue",
        "riskLevel", "evidenceRefs", "dataStatus", "evaluatedAt", "inputFingerprint",
        "demoRule", "ruleDescription"
})
public record WarningRecordV1(
        String schemaVersion,
        String warningId,
        String ruleId,
        String ruleVersion,
        String itemId,
        String grain,
        String periodStart,
        String periodEnd,
        String businessDate,
        String threshold,
        String currentValue,
        String baselineValue,
        RiskLevel riskLevel,
        List<String> evidenceRefs,
        String dataStatus,
        OffsetDateTime evaluatedAt,
        String inputFingerprint,
        boolean demoRule,
        String ruleDescription
) {
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public WarningRecordV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(warningId, "warningId");
        ModelRules.id(ruleId, "ruleId");
        ModelRules.nonBlank(ruleVersion, "ruleVersion");
        ModelRules.id(itemId, "itemId");
        ModelRules.nonBlank(grain, "grain");
        ModelRules.isoDateText(periodStart, "periodStart");
        ModelRules.isoDateText(periodEnd, "periodEnd");
        if (periodStart.compareTo(periodEnd) > 0) {
            throw new SchemaValidationException("warning periodStart must not be after periodEnd");
        }
        if (businessDate != null) {
            ModelRules.isoDateText(businessDate, "businessDate");
        }
        if (threshold != null) {
            decimal(threshold, "threshold");
        }
        decimal(currentValue, "currentValue");
        decimal(baselineValue, "baselineValue");
        if (riskLevel == null) {
            throw new SchemaValidationException("warning riskLevel is required");
        }
        evidenceRefs = ModelRules.immutableList(evidenceRefs, "evidenceRefs");
        if (evidenceRefs.isEmpty()) {
            throw new SchemaValidationException("warning evidenceRefs must not be empty");
        }
        ModelRules.nonBlank(dataStatus, "dataStatus");
        ModelRules.dateTime(evaluatedAt, "evaluatedAt");
        ModelRules.sha256(inputFingerprint, "inputFingerprint");
        ModelRules.nonBlank(ruleDescription, "ruleDescription");
    }

    /** Month under which the evidence file is persisted (warning/YYYY-MM). */
    public YearMonth warningMonth() {
        return YearMonth.parse(periodStart.substring(0, 7));
    }

    private static void decimal(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new SchemaValidationException(name + " must be a decimal string");
        }
        try {
            new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new SchemaValidationException(name + " must be a decimal string: " + value);
        }
    }
}
