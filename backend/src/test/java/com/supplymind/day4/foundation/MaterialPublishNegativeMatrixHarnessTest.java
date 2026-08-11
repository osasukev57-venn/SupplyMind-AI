package com.supplymind.day4.foundation;

import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-T02 expected negative publish matrix.  It is a reference matrix only until D4-T01/T02 drive
 * material lifecycle records through the real service.
 */
class MaterialPublishNegativeMatrixHarnessTest {

    @Test
    void pendingRejectedConflictDemoAndNonPublishedVerifiedAreNeverFormalBusinessInputs() {
        List<PublishCase> negatives = List.of(
                new PublishCase("RECEIVED_PENDING", Mode.FORMAL, ProcessingStage.RECEIVED, ValidationStatus.PENDING),
                new PublishCase("PARSED_PENDING", Mode.FORMAL, ProcessingStage.PARSED, ValidationStatus.PENDING),
                new PublishCase("VALIDATED_VERIFIED", Mode.FORMAL, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED),
                new PublishCase("VALIDATED_NOTICE", Mode.FORMAL, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED_WITH_NOTICE),
                new PublishCase("VALIDATED_REJECTED", Mode.FORMAL, ProcessingStage.VALIDATED, ValidationStatus.REJECTED),
                new PublishCase("VALIDATED_CONFLICT", Mode.FORMAL, ProcessingStage.VALIDATED, ValidationStatus.CONFLICT),
                new PublishCase("PUBLISHED_DEMO", Mode.DEMO, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED),
                new PublishCase("PUBLISHED_DEMO_NOTICE", Mode.DEMO, ProcessingStage.PUBLISHED,
                        ValidationStatus.VERIFIED_WITH_NOTICE));

        assertTrue(negatives.stream().noneMatch(MaterialPublishNegativeMatrixHarnessTest::isFormalEligible));
    }

    @Test
    void onlyFormalPublishedVerifiedClassIsTheFuturePositiveControl() {
        assertTrue(isFormalEligible(new PublishCase("FORMAL_VERIFIED", Mode.FORMAL,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED)));
        assertTrue(isFormalEligible(new PublishCase("FORMAL_NOTICE", Mode.FORMAL,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED_WITH_NOTICE)));
        assertFalse(isFormalEligible(new PublishCase("FORMAL_PENDING", Mode.FORMAL,
                ProcessingStage.PUBLISHED, ValidationStatus.PENDING)));
    }

    private static boolean isFormalEligible(PublishCase item) {
        return item.mode() == Mode.FORMAL && item.stage() == ProcessingStage.PUBLISHED && item.status().isPublishEligible();
    }

    private record PublishCase(String name, Mode mode, ProcessingStage stage, ValidationStatus status) {
    }
}
