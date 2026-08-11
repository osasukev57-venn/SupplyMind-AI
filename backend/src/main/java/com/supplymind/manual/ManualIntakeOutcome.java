package com.supplymind.manual;

import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;

/** D3-T04 intake result: the final manual lifecycle position of one submission. */
public record ManualIntakeOutcome(
        String schemaVersion,
        String runId,
        String rawRef,
        String timelineRef,
        ProcessingStage processingStage,
        ValidationStatus validationStatus,
        String reasonCode,
        IntakeMode mode,
        String normalizedValue
) {
    public enum IntakeMode {
        NEW,
        IDEMPOTENT_REUSE,
        REJECTED_MECHANICAL
    }

    public boolean isPending() {
        return validationStatus == ValidationStatus.PENDING;
    }
}
