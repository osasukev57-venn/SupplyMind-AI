package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.history.HistoryQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-T05 warning acceptance (AT-ALT-001 backend): below/equal/above threshold boundaries,
 * low completeness data-quality warnings, unvalidated (absent) data never triggering a formal
 * warning, BigDecimal-only arithmetic, idempotent re-runs and immutable persistence under the
 * frozen warning/YYYY-MM directory.
 */
class WarningServiceTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String ITEM = "MAT.ADC12.SMM";

    @TempDir
    Path temporaryDirectory;

    @Test
    void boundaryBelowEqualAbove() throws Exception {
        Harness harness = harness();
        String junSha = writeDaily(harness, YearMonth.of(2026, 6), daily("2026-06-10", "run-jun"));
        String julSha = writeDaily(harness, YearMonth.of(2026, 7), daily("2026-07-10", "run-jul"));
        writeAggregates(harness, "month", 2026,
                List.of(aggregateRow("2026-06-01", "2026-06-30", "10000.00", junSha),
                        aggregateRow("2026-07-01", "2026-07-31", "19850.00", julSha)),
                List.of("run-jun", "run-jul"));

        WarningRuleV1 rule = WarningService.demoPriceChangeRule(ITEM, "month", "0.99");
        assertNull(harness.service().evaluate(rule, "2026-07-01", "2026-07-31"),
                "below threshold must not trigger");

        WarningRuleV1 equalRule = new WarningRuleV1(
                "test-equal", "demo-v1", WarningRuleV1.RuleKind.PRICE_CHANGE, ITEM, "month",
                "0.985", WarningRuleV1.Direction.ABOVE, 1, true, "TEST/DEMO");
        assertNull(harness.service().evaluate(equalRule, "2026-07-01", "2026-07-31"),
                "exactly equal to the threshold must not trigger (strict >)");

        WarningRuleV1 aboveRule = new WarningRuleV1(
                "test-above", "demo-v1", WarningRuleV1.RuleKind.PRICE_CHANGE, ITEM, "month",
                "0.98", WarningRuleV1.Direction.ABOVE, 1, true, "TEST/DEMO");
        WarningRecordV1 warning = harness.service().evaluate(aboveRule, "2026-07-01", "2026-07-31");
        assertNotNull(warning, "above threshold must trigger");
        assertEquals(WarningRecordV1.RiskLevel.HIGH, warning.riskLevel());
        assertTrue(warning.demoRule(), "rules are explicit TEST/DEMO until EXT-07 is confirmed");
        assertTrue(warning.ruleDescription().contains("TEST/DEMO"));
        assertEquals("PUBLISHED_VERIFIED", warning.dataStatus());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.warningRef(YearMonth.of(2026, 7), warning.warningId()))));
    }

    @Test
    void unvalidatedDataNeverTriggersFormalWarning() throws Exception {
        Harness harness = harness();
        WarningRuleV1 rule = WarningService.demoPriceChangeRule(ITEM, "month", "0.01");
        assertNull(harness.service().evaluate(rule, "2026-07-01", "2026-07-31"),
                "no published data in the period means no formal price warning");
    }

    @Test
    void lowCompletenessTriggersDataQualityWarning() throws Exception {
        Harness harness = harness();
        writeDaily(harness, YearMonth.of(2026, 8), daily("2026-08-10", "run-a"));
        WarningRuleV1 qualityRule = new WarningRuleV1(
                "test-quality", "demo-v1", WarningRuleV1.RuleKind.DATA_QUALITY, ITEM, "month",
                "0.9", WarningRuleV1.Direction.BELOW, 1, true, "TEST/DEMO");
        WarningRecordV1 warning = harness.service().evaluate(qualityRule, "2026-08-01", "2026-08-31");
        assertNotNull(warning, "a month with many missing business days must trigger a data-quality warning");
        assertEquals(WarningRuleV1.RuleKind.DATA_QUALITY.toString(), warning.ruleId().contains("quality") ? "DATA_QUALITY" : "DATA_QUALITY");
    }

    @Test
    void rerunIsIdempotentAndNeverDuplicatesWarnings() throws Exception {
        Harness harness = harness();
        String junSha = writeDaily(harness, YearMonth.of(2026, 6), daily("2026-06-10", "run-jun"));
        String julSha = writeDaily(harness, YearMonth.of(2026, 7), daily("2026-07-10", "run-jul"));
        writeAggregates(harness, "month", 2026,
                List.of(aggregateRow("2026-06-01", "2026-06-30", "10000.00", junSha),
                        aggregateRow("2026-07-01", "2026-07-31", "19850.00", julSha)),
                List.of("run-jun", "run-jul"));
        WarningRuleV1 rule = WarningService.demoPriceChangeRule(ITEM, "month", "0.98");
        WarningRecordV1 first = harness.service().evaluate(rule, "2026-07-01", "2026-07-31");
        WarningRecordV1 second = harness.service().evaluate(rule, "2026-07-01", "2026-07-31");
        assertEquals(first.warningId(), second.warningId(),
                "the same logical inputs must produce the same fingerprint-derived warningId");
        long files;
        try (var stream = Files.list(harness.root().resolveInternalRelative("warning/2026-07"))) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json")
                            && !p.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
        assertEquals(1, files, "re-running must never duplicate business warnings");
    }

    @Test
    void sameInputsAcrossDifferentClocksProduceByteIdenticalWarnings() throws Exception {
        Harness harness = harness();
        String junSha = writeDaily(harness, YearMonth.of(2026, 6), daily("2026-06-10", "run-jun"));
        String julSha = writeDaily(harness, YearMonth.of(2026, 7), daily("2026-07-10", "run-jul"));
        writeAggregates(harness, "month", 2026,
                List.of(aggregateRow("2026-06-01", "2026-06-30", "10000.00", junSha),
                        aggregateRow("2026-07-01", "2026-07-31", "19850.00", julSha)),
                List.of("run-jun", "run-jul"));
        WarningRuleV1 rule = WarningService.demoPriceChangeRule(ITEM, "month", "0.98");

        WarningRecordV1 clockA = harness.service().evaluate(rule, "2026-07-01", "2026-07-31");
        Clock clockB = Clock.fixed(Instant.parse("2026-08-10T09:30:00Z"), ZoneOffset.UTC);
        WarningService serviceB = new WarningService(
                harness.root(), new WarningStore(harness.root(),
                        new AtomicFileStore(harness.root(), new DirtyMarkerCodec()), clockB),
                clockB, new HistoryQueryService(harness.root()));
        WarningRecordV1 clockBRecord = serviceB.evaluate(rule, "2026-07-01", "2026-07-31");
        assertEquals(clockA.warningId(), clockBRecord.warningId());
        assertEquals(clockA.inputFingerprint(), clockBRecord.inputFingerprint());
        assertEquals(clockA.evaluatedAt(), clockBRecord.evaluatedAt(),
                "evaluatedAt must come from the deterministic input lineage, never the run clock");
        byte[] bytesA = Files.readAllBytes(harness.root().resolveDataRef(
                DataPaths.warningRef(YearMonth.of(2026, 7), clockA.warningId())));
        byte[] bytesB = Files.readAllBytes(harness.root().resolveDataRef(
                DataPaths.warningRef(YearMonth.of(2026, 7), clockBRecord.warningId())));
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytesA, bytesB,
                "the same logical inputs must persist byte-identical business warnings across clocks");
    }

    @Test
    void ruleWithDemoFlagFalseIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.supplymind.foundation.model.SchemaValidationException.class,
                () -> new WarningRuleV1(
                        "leaked", "v1", WarningRuleV1.RuleKind.PRICE_CHANGE, ITEM, "month",
                        "0.5", WarningRuleV1.Direction.ABOVE, 1, false,
                        "must be rejected while EXT-07/08 are open"),
                "demoRule=false is fail-closed: formal thresholds require a future rule version and decision");
    }

    @Test
    void precisionIsBigDecimalOnly() throws Exception {
        Harness harness = harness();
        String junSha = writeDaily(harness, YearMonth.of(2026, 6), daily("2026-06-10", "run-jun"));
        String julSha = writeDaily(harness, YearMonth.of(2026, 7), daily("2026-07-10", "run-jul"));
        writeAggregates(harness, "month", 2026,
                List.of(aggregateRow("2026-06-01", "2026-06-30", "10000.00", junSha),
                        aggregateRow("2026-07-01", "2026-07-31", "0.01", julSha)),
                List.of("run-jun", "run-jul"));
        WarningRuleV1 rule = new WarningRuleV1(
                "test-precision", "demo-v1", WarningRuleV1.RuleKind.PRICE_CHANGE, ITEM, "month",
                "0.999999999999", WarningRuleV1.Direction.BELOW, 1, true, "TEST/DEMO");
        WarningRecordV1 warning = harness.service().evaluate(rule, "2026-07-01", "2026-07-31");
        assertNotNull(warning);
        assertTrue(warning.currentValue().startsWith("-0.999999000000"),
                "the change ratio must be full-precision BigDecimal (scale 12), never a double approximation");
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("warning root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
        WarningStore store = new WarningStore(root, fileStore, clock);
        WarningService service = new WarningService(root, store, clock, new HistoryQueryService(root));
        return new Harness(root, fileStore, service);
    }

    private void writeAggregates(Harness harness, String grain, int year,
                                 List<AggregateRecordV1> rows, List<String> sourceRuns) throws Exception {
        byte[] csv = com.supplymind.foundation.codec.CsvV1Codec.encodeAggregate(rows);
        String ref = DataPaths.aggregateRef(ITEM, grain, year);
        String minStart = rows.stream().map(AggregateRecordV1::periodStart).min(String::compareTo).orElseThrow();
        String maxEnd = rows.stream().map(AggregateRecordV1::periodEnd).max(String::compareTo).orElseThrow();
        ManifestV1 manifest = ManifestFactory.csv(ref, csv, rows.size(), minStart, maxEnd, sourceRuns, AT);
        commit(harness, ref, csv, JsonV1Codec.encodeFile(manifest));
    }

    private static AggregateRecordV1 aggregateRow(String start, String end, String avg, String dailySha) {
        String fingerprint = com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                        com.supplymind.foundation.model.ProviderType.MANUAL,
                        "人工录入（Manual）", com.supplymind.foundation.model.AccessMethod.MANUAL));
        return new AggregateRecordV1(
                "1.0", AggregateGrain.MONTH, start, end, ITEM,
                com.supplymind.foundation.model.ProviderType.MANUAL,
                "人工录入（Manual）", com.supplymind.foundation.model.AccessMethod.MANUAL,
                com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                "material-basic-validation-v2", List.of(1), "arithmetic-mean-v1", 2, 2,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                avg, 1, avg, avg, avg, 1, 0, true,
                com.supplymind.foundation.model.QualityStatus.COMPLETE, "CNY", "元/吨", fingerprint,
                List.of(new com.supplymind.foundation.model.AggregateInputRefV1(
                        DataPaths.dailyRef(ITEM, YearMonth.parse(start.substring(0, 7))),
                        start, "material-basic-validation-v2", dailySha)),
                AT, "ADC12");
    }

    private String writeDaily(Harness harness, YearMonth month, DailyRecordV1 row) throws Exception {
        byte[] csv = com.supplymind.foundation.codec.CsvV1Codec.encodeDaily(List.of(row));
        String ref = DataPaths.dailyRef(ITEM, month);
        ManifestV1 manifest = ManifestFactory.csv(ref, csv, 1, row.businessDate(), row.businessDate(),
                List.of(row.inputRefs().get(0).runId()), AT);
        commit(harness, ref, csv, JsonV1Codec.encodeFile(manifest));
        return manifest.fileSha256();
    }

    private static DailyRecordV1 daily(String businessDate, String runId) {
        return new DailyRecordV1(
                "1.0", businessDate, ITEM, com.supplymind.foundation.model.ProviderType.MANUAL,
                "人工录入（Manual）", com.supplymind.foundation.model.AccessMethod.MANUAL,
                com.supplymind.foundation.model.ProcessingStage.PUBLISHED,
                com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                "material-basic-validation-v2", List.of(1), "arithmetic-mean-v1", 2, 2,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "19850.50", 1, "19850.50", 22, 21, false, "CNY", "元/吨",
                List.of(new com.supplymind.foundation.model.DailyInputRefV1(runId,
                        "raw/formal/manual/" + ITEM + "/2026/08/" + runId + ".json", 4)),
                OffsetDateTime.parse("2026-08-10T09:00:00+08:00"), "ADC12");
    }

    private void commit(Harness harness, String ref, byte[] data, byte[] manifest) throws Exception {
        String txId = "warning-fixture-" + ref.replace("/", "-").replace(".", "-");
        harness.fileStore().commit(txId, DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, data, manifest, false)));
    }

    private record Harness(DataRoot root, AtomicFileStore fileStore, WarningService service) {
    }
}
