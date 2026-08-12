package com.supplymind.backfill;

import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawAcquisitionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D5-T04/F4 backfill orchestration over the REAL production chain. It never fakes PUBLISHED,
 * never writes daily/aggregate directly and never skips raw/validation/publish: automatic
 * targets run WAITING -&gt; RUNNING -&gt; (SUCCEEDED | PARTIAL_SUCCESS | FAILED) through
 * provider acquisition -&gt; raw persistence (with source acquisition for external HTTP
 * providers) -&gt; LifecycleValidationService -&gt; LifecyclePublishService -&gt;
 * DailyProcessingService -&gt; AggregateProcessingService; manual targets honestly report
 * AWAITING_MANUAL_INPUT and only progress after real input is published. Checkpoints advance
 * per completed business date and are persisted atomically; restarts resume from the
 * checkpoint instead of re-running all history, and duplicate starts reuse the same jobId.
 */
public final class BackfillOrchestrator {

    private final DataRoot dataRoot;
    private final BackfillJobStore jobStore;
    private final ConfigActivationStore configStore;
    private final DataProviderRegistry registry;
    private final RawAcquisitionStore acquisitionStore;
    private final RawReceiptStore rawStore;
    private final TimelineStore timelineStore;
    private final LifecycleValidationService validation;
    private final LifecyclePublishService publish;
    private final DailyProcessingService daily;
    private final AggregateProcessingService aggregate;

    public BackfillOrchestrator(
            DataRoot dataRoot,
            BackfillJobStore jobStore,
            ConfigActivationStore configStore,
            DataProviderRegistry registry,
            RawAcquisitionStore acquisitionStore,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.acquisitionStore = Objects.requireNonNull(acquisitionStore, "acquisitionStore");
        this.rawStore = Objects.requireNonNull(rawStore, "rawStore");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.daily = Objects.requireNonNull(daily, "daily");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
    }

