package com.supplymind.agent.application;

import java.util.List;

/**
 * Untrusted model draft. The production cloud contract contains only polished text and the
 * StandardFactPack ids it used; Java validates those ids and the answer before attaching formal
 * evidence. The claims field and compatibility constructor are retained only for frozen legacy
 * verifier tests and are not accepted by the production response parser.
 */
public record ModelDraftV1(
        String requestId,
        String rawText,
        List<String> usedFactIds,
        List<ModelClaimV1> claims
) {
    public ModelDraftV1 {
        if (rawText == null) {
            throw new IllegalArgumentException("rawText is required (may be empty)");
        }
        usedFactIds = usedFactIds == null ? List.of() : List.copyOf(usedFactIds);
        claims = claims == null ? List.of() : List.copyOf(claims);
    }

    /** Compatibility constructor for historical structured-claim verifier tests. */
    public ModelDraftV1(String requestId, String rawText, List<ModelClaimV1> claims) {
        this(requestId, rawText, List.of(), claims);
    }

    public static ModelDraftV1 untrusted(String requestId, String rawText) {
        return new ModelDraftV1(requestId, rawText == null ? "" : rawText, List.of(), List.of());
    }
}
