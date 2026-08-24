package com.supplymind.localimport;

import com.supplymind.manual.ManualMaterialProcessingService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, bounded operator action that advances previously accepted LocalImport rows through
 * the existing validation -> publish -> daily -> aggregate chain. Upload remains raw-first and
 * PENDING; this service never invents values and isolates a failed row from the remaining batch.
 */
public final class LocalImportBatchProcessingService {

    static final int MAX_BATCH_SIZE = 500;

    public record RowResult(
            String runId,
            String itemId,
            String businessDate,
            String status,
            String validationStatus,
            String validationVersion,
            String publishRef,
            String dailyRef,
            List<String> aggregateRefs,
            String message
    ) {
        public RowResult {
            aggregateRefs = aggregateRefs == null ? List.of() : List.copyOf(aggregateRefs);
        }
    }

    public record BatchResult(String status, String message, List<RowResult> rows) {
        public BatchResult {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    private final ManualMaterialProcessingService processing;

    public LocalImportBatchProcessingService(ManualMaterialProcessingService processing) {
        this.processing = Objects.requireNonNull(processing, "processing");
    }

    public BatchResult process(List<String> requestedRunIds) {
        if (requestedRunIds == null || requestedRunIds.isEmpty()) {
            throw new IllegalArgumentException("runIds are required");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String runId : requestedRunIds) {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("every runId must be non-blank");
            }
            distinct.add(runId.trim());
        }
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("runIds must contain at most " + MAX_BATCH_SIZE + " rows");
        }

        List<RowResult> rows = new ArrayList<>();
        int published = 0;
        for (String runId : distinct) {
            try {
                ManualMaterialProcessingService.Result result = processing.processLocalImport(runId);
                rows.add(from(result));
                if ("PUBLISHED".equals(result.status())) {
                    published++;
                }
            } catch (RuntimeException exception) {
                rows.add(new RowResult(
                        runId, null, null, "REJECTED", null, null,
                        null, null, List.of(), "row failed closed during validation or publishing"));
            }
        }
        String status = published == rows.size()
                ? "PUBLISHED"
                : published == 0 ? "REJECTED" : "PARTIAL_SUCCESS";
        return new BatchResult(
                status,
                "processed " + rows.size() + " distinct rows: " + published
                        + " published, " + (rows.size() - published) + " rejected",
                rows);
    }

    private static RowResult from(ManualMaterialProcessingService.Result result) {
        return new RowResult(
                result.runId(), result.itemId(), result.businessDate(), result.status(),
                result.validationStatus(), result.validationVersion(), result.publishRef(),
                result.dailyRef(), result.aggregateRefs(), result.message());
    }
}