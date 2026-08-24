package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.storage.DataPaths;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * D6-T01 series.resolve: resolve a monitored series from the CURRENT active monitor-series
 * configuration (H07/H09). Accepts itemId, displayName or externalCode. Read-only; unknown
 * targets are a structured NO_DATA, never a guess.
 */
public final class SeriesResolveToolAdapter {

    public static final String TOOL_NAME = "series.resolve";
    public static final String TOOL_VERSION = "1.0";

    private final ConfigManagementService configManagement;

    public SeriesResolveToolAdapter(ConfigManagementService configManagement) {
        this.configManagement = configManagement;
    }

    @Tool(name = TOOL_NAME, description = "Resolve a monitored series from the active monitor-series configuration by itemId, displayName or externalCode.")
    public ToolResult seriesResolve(
            @ToolParam(description = "itemId, displayName or externalCode of the series") String target,
            @ToolParam(description = "request id for traceability") String requestId
    ) {
        try {
            String safeTarget = ToolArguments.identifier(target, "target", TOOL_NAME);
            MonitorSeriesConfigV1 active = configManagement.active();
            MonitorSeriesItemV1 item = find(active, safeTarget);
            if (item == null) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "target=" + safeTarget, "unknown series in active config");
            }
            return resolved(requestId, "target=" + safeTarget, active, item);
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "active configuration unavailable: " + exception.getClass().getSimpleName());
        }
    }

    public ToolResult seriesResolveQuestion(String question, String requestId) {
        try {
            if (question == null || question.isBlank()) {
                return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                        "question is required when itemId is omitted");
            }
            MonitorSeriesConfigV1 active = configManagement.active();
            String normalizedQuestion = normalize(question);
            int bestScore = 0;
            java.util.List<MonitorSeriesItemV1> best = new java.util.ArrayList<>();
            for (MonitorSeriesItemV1 item : active.items()) {
                if (!item.enabled()) continue;
                int score = matchScore(normalizedQuestion, item);
                if (score > bestScore) {
                    bestScore = score;
                    best.clear();
                    best.add(item);
                } else if (score > 0 && score == bestScore) {
                    best.add(item);
                }
            }
            if (best.isEmpty()) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "questionTargetResolve", "no unique monitored series mentioned in question");
            }
            if (best.size() > 1) {
                List<String> candidates = best.stream()
                        .map(MonitorSeriesItemV1::itemId).sorted().toList();
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "questionTargetResolve candidates=" + String.join(",", candidates),
                        "ambiguous monitored series; provide itemId");
            }
            return resolved(requestId, "questionTargetResolve", active, best.get(0));
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "active configuration unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private static ToolResult resolved(
            String requestId, String inputSummary,
            MonitorSeriesConfigV1 active, MonitorSeriesItemV1 item
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", item.itemId());
        result.put("displayName", item.displayName());
        result.put("enabled", item.enabled());
        result.put("providerType", item.providerType().wireValue());
        result.put("accessMethod", item.accessMethod().wireValue());
        result.put("rateKind", item.rateKind());
        result.put("unit", item.unit());
        result.put("currency", item.currency());
        result.put("configVersion", active.configVersion());
        return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                inputSummary, result, List.of(DataPaths.configActiveRef()), List.of());
    }

    private static int matchScore(String question, MonitorSeriesItemV1 item) {
        if (contains(question, item.itemId())) return 100;
        if (contains(question, item.displayName())) return 90;
        if (contains(question, item.sourceFieldKey())) return 80;
        if (contains(question, item.externalCode())) return 70;
        return 0;
    }

    private static boolean contains(String normalizedQuestion, String candidate) {
        return candidate != null && !candidate.isBlank()
                && normalizedQuestion.contains(normalize(candidate));
    }

    private static String normalize(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private static MonitorSeriesItemV1 find(MonitorSeriesConfigV1 active, String target) {
        for (MonitorSeriesItemV1 item : active.items()) {
            if (item.itemId().equals(target) || item.displayName().equals(target)
                    || target.equals(item.externalCode())) {
                return item;
            }
        }
        return null;
    }
}
