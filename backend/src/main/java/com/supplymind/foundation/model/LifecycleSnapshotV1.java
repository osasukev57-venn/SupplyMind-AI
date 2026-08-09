package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.OffsetDateTime;

/** One immutable snapshot inside a LifecycleTimelineV1. */
@JsonPropertyOrder({
        "recordVersion", "processingStage", "validationStatus", "candidate", "reasonCode", "validationVersion",
        "validatedAt", "publishedAt", "publishRef", "updatedAt"
})
public record LifecycleSnapshotV1(
        int recordVersion,
        ProcessingStage processingStage,
        ValidationStatus validationStatus,
        CandidateV1 candidate,
        String reasonCode,
        String validationVersion,
        OffsetDateTime validatedAt,
        OffsetDateTime publishedAt,
        String publishRef,
        OffsetDateTime updatedAt
) {
    public LifecycleSnapshotV1 {
        ModelRules.positive(recordVersion, "recordVersion");
        ModelRules.required(processingStage, "processingStage");
        ModelRules.required(validationStatus, "validationStatus");
        ModelRules.dateTime(updatedAt, "updatedAt");
        validateLegalCombination(processingStage, validationStatus);
        validateCandidate(processingStage, validationStatus, candidate);
        validateValidationFields(processingStage, validationVersion, validatedAt);
        validateReasonCode(processingStage, validationStatus, reasonCode);
        validatePublicationFields(processingStage, publishedAt, publishRef);
    }

    @JsonIgnore
    public boolean isTerminal() {
        return (processingStage == ProcessingStage.RECEIVED && validationStatus == ValidationStatus.REJECTED)
                || (processingStage == ProcessingStage.VALIDATED
                && (validationStatus == ValidationStatus.REJECTED || validationStatus == ValidationStatus.CONFLICT))
                || processingStage == ProcessingStage.PUBLISHED;
    }

    private static void validateLegalCombination(ProcessingStage stage, ValidationStatus status) {
        boolean allowed = (stage == ProcessingStage.RECEIVED
                && (status == ValidationStatus.PENDING || status == ValidationStatus.REJECTED))
                || (stage == ProcessingStage.PARSED && status == ValidationStatus.PENDING)
                || (stage == ProcessingStage.VALIDATED
                && (status == ValidationStatus.VERIFIED || status == ValidationStatus.VERIFIED_WITH_NOTICE
                || status == ValidationStatus.REJECTED || status == ValidationStatus.CONFLICT))
                || (stage == ProcessingStage.PUBLISHED
                && (status == ValidationStatus.VERIFIED || status == ValidationStatus.VERIFIED_WITH_NOTICE));
        if (!allowed) {
            throw new SchemaValidationException("Illegal ProcessingStage/ValidationStatus combination: " + stage + "/" + status);
        }
    }

    private static void validateCandidate(ProcessingStage stage, ValidationStatus status, CandidateV1 candidate) {
        boolean candidateMustBeNull = stage == ProcessingStage.RECEIVED
                && (status == ValidationStatus.PENDING || status == ValidationStatus.REJECTED);
        if (candidateMustBeNull && candidate != null) {
            throw new SchemaValidationException("RECEIVED snapshots must have candidate=null");
        }
        if (!candidateMustBeNull && candidate == null) {
            throw new SchemaValidationException("PARSED, VALIDATED and PUBLISHED snapshots require CandidateV1");
        }
    }

    private static void validateValidationFields(
            ProcessingStage stage,
            String validationVersion,
            OffsetDateTime validatedAt
    ) {
        boolean validationRequired = stage == ProcessingStage.VALIDATED || stage == ProcessingStage.PUBLISHED;
        if (validationRequired) {
            ModelRules.nonBlank(validationVersion, "validationVersion");
            ModelRules.dateTime(validatedAt, "validatedAt");
        } else if (validationVersion != null || validatedAt != null) {
            throw new SchemaValidationException("RECEIVED/PARSED snapshots must have null validationVersion and validatedAt");
        }
    }

    private static void validateReasonCode(ProcessingStage stage, ValidationStatus status, String reasonCode) {
        boolean reasonRequired = (stage == ProcessingStage.RECEIVED && status == ValidationStatus.REJECTED)
                || (stage == ProcessingStage.VALIDATED
                && (status == ValidationStatus.REJECTED || status == ValidationStatus.CONFLICT
                || status == ValidationStatus.VERIFIED_WITH_NOTICE))
                || (stage == ProcessingStage.PUBLISHED && status == ValidationStatus.VERIFIED_WITH_NOTICE);
        if (reasonRequired) {
            ModelRules.nonBlank(reasonCode, "reasonCode");
        } else if (reasonCode != null) {
            throw new SchemaValidationException("reasonCode is only permitted for frozen notice/rejection/conflict combinations");
        }
    }

    private static void validatePublicationFields(
            ProcessingStage stage,
            OffsetDateTime publishedAt,
            String publishRef
    ) {
        if (stage == ProcessingStage.PUBLISHED) {
            ModelRules.dateTime(publishedAt, "publishedAt");
            ModelRules.nonBlank(publishRef, "publishRef");
        } else if (publishedAt != null || publishRef != null) {
            throw new SchemaValidationException("publishedAt and publishRef must be null before PUBLISHED");
        }
    }
}
