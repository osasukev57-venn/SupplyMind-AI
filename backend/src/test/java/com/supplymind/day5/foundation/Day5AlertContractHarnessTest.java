package com.supplymind.day5.foundation;

import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.history.HistoryQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
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

    @Test
    void configuredThresholdInclusionWillDriveFutureAtAlt001Integration() throws Exception {
        // The production WarningService freezes the inclusion relation as strict: equality does
        // not trigger. Bind the reference boundary to the real production service.
        com.supplymind.warning.WarningService service = productionWarningService(
                new BigDecimal("10000.00"), new BigDecimal("19850.00"));
        com.supplymind.warning.WarningRuleV1 equalRule = new com.supplymind.warning.WarningRuleV1(
                "alt001-equal", "demo-v1", com.supplymind.warning.WarningRuleV1.RuleKind.PRICE_CHANGE,
                "MAT.ADC12.SMM", "month", "0.985", com.supplymind.warning.WarningRuleV1.Direction.ABOVE,
                1, true, "TEST/DEMO");
        assertEquals(null, service.evaluate(equalRule, "2026-07-01", "2026-07-31"),
                "production threshold inclusion is strict: equality never triggers");
        com.supplymind.warning.WarningRuleV1 aboveRule = new com.supplymind.warning.WarningRuleV1(
                "alt001-above", "demo-v1", com.supplymind.warning.WarningRuleV1.RuleKind.PRICE_CHANGE,
                "MAT.ADC12.SMM", "month", "0.98", com.supplymind.warning.WarningRuleV1.Direction.ABOVE,
                1, true, "TEST/DEMO");
        assertTrue(service.evaluate(aboveRule, "2026-07-01", "2026-07-31") != null,
                "production threshold inclusion is strict: above triggers");
    }

    private static com.supplymind.warning.WarningService productionWarningService(
            java.math.BigDecimal baselineAvg, java.math.BigDecimal currentAvg) throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("alt001-harness");
        com.supplymind.foundation.storage.DataRoot dataRoot = com.supplymind.foundation.storage.DataRoot.forTest(root);
        com.supplymind.foundation.storage.AtomicMoveSupport.probeOrFail(dataRoot);
        com.supplymind.foundation.storage.AtomicFileStore fileStore =
                new com.supplymind.foundation.storage.AtomicFileStore(dataRoot, new DirtyMarkerCodec());
        java.time.Clock clock = java.time.Clock.fixed(java.time.Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
        com.supplymind.warning.WarningStore store =
                new com.supplymind.warning.WarningStore(dataRoot, fileStore, clock);
        com.supplymind.warning.WarningService service =
                new com.supplymind.warning.WarningService(
                        dataRoot, store, clock, new HistoryQueryService(dataRoot));
        String fingerprint = com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                        com.supplymind.foundation.model.ProviderType.MANUAL,
                        "人工录入（Manual）", com.supplymind.foundation.model.AccessMethod.MANUAL));
        String junSha = writeDailyFixture(dataRoot, fileStore, "2026-06-10", "run-jun");
        String julSha = writeDailyFixture(dataRoot, fileStore, "2026-07-10", "run-jul");
        List<com.supplymind.foundation.model.AggregateRecordV1> rows = List.of(
                aggregateFixture("2026-06-01", "2026-06-30", baselineAvg, fingerprint, junSha),
                aggregateFixture("2026-07-01", "2026-07-31", currentAvg, fingerprint, julSha));
        byte[] csv = com.supplymind.foundation.codec.CsvV1Codec.encodeAggregate(rows);
        String ref = com.supplymind.foundation.storage.DataPaths.aggregateRef(
                "MAT.ADC12.SMM", "month", 2026);
        var manifest = com.supplymind.foundation.storage.ManifestFactory.csv(
                ref, csv, 2, "2026-06-01", "2026-07-31", List.of("run-jun", "run-jul"),
                java.time.OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
        commit(dataRoot, fileStore, ref, csv, manifest);
        return service;
    }

    private static com.supplymind.foundation.model.AggregateRecordV1 aggregateFixture(
            String start, String end, java.math.BigDecimal avg, String fingerprint, String dailySha) {
        String avgText = avg.toPlainString();
        return new com.supplymind.foundation.model.AggregateRecordV1(
                "1.0", com.supplymind.foundation.model.AggregateGrain.MONTH, start, end, "MAT.ADC12.SMM",
                com.supplymind.foundation.model.ProviderType.MANUAL,
                "人工录入（Manual）", com.supplymind.foundation.model.AccessMethod.MANUAL,
                com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                "material-basic-validation-v2", List.of(1), "arithmetic-mean-v1", 2, 2,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                avgText, 1, avgText, avgText, avgText, 1, 0, true,
                com.supplymind.foundation.model.QualityStatus.COMPLETE, "CNY", "元/吨", fingerprint,
                List.of(new com.supplymind.foundation.model.AggregateInputRefV1(
                        com.supplymind.foundation.storage.DataPaths.dailyRef(
                                "MAT.ADC12.SMM", java.time.YearMonth.parse(start.substring(0, 7))),
                        start, "material-basic-validation-v2", dailySha)),
                java.time.OffsetDateTime.parse("2026-08-10T09:00:00+08:00"), "ADC12");
    }

    private static String writeDailyFixture(
            com.supplymind.foundation.storage.DataRoot root,
            com.supplymind.foundation.storage.AtomicFileStore fileStore,
            String businessDate, String runId) throws Exception {
        com.supplymind.foundation.model.DailyRecordV1 row = new com.supplymind.foundation.model.DailyRecordV1(
                "1.0", businessDate, "MAT.ADC12.SMM", com.supplymind.foundation.model.ProviderType.MANUAL,
                "人工录入（Manual）", com.supplymind.foundation.model.AccessMethod.MANUAL,
                com.supplymind.foundation.model.ProcessingStage.PUBLISHED,
                com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                "material-basic-validation-v2", List.of(1), "arithmetic-mean-v1", 2, 2,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "19850.00", 1, "19850.00", 22, 21, false, "CNY", "元/吨",
                List.of(new com.supplymind.foundation.model.DailyInputRefV1(runId,
                        "raw/formal/manual/MAT.ADC12.SMM/2026/08/" + runId + ".json", 4)),
                java.time.OffsetDateTime.parse("2026-08-10T09:00:00+08:00"), "ADC12");
        byte[] csv = com.supplymind.foundation.codec.CsvV1Codec.encodeDaily(List.of(row));
        String ref = com.supplymind.foundation.storage.DataPaths.dailyRef(
                "MAT.ADC12.SMM", java.time.YearMonth.from(java.time.LocalDate.parse(businessDate)));
        var manifest = com.supplymind.foundation.storage.ManifestFactory.csv(
                ref, csv, 1, businessDate, businessDate, List.of(runId),
                java.time.OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
        commit(root, fileStore, ref, csv, manifest);
        return manifest.fileSha256();
    }

    private static void commit(
            com.supplymind.foundation.storage.DataRoot root,
            com.supplymind.foundation.storage.AtomicFileStore fileStore,
            String ref, byte[] csv, com.supplymind.foundation.model.ManifestV1 manifest) throws Exception {
        fileStore.commit("alt001-" + ref.replace("/", "-").replace(".", "-"),
                com.supplymind.foundation.storage.DirtyTransactionType.SINGLE_FILE,
                java.time.OffsetDateTime.parse("2026-08-10T10:00:00+08:00"),
                List.of(new com.supplymind.foundation.storage.FileTransactionTarget(
                        com.supplymind.foundation.storage.DirtyTargetRole.BUSINESS_FILE,
                        ref, csv, com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), false)));
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
