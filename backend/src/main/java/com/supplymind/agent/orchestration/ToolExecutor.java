package com.supplymind.agent.orchestration;

import com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter;
import com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter;
import com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter;
import com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D6-T02/D6-T04 Java-decided tool executor: given the query input, Java (not the LLM) decides
 * which read-only tools to run and in which order. This keeps the tool chain deterministic and
 * audit-able; the LLM only explains the resulting facts. Unknown targets, missing parameters
 * and demo data are handled with structured results.
 */
public final class ToolExecutor {

    private final SeriesResolveToolAdapter seriesResolve;
    private final HistoryQueryToolAdapter historyQuery;
    private final PeriodMetricsToolAdapter periodMetrics;
    private final QualityInspectToolAdapter qualityInspect;
    private final CostImpactToolAdapter costImpact;
    private final WarningExplainToolAdapter warningExplain;
    private final ProvenanceTraceToolAdapter provenanceTrace;

    public ToolExecutor(
            SeriesResolveToolAdapter seriesResolve,
            HistoryQueryToolAdapter historyQuery,
            PeriodMetricsToolAdapter periodMetrics,
            QualityInspectToolAdapter qualityInspect,
            CostImpactToolAdapter costImpact,
            WarningExplainToolAdapter warningExplain,
            ProvenanceTraceToolAdapter provenanceTrace
    ) {
        this.seriesResolve = Objects.requireNonNull(seriesResolve, "seriesResolve");
        this.historyQuery = Objects.requireNonNull(historyQuery, "historyQuery");
        this.periodMetrics = Objects.requireNonNull(periodMetrics, "periodMetrics");
        this.qualityInspect = Objects.requireNonNull(qualityInspect, "qualityInspect");
        this.costImpact = Objects.requireNonNull(costImpact, "costImpact");
        this.warningExplain = Objects.requireNonNull(warningExplain, "warningExplain");
        this.provenanceTrace = Objects.requireNonNull(provenanceTrace, "provenanceTrace");
    }

    public List<ToolResult> execute(AgentOrchestrator.AgentQueryInput input, String requestId) {
        List<ToolResult> results = new ArrayList<>();
        if (input.itemId() == null || input.itemId().isBlank()) {
            results.add(ToolResult.rejected("series.resolve", "1.0", requestId,
                    "itemId is required"));
            return results;
        }
        results.add(seriesResolve.seriesResolve(input.itemId(), requestId));
        boolean hasHistoryRange = input.startDate() != null && input.endDate() != null;
        boolean hasPeriod = input.periodStart() != null;
        boolean hasMonth = input.month() != null;
        boolean hasGrain = input.grain() != null;

        if (hasHistoryRange) {
            results.add(historyQuery.historyQuery(
                    input.itemId(), input.startDate(), input.endDate(), requestId));
            results.add(qualityInspect.qualityInspect(
                    input.itemId(), input.startDate(), input.endDate(), requestId));
            results.add(provenanceTrace.provenanceTrace(
                    input.itemId(), input.startDate(), input.endDate(), requestId));
        }
        if (hasPeriod && hasGrain) {
            int year = java.time.YearMonth.parse(input.periodStart().substring(0, 7)).getYear();
            results.add(periodMetrics.periodMetrics(
                    input.itemId(), input.grain(), String.valueOf(year), String.valueOf(year), requestId));
            results.add(costImpact.costImpact(
                    input.itemId(), input.grain(), input.periodStart(), requestId));
        }
        if (hasMonth) {
            results.add(warningExplain.warningExplain(input.itemId(), input.month(), requestId));
        }
        return List.copyOf(results);
    }
}
