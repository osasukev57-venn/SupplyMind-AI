package com.supplymind.publish;

import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;

/** Result of one D2-T02 publish-gate run over one lifecycle runId. */
public record PublishOutcome(
        String runId,
        PublishAction action,
        int recordVersion,
        ProcessingStage processingStage,
        ValidationStatus validationStatus,
        String publishRef,
        String quarantineRef,
        String reasonCode
) {
    public enum PublishAction {
        PUBLISHED,
        QUARANTINED,
        ALREADY_PUBLISHED,
        NOT_READY
    }
}
