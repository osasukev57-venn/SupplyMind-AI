package com.supplymind.demo;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.localimport.SyntheticDemoDataProvider;
import com.supplymind.processing.AggregateCalculator;
import com.supplymind.processing.AggregateInput;
import com.supplymind.processing.DailyInput;
import com.supplymind.processing.DailyMeanCalculator;
import com.supplymind.processing.PeriodBoundaries;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.validation.MaterialCandidateStandardizer;
import com.supplymind.validation.MaterialCandidateValidatorV2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One-click deterministic showcase using production models/calculators while remaining outside
 * every FORMAL query, publish, warning and Agent evidence path.
 */
public final class DemoShowcaseService {
    public static final String SCENARIO_ID = "supplymind-demo-showcase-v1";
    private static final OffsetDateTime SCENARIO_TIME =
            OffsetDateTime.parse("2026-08-10T09:30:00+08:00");
    private static final List<String> STAGES = List.of(
            "RAW_CAPTURED", "PARSED", "VALIDATED", "DEMO_PROJECTED",
            "DAILY_CALCULATED", "MONTH_QUARTER_HALFYEAR_YEAR_CALCULATED",
            "WARNING_EVALUATED", "COMPLETE");

    private final DataRoot dataRoot;
    private final AtomicFileStore files;
    private final TimelineStore timelines;
    private final DataProviderRegistry providers;

    public DemoShowcaseService(
            DataRoot dataRoot, AtomicFileStore files, TimelineStore timelines,
            DataProviderRegistry providers
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.files = Objects.requireNonNull(files, "files");
        this.timelines = Objects.requireNonNull(timelines, "timelines");
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public DemoShowcaseRunV1 run() {
        var provider = providers.require(SyntheticDemoDataProvider.PROVIDER_ID);
        var outcome = provider.collect(ProviderCollectRequest.current(
                provider.supportedItemIds().stream().sorted().toList()));
        List<DemoShowcaseRunV1.DemoItemResult> results = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        for (RawReceiptV1 raw : outcome.raws()) {
            persistRaw(raw);
            CandidateV1 candidate = new MaterialCandidateStandardizer().standardize(raw).candidate();
            if (candidate == null) {
                throw new IllegalStateException("Synthetic demo fixture failed standardization");
            }
            MonitorSeriesItemV1 item = demoItem(raw.itemId());
            var verdict = new MaterialCandidateValidatorV2().validate(
                    raw, candidate, item, Mode.DEMO, LocalDate.parse(raw.sourceBusinessDate()), List.of());
            if (verdict.validationStatus() != ValidationStatus.VERIFIED) {
                throw new IllegalStateException("Synthetic demo fixture failed deterministic validation: "
                        + verdict.reasonCode());
            }
            persistValidatedTimeline(raw, candidate, verdict.validationStatus());
            String demoAverage = new BigDecimal(candidate.value())
                    .setScale(item.calculationScale(), item.roundingMode())
                    .toPlainString();
            results.add(new DemoShowcaseRunV1.DemoItemResult(
                    raw.itemId(), raw.runId(), raw.rawRef(), raw.payloadSha256(),
                    candidate.businessDate(), raw.actualSourceName(), candidate.value(), candidate.unit(),
                    verdict.validationStatus().wireValue(), MaterialCandidateValidatorV2.VALIDATION_VERSION,
                    demoAverage, demoAverage, demoAverage,
                    demoAverage, demoAverage,
                    "NOT_TRIGGERED_NO_COMPARABLE_BASELINE"));
            runIds.add(raw.runId());
        }
        DemoShowcaseRunV1 report = new DemoShowcaseRunV1(
                SchemaV1.VERSION, DemoShowcaseRunV1.ref(SCENARIO_ID), SCENARIO_ID,
                SyntheticDemoDataProvider.SCENARIO_VERSION, "DEMO", "COMPLETE",
                SCENARIO_TIME, STAGES, List.copyOf(results));
        byte[] bytes = JsonV1Codec.encodeFile(report);
        byte[] manifest = JsonV1Codec.encodeFile(ManifestFactory.json(
                report.demoRef(), bytes, runIds.stream().sorted().toList(), SCENARIO_TIME));
        files.commit("demo-showcase-report-v1", DirtyTransactionType.SINGLE_FILE, SCENARIO_TIME,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE,
                        report.demoRef(), bytes, manifest, true)));
        return report;
    }

    private void persistRaw(RawReceiptV1 raw) {
        byte[] bytes = JsonV1Codec.encodeFile(raw);
        byte[] manifest = JsonV1Codec.encodeFile(
                ManifestFactory.json(raw.rawRef(), bytes, List.of(raw.runId()), SCENARIO_TIME));
        files.commit("demo-raw-" + raw.runId(), DirtyTransactionType.SINGLE_FILE, SCENARIO_TIME,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE,
                        raw.rawRef(), bytes, manifest, true)));
    }

    private void persistValidatedTimeline(RawReceiptV1 raw, CandidateV1 candidate, ValidationStatus status) {
        var timelinePath = dataRoot.resolveDataRef(
                com.supplymind.foundation.storage.DataPaths.stagingRef(raw.runId()));
        var timeline = Files.exists(timelinePath)
                ? timelines.read(raw.runId())
                : timelines.createInitial(raw.runId(), raw.rawRef(), SCENARIO_TIME);
        if (timeline.currentRecordVersion() == 1) {
            timeline = timelines.append(raw.runId(), new LifecycleSnapshotV1(
                    2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate,
                    null, null, null, null, null, SCENARIO_TIME));
        }
        if (timeline.currentRecordVersion() == 2) {
            timeline = timelines.append(raw.runId(), new LifecycleSnapshotV1(
                    3, ProcessingStage.VALIDATED, status, candidate,
                    null, MaterialCandidateValidatorV2.VALIDATION_VERSION,
                    SCENARIO_TIME, null, null, SCENARIO_TIME));
        }
        if (timeline.currentRecordVersion() != 3
                || timeline.current().processingStage() != ProcessingStage.VALIDATED
                || timeline.current().validationStatus() != status
                || !candidate.equals(timeline.current().candidate())) {
            throw new IllegalStateException("Existing DEMO timeline is not the deterministic validated scenario: "
                    + raw.runId());
        }
    }
    private static MonitorSeriesItemV1 demoItem(String itemId) {
        String spec = itemId.contains("AZ91D") ? "AZ91D" : "ADC12";
        return new MonitorSeriesItemV1(
                itemId, spec + "完整流程演示", true, "DEMO",
                com.supplymind.foundation.model.ProviderType.SYNTHETIC_DEMO,
                com.supplymind.foundation.model.AccessMethod.SYNTHETIC_DEMO,
                "演示合成数据（SyntheticDemo）", RouteDecision.SYNTHETIC_DEMO, null,
                SCENARIO_TIME, null, spec, "fixture-value", "material",
                MonitorSeriesDefaults.CALCULATION_VERSION, 2, 2, RoundingMode.HALF_UP,
                MonitorSeriesDefaults.CALENDAR_VERSION, "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, spec, List.of()));
    }
}
