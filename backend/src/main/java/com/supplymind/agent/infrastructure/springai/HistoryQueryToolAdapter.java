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

    /** M2 R4: each row's own fingerprint from ITS providerType + actualSourceName + accessMethod. */
    private static String fingerprintOf(DailyRecordV1 row) {
        return com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                        row.providerType(), row.actualSourceName(), row.accessMethod()));
    }

    /** M2 R4: configVersions is an ordered list - dedupe, numeric ascending, never a scalar. */
    private static List<String> normalizedConfigVersions(DailyRecordV1 row) {
        if (row.configVersions() == null) {
            return List.of();
        }
        return row.configVersions().stream().distinct().sorted()
                .map(String::valueOf).toList();
    }

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
                entry.put("calculationVersion", row.calculationVersion());
                entry.put("calendarVersion", row.calendarVersion());
                entry.put("complete", row.complete());
                // M2 R4: every row keeps the evidence refs IT really comes from (never the whole
                // ToolResult set) and its OWN source fingerprint.
                List<String> rowRefs = new ArrayList<>();
                for (var input : row.inputRefs()) {
                    if (!rowRefs.contains(input.rawRef())) {
                        rowRefs.add(input.rawRef());
                    }
                    if (!evidenceRefs.contains(input.rawRef())) {
                        evidenceRefs.add(input.rawRef());
                    }
                }
                entry.put("evidenceRefs", List.copyOf(rowRefs));
                entry.put("sourceFingerprint", fingerprintOf(row));
                rows.add(entry);
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
            DailyRecordV1 first = result.rows().get(0);
            // M2 R4: per-evidenceRef lineage - each ref is filled ONLY with the lineage of the rows
            // that actually reference it; a ref referenced by heterogeneous rows is PERMANENTLY
            // tombstoned (A->B->A order never re-adds it) - the file-level
            // AMBIGUOUS_FILE_LINEAGE then governs. Every row computes its OWN fingerprint.
            java.util.Map<String, ToolResult.Lineage> perRef = new java.util.LinkedHashMap<>();
            java.util.Set<String> tombstonedRefs = new java.util.HashSet<>();
            for (DailyRecordV1 row : result.rows()) {
                ToolResult.Lineage rowLineage = new ToolResult.Lineage(
                        row.calculationVersion(), row.calendarVersion(),
                        normalizedConfigVersions(row),
                        row.actualSourceName(), fingerprintOf(row), row.validationVersion());
                for (var input : row.inputRefs()) {
                    if (tombstonedRefs.contains(input.rawRef())) {
                        continue; // heterogeneous ref stays tombstoned forever
                    }
                    ToolResult.Lineage existing = perRef.get(input.rawRef());
                    if (existing == null) {
                        perRef.put(input.rawRef(), rowLineage);
                    } else if (!existing.equals(rowLineage)) {
                        perRef.remove(input.rawRef());
                        tombstonedRefs.add(input.rawRef()); // never re-added
                    }
                }
            }
            return ToolResult.success(TOOL_NAME, TOOL_VERSION, requestId,
                    "itemId=" + safeItem + " range=" + from + ".." + to, body,
                    List.copyOf(evidenceRefs), List.of(),
                    new ToolResult.Lineage(
                            first.calculationVersion(), first.calendarVersion(),
                            normalizedConfigVersions(first),
                            first.actualSourceName(), fingerprintOf(first),
                            first.validationVersion()),
                    perRef);
        } catch (ToolInputException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.rejected(TOOL_NAME, TOOL_VERSION, requestId,
                    "history read unavailable: " + exception.getClass().getSimpleName());
        }
    }
}
