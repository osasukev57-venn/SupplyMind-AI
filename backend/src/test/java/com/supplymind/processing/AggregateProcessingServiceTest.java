package com.supplymind.processing;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.QualityStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateProcessingServiceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final String ITEM = "FX.USD.CNY.PBOC_MID";
    private static final String FIXTURE_ROOT = "contracts/v1/valid/";

    @TempDir
    Path temporaryDirectory;

    @Test
    void monthAggregationMatchesStaticGoldenBytes() throws IOException {
        Harness harness = harness();
        installDailyFixture(harness, "2026-08", "daily-pboc-v1.csv",
                "aggregate-daily-input-2026-08.csv.manifest.json");

        String ref = harness.aggregate().processGrain(ITEM, AggregateGrain.MONTH, 2026).get(0);

        assertEquals("processed/aggregate/FX.USD.CNY.PBOC_MID/month/2026.csv", ref);
        byte[] golden = fixtureBytes("aggregate-month-pboc-2026-08.csv");
        Path aggregatePath = harness.root().resolveDataRef(ref);
        assertArrayEquals(golden, Files.readAllBytes(aggregatePath),
                "the persisted aggregate CSV must match the hand-authored golden bytes");
        ManifestV1 manifest = JsonV1Codec.decodeFile(
                Files.readAllBytes(harness.root().resolveDataRef(DataPaths.manifestRef(ref))), ManifestV1.class);
        assertEquals(FileDigest.sha256(aggregatePath), manifest.fileSha256());
        assertEquals(Files.size(aggregatePath), manifest.byteLength());
        assertEquals(1, manifest.rowCount());
        assertEquals("2026-08-01", manifest.minBusinessDate());
        assertEquals("2026-08-31", manifest.maxBusinessDate());
        assertEquals(List.of("run-daily-golden-001"), manifest.sourceRunIds());
        assertTrue(ManifestVerifier.matches(harness.root(), ref, aggregatePath,
                harness.root().resolveDataRef(DataPaths.manifestRef(ref))));
        List<AggregateRecordV1> decoded = CsvV1Codec.decodeAggregate(Files.readAllBytes(aggregatePath));
        assertEquals(1, decoded.size());
        AggregateRecordV1 row = decoded.get(0);
        assertEquals("6.79040000", row.sum());
        assertEquals(1, row.validCount());
        assertEquals("6.79040000", row.avg());
        assertEquals(21, row.expectedCount());
        assertEquals(20, row.missingCount());
        assertFalse(row.complete());
        assertEquals(QualityStatus.INCOMPLETE, row.qualityStatus());
        assertEquals("2026-08-10T09:02+08:00", row.calculatedAt().toString(),
                "calculatedAt must equal max(daily.updatedAt) of the participating daily rows");
    }

    @Test
    void fourGrainsAreBuiltDirectlyFromDailyWithHandComputedValues() throws IOException {
        Harness harness = harness();
        installDailyFixture(harness, "2026-01", "aggregate-daily-input-2026-01.csv",
                "aggregate-daily-input-2026-01.csv.manifest.json");
        installDailyFixture(harness, "2026-02", "aggregate-daily-input-2026-02.csv",
                "aggregate-daily-input-2026-02.csv.manifest.json");

        AggregateProcessingService.AggregateYearResult result = harness.aggregate().processYear(ITEM, 2026);

        assertEquals(4, result.writtenRefs().size(), "one file per grain (month/quarter/halfyear/year)");
        assertTrue(result.writtenRefs().contains("processed/aggregate/FX.USD.CNY.PBOC_MID/month/2026.csv"));
        assertTrue(result.writtenRefs().contains("processed/aggregate/FX.USD.CNY.PBOC_MID/quarter/2026.csv"));
        assertTrue(result.writtenRefs().contains("processed/aggregate/FX.USD.CNY.PBOC_MID/halfyear/2026.csv"));
        assertTrue(result.writtenRefs().contains("processed/aggregate/FX.USD.CNY.PBOC_MID/year/2026.csv"));

        List<AggregateRecordV1> january = decodeMonth(harness.root(), "2026-01");
        assertEquals(1, january.size());
        assertEquals("13.59040000", january.get(0).sum());
        assertEquals(2, january.get(0).validCount());
        assertEquals("6.79520000", january.get(0).avg());
        assertEquals("6.79040000", january.get(0).min());
        assertEquals("6.80000000", january.get(0).max());
        assertEquals(22, january.get(0).expectedCount());
        assertEquals(20, january.get(0).missingCount());
        assertEquals("2026-01-06T09:30+08:00", january.get(0).calculatedAt().toString());

        List<AggregateRecordV1> february = decodeMonth(harness.root(), "2026-02");
        assertEquals("6.90000000", february.get(0).sum());
        assertEquals(20, february.get(0).expectedCount());
        assertEquals("2026-02-02T10:00+08:00", february.get(0).calculatedAt().toString());

        List<AggregateRecordV1> quarter = decodeAggregate(harness.root(), AggregateGrain.QUARTER, 2026);
        assertEquals(1, quarter.size());
        assertEquals("20.49040000", quarter.get(0).sum());
        assertEquals(3, quarter.get(0).validCount());
        assertEquals("6.83013333", quarter.get(0).avg());
        assertEquals("6.79040000", quarter.get(0).min());
        assertEquals("6.90000000", quarter.get(0).max());
        assertEquals(64, quarter.get(0).expectedCount());
        assertEquals(61, quarter.get(0).missingCount());
        assertEquals("2026-02-02T10:00+08:00", quarter.get(0).calculatedAt().toString(),
                "quarter must take its own max(daily.updatedAt) directly from daily");

        List<AggregateRecordV1> halfyear = decodeAggregate(harness.root(), AggregateGrain.HALFYEAR, 2026);
        assertEquals("20.49040000", halfyear.get(0).sum());
        assertEquals(129, halfyear.get(0).expectedCount());
        assertEquals(3, halfyear.get(0).validCount());
        assertEquals("2026-02-02T10:00+08:00", halfyear.get(0).calculatedAt().toString());

        List<AggregateRecordV1> year = decodeAggregate(harness.root(), AggregateGrain.YEAR, 2026);
        assertEquals("20.49040000", year.get(0).sum());
        assertEquals(261, year.get(0).expectedCount());
        assertEquals(3, year.get(0).validCount());
        assertEquals("2026-02-02T10:00+08:00", year.get(0).calculatedAt().toString());
    }

    @Test
    void quarterReadsMultipleMonthlyDailyFiles() throws IOException {
        Harness harness = harness();
        installDailyFixture(harness, "2026-01", "aggregate-daily-input-2026-01.csv",
                "aggregate-daily-input-2026-01.csv.manifest.json");
        installDailyFixture(harness, "2026-02", "aggregate-daily-input-2026-02.csv",
                "aggregate-daily-input-2026-02.csv.manifest.json");
        assertTrue(ManifestVerifier.matches(harness.root(),
                DataPaths.dailyRef(ITEM, YearMonth.of(2026, 1)),
                harness.root().resolveDataRef(DataPaths.dailyRef(ITEM, YearMonth.of(2026, 1))),
                harness.root().resolveDataRef(DataPaths.manifestRef(DataPaths.dailyRef(ITEM, YearMonth.of(2026, 1))))));
        assertFalse(Files.exists(harness.root().resolveDataRef(
                DataPaths.dailyRef(ITEM, YearMonth.of(2026, 3)))));

        harness.aggregate().processGrain(ITEM, AggregateGrain.QUARTER, 2026);
        List<AggregateRecordV1> quarter = decodeAggregate(harness.root(), AggregateGrain.QUARTER, 2026);

        assertEquals(1, quarter.size());
        assertEquals(3, quarter.get(0).validCount(),
                "the quarter must merge the 2026-01 and 2026-02 daily files");
        assertEquals(2, quarter.get(0).inputRefs().stream()
                .map(ref -> ref.dailyFileRef()).distinct().count(),
                "inputRefs must cover daily rows from multiple monthly files");
    }

    @Test
    void fourGrainsAreDeterministicAcrossProcessingClocks() throws IOException {
        Clock clockA = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), SHANGHAI);
        Clock clockB = Clock.fixed(Instant.parse("2026-08-10T10:30:00Z"), SHANGHAI);
        Harness writerA = harness(clockA);
        installDailyFixture(writerA, "2026-01", "aggregate-daily-input-2026-01.csv",
                "aggregate-daily-input-2026-01.csv.manifest.json");
        installDailyFixture(writerA, "2026-02", "aggregate-daily-input-2026-02.csv",
                "aggregate-daily-input-2026-02.csv.manifest.json");
        Harness writerB = harness(clockB);
        installDailyFixture(writerB, "2026-01", "aggregate-daily-input-2026-01.csv",
                "aggregate-daily-input-2026-01.csv.manifest.json");
        installDailyFixture(writerB, "2026-02", "aggregate-daily-input-2026-02.csv",
                "aggregate-daily-input-2026-02.csv.manifest.json");

        writerA.aggregate().processYear(ITEM, 2026);
        writerB.aggregate().processYear(ITEM, 2026);

        for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
            String ref = DataPaths.aggregateRef(ITEM, grain.wireValue(), 2026);
            byte[] bytesA = Files.readAllBytes(writerA.root().resolveDataRef(ref));
            byte[] bytesB = Files.readAllBytes(writerB.root().resolveDataRef(ref));
            assertArrayEquals(bytesA, bytesB,
                    grain.wireValue() + " CSV bytes must be identical across processing clocks");
            assertEquals(FileDigest.sha256(bytesA), FileDigest.sha256(bytesB),
                    grain.wireValue() + " CSV SHA-256 must be identical across processing clocks");
            List<AggregateRecordV1> rowsA = CsvV1Codec.decodeAggregate(bytesA);
            List<AggregateRecordV1> rowsB = CsvV1Codec.decodeAggregate(bytesB);
            assertEquals(rowsA, rowsB);
            for (int index = 0; index < rowsA.size(); index++) {
                assertEquals(rowsA.get(index).calculatedAt(), rowsB.get(index).calculatedAt(),
                        grain.wireValue() + " calculatedAt must be identical across processing clocks");
            }

            ManifestV1 manifestA = JsonV1Codec.decodeFile(
                    Files.readAllBytes(writerA.root().resolveDataRef(DataPaths.manifestRef(ref))), ManifestV1.class);
            ManifestV1 manifestB = JsonV1Codec.decodeFile(
                    Files.readAllBytes(writerB.root().resolveDataRef(DataPaths.manifestRef(ref))), ManifestV1.class);
            assertEquals(manifestA.fileSha256(), manifestB.fileSha256(),
                    grain.wireValue() + " manifest fileSha256 must be identical");
            assertEquals(manifestA.byteLength(), manifestB.byteLength());
            assertEquals(manifestA.rowCount(), manifestB.rowCount());
            assertEquals(manifestA.sourceRunIds(), manifestB.sourceRunIds());
            assertFalse(manifestA.generatedAt().equals(manifestB.generatedAt()),
                    grain.wireValue() + " manifest.generatedAt may legitimately differ across clocks");
        }
    }

    @Test
    void restartReaderOnlyReadsPersistedAggregatesWithoutAnyRebuild() throws IOException {
        Harness writerA = harness();
        installDailyFixture(writerA, "2026-01", "aggregate-daily-input-2026-01.csv",
                "aggregate-daily-input-2026-01.csv.manifest.json");
        installDailyFixture(writerA, "2026-02", "aggregate-daily-input-2026-02.csv",
                "aggregate-daily-input-2026-02.csv.manifest.json");
        writerA.aggregate().processYear(ITEM, 2026);

        Map<String, String> aggregateHashesBefore = new java.util.TreeMap<>();
        for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
            String ref = DataPaths.aggregateRef(ITEM, grain.wireValue(), 2026);
            aggregateHashesBefore.put(ref, FileDigest.sha256(writerA.root().resolveDataRef(ref)));
        }

        DataRoot readerRoot = DataRoot.forTest(writerA.root().path());
        AggregateReadService reader = new AggregateReadService(readerRoot);

        String expectedMonthSum = "13.59040000";
        String expectedHighSum = "20.49040000";
        for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
            AggregateReadService.AggregateFile file = reader.read(ITEM, grain, 2026);
            assertNotNull(file, grain.wireValue() + " must be discoverable by the read-only reader");
            assertEquals("processed/aggregate/FX.USD.CNY.PBOC_MID/" + grain.wireValue() + "/2026.csv", file.ref());
            assertEquals(ManifestV1.COMMITTED, file.manifest().commitState());
            assertEquals(FileDigest.sha256(file.csvBytes()), file.manifest().fileSha256());
            assertEquals(file.csvBytes().length, file.manifest().byteLength());
            if (grain == AggregateGrain.MONTH) {
                assertEquals(2, file.manifest().rowCount());
                assertEquals("2026-01-01", file.manifest().minBusinessDate());
                assertEquals("2026-02-28", file.manifest().maxBusinessDate());
            } else if (grain == AggregateGrain.QUARTER) {
                assertEquals(1, file.manifest().rowCount());
                assertEquals("2026-01-01", file.manifest().minBusinessDate());
                assertEquals("2026-03-31", file.manifest().maxBusinessDate());
            } else if (grain == AggregateGrain.HALFYEAR) {
                assertEquals(1, file.manifest().rowCount());
                assertEquals("2026-01-01", file.manifest().minBusinessDate());
                assertEquals("2026-06-30", file.manifest().maxBusinessDate());
            } else {
                assertEquals(1, file.manifest().rowCount());
                assertEquals("2026-01-01", file.manifest().minBusinessDate());
                assertEquals("2026-12-31", file.manifest().maxBusinessDate());
            }
            assertEquals(grain == AggregateGrain.MONTH ? 2 : 1, file.rows().size(),
                    grain.wireValue() + " row count");
            AggregateRecordV1 row = file.rows().get(0);
            assertEquals(grain == AggregateGrain.MONTH ? expectedMonthSum : expectedHighSum, row.sum());
            assertEquals(grain == AggregateGrain.MONTH ? 2 : 3, row.validCount());
            assertEquals(grain == AggregateGrain.MONTH
                    ? "2026-01-06T09:30+08:00" : "2026-02-02T10:00+08:00", row.calculatedAt().toString());
            assertEquals(aggregateHashesBefore.get(file.ref()), FileDigest.sha256(
                    readerRoot.resolveDataRef(file.ref())),
                    grain.wireValue() + " CSV bytes must be untouched by the read-only reader");
        }
    }

    @Test
    void emptyPeriodProducesNoAggregateFile() throws IOException {
        Harness harness = harness();
        var refs = harness.aggregate().processGrain(ITEM, AggregateGrain.MONTH, 2026);
        assertTrue(refs.isEmpty());
        assertFalse(Files.exists(harness.root().path().resolve(
                "processed/aggregate/FX.USD.CNY.PBOC_MID/month/2026.csv")));
    }

    private List<AggregateRecordV1> decodeMonth(DataRoot root, String month) throws IOException {
        String ref = DataPaths.aggregateRef(ITEM, "month", 2026);
        return CsvV1Codec.decodeAggregate(Files.readAllBytes(root.resolveDataRef(ref))).stream()
                .filter(row -> row.periodStart().equals(month + "-01"))
                .toList();
    }

    private List<AggregateRecordV1> decodeAggregate(DataRoot root, AggregateGrain grain, int year) throws IOException {
        String ref = DataPaths.aggregateRef(ITEM, grain.wireValue(), year);
        return CsvV1Codec.decodeAggregate(Files.readAllBytes(root.resolveDataRef(ref)));
    }

    private Harness harness() {
        return harness(FIXED_CLOCK);
    }

    private Harness harness(Clock aggregateClock) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t04 aggregate root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, aggregateClock);
        return new Harness(root, fileStore, aggregate);
    }

    private void installDailyFixture(Harness harness, String month, String csvFixture, String manifestFixture)
            throws IOException {
        String dailyRef = DataPaths.dailyRef(ITEM, YearMonth.parse(month));
        Path dailyPath = harness.root().resolveDataRef(dailyRef);
        Files.createDirectories(dailyPath.getParent());
        byte[] csvBytes = fixtureBytes(csvFixture);
        Files.write(dailyPath, csvBytes);
        byte[] manifestBytes = fixtureBytes(manifestFixture);
        Files.write(harness.root().resolveDataRef(DataPaths.manifestRef(dailyRef)), manifestBytes);
        CsvV1Codec.decodeDaily(csvBytes);
        JsonV1Codec.decodeFile(manifestBytes, ManifestV1.class);
        assertTrue(ManifestVerifier.matches(harness.root(), dailyRef, dailyPath,
                harness.root().resolveDataRef(DataPaths.manifestRef(dailyRef))));
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                AggregateProcessingServiceTest.class.getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D2-T04 contract fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private record Harness(DataRoot root, AtomicFileStore fileStore, AggregateProcessingService aggregate) {
    }
}
