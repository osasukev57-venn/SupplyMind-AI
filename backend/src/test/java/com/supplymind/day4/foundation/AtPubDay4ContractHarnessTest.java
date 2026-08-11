package com.supplymind.day4.foundation;

import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT-PUB-001..003 reusable acceptance matrix.  This is an independent expected-result harness;
 * D4-T01/D4-T02 must later drive its cases through the real validation/publish/query path.
 */
class AtPubDay4ContractHarnessTest {

    @Test
    void onlyPublishedVerifiedAndPublishedVerifiedWithNoticeAreFormalBusinessEligible() {
        List<GateCase> cases = List.of(
                new GateCase("PUBLISHED_VERIFIED", ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, false, true),
                new GateCase("PUBLISHED_VERIFIED_WITH_NOTICE", ProcessingStage.PUBLISHED,
                        ValidationStatus.VERIFIED_WITH_NOTICE, false, true),
                new GateCase("RECEIVED_PENDING", ProcessingStage.RECEIVED, ValidationStatus.PENDING, false, false),
                new GateCase("PARSED_PENDING", ProcessingStage.PARSED, ValidationStatus.PENDING, false, false),
                new GateCase("VALIDATED_VERIFIED", ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, false, false),
                new GateCase("VALIDATED_REJECTED", ProcessingStage.VALIDATED, ValidationStatus.REJECTED, false, false),
                new GateCase("VALIDATED_CONFLICT", ProcessingStage.VALIDATED, ValidationStatus.CONFLICT, false, false),
                new GateCase("PUBLISHED_DEMO", ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, true, false));

        for (GateCase gateCase : cases) {
            assertEquals(gateCase.expectedFormalVisibility(), expectedFormalVisibility(gateCase), gateCase.name());
        }
    }

    @Test
    void gd03NegativeCasesRemainOutOfFormalVisibilityUntilD4ProductionImplementsTheirOutcomes() {
        List<String> gd03 = List.of(
                "REQUIRED_VALUE_EMPTY", "NEGATIVE_PRICE", "CURRENCY_MISMATCH", "UNIT_MISMATCH",
                "BUSINESS_DATE_UNPARSABLE", "SAME_BUSINESS_KEY_VALUE_CONFLICT",
                "RAW_SHA256_TAMPERED_AFTER_FIRST_VALIDATION", "OUTSIDE_CONFIGURED_CHANGE_RANGE");

        assertEquals(8, gd03.size());
        assertTrue(gd03.stream().noneMatch(name -> expectedFormalVisibility(
                new GateCase(name, ProcessingStage.PUBLISHED, ValidationStatus.REJECTED, false, false))));
        assertFalse(expectedFormalVisibility(new GateCase("SYNTHETIC", ProcessingStage.PUBLISHED,
                ValidationStatus.VERIFIED, true, false)));
    }

    @Test
    void reusableNegativeCaseMatrixCoversMissingDuplicateSourceSpecContextStatusPendingAndDemo() {
        List<NegativeCase> cases = List.of(
                new NegativeCase("MISSING", "REQUIRED_VALUE_EMPTY", false),
                new NegativeCase("DUPLICATE", "SAME_BUSINESS_KEY_IDENTICAL", false),
                new NegativeCase("SOURCE_MISMATCH", "FREE_PUBLIC_MISLABELLED_AS_SMM", false),
                new NegativeCase("SPEC_MISMATCH", "UNIT_MISMATCH", false),
                new NegativeCase("CONTEXT_MISMATCH", "CALCULATION_CONTEXT_MISMATCH", false),
                new NegativeCase("INVALID_STATUS", "VALIDATED_REJECTED", false),
                new NegativeCase("PENDING", "PARSED_PENDING", false),
                new NegativeCase("DEMO", "SYNTHETIC_DEMO", false));

        assertEquals(List.of("MISSING", "DUPLICATE", "SOURCE_MISMATCH", "SPEC_MISMATCH",
                        "CONTEXT_MISMATCH", "INVALID_STATUS", "PENDING", "DEMO"),
                cases.stream().map(NegativeCase::category).toList());
        assertTrue(cases.stream().noneMatch(NegativeCase::eligibleForFormalCalculation));
    }

    private static boolean expectedFormalVisibility(GateCase gateCase) {
        return !gateCase.demo()
                && gateCase.stage() == ProcessingStage.PUBLISHED
                && (gateCase.status() == ValidationStatus.VERIFIED
                || gateCase.status() == ValidationStatus.VERIFIED_WITH_NOTICE);
    }

    private record GateCase(
            String name,
            ProcessingStage stage,
            ValidationStatus status,
            boolean demo,
            boolean expectedFormalVisibility
    ) {
    }

    private record NegativeCase(String category, String fixtureCase, boolean eligibleForFormalCalculation) {
    }
}
