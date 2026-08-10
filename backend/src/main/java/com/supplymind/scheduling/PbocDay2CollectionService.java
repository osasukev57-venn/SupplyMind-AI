package com.supplymind.scheduling;

import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.processing.DailyResult;
import com.supplymind.provider.pboc.PbocCollectionResult;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishOutcome;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.ValidationOutcome;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * D2-T05 immediate cycle orchestration: real PBOC collection followed by the frozen Day 2
 * chain raw -> validation -> publish gate -> daily CSV -> four-grain aggregate CSV.
 * Every stage is idempotent (stage guards); a repeated trigger for the same business date
 * fails closed at the raw layer with frozen conflict evidence and never double-publishes.
 */
public final class PbocDay2CollectionService {

    private final PbocOfficialWebDataProvider provider;
    private final TimelineStore timelineStore;
    private final LifecycleValidationService validation;
    private final LifecyclePublishService publish;
    private final DailyProcessingService daily;
    private final AggregateProcessingService aggregate;

    public PbocDay2CollectionService(
            PbocOfficialWebDataProvider provider,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.daily = Objects.requireNonNull(daily, "daily");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
    }

    public Day2CycleResult runImmediateCycle() {
        PbocCollectionResult collected = provider.collectLatestAnnouncement();
        RawReceiptV1 usdRaw = collected.usdRaw();
        RawReceiptV1 eurRaw = collected.eurRaw();
        for (String runId : List.of(usdRaw.runId(), eurRaw.runId())) {
            ValidationOutcome validationOutcome = validation.process(runId);
            Objects.requireNonNull(validationOutcome, "validation outcome");
            PublishOutcome publishOutcome = publish.process(runId);
            Objects.requireNonNull(publishOutcome, "publish outcome");
        }
        LocalDate businessDate = LocalDate.parse(collected.businessDate());
        YearMonth month = YearMonth.from(businessDate);

        DailyResult usdDaily = daily.processMonth(MonitorSeriesDefaults.USD_CNY_ITEM_ID, month);
        DailyResult eurDaily = daily.processMonth(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, month);
        AggregateProcessingService.AggregateYearResult usdAggregate =
                aggregate.processYear(MonitorSeriesDefaults.USD_CNY_ITEM_ID, businessDate.getYear());
        AggregateProcessingService.AggregateYearResult eurAggregate =
                aggregate.processYear(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, businessDate.getYear());

        return new Day2CycleResult(
                collected.businessDate(),
                collected.acquisitionId(),
                collected.payloadSha256(),
                usdRaw.runId(),
                eurRaw.runId(),
                usdRaw.rawRef(),
                eurRaw.rawRef(),
                usdRaw.rawValue(),
                eurRaw.rawValue(),
                usdDaily.dailyRef(),
                eurDaily.dailyRef(),
                usdAggregate.writtenRefs(),
                eurAggregate.writtenRefs(),
                usdDaily.rows().size(),
                eurDaily.rows().size());
    }

    /**
     * Re-trigger guard used by the AT-SRC-002 idempotency step: the repeat collection for the
     * same business date must fail closed at the raw layer (frozen conflict evidence) instead
     * of publishing twice. Delegates to the provider so the raw conflict contract stays intact.
     */
    public PbocCollectionResult collectRepeatForSameBusinessDate() {
        return provider.collectLatestAnnouncement();
    }

    public TimelineStore timelineStore() {
        return timelineStore;
    }
}
