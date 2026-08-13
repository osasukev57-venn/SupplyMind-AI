package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.history.HistoryQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D6-T01 quality.inspect: data quality for one series over a range - validation status counts,
 * completeness (expected/missing persisted on the daily rows) and missing/corrupt/conflict
 * reports from the production history reader. Missing files are never zero-filled.
 */
public final class QualityInspectToolAdapter {

    public static final String TOOL_NAME = "quality.inspect";
    public static final String TOOL_VERSION = "1.0";

    private final HistoryQueryService history;

    public QualityInspectToolAdapter(HistoryQueryService history) {
        this.history = history;
    }

    @Tool(name = TOOL_NAME, description = "Inspect data quality for a series over a date range: validation statuses, completeness, missing/corrupt/conflict reports.")
    public ToolResult qualityInspect(
            @ToolParam(description = "monitored series itemId") String itemId,
            @ToolParam(description = "range start, ISO yyyy-MM-dd") String startDate,
            @ToolParam(description = "range end, ISO yyyy-MM-dd") String endDate,
            @ToolParam(description = "request id for traceability") String requestId
    ) {
        try {
            String safeItem = ToolArguments.identifier(itemId, "itemId", TOOL_NAME);
            LocalDate from = ToolArguments.date(startDate, "startDate", TOOL_NAME);
            LocalDate to = ToolArguments.date(endDate, "endDate", TOOL_NAME);
            ToolArguments.range(from, to, TOOL_NAME);
            HistoryQueryService.DailyHistoryResult result = history.queryDaily(safeItem, from, to);
            Map<String, Integer> statusCounts = new LinkedHashMap<>();
            int expected = 0;
            int missing = 0;
            int completeRows = 0;
            for (DailyRecordV1 row : result.rows()) {
                statusCounts.merge(row.validationStatus().wireValue(), 1, Integer::sum);
                expected += row.expectedCount();
                missing += row.missingCount();
                if (row.complete()) {
                    completeRows++;
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemId", safeItem);
            body.put("rowCount", result.rows().size());
            body.put("validationStatusCounts", statusCounts);
            body.put("expectedCountTotal", expected);
            body.put("missingCountTotal", missing);
            body.put("completeRowCount", completeRows);
            body.put("missingRefs", result.missingRefs());
            body.put("corruptRefs", result.corruptRefs());
            body.put("conflictKeys", result.conflictKeys());
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + " range=" + from + ".." + to, body,
                    java.util.List.of(), java.util.List.of());
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "quality read unavailable: " + exception.getClass().getSimpleName());
        }
    }
}
