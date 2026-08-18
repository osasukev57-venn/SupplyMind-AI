package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolArguments;
import com.supplymind.agent.tool.ToolInputException;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.warning.WarningRecordV1;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * D6-T01 warning.explain: read-only explanation of persisted warning evidence (D5-T05
 * warning/YYYY-MM files). Only manifest-valid warning records are returned; corrupt records are
 * never surfaced as evidence; absent warnings are NO_DATA. Demo rules stay TEST/DEMO (EXT-07/08
 * open).
 */
public final class WarningExplainToolAdapter {

    public static final String TOOL_NAME = "warning.explain";
    public static final String TOOL_VERSION = "1.0";

    private final DataRoot dataRoot;

    public WarningExplainToolAdapter(DataRoot dataRoot) {
        this.dataRoot = dataRoot;
    }

    @Tool(name = TOOL_NAME, description = "Explain persisted warning evidence for a series, optionally filtered by warning month (yyyy-MM).")
    public ToolResult warningExplain(
            @ToolParam(description = "monitored series itemId") String itemId,
            @ToolParam(description = "warning month filter, ISO yyyy-MM (optional)") String month,
            @ToolParam(description = "request id for traceability") String requestId
    ) {
        try {
            String safeItem = ToolArguments.identifier(itemId, "itemId", TOOL_NAME);
            YearMonth monthFilter = null;
            if (month != null && !month.isBlank()) {
                monthFilter = ToolArguments.yearMonth(month, "month", TOOL_NAME);
            }
            List<WarningRecordV1> warnings = readWarnings(safeItem, monthFilter);
            if (warnings.isEmpty()) {
                return ToolResult.noData(TOOL_NAME, TOOL_VERSION, requestId,
                        "itemId=" + safeItem + (monthFilter == null ? "" : " month=" + monthFilter),
                        "no persisted warning evidence");
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> evidenceRefs = new ArrayList<>();
            for (WarningRecordV1 warning : warnings) {
                String evidenceRef = DataPaths.warningRef(
                        YearMonth.parse(warning.periodStart().substring(0, 7)), warning.warningId());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("warningId", warning.warningId());
                entry.put("itemId", warning.itemId());
                entry.put("ruleId", warning.ruleId());
                entry.put("ruleDescription", warning.ruleDescription());
                entry.put("grain", warning.grain());
                entry.put("periodStart", warning.periodStart());
                entry.put("periodEnd", warning.periodEnd());
                entry.put("currentValue", warning.currentValue());
                entry.put("baselineValue", warning.baselineValue());
                entry.put("threshold", warning.threshold());
                entry.put("riskLevel", warning.riskLevel().name());
                entry.put("dataStatus", warning.dataStatus());
                entry.put("demoRule", warning.demoRule());
                // Row-scoped binding: one warning row may only support a projection through
                // its own immutable warning evidence.
                entry.put("evidenceRefs", List.of(evidenceRef));
                rows.add(entry);
                evidenceRefs.add(evidenceRef);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("warnings", rows);
            body.put("warningCount", rows.size());
            body.put("note", "warning rules are TEST/DEMO until EXT-07/08 are confirmed");
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + (monthFilter == null ? "" : " month=" + monthFilter),
                    body, List.copyOf(evidenceRefs), List.of());
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "warning read unavailable: " + exception.getClass().getSimpleName());
        }
    }

    private List<WarningRecordV1> readWarnings(String itemId, YearMonth monthFilter) {
        List<YearMonth> months = monthFilter == null ? listWarningMonths() : List.of(monthFilter);
        List<WarningRecordV1> warnings = new ArrayList<>();
        for (YearMonth month : months) {
            Path dir = dataRoot.resolveInternalRelative("warning/" + month);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                        .forEach(path -> {
                            String warningId = path.getFileName().toString()
                                    .substring(0, path.getFileName().toString().length() - ".json".length());
                            String ref = DataPaths.warningRef(month, warningId);
                            if (!ManifestVerifier.matches(dataRoot, ref, path,
                                    dataRoot.resolveDataRef(DataPaths.manifestRef(ref)))) {
                                return;
                            }
                            try {
                                WarningRecordV1 warning = JsonV1Codec.decodeFile(
                                        Files.readAllBytes(path), WarningRecordV1.class);
                                if (warning.itemId().equals(itemId)) {
                                    warnings.add(warning);
                                }
                            } catch (IOException | RuntimeException ignored) {
                                // corrupt warning files are not surfaced as evidence
                            }
                        });
            } catch (IOException exception) {
                throw new com.supplymind.foundation.storage.StorageException(
                        "Unable to list warning evidence", exception);
            }
        }
        warnings.sort(Comparator.comparing(WarningRecordV1::periodStart)
                .thenComparing(WarningRecordV1::warningId));
        return List.copyOf(warnings);
    }

    private List<YearMonth> listWarningMonths() {
        Path dir = dataRoot.resolveInternalRelative("warning");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<YearMonth> months = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(path -> {
                try {
                    months.add(YearMonth.parse(path.getFileName().toString()));
                } catch (java.time.format.DateTimeParseException ignored) {
                    // non-month directories are ignored
                }
            });
        } catch (IOException exception) {
            throw new com.supplymind.foundation.storage.StorageException(
                    "Unable to list warning months", exception);
        }
        months.sort(Comparator.naturalOrder());
        return months;
    }
}
