package com.supplymind.validation;

import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;

import java.time.OffsetDateTime;

/** Terminal view of one lifecycle after standardization/validation; no mutable state is copied. */
public record ValidationOutcome(
        String runId,
        String rawRef,
        int recordVersion,
        ProcessingStage processingStage,
        ValidationStatus validationStatus,
        CandidateV1 candidate,
        String reasonCode,
        String validationVersion,
        OffsetDateTime validatedAt
) {
    public static ValidationOutcome of(LifecycleTimelineV1 timeline) {
        var snapshot = timeline.current();
        return new ValidationOutcome(
                timeline.runId(),
                timeline.rawRef(),
                snapshot.recordVersion(),
                snapshot.processingStage(),
                snapshot.validationStatus(),
                snapshot.candidate(),
                snapshot.reasonCode(),
                snapshot.validationVersion(),
                snapshot.validatedAt());
    }
}
