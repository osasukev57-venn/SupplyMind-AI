package com.supplymind.demo;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.localimport.SyntheticDemoDataProvider;
import com.supplymind.provider.DataProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoShowcaseServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T01:30:00Z"), ZoneId.of("Asia/Shanghai"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void completeShowcasePersistsAuditEvidenceAndNeverEntersFormalOutputs() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("demo-data"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new SyntheticDemoDataProvider(SyntheticDemoDataProvider.defaultScenarioItems()));
        DemoShowcaseService service = new DemoShowcaseService(root, files, timelines, registry);

        DemoShowcaseRunV1 first = service.run();
        assertEquals("COMPLETE", first.status());
        assertEquals("DEMO", first.mode());
        assertEquals(2, first.items().size());
        assertTrue(first.stages().containsAll(List.of(
                "RAW_CAPTURED", "PARSED", "VALIDATED", "DAILY_CALCULATED",
                "MONTH_QUARTER_HALFYEAR_YEAR_CALCULATED", "WARNING_EVALUATED", "COMPLETE")));
        first.items().forEach(item -> {
            assertEquals(item.value(), item.dailyAverage());
            assertEquals(item.dailyAverage(), item.monthlyAverage());
            assertEquals(item.monthlyAverage(), item.quarterlyAverage());
            assertEquals(item.quarterlyAverage(), item.halfyearAverage());
            assertEquals(item.halfyearAverage(), item.yearlyAverage());
            assertEquals("NOT_TRIGGERED_NO_COMPARABLE_BASELINE", item.warningOutcome());
            assertTrue(Files.isRegularFile(root.resolveDataRef(item.rawRef())));
            assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(item.rawRef()))));
            var timeline = timelines.read(item.runId());
            assertEquals(3, timeline.currentRecordVersion());
            assertEquals(ProcessingStage.VALIDATED, timeline.current().processingStage());
            assertEquals(ValidationStatus.VERIFIED, timeline.current().validationStatus());
        });

        Path reportPath = root.resolveDataRef(first.demoRef());
        Path reportManifest = root.resolveDataRef(DataPaths.manifestRef(first.demoRef()));
        byte[] before = Files.readAllBytes(reportPath);
        assertTrue(ManifestVerifier.matches(root, first.demoRef(), reportPath, reportManifest,
                first.items().stream().map(DemoShowcaseRunV1.DemoItemResult::runId).sorted().toList()));
        assertEquals(first, JsonV1Codec.decodeFile(before, DemoShowcaseRunV1.class));

        DemoShowcaseRunV1 replay = service.run();
        assertEquals(first, replay);
        assertArrayEquals(before, Files.readAllBytes(reportPath));
        assertEquals(FileDigest.sha256(before), FileDigest.sha256(Files.readAllBytes(reportPath)));

        assertFalse(Files.exists(root.path().resolve("processed")), "DEMO must not create formal daily/aggregate files");
        assertFalse(Files.exists(root.path().resolve("warning")), "DEMO must not create formal warning evidence");
        assertFalse(Files.exists(root.path().resolve("report")), "DEMO must not create formal Agent reports");
        assertFalse(Files.exists(root.path().resolve("quarantine")));
    }
}
