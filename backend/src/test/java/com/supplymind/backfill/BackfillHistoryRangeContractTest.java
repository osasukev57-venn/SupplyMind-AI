package com.supplymind.backfill;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaValidationException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3/H08 production contract: backfill history acquisition is driven by an explicit
 * date/range request, never by a provider's implicit "next internal day" state.
 * 1) A current-only provider (supportsHistoryData=false) is never asked for pseudo-history,
 *    never succeeds, and honestly lands in AWAITING_MANUAL_INPUT.
 * 2) A history-capable provider receives HISTORY requests carrying the exact remaining range.
 * 3) The provider returns data for the requested date; full chain
 *    provider-&gt;raw-&gt;validation-&gt;publish-&gt;daily-&gt;aggregate runs across multiple days.
 * 4) Restart resumes from the checkpoint and never re-collects completed dates.
 */
class BackfillHistoryRangeContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-02T10:00:00+08:00");
    private static final String AUTO_ITEM = "FX.M3.AUTO.USD";

    @TempDir
    Path temporaryDirectory;

    @Test
    void requestContractValidatesCurrentAndHistoryModesFailClosed() {
        assertThrows(SchemaValidationException.class,
                () -> ProviderCollectRequest.history(List.of(AUTO_ITEM), null, null),
                "HISTORY without a range must be rejected");
        assertThrows(SchemaValidationException.class,
                () -> ProviderCollectRequest.history(List.of(AUTO_ITEM),
                        LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-09")),
                "an inverted range must be rejected");
        assertThrows(SchemaValidationException.class,
                () -> new ProviderCollectRequest(List.of(AUTO_ITEM), CollectionMode.CURRENT,
                        LocalDate.parse("2026-08-10"), null),
                "CURRENT with a history date must be rejected");

        ProviderCollectRequest current = ProviderCollectRequest.current(List.of(AUTO_ITEM));
        assertEquals(CollectionMode.CURRENT, current.collectionMode());
        assertEquals(null, current.historyStartDate());
        assertEquals(null, current.historyEndDate());
        ProviderCollectRequest history = ProviderCollectRequest.history(
                List.of(AUTO_ITEM), LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-31"));
        assertEquals(CollectionMode.HISTORY, history.collectionMode());
        assertEquals("2026-08-10", history.historyStartDate().toString());
        assertEquals("2026-08-31", history.historyEndDate().toString());
    }

    @Test
    void currentOnlyProviderNeverGetsHistoryCollectAndLandsInHonestAwaitingManualInput() {
        RequestDrivenProvider provider = new RequestDrivenProvider(
                LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12"), true, false, null);
        Harness harness = harness("m3 current only", provider);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12"));

        BackfillJobStateV1 result = harness.orchestrator().run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT, result.status(),
                "a current-only provider must land in a frozen honest non-success state");
        assertFalse(result.status() == BackfillJobStateV1.JobStatus.SUCCEEDED);
        assertTrue(result.failureReasons().stream().anyMatch(reason -> reason.contains("NO_HISTORY_CAPABILITY")));
        assertEquals(0, provider.collectCalls.get(),
                "no pseudo-history collect may ever be issued to a current-only provider");
        assertEquals(0, harness.rawCount(),
                "no raw may be persisted by a gated history backfill");
    }

    @Test
    void historyCapableProviderReceivesExplicitRangesAndCompletesMultiDayFullChain() throws Exception {
        RequestDrivenProvider provider = new RequestDrivenProvider(
                LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12"), true, true, null);
        Harness harness = harness("m3 multi day", provider);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12"));

        BackfillJobStateV1 succeeded = harness.orchestrator().run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status());
        assertEquals("2026-08-12", succeeded.currentCheckpoint());
        assertEquals(3, provider.collectCalls.get(), "one HISTORY request per remaining day");
        assertEquals(3, harness.rawCount(), "one raw per requested day through the real chain");
        for (LocalDate day : List.of(LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-11"),
                LocalDate.parse("2026-08-12"))) {
            assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                            DataPaths.dailyRef(AUTO_ITEM, YearMonth.from(day)))),
                    "daily must be rebuilt for " + day);
        }
        List<AggregateRecordV1> monthly = CsvV1Codec.decodeAggregate(Files.readAllBytes(
                harness.root().resolveDataRef(DataPaths.aggregateRef(AUTO_ITEM, "month", 2026))));
        assertEquals(1, monthly.size(), "one month aggregate row for the completed backfill");
        provider.requests.forEach(request -> {
            assertEquals(CollectionMode.HISTORY, request.collectionMode());
            assertEquals(AUTO_ITEM, request.itemIds().get(0));
            assertTrue(request.historyEndDate().toString().equals("2026-08-12"),
                    "every request must carry the true job end as its range end");
        });
    }

    @Test
    void restartResumesFromCheckpointAndNeverReCollectsCompletedDates() throws Exception {
        RequestDrivenProvider provider = new RequestDrivenProvider(
                LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-01"), true, true,
                LocalDate.parse("2026-09-01"));
        Harness first = harness("m3 resume", provider);
        BackfillJobStateV1 job = first.orchestrator().createOrResume(
                AUTO_ITEM, LocalDate.parse("2026-08-31"), LocalDate.parse("2026-09-01"));

        BackfillJobStateV1 partial = first.orchestrator().run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS, partial.status());
        assertEquals("2026-08-31", partial.currentCheckpoint(),
                "the checkpoint is the last completed business date");
        assertEquals(1, first.rawCount());

        provider.clearFailure();
        Harness restarted = restart(first.root(), provider);
        BackfillJobStateV1 succeeded = restarted.orchestrator().run(job.jobId());
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status());
        assertEquals("2026-09-01", succeeded.currentCheckpoint());
        assertEquals(2, restarted.rawCount(), "resume must complete only the post-checkpoint day");
        assertTrue(Files.isRegularFile(restarted.root().resolveDataRef(
                        DataPaths.dailyRef(AUTO_ITEM, YearMonth.of(2026, 8)))),
                "August daily must exist from the completed checkpoint day");
        assertTrue(Files.isRegularFile(restarted.root().resolveDataRef(
                        DataPaths.dailyRef(AUTO_ITEM, YearMonth.of(2026, 9)))),
                "September daily must be rebuilt after resume");

        LocalDate firstRequested = provider.requests.get(0).historyStartDate();
        LocalDate lastRequested = provider.requests.get(provider.requests.size() - 1).historyStartDate();
        assertEquals(LocalDate.parse("2026-08-31"), firstRequested);
        assertEquals(LocalDate.parse("2026-09-01"), lastRequested);
        assertTrue(provider.requests.stream().allMatch(request ->
                        request.historyStartDate().isAfter(LocalDate.parse("2026-08-31"))
                                || request.historyStartDate().equals(LocalDate.parse("2026-08-31"))),
                "no request may ever reach back before the job start");
        assertTrue(provider.requests.stream().anyMatch(request ->
                        request.historyStartDate().equals(LocalDate.parse("2026-09-01"))),
                "resume must re-request only from checkpoint+1");
        assertEquals(1, provider.requests.stream()
                        .filter(request -> request.historyStartDate().equals(LocalDate.parse("2026-08-31")))
                        .count(),
                "a completed date may never be requested a second time after resume");
    }

    private Harness harness(String name, RequestDrivenProvider provider) {
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
        registry.register(provider);
        BackfillJobStore jobs = new BackfillJobStore(root, files, CLOCK);
        return new Harness(root, new BackfillOrchestrator(root, jobs, configs, registry,
                new RawAcquisitionStore(root, files, CLOCK), raws, timelines, validation, publish, daily, aggregate));
    }

    private Harness restart(DataRoot root, RequestDrivenProvider provider) {
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        BackfillJobStore jobs = new BackfillJobStore(root, files, CLOCK);
        TimelineStore timelines = new TimelineStore(root, files, CLOCK);
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        RawReceiptStore raws = new RawReceiptStore(root, files, CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelines, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelines,
                new QuarantineStore(root, files, CLOCK), CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);
        return new Harness(root, new BackfillOrchestrator(root, jobs, configs, registry,
                new RawAcquisitionStore(root, files, CLOCK), raws, timelines, validation, publish,
                new DailyProcessingService(root, timelines, files, CLOCK),
                new AggregateProcessingService(root, files, CLOCK)));
    }

    private static MonitorSeriesConfigV1 config() {
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, List.of(
                new MonitorSeriesItemV1(AUTO_ITEM, "M3 automatic USD", true, "M3",
                        ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, "M3 fixture source",
                        RouteDecision.PRIMARY, null, NOW, null, "USD", "1美元对人民币", "m3-fx",
                        "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP,
                        "weekday-asia-shanghai-v1", "CNY", "USD", "CNY/1 USD", null)));
    }

    /**
     * M3 contract provider: every collect() reads the explicit HISTORY range from the request
     * and returns the raw for the requested start date (the orchestrator's current cursor).
     * It never maintains its own internal day sequence.
     */
    static final class RequestDrivenProvider implements DataProvider {
        private final LocalDate rangeFrom;
        private final LocalDate rangeTo;
        private final boolean supportsItem;
        private final boolean supportsHistoryData;
        private final java.util.concurrent.atomic.AtomicReference<LocalDate> failOnDateRef;
        private final AtomicInteger collectCalls = new AtomicInteger();
        private final List<ProviderCollectRequest> requests = new ArrayList<>();

        RequestDrivenProvider(LocalDate rangeFrom, LocalDate rangeTo, boolean supportsItem,
                              boolean supportsHistoryData, LocalDate failOnDate) {
            this.rangeFrom = rangeFrom;
            this.rangeTo = rangeTo;
            this.supportsItem = supportsItem;
            this.supportsHistoryData = supportsHistoryData;
            this.failOnDateRef = new java.util.concurrent.atomic.AtomicReference<>(failOnDate);
        }

        void clearFailure() {
            failOnDateRef.set(null);
        }

        @Override public ProviderSourceProfile profile() {
            return ProviderSourceProfile.of("m3-request-driven", ProviderType.OFFICIAL_WEB,
                    AccessMethod.PUBLIC_OFFICIAL_HTML, "M3 fixture source",
                    "https://example.test/m3", true, supportsHistoryData);
        }
        @Override public Set<String> supportedItemIds() { return Set.of(AUTO_ITEM); }
        @Override public boolean supports(MonitorSeriesItemV1 item) {
            return supportsItem && item.providerType() == ProviderType.OFFICIAL_WEB
                    && "m3-fx".equals(item.rateKind());
        }
        @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
            collectCalls.incrementAndGet();
            requests.add(request);
            if (request.collectionMode() != CollectionMode.HISTORY) {
                throw new IllegalStateException("M3 backfill must always issue HISTORY requests, got "
                        + request.collectionMode());
            }
            if (request.historyStartDate().isBefore(rangeFrom) || request.historyStartDate().isAfter(rangeTo)) {
                throw new IllegalStateException("requested date outside the job range: " + request.historyStartDate());
            }
            LocalDate target = request.historyStartDate();
            if (target.equals(failOnDateRef.get())) {
                throw new IllegalStateException("injected interrupt on " + target);
            }
            byte[] payload = ("m3-auto-" + target).getBytes(StandardCharsets.UTF_8);
            String runId = "m3-auto-" + target.toString().replace("-", "");
            String acquisitionId = "m3-acq-" + target.toString().replace("-", "");
            RawReceiptV1 raw = new RawReceiptV1("1.0", RawReceiptV1.deriveRawRef(Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AUTO_ITEM, NOW, runId), acquisitionId, runId, Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1, "M3 fixture source",
                    "https://example.test/m3", "m3-ref", AUTO_ITEM, target.toString(), target.toString(),
                    null, NOW, NOW, null, "7.12340000", "CNY/1 USD", "CNY", null, 200,
                    "text/html; charset=UTF-8", "base64",
                    Base64.getEncoder().encodeToString(payload), FileDigest.sha256(payload),
                    "m3-anchor", NOW, DataPaths.acquisitionRef(acquisitionId), null);
            return new ProviderCollectOutcome("1.0", "m3-request-driven", acquisitionId, target.toString(),
                    raw.payloadSha256(), List.of(raw), List.of(), Map.of());
        }
    }

    private record Harness(DataRoot root, BackfillOrchestrator orchestrator) {
        private int rawCount() {
            Path directory = root.resolveInternalRelative("raw/formal/official_web/" + AUTO_ITEM);
            if (!Files.isDirectory(directory)) {
                return 0;
            }
            try (var stream = Files.walk(directory)) {
                return (int) stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                        .count();
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
