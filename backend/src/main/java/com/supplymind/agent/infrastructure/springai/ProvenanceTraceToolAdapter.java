package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.history.HistoryQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D6-T01 provenance.trace: evidence chain behind published daily rows - which raw receipts
 * (with refs and manifests) feed each business date, plus validation status recorded on the
 * processed row. Only real, manifest-verified files are referenced; missing or invalid raws
 * are reported honestly as unavailable.
 */
public final class ProvenanceTraceToolAdapter {

    public static final String TOOL_NAME = "provenance.trace";
    public static final String TOOL_VERSION = "1.0";

    private final DataRoot dataRoot;
    private final HistoryQueryService history;

    public ProvenanceTraceToolAdapter(DataRoot dataRoot, HistoryQueryService history) {
        this.dataRoot = dataRoot;
        this.history = history;
    }

    @Tool(name = TOOL_NAME, description = "Trace the provenance/evidence chain behind published daily rows for a series over a date range.")
    public ToolResult provenanceTrace(
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
            for (com.supplymind.foundation.model.DailyRecordV1 row : result.rows()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("businessDate", row.businessDate());
                entry.put("validationStatus", row.validationStatus().wireValue());
                entry.put("validationVersion", row.validationVersion());
                entry.put("actualSourceName", row.actualSourceName());
                entry.put("providerType", row.providerType().wireValue());
                entry.put("accessMethod", row.accessMethod().wireValue());
                List<String> rawRefs = new ArrayList<>();
                for (var input : row.inputRefs()) {
                    rawRefs.add(input.rawRef());
                    if (!evidenceRefs.contains(input.rawRef())) {
                        evidenceRefs.add(input.rawRef());
                    }
                }
                entry.put("rawRefs", rawRefs);
                rows.add(entry);
            }
            if (rows.isEmpty()) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "itemId=" + safeItem + " range=" + from + ".." + to,
                        "no published rows to trace");
            }
            List<String> unavailable = new ArrayList<>();
            for (String rawRef : evidenceRefs) {
                if (!rawExists(rawRef)) {
                    unavailable.add(rawRef);
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rows", rows);
            body.put("rowCount", rows.size());
            body.put("unavailableRawRefs", unavailable);
            body.put("conflictKeys", result.conflictKeys());
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + " range=" + from + ".." + to,
                    body, List.copyOf(evidenceRefs), List.of());
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "provenance read unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private boolean rawExists(String rawRef) {
        try {
            DataPaths.requireLegalDataRef(rawRef);
        } catch (RuntimeException exception) {
            return false;
        }
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        return Files.isRegularFile(rawPath) && Files.isRegularFile(manifestPath)
                && ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath);
    }
}
