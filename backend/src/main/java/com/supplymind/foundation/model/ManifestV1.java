package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Adjacent manifest for a final business JSON/CSV file, never for a manifest or dirty marker. */
@JsonPropertyOrder({
        "schemaVersion", "fileName", "fileSha256", "byteLength", "rowCount", "minBusinessDate",
        "maxBusinessDate", "sourceRunIds", "generatedAt", "commitState"
})
public record ManifestV1(
        String schemaVersion,
        String fileName,
        String fileSha256,
        long byteLength,
        Long rowCount,
        String minBusinessDate,
        String maxBusinessDate,
        List<String> sourceRunIds,
        OffsetDateTime generatedAt,
        String commitState
) {
    public static final String COMMITTED = "COMMITTED";

    public ManifestV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.nonBlank(fileName, "fileName");
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new SchemaValidationException("fileName must be the target basename, not a dataRoot reference");
        }
        ModelRules.sha256(fileSha256, "fileSha256");
        ModelRules.nonNegative(byteLength, "byteLength");
        if (rowCount != null) {
            ModelRules.nonNegative(rowCount, "rowCount");
        }
        if ((minBusinessDate == null) != (maxBusinessDate == null)) {
            throw new SchemaValidationException("minBusinessDate and maxBusinessDate must be both null or both present");
        }
        if (minBusinessDate != null) {
            ModelRules.isoDateText(minBusinessDate, "minBusinessDate");
            ModelRules.isoDateText(maxBusinessDate, "maxBusinessDate");
            if (minBusinessDate.compareTo(maxBusinessDate) > 0) {
                throw new SchemaValidationException("minBusinessDate must not be after maxBusinessDate");
            }
        }
        sourceRunIds = canonicalRunIds(sourceRunIds);
        ModelRules.dateTime(generatedAt, "generatedAt");
        if (!COMMITTED.equals(commitState)) {
            throw new SchemaValidationException("commitState must be COMMITTED and is not a lifecycle state");
        }
    }

    private static List<String> canonicalRunIds(List<String> sourceRunIds) {
        ModelRules.required(sourceRunIds, "sourceRunIds");
        List<String> canonical = new ArrayList<>(sourceRunIds.size());
        for (String runId : sourceRunIds) {
            ModelRules.id(runId, "sourceRunIds item");
            if (!canonical.contains(runId)) {
                canonical.add(runId);
            }
        }
        canonical.sort(Comparator.naturalOrder());
        return List.copyOf(canonical);
    }
}
