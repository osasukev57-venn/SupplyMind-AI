package com.supplymind.backfill;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D5-T04 backfill orchestration (H08). It never re-implements providers, validation, publish,
 * daily or aggregate - it orchestrates the existing chain: for every monthly period in the
 * requested range it detects published formal input (daily file produced by the frozen
 * processing chain) and rebuilds daily/aggregate. Manual-route targets honestly report
 * AWAITING_MANUAL_INPUT instead of faking success; targets without automatic history
 * capability record the reason and never claim SUCCEEDED. Duplicate starts reuse the same
 * stable jobId; checkpoints are persisted atomically and restarts resume.
 */
public final class BackfillOrchestrator {

    private final DataRoot dataRoot;
    private final BackfillJobStore jobStore;
    private final ConfigActivationStore configStore;
    private final TimelineStore timelineStore;
    private final DailyProcessingService daily;
    private final AggregateProcessingService aggregate;

    public BackfillOrchestrator(
            DataRoot dataRoot,
            BackfillJobStore jobStore,
            ConfigActivationStore configStore,
            TimelineStore timelineStore,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
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
     * Refresh: rebuild newly completed months through the existing daily/aggregate chain and
     * advance the job state. A period is complete once its monthly daily file exists (i.e.
     * published formal data flowed through the frozen chain). Manual targets report
     * AWAITING_MANUAL_INPUT; targets without auto history capability record the reason and
     * never claim SUCCEEDED for the missing periods.
     */
    public BackfillJobStateV1 refresh(String jobId) {
        BackfillJobStateV1 job = jobStore.read(jobId);
        if (job.status() == BackfillJobStateV1.JobStatus.SUCCEEDED
                || job.status() == BackfillJobStateV1.JobStatus.FAILED) {
            return job;
        }
        MonitorSeriesConfigV1 config = configStore.readActiveConfig();
        MonitorSeriesItemV1 item = config.requireItem(job.itemId());
        boolean manual = item.providerType() == ProviderType.MANUAL
                || item.routeDecision() == RouteDecision.FALLBACK_MANUAL;
        OffsetDateTime now = OffsetDateTime.now();

        LocalDate from = LocalDate.parse(job.fromDate());
        LocalDate to = LocalDate.parse(job.toDate());
        List<String> completed = new ArrayList<>(job.completedPeriods());
        List<String> failures = new ArrayList<>(job.failureReasons());
        YearMonth cursor = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        List<YearMonth> rebuilt = new ArrayList<>();
        while (!cursor.isAfter(last)) {
            if (completed.contains(cursor.toString())) {
                cursor = cursor.plusMonths(1);
                continue;
            }
            if (Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.dailyRef(job.itemId(), cursor)))) {
                completed.add(cursor.toString());
            } else if (hasPublishedRunInMonth(job.itemId(), cursor)) {
                daily.processMonth(job.itemId(), cursor);
                aggregate.processYear(job.itemId(), cursor.getYear());
                rebuilt.add(cursor);
                if (Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.dailyRef(job.itemId(), cursor)))) {
                    completed.add(cursor.toString());
                }
            } else {
                failures.add(cursor + (manual ? ":AWAITING_MANUAL_INPUT" : ":NO_AUTO_HISTORY_CAPABILITY"));
            }
            cursor = cursor.plusMonths(1);
        }

        int total = (int) (java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(from), last) + 1);
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
        BackfillJobStateV1 updated = job.withStatus(status, completed, null, failures, now);
        jobStore.write(updated);
        return updated;
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

    private boolean isPublishedRunForMonth(String runId, String itemId, YearMonth month) {
        try {
            com.supplymind.foundation.model.LifecycleTimelineV1 timeline = timelineStore.read(runId);
            com.supplymind.foundation.model.LifecycleSnapshotV1 current = timeline.current();
            if (current.processingStage() != com.supplymind.foundation.model.ProcessingStage.PUBLISHED
                    || (current.validationStatus() != com.supplymind.foundation.model.ValidationStatus.VERIFIED
                    && current.validationStatus()
                    != com.supplymind.foundation.model.ValidationStatus.VERIFIED_WITH_NOTICE)) {
                return false;
            }
            var candidate = current.candidate();
            return candidate != null
                    && candidate.itemId().equals(itemId)
                    && YearMonth.from(LocalDate.parse(candidate.businessDate())).equals(month);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
