package com.supplymind.day5.r2;

import com.supplymind.backfill.BackfillJobStateV1;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent R2 attacks for A4/A5.  Each automatic assertion calls only BackfillOrchestrator
 * externally; the provider, raw store, validation, publish, daily, and aggregate services are
 * the actual production chain.  No PUBLISHED timeline is seeded by the test.
 */
class Day5R2BackfillAttackTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-02T10:00:00+08:00");
    private static final String AUTO_ITEM = "FX.R2.AUTO.USD.CNY";
    private static final String MANUAL_ITEM = "MAT.R2.MANUAL.ADC12";

    @TempDir
    Path temporaryDirectory;

    @Test
    void a4AutomaticBackfillCallsRealFullChainAndCreatesAllPersistedArtifacts() throws Exception {
        SequencedProvider provider = new SequencedProvider(List.of("2026-08-10"), null);
        Harness harness = harness("a4 full chain", provider);
        BackfillJobStateV1 waiting = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-10"));

        BackfillJobStateV1 terminal = harness.orchestrator().run(waiting.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, terminal.status());
        assertEquals("2026-08-10", terminal.currentCheckpoint());
        assertEquals(1, provider.collectCalls.get());
        assertTrue(harness.rawCount() > 0, "provider acquisition must persist a real raw receipt");
        LifecycleTimelineV1 timeline = harness.timelineStore().read("r2-auto-20260810");
        assertEquals(4, timeline.currentRecordVersion());
        assertEquals(ProcessingStage.PUBLISHED, timeline.current().processingStage());
        assertEquals(ValidationStatus.VERIFIED, timeline.current().validationStatus());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.dailyRef(AUTO_ITEM, YearMonth.of(2026, 8)))));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.aggregateRef(AUTO_ITEM, "month", 2026))));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.aggregateRef(AUTO_ITEM, "year", 2026))));
    }

    @Test
    void a5CheckpointRestartAndDuplicateStartDoNotReproduceRawPublishedDailyOrAggregateArtifacts() throws Exception {
        AtomicReference<BackfillJobStore> storeReference = new AtomicReference<>();
        String jobId = "backfill-" + AUTO_ITEM + "-2026-08-31-2026-09-01";
        SequencedProvider provider = new SequencedProvider(List.of("2026-08-31", "2026-09-01"), storeReference);
        Harness first = harness("a5 resume", provider);
        storeReference.set(first.jobStore());
        BackfillJobStateV1 waiting = first.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-01"));
        BackfillJobStateV1 duplicate = first.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-01"));
        assertEquals(waiting.jobId(), duplicate.jobId(), "duplicate starts must reuse the same persisted job");

        provider.failOnSecondCollection = true;
        BackfillJobStateV1 partial = first.orchestrator().run(jobId);
        assertEquals(BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS, partial.status());
        assertEquals("2026-08-31", partial.currentCheckpoint());
        assertEquals(BackfillJobStateV1.JobStatus.RUNNING, provider.observedRunningState.get(),
                "provider acquisition must see a truly persisted RUNNING job, not a synthetic result");
        int rawAfterPartial = first.rawCount();
        byte[] augustDaily = Files.readAllBytes(first.root().resolveDataRef(DataPaths.dailyRef(AUTO_ITEM, YearMonth.of(2026, 8))));

        Harness restarted = restart(first, provider);
        storeReference.set(restarted.jobStore());
        provider.failOnSecondCollection = false;
        BackfillJobStateV1 succeeded = restarted.orchestrator().run(jobId);
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status());
        assertEquals("2026-09-01", succeeded.currentCheckpoint());
        assertEquals(rawAfterPartial + 1, restarted.rawCount(), "resume must acquire only the post-checkpoint day");
        assertTrue(java.util.Arrays.equals(augustDaily, Files.readAllBytes(
                restarted.root().resolveDataRef(DataPaths.dailyRef(AUTO_ITEM, YearMonth.of(2026, 8))))),
                "resume must not rewrite an already completed daily period");
        List<AggregateRecordV1> monthly = CsvV1Codec.decodeAggregate(Files.readAllBytes(
                restarted.root().resolveDataRef(DataPaths.aggregateRef(AUTO_ITEM, "month", 2026))));
        assertEquals(2, monthly.size(), "one August and one September aggregate row; no duplicate replay rows");
        assertEquals(3, provider.collectCalls.get(), "two first-run attempts plus exactly one resumed acquisition");

        BackfillJobStateV1 duplicateAfterSuccess = restarted.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-01"));
        assertEquals(jobId, duplicateAfterSuccess.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, duplicateAfterSuccess.status());
        assertEquals(3, provider.collectCalls.get(), "a duplicate start after completion must not call provider again");
    }

    @Test
    void a5ManualAndNoProviderHistoryRemainHonestNonSuccessStates() {
        Harness harness = harness("a5 manual", new SequencedProvider(List.of(), null));
        BackfillJobStateV1 manual = harness.orchestrator().createOrResume(
                MANUAL_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        BackfillJobStateV1 waiting = harness.orchestrator().run(manual.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT, waiting.status());
        assertFalse(waiting.status() == BackfillJobStateV1.JobStatus.SUCCEEDED);

        Harness noProvider = harness("a5 no provider", null);
        BackfillJobStateV1 automatic = noProvider.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"));
        BackfillJobStateV1 failed = noProvider.orchestrator().run(automatic.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.FAILED, failed.status());
        assertTrue(failed.failureReasons().contains("NO_AUTO_PROVIDER_CAPABILITY"));
    }

    private Harness harness(String name, SequencedProvider provider) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(name));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(config());
        RawReceiptStore raws = new RawReceiptStore(root, files, CLOCK);
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelines, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelines,
                new QuarantineStore(root, files, CLOCK), CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelines, files, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, files, CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        if (provider != null) {
            registry.register(provider);
        }
        BackfillJobStore jobs = new BackfillJobStore(root, files, CLOCK);
        return new Harness(root, jobs, timelines, new BackfillOrchestrator(root, jobs, configs, registry,
                new RawAcquisitionStore(root, files, CLOCK), raws, timelines, validation, publish, daily, aggregate));
    }

    private Harness restart(Harness first, SequencedProvider provider) {
        AtomicFileStore files = new AtomicFileStore(first.root(), new DirtyMarkerCodec());
        BackfillJobStore jobs = new BackfillJobStore(first.root(), files, CLOCK);
        TimelineStore timelines = new TimelineStore(first.root(), files, CLOCK);
        ConfigActivationStore configs = new ConfigActivationStore(first.root(), files, CLOCK);
        RawReceiptStore raws = new RawReceiptStore(first.root(), files, CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(first.root(), timelines, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(first.root(), timelines,
                new QuarantineStore(first.root(), files, CLOCK), CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);
        return new Harness(first.root(), jobs, timelines, new BackfillOrchestrator(first.root(), jobs, configs, registry,
                new RawAcquisitionStore(first.root(), files, CLOCK), raws, timelines, validation, publish,
                new DailyProcessingService(first.root(), timelines, files, CLOCK),
                new AggregateProcessingService(first.root(), files, CLOCK)));
    }

    private static MonitorSeriesConfigV1 config() {
        MonitorSeriesItemV1 automatic = new MonitorSeriesItemV1(AUTO_ITEM, "R2 automatic USD", true, "R2",
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, "R2 auto fixture source",
                RouteDecision.PRIMARY, null, NOW, null, "USD", "1美元对人民币", "r2-fx",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
        MonitorSeriesItemV1 manual = new MonitorSeriesItemV1(MANUAL_ITEM, "R2 manual ADC12", true, "R2",
                ProviderType.MANUAL, AccessMethod.MANUAL, "declared source", RouteDecision.FALLBACK_MANUAL,
                "MANUAL_FALLBACK", NOW, null, "ADC12", "material-field", "material", "arithmetic-mean-v1",
                2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, List.of(automatic, manual));
    }

    private static final class SequencedProvider implements DataProvider {
        private final List<String> dates;
        private final AtomicReference<BackfillJobStore> jobs;
        private final AtomicInteger collectCalls = new AtomicInteger();
        private final AtomicReference<BackfillJobStateV1.JobStatus> observedRunningState = new AtomicReference<>();
        private volatile boolean failOnSecondCollection;

        private SequencedProvider(List<String> dates, AtomicReference<BackfillJobStore> jobs) {
            this.dates = new ArrayList<>(dates);
            this.jobs = jobs;
        }

        @Override public ProviderSourceProfile profile() {
            return ProviderSourceProfile.of("r2-sequenced-provider", ProviderType.OFFICIAL_WEB,
                    AccessMethod.PUBLIC_OFFICIAL_HTML, "R2 auto fixture source", "https://example.test/r2-auto", true, true);
        }
        @Override public Set<String> supportedItemIds() { return Set.of(AUTO_ITEM); }
        @Override public boolean supports(MonitorSeriesItemV1 item) {
            return item.providerType() == ProviderType.OFFICIAL_WEB && "r2-fx".equals(item.rateKind());
        }
        @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
            int call = collectCalls.incrementAndGet();
            if (jobs != null && jobs.get() != null) {
                String expectedJob = "backfill-" + AUTO_ITEM + "-2026-08-31-2026-09-01";
                if (jobs.get().exists(expectedJob)) {
                    observedRunningState.set(jobs.get().read(expectedJob).status());
                }
            }
            if (failOnSecondCollection && call == 2) {
                throw new IllegalStateException("injected interrupt after checkpoint");
            }
            String date = dates.isEmpty() ? null : dates.remove(0);
            if (date == null) {
                return ProviderCollectOutcome.rejectedOnly("r2-sequenced-provider", Map.of(AUTO_ITEM, "NO_PENDING_DAY"));
            }
            byte[] payload = ("r2-auto-" + date).getBytes(StandardCharsets.UTF_8);
            String runId = "r2-auto-" + date.replace("-", "");
            String acquisitionId = "r2-acq-" + date.replace("-", "");
            RawReceiptV1 raw = new RawReceiptV1("1.0", RawReceiptV1.deriveRawRef(Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AUTO_ITEM, NOW, runId), acquisitionId, runId, Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1, "R2 auto fixture source",
                    "https://example.test/r2-auto", "r2-auto-ref", AUTO_ITEM, date, date, null, NOW, NOW, null,
                    "7.12340000", "CNY/1 USD", "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                    Base64.getEncoder().encodeToString(payload), FileDigest.sha256(payload), "r2-anchor", NOW,
                    DataPaths.acquisitionRef(acquisitionId), null);
            return new ProviderCollectOutcome("1.0", "r2-sequenced-provider", acquisitionId, date,
                    raw.payloadSha256(), List.of(raw), List.of(), Map.of());
        }
    }

    private record Harness(DataRoot root, BackfillJobStore jobStore, TimelineStore timelineStore,
                           BackfillOrchestrator orchestrator) {
        private int rawCount() throws Exception {
            Path directory = root.resolveInternalRelative("raw/formal/official_web/" + AUTO_ITEM);
            if (!Files.isDirectory(directory)) return 0;
            try (var files = Files.walk(directory)) {
                return (int) files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().endsWith(".manifest.json")).count();
            }
        }
    }
}