    /** Stable job identity; a duplicate start returns the existing job (idempotent). */
    public BackfillJobStateV1 createOrResume(String itemId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new com.supplymind.foundation.storage.StorageException("backfill from must not be after to");
        }
        String jobId = "backfill-" + itemId + "-" + from + "-" + to;
        if (jobStore.exists(jobId)) {
            return jobStore.read(jobId);
        }
        MonitorSeriesConfigV1 config = configStore.readActiveConfig();
        OffsetDateTime now = OffsetDateTime.now();
        BackfillJobStateV1 job = new BackfillJobStateV1(
                "1.0", jobId, itemId, from.toString(), to.toString(),
                BackfillJobStateV1.JobStatus.WAITING, List.of(), null, List.of(),
                config.configVersion(), now, now);
        jobStore.write(job);
        return job;
    }

    /**
     * Automatic path: WAITING -&gt; RUNNING -&gt; real acquisition -&gt; raw -&gt; validation -&gt;
     * publish -&gt; daily -&gt; aggregate. Manual targets never enter RUNNING and return
     * AWAITING_MANUAL_INPUT. Restart resumes from the persisted checkpoint.
     */
    public BackfillJobStateV1 run(String jobId) {
        BackfillJobStateV1 job = jobStore.read(jobId);
        if (job.status() == BackfillJobStateV1.JobStatus.SUCCEEDED
                || job.status() == BackfillJobStateV1.JobStatus.FAILED) {
            return job;
        }
        MonitorSeriesConfigV1 config = configStore.readActiveConfig();
        MonitorSeriesItemV1 item = config.requireItem(job.itemId());
        if (isManual(item)) {
            BackfillJobStateV1 waiting = job.withStatus(
                    BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT,
                    job.completedPeriods(), job.currentCheckpoint(),
                    job.failureReasons().isEmpty() ? List.of(job.fromDate() + ":AWAITING_MANUAL_INPUT")
                            : job.failureReasons(),
                    OffsetDateTime.now());
            jobStore.write(waiting);
            return waiting;
        }
        DataProvider provider = registry.all().stream()
                .filter(candidate -> candidate.profile().providerType() == item.providerType()
                        && candidate.supports(item))
                .findFirst().orElse(null);
        if (provider == null) {
            BackfillJobStateV1 failed = job.withStatus(
                    BackfillJobStateV1.JobStatus.FAILED,
                    job.completedPeriods(), job.currentCheckpoint(),
                    List.of("NO_AUTO_PROVIDER_CAPABILITY"),
                    OffsetDateTime.now());
            jobStore.write(failed);
            return failed;
        }
        if (!provider.profile().supportsHistoryData()) {
            // M3 gate: a provider that only supports current data must never be asked for a
            // pseudo-history collect and must never claim SUCCEEDED. Frozen honest state:
            // AWAITING_MANUAL_INPUT when nothing completed, PARTIAL_SUCCESS when some periods
            // were already completed through real input. No new state is invented.
            List<String> reasons = new ArrayList<>(job.failureReasons());
            reasons.add("NO_HISTORY_CAPABILITY");
            BackfillJobStateV1.JobStatus honest = job.completedPeriods().isEmpty()
                    ? BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT
                    : BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS;
            BackfillJobStateV1 honestJob = job.withStatus(
                    honest, job.completedPeriods(), job.currentCheckpoint(), reasons, OffsetDateTime.now());
            jobStore.write(honestJob);
            return honestJob;
        }
        BackfillJobStateV1 running = job.withStatus(
                BackfillJobStateV1.JobStatus.RUNNING,
                job.completedPeriods(), job.currentCheckpoint(), job.failureReasons(), OffsetDateTime.now());
        jobStore.write(running);

        List<String> completed = new ArrayList<>(job.completedPeriods());
        List<String> failures = new ArrayList<>(job.failureReasons());
        String checkpoint = job.currentCheckpoint();
        LocalDate cursor = checkpoint == null ? LocalDate.parse(job.fromDate())
                : LocalDate.parse(checkpoint).plusDays(1);
        LocalDate to = LocalDate.parse(job.toDate());
        boolean anyProgress = false;
        while (!cursor.isAfter(to)) {
            if (hasPublishedRunForDay(job.itemId(), cursor)) {
                checkpoint = cursor.toString();
                anyProgress = true;
            } else {
                try {
                    // M3: every automatic acquisition is a HISTORY request carrying the explicit
                    // remaining range [cursor..to]; the provider returns data for the requested
                    // dates and never relies on implicit internal ordering. The checkpoint below
                    // stays bound to this range (resume continues at checkpoint+1).
                    ProviderCollectOutcome outcome = provider.collect(
                            ProviderCollectRequest.history(
                                    List.of(job.itemId()), cursor, to));
                    boolean acquired = false;
                    for (RawReceiptV1 raw : outcome.raws()) {
                        if (!raw.itemId().equals(job.itemId())
                                || !raw.sourceBusinessDate().equals(cursor.toString())) {
                            continue;
                        }
                        persistRawChain(job.itemId(), raw);
                        acquired = true;
                    }
                    if (acquired && hasPublishedRunForDay(job.itemId(), cursor)) {
                        checkpoint = cursor.toString();
                        anyProgress = true;
                    } else {
                        failures.add(cursor + ":NO_DATA_FOR_DAY");
                    }
                } catch (RuntimeException exception) {
                    failures.add(cursor + ":" + exception.getClass().getSimpleName());
                }
            }
            cursor = cursor.plusDays(1);
        }
        completed = refreshCompletedMonths(job.itemId(), completed, LocalDate.parse(job.fromDate()), to);
        BackfillJobStateV1.JobStatus status;
        if (completed.size() >= monthsBetween(job.fromDate(), job.toDate())) {
            status = BackfillJobStateV1.JobStatus.SUCCEEDED;
        } else if (anyProgress || !completed.isEmpty()) {
            status = BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS;
        } else {
            status = BackfillJobStateV1.JobStatus.FAILED;
        }
        BackfillJobStateV1 updated = job.withStatus(status, completed, checkpoint, failures, OffsetDateTime.now());
        jobStore.write(updated);
        return updated;
    }

    /**
     * Manual/resume refresh: detect real published input (external intake) per month, rebuild
     * daily/aggregate through the frozen chain, and advance the state. Restart resumes from the
     * persisted checkpoint.
     */
    public BackfillJobStateV1 refresh(String jobId) {
        BackfillJobStateV1 job = jobStore.read(jobId);
        if (job.status() == BackfillJobStateV1.JobStatus.SUCCEEDED
                || job.status() == BackfillJobStateV1.JobStatus.FAILED) {
            return job;
        }
        MonitorSeriesConfigV1 config = configStore.readActiveConfig();
        MonitorSeriesItemV1 item = config.requireItem(job.itemId());
        boolean manual = isManual(item);
        List<String> completed = new ArrayList<>(job.completedPeriods());
        List<String> failures = new ArrayList<>(job.failureReasons());
        completed = refreshCompletedMonths(job.itemId(), completed, LocalDate.parse(job.fromDate()),
                LocalDate.parse(job.toDate()));
        LocalDate from = LocalDate.parse(job.fromDate());
        LocalDate to = LocalDate.parse(job.toDate());
        YearMonth cursor = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!cursor.isAfter(last)) {
            if (!completed.contains(cursor.toString())
                    && !Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.dailyRef(job.itemId(), cursor)))
                    && !hasPublishedRunInMonth(job.itemId(), cursor)) {
                failures.add(cursor + (manual ? ":AWAITING_MANUAL_INPUT" : ":NO_AUTO_HISTORY_CAPABILITY"));
            }
            cursor = cursor.plusMonths(1);
        }
        int total = monthsBetween(job.fromDate(), job.toDate());
        BackfillJobStateV1.JobStatus status;
        if (completed.size() >= total) {
            status = BackfillJobStateV1.JobStatus.SUCCEEDED;
        } else if (completed.isEmpty()) {
            status = manual
                    ? BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT
                    : BackfillJobStateV1.JobStatus.FAILED;
        } else {
            status = BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS;
        }
        BackfillJobStateV1 updated = job.withStatus(status, completed, null, failures, OffsetDateTime.now());
        jobStore.write(updated);
        return updated;
    }

    private List<String> refreshCompletedMonths(String itemId, List<String> completed, LocalDate from, LocalDate to) {
        YearMonth cursor = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        List<String> result = new ArrayList<>(completed);
        while (!cursor.isAfter(last)) {
            if (!result.contains(cursor.toString())
                    && hasPublishedRunInMonth(itemId, cursor)) {
                daily.processMonth(itemId, cursor);
                aggregate.processYear(itemId, cursor.getYear());
                if (Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.dailyRef(itemId, cursor)))) {
                    result.add(cursor.toString());
                }
            }
            cursor = cursor.plusMonths(1);
        }
        return result;
    }

    private void persistRawChain(String itemId, RawReceiptV1 raw) {
        if (raw.providerType().isExternalHttpProvider()) {
            acquisitionStore.store(acquisitionFor(raw));
        }
        rawStore.store(raw);
        timelineStore.createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
        validation.process(raw.runId());
        publish.process(raw.runId());
    }

    private static RawAcquisitionV1 acquisitionFor(RawReceiptV1 raw) {
        return new RawAcquisitionV1(
                "1.0", DataPaths.acquisitionRef(raw.acquisitionId()), raw.acquisitionId(),
                raw.mode(), raw.providerType(), raw.accessMethod(), raw.configVersion(),
                raw.actualSourceName(), raw.sourceUrl() == null ? "https://example.test/list" : raw.sourceUrl(),
                raw.sourceUrl() == null ? "https://example.test/detail" : raw.sourceUrl(),
                raw.httpStatus(), raw.contentType(), raw.receivedAt(),
                "base64", raw.payloadBase64(), raw.payloadSha256());
    }

    private static boolean isManual(MonitorSeriesItemV1 item) {
        return item.providerType() == ProviderType.MANUAL
                || item.routeDecision() == RouteDecision.FALLBACK_MANUAL;
    }

    private static int monthsBetween(String from, String to) {
        return (int) (java.time.temporal.ChronoUnit.MONTHS.between(
                YearMonth.parse(from.substring(0, 7)), YearMonth.parse(to.substring(0, 7))) + 1);
    }

    private boolean hasPublishedRunInMonth(String itemId, YearMonth month) {
        java.nio.file.Path staging = dataRoot.resolveInternalRelative("staging");
        if (!Files.isDirectory(staging)) {
            return false;
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(staging)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .map(path -> path.getFileName().toString()
                            .substring(0, path.getFileName().toString().length() - ".json".length()))
                    .anyMatch(runId -> isPublishedRunForMonth(runId, itemId, month));
        } catch (java.io.IOException exception) {
            throw new com.supplymind.foundation.storage.StorageException(
                    "Unable to scan staging for backfill", exception);
        }
    }

    private boolean hasPublishedRunForDay(String itemId, LocalDate businessDate) {
        java.nio.file.Path staging = dataRoot.resolveInternalRelative("staging");
        if (!Files.isDirectory(staging)) {
            return false;
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.list(staging)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .map(path -> path.getFileName().toString()
                            .substring(0, path.getFileName().toString().length() - ".json".length()))
                    .anyMatch(runId -> isPublishedRunForDay(runId, itemId, businessDate));
        } catch (java.io.IOException exception) {
            throw new com.supplymind.foundation.storage.StorageException(
                    "Unable to scan staging for backfill", exception);
        }
    }

    private boolean isPublishedRunForDay(String runId, String itemId, LocalDate businessDate) {
        try {
            LifecycleTimelineV1 timeline = timelineStore.read(runId);
            LifecycleSnapshotV1 current = timeline.current();
            return current.processingStage() == ProcessingStage.PUBLISHED
                    && (current.validationStatus() == ValidationStatus.VERIFIED
                    || current.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE)
                    && current.candidate() != null
                    && current.candidate().itemId().equals(itemId)
                    && current.candidate().businessDate().equals(businessDate.toString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isPublishedRunForMonth(String runId, String itemId, YearMonth month) {
        try {
            LifecycleTimelineV1 timeline = timelineStore.read(runId);
            LifecycleSnapshotV1 current = timeline.current();
            return current.processingStage() == ProcessingStage.PUBLISHED
                    && (current.validationStatus() == ValidationStatus.VERIFIED
                    || current.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE)
                    && current.candidate() != null
                    && current.candidate().itemId().equals(itemId)
                    && YearMonth.from(LocalDate.parse(current.candidate().businessDate())).equals(month);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
