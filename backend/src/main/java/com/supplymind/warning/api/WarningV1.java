package com.supplymind.warning.api;

import com.supplymind.warning.WarningAcknowledgementV1;
import com.supplymind.warning.WarningRecordV1;

import java.util.List;

/**
 * D8-T02 frozen warning API wire contract. Warning records stay immutable evidence; the ack
 * sidecar is a separate document. All values are backend strings; the frontend never computes
 * thresholds, risk levels or completeness.
 */
public final class WarningV1 {

    private WarningV1() {
    }

    /** Effective system-default or user-custom rule bound to one monitored series. */
    public record RuleView(
            String itemId,
            String displayName,
            String actualSourceName,
            String unit,
            String rateKind,
            boolean monitorEnabled,
            String ruleId,
            String ruleVersion,
            int lookbackDays,
            String threshold,
            String thresholdPercent,
            String direction,
            boolean enabled,
            String origin,
            String description
    ) {
    }

    /** Controlled user override. thresholdPercent is the user-facing percentage (5 = 5%). */
    public record UpdateRuleRequest(
            Integer lookbackDays,
            String thresholdPercent,
            String direction,
            Boolean enabled,
            String description
    ) {
        public UpdateRuleRequest {
            if (lookbackDays == null || lookbackDays < 1 || lookbackDays > 365) {
                throw new IllegalArgumentException("lookbackDays must be between 1 and 365");
            }
            if (thresholdPercent == null || thresholdPercent.isBlank()) {
                throw new IllegalArgumentException("thresholdPercent is required");
            }
            if (direction == null || direction.isBlank()) {
                throw new IllegalArgumentException("direction is required");
            }
            if (enabled == null) {
                throw new IllegalArgumentException("enabled is required");
            }
        }
    }

    /** Java-computed current evaluation state; the frontend never recalculates it. */
    public record EvaluationView(
            RuleView rule,
            String status,
            String message,
            String currentBusinessDate,
            String currentValue,
            String baselineBusinessDate,
            String baselineValue,
            String changeRatio,
            String changePercent,
            String unit,
            WarningView warning
    ) {
    }

    public static RuleView toView(com.supplymind.warning.WarningRuleManagementService.BoundRule bound) {
        com.supplymind.warning.WarningRuleConfigV1 rule = bound.rule();
        return new RuleView(
                bound.itemId(), bound.displayName(), bound.actualSourceName(), bound.unit(),
                bound.rateKind(), bound.monitorEnabled(), rule.ruleId(), rule.ruleVersion(),
                rule.lookbackDays(), rule.threshold(), percent(rule.threshold()),
                rule.direction().name(), rule.enabled(),
                rule.origin().name(), rule.description());
    }

    public static EvaluationView toView(com.supplymind.warning.WarningEvaluationV1 evaluation) {
        com.supplymind.warning.WarningRuleConfigV1 rule = evaluation.rule();
        RuleView ruleView = new RuleView(
                rule.itemId(), rule.itemId(), null, evaluation.unit(), rule.rateKind(), true,
                rule.ruleId(), rule.ruleVersion(), rule.lookbackDays(), rule.threshold(),
                percent(rule.threshold()), rule.direction().name(), rule.enabled(),
                rule.origin().name(), rule.description());
        return new EvaluationView(
                ruleView, evaluation.status(), evaluation.message(),
                evaluation.currentBusinessDate(), evaluation.currentValue(),
                evaluation.baselineBusinessDate(), evaluation.baselineValue(),
                evaluation.changeRatio(), percentNullable(evaluation.changeRatio()), evaluation.unit(),
                evaluation.warning() == null ? null : toView(evaluation.warning(), false, null));
    }
    /** Read-only projection of one immutable WarningRecordV1. */
    public record WarningView(
            String warningId,
            String ruleId,
            String ruleVersion,
            String itemId,
            String grain,
            String periodStart,
            String periodEnd,
            String threshold,
            String thresholdPercent,
            String currentValue,
            String baselineValue,
            String riskLevel,
            List<String> evidenceRefs,
            String dataStatus,
            String evaluatedAt,
            boolean demoRule,
            String ruleDescription,
            boolean acknowledged,
            String ackRef
    ) {
        public WarningView {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** Read-only projection of a DEC-061 acknowledgement sidecar. */
    public record AckView(
            String warningId,
            String warningRef,
            String warningFileSha256,
            String status,
            String acknowledgedAt,
            String dispositionNote
    ) {
    }

    /** Controlled ack request: dispositionNote only; the backend generates the ack time. */
    public record AckRequest(
            String dispositionNote
    ) {
        public AckRequest {
            if (dispositionNote == null || dispositionNote.isBlank()) {
                throw new IllegalArgumentException("dispositionNote is required");
            }
        }
    }

    /** Compatibility request for the legacy demo-only evaluation endpoint. */
    public record EvaluateRequest(
            String ruleId,
            String ruleVersion,
            String ruleKind,
            String itemId,
            String grain,
            String threshold,
            String direction,
            Integer baselinePeriods,
            String description,
            String periodStart,
            String periodEnd
    ) {
        public EvaluateRequest {
            if (ruleId == null || ruleId.isBlank()) {
                throw new IllegalArgumentException("ruleId is required");
            }
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("itemId is required");
            }
            if (grain == null || grain.isBlank()) {
                throw new IllegalArgumentException("grain is required");
            }
            if (threshold == null || threshold.isBlank()) {
                throw new IllegalArgumentException("threshold is required");
            }
            if (direction == null || direction.isBlank()) {
                throw new IllegalArgumentException("direction is required");
            }
            if (periodStart == null || periodStart.isBlank()) {
                throw new IllegalArgumentException("periodStart is required");
            }
            if (periodEnd == null || periodEnd.isBlank()) {
                throw new IllegalArgumentException("periodEnd is required");
            }
        }
    }

    public static WarningView toView(WarningRecordV1 warning, boolean acknowledged, String ackRef) {
        return new WarningView(
                warning.warningId(), warning.ruleId(), warning.ruleVersion(), warning.itemId(),
                warning.grain(), warning.periodStart(), warning.periodEnd(), warning.threshold(),
                percent(warning.threshold()), warning.currentValue(), warning.baselineValue(),
                warning.riskLevel() == null ? null : warning.riskLevel().name(),
                warning.evidenceRefs(), warning.dataStatus(),
                warning.evaluatedAt() == null ? null : warning.evaluatedAt().toString(),
                warning.demoRule(), warning.ruleDescription(), acknowledged, ackRef);
    }

    private static String percent(String ratio) {
        return new java.math.BigDecimal(ratio).movePointRight(2).stripTrailingZeros().toPlainString();
    }

    private static String percentNullable(String ratio) {
        return ratio == null ? null : percent(ratio);
    }

    public static AckView toView(WarningAcknowledgementV1 ack) {
        return new AckView(
                ack.warningId(), ack.warningRef(), ack.warningFileSha256(),
                ack.status() == null ? null : ack.status().name(),
                ack.acknowledgedAt() == null ? null : ack.acknowledgedAt().toString(),
                ack.dispositionNote());
    }
}
