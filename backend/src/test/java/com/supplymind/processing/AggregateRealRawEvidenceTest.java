package com.supplymind.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateRealRawEvidenceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-09T14:50:00Z"), SHANGHAI);
    private static final String REAL_PAGE_SHA256 =
            "f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82";

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "d2-t04.real-raw", matches = "true")
    void computesRealFourGrainAggregatesFromTheRealD1T05PbocRaw() throws IOException {
        String sourceValue = System.getProperty("d2-t04.source-data-root");
        assertNotNull(sourceValue, "an explicit absolute d2-t04.source-data-root is required for the real raw evidence run");
        Path sourceRoot = Path.of(sourceValue).toAbsolutePath().normalize();
        assertTrue(Path.of(sourceValue).isAbsolute(), "the real raw source dataRoot must be absolute");

        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t04 real raw aggregate root"));
        AtomicMoveSupport.probeOrFail(root);
        copyTree(sourceRoot.resolve("config"), root.path().resolve("config"));
        copyTree(sourceRoot.resolve("raw"), root.path().resolve("raw"));
        copyTree(sourceRoot.resolve("staging"), root.path().resolve("staging"));
        List<String> runIds = stagingRunIds(root);
        assertEquals(2, runIds.size(), "the D1-T05 real raw root must carry both USD and EUR runs");

        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, FIXED_CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, FIXED_CLOCK);

        for (String runId : runIds) {
            validation.process(runId);
            publish.process(runId);
        }
        daily.processMonth("FX.USD.CNY.PBOC_MID", java.time.YearMonth.of(2026, 8));
        daily.processMonth("FX.EUR.CNY.PBOC_MID", java.time.YearMonth.of(2026, 8));
        aggregate.processYear("FX.USD.CNY.PBOC_MID", 2026);
        aggregate.processYear("FX.EUR.CNY.PBOC_MID", 2026);

        Map<String, Object> results = new LinkedHashMap<>();
        for (String itemId : List.of("FX.USD.CNY.PBOC_MID", "FX.EUR.CNY.PBOC_MID")) {
            Map<String, Object> grainResults = new LinkedHashMap<>();
            for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                    AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
                String ref = DataPaths.aggregateRef(itemId, grain.wireValue(), 2026);
                Path csvPath = root.resolveDataRef(ref);
                Path manifestPath = root.resolveDataRef(DataPaths.manifestRef(ref));
                assertTrue(Files.isRegularFile(csvPath), ref + " must exist");
                assertTrue(Files.isRegularFile(manifestPath));
                byte[] csvBytes = Files.readAllBytes(csvPath);
                ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
                assertEquals(FileDigest.sha256(csvBytes), manifest.fileSha256());
                assertTrue(ManifestVerifier.matches(root, ref, csvPath, manifestPath));
                List<AggregateRecordV1> rows = CsvV1Codec.decodeAggregate(csvBytes);
                assertEquals(1, rows.size(), ref + " must carry exactly one row for the real single daily input");
                AggregateRecordV1 row = rows.get(0);
                assertEquals(ValidationStatus.VERIFIED, row.validationStatus());
                assertEquals(1, row.validCount());
                assertEquals("2026-08-07", row.inputRefs().get(0).businessDate());
                assertEquals("pboc-basic-validation-v1", row.inputRefs().get(0).validationVersion());
                assertEquals("processed/daily/" + itemId + "/2026-08.csv", row.inputRefs().get(0).dailyFileRef());
                assertEquals("2026-08-09T22:50+08:00", row.calculatedAt().toString(),
                        "calculatedAt must equal max(daily.updatedAt) of the real participating daily row");
                grainResults.put(grain.wireValue(), Map.of(
                        "sum", row.sum(),
                        "avg", row.avg(),
                        "min", row.min(),
                        "max", row.max(),
                        "validCount", row.validCount(),
                        "expectedCount", row.expectedCount(),
                        "missingCount", row.missingCount(),
                        "complete", row.complete(),
                        "calculatedAt", row.calculatedAt().toString(),
                        "inputRefs", row.inputRefs().size()));
                System.out.printf("D2T04_REAL_AGG itemId=%s grain=%s sum=%s avg=%s min=%s max=%s expected=%d missing=%d calculatedAt=%s%n",
                        itemId, grain.wireValue(), row.sum(), row.avg(), row.min(), row.max(),
                        row.expectedCount(), row.missingCount(), row.calculatedAt());
            }
            results.put(itemId, grainResults);
        }

        DataRoot readerRoot = DataRoot.forTest(root.path());
        AggregateReadService reader = new AggregateReadService(readerRoot);
        Map<String, String> aggregateHashesBefore = new java.util.TreeMap<>();
        for (String itemId : List.of("FX.USD.CNY.PBOC_MID", "FX.EUR.CNY.PBOC_MID")) {
            for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                    AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
                String ref = DataPaths.aggregateRef(itemId, grain.wireValue(), 2026);
                aggregateHashesBefore.put(ref, FileDigest.sha256(root.resolveDataRef(ref)));
            }
        }

        String expectedUsdSum = "6.79040000";
        String expectedEurSum = "7.80670000";
        for (String itemId : List.of("FX.USD.CNY.PBOC_MID", "FX.EUR.CNY.PBOC_MID")) {
            String expectedSum = itemId.startsWith("FX.USD") ? expectedUsdSum : expectedEurSum;
            for (AggregateGrain grain : List.of(AggregateGrain.MONTH, AggregateGrain.QUARTER,
                    AggregateGrain.HALFYEAR, AggregateGrain.YEAR)) {
                AggregateReadService.AggregateFile file = reader.read(itemId, grain, 2026);
                assertNotNull(file, "restart reader must discover the persisted " + grain.wireValue() + " CSV");
                assertEquals(ManifestV1.COMMITTED, file.manifest().commitState());
                assertEquals(FileDigest.sha256(file.csvBytes()), file.manifest().fileSha256());
                assertEquals(file.csvBytes().length, file.manifest().byteLength());
                assertEquals(1, file.rows().size());
                assertEquals(expectedSum, file.rows().get(0).sum());
                assertEquals(1, file.rows().get(0).validCount());
                assertEquals(expectedSum, file.rows().get(0).avg());
                assertEquals(expectedSum, file.rows().get(0).min());
                assertEquals(expectedSum, file.rows().get(0).max());
                assertEquals("2026-08-09T22:50+08:00", file.rows().get(0).calculatedAt().toString(),
                        "restart reader must read the identical calculatedAt without any rebuild");
                assertEquals(aggregateHashesBefore.get(file.ref()), FileDigest.sha256(root.resolveDataRef(file.ref())),
                        "the read-only restart reader must never rewrite the persisted aggregate CSV");
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gate", "D2-T04 real D1-T05 PBOC four-grain aggregate evidence");
        summary.put("executedAt", OffsetDateTime.now(FIXED_CLOCK).toString());
        summary.put("javaRuntime", System.getProperty("java.version"));
        summary.put("springBootVersion", SpringBootVersion.getVersion());
        summary.put("evidenceDataRoot", root.path().toString());
        summary.put("sourceDataRoot", sourceRoot.toString());
        summary.put("realPageSha256", REAL_PAGE_SHA256);
        summary.put("chain", "real raw -> validation -> publish -> daily -> aggregate (month/quarter/halfyear/year)");
        summary.put("restartReaderOutcome", "PASS");
        summary.put("results", results);

        String evidenceDirValue = System.getProperty("d2-t04.evidence-dir");
        if (evidenceDirValue != null && !evidenceDirValue.isBlank()) {
            Path evidenceDir = Path.of(evidenceDirValue).toAbsolutePath().normalize();
            Files.createDirectories(evidenceDir);
            ObjectMapper mapper = JsonV1Codec.mapper();
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(evidenceDir.resolve("d2-t04-real-aggregate-summary.json"), bytes);
        }
    }

    private static List<String> stagingRunIds(DataRoot root) throws IOException {
        Path stagingDir = root.resolveInternalRelative("staging");
        List<String> runIds = new ArrayList<>();
        try (Stream<Path> files = Files.list(stagingDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> runIds.add(path.getFileName().toString()
                            .substring(0, path.getFileName().toString().length() - ".json".length())));
        }
        return List.copyOf(runIds);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                Path relative = source.relativize(file);
                Path destination = target.resolve(relative.toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }
    }
}
