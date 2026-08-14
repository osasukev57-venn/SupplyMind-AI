package com.supplymind.agent.application;

import com.supplymind.agent.evidence.EvidencePackV1;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * M1 AgentResponseVerifier: the LLM output is an UNTRUSTED MODEL DRAFT. Verification rules:
 *
 * 1. Every claim must reference factIds/evidenceRefs that EXIST in the current EvidencePack
 *    (unknown fact/evidence ref -> claim rejected, model result unusable).
 * 2. Any business number appearing in the model answer must strictly correspond to a
 *    referenced deterministic fact value; a fabricated number (e.g. 999999.999 with no such
 *    fact) makes the whole model result unusable -> caller must fall back to Java template.
 * 3. Secret injection (configured apiKey / credential material) inside the draft -> REJECTED.
 *
 * Verification outcome is binary: either the model draft may become the formal answer/claims
 * (ACCEPTED) or it must not (REJECTED with a stable reason) - partial acceptance is forbidden.
 */
public final class AgentResponseVerifier {

    private final Set<String> configuredSecrets;

    public AgentResponseVerifier(List<String> configuredSecrets) {
        this.configuredSecrets = configuredSecrets == null
                ? Set.of() : Set.copyOf(configuredSecrets);
    }

    public Verification verify(ModelDraftV1 draft, EvidencePackV1 evidencePack) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(evidencePack, "evidencePack");

        String secretHit = findSecret(draft.rawText());
        if (secretHit != null) {
            return Verification.rejected("SECRET_INJECTION");
        }
        Set<String> knownFactIds = new LinkedHashSet<>();
        Set<String> knownEvidenceRefs = new LinkedHashSet<>();
        for (EvidencePackV1.Fact fact : evidencePack.facts()) {
            knownFactIds.add(fact.factId());
            knownEvidenceRefs.addAll(fact.evidenceRefs());
        }
        for (EvidencePackV1.EvidenceRefEntry entry : evidencePack.evidenceRefs()) {
            knownEvidenceRefs.add(entry.ref());
        }

        for (ModelClaimV1 claim : draft.claims()) {
            for (String factId : claim.factIds()) {
                if (!knownFactIds.contains(factId)) {
                    return Verification.rejected("UNKNOWN_FACT_REF:" + factId);
                }
            }
            for (String ref : claim.evidenceRefs()) {
                if (!knownEvidenceRefs.contains(ref)) {
                    return Verification.rejected("UNKNOWN_EVIDENCE_REF:" + ref);
                }
            }
        }
        String fabricated = findFabricatedNumber(draft.rawText(), evidencePack);
        if (fabricated != null) {
            return Verification.rejected("FABRICATED_NUMBER:" + fabricated);
        }
        return Verification.accepted();
    }

    /** Any number token in the draft that is not the value of a referenced fact is fabrication. */
    private static String findFabricatedNumber(String rawText, EvidencePackV1 evidencePack) {
        List<String> knownValues = new ArrayList<>();
        for (EvidencePackV1.Fact fact : evidencePack.facts()) {
            if (fact.value() != null && !fact.value().isBlank()) {
                knownValues.add(fact.value());
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9]+\\.[0-9]{2,}")
                .matcher(rawText);
        while (matcher.find()) {
            String token = matcher.group();
            if (knownValues.stream().noneMatch(token::equals)) {
                return token;
            }
        }
        return null;
    }

    private String findSecret(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        for (String secret : configuredSecrets) {
            if (secret != null && secret.length() >= 6 && rawText.contains(secret)) {
                return "configured_secret";
            }
        }
        if (rawText.contains("Bearer ") && rawText.length() > 20) {
            return "bearer_token_pattern";
        }
        if (rawText.contains("sk-") && rawText.contains("api")) {
            return "api_key_pattern";
        }
        return null;
    }

    public record Verification(boolean verified, String reason) {
        public static Verification accepted() {
            return new Verification(true, null);
        }

        public static Verification rejected(String reason) {
            return new Verification(false, reason);
        }
    }
}
