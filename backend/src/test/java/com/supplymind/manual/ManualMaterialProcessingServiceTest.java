package com.supplymind.manual;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualMaterialProcessingServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-21T01:00:00Z"), ZoneOffset.ofHours(8));

    @TempDir
    Path temporaryDirectory;

    @Test
    void explicitOperatorStepRunsFrozenValidationPublishDailyAndAggregateChain() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("manual processing root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, files, CLOCK);
        configStore.activate(MonitorSeriesDefaults.initialDay3(java.time.OffsetDateTime.now(CLOCK)));
        RawReceiptStore raws = new RawReceiptStore(root, files, CLOCK);
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        ManualMaterialIntakeService intake = new ManualMaterialIntakeService(
                root, raws, timelines, new ManualMaterialNormalizer(),
                OperatorContext.configured("d10-operator"), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelines, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(
                root, timelines, new QuarantineStore(root, files, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelines, files, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, files, CLOCK);
        ManualMaterialProcessingService processing = new ManualMaterialProcessingService(
                root, timelines, validation, publish, daily, aggregate);

        ManualIntakeOutcome pending = intake.submit(ManualMaterialSubmission.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,
                "2026-08-20", "18888.50", "\u5143/\u5428", "CNY",
                "D10 manual simulation non-external", "d10-manual-simulation", null));
        assertEquals(ValidationStatus.PENDING, pending.validationStatus());

        ManualMaterialProcessingService.Result result = processing.process(pending.runId());

        assertEquals("PUBLISHED", result.status());
        assertEquals("material-basic-validation-v2", result.validationVersion());
        assertNotNull(result.publishRef());
        assertEquals(4, result.aggregateRefs().size());
        assertEquals(ProcessingStage.PUBLISHED, timelines.read(pending.runId()).current().processingStage());
        assertTrue(Files.isRegularFile(root.resolveDataRef(result.dailyRef())));
        var rows = CsvV1Codec.decodeDaily(Files.readAllBytes(root.resolveDataRef(result.dailyRef())));
        assertEquals(1, rows.size());
        assertEquals("18888.50", rows.get(0).avg());
        assertEquals("D10 manual simulation non-external", rows.get(0).actualSourceName());

        var registry = new com.supplymind.provider.DataProviderRegistry();
        var configs = new com.supplymind.config.ConfigManagementService(configStore, registry);
        var history = new com.supplymind.history.HistoryQueryService(root);
        var warningStore = new com.supplymind.warning.WarningStore(root, files, CLOCK);
        var warnings = new com.supplymind.warning.WarningService(
                root, warningStore, CLOCK, history);
        var localImport = new com.supplymind.localimport.LocalImportService(
                root, raws,
                new com.supplymind.localimport.LocalImportFileStore(root, files, CLOCK),
                timelines, new com.supplymind.localimport.LocalImportCsvParser(), CLOCK);
        var dashboard = new com.supplymind.dashboard.DashboardService(
                configs, new com.supplymind.publish.PublishedQueryService(root, timelines, CLOCK),
                history, warnings, CLOCK, intake, localImport, registry);
        var card = dashboard.overview().items().stream()
                .filter(item -> item.itemId().equals(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID))
                .findFirst().orElseThrow();
        assertEquals("D10 manual simulation non-external", card.source().actualSourceName(),
                "overview must show the published row's real source, not only config-level Manual");
    }
}