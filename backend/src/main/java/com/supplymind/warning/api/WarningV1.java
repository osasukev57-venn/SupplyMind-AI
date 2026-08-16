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

    /** Request-driven evaluation (v1 demo rules only; EXT-07/EXT-08 stay open). */
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
                warning.currentValue(), warning.baselineValue(),
                warning.riskLevel() == null ? null : warning.riskLevel().name(),
                warning.evidenceRefs(), warning.dataStatus(),
                warning.evaluatedAt() == null ? null : warning.evaluatedAt().toString(),
                warning.demoRule(), warning.ruleDescription(), acknowledged, ackRef);
    }

    public static AckView toView(WarningAcknowledgementV1 ack) {
        return new AckView(
                ack.warningId(), ack.warningRef(), ack.warningFileSha256(),
                ack.status() == null ? null : ack.status().name(),
                ack.acknowledgedAt() == null ? null : ack.acknowledgedAt().toString(),
                ack.dispositionNote());
    }
}
