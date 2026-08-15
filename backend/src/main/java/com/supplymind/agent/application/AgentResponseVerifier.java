package com.supplymind.agent.application;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M1/M3/M5 AgentResponseVerifier: the LLM output is an UNTRUSTED MODEL DRAFT. Verification rules:
 *
 * M1/M3: every claim must reference factIds/evidenceRefs that EXIST in the current EvidencePack,
 * and each claim's references must SUPPORT the numbers stated in that claim (an unrelated
 * reference never validates the whole draft). Every business number in the draft - integer,
 * one-decimal, multi-decimal, negative, percentage, thousands-separated or scientific notation -
 * must strictly match a verified deterministic fact value; fabricated numbers reject the draft.
 *
 * M5: `requireNoSecret` scans USER INPUT (question/args) before EvidencePack/LLM/persistence.
 *
 * Verification outcome is binary: ACCEPTED or REJECTED (with a stable reason) - partial
 * acceptance is forbidden.
 */
public final class AgentResponseVerifier {

    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "-?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?%?"
                    + "|-?\\d+(?:\\.\\d+)?%?"
                    + "|-?\\d+(?:\\.\\d+)?[eE][+-]?\\d+"
                    + "|-?\\d+(?:\\.\\d+)?");
    private static final Pattern FACT_REF = Pattern.compile("fact-[A-Za-z0-9._-]+");
    private static final Pattern EVIDENCE_REF = Pattern
            .compile("(raw|processed|staging|warning|config)/[A-Za-z0-9._/-]+\\.(json|csv)");

    private final Set<String> configuredSecrets;

    public AgentResponseVerifier(List<String> configuredSecrets) {
        this.configuredSecrets = configuredSecrets == null
                ? Set.of() : Set.copyOf(configuredSecrets);
    }

    /** M5: fail closed when user input carries a secret; safe before any persistence. */
    public void requireNoSecret(String text) {
        String hit = findSecret(text);
        if (hit != null) {
            throw new IllegalArgumentException("input rejected: credential-like content detected");
        }
    }

    public Verification verify(ModelDraftV1 draft, EvidencePackV1 evidencePack) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(evidencePack, "evidencePack");

        String secretHit = findSecret(draft.rawText());
        if (secretHit != null) {
            return Verification.rejected("SECRET_INJECTION");
        }
        Set<String> knownFactIds = new LinkedHashSet<>();
        Set<String> knownVerifiedEvidenceRefs = new LinkedHashSet<>();
        List<EvidenceFact> facts = new ArrayList<>();
        for (EvidencePackV1.Fact fact : evidencePack.facts()) {
            knownFactIds.add(fact.factId());
            knownVerifiedEvidenceRefs.addAll(fact.evidenceRefs());
            facts.add(new EvidenceFact(fact.factId(), fact.value(), fact.evidenceRefs()));
        }
        for (EvidencePackV1.EvidenceRefEntry entry : evidencePack.evidenceRefs()) {
            if (entry.status() == EvidenceStatus.VERIFIED) {
                knownVerifiedEvidenceRefs.add(entry.ref());
            }
        }

        // M3: unknown references anywhere in the draft reject the whole draft.
        String unknownRef = findUnknownReference(draft.rawText(), knownFactIds, knownVerifiedEvidenceRefs);
        if (unknownRef != null) {
            return Verification.rejected("UNKNOWN_" + (unknownRef.startsWith("fact-")
                    ? "FACT_REFERENCE" : "EVIDENCE_REFERENCE"));
        }
        // M3: structured claims - each claim's references must support its own numbers.
        for (ModelClaimV1 claim : draft.claims()) {
            for (String factId : claim.factIds()) {
                if (!knownFactIds.contains(factId)) {
                    return Verification.rejected("UNKNOWN_FACT_REFERENCE");
                }
            }
            for (String ref : claim.evidenceRefs()) {
                if (!knownVerifiedEvidenceRefs.contains(ref)) {
                    return Verification.rejected("UNKNOWN_EVIDENCE_REFERENCE");
                }
            }
            List<String> claimNumbers = extractNumbers(claim.text());
            if (!claimNumbers.isEmpty() && !supportsNumbers(claim, facts)) {
                return Verification.rejected("UNSUPPORTED_CLAIM_REFERENCE");
            }
        }

        // M3: every business number in the free text must match a verified fact value.
        List<String> numbers = extractNumbers(draft.rawText());
        for (String number : numbers) {
            if (!matchesAnyFactValue(number, facts)) {
                return Verification.rejected("FABRICATED_NUMBER");
            }
        }
        // F2 micro-fix: a draft that restates a REAL formal business number must still cite at
        // least one valid factId or VERIFIED evidenceRef from the current request.
        if (!numbers.isEmpty() && !hasAnyValidReference(draft, knownFactIds, knownVerifiedEvidenceRefs)) {
            return Verification.rejected("MISSING_REQUIRED_REFERENCE");
        }
        return Verification.accepted();
    }

    /** M3: claim's factIds must point at facts whose value is among the claim's stated numbers. */
    private static boolean supportsNumbers(ModelClaimV1 claim, List<EvidenceFact> facts) {
        List<String> claimNumbers = extractNumbers(claim.text());
        for (String factId : claim.factIds()) {
            for (EvidenceFact fact : facts) {
                if (fact.factId().equals(factId) && matchesAnyFactValueInList(fact.value(), claimNumbers)) {
                    return true;
                }
            }
        }
        for (String ref : claim.evidenceRefs()) {
            for (EvidenceFact fact : facts) {
                if (fact.evidenceRefs() != null && fact.evidenceRefs().contains(ref)
                        && matchesAnyFactValueInList(fact.value(), claimNumbers)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesAnyFactValue(String number, List<EvidenceFact> facts) {
        for (EvidenceFact fact : facts) {
            if (matchesAnyFactValueInList(fact.value(), List.of(number))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFactValueInList(String factValue, List<String> numbers) {
        if (factValue == null || factValue.isBlank()) {
            return false;
        }
        String normalizedFact = normalizeNumber(factValue);
        for (String number : numbers) {
            if (normalizeNumber(number).equals(normalizedFact)) {
                return true;
            }
        }
        return false;
    }

    /** M3: normalize 1,234.56 -> 1234.56; 12% -> 12; 1.5e3 -> 1500; keep - sign. */
    private static String normalizeNumber(String token) {
        String value = token.trim();
        boolean negative = value.startsWith("-");
        if (negative) {
            value = value.substring(1);
        }
        if (value.endsWith("%")) {
            value = value.substring(0, value.length() - 1);
        }
        value = value.replace(",", "");
        try {
            BigDecimal decimal = new BigDecimal(value);
            value = decimal.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            // keep the raw token; exact-string matching will still reject unknown forms
        }
        return (negative ? "-" : "") + value;
    }

    /** M3: extract business numbers from free text (integer/one-decimal/multi/negative/percent/thousands/scientific). */
    static List<String> extractNumbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            numbers.add(token);
        }
        return List.copyOf(numbers);
    }

    private static boolean hasAnyValidReference(
            ModelDraftV1 draft, Set<String> knownFactIds, Set<String> knownEvidenceRefs
    ) {
        for (ModelClaimV1 claim : draft.claims()) {
            for (String factId : claim.factIds()) {
                if (knownFactIds.contains(factId)) {
                    return true;
                }
            }
            for (String ref : claim.evidenceRefs()) {
                if (knownEvidenceRefs.contains(ref)) {
                    return true;
                }
            }
        }
        String text = draft.rawText() == null ? "" : draft.rawText();
        Matcher factMatcher = FACT_REF.matcher(text);
        while (factMatcher.find()) {
            if (knownFactIds.contains(factMatcher.group())) {
                return true;
            }
        }
        Matcher refMatcher = EVIDENCE_REF.matcher(text);
        while (refMatcher.find()) {
            if (knownEvidenceRefs.contains(refMatcher.group())) {
                return true;
            }
        }
        return false;
    }

    private static String findUnknownReference(
            String rawText, Set<String> knownFactIds, Set<String> knownEvidenceRefs
    ) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        Matcher factMatcher = FACT_REF.matcher(rawText);
        while (factMatcher.find()) {
            String token = factMatcher.group();
            if (!knownFactIds.contains(token)) {
                return token;
            }
        }
        Matcher refMatcher = EVIDENCE_REF.matcher(rawText);
        while (refMatcher.find()) {
            String token = refMatcher.group();
            if (!knownEvidenceRefs.contains(token)) {
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
        String lower = rawText.toLowerCase();
        if (lower.contains("authorization: bearer ") || rawText.contains("Bearer ")
                || lower.contains("bearer ")) {
            return "bearer_token_pattern";
        }
        if (lower.contains("sk-") && (lower.contains("api") || lower.contains("key"))) {
            return "api_key_pattern";
        }
        if (lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("x-api-key")) {
            return "api_key_field";
        }
        if (lower.contains("cookie: ") || lower.contains("password=")
                || lower.contains("passwd=") || lower.contains("token=")) {
            return "credential_field";
        }
        return null;
    }

    private record EvidenceFact(String factId, String value, List<String> evidenceRefs) {
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
