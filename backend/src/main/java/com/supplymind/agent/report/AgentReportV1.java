package com.supplymind.agent.report;

import com.supplymind.agent.evidence.EvidencePackV1;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * D6-T04 AgentReportV1 strictly per AGENT-EVIDENCE-SCHEMA-V1 §3 (schemaVersion
 * AGENT-REPORT-V1). Java facts and LLM/template explanation are separated; generatedBy records
 * LLM or JAVA_TEMPLATE, degraded/degradeReason record the fallback path; claims only restate
 * verified evidence.
 */
public record AgentReportV1(
        String schemaVersion,
        String reportId,
        String requestId,
        EvidencePackV1 evidencePack,
        String generatedBy,
        String provider,
        String model,
        boolean degraded,
        String degradeReason,
        List<FactSummary> factsSummary,
        List<Claim> claims,
        List<String> recommendations,
        List<String> limitations,
        OffsetDateTime createdAt
) {
    public AgentReportV1 {
        if (!"AGENT-REPORT-V1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be AGENT-REPORT-V1");
        }
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException("reportId is required");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (evidencePack == null) {
            throw new IllegalArgumentException("evidencePack is required");
        }
        factsSummary = factsSummary == null ? List.of() : List.copyOf(factsSummary);
        claims = claims == null ? List.of() : List.copyOf(claims);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public record FactSummary(
            String factId,
            String statement,
            String value,
            String businessDate,
            String period,
            String validationStatus
    ) {
    }

    public record Claim(
            String claimId,
            String text,
            List<String> evidenceRefs
    ) {
        public Claim {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}
