package com.supplymind.manual;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.processing.DailyResult;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishOutcome;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.ValidationOutcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Explicit operator action after Manual intake. It does not trust the submission: the existing
 * material validation and publish gates run before daily/aggregate processing. Only a manifest-
 * verified Manual raw can enter this workflow.
 */
public final class ManualMaterialProcessingService {

    public record Result(
            String status,
            String runId,
            String itemId,
            String businessDate,
            String validationStatus,
            String validationVersion,
            String publishRef,
            String dailyRef,
            List<String> aggregateRefs,
            String message
    ) {
        public Result {
            aggregateRefs = aggregateRefs == null ? List.of() : List.copyOf(aggregateRefs);
        }
    }

    private final DataRoot dataRoot;
    private final TimelineStore timelines;
    private final LifecycleValidationService validation;
    private final LifecyclePublishService publish;
    private final DailyProcessingService daily;
    private final AggregateProcessingService aggregate;

    public ManualMaterialProcessingService(
            DataRoot dataRoot,
            TimelineStore timelines,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.timelines = Objects.requireNonNull(timelines, "timelines");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.daily = Objects.requireNonNull(daily, "daily");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
    }

    public Result process(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        LifecycleTimelineV1 initial = timelines.read(runId);
        RawReceiptV1 raw = readManualRaw(initial);
        ValidationOutcome validated = validation.process(runId);
        PublishOutcome published = publish.process(runId);
        LifecycleTimelineV1 finalTimeline = timelines.read(runId);
        if (published.action() != PublishOutcome.PublishAction.PUBLISHED
                && published.action() != PublishOutcome.PublishAction.ALREADY_PUBLISHED) {
            return new Result(
                    "REJECTED", runId, raw.itemId(), raw.sourceBusinessDate(),
                    validated.validationStatus() == null ? null : validated.validationStatus().name(),
                    validated.validationVersion(), null, null, List.of(),
                    "Manual input did not pass the frozen validation and publish gates");
        }

        LocalDate businessDate = LocalDate.parse(raw.sourceBusinessDate());
        DailyResult dailyResult = daily.processMonth(raw.itemId(), YearMonth.from(businessDate));
        AggregateProcessingService.AggregateYearResult aggregateResult =
                aggregate.processYear(raw.itemId(), businessDate.getYear());
        return new Result(
                "PUBLISHED", runId, raw.itemId(), raw.sourceBusinessDate(),
                finalTimeline.current().validationStatus().name(),
                finalTimeline.current().validationVersion(),
                finalTimeline.current().publishRef(),
                dailyResult.dailyRef(),
                aggregateResult.writtenRefs(),
                "Manual input passed validation and is now available to dashboard queries");
    }

    private RawReceiptV1 readManualRaw(LifecycleTimelineV1 timeline) {
        String rawRef = timeline.rawRef();
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath, List.of(timeline.runId()))) {
            throw new StorageException("Manual raw is missing or fails its manifest");
        }
        try {
            RawReceiptV1 raw = JsonV1Codec.decodeFile(Files.readAllBytes(rawPath), RawReceiptV1.class);
            if (raw.providerType() != ProviderType.MANUAL || !raw.runId().equals(timeline.runId())) {
                throw new StorageException("Only a Manual raw can use the manual processing workflow");
            }
            return raw;
        } catch (IOException exception) {
            throw new StorageException("Unable to read Manual raw", exception);
        }
    }
}
