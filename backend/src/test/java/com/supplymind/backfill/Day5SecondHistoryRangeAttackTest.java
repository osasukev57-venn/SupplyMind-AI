package com.supplymind.backfill;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
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
import com.supplymind.provider.CollectionMode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent A3 attack. The fixture provider's response is a pure function of the actual
 * HISTORY request range; observation lists are assertions only and never choose a business date.
 */
class Day5SecondHistoryRangeAttackTest {

    private static final String ITEM = "FX.A3.RANGE.USD";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-03T10:00:00+08:00");
    private static final LocalDate FROM = LocalDate.parse("2026-08-01");
    private static final LocalDate TO = LocalDate.parse("2026-08-03");

    @TempDir
    Path temporaryDirectory;

    @Test
    void currentOnlyProviderIsNotCalledForHistoryAndReturnsHonestNonSuccess() {
        RangeDrivenProvider provider = new RangeDrivenProvider(false, Set.of());
        Harness harness = harness("a3-current-only", provider);
        BackfillJobStateV1 job = harness.orchestrator.createOrResume(ITEM, FROM, TO);

        BackfillJobStateV1 result = harness.orchestrator.run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT, result.status());
        assertFalse(result.status() == BackfillJobStateV1.JobStatus.SUCCEEDED);
        assertTrue(result.failureReasons().contains("NO_HISTORY_CAPABILITY"));
        assertTrue(provider.observedRequests.isEmpty(), "no automatic pseudo-history collection is allowed");
        assertEquals(0, harness.rawCount());
    }

    @Test
    void explicitHistoryRangesDriveTheRealRawValidationPublishDailyAndAggregateChain() throws Exception {
        RangeDrivenProvider provider = new RangeDrivenProvider(true, Set.of());
        Harness harness = harness("a3-full-chain", provider);
        BackfillJobStateV1 job = harness.orchestrator.createOrResume(ITEM, FROM, TO);

        BackfillJobStateV1 result = harness.orchestrator.run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, result.status());
        assertEquals(TO.toString(), result.currentCheckpoint());
        assertHistoryStarts(provider.observedRequests, List.of(FROM, FROM.plusDays(1), TO));
        for (LocalDate day : List.of(FROM, FROM.plusDays(1), TO)) {
            LifecycleTimelineV1 timeline = harness.timelines.read(runId(day));
            assertEquals(4, timeline.currentRecordVersion());
            assertEquals(ProcessingStage.PUBLISHED, timeline.current().processingStage());
            assertEquals(ValidationStatus.VERIFIED, timeline.current().validationStatus());
        }
        assertEquals(3, harness.rawCount());
        assertTrue(Files.isRegularFile(harness.root.resolveDataRef(DataPaths.dailyRef(ITEM, YearMonth.of(2026, 8)))));
        assertTrue(Files.isRegularFile(harness.root.resolveDataRef(DataPaths.aggregateRef(ITEM, "month", 2026))));
        assertTrue(Files.isRegularFile(harness.root.resolveDataRef(DataPaths.aggregateRef(ITEM, "year", 2026))));
    }

    @Test
    void restartResumesFromPersistedCheckpointAndOnlyRequestsTheRemainingRange() throws Exception {
        RangeDrivenProvider interrupted = new RangeDrivenProvider(true, Set.of(TO));
        Harness first = harness("a3-resume", interrupted);
        BackfillJobStateV1 job = first.orchestrator.createOrResume(ITEM, FROM, TO);

        BackfillJobStateV1 partial = first.orchestrator.run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS, partial.status());
        assertEquals(FROM.plusDays(1).toString(), partial.currentCheckpoint());
        assertHistoryStarts(interrupted.observedRequests, List.of(FROM, FROM.plusDays(1), TO));
        assertEquals(1, interrupted.successfulAcquisitions.get(FROM));
        assertEquals(1, interrupted.successfulAcquisitions.get(FROM.plusDays(1)));

        RangeDrivenProvider resumedProvider = new RangeDrivenProvider(true, Set.of());
        Harness restarted = restart(first.root, resumedProvider);
        BackfillJobStateV1 completed = restarted.orchestrator.run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, completed.status());
        assertEquals(TO.toString(), completed.currentCheckpoint());
        assertHistoryStarts(resumedProvider.observedRequests, List.of(TO));
        assertEquals(1, resumedProvider.successfulAcquisitions.get(TO));
        assertEquals(3, restarted.rawCount());
        assertTrue(Files.isRegularFile(restarted.root.resolveDataRef(DataPaths.dailyRef(ITEM, YearMonth.of(2026, 8)))));
        assertTrue(Files.isRegularFile(restarted.root.resolveDataRef(DataPaths.aggregateRef(ITEM, "month", 2026))));
    }

    private static void assertHistoryStarts(List<ProviderCollectRequest> actual, List<LocalDate> expectedStarts) {
        assertEquals(expectedStarts.size(), actual.size());
        for (int index = 0; index < expectedStarts.size(); index++) {
            ProviderCollectRequest request = actual.get(index);
            assertEquals(CollectionMode.HISTORY, request.collectionMode());
            assertEquals(expectedStarts.get(index), request.historyStartDate());
            assertEquals(TO, request.historyEndDate(), "every request carries the explicit remaining range end");
            assertEquals(List.of(ITEM), request.itemIds());
        }
    }

    private Harness harness(String name, RangeDrivenProvider provider) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(name));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(config());
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);
        return new Harness(root, timelines, new BackfillOrchestrator(root,
                new BackfillJobStore(root, files, CLOCK), configs, registry,
                new RawAcquisitionStore(root, files, CLOCK), new RawReceiptStore(root, files, CLOCK), timelines,
                new LifecycleValidationService(root, timelines, CLOCK),
                new LifecyclePublishService(root, timelines, new QuarantineStore(root, files, CLOCK), CLOCK),
                new DailyProcessingService(root, timelines, files, CLOCK),
                new AggregateProcessingService(root, files, CLOCK)));
    }

    private Harness restart(DataRoot root, RangeDrivenProvider provider) {
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);
        return new Harness(root, timelines, new BackfillOrchestrator(root,
                new BackfillJobStore(root, files, CLOCK), new ConfigActivationStore(root, files, CLOCK), registry,
                new RawAcquisitionStore(root, files, CLOCK), new RawReceiptStore(root, files, CLOCK), timelines,
                new LifecycleValidationService(root, timelines, CLOCK),
                new LifecyclePublishService(root, timelines, new QuarantineStore(root, files, CLOCK), CLOCK),
                new DailyProcessingService(root, timelines, files, CLOCK),
                new AggregateProcessingService(root, files, CLOCK)));
    }

    private static MonitorSeriesConfigV1 config() {
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT, List.of(new MonitorSeriesItemV1(
                ITEM, "A3 range USD", true, "A3", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "A3 request-derived provider", RouteDecision.PRIMARY,
                null, AT, null, "USD", "1美元对人民币", "a3-fx", "arithmetic-mean-v1", 8, 4,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", "CNY", "USD", "CNY/1 USD", null)));
    }

    private static String runId(LocalDate date) {
        return "a3-range-" + date.toString().replace("-", "");
    }

    private static final class RangeDrivenProvider implements DataProvider {
        private final boolean supportsHistory;
        private final Set<LocalDate> blockedDates;
        private final List<ProviderCollectRequest> observedRequests = new ArrayList<>();
        private final Map<LocalDate, Integer> successfulAcquisitions = new java.util.TreeMap<>();

        private RangeDrivenProvider(boolean supportsHistory, Set<LocalDate> blockedDates) {
            this.supportsHistory = supportsHistory;
            this.blockedDates = Set.copyOf(blockedDates);
        }

        @Override public ProviderSourceProfile profile() {
            return ProviderSourceProfile.of("a3-range-provider", ProviderType.OFFICIAL_WEB,
                    AccessMethod.PUBLIC_OFFICIAL_HTML, "A3 request-derived provider", "https://example.test/a3",
                    true, supportsHistory);
        }
        @Override public Set<String> supportedItemIds() { return Set.of(ITEM); }
        @Override public boolean supports(MonitorSeriesItemV1 item) {
            return item.providerType() == ProviderType.OFFICIAL_WEB && "a3-fx".equals(item.rateKind());
        }
        @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
            observedRequests.add(request);
            if (request.collectionMode() != CollectionMode.HISTORY) {
                throw new IllegalStateException("history backfill must issue a HISTORY request");
            }
            LocalDate date = request.historyStartDate();
            if (date == null || request.historyEndDate() == null || date.isAfter(request.historyEndDate())) {
                throw new IllegalStateException("provider needs a valid explicit history range");
            }
            if (blockedDates.contains(date)) {
                throw new IllegalStateException("request-derived interrupt for " + date);
            }
            successfulAcquisitions.merge(date, 1, Integer::sum);
            byte[] payload = ("a3-request-derived-" + date).getBytes(StandardCharsets.UTF_8);
            String runId = runId(date);
            String acquisitionId = "a3-acq-" + date.toString().replace("-", "");
            RawReceiptV1 raw = new RawReceiptV1("1.0", RawReceiptV1.deriveRawRef(Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, ITEM, AT, runId), acquisitionId, runId, Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                    "A3 request-derived provider", "https://example.test/a3", "a3-range", ITEM,
                    date.toString(), date.toString(), null, AT, AT, null, "7.12340000", "CNY/1 USD", "CNY",
                    null, 200, "text/html; charset=UTF-8", "base64", Base64.getEncoder().encodeToString(payload),
                    FileDigest.sha256(payload), "a3-anchor", AT, DataPaths.acquisitionRef(acquisitionId), null);
            return new ProviderCollectOutcome("1.0", "a3-range-provider", acquisitionId, date.toString(),
                    raw.payloadSha256(), List.of(raw), List.of(), Map.of());
        }
    }

    private record Harness(DataRoot root, TimelineStore timelines, BackfillOrchestrator orchestrator) {
        private int rawCount() {
            Path directory = root.resolveInternalRelative("raw/formal/official_web/" + ITEM);
            if (!Files.isDirectory(directory)) return 0;
            try (var files = Files.walk(directory)) {
                return (int) files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().endsWith(".manifest.json")).count();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
