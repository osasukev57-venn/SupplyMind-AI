package com.supplymind.agent.application;

import java.util.List;

/**
 * M1/M3 one model-generated claim. To become a formal claim it must reference existing
 * factIds/evidenceRefs and EVERY business number it states must be supported by the facts it
 * references (an unrelated reference never validates the claim). M3: sourceNames[] and
 * businessDates[] are explicit Java-verifiable fields - each value must be supported by the
 * referenced facts, never inferred from free text.
 */
public record ModelClaimV1(
        String claimId,
        String text,
        List<String> factIds,
        List<String> evidenceRefs,
        List<String> sourceNames,
        List<String> businessDates
) {
    public ModelClaimV1 {
        if (claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("claimId is required");
        }
        factIds = factIds == null ? List.of() : List.copyOf(factIds);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        sourceNames = sourceNames == null ? List.of() : List.copyOf(sourceNames);
        businessDates = businessDates == null ? List.of() : List.copyOf(businessDates);
    }

    /** Backwards-compatible constructor without the explicit M3 source/date fields. */
    public ModelClaimV1(
            String claimId, String text, List<String> factIds, List<String> evidenceRefs
    ) {
        this(claimId, text, factIds, evidenceRefs, List.of(), List.of());
    }
}
