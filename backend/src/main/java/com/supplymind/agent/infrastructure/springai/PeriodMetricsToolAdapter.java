package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.history.HistoryQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D6-T01 period.metrics: persisted aggregate metrics for one series and grain over a year
 * range. Reuses the production HistoryQueryService aggregate read (manifest-verified
 * processed/aggregate files); values are frozen BigDecimal strings, never recomputed.
 */
public final class PeriodMetricsToolAdapter {

    public static final String TOOL_NAME = "period.metrics";
    public static final String TOOL_VERSION = "1.0";

    private final HistoryQueryService history;

    public PeriodMetricsToolAdapter(HistoryQueryService history) {
        this.history = history;
    }

    @Tool(name = TOOL_NAME, description = "Query persisted aggregate metrics for a series and grain (month/quarter/halfyear/year) over a year range.")
    public ToolResult periodMetrics(
            @ToolParam(description = "monitored series itemId") String itemId,
            @ToolParam(description = "aggregate grain: month, quarter, halfyear or year") String grain,
            @ToolParam(description = "start year, e.g. 2026") String fromYear,
            @ToolParam(description = "end year, e.g. 2026") String toYear,
            @ToolParam(description = "request id for traceability") String requestId
    ) {
        try {
            String safeItem = ToolArguments.identifier(itemId, "itemId", TOOL_NAME);
            String safeGrain = ToolArguments.grain(grain, TOOL_NAME);
            int from = ToolArguments.year(fromYear, "fromYear", TOOL_NAME);
            int to = ToolArguments.year(toYear, "toYear", TOOL_NAME);
            if (from > to) {
                throw new ToolInputException(TOOL_NAME, "fromYear must not be after toYear");
            }
            HistoryQueryService.AggregateHistoryResult result =
                    history.queryAggregate(safeItem, safeGrain, from, to);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AggregateRecordV1 row : result.rows()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("periodStart", row.periodStart());
                entry.put("periodEnd", row.periodEnd());
                entry.put("grain", row.grain().wireValue());
                entry.put("avg", row.avg());
                entry.put("min", row.min());
                entry.put("max", row.max());
                entry.put("unit", row.unit());
                entry.put("currency", row.currency());
                entry.put("validationStatus", row.validationStatus().wireValue());
                entry.put("validationVersion", row.validationVersion());
                entry.put("complete", row.complete());
                entry.put("calculatedAt", row.calculatedAt() == null ? null : row.calculatedAt().toString());
                rows.add(entry);
            }
            if (rows.isEmpty()) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "itemId=" + safeItem + " grain=" + safeGrain + " years=" + from + ".." + to,
                        "no persisted aggregate rows in range");
            }
            List<String> evidenceRefs = new ArrayList<>();
            for (int year = from; year <= to; year++) {
                evidenceRefs.add(DataPaths.aggregateRef(safeItem, safeGrain, year));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", rows);
            body.put("rowCount", rows.size());
            body.put("missingRefs", result.missingRefs());
            body.put("corruptRefs", result.corruptRefs());
            body.put("conflictKeys", result.conflictKeys());
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + " grain=" + safeGrain + " years=" + from + ".." + to,
                    body, List.copyOf(evidenceRefs), List.of());
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "aggregate read unavailable: " + exception.getClass().getSimpleName());
        }
    }
}
