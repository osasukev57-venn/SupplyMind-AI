package com.supplymind.manual;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.provider.ProviderModelChecks;

import java.math.BigDecimal;

/**
 * D3-T04 mechanical normalization, version manual-material-normalization-v1. It performs only
 * deterministic field/format processing: required fields present, itemId configured and
 * allowed for the Manual route, strict ISO businessDate, value as an exact non-scientific
 * decimal string (BigDecimal(String), never float/double), and deterministic CandidateV1
 * mapping. It MUST NOT judge value range, future date, stale, material spec comparability,
 * unit/currency business correctness or source truthfulness (DEFERRED_TO_D4_T01).
 */
public final class ManualMaterialNormalizer {

    public static final String NORMALIZATION_VERSION = "manual-material-normalization-v1";

    private static final String SCIENTIFIC_NOTATION = "(?i).*[eE].*";

    public ManualNormalizationOutcome normalize(
            ManualMaterialSubmission submission,
            MonitorSeriesConfigV1 config
    ) {
        ProviderModelChecks.required(submission, "submission");
        ProviderModelChecks.required(config, "config");

        if (submission.sourceReference() == null || submission.sourceReference().isBlank()) {
            return ManualNormalizationOutcome.rejected("SOURCE_REFERENCE_MISSING");
        }

        MonitorSeriesItemV1 item;
        try {
            item = config.requireItem(submission.itemId());
        } catch (RuntimeException exception) {
            return ManualNormalizationOutcome.rejected("ITEM_NOT_CONFIGURED");
        }
        if (!item.enabled()
                || (item.providerType() != com.supplymind.foundation.model.ProviderType.MANUAL
                && item.routeDecision() != RouteDecision.FALLBACK_MANUAL)) {
            return ManualNormalizationOutcome.rejected("ITEM_NOT_MANUAL_ROUTE");
        }

        if (!submission.businessDate().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return ManualNormalizationOutcome.rejected("BUSINESS_DATE_INVALID");
        }
        try {
            java.time.LocalDate.parse(submission.businessDate());
        } catch (java.time.format.DateTimeParseException exception) {
            return ManualNormalizationOutcome.rejected("BUSINESS_DATE_INVALID");
        }

        String value = submission.value();
        if (value == null || value.isBlank()) {
            return ManualNormalizationOutcome.rejected("VALUE_MISSING");
        }
        if (value.matches(SCIENTIFIC_NOTATION)) {
            return ManualNormalizationOutcome.rejected("VALUE_SCIENTIFIC_NOTATION");
        }
        try {
            new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return ManualNormalizationOutcome.rejected("VALUE_NOT_DECIMAL");
        }

        return ManualNormalizationOutcome.accepted(
                submission.businessDate(), value, submission.unit(), submission.currency());
    }
}
