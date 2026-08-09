package com.supplymind.processing;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.PbocCandidateStandardizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyProcessingServiceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-08-10T09:25:38+08:00");
    private static final String SOURCE_NAME = MonitorSeriesDefaults.PBOC_SOURCE_NAME;
    private static final String FIXTURE_ROOT = "contracts/v1/valid/";
    private static final YearMonth MONTH_2026_08 = YearMonth.of(2026, 8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void singleValueDayProducesFrozenDailyRowWithGoldenBytes() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-daily-golden-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingestAndPublish(harness, raw);

        DailyResult result = harness.daily().processMonth(raw.itemId(), MONTH_2026_08);

        assertEquals("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv", result.dailyRef());
        assertEquals(1, result.rows().size());
        DailyRecordV1 row = result.rows().get(0);
        assertRow(row, "2026-08-10", "6.7904", 1, "6.79040000", 1, 0, true,
                List.of(1));
        Path dailyPath = harness.root().resolveDataRef(result.dailyRef());
        byte[] golden = fixtureBytes("daily-pboc-v1.csv");
        assertArrayEquals(golden, Files.readAllBytes(dailyPath),
                "the persisted daily CSV must match the hand-authored golden bytes");
        ManifestV1 manifest = JsonV1Codec.decodeFile(
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.manifestRef(result.dailyRef()))),
                ManifestV1.class);
        assertEquals("2026-08.csv", manifest.fileName());
        assertEquals(FileDigest.sha256(dailyPath), manifest.fileSha256());
        assertEquals(Files.size(dailyPath), manifest.byteLength());
        assertEquals(1, manifest.rowCount());
        assertEquals("2026-08-10", manifest.minBusinessDate());
        assertEquals("2026-08-10", manifest.maxBusinessDate());
        assertEquals(List.of("run-daily-golden-001"), manifest.sourceRunIds());
        assertTrue(ManifestVerifier.matches(harness.root(), result.dailyRef(), dailyPath,
                harness.root().resolveDataRef(DataPaths.manifestRef(result.dailyRef())),
                List.of("run-daily-golden-001")));
        assertEquals(List.of(row), CsvV1Codec.decodeDaily(Files.readAllBytes(dailyPath)));
    }

    @Test
    void multiObservationDayAveragesExactSumWithFullInputRefs() throws IOException {
        Harness harness = harness();
        RawReceiptV1 a = pbocRaw("run-daily-multi-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 b = pbocRaw("run-daily-multi-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.8000", "2026-08-10", 1);
        publishFixtureRun(harness, a, ValidationStatus.VERIFIED);
        publishFixtureRun(harness, b, ValidationStatus.VERIFIED);

        DailyResult result = harness.daily().processMonth(a.itemId(), MONTH_2026_08);

        assertEquals(1, result.rows().size());
        DailyRecordV1 row = result.rows().get(0);
        assertEquals("13.5904", row.sum());
        assertEquals(2, row.validCount());
        assertEquals("6.79520000", row.avg());
        assertEquals(0, row.missingCount());
        assertTrue(row.complete());
        assertEquals(List.of(1), row.configVersions());
        assertEquals(2, row.inputRefs().size());
        assertEquals("run-daily-multi-a-001", row.inputRefs().get(0).runId());
        assertEquals("run-daily-multi-b-001", row.inputRefs().get(1).runId());
        assertEquals(4, row.inputRefs().get(0).recordVersion());
        assertTrue(row.inputRefs().get(0).rawRef().startsWith("raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/"));
    }

    @Test
    void missingDayProducesNoRowAndEmptyMonthProducesNoFile() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-daily-missing-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingestAndPublish(harness, raw);

        DailyResult result = harness.daily().processMonth(raw.itemId(), MONTH_2026_08);

        assertEquals(1, result.rows().size());
        assertEquals("2026-08-10", result.rows().get(0).businessDate());
        assertFalse(result.rows().stream().anyMatch(row -> row.businessDate().equals("2026-08-11")),
                "a missing day must not produce a zero-filled row");

        DailyResult empty = harness.daily().processMonth(raw.itemId(), YearMonth.of(2026, 9));
        assertNull(empty.dailyRef());
        assertTrue(empty.rows().isEmpty());
        assertFalse(Files.exists(harness.root().path().resolve("processed/daily/FX.USD.CNY.PBOC_MID/2026-09.csv")));
    }

    @Test
    void identicalDuplicateObservationsSplitByValidationConclusion() throws IOException {
        Harness harness = harness();
        RawReceiptV1 a = pbocRaw("run-daily-dup-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 b = pbocRaw("run-daily-dup-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingestAndPublish(harness, a);
        ingestAndPublish(harness, b);

        DailyResult result = harness.daily().processMonth(a.itemId(), MONTH_2026_08);

        assertEquals(2, result.rows().size(),
                "different validation conclusions of the same day must never mix");
        DailyRecordV1 verifiedRow = result.rows().stream()
                .filter(row -> row.validationStatus() == ValidationStatus.VERIFIED).findFirst().orElseThrow();
        DailyRecordV1 noticeRow = result.rows().stream()
                .filter(row -> row.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE).findFirst().orElseThrow();
        assertEquals("6.7904", verifiedRow.sum());
        assertEquals("6.79040000", verifiedRow.avg());
        assertEquals(1, verifiedRow.validCount());
        assertEquals("6.7904", noticeRow.sum());
        assertEquals("6.79040000", noticeRow.avg());
        assertEquals(1, noticeRow.validCount());
    }

    @Test
    void nonPublishedAndInvalidRecordsNeverEnterDaily() throws IOException {
        Harness harness = harness();
        RawReceiptV1 pending = pbocRaw("run-daily-invalid-pending-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 rejected = pbocRaw("run-daily-invalid-rejected-001", MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                "7.8067", "2026-08-10", 1);
        RawReceiptV1 validButNotPublished = pbocRaw("run-daily-invalid-unpublished-001",
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "6.7904", "2026-08-10", 1);
        RawReceiptV1 published = pbocRaw("run-daily-invalid-published-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingest(harness, pending);
        ingest(harness, rejected);
        ingest(harness, validButNotPublished);
        ingest(harness, published);
        harness.validation().process(rejected.runId());
        harness.validation().process(validButNotPublished.runId());
        harness.validation().process(published.runId());

        assertTrue(harness.daily().processMonth(MonitorSeriesDefaults.USD_CNY_ITEM_ID, MONTH_2026_08).rows().isEmpty(),
                "unpublished, pending and rejected records must never enter daily");
        assertTrue(harness.daily().processMonth(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, MONTH_2026_08).rows().isEmpty());

        harness.publish().process(validButNotPublished.runId());
        harness.publish().process(published.runId());
        DailyResult result = harness.daily().processMonth(MonitorSeriesDefaults.USD_CNY_ITEM_ID, MONTH_2026_08);
        assertEquals(2, result.rows().size(),
                "the two published observations split by validation conclusion; rejected and pending stay excluded");
        List<String> contributedRunIds = result.rows().stream()
                .flatMap(row -> row.inputRefs().stream())
                .map(DailyInputRefV1::runId)
                .toList();
        assertTrue(contributedRunIds.contains("run-daily-invalid-unpublished-001"));
        assertTrue(contributedRunIds.contains("run-daily-invalid-published-001"));
        assertFalse(contributedRunIds.contains("run-daily-invalid-pending-001"));
        assertFalse(contributedRunIds.contains("run-daily-invalid-rejected-001"));
        assertTrue(harness.daily().processMonth(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, MONTH_2026_08).rows().isEmpty());
    }

    @Test
    void repeatingDecimalRoundsOnlyAtCalculationScale() throws IOException {
        Harness harness = harness();
        RawReceiptV1 a = pbocRaw("run-daily-repeat-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 b = pbocRaw("run-daily-repeat-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 c = pbocRaw("run-daily-repeat-c-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "7.8067", "2026-08-10", 1);
        publishFixtureRun(harness, a, ValidationStatus.VERIFIED);
        publishFixtureRun(harness, b, ValidationStatus.VERIFIED);
        publishFixtureRun(harness, c, ValidationStatus.VERIFIED);

        DailyResult result = harness.daily().processMonth(a.itemId(), MONTH_2026_08);

        assertEquals(1, result.rows().size());
        DailyRecordV1 row = result.rows().get(0);
        assertEquals("21.3875", row.sum(), "sum must stay exact without rounding");
        assertEquals(3, row.validCount());
        assertEquals("7.12916667", row.avg(), "avg must round exactly once at calculationScale=8 HALF_UP");
    }

    @Test
    void twelveDigitPersistenceAndNineDigitDisplayContext() throws IOException {
        Harness harness = harness();
        harness.configStore().activate(configWith(2, 12, 9, null));
        RawReceiptV1 a = pbocRaw("run-daily-gd01-a-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "100.1", "2026-08-10", 2);
        RawReceiptV1 b = pbocRaw("run-daily-gd01-b-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "100.2", "2026-08-10", 2);
        RawReceiptV1 c = pbocRaw("run-daily-gd01-c-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "100.2", "2026-08-10", 2);
        publishFixtureRun(harness, a, ValidationStatus.VERIFIED);
        publishFixtureRun(harness, b, ValidationStatus.VERIFIED);
        publishFixtureRun(harness, c, ValidationStatus.VERIFIED);

        DailyResult result = harness.daily().processMonth(a.itemId(), MONTH_2026_08);

        assertEquals(1, result.rows().size());
        DailyRecordV1 row = result.rows().get(0);
        assertEquals(12, row.calculationScale());
        assertEquals(9, row.displayScale());
        assertEquals("300.5", row.sum());
        assertEquals("100.166666666667", row.avg(), "12-digit persistence, displayScale=9 never written back");
        assertEquals(List.of(2), row.configVersions());
    }

    @Test
    void configVersionSwitchUnifiesRowsWithIdenticalCalculationContext() throws IOException {
        Harness harness = harness();
        harness.configStore().activate(configWith(2, 12, 9, null));
        harness.configStore().activate(configWith(3, 8, 4, "D2-T03 config v3 same calculation context"));
        RawReceiptV1 v1Raw = pbocRaw("run-daily-cfgswitch-v1-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 v3Raw = pbocRaw("run-daily-cfgswitch-v3-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.8000", "2026-08-10", 3);
        publishFixtureRun(harness, v1Raw, ValidationStatus.VERIFIED);
        publishFixtureRun(harness, v3Raw, ValidationStatus.VERIFIED);

        DailyResult result = harness.daily().processMonth(v1Raw.itemId(), MONTH_2026_08);

        assertEquals(1, result.rows().size());
        DailyRecordV1 row = result.rows().get(0);
        assertEquals(List.of(1, 3), row.configVersions(),
                "identical calculation context across config versions must unify into one row");
        assertEquals(2, row.validCount());
        assertEquals("13.5904", row.sum());
    }

    @Test
    void calculationContextSwitchSeparatesRows() throws IOException {
        Harness harness = harness();
        harness.configStore().activate(configWith(2, 12, 9, null));
        RawReceiptV1 v1Raw = pbocRaw("run-daily-calcswitch-v1-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        RawReceiptV1 v2Raw = pbocRaw("run-daily-calcswitch-v2-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 2);
        ingestAndPublish(harness, v1Raw);
        ingestAndPublish(harness, v2Raw);

        DailyResult result = harness.daily().processMonth(v1Raw.itemId(), MONTH_2026_08);

        assertEquals(2, result.rows().size(), "different calculation contexts must never mix");
        DailyRecordV1 rowScale8 = result.rows().stream().filter(row -> row.calculationScale() == 8).findFirst().orElseThrow();
        DailyRecordV1 rowScale12 = result.rows().stream().filter(row -> row.calculationScale() == 12).findFirst().orElseThrow();
        assertEquals(ValidationStatus.VERIFIED, rowScale8.validationStatus());
        assertEquals(ValidationStatus.VERIFIED_WITH_NOTICE, rowScale12.validationStatus(),
                "the same-key duplicate is VERIFIED_WITH_NOTICE while the baseline is VERIFIED");
        assertEquals("6.79040000", rowScale8.avg());
        assertEquals("6.790400000000", rowScale12.avg());
        assertEquals(List.of(1), rowScale8.configVersions());
        assertEquals(List.of(2), rowScale12.configVersions());
    }

    @Test
    void sameInputsAcrossDifferentProcessingClocksProduceIdenticalBytesAndSha() throws IOException {
        RawReceiptV1 raw = pbocRaw("run-daily-crossclock-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        Harness writerA = harness(Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), SHANGHAI));
        ingestAndPublish(writerA, raw);
        DailyResult resultA = writerA.daily().processMonth(raw.itemId(), MONTH_2026_08);
        byte[] bytesA = Files.readAllBytes(writerA.root().resolveDataRef(resultA.dailyRef()));
        String shaA = FileDigest.sha256(bytesA);

        Harness writerB = harness(Clock.fixed(Instant.parse("2026-08-10T10:30:00Z"), SHANGHAI));
        ingestAndPublish(writerB, raw);
        DailyResult resultB = writerB.daily().processMonth(raw.itemId(), MONTH_2026_08);
        byte[] bytesB = Files.readAllBytes(writerB.root().resolveDataRef(resultB.dailyRef()));
        String shaB = FileDigest.sha256(bytesB);

        assertArrayEquals(bytesA, bytesB, "identical business inputs must produce identical daily CSV bytes");
        assertEquals(shaA, shaB, "identical business inputs must produce identical CSV SHA-256");
        assertEquals(resultA.rows().get(0).updatedAt(), resultB.rows().get(0).updatedAt());
        assertEquals(resultA.rows().get(0), resultB.rows().get(0));
    }

    @Test
    void dailyUpdatedAtEqualsPublishedAtOfSingleInput() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-daily-updatedat-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingestAndPublish(harness, raw);
        DailyResult result = harness.daily().processMonth(raw.itemId(), MONTH_2026_08);
        assertEquals("2026-08-10T09:02+08:00", result.rows().get(0).updatedAt().toString(),
                "daily.updatedAt must equal the valid PUBLISHED input publishedAt, not the processing clock");
    }

    @Test
    void dailyUpdatedAtIsMaxPublishedAtAcrossInputs() {
        List<DailyInput> inputs = List.of(
                dailyInput("run-max-a", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T08:00+08:00"),
                dailyInput("run-max-b", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T09:30+08:00"),
                dailyInput("run-max-c", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T09:00+08:00"));
        DailyRecordV1 row = DailyMeanCalculator.calculate(inputs).get(0);
        assertEquals("2026-08-10T09:30+08:00", row.updatedAt().toString(),
                "updatedAt must be the latest official publish instant of the group");
    }

    @Test
    void dailyUpdatedAtIsOrderIndependent() {
        List<DailyInput> forward = List.of(
                dailyInput("run-order-a", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T08:00+08:00"),
                dailyInput("run-order-b", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T09:30+08:00"),
                dailyInput("run-order-c", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T09:00+08:00"));
        List<DailyInput> reversed = new java.util.ArrayList<>(forward);
        java.util.Collections.reverse(reversed);
        assertEquals(DailyMeanCalculator.calculate(forward).get(0),
                DailyMeanCalculator.calculate(reversed).get(0),
                "input order must not affect updatedAt");
    }

    @Test
    void addingOlderInputKeepsUpdatedAtWhileNewerInputAdvancesIt() {
        List<DailyInput> base = List.of(
                dailyInput("run-evolve-a", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T08:00+08:00"),
                dailyInput("run-evolve-b", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T09:00+08:00"));
        assertEquals("2026-08-10T09:00+08:00",
                DailyMeanCalculator.calculate(base).get(0).updatedAt().toString());
        List<DailyInput> withOlder = new java.util.ArrayList<>(base);
        withOlder.add(dailyInput("run-evolve-older", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                "2026-08-10T08:30+08:00"));
        assertEquals("2026-08-10T09:00+08:00",
                DailyMeanCalculator.calculate(withOlder).get(0).updatedAt().toString(),
                "adding an older input must not change updatedAt");
        List<DailyInput> withNewer = new java.util.ArrayList<>(base);
        withNewer.add(dailyInput("run-evolve-newer", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                "2026-08-10T10:00+08:00"));
        assertEquals("2026-08-10T10:00+08:00",
                DailyMeanCalculator.calculate(withNewer).get(0).updatedAt().toString(),
                "adding a newer input must advance updatedAt");
    }

    @Test
    void publishedAtComparisonUsesInstantNotOffsetText() {
        DailyInput textLater = dailyInput("run-instant-a", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                "2026-08-10T02:00+08:00");
        DailyInput instantLater = dailyInput("run-instant-b", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                "2026-08-09T23:00+01:00");
        List<DailyInput> inputs = List.of(textLater, instantLater);
        DailyRecordV1 row = DailyMeanCalculator.calculate(inputs).get(0);
        assertEquals("2026-08-10T06:00+08:00", row.updatedAt().toString(),
                "comparison must use Instant (2026-08-09T23:00+01:00 is later than 2026-08-10T02:00+08:00), "
                        + "not offset-text lexicographic order");
    }

    @Test
    void verifiedGroupsTakeTheirOwnMaxPublishedAt() {
        List<DailyInput> inputs = List.of(
                dailyInput("run-split-verified", "6.7904", "2026-08-10", ValidationStatus.VERIFIED,
                        "2026-08-10T08:00+08:00"),
                dailyInput("run-split-notice-a", "6.7904", "2026-08-10", ValidationStatus.VERIFIED_WITH_NOTICE,
                        "2026-08-10T09:30+08:00"),
                dailyInput("run-split-notice-b", "6.7904", "2026-08-10", ValidationStatus.VERIFIED_WITH_NOTICE,
                        "2026-08-10T09:00+08:00"));
        List<DailyRecordV1> rows = DailyMeanCalculator.calculate(inputs);
        assertEquals(2, rows.size(), "VERIFIED and VERIFIED_WITH_NOTICE must keep separate rows");
        DailyRecordV1 verifiedRow = rows.stream()
                .filter(row -> row.validationStatus() == ValidationStatus.VERIFIED).findFirst().orElseThrow();
        DailyRecordV1 noticeRow = rows.stream()
                .filter(row -> row.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE).findFirst().orElseThrow();
        assertEquals("2026-08-10T08:00+08:00", verifiedRow.updatedAt().toString(),
                "each group must take its own max publishedAt");
        assertEquals("2026-08-10T09:30+08:00", noticeRow.updatedAt().toString());
    }

    @Test
    void missingPublishedAtFailsClosed() throws IOException {
        Harness harness = harness();
        CandidateV1 candidate = new PbocCandidateStandardizer().standardize(
                pbocRaw("run-daily-nopublish-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                        "6.7904", "2026-08-10", 1)).candidate();
        OffsetDateTime at = RECEIVED_AT.plusMinutes(1);
        assertThrows(SchemaValidationException.class, () -> new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate, null,
                "pboc-basic-validation-v1", at, null, null, at),
                "a PUBLISHED snapshot without publishedAt must fail closed at the model boundary");

        String stagingRef = DataPaths.stagingRef("run-daily-corrupt-001");
        byte[] invalidStaging = ("{\"schemaVersion\":\"1.0\",\"recordId\":\"record-run-daily-corrupt-001\","
                + "\"runId\":\"run-daily-corrupt-001\",\"rawRef\":\"raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/"
                + "run-daily-corrupt-001.json\",\"currentRecordVersion\":4,\"records\":[{\"recordVersion\":4,"
                + "\"processingStage\":\"PUBLISHED\",\"validationStatus\":\"VERIFIED\",\"candidate\":{\"itemId\":"
                + "\"FX.USD.CNY.PBOC_MID\",\"businessDate\":\"2026-08-10\",\"value\":\"6.7904\",\"currency\":\"CNY\","
                + "\"unit\":\"CNY/1 USD\",\"providerType\":\"official_web\",\"actualSourceName\":\""
                + SOURCE_NAME + "\",\"accessMethod\":\"public_official_html\",\"normalizationVersion\":"
                + "\"pboc-standardization-v1\"},\"reasonCode\":null,\"validationVersion\":\"pboc-basic-validation-v1\","
                + "\"validatedAt\":\"2026-08-10T09:01+08:00\",\"publishedAt\":null,\"publishRef\":null,"
                + "\"updatedAt\":\"2026-08-10T09:01+08:00\"}]}\n")
                .getBytes(StandardCharsets.UTF_8);
        ManifestV1 corruptManifest = com.supplymind.foundation.storage.ManifestFactory.json(
                stagingRef, invalidStaging, List.of("run-daily-corrupt-001"), RECEIVED_AT);
        assertThrows(SchemaValidationException.class, () -> harness.fileStore().commit(
                        "daily-corrupt-" + System.nanoTime(),
                        com.supplymind.foundation.storage.DirtyTransactionType.SINGLE_FILE, RECEIVED_AT,
                        List.of(new com.supplymind.foundation.storage.FileTransactionTarget(
                                com.supplymind.foundation.storage.DirtyTargetRole.BUSINESS_FILE,
                                stagingRef, invalidStaging, JsonV1Codec.encodeFile(corruptManifest), false))),
                "a timeline whose PUBLISHED snapshot lacks publishedAt must fail closed at the storage boundary");
        assertFalse(Files.exists(harness.root().resolveDataRef(stagingRef)),
                "no illegal timeline may be persisted");
    }

    @Test
    void reprocessingIsIdempotentWithFixedClock() throws IOException {
        Harness harness = harness();
        RawReceiptV1 raw = pbocRaw("run-daily-idempotent-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingestAndPublish(harness, raw);

        DailyResult first = harness.daily().processMonth(raw.itemId(), MONTH_2026_08);
        Path path = harness.root().resolveDataRef(first.dailyRef());
        String hashAfterFirst = FileDigest.sha256(path);

        DailyResult replay = harness.daily().processMonth(raw.itemId(), MONTH_2026_08);

        assertEquals(first.rows(), replay.rows());
        assertEquals(hashAfterFirst, FileDigest.sha256(path),
                "reprocessing with identical inputs must produce identical bytes");
    }

    @Test
    void restartReaderReinitializesIndependentlyAndVerifiesCsvAndManifestFromDisk() throws IOException {
        Harness writerA = harness();
        RawReceiptV1 raw = pbocRaw("run-daily-restart-001", MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                "6.7904", "2026-08-10", 1);
        ingestAndPublish(writerA, raw);
        writerA.daily().processMonth(raw.itemId(), MONTH_2026_08);

        Harness readerB = harness();
        String dailyRef = DataPaths.dailyRef(raw.itemId(), MONTH_2026_08);
        Path dailyPath = readerB.root().resolveDataRef(dailyRef);
        Path manifestPath = readerB.root().resolveDataRef(DataPaths.manifestRef(dailyRef));
        assertTrue(Files.isRegularFile(dailyPath), "the daily CSV must exist on disk for the restarted reader");
        assertTrue(Files.isRegularFile(manifestPath), "the daily manifest must exist on disk for the restarted reader");

        byte[] csvBytes = Files.readAllBytes(dailyPath);
        ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        assertEquals(FileDigest.sha256(csvBytes), manifest.fileSha256(),
                "manifest fileSha256 must equal the actual CSV bytes");
        assertEquals(csvBytes.length, manifest.byteLength());
        assertEquals(1, manifest.rowCount());
        assertEquals("2026-08-10", manifest.minBusinessDate());
        assertEquals("2026-08-10", manifest.maxBusinessDate());
        assertEquals(List.of("run-daily-restart-001"), manifest.sourceRunIds());
        assertTrue(ManifestVerifier.matches(readerB.root(), dailyRef, dailyPath, manifestPath,
                List.of("run-daily-restart-001")));

        List<DailyRecordV1> decoded = CsvV1Codec.decodeDaily(csvBytes);
        assertEquals(1, decoded.size());
        DailyRecordV1 row = decoded.get(0);
        assertEquals("2026-08-10", row.businessDate());
        assertEquals(MonitorSeriesDefaults.USD_CNY_ITEM_ID, row.itemId());
        assertEquals(ProcessingStage.PUBLISHED, row.processingStage());
        assertEquals(ValidationStatus.VERIFIED, row.validationStatus());
        assertEquals("pboc-basic-validation-v1", row.validationVersion());
        assertEquals(List.of(1), row.configVersions());
        assertEquals("arithmetic-mean-v1", row.calculationVersion());
        assertEquals(8, row.calculationScale());
        assertEquals(4, row.displayScale());
        assertEquals(RoundingMode.HALF_UP, row.roundingMode());
        assertEquals("weekday-asia-shanghai-v1", row.calendarVersion());
        assertEquals("6.7904", row.sum());
        assertEquals(1, row.validCount());
        assertEquals("6.79040000", row.avg());
        assertEquals(1, row.expectedCount());
        assertEquals(0, row.missingCount());
        assertTrue(row.complete());
        assertEquals("CNY", row.currency());
        assertEquals("CNY/1 USD", row.unit());
        assertEquals(1, row.inputRefs().size());
        assertEquals("run-daily-restart-001", row.inputRefs().get(0).runId());
        assertEquals(raw.rawRef(), row.inputRefs().get(0).rawRef());
        assertEquals(4, row.inputRefs().get(0).recordVersion());
    }

    private void assertRow(
            DailyRecordV1 row,
            String businessDate,
            String sum,
            int validCount,
            String avg,
            int expectedCount,
            int missingCount,
            boolean complete,
            List<Integer> configVersions
    ) {
        assertEquals("1.0", row.schemaVersion());
        assertEquals(businessDate, row.businessDate());
        assertEquals(MonitorSeriesDefaults.USD_CNY_ITEM_ID, row.itemId());
        assertEquals(ProviderType.OFFICIAL_WEB, row.providerType());
        assertEquals(SOURCE_NAME, row.actualSourceName());
        assertEquals(AccessMethod.PUBLIC_OFFICIAL_HTML, row.accessMethod());
        assertEquals(ProcessingStage.PUBLISHED, row.processingStage());
        assertEquals(ValidationStatus.VERIFIED, row.validationStatus());
        assertEquals("pboc-basic-validation-v1", row.validationVersion());
        assertEquals(configVersions, row.configVersions());
        assertEquals("arithmetic-mean-v1", row.calculationVersion());
        assertEquals(8, row.calculationScale());
        assertEquals(4, row.displayScale());
        assertEquals(RoundingMode.HALF_UP, row.roundingMode());
        assertEquals("weekday-asia-shanghai-v1", row.calendarVersion());
        assertEquals(sum, row.sum());
        assertEquals(validCount, row.validCount());
        assertEquals(avg, row.avg());
        assertEquals(expectedCount, row.expectedCount());
        assertEquals(missingCount, row.missingCount());
        assertEquals(complete, row.complete());
        assertEquals("CNY", row.currency());
        assertEquals("CNY/1 USD", row.unit());
        assertEquals(validCount, row.inputRefs().size());
        for (DailyInputRefV1 inputRef : row.inputRefs()) {
            assertEquals(4, inputRef.recordVersion());
        }
        assertNotNull(row.updatedAt());
    }

    private Harness harness() {
        return harness(FIXED_CLOCK);
    }

    private Harness harness(Clock dailyClock) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t03 daily root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, FIXED_CLOCK);
        configStore.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, dailyClock);
        return new Harness(root, fileStore, configStore, rawStore, timelineStore, validation, publish, daily);
    }

    private static void ingest(Harness harness, RawReceiptV1 raw) {
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
    }

    private static void ingestAndPublish(Harness harness, RawReceiptV1 raw) {
        ingest(harness, raw);
        harness.validation().process(raw.runId());
        harness.publish().process(raw.runId());
    }

    private static void publishFixtureRun(Harness harness, RawReceiptV1 raw, ValidationStatus status) {
        ingest(harness, raw);
        CandidateV1 candidate = new PbocCandidateStandardizer().standardize(raw).candidate();
        OffsetDateTime at = RECEIVED_AT.plusMinutes(1);
        String reasonCode = status == ValidationStatus.VERIFIED ? null : "FIXTURE_DUPLICATE_OBSERVATION";
        harness.timelineStore().append(raw.runId(), new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null, null, null, null, null, at));
        harness.timelineStore().append(raw.runId(), new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, status, candidate, reasonCode, "pboc-basic-validation-v1", at, null, null, at));
        harness.timelineStore().append(raw.runId(), new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, status, candidate, reasonCode, "pboc-basic-validation-v1", at, at,
                "staging/" + raw.runId() + ".json#recordVersion=4", at));
    }

    private static MonitorSeriesConfigV1 configWith(int version, int calculationScale, int displayScale, String displayName) {
        MonitorSeriesConfigV1 v1 = MonitorSeriesDefaults.initialPboc(RECEIVED_AT);
        List<MonitorSeriesItemV1> items = new java.util.ArrayList<>();
        for (MonitorSeriesItemV1 item : v1.items()) {
            items.add(new MonitorSeriesItemV1(
                    item.itemId(),
                    displayName == null ? item.displayName() : displayName,
                    item.enabled(), item.sourceIntent(), item.providerType(),
                    item.accessMethod(), item.actualSourceName(), item.routeDecision(), item.fallbackReason(),
                    item.routeEffectiveAt(), item.supersedesItemId(), item.externalCode(), item.sourceFieldKey(),
                    item.rateKind(), item.calculationVersion(), calculationScale, displayScale,
                    item.roundingMode(), item.calendarVersion(), item.currency(), item.baseCurrency(), item.unit()));
        }
        return new MonitorSeriesConfigV1(SchemaV1.VERSION, version, Mode.FORMAL, RECEIVED_AT.plusHours(1), items);
    }

    private static RawReceiptV1 pbocRaw(String runId, String itemId, String value, String businessDate, int configVersion) {
        byte[] payload = ("test/contract fixture PBOC-shaped page - NOT REAL PBOC - " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB, itemId, RECEIVED_AT, runId),
                "acq-" + runId,
                runId,
                Mode.FORMAL,
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                configVersion,
                SOURCE_NAME,
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/2026081009013821880/index.html",
                "PBOC公告列表=https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html;公告标题=test fixture",
                itemId,
                businessDate,
                businessDate,
                "2026-08-10 09:25:38",
                PUBLISHED_AT,
                RECEIVED_AT,
                null,
                value,
                "CNY/1 USD",
                "CNY",
                null,
                200,
                "text/html",
                "base64",
                Base64.getEncoder().encodeToString(payload),
                JsonV1Codec.sha256LowerHex(payload),
                "1美元对人民币",
                RECEIVED_AT
        );
    }

    private static DailyInput dailyInput(
            String runId,
            String value,
            String businessDate,
            ValidationStatus status,
            String publishedAt
    ) {
        String rawRef = "raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/" + runId + ".json";
        return new DailyInput(
                MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                businessDate,
                value,
                "CNY",
                "CNY/1 USD",
                ProviderType.OFFICIAL_WEB,
                SOURCE_NAME,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                status,
                "pboc-basic-validation-v1",
                1,
                runId,
                rawRef,
                4,
                "arithmetic-mean-v1",
                8,
                4,
                RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1",
                OffsetDateTime.parse(publishedAt));
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                DailyProcessingServiceTest.class.getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D2-T03 contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore fileStore,
            ConfigActivationStore configStore,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily
    ) {
    }
}
