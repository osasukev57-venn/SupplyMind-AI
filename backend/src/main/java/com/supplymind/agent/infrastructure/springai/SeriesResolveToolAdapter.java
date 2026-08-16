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
                    "target=" + safeTarget, result,
                    java.util.List.of(DataPaths.configActiveRef()), java.util.List.of());
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "active configuration unavailable: " + exception.getClass().getSimpleName());
        }
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
