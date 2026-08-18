package com.supplymind.agent.api;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.evidence.RiskProjectionV1;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;

import java.util.ArrayList;
import java.util.List;

/**
 * D8-T03/M3 Agent API structured response. D6-T04 fields (requestId/answer/llmStatus/degraded/
 * degradeReason/toolTrace/evidenceRefs/reportRef/facts) are PRESERVED verbatim; D8-T03 adds
 * the report-level projection (generatedBy/model/provider, intent/scope, limitations,
 * recommendations, claims and dataThrough) mapped ONLY from the verified
 * AgentReportV1/EvidencePackV1. M3 adds CONTROLLED evidence navigation links (target view +
 * route parameters - never file paths), the calculation basis and a risk/quality projection -
 * the frontend never re-derives risk, recommendations, results or navigable links.
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
        String dataThrough,
        // ---- D8-M3 additions ----
        List<EvidenceLinkView> evidenceLinks,
        CalculationBasisView calculationBasis,
        RiskView risk
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

    /**
     * M3: a CONTROLLED evidence navigation descriptor. It is projected ONLY from the verified
     * EvidencePack metadata (refType/status/businessDate/periodStart/periodEnd) - the frontend
     * never parses internal file paths and never sees absolute paths, dataRoot or Windows
     * paths. targetView is HISTORY/WARNING/QUALITY; route/query carry the parameters needed to
     * re-execute the target query in the frontend router.
     */
    public record EvidenceLinkView(
            String evidenceId,
            String evidenceType,
            String itemId,
            String businessDate,
            String periodStart,
            String periodEnd,
            String grain,
            String targetView,
            String route,
            String query
    ) {
    }

    /** M3: calculation/validation basis projected from the verified evidence lineage. */
    public record CalculationBasisView(
            String validationVersion,
            String calculationVersion,
            String calendarVersion,
            List<String> configVersions
    ) {
        public CalculationBasisView {
            configVersions = configVersions == null ? List.of() : List.copyOf(configVersions);
        }
    }

    /** M3: risk/quality view projected from Java/EvidencePack/Warning facts only. */
    public record RiskView(
            String riskLevel,
            String currentValue,
            String baselineValue,
            String threshold,
            String dataStatus
    ) {
    }

    /** M3: backend-projected grain for an aggregate evidence ref. */
    static String aggregateGrainOf(String ref) {
        String[] segments = ref == null ? new String[0] : ref.split("/");
        if (segments.length >= 4 && "processed".equals(segments[0])
                && "aggregate".equals(segments[1])) {
            return segments[3];
        }
        return null;
    }

    static String grainOf(String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("processed/daily/")) {
            return "daily";
        }
        return aggregateGrainOf(ref);
    }

    /** Builds the full D8-T03/M3 projection from a verified AgentResult. */
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
        // M3: navigable links ONLY from VERIFIED evidence entries (MISSING/INVALID/UNAVAILABLE
        // never produce a fake link). Projected from metadata, never from file-path parsing.
        // The link itemId is the EvidencePack scope itemId - EvidenceRefEntry carries no
        // itemId field, so the frontend is never asked to guess one from a file path.
        List<EvidenceLinkView> links = new ArrayList<>();
        String linkItemId = pack.scope() == null || pack.scope().itemIds().isEmpty()
                ? "" : pack.scope().itemIds().get(0);
        for (EvidencePackV1.EvidenceRefEntry entry : pack.evidenceRefs()) {
            if (entry.status() != EvidenceStatus.VERIFIED) {
                continue;
            }
            String targetView;
            String route;
            String query;
            if (entry.ref() != null && entry.ref().startsWith("warning/")) {
                targetView = "WARNING";
                route = "/warning";
                query = "itemId=" + linkItemId + "&from=1900-01-01&to=2999-12-31";
            } else if (entry.ref() != null && entry.ref().startsWith("processed/aggregate/")) {
                targetView = "HISTORY";
                route = "/history";
                String from = entry.periodStart() == null ? "1900-01-01" : entry.periodStart();
                String to = entry.periodEnd() == null ? "2999-12-31" : entry.periodEnd();
                // M3: the aggregate grain is projected by the BACKEND from the ref metadata
                // (processed/aggregate/<itemId>/<grain>/<year>.csv) - never parsed by the client.
                String grain = aggregateGrainOf(entry.ref());
                query = "itemId=" + linkItemId + "&from=" + from + "&to=" + to
                        + "&grain=" + (grain == null ? "month" : grain);
            } else if (entry.ref() != null && entry.ref().startsWith("processed/daily/")) {
                targetView = "HISTORY";
                route = "/history";
                String businessDate = entry.businessDate() == null ? "" : entry.businessDate();
                query = "itemId=" + linkItemId + "&from=" + businessDate + "&to=" + businessDate
                        + "&grain=daily";
            } else {
                targetView = "QUALITY";
                route = "/quality";
                String businessDate = entry.businessDate() == null ? "" : entry.businessDate();
                query = "itemId=" + linkItemId + "&from=" + businessDate + "&to=" + businessDate;
            }
            links.add(new EvidenceLinkView(
                    entry.evidenceRefId(), entry.refType(), linkItemId,
                    entry.businessDate(), entry.periodStart(), entry.periodEnd(),
                    grainOf(entry.ref()), targetView, route, query));
        }
        // M3: calculation basis from the first lineage-complete verified entry.
        CalculationBasisView basis = null;
        for (EvidencePackV1.EvidenceRefEntry entry : pack.evidenceRefs()) {
            if (entry.status() != EvidenceStatus.VERIFIED) {
                continue;
            }
            if (entry.validationVersion() != null || entry.calculationVersion() != null
                    || entry.calendarVersion() != null || !entry.configVersions().isEmpty()) {
                basis = new CalculationBasisView(
                        entry.validationVersion(), entry.calculationVersion(),
                        entry.calendarVersion(), entry.configVersions());
                break;
            }
        }
        // M3: risk view comes ONLY from the structured RiskProjectionV1 built by the
        // orchestrator from the real warning.explain ToolResult rows (VERIFIED + lineage-
        // complete). No string-contains guessing, no manual construction here.
        RiskView risk = null;
        if (result.riskProjection() != null) {
            RiskProjectionV1 projection = result.riskProjection();
            risk = new RiskView(
                    projection.riskLevel(),
                    projection.currentValue(),
                    projection.baselineValue(),
                    projection.threshold(),
                    projection.dataStatus());
        }
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
                dataThrough,
                links,
                basis,
                risk);
    }
}
