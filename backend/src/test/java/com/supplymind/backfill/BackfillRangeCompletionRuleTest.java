package com.supplymind.backfill;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
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
 * F2 range-completion rule attacks: SUCCEEDED is decided by the requested range vs the
 * contiguous completed high-water mark, never by "any raw/published/daily/aggregate exists"
 * or "the month has a file".
 *
 * Case A: 8/1 + 8/2 success, 8/3 fail -&gt; PARTIAL_SUCCESS, checkpoint=8/2, resume requests
 *         only 8/3, final success -&gt; SUCCEEDED.
 * Case B: 8/1 success, 8/2 fail, 8/3 data exists -&gt; the checkpoint must NOT skip over 8/2;
 *         resume must first handle 8/2.
 * Case C: all dates succeed -&gt; SUCCEEDED.
 * Case D: duplicate start -&gt; completed dates are never re-collected.
 */
class BackfillRangeCompletionRuleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-03T10:00:00+08:00");
    private static final String ITEM = "FX.F2.USD.CNY";
    private static final LocalDate FROM = LocalDate.parse("2026-08-01");
    private static final LocalDate TO = LocalDate.parse("2026-08-03");

    @TempDir
    Path temporaryDirectory;

    @Test
    void caseAAugustFirstSecondSuccessThirdFailIsPartialWithCheckpointOnSecond() throws Exception {
        RangeBlockedProvider interrupted = new RangeBlockedProvider(Set.of(TO));
        Harness first = harness("f2-case-a", interrupted);
        BackfillJobStateV1 job = first.orchestrator().createOrResume(ITEM, FROM, TO);

        BackfillJobStateV1 partial = first.orchestrator().run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS, partial.status(),
                "8/3 failure must not be reported as SUCCEEDED");
        assertFalse(partial.status() == BackfillJobStateV1.JobStatus.SUCCEEDED);
        assertEquals(LocalDate.parse("2026-08-02").toString(), partial.currentCheckpoint(),
                "the checkpoint is the contiguous completed high-water (8/1, 8/2)");
        assertEquals(List.of(FROM, FROM.plusDays(1), TO), interrupted.requestStarts(),
                "one HISTORY request per remaining day");
        assertEquals(2, first.rawCount());

        RangeBlockedProvider resumed = new RangeBlockedProvider(Set.of());
        Harness restarted = restart(first.root(), resumed);
        BackfillJobStateV1 completed = restarted.orchestrator().run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, completed.status());
        assertEquals(TO.toString(), completed.currentCheckpoint());
        assertEquals(List.of(TO), resumed.requestStarts(),
                "resume must request ONLY the remaining day 8/3");
        assertEquals(3, restarted.rawCount(), "resume must not re-collect 8/1 or 8/2");
    }

    @Test
    void caseBFailedDateCanNeverBeSkippedByALaterSuccess() throws Exception {
        RangeBlockedProvider provider = new RangeBlockedProvider(Set.of(FROM.plusDays(1)));
        Harness harness = harness("f2-case-b", provider);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(ITEM, FROM, TO);

        BackfillJobStateV1 result = harness.orchestrator().run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS, result.status());
        assertEquals(FROM.toString(), result.currentCheckpoint(),
                "8/1 success and 8/2 failure must leave the contiguous high-water at 8/1 - "
                        + "a later 8/3 success must never skip over the failed 8/2");
        assertTrue(result.failureReasons().stream().anyMatch(reason -> reason.contains("2026-08-02")),
                "the failed date must be recorded as an unresolved required date");
        assertEquals(List.of(FROM, FROM.plusDays(1), TO), provider.requestStarts(),
                "8/3 was still requested (it must not be skipped) but the checkpoint stays at 8/1");

        RangeBlockedProvider resumed = new RangeBlockedProvider(Set.of());
        Harness restarted = restart(harness.root(), resumed);
        BackfillJobStateV1 completed = restarted.orchestrator().run(job.jobId());
        assertEquals(FROM.plusDays(1), resumed.requestStarts().get(0),
                "resume must FIRST re-handle the failed 8/2 before any later day");
        assertEquals(TO.toString(), completed.currentCheckpoint(),
                "after 8/2 and 8/3 both complete the contiguous range reaches the end");
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, completed.status());
    }

    @Test
    void caseCAllDatesSucceedIsSucceeded() throws Exception {
        RangeBlockedProvider provider = new RangeBlockedProvider(Set.of());
        Harness harness = harness("f2-case-c", provider);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(ITEM, FROM, TO);

        BackfillJobStateV1 succeeded = harness.orchestrator().run(job.jobId());

        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, succeeded.status());
        assertEquals(TO.toString(), succeeded.currentCheckpoint());
        assertEquals(List.of(FROM, FROM.plusDays(1), TO), provider.requestStarts());
        assertEquals(3, harness.rawCount());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.dailyRef(ITEM, YearMonth.of(2026, 8)))));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.aggregateRef(ITEM, "month", 2026))));
    }

    @Test
    void caseDDuplicateStartNeverReCollectsCompletedDates() throws Exception {
        RangeBlockedProvider provider = new RangeBlockedProvider(Set.of());
        Harness harness = harness("f2-case-d", provider);
        BackfillJobStateV1 job = harness.orchestrator().createOrResume(ITEM, FROM, TO);
        harness.orchestrator().run(job.jobId());

        BackfillJobStateV1 duplicate = harness.orchestrator().createOrResume(ITEM, FROM, TO);

        assertEquals(job.jobId(), duplicate.jobId(), "duplicate start must reuse the same job");
        assertEquals(BackfillJobStateV1.JobStatus.SUCCEEDED, duplicate.status());
        assertEquals(3, provider.requestStarts().size(),
                "a completed job must never issue new acquisition requests");
    }

    private Harness harness(String name, RangeBlockedProvider provider) {
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

    private Harness restart(DataRoot root, RangeBlockedProvider provider) {
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
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT, List.of(new MonitorSeriesItemV1(
                ITEM, "F2 range USD", true, "F2", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "F2 request-derived provider", RouteDecision.PRIMARY,
                null, AT, null, "USD", "1美元对人民币", "f2-fx", "arithmetic-mean-v1", 8, 4,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", "CNY", "USD", "CNY/1 USD", null)));
    }

    private static final class RangeBlockedProvider implements DataProvider {
        private final Set<LocalDate> blockedDates;
        private final List<ProviderCollectRequest> observedRequests = new ArrayList<>();

        private RangeBlockedProvider(Set<LocalDate> blockedDates) {
            this.blockedDates = Set.copyOf(blockedDates);
        }

        @Override public ProviderSourceProfile profile() {
            return ProviderSourceProfile.of("f2-range-provider", ProviderType.OFFICIAL_WEB,
                    AccessMethod.PUBLIC_OFFICIAL_HTML, "F2 request-derived provider",
                    "https://example.test/f2", true, true);
        }
        @Override public Set<String> supportedItemIds() { return Set.of(ITEM); }
        @Override public boolean supports(MonitorSeriesItemV1 item) {
            return item.providerType() == ProviderType.OFFICIAL_WEB && "f2-fx".equals(item.rateKind());
        }
        @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
            observedRequests.add(request);
            if (request.collectionMode() != CollectionMode.HISTORY) {
                throw new IllegalStateException("backfill must issue HISTORY requests");
            }
            LocalDate date = request.historyStartDate();
            if (blockedDates.contains(date)) {
                throw new IllegalStateException("request-derived interrupt for " + date);
            }
            byte[] payload = ("f2-request-derived-" + date).getBytes(StandardCharsets.UTF_8);
            String runId = "f2-range-" + date.toString().replace("-", "");
            String acquisitionId = "f2-acq-" + date.toString().replace("-", "");
            RawReceiptV1 raw = new RawReceiptV1("1.0", RawReceiptV1.deriveRawRef(Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, ITEM, AT, runId), acquisitionId, runId, Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                    "F2 request-derived provider", "https://example.test/f2", "f2-range", ITEM,
                    date.toString(), date.toString(), null, AT, AT, null, "7.12340000", "CNY/1 USD", "CNY",
                    null, 200, "text/html; charset=UTF-8", "base64",
                    Base64.getEncoder().encodeToString(payload), FileDigest.sha256(payload),
                    "f2-anchor", AT, DataPaths.acquisitionRef(acquisitionId), null);
            return new ProviderCollectOutcome("1.0", "f2-range-provider", acquisitionId, date.toString(),
                    raw.payloadSha256(), List.of(raw), List.of(), Map.of());
        }

        List<LocalDate> requestStarts() {
            return observedRequests.stream().map(ProviderCollectRequest::historyStartDate).toList();
        }
    }

    private record Harness(DataRoot root, BackfillOrchestrator orchestrator) {
        private int rawCount() {
            Path directory = root.resolveInternalRelative("raw/formal/official_web/" + ITEM);
            if (!Files.isDirectory(directory)) return 0;
            try (var stream = Files.walk(directory)) {
                return (int) stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                        .count();
            } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
