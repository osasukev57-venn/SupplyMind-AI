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
    private final java.util.function.BiConsumer<String, LocalDate> publishedInputListener;

    public ManualMaterialProcessingService(
            DataRoot dataRoot,
            TimelineStore timelines,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        this(dataRoot, timelines, validation, publish, daily, aggregate, (itemId, date) -> { });
    }

    public ManualMaterialProcessingService(
            DataRoot dataRoot, TimelineStore timelines, LifecycleValidationService validation,
            LifecyclePublishService publish, DailyProcessingService daily, AggregateProcessingService aggregate,
            java.util.function.BiConsumer<String, LocalDate> publishedInputListener
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.timelines = Objects.requireNonNull(timelines, "timelines");
        this.validation = Objects.requireNonNull(validation, "validation");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.daily = Objects.requireNonNull(daily, "daily");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
        this.publishedInputListener = Objects.requireNonNull(publishedInputListener, "publishedInputListener");
    }

    public Result process(String runId) {
        return processIngress(runId, ProviderType.MANUAL, "Manual input");
    }

    /**
     * Explicit operator action for a row previously accepted by LocalImportService. The same
     * frozen validation, publish, daily and aggregate gates are reused; provider identity is
     * checked before any lifecycle transition so a Manual or synthetic raw cannot cross over.
     */
    public Result processLocalImport(String runId) {
        return processIngress(runId, ProviderType.LOCAL_IMPORT, "Local import row");
    }

    private Result processIngress(String runId, ProviderType expectedProvider, String ingressName) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        LifecycleTimelineV1 initial = timelines.read(runId);
        RawReceiptV1 raw = readIngressRaw(initial, expectedProvider);
        ValidationOutcome validated = validation.process(runId);
        PublishOutcome published = publish.process(runId);
        LifecycleTimelineV1 finalTimeline = timelines.read(runId);
        if (published.action() != PublishOutcome.PublishAction.PUBLISHED
                && published.action() != PublishOutcome.PublishAction.ALREADY_PUBLISHED) {
            return new Result(
                    "REJECTED", runId, raw.itemId(), raw.sourceBusinessDate(),
                    validated.validationStatus() == null ? null : validated.validationStatus().name(),
                    validated.validationVersion(), null, null, List.of(),
                    ingressName + " did not pass the frozen validation and publish gates");
        }

        LocalDate businessDate = LocalDate.parse(raw.sourceBusinessDate());
        DailyResult dailyResult = daily.processMonth(raw.itemId(), YearMonth.from(businessDate));
        AggregateProcessingService.AggregateYearResult aggregateResult =
                aggregate.processYear(raw.itemId(), businessDate.getYear());
        String message = ingressName + " passed validation and is now available to dashboard queries";
        try {
            // Intake/PENDING never reaches this hook. Repeating processing also repairs a missed update.
            publishedInputListener.accept(raw.itemId(), businessDate);
        } catch (RuntimeException exception) {
            // Publication is already committed: do not lie that the input was rejected or roll it back.
            org.slf4j.LoggerFactory.getLogger(ManualMaterialProcessingService.class)
                    .warn("Published input {} could not refresh backfill progress", raw.itemId(), exception);
            message += "; 回填任务进度更新失败，数据已发布，请在回填任务中运行或重试";
        }
        return new Result(
                "PUBLISHED", runId, raw.itemId(), raw.sourceBusinessDate(),
                finalTimeline.current().validationStatus().name(),
                finalTimeline.current().validationVersion(),
                finalTimeline.current().publishRef(),
                dailyResult.dailyRef(),
                aggregateResult.writtenRefs(),
                message);
    }

    private RawReceiptV1 readIngressRaw(
            LifecycleTimelineV1 timeline,
            ProviderType expectedProvider
    ) {
        String rawRef = timeline.rawRef();
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath, List.of(timeline.runId()))) {
            throw new StorageException("Ingress raw is missing or fails its manifest");
        }
        try {
            RawReceiptV1 raw = JsonV1Codec.decodeFile(Files.readAllBytes(rawPath), RawReceiptV1.class);
            if (raw.providerType() != expectedProvider || !raw.runId().equals(timeline.runId())) {
                throw new StorageException("Only a " + expectedProvider.wireValue()
                        + " raw can use this processing workflow");
            }
            return raw;
        } catch (IOException exception) {
            throw new StorageException("Unable to read ingress raw", exception);
        }
    }
}
