package com.supplymind.agent.application;

import java.util.List;

/**
 * M1 untrusted model draft: the raw LLM output is never a formal fact. It is captured as a
 * draft, then {@link AgentResponseVerifier} decides whether any of its claims may become
 * formal claims/answer. Model claims must reference factIds/evidenceRefs that exist in the
 * current EvidencePack, and any business number in a claim must strictly correspond to the
 * referenced deterministic fact value.
 */
public record ModelDraftV1(
        String requestId,
        String rawText,
        List<ModelClaimV1> claims
) {
    public ModelDraftV1 {
        if (rawText == null) {
            throw new IllegalArgumentException("rawText is required (may be empty)");
        }
        claims = claims == null ? List.of() : List.copyOf(claims);
    }

    public static ModelDraftV1 untrusted(String requestId, String rawText) {
        return new ModelDraftV1(requestId, rawText == null ? "" : rawText, List.of());
    }
}
