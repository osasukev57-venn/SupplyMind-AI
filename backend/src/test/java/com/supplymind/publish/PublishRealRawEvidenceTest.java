package com.supplymind.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.ValidationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishRealRawEvidenceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-09T14:50:00Z"), SHANGHAI);
    private static final String REAL_PAGE_SHA256 =
            "f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82";

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "d2-t02.real-raw", matches = "true")
    void publishesTheRealD1T05PbocRawAndExposesItThroughTheBusinessEntry() throws IOException {
        String sourceValue = System.getProperty("d2-t02.source-data-root");
        assertNotNull(sourceValue, "an explicit absolute d2-t02.source-data-root is required for the real raw evidence run");
        Path sourceRoot = Path.of(sourceValue).toAbsolutePath().normalize();
        assertTrue(Path.of(sourceValue).isAbsolute(), "the real raw source dataRoot must be absolute");

        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t02 real raw publish root"));
        AtomicMoveSupport.probeOrFail(root);
        copyTree(sourceRoot.resolve("config"), root.path().resolve("config"));
        copyTree(sourceRoot.resolve("raw"), root.path().resolve("raw"));
        copyTree(sourceRoot.resolve("staging"), root.path().resolve("staging"));
        List<String> runIds = stagingRunIds(root);
        assertEquals(2, runIds.size(), "the D1-T05 real raw root must carry both USD and EUR runs");

        Map<String, String> rawHashesBefore = new LinkedHashMap<>();
        DataRoot sourceDataRoot = DataRoot.forTest(sourceRoot);
        for (String runId : runIds) {
            LifecycleTimelineV1 timeline = decodeTimeline(root, runId);
            RawReceiptV1 raw = decodeRaw(root, timeline.rawRef());
            assertEquals(FileDigest.sha256(sourceDataRoot.resolveDataRef(timeline.rawRef())),
                    FileDigest.sha256(root.resolveDataRef(timeline.rawRef())),
                    "the copied raw must be byte-identical to the D1-T05 real raw");
            assertEquals(REAL_PAGE_SHA256, raw.payloadSha256());
            assertEquals("2026-08-07", raw.sourceBusinessDate());
            rawHashesBefore.put(raw.runId(), FileDigest.sha256(root.resolveDataRef(timeline.rawRef())));
        }

        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        PublishedQueryService query = new PublishedQueryService(root, timelineStore, FIXED_CLOCK);

        Map<String, Object> results = new LinkedHashMap<>();
        for (String runId : runIds) {
            ValidationOutcome validated = validation.process(runId);
            assertEquals(ValidationStatus.VERIFIED, validated.validationStatus(), "real raw run " + runId);
            PublishOutcome published = publish.process(runId);
            assertEquals(PublishOutcome.PublishAction.PUBLISHED, published.action(), "real raw run " + runId);
            assertEquals(4, published.recordVersion());
            assertEquals(ProcessingStage.PUBLISHED, published.processingStage());
            assertEquals("staging/" + runId + ".json#recordVersion=4", published.publishRef());
            assertEquals(FileDigest.sha256(root.resolveDataRef(decodeTimeline(root, runId).rawRef())),
                    rawHashesBefore.get(runId), "the real raw file must stay byte-identical after publish");

            LifecycleTimelineV1 timeline = timelineStore.read(runId);
            assertEquals(4, timeline.currentRecordVersion());
            assertEquals(ProcessingStage.PUBLISHED, timeline.current().processingStage());
            assertEquals(ValidationStatus.VERIFIED, timeline.current().validationStatus());
            assertEquals(timeline.records().get(1).candidate(), timeline.records().get(3).candidate());
            results.put(runId, Map.of(
                    "itemId", timeline.current().candidate().itemId(),
                    "businessDate", timeline.current().candidate().businessDate(),
                    "value", timeline.current().candidate().value(),
                    "validationVersion", timeline.current().validationVersion(),
                    "publishRef", timeline.current().publishRef()));

            PublishedRecord record = query.latestPublished(timeline.current().candidate().itemId());
            assertNotNull(record, "real raw run " + runId + " must be visible at the business entry");
            assertEquals(timeline.current().candidate().value(), record.value());
            assertEquals(REAL_PAGE_SHA256, record.rawPayloadSha256());
            assertEquals("staging/" + runId + ".json#recordVersion=4", record.publishRef(),
                    "the real raw published record must carry its actual publishRef");
            assertEquals(timeline.current().publishRef(), record.publishRef(),
                    "PublishedRecord.publishRef must equal the PUBLISHED snapshot publishRef");
            long calendarAgeDays = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(record.businessDate()),
                    LocalDate.parse("2026-08-09"));
            assertEquals(2L, calendarAgeDays);
            assertFalse(record.stale(), "calendar age 2 days (<=30) must not be stale per DEC-051");
            System.out.printf("D2T02_REAL_RAW runId=%s itemId=%s value=%s published=%s publishRef=%s businessDate=%s referenceDate=%s calendarAgeDays=%d stale=%s%n",
                    runId, record.itemId(), record.value(), record.publishedAt(), record.publishRef(),
                    record.businessDate(), "2026-08-09", calendarAgeDays, record.stale());
            results.put(runId, Map.of(
                    "itemId", record.itemId(),
                    "businessDate", record.businessDate(),
                    "referenceDate", "2026-08-09",
                    "calendarAgeDays", calendarAgeDays,
                    "stale", record.stale(),
                    "value", record.value(),
                    "publishRef", record.publishRef()));
        }
        assertFalse(Files.exists(root.path().resolve("quarantine")),
                "real validated runs must never produce quarantine");

        TimelineStore freshStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        for (String runId : runIds) {
            LifecycleTimelineV1 reloaded = freshStore.read(runId);
            assertEquals(4, reloaded.currentRecordVersion());
            assertEquals(ProcessingStage.PUBLISHED, reloaded.current().processingStage());
            assertEquals(ValidationStatus.VERIFIED, reloaded.current().validationStatus());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gate", "D2-T02 real D1-T05 raw publish gate and business entry evidence");
        summary.put("executedAt", OffsetDateTime.now(FIXED_CLOCK).toString());
        summary.put("javaRuntime", System.getProperty("java.version"));
        summary.put("springBootVersion", SpringBootVersion.getVersion());
        summary.put("evidenceDataRoot", root.path().toString());
        summary.put("sourceDataRoot", sourceRoot.toString());
        summary.put("realPageSha256", REAL_PAGE_SHA256);
        summary.put("sourceRawCopiedByteIdentical", true);
        summary.put("rawFilesUnchangedAfterPublish", true);
        summary.put("quarantineCreated", false);
        summary.put("restartReadOutcome", "PASS");
        summary.put("referenceDate", LocalDate.parse("2026-08-09").toString());
        summary.put("results", results);

        String evidenceDirValue = System.getProperty("d2-t02.evidence-dir");
        if (evidenceDirValue != null && !evidenceDirValue.isBlank()) {
            Path evidenceDir = Path.of(evidenceDirValue).toAbsolutePath().normalize();
            Files.createDirectories(evidenceDir);
            ObjectMapper mapper = JsonV1Codec.mapper();
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(evidenceDir.resolve("d2-t02-real-raw-publish-summary.json"), bytes);
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

    private static LifecycleTimelineV1 decodeTimeline(DataRoot root, String runId) throws IOException {
        return JsonV1Codec.decodeFile(
                Files.readAllBytes(root.resolveDataRef(DataPaths.stagingRef(runId))), LifecycleTimelineV1.class);
    }

    private static RawReceiptV1 decodeRaw(DataRoot root, String rawRef) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(rawRef)), RawReceiptV1.class);
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
