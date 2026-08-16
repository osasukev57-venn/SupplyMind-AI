package com.supplymind.agent.api;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;

import java.util.ArrayList;
import java.util.List;

/**
 * D8-T03 Agent API structured response. D6-T04 fields (requestId/answer/llmStatus/degraded/
 * degradeReason/toolTrace/evidenceRefs/reportRef/facts) are PRESERVED verbatim; D8-T03 adds
 * the report-level projection (generatedBy/model/provider, intent/scope, calculation basis,
 * risk/quality view, recommendations, limitations, claims and dataThrough) mapped ONLY from the
 * verified AgentReportV1/EvidencePackV1 - the frontend never re-derives risk, recommendations
 * or results. Evidence references are controlled descriptors, never absolute paths.
 */
public record AgentQueryResponse(
        String requestId,
        String answer,
        String llmStatus,
        boolean degraded,
        String degradeReason,
        List<ToolExecutionView> toolTrace,
        List<String> evidenceRefs,
        String reportRef,
        List<FactView> facts,
        // ---- D8-T03 additions (backward compatible) ----
        String generatedBy,
        String provider,
        String model,
        ScopeView scope,
        List<String> limitations,
        List<String> recommendations,
        List<ClaimView> claims,
        String dataThrough
) {
    public record ToolExecutionView(
            int invocationIndex,
            String toolName,
            String toolVersion,
            boolean readOnly,
            String input,
            String output,
            String status,
            List<String> evidenceRefs
    ) {
        public ToolExecutionView {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record FactView(
            String factId,
            String statement,
            String value,
            String businessDate,
            String period,
            String validationStatus
    ) {
    }

    /** Intent/scope projection from the EvidencePack scope - the frontend never infers it. */
    public record ScopeView(
            List<String> itemIds,
            String businessDate,
            String periodStart,
            String periodEnd,
            String timezone
    ) {
        public ScopeView {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    /** One verified claim from the persisted AgentReport (with its evidenceRefs). */
    public record ClaimView(
            String claimId,
            String text,
            List<String> evidenceRefs
    ) {
        public ClaimView {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** Builds the full D8-T03 projection from a verified AgentResult. */
    public static AgentQueryResponse of(AgentOrchestrator.AgentResult result) {
        EvidencePackV1 pack = result.evidencePack();
        List<ToolExecutionView> executions = new ArrayList<>();
        for (EvidencePackV1.ToolExecution execution : pack.toolExecutions()) {
            executions.add(new ToolExecutionView(
                    execution.invocationIndex(), execution.toolName(), execution.toolVersion(),
                    execution.readOnly(), String.valueOf(execution.input()),
                    String.valueOf(execution.output()), execution.status().name(),
                    execution.evidenceRefs()));
        }
        List<String> evidenceRefs = pack.evidenceRefs().stream()
                .map(EvidencePackV1.EvidenceRefEntry::ref).toList();
        AgentReportV1 report = result.report();
        List<ClaimView> claims = new ArrayList<>();
        if (report != null) {
            for (AgentReportV1.Claim claim : report.claims()) {
                claims.add(new ClaimView(claim.claimId(), claim.text(), claim.evidenceRefs()));
            }
        }
        String dataThrough = pack.facts().stream()
                .map(EvidencePackV1.Fact::businessDate)
                .filter(value -> value != null)
                .max(java.util.Comparator.naturalOrder())
                .orElse(null);
        return new AgentQueryResponse(
                pack.requestId(),
                result.llmResponse().explanation() != null && !result.llmResponse().explanation().isBlank()
                        ? result.llmResponse().explanation()
                        : (report == null || report.claims().isEmpty()
                        ? null : report.claims().get(0).text()),
                result.llmResponse().status().name(),
                result.degraded(),
                result.degradeReason(),
                executions,
                evidenceRefs,
                result.reportRef(),
                report == null ? List.of() : report.factsSummary().stream()
                        .map(fact -> new FactView(
                                fact.factId(), fact.statement(), fact.value(),
                                fact.businessDate(), fact.period(), fact.validationStatus()))
                        .toList(),
                report == null ? null : report.generatedBy(),
                report == null ? null : report.provider(),
                report == null ? null : report.model(),
                new ScopeView(pack.scope() == null ? List.of() : pack.scope().itemIds(),
                        pack.scope() == null ? null : pack.scope().businessDate(),
                        pack.scope() == null ? null : pack.scope().periodStart(),
                        pack.scope() == null ? null : pack.scope().periodEnd(),
                        pack.scope() == null ? null : pack.scope().timezone()),
                report == null ? List.of() : report.limitations(),
                report == null ? List.of() : report.recommendations(),
                claims,
                dataThrough);
    }
}
