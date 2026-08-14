package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.history.HistoryQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D6-T01 cost.impact: cost-impact overview for a series. The tool only re-reads persisted
 * aggregate rows (frozen BigDecimal strings) and reports the deterministic period-over-period
 * change ratio in BigDecimal plain string form; it never applies unconfirmed cost weights
 * (EXT-08 open) and never recomputes the business aggregates. Missing baselines are NO_DATA.
 */
public final class CostImpactToolAdapter {

    public static final String TOOL_NAME = "cost.impact";
    public static final String TOOL_VERSION = "1.0";

    private final HistoryQueryService history;

    public CostImpactToolAdapter(HistoryQueryService history) {
        this.history = history;
    }

    @Tool(name = TOOL_NAME, description = "Cost-impact overview for a series: current vs previous period average change ratio (BigDecimal), with evidence refs. EXT-08 cost weights unconfirmed; ratio only.")
    public ToolResult costImpact(
            @ToolParam(description = "monitored series itemId") String itemId,
            @ToolParam(description = "aggregate grain: month, quarter, halfyear or year") String grain,
            @ToolParam(description = "current period start, ISO yyyy-MM-dd") String periodStart,
            @ToolParam(description = "request id for traceability") String requestId
    ) {
        try {
            String safeItem = ToolArguments.identifier(itemId, "itemId", TOOL_NAME);
            String safeGrain = ToolArguments.grain(grain, TOOL_NAME);
            LocalDate start = ToolArguments.date(periodStart, "periodStart", TOOL_NAME);
            YearMonth startMonth = YearMonth.from(start);
            YearMonth previousMonth = startMonth.minusMonths(monthsPerGrain(safeGrain));
            YearMonth currentEnd = startMonth.plusMonths(monthsPerGrain(safeGrain)).minusMonths(1);
            YearMonth previousEnd = previousMonth.plusMonths(monthsPerGrain(safeGrain)).minusMonths(1);

            String currentAvg = aggregateAvg(safeItem, safeGrain, startMonth, currentEnd);
            String previousAvg = aggregateAvg(safeItem, safeGrain, previousMonth, previousEnd);
            if (currentAvg == null || previousAvg == null) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "itemId=" + safeItem + " grain=" + safeGrain + " periodStart=" + start,
                        currentAvg == null ? "no current-period aggregate" : "no previous-period baseline");
            }
            BigDecimal current = new BigDecimal(currentAvg);
            BigDecimal previous = new BigDecimal(previousAvg);
            if (previous.signum() == 0) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "itemId=" + safeItem + " grain=" + safeGrain + " periodStart=" + start,
                        "previous-period baseline is zero; ratio undefined");
            }
            // M6: the change ratio is computed by the SAME production component as the Day5
            // warning chain (CostImpactCalculator) - the Agent tool owns no business formula.
            BigDecimal changeRatio = com.supplymind.processing.CostImpactCalculator
                    .changeRatio(current, previous);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemId", safeItem);
            body.put("grain", safeGrain);
            body.put("periodStart", startMonth.atDay(1).toString());
            body.put("periodEnd", currentEnd.atEndOfMonth().toString());
            body.put("currentAvg", currentAvg);
            body.put("previousAvg", previousAvg);
            body.put("changeRatio", changeRatio.toPlainString());
            body.put("note", "EXT-08 cost weights unconfirmed; ratio only, no cost-weight application");
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + " grain=" + safeGrain + " periodStart=" + start, body,
                    List.of(DataPaths.aggregateRef(safeItem, safeGrain, startMonth.getYear())),
                    List.of("EXT-08 open: change ratio is not a confirmed cost impact"));
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "cost-impact read unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private String aggregateAvg(String itemId, String grain, YearMonth start, YearMonth end) {
        HistoryQueryService.AggregateHistoryResult result =
                history.queryAggregate(itemId, grain, start.getYear(), end.getYear());
        return result.rows().stream()
                .filter(row -> row.periodStart().equals(start.atDay(1).toString())
                        && row.periodEnd().equals(end.atEndOfMonth().toString()))
                .map(row -> row.avg())
                .findFirst().orElse(null);
    }

    private static int monthsPerGrain(String grain) {
        return switch (grain) {
            case "month" -> 1;
            case "quarter" -> 3;
            case "halfyear" -> 6;
            case "year" -> 12;
            default -> 1;
        };
    }
}
