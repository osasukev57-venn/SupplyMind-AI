package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Append-only, single-run lifecycle history; this is the authority for lifecycle state. */
@JsonPropertyOrder({"schemaVersion", "recordId", "runId", "rawRef", "currentRecordVersion", "records"})
public record LifecycleTimelineV1(
        String schemaVersion,
        String recordId,
        String runId,
        String rawRef,
        int currentRecordVersion,
        List<LifecycleSnapshotV1> records
) {
    public LifecycleTimelineV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.id(recordId, "recordId");
        ModelRules.id(runId, "runId");
        ModelRules.relativeDataRef(rawRef, "rawRef");
        records = ModelRules.immutableList(records, "records");
        if (records.isEmpty()) {
            throw new SchemaValidationException("records must contain the initial RECEIVED+PENDING snapshot");
        }
        ModelRules.positive(currentRecordVersion, "currentRecordVersion");
        if (currentRecordVersion != records.size()
                || records.get(records.size() - 1).recordVersion() != currentRecordVersion) {
            throw new SchemaValidationException("currentRecordVersion must equal records.size and last recordVersion");
        }
        validateRecords(runId, records);
    }

    public static LifecycleTimelineV1 initial(
            String recordId,
            String runId,
            String rawRef,
            OffsetDateTime receivedAt
    ) {
        LifecycleSnapshotV1 initial = new LifecycleSnapshotV1(
                1,
                ProcessingStage.RECEIVED,
                ValidationStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                null,
                receivedAt
        );
        return new LifecycleTimelineV1(SchemaV1.VERSION, recordId, runId, rawRef, 1, List.of(initial));
    }

    /**
     * Appends a legal next immutable snapshot. Replaying an identical current snapshot is a
     * no-op, while a same-status mutation or any skipped/reversed version fails closed.
     */
    public LifecycleTimelineV1 append(LifecycleSnapshotV1 next) {
        ModelRules.required(next, "next snapshot");
        LifecycleSnapshotV1 last = records.get(records.size() - 1);
        if (next.recordVersion() == currentRecordVersion && next.equals(last)) {
            return this;
        }
        if (next.recordVersion() != currentRecordVersion + 1) {
            throw new SchemaValidationException("Lifecycle snapshots must append exactly one recordVersion");
        }
        List<LifecycleSnapshotV1> updated = new ArrayList<>(records);
        updated.add(next);
        return new LifecycleTimelineV1(
                schemaVersion,
                recordId,
                runId,
                rawRef,
                next.recordVersion(),
                updated
        );
    }

    public LifecycleSnapshotV1 current() {
        return records.get(records.size() - 1);
    }

    @JsonIgnore
    public boolean isPublishedForDailyInput() {
        return currentRecordVersion == 4
                && current().processingStage() == ProcessingStage.PUBLISHED
                && current().validationStatus().isPublishEligible();
    }

    private static void validateRecords(String runId, List<LifecycleSnapshotV1> snapshots) {
        LifecycleSnapshotV1 previous = null;
        CandidateV1 immutableCandidate = null;
        for (int index = 0; index < snapshots.size(); index++) {
            LifecycleSnapshotV1 current = snapshots.get(index);
            if (current.recordVersion() != index + 1) {
                throw new SchemaValidationException("records must be contiguous from recordVersion=1");
            }
            if (index == 0 && (current.processingStage() != ProcessingStage.RECEIVED
                    || current.validationStatus() != ValidationStatus.PENDING)) {
                throw new SchemaValidationException("recordVersion=1 must be RECEIVED+PENDING");
            }
            if (previous != null) {
                if (current.updatedAt().isBefore(previous.updatedAt())) {
                    throw new SchemaValidationException("snapshot updatedAt values must be non-decreasing");
                }
                if (!isLegalTransition(previous, current)) {
                    throw new SchemaValidationException("Illegal lifecycle transition "
                            + previous.processingStage() + "+" + previous.validationStatus() + " -> "
                            + current.processingStage() + "+" + current.validationStatus());
                }
                if (current.processingStage() == ProcessingStage.PUBLISHED) {
                    validatePublicationInvariant(runId, previous, current);
                }
            }
            if (current.candidate() != null) {
                if (immutableCandidate == null) {
                    immutableCandidate = current.candidate();
                } else if (!immutableCandidate.equals(current.candidate())) {
                    throw new SchemaValidationException("CandidateV1 must remain immutable within a runId");
                }
            }
            previous = current;
        }
    }

    private static boolean isLegalTransition(LifecycleSnapshotV1 previous, LifecycleSnapshotV1 current) {
        ProcessingStage fromStage = previous.processingStage();
        ValidationStatus fromStatus = previous.validationStatus();
        ProcessingStage toStage = current.processingStage();
        ValidationStatus toStatus = current.validationStatus();
        return (fromStage == ProcessingStage.RECEIVED && fromStatus == ValidationStatus.PENDING
                && ((toStage == ProcessingStage.PARSED && toStatus == ValidationStatus.PENDING)
                || (toStage == ProcessingStage.RECEIVED && toStatus == ValidationStatus.REJECTED)))
                || (fromStage == ProcessingStage.PARSED && fromStatus == ValidationStatus.PENDING
                && toStage == ProcessingStage.VALIDATED
                && (toStatus == ValidationStatus.VERIFIED || toStatus == ValidationStatus.VERIFIED_WITH_NOTICE
                || toStatus == ValidationStatus.REJECTED || toStatus == ValidationStatus.CONFLICT))
                || (fromStage == ProcessingStage.VALIDATED && fromStatus == ValidationStatus.VERIFIED
                && toStage == ProcessingStage.PUBLISHED && toStatus == ValidationStatus.VERIFIED)
                || (fromStage == ProcessingStage.VALIDATED && fromStatus == ValidationStatus.VERIFIED_WITH_NOTICE
                && toStage == ProcessingStage.PUBLISHED && toStatus == ValidationStatus.VERIFIED_WITH_NOTICE);
    }

    private static void validatePublicationInvariant(
            String runId,
            LifecycleSnapshotV1 previous,
            LifecycleSnapshotV1 published
    ) {
        String expectedPublishRef = "staging/" + runId + ".json#recordVersion=" + published.recordVersion();
        if (!expectedPublishRef.equals(published.publishRef())) {
            throw new SchemaValidationException("publishRef must target this published staging recordVersion");
        }
        if (!previous.candidate().equals(published.candidate())
                || !previous.validationVersion().equals(published.validationVersion())
                || !previous.validatedAt().equals(published.validatedAt())
                || (previous.reasonCode() != null && !previous.reasonCode().equals(published.reasonCode()))) {
            throw new SchemaValidationException("PUBLISHED must preserve CandidateV1 and validation audit fields");
        }
    }
}
