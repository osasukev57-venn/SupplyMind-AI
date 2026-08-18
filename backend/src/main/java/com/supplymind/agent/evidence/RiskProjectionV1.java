package com.supplymind.agent.evidence;

import java.util.List;
import java.util.Objects;

/**
 * M3: structured risk/quality projection from the REAL warning.explain ToolResult row contract.
 * Every field comes from ONE manifest-verified, lineage-complete warning evidence row
 * (warningId/currentValue/baselineValue/threshold/riskLevel/dataStatus/periodStart/periodEnd/
 * grain + its evidenceRef). It is projected by the backend orchestrator - the frontend never
 * computes risk, thresholds or status. Not part of the frozen AGENT-EVIDENCE-SCHEMA-V1;
 * this is a backend-owned presentation projection.
 */
public record RiskProjectionV1(
        String warningId,
        String itemId,
        String grain,
        String periodStart,
        String periodEnd,
        String riskLevel,
        String currentValue,
        String baselineValue,
        String threshold,
        String dataStatus,
        List<String> evidenceRefs
) {
    public RiskProjectionV1 {
        Objects.requireNonNull(warningId, "warningId");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(currentValue, "currentValue");
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("RiskProjectionV1 requires at least one evidenceRef");
        }
    }
}
