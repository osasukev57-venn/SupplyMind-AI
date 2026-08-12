package com.supplymind.history;

import com.supplymind.foundation.codec.CsvV1Codec;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D5-T02 cross-file/cross-year history query (AT-XR-001/002): month files are merged across
 * the year boundary, identical duplicates collapse on the stable business key, conflicting
 * duplicates are reported (never silently picked), missing files are reported as missing
 * (never zero) and corrupt files are reported explicitly (never treated as no data).
 * Repeated queries are deterministic.
 */
class HistoryQueryServiceTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String ITEM = "FX.USD.CNY.PBOC_MID";

    @TempDir
    Path temporaryDirectory;

    @Test
    void crossYearDailyMergeIsSortedDeduplicatedAndDeterministic() throws IOException {
        Harness harness = harness();
        DailyRecordV1 dec30 = daily("2025-12-30", "run-dec-30");
        DailyRecordV1 dec31 = daily("2025-12-31", "run-dec-31");
        DailyRecordV1 jan01 = daily("2026-01-01", "run-jan-01");
        DailyRecordV1 jan02 = daily("2026-01-02", "run-jan-02");
        writeDaily(harness, YearMonth.of(2025, 12), List.of(dec31, dec30));
        writeDaily(harness, YearMonth.of(2026, 1), List.of(jan01, jan02));

        HistoryQueryService.DailyHistoryResult result = harness.query().queryDaily(
                ITEM, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-01-31"));
        assertEquals(4, result.rows().size());
        assertEquals(List.of("2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02"),
                result.rows().stream().map(DailyRecordV1::businessDate).toList());
        assertTrue(result.missingRefs().isEmpty());
        assertTrue(result.corruptRefs().isEmpty());
        assertTrue(result.conflictKeys().isEmpty());

        HistoryQueryService.DailyHistoryResult again = harness.query().queryDaily(
                ITEM, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-01-31"));
        assertEquals(result.rows(), again.rows(), "repeated queries must be deterministic");
    }

    @Test
    void identicalDuplicatesCollapseAndConflictingDuplicatesAreReported() throws IOException {
        Harness harness = harness();
        DailyRecordV1 original = daily("2026-01-05", "run-jan-05");
        DailyRecordV1 conflicting = daily("2026-01-06", "run-jan-06");
        writeDaily(harness, YearMonth.of(2026, 1), List.of(original));
        writeDaily(harness, YearMonth.of(2026, 2), List.of(original));
        writeDaily(harness, YearMonth.of(2026, 3), List.of(conflicting));

        HistoryQueryService.DailyHistoryResult result = harness.query().queryDaily(
                ITEM, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"));
        assertEquals(2, result.rows().size(),
                "the identical duplicate across files must collapse on the stable business key");
        assertTrue(result.conflictKeys().isEmpty(), "identical records are not a conflict");

        DailyRecordV1 sameKeyOtherContent = new DailyRecordV1(
                "1.0", conflicting.businessDate(), ITEM, conflicting.providerType(),
                conflicting.actualSourceName(), conflicting.accessMethod(),
                conflicting.processingStage(), conflicting.validationStatus(),
                conflicting.validationVersion(), conflicting.configVersions(),
                conflicting.calculationVersion(), conflicting.calculationScale(),
                conflicting.displayScale(), conflicting.roundingMode(),
                conflicting.calendarVersion(), "6.99999999", conflicting.validCount(), "6.99999999",
                conflicting.expectedCount(), conflicting.missingCount(), conflicting.complete(),
                conflicting.currency(), conflicting.unit(), conflicting.inputRefs(),
                conflicting.updatedAt(), conflicting.canonicalSpecCode());
        writeDaily(harness, YearMonth.of(2026, 4), List.of(sameKeyOtherContent));

        HistoryQueryService.DailyHistoryResult conflicted = harness.query().queryDaily(
                ITEM, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-30"));
        assertFalse(conflicted.conflictKeys().isEmpty(),
                "a same-key record with different content must be reported, never silently picked");
        assertTrue(conflicted.corruptRefs().isEmpty());
    }

    @Test
    void missingFilesAreReportedNotZeroFilled() throws IOException {
        Harness harness = harness();
        writeDaily(harness, YearMonth.of(2026, 1), List.of(daily("2026-01-15", "run-jan-15")));
        HistoryQueryService.DailyHistoryResult result = harness.query().queryDaily(
                ITEM, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-01-31"));
        assertEquals(1, result.rows().size());
        assertTrue(result.missingRefs().stream().anyMatch(ref -> ref.endsWith("2025-12.csv")),
                "the absent month must be reported as missing, never zero-filled");
    }

    @Test
    void corruptFileIsReportedExplicitlyNotTreatedAsNoData() throws IOException {
        Harness harness = harness();
        writeDaily(harness, YearMonth.of(2026, 1), List.of(daily("2026-01-15", "run-jan-15")));
        Files.writeString(harness.root().resolveDataRef(
                DataPaths.dailyRef(ITEM, YearMonth.of(2026, 2))), "not-a-csv\r\n");
        HistoryQueryService.DailyHistoryResult result = harness.query().queryDaily(
                ITEM, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-28"));
        assertEquals(1, result.rows().size());
        assertFalse(result.corruptRefs().isEmpty(), "a corrupt file must be reported, never treated as no data");
    }

    @Test
    void corruptManifestIsReportedExplicitly() throws IOException {
        Harness harness = harness();
        writeDaily(harness, YearMonth.of(2026, 1), List.of(daily("2026-01-15", "run-jan-15")));
        Path manifest = harness.root().resolveDataRef(DataPaths.manifestRef(
                DataPaths.dailyRef(ITEM, YearMonth.of(2026, 1))));
        Files.writeString(manifest, "{}");
        HistoryQueryService.DailyHistoryResult result = harness.query().queryDaily(
                ITEM, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));
        assertTrue(result.rows().isEmpty());
        assertFalse(result.corruptRefs().isEmpty(), "a manifest mismatch must be reported as corruption");
    }

    @Test
    void crossYearAggregateQueryMergesYearlyFiles() throws IOException {
        Harness harness = harness();
        String decDailySha = writeDaily(harness, YearMonth.of(2025, 12),
                List.of(daily("2025-12-30", "run-dec-30")));
        String janDailySha = writeDaily(harness, YearMonth.of(2026, 1),
                List.of(daily("2026-01-15", "run-jan-15")));
        writeAggregate(harness, "month", 2025,
                aggregate(AggregateGrain.MONTH, "2025-12-01", "2025-12-31", decDailySha),
                List.of("run-dec-30"));
        writeAggregate(harness, "month", 2026,
                aggregate(AggregateGrain.MONTH, "2026-01-01", "2026-01-31", janDailySha),
                List.of("run-jan-15"));
        HistoryQueryService.AggregateHistoryResult result = harness.query().queryAggregate(
                ITEM, "month", 2025, 2026);
        assertEquals(2, result.rows().size());
        assertEquals(List.of("2025-12-01", "2026-01-01"),
                result.rows().stream().map(AggregateRecordV1::periodStart).toList());
        assertTrue(result.missingRefs().isEmpty());
        assertTrue(result.corruptRefs().isEmpty());
    }

    @Test
    void conflictingDuplicateOutcomeIsIndependentOfFileTraversalOrder() throws IOException {
        Harness harness = harness();
        DailyRecordV1 jan05 = daily("2026-01-05", "run-jan-05");
        DailyRecordV1 jan05Other = new DailyRecordV1(
                "1.0", "2026-01-05", ITEM, jan05.providerType(), jan05.actualSourceName(),
                jan05.accessMethod(), jan05.processingStage(), jan05.validationStatus(),
                jan05.validationVersion(), jan05.configVersions(), jan05.calculationVersion(),
                jan05.calculationScale(), jan05.displayScale(), jan05.roundingMode(),
                jan05.calendarVersion(), "6.99999999", jan05.validCount(), "6.99999999",
                jan05.expectedCount(), jan05.missingCount(), jan05.complete(),
                jan05.currency(), jan05.unit(), jan05.inputRefs(), jan05.updatedAt(),
                jan05.canonicalSpecCode());
        DailyRecordV1 dec30 = daily("2025-12-30", "run-dec-30");
        writeDaily(harness, YearMonth.of(2026, 1), List.of(jan05));
        writeDaily(harness, YearMonth.of(2026, 2), List.of(jan05Other));
        writeDaily(harness, YearMonth.of(2025, 12), List.of(dec30));

        HistoryQueryService.DailyHistoryResult forward = harness.query().queryDaily(
                ITEM, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-02-28"));
        HistoryQueryService.DailyHistoryResult again = harness.query().queryDaily(
                ITEM, LocalDate.parse("2025-12-01"), LocalDate.parse("2026-02-28"));
        assertEquals(forward.conflictKeys(), again.conflictKeys(),
                "the conflict outcome must be identical on every query, independent of traversal");
        assertFalse(forward.conflictKeys().isEmpty());
        assertEquals(1, forward.rows().size(),
                "the conflicting key must be excluded from usable results - no arbitrary record is returned");
        assertEquals("2025-12-30", forward.rows().get(0).businessDate(),
                "the usable result must never contain any record of the conflicting key");
    }

    @Test
    void reverseDateRangeFailsClosed() {
        Harness harness = harness();
        org.junit.jupiter.api.Assertions.assertThrows(
                com.supplymind.foundation.storage.StorageException.class,
                () -> harness.query().queryDaily(ITEM, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01")));
    }

    private static DailyRecordV1 daily(String businessDate, String runId) {
        return new DailyRecordV1(
                "1.0", businessDate, ITEM, com.supplymind.foundation.model.ProviderType.OFFICIAL_WEB,
                "中国人民银行官网（授权中国外汇交易中心公布）", com.supplymind.foundation.model.AccessMethod.PUBLIC_OFFICIAL_HTML,
                com.supplymind.foundation.model.ProcessingStage.PUBLISHED,
                com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1", 8, 4,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "7.12340000", 1, "7.12340000", 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new com.supplymind.foundation.model.DailyInputRefV1(runId,
                        "raw/formal/official_web/" + ITEM + "/2026/01/" + runId + ".json", 4)),
                OffsetDateTime.parse("2026-08-10T09:00:00+08:00"), null);
    }

    private static AggregateRecordV1 aggregate(AggregateGrain grain, String start, String end, String dailySha) {
        String fingerprint = com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                        com.supplymind.foundation.model.ProviderType.OFFICIAL_WEB,
                        "中国人民银行官网（授权中国外汇交易中心公布）",
                        com.supplymind.foundation.model.AccessMethod.PUBLIC_OFFICIAL_HTML));
        return new AggregateRecordV1(
                "1.0", grain, start, end, ITEM,
                com.supplymind.foundation.model.ProviderType.OFFICIAL_WEB,
                "中国人民银行官网（授权中国外汇交易中心公布）",
                com.supplymind.foundation.model.AccessMethod.PUBLIC_OFFICIAL_HTML,
                com.supplymind.foundation.model.ValidationStatus.VERIFIED,
                "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1", 8, 4,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "7.12340000", 1, "7.12340000", "7.12340000", "7.12340000", 22, 21, false,
                com.supplymind.foundation.model.QualityStatus.INCOMPLETE, "CNY", "CNY/1 USD", fingerprint,
                List.of(new com.supplymind.foundation.model.AggregateInputRefV1(
                        DataPaths.dailyRef(ITEM, YearMonth.parse(start.substring(0, 7))),
                        start, "pboc-basic-validation-v1", dailySha)),
                OffsetDateTime.parse("2026-08-10T09:00:00+08:00"), null);
    }

    private String writeDaily(Harness harness, YearMonth month, List<DailyRecordV1> rows) throws IOException {
        byte[] csv = CsvV1Codec.encodeDaily(rows);
        String ref = DataPaths.dailyRef(ITEM, month);
        List<String> runs = rows.stream()
                .flatMap(row -> row.inputRefs().stream())
                .map(ref1 -> ref1.runId()).distinct().sorted().toList();
        ManifestV1 manifest = ManifestFactory.csv(ref, csv, rows.size(),
                rows.stream().map(DailyRecordV1::businessDate).min(String::compareTo).orElseThrow(),
                rows.stream().map(DailyRecordV1::businessDate).max(String::compareTo).orElseThrow(),
                runs, AT);
        commit(harness, ref, csv, JsonV1Codec.encodeFile(manifest));
        return manifest.fileSha256();
    }

    private void writeAggregate(Harness harness, String grain, int year, AggregateRecordV1 row,
                                List<String> sourceRuns) throws IOException {
        byte[] csv = CsvV1Codec.encodeAggregate(List.of(row));
        String ref = DataPaths.aggregateRef(ITEM, grain, year);
        ManifestV1 manifest = ManifestFactory.csv(ref, csv, 1, row.periodStart(), row.periodEnd(), sourceRuns, AT);
        commit(harness, ref, csv, JsonV1Codec.encodeFile(manifest));
    }

    private void commit(Harness harness, String ref, byte[] data, byte[] manifest) throws IOException {
        String txId = "history-fixture-" + ref.replace("/", "-").replace(".", "-");
        harness.fileStore().commit(txId,
                DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, data, manifest, false)));
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("history root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        return new Harness(root, fileStore, new HistoryQueryService(root));
    }

    private record Harness(DataRoot root, AtomicFileStore fileStore, HistoryQueryService query) {
    }
}
