package com.supplymind.backfill;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaValidationException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * D5-T04 backfill job state (frozen D5-T04 status vocabulary). One immutable-when-written
 * runtime job document persisted atomically under runtime/jobs/active; checkpoints advance
 * monotonically so a restart resumes from the persisted state. Duplicate starts reuse the
 * same jobId, never re-producing history.
 */
@JsonPropertyOrder({
        "schemaVersion", "jobId", "itemId", "fromDate", "toDate", "status",
        "completedPeriods", "currentCheckpoint", "failureReasons", "configVersion",
        "createdAt", "updatedAt"
})
public record BackfillJobStateV1(
        String schemaVersion,
        String jobId,
        String itemId,
        String fromDate,
        String toDate,
        JobStatus status,
        List<String> completedPeriods,
        String currentCheckpoint,
        List<String> failureReasons,
        int configVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public enum JobStatus {
        WAITING,
        AWAITING_MANUAL_INPUT,
        RUNNING,
        PARTIAL_SUCCESS,
        SUCCEEDED,
        FAILED
    }

    public BackfillJobStateV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(jobId, "jobId");
        ModelRules.id(itemId, "itemId");
        ModelRules.isoDateText(fromDate, "fromDate");
        ModelRules.isoDateText(toDate, "toDate");
        if (fromDate.compareTo(toDate) > 0) {
            throw new SchemaValidationException("backfill fromDate must not be after toDate");
        }
        if (status == null) {
            throw new SchemaValidationException("backfill job status is required");
        }
        completedPeriods = ModelRules.immutableList(completedPeriods, "completedPeriods");
        for (String period : completedPeriods) {
            if (!period.matches("\\d{4}-\\d{2}")) {
                throw new SchemaValidationException("completedPeriods must be YYYY-MM: " + period);
            }
        }
        if (currentCheckpoint != null && !currentCheckpoint.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new SchemaValidationException("currentCheckpoint must be a YYYY-MM-DD business date: "
                    + currentCheckpoint);
        }
        failureReasons = ModelRules.immutableList(failureReasons, "failureReasons");
        ModelRules.positive(configVersion, "configVersion");
        ModelRules.dateTime(createdAt, "createdAt");
        ModelRules.dateTime(updatedAt, "updatedAt");
    }

    public BackfillJobStateV1 withStatus(JobStatus next, List<String> completed, String checkpoint,
                                         List<String> failures, OffsetDateTime at) {
        return new BackfillJobStateV1(
                schemaVersion, jobId, itemId, fromDate, toDate, next,
                completed == null ? completedPeriods : new ArrayList<>(completed),
                checkpoint == null ? currentCheckpoint : checkpoint,
                failures == null ? failureReasons : new ArrayList<>(failures),
                configVersion, createdAt, at);
    }
}
