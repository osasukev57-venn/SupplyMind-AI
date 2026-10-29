package com.supplymind.backfill;

import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.processing.ExpectedBusinessDayCounter;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.validation.VersionedConfigReader;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Reconciles external published input without collecting, validating or publishing on its behalf. */
final class PublishedBackfillReconciler {
    private final DataRoot root;
    private final BackfillJobStore jobs;
    private final PublishedQueryService published;
    private final DailyProcessingService daily;
    private final AggregateProcessingService aggregate;

    PublishedBackfillReconciler(DataRoot root, BackfillJobStore jobs, TimelineStore timelines,
                               DailyProcessingService daily, AggregateProcessingService aggregate) {
        this.root = root;
        this.jobs = jobs;
        this.published = new PublishedQueryService(root, timelines, Clock.systemUTC());
        this.daily = daily;
        this.aggregate = aggregate;
    }

    BackfillJobStateV1 reconcile(BackfillJobStateV1 job, boolean useBusinessCalendar,
                                 String missingReason, List<String> capabilityReasons) {
        LocalDate from = LocalDate.parse(job.fromDate());
        LocalDate to = LocalDate.parse(job.toDate());
        // The required calendar is bound to the job's creation config, not a later config change.
        MonitorSeriesItemV1 item = VersionedConfigReader.readVersion(root, job.configVersion())
                .requireItem(job.itemId());
        Set<LocalDate> dates = published.findPublishedRange(job.itemId(), from, to).stream()
                .map(row -> LocalDate.parse(row.businessDate())).collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        Set<YearMonth> incomplete = new TreeSet<>();
        Set<YearMonth> rebuildMonths = new TreeSet<>();
        String checkpoint = null;
        boolean gap = false;
        int eligibleDays = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            boolean required = !useBusinessCalendar
                    || ExpectedBusinessDayCounter.expectedCount(item.calendarVersion(), date, date) > 0;
            if (dates.contains(date)) {
                rebuildMonths.add(YearMonth.from(date));
            }
            if (required && !dates.contains(date)) {
                gap = true;
                incomplete.add(YearMonth.from(date));
                missing.add(date + ":" + missingReason);
            } else if (required) {
                eligibleDays++;
            }
            // A checkpoint covers only a contiguous range; later input cannot leap a gap.
            if (!gap) {
                checkpoint = date.toString();
            }
        }
        // Reuse the production derived-data chain. No success is persisted if rebuilding fails.
        for (YearMonth month : rebuildMonths) {
            daily.processMonth(job.itemId(), month);
        }
        for (int year : rebuildMonths.stream().map(YearMonth::getYear).collect(Collectors.toCollection(TreeSet::new))) {
            aggregate.processYear(job.itemId(), year);
        }
        List<String> completed = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            // This says only that the requested slice of this month is complete, not the full month.
            if (!incomplete.contains(month)) {
                completed.add(month.toString());
            }
        }
        BackfillJobStateV1.JobStatus status = missing.isEmpty() ? BackfillJobStateV1.JobStatus.SUCCEEDED
                : eligibleDays > 0 ? BackfillJobStateV1.JobStatus.PARTIAL_SUCCESS
                : BackfillJobStateV1.JobStatus.AWAITING_MANUAL_INPUT;
        if (!missing.isEmpty()) {
            missing.addAll(capabilityReasons);
        }
        if (status == job.status() && completed.equals(job.completedPeriods())
                && java.util.Objects.equals(checkpoint, job.currentCheckpoint())
                && missing.equals(job.failureReasons())) {
            return job;
        }
        // Construct explicitly: withStatus(null checkpoint) retains the OLD checkpoint.
        var updated = new BackfillJobStateV1(job.schemaVersion(), job.jobId(), job.itemId(),
                job.fromDate(), job.toDate(), status, completed, checkpoint, missing,
                job.configVersion(), job.createdAt(), OffsetDateTime.now());
        jobs.write(updated);
        return updated;
    }
}
