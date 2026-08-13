package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.history.HistoryQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D6-T01 history.query: historical daily values for one series over an explicit date range.
 * Reuses the production HistoryQueryService (cross-file merge, stable dedupe, deterministic
 * sort, conflict EXCLUDED_AND_REPORTED, missing != zero). Values are BigDecimal strings from
 * the processed daily files; the range is bounded (max 10 years).
 */
public final class HistoryQueryToolAdapter {

    public static final String TOOL_NAME = "history.query";
    public static final String TOOL_VERSION = "1.0";

    private final HistoryQueryService history;

    public HistoryQueryToolAdapter(HistoryQueryService history) {
        this.history = history;
    }

    @Tool(name = TOOL_NAME, description = "Query historical daily values for a series over an explicit business-date range (max 10 years).")
    public ToolResult historyQuery(
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
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> evidenceRefs = new ArrayList<>();
            for (DailyRecordV1 row : result.rows()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("businessDate", row.businessDate());
                entry.put("value", row.avg());
                entry.put("unit", row.unit());
                entry.put("currency", row.currency());
                entry.put("validationStatus", row.validationStatus().wireValue());
                entry.put("validationVersion", row.validationVersion());
                entry.put("complete", row.complete());
                rows.add(entry);
                for (var input : row.inputRefs()) {
                    if (!evidenceRefs.contains(input.rawRef())) {
                        evidenceRefs.add(input.rawRef());
                    }
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", rows);
            body.put("rowCount", rows.size());
            body.put("missingRefs", result.missingRefs());
            body.put("corruptRefs", result.corruptRefs());
            body.put("conflictKeys", result.conflictKeys());
            if (rows.isEmpty()) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "itemId=" + safeItem + " range=" + from + ".." + to, "no published daily rows in range");
            }
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + " range=" + from + ".." + to, body,
                    List.copyOf(evidenceRefs), List.of());
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "history read unavailable: " + exception.getClass().getSimpleName());
        }
    }
}
