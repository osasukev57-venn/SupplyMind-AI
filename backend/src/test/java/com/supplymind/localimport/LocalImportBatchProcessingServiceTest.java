package com.supplymind.localimport;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.manual.ManualMaterialProcessingService;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalImportBatchProcessingServiceTest {

    private static final String ITEM_ID = "MAT.AZ91D.DEMO_IMPORT";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-24T02:00:00Z"), ZoneOffset.ofHours(8));
    private static final String HEADER = "schemaVersion,itemId,businessDate,value,unit,currency,"
            + "actualSourceName,sourceReference,sourceUrl";

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptedRowsAreExplicitlyBatchValidatedPublishedAndRebuiltIdempotently() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("local import batch root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(config());
        RawReceiptStore raws = new RawReceiptStore(root, files, CLOCK);
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        LocalImportService intake = new LocalImportService(
                root, raws, new LocalImportFileStore(root, files, CLOCK), timelines,
                new LocalImportCsvParser(), CLOCK);
        ManualMaterialProcessingService processing = new ManualMaterialProcessingService(
                root, timelines,
                new LifecycleValidationService(root, timelines, CLOCK),
                new LifecyclePublishService(
                        root, timelines, new QuarantineStore(root, files, CLOCK), CLOCK),
                new DailyProcessingService(root, timelines, files, CLOCK),
                new AggregateProcessingService(root, files, CLOCK));
        LocalImportBatchProcessingService batch = new LocalImportBatchProcessingService(processing);

        String csv = HEADER + "\n"
                + row("2026-08-17", "23000")
                + row("2026-08-18", "23150")
                + row("2026-08-19", "23250")
                + row("2026-08-20", "23400")
                + row("2026-08-21", "23600")
                + row("2026-08-24", "24900");
        LocalImportResult accepted = intake.importFile(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(6, accepted.accepted().size());
        assertTrue(accepted.rowErrors().isEmpty());
        accepted.accepted().forEach(row -> {
            assertEquals(ProcessingStage.RECEIVED, timelines.read(row.runId()).current().processingStage());
            assertEquals(ProviderType.LOCAL_IMPORT,
                    com.supplymind.foundation.codec.JsonV1Codec.decodeFile(
                            read(root.resolveDataRef(row.rawRef())),
                            com.supplymind.foundation.model.RawReceiptV1.class).providerType());
        });

        List<String> runIds = accepted.accepted().stream().map(LocalImportResult.RowOutcome::runId).toList();
        LocalImportBatchProcessingService.BatchResult first = batch.process(runIds);
        assertEquals("PUBLISHED", first.status());
        assertEquals(6, first.rows().size());
        first.rows().forEach(row -> {
            assertEquals("PUBLISHED", row.status());
            assertEquals("material-basic-validation-v2", row.validationVersion());
            assertNotNull(row.publishRef());
            assertNotNull(row.dailyRef());
            assertEquals(4, row.aggregateRefs().size());
            assertEquals(ProcessingStage.PUBLISHED,
                    timelines.read(row.runId()).current().processingStage());
        });

        String dailyRef = first.rows().get(0).dailyRef();
        byte[] firstDailyBytes = Files.readAllBytes(root.resolveDataRef(dailyRef));
        var dailyRows = CsvV1Codec.decodeDaily(firstDailyBytes);
        assertEquals(6, dailyRows.size());
        assertEquals("24900", new PublishedQueryService(root, timelines, CLOCK)
                .latestPublished(ITEM_ID).value());

        LocalImportBatchProcessingService.BatchResult replay = batch.process(runIds);
        assertEquals("PUBLISHED", replay.status());
        assertArrayEquals(firstDailyBytes, Files.readAllBytes(root.resolveDataRef(dailyRef)),
                "same imported rows must rebuild byte-identical daily output");

        LocalImportBatchProcessingService.BatchResult partial =
                batch.process(List.of(runIds.get(0), "missing-local-import-run"));
        assertEquals("PARTIAL_SUCCESS", partial.status());
        assertEquals("PUBLISHED", partial.rows().get(0).status());
        assertEquals("REJECTED", partial.rows().get(1).status());
    }

    private static String row(String date, String value) {
        return "1.0," + ITEM_ID + "," + date + "," + value
                + ",元/吨,CNY,模拟验收数据（非外部市场信源）,demo-" + date + ",\n";
    }

    private static MonitorSeriesConfigV1 config() {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, now, List.of(
                new MonitorSeriesItemV1(
                        ITEM_ID, "AZ91D模拟导入数据", true, "模拟验收数据",
                        ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT,
                        "本地文件导入（LocalImport）", RouteDecision.DIRECT_LOCAL_IMPORT,
                        null, now, null, "AZ91D", "material-field-key", "material",
                        "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP,
                        "weekday-asia-shanghai-v1", "CNY", "CNY", "元/吨",
                        new MaterialValidationConfigV1("0", null, 7, "AZ91D", List.of()))));
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }
}