package com.supplymind.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.ProcessingStage;
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
import java.time.YearMonth;
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

class DailyRealRawEvidenceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-09T14:50:00Z"), SHANGHAI);
    private static final String REAL_PAGE_SHA256 =
            "f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82";

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "d2-t03.real-raw", matches = "true")
    void computesDailyCsvFromTheRealD1T05PbocRaw() throws IOException {
        String sourceValue = System.getProperty("d2-t03.source-data-root");
        assertNotNull(sourceValue, "an explicit absolute d2-t03.source-data-root is required for the real raw evidence run");
        Path sourceRoot = Path.of(sourceValue).toAbsolutePath().normalize();
        assertTrue(Path.of(sourceValue).isAbsolute(), "the real raw source dataRoot must be absolute");

        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t03 real raw daily root"));
        AtomicMoveSupport.probeOrFail(root);
        copyTree(sourceRoot.resolve("config"), root.path().resolve("config"));
        copyTree(sourceRoot.resolve("raw"), root.path().resolve("raw"));
        copyTree(sourceRoot.resolve("staging"), root.path().resolve("staging"));
        List<String> runIds = stagingRunIds(root);
        assertEquals(2, runIds.size(), "the D1-T05 real raw root must carry both USD and EUR runs");

        Map<String, String> rawHashesBefore = new LinkedHashMap<>();
        DataRoot sourceDataRoot = DataRoot.forTest(sourceRoot);
        for (String runId : runIds) {
            String rawRef = decodeRaw(root, decodeTimeline(root, runId).rawRef()).rawRef();
            assertEquals(FileDigest.sha256(sourceDataRoot.resolveDataRef(rawRef)),
                    FileDigest.sha256(root.resolveDataRef(rawRef)),
                    "the copied raw must be byte-identical to the D1-T05 real raw");
            rawHashesBefore.put(runId, FileDigest.sha256(root.resolveDataRef(rawRef)));
        }

        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, FIXED_CLOCK);

        Map<String, Object> results = new LinkedHashMap<>();
        for (String runId : runIds) {
            validation.process(runId);
            publish.process(runId);
            assertEquals(FileDigest.sha256(root.resolveDataRef(decodeTimeline(root, runId).rawRef())),
                    rawHashesBefore.get(runId), "the real raw file must stay byte-identical");
        }

        DailyResult usd = daily.processMonth("FX.USD.CNY.PBOC_MID", YearMonth.of(2026, 8));
        DailyResult eur = daily.processMonth("FX.EUR.CNY.PBOC_MID", YearMonth.of(2026, 8));
        assertEquals(1, usd.rows().size());
        assertEquals(1, eur.rows().size());
        DailyRecordV1 usdRow = usd.rows().get(0);
        DailyRecordV1 eurRow = eur.rows().get(0);
        assertEquals("6.7904", usdRow.sum());
        assertEquals("6.79040000", usdRow.avg());
        assertEquals("7.8067", eurRow.sum());
        assertEquals("7.80670000", eurRow.avg());
        assertEquals("2026-08-07", usdRow.businessDate());
        assertEquals(ProcessingStage.PUBLISHED, usdRow.processingStage());
        assertEquals(ValidationStatus.VERIFIED, usdRow.validationStatus());
        assertEquals("pboc-basic-validation-v1", usdRow.validationVersion());
        assertEquals(List.of(1), usdRow.configVersions());
        assertEquals(1, usdRow.inputRefs().size());
        assertTrue(usdRow.inputRefs().get(0).runId().startsWith("pboc-usd-20260807-"),
                "the USD daily inputRef must point at the real USD run");
        assertTrue(usdRow.inputRefs().get(0).rawRef().startsWith("raw/formal/official_web/FX.USD.CNY.PBOC_MID/2026/08/"));

        assertTrue(Files.isRegularFile(root.resolveDataRef(usd.dailyRef())));
        assertTrue(Files.isRegularFile(root.resolveDataRef(eur.dailyRef())));
        assertTrue(ManifestVerifier.matches(root, usd.dailyRef(),
                root.resolveDataRef(usd.dailyRef()),
                root.resolveDataRef(DataPaths.manifestRef(usd.dailyRef())), usdRow.inputRefs().stream()
                        .map(ref -> ref.runId()).toList()));
        ManifestV1 usdManifest = JsonV1Codec.decodeFile(
                Files.readAllBytes(root.resolveDataRef(DataPaths.manifestRef(usd.dailyRef()))), ManifestV1.class);
        assertEquals(1, usdManifest.rowCount());
        assertEquals("2026-08-07", usdManifest.minBusinessDate());
        assertEquals("2026-08-07", usdManifest.maxBusinessDate());
        assertEquals(1, usdManifest.sourceRunIds().size());
        assertTrue(usdManifest.sourceRunIds().get(0).startsWith("pboc-usd-20260807-"));

        List<DailyRecordV1> decodedUsd = CsvV1Codec.decodeDaily(
                Files.readAllBytes(root.resolveDataRef(usd.dailyRef())));
        assertEquals(usd.rows(), decodedUsd, "restart read must decode the persisted daily CSV identically");
        assertEquals("CNY/1 USD", decodedUsd.get(0).unit());
        assertEquals("CNY", decodedUsd.get(0).currency());

        DataRoot readerRoot = DataRoot.forTest(root.path());
        AtomicFileStore readerFileStore = new AtomicFileStore(readerRoot, new DirtyMarkerCodec());
        TimelineStore readerTimeline = new TimelineStore(readerRoot, readerFileStore, FIXED_CLOCK);
        DailyProcessingService readerDaily =
                new DailyProcessingService(readerRoot, readerTimeline, readerFileStore, FIXED_CLOCK);
        DailyResult usdReRead = readerDaily.processMonth("FX.USD.CNY.PBOC_MID", YearMonth.of(2026, 8));
        DailyResult eurReRead = readerDaily.processMonth("FX.EUR.CNY.PBOC_MID", YearMonth.of(2026, 8));
        assertEquals(usd.rows(), usdReRead.rows(),
                "the restarted reader must recompute identical rows from disk state");
        assertEquals(eur.rows(), eurReRead.rows(),
                "the restarted reader must recompute identical rows from disk state");
        byte[] usdBytesReRead = Files.readAllBytes(readerRoot.resolveDataRef(usdReRead.dailyRef()));
        ManifestV1 usdManifestReRead = JsonV1Codec.decodeFile(
                Files.readAllBytes(readerRoot.resolveDataRef(DataPaths.manifestRef(usdReRead.dailyRef()))),
                ManifestV1.class);
        assertEquals(FileDigest.sha256(usdBytesReRead), usdManifestReRead.fileSha256());
        assertEquals(usdBytesReRead.length, usdManifestReRead.byteLength());
        assertEquals(1, usdManifestReRead.rowCount());
        assertEquals("2026-08-07", usdManifestReRead.minBusinessDate());
        assertEquals("2026-08-07", usdManifestReRead.maxBusinessDate());
        assertTrue(ManifestVerifier.matches(readerRoot, usdReRead.dailyRef(),
                readerRoot.resolveDataRef(usdReRead.dailyRef()),
                readerRoot.resolveDataRef(DataPaths.manifestRef(usdReRead.dailyRef())),
                List.of(usdRow.inputRefs().get(0).runId())));
        assertEquals(usd.rows(), CsvV1Codec.decodeDaily(usdBytesReRead),
                "the restarted reader must decode the persisted CSV identically");

        results.put("usd", Map.of(
                "businessDate", usdRow.businessDate(),
                "sum", usdRow.sum(),
                "avg", usdRow.avg(),
                "validCount", usdRow.validCount(),
                "dailyRef", usd.dailyRef(),
                "avgScale", usdRow.avg().split("\\.")[1].length(),
                "restartReaderRecomputeIdentical", usd.rows().equals(usdReRead.rows())));
        results.put("eur", Map.of(
                "businessDate", eurRow.businessDate(),
                "sum", eurRow.sum(),
                "avg", eurRow.avg(),
                "validCount", eurRow.validCount(),
                "dailyRef", eur.dailyRef(),
                "avgScale", eurRow.avg().split("\\.")[1].length(),
                "restartReaderRecomputeIdentical", eur.rows().equals(eurReRead.rows())));

        System.out.printf("D2T03_REAL_RAW usd sum=%s avg=%s dailyRef=%s%n",
                usdRow.sum(), usdRow.avg(), usd.dailyRef());
        System.out.printf("D2T03_REAL_RAW eur sum=%s avg=%s dailyRef=%s%n",
                eurRow.sum(), eurRow.avg(), eur.dailyRef());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gate", "D2-T03 real D1-T05 raw daily CSV evidence");
        summary.put("executedAt", OffsetDateTime.now(FIXED_CLOCK).toString());
        summary.put("javaRuntime", System.getProperty("java.version"));
        summary.put("springBootVersion", SpringBootVersion.getVersion());
        summary.put("evidenceDataRoot", root.path().toString());
        summary.put("sourceDataRoot", sourceRoot.toString());
        summary.put("realPageSha256", REAL_PAGE_SHA256);
        summary.put("sourceRawCopiedByteIdentical", true);
        summary.put("rawFilesUnchangedAfterProcessing", true);
        summary.put("restartReadOutcome", "PASS");
        summary.put("results", results);

        String evidenceDirValue = System.getProperty("d2-t03.evidence-dir");
        if (evidenceDirValue != null && !evidenceDirValue.isBlank()) {
            Path evidenceDir = Path.of(evidenceDirValue).toAbsolutePath().normalize();
            Files.createDirectories(evidenceDir);
            ObjectMapper mapper = JsonV1Codec.mapper();
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(evidenceDir.resolve("d2-t03-real-raw-daily-summary.json"), bytes);
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

    private static com.supplymind.foundation.model.LifecycleTimelineV1 decodeTimeline(DataRoot root, String runId)
            throws IOException {
        return JsonV1Codec.decodeFile(
                Files.readAllBytes(root.resolveDataRef(DataPaths.stagingRef(runId))),
                com.supplymind.foundation.model.LifecycleTimelineV1.class);
    }

    private static com.supplymind.foundation.model.RawReceiptV1 decodeRaw(
            DataRoot root, String rawRef) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(rawRef)),
                com.supplymind.foundation.model.RawReceiptV1.class);
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
