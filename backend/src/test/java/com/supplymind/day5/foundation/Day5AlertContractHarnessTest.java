package com.supplymind.day5.foundation;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-only AT-ALT-001 contract harness.  Threshold inclusion remains data-driven by the future
 * alert-rules configuration; this class therefore validates exact boundary ordering and eligibility
 * without inventing an inclusion decision or treating an LLM as an oracle.
 */
class Day5AlertContractHarnessTest {

    @Test
    void exactDecimalBoundaryInputsAndFormalEligibilityArePreparedWithoutFloatingPointOrLlm() {
        BigDecimal threshold = new BigDecimal("0.0500");
        assertTrue(new BigDecimal("0.049999999999").compareTo(threshold) < 0);
        assertEquals(0, new BigDecimal("0.0500").compareTo(threshold));
        assertTrue(new BigDecimal("0.050000000001").compareTo(threshold) > 0);

        assertTrue(ReferenceAlertEligibility.isFormal(new InputState(true, "PUBLISHED", "VERIFIED", "formal")));
        assertTrue(ReferenceAlertEligibility.isFormal(new InputState(true, "PUBLISHED", "VERIFIED_WITH_NOTICE", "formal")));
        assertFalse(ReferenceAlertEligibility.isFormal(new InputState(true, "PUBLISHED", "PENDING", "formal")));
        assertFalse(ReferenceAlertEligibility.isFormal(new InputState(true, "VALIDATED", "VERIFIED", "formal")));
        assertFalse(ReferenceAlertEligibility.isFormal(new InputState(true, "PUBLISHED", "REJECTED", "formal")));
        assertFalse(ReferenceAlertEligibility.isFormal(new InputState(true, "PUBLISHED", "CONFLICT", "formal")));
        assertFalse(ReferenceAlertEligibility.isFormal(new InputState(true, "PUBLISHED", "VERIFIED", "demo")));
        assertFalse(ReferenceAlertEligibility.isFormal(new InputState(false, "PUBLISHED", "VERIFIED", "formal")),
                "low completeness must not silently become a formal business warning");
    }

    @Test
    void warningEvidencePersistenceAndFingerprintsAreDeterministicAcrossRepeatEvaluation() {
        WarningEvidence first = new WarningEvidence("ALT.FX.CHANGE", "v1", "FX.USD.CNY.PBOC_MID",
                "2026-08", "0.0500", List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"));
        WarningEvidence same = new WarningEvidence("ALT.FX.CHANGE", "v1", "FX.USD.CNY.PBOC_MID",
                "2026-08", "0.0500", List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"));
        WarningEvidence changedRule = new WarningEvidence("ALT.FX.CHANGE", "v2", "FX.USD.CNY.PBOC_MID",
                "2026-08", "0.0500", List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"));

        assertEquals(first.fingerprint(), same.fingerprint());
        assertNotEquals(first.fingerprint(), changedRule.fingerprint());
        ReferenceWarningStore store = new ReferenceWarningStore();
        store.persist(first);
        store.persist(same);
        assertEquals(1, store.persisted().size(), "repeat evaluation must not duplicate warning evidence");
        assertThrows(IllegalArgumentException.class,
                () -> new WarningEvidence("ALT.FX.CHANGE", "v1", "FX.USD.CNY.PBOC_MID", "2026-08", "0.0500", List.of()));
    }

    @Disabled("PENDING_IMPLEMENTATION: D5-T05 must bind the frozen alert-rules threshold inclusion relation before this integration assertion can run.")
    @Test
    void configuredThresholdInclusionWillDriveFutureAtAlt001Integration() {
        // The fixture has below/equal/above values; this lane does not invent whether equality triggers.
    }

    private record InputState(boolean complete, String processingStage, String validationStatus, String mode) {
    }

    private static final class ReferenceAlertEligibility {
        private static boolean isFormal(InputState input) {
            return input.complete() && "formal".equals(input.mode()) && "PUBLISHED".equals(input.processingStage())
                    && ("VERIFIED".equals(input.validationStatus()) || "VERIFIED_WITH_NOTICE".equals(input.validationStatus()));
        }
    }

    private record WarningEvidence(String ruleId, String ruleVersion, String itemId, String businessPeriod,
                                   String threshold, List<String> evidenceRefs) {
        private WarningEvidence {
            if (evidenceRefs.isEmpty()) {
                throw new IllegalArgumentException("warning evidence references are mandatory");
            }
            evidenceRefs = List.copyOf(evidenceRefs);
        }

        private String fingerprint() {
            return sha256(ruleId + "|" + ruleVersion + "|" + itemId + "|" + businessPeriod + "|" + threshold
                    + "|" + String.join(",", evidenceRefs));
        }
    }

    private static final class ReferenceWarningStore {
        private final Map<String, WarningEvidence> persisted = new LinkedHashMap<>();

        private void persist(WarningEvidence evidence) {
            WarningEvidence existing = persisted.putIfAbsent(evidence.fingerprint(), evidence);
            if (existing != null && !existing.equals(evidence)) {
                throw new IllegalStateException("deterministic fingerprint collision");
            }
        }

        private Map<String, WarningEvidence> persisted() {
            return Map.copyOf(persisted);
        }
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
