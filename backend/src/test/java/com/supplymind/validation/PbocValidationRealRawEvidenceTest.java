package com.supplymind.validation;

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
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootVersion;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PbocValidationRealRawEvidenceTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-09T14:50:00Z"), SHANGHAI);
    private static final String REAL_PAGE_SHA256 =
            "f37cda1f7717a317fe42997514b27850dd96b16df5847b5eafcfab9a543d4f82";

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "d2-t01.real-raw", matches = "true")
    void standardizesAndValidatesTheRealD1T05PbocRawIntoVerifiedCandidates() throws IOException {
        String sourceValue = System.getProperty("d2-t01.source-data-root");
        assertNotNull(sourceValue, "an explicit absolute d2-t01.source-data-root is required for the real raw evidence run");
        Path sourceRoot = Path.of(sourceValue).toAbsolutePath().normalize();
        assertTrue(Path.of(sourceValue).isAbsolute(), "the real raw source dataRoot must be absolute");

        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d2-t01 real raw evidence root"));
        AtomicMoveSupport.probeOrFail(root);
        copyTree(sourceRoot.resolve("config"), root.path().resolve("config"));
        copyTree(sourceRoot.resolve("raw"), root.path().resolve("raw"));
        copyTree(sourceRoot.resolve("staging"), root.path().resolve("staging"));
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.configActiveRef())));
        List<String> runIds = stagingRunIds(root);
        assertEquals(2, runIds.size(), "the D1-T05 real raw root must carry both USD and EUR runs");

        Map<String, String> rawHashesBefore = new LinkedHashMap<>();
        DataRoot sourceDataRoot = DataRoot.forTest(sourceRoot);
        for (String runId : runIds) {
            LifecycleTimelineV1 timeline = decodeTimeline(root, runId);
            RawReceiptV1 raw = decodeRaw(root, timeline.rawRef());
            Path sourceRaw = sourceDataRoot.resolveDataRef(timeline.rawRef());
            Path copyRaw = root.resolveDataRef(timeline.rawRef());
            assertEquals(FileDigest.sha256(sourceRaw), FileDigest.sha256(copyRaw),
                    "the copied raw must be byte-identical to the D1-T05 real raw");
            assertEquals(REAL_PAGE_SHA256, raw.payloadSha256(),
                    "the real PBOC page SHA-256 must match the D1-T05 evidence value");
            assertTrue(new BigDecimal(raw.rawValue()).signum() > 0);
            assertEquals("2026-08-07", raw.sourceBusinessDate());
            rawHashesBefore.put(raw.runId(), FileDigest.sha256(copyRaw));
        }

        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, FIXED_CLOCK).ensureInitialDefault();
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService service =
                new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);

        Map<String, Object> results = new LinkedHashMap<>();
        for (String runId : runIds) {
            ValidationOutcome outcome = service.process(runId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("itemId", outcome.candidate() == null ? null : outcome.candidate().itemId());
            result.put("businessDate", outcome.candidate() == null ? null : outcome.candidate().businessDate());
            result.put("value", outcome.candidate() == null ? null : outcome.candidate().value());
            result.put("processingStage", outcome.processingStage().name());
            result.put("validationStatus", outcome.validationStatus().name());
            result.put("recordVersion", outcome.recordVersion());
            result.put("validationVersion", outcome.validationVersion());
            result.put("reasonCode", outcome.reasonCode());
            results.put(runId, result);

            assertEquals(ProcessingStage.VALIDATED, outcome.processingStage(), "real raw run " + runId);
            assertEquals(ValidationStatus.VERIFIED, outcome.validationStatus(), "real raw run " + runId);
            assertEquals(3, outcome.recordVersion());
            assertEquals(PbocBasicValidator.VALIDATION_VERSION, outcome.validationVersion());
            assertEquals("2026-08-07", outcome.candidate().businessDate());
            assertNotNull(outcome.validatedAt());
            System.out.printf("D2T01_REAL_RAW runId=%s itemId=%s value=%s verdict=%s businessDate=%s%n",
                    runId, outcome.candidate().itemId(), outcome.candidate().value(),
                    outcome.validationStatus(), outcome.candidate().businessDate());

            assertEquals(rawHashesBefore.get(runId), FileDigest.sha256(root.resolveDataRef(timelineRawRef(root, runId))),
                    "the real raw file must remain byte-identical after validation");
        }

        TimelineStore freshStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        for (String runId : runIds) {
            LifecycleTimelineV1 reloaded = freshStore.read(runId);
            assertEquals(3, reloaded.currentRecordVersion());
            assertEquals(ProcessingStage.VALIDATED, reloaded.current().processingStage());
            assertEquals(ValidationStatus.VERIFIED, reloaded.current().validationStatus());
            assertEquals(reloaded.records().get(1).candidate(), reloaded.records().get(2).candidate());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("gate", "D2-T01 real D1-T05 raw standardization and basic validation evidence");
        summary.put("executedAt", OffsetDateTime.now(FIXED_CLOCK).toString());
        summary.put("javaRuntime", System.getProperty("java.version"));
        summary.put("springBootVersion", SpringBootVersion.getVersion());
        summary.put("evidenceDataRoot", root.path().toString());
        summary.put("sourceDataRoot", sourceRoot.toString());
        summary.put("realPageSha256", REAL_PAGE_SHA256);
        summary.put("sourceRawCopiedByteIdentical", true);
        summary.put("rawFilesUnchangedAfterValidation", true);
        summary.put("restartReadOutcome", "PASS");
        summary.put("results", results);

        String evidenceDirValue = System.getProperty("d2-t01.evidence-dir");
        if (evidenceDirValue != null && !evidenceDirValue.isBlank()) {
            Path evidenceDir = Path.of(evidenceDirValue).toAbsolutePath().normalize();
            Files.createDirectories(evidenceDir);
            ObjectMapper mapper = JsonV1Codec.mapper();
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(evidenceDir.resolve("d2-t01-real-raw-validation-summary.json"), bytes);
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

    private static String timelineRawRef(DataRoot root, String runId) throws IOException {
        return decodeTimeline(root, runId).rawRef();
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
