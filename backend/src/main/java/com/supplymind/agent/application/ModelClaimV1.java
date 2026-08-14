package com.supplymind.agent.application;

import java.util.List;

/**
 * M1 one model-generated claim. To become a formal claim it must reference existing
 * factIds/evidenceRefs and any business number must match the referenced deterministic fact.
 */
public record ModelClaimV1(
        String claimId,
        String text,
        List<String> factIds,
        List<String> evidenceRefs
) {
    public ModelClaimV1 {
        if (claimId == null || claimId.isBlank()) {
            throw new IllegalArgumentException("claimId is required");
        }
        factIds = factIds == null ? List.of() : List.copyOf(factIds);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
