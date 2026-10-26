package com.supplymind.agent.orchestration;

import com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter;
import com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter;
import com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter;
import com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;

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

    public java.time.LocalDate latestBusinessDate(String itemId,java.time.Clock clock) {
        if(itemId==null||itemId.isBlank())return null;
        var today=java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Shanghai")));
        var result=historyQuery.historyQuery(itemId,today.minusYears(10).plusDays(1).toString(),today.toString(),"scope-discovery");
        Object rows=result.result().get("rows");
        if(rows instanceof List<?> list)return list.stream().filter(r->r instanceof java.util.Map<?,?>).map(r->((java.util.Map<?,?>)r).get("businessDate")).filter(Objects::nonNull).map(String::valueOf).max(String::compareTo).map(java.time.LocalDate::parse).orElse(null);
        return null;
    }

    public List<ToolResult> execute(AgentOrchestrator.AgentQueryInput input, String requestId) {
        List<ToolResult> results = new ArrayList<>();
        ToolResult resolved = input.itemId() == null || input.itemId().isBlank()
                ? seriesResolve.seriesResolveQuestion(input.question(), requestId)
                : seriesResolve.seriesResolve(input.itemId(), requestId);
        results.add(resolved);
        if (resolved.status() != ToolStatus.SUCCESS) {
            return results;
        }
        String effectiveItemId = String.valueOf(resolved.result().get("itemId"));
        boolean hasHistoryRange = input.startDate() != null && input.endDate() != null;
        boolean hasPeriod = input.periodStart() != null;
        boolean hasMonth = input.month() != null;
        boolean hasGrain = input.grain() != null;

        if (hasHistoryRange) {
            results.add(historyQuery.historyQuery(
                    effectiveItemId, input.startDate(), input.endDate(), requestId));
            results.add(qualityInspect.qualityInspect(
                    effectiveItemId, input.startDate(), input.endDate(), requestId));
            results.add(provenanceTrace.provenanceTrace(
                    effectiveItemId, input.startDate(), input.endDate(), requestId));
        }
        if (hasPeriod && hasGrain) {
            int year = java.time.YearMonth.parse(input.periodStart().substring(0, 7)).getYear();
            var metrics=periodMetrics.periodMetrics(
                    effectiveItemId, input.grain(), String.valueOf(year), input.periodEnd()==null?String.valueOf(year):input.periodEnd().substring(0,4), requestId);
            if(metrics.status()==ToolStatus.SUCCESS && metrics.result().get("rows") instanceof List<?> rows) {
                String scopedStart=input.periodStart().length()==7?java.time.YearMonth.parse(input.periodStart()).atDay(1).toString():input.periodStart();
                String scopedEnd=input.periodEnd()==null?null:input.periodEnd().length()==7?java.time.YearMonth.parse(input.periodEnd()).atEndOfMonth().toString():input.periodEnd();
                var scoped=rows.stream().filter(r->r instanceof java.util.Map<?,?>).map(r->(java.util.Map<?,?>)r)
                        .filter(r->String.valueOf(r.get("periodEnd")).compareTo(scopedStart)>=0)
                        .filter(r->scopedEnd==null||String.valueOf(r.get("periodStart")).compareTo(scopedEnd)<=0).toList();
                var body=new java.util.LinkedHashMap<>(metrics.result());body.put("rows",scoped);body.put("rowCount",scoped.size());
                metrics=new ToolResult(metrics.toolName(),metrics.toolVersion(),metrics.requestId(),scoped.isEmpty()?ToolStatus.NO_DATA:ToolStatus.SUCCESS,metrics.inputSummary(),body,metrics.notices(),metrics.evidenceRefs(),metrics.lineage(),metrics.evidenceLineageByRef());
            }
            results.add(metrics);
            results.add(costImpact.costImpact(
                    effectiveItemId, input.grain(), input.periodStart(), requestId));
        }
        if (hasMonth) {
            var first=java.time.YearMonth.parse(input.month());
            var last=input.endDate()==null?first:java.time.YearMonth.parse(input.endDate().substring(0,7));
            if(java.time.temporal.ChronoUnit.MONTHS.between(first,last)>120)throw new IllegalArgumentException("预警范围超过十年");
            for(var cursor=first;!cursor.isAfter(last);cursor=cursor.plusMonths(1))results.add(warningExplain.warningExplain(effectiveItemId,cursor.toString(),requestId));
        }
        return List.copyOf(results);
    }
}
