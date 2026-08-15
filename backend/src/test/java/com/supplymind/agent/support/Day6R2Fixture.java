package com.supplymind.agent.support;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter;
import com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter;
import com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter;
import com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.orchestration.ToolExecutor;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.CanonicalJsonV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.QualityStatus;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.history.HistoryQueryService;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/** Test-only persisted FORMAL fixture used by independent Day6 R2 attacks. */
public final class Day6R2Fixture {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    public static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    public static final String ITEM = "FX.D6.R2.USD";
    public static final String SOURCE = "PBOC test official source";
    public static final String RUN_ID = "d6r2run20260810";

    private final DataRoot root;
    private final AtomicFileStore files;
    private final String rawRef;
    private final String dailyRef;
    private final String aggregateRef;
    private final SeriesResolveToolAdapter seriesResolve;
    private final HistoryQueryToolAdapter historyQuery;
    private final PeriodMetricsToolAdapter periodMetrics;
    private final QualityInspectToolAdapter qualityInspect;
    private final CostImpactToolAdapter costImpact;
    private final WarningExplainToolAdapter warningExplain;
    private final ProvenanceTraceToolAdapter provenanceTrace;
    private final ReportStore reportStore;

    private Day6R2Fixture(DataRoot root, AtomicFileStore files, String rawRef, String dailyRef,
                          String aggregateRef, SeriesResolveToolAdapter seriesResolve,
                          HistoryQueryToolAdapter historyQuery, PeriodMetricsToolAdapter periodMetrics,
                          QualityInspectToolAdapter qualityInspect, CostImpactToolAdapter costImpact,
                          WarningExplainToolAdapter warningExplain, ProvenanceTraceToolAdapter provenanceTrace,
                          ReportStore reportStore) {
        this.root = root;
        this.files = files;
        this.rawRef = rawRef;
        this.dailyRef = dailyRef;
        this.aggregateRef = aggregateRef;
        this.seriesResolve = seriesResolve;
        this.historyQuery = historyQuery;
        this.periodMetrics = periodMetrics;
        this.qualityInspect = qualityInspect;
        this.costImpact = costImpact;
        this.warningExplain = warningExplain;
        this.provenanceTrace = provenanceTrace;
        this.reportStore = reportStore;
    }

    public static Day6R2Fixture create(Path base, String leaf) {
        DataRoot root = DataRoot.forTest(base.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT, List.of(item())));
        String rawRef = writeRaw(root, files);
        String dailyRef = writeDaily(root, files, rawRef);
        String aggregateRef = writeAggregate(root, files, dailyRef);
        HistoryQueryService history = new HistoryQueryService(root);
        ConfigManagementService configManagement = new ConfigManagementService(
                configs, new com.supplymind.provider.DataProviderRegistry());
        return new Day6R2Fixture(root, files, rawRef, dailyRef, aggregateRef,
                new SeriesResolveToolAdapter(configManagement),
                new HistoryQueryToolAdapter(history),
                new PeriodMetricsToolAdapter(history),
                new QualityInspectToolAdapter(history),
                new CostImpactToolAdapter(history),
                new WarningExplainToolAdapter(root),
                new ProvenanceTraceToolAdapter(root, history),
                new ReportStore(root, files));
    }

    public DataRoot root() { return root; }
    public AtomicFileStore files() { return files; }
    public String rawRef() { return rawRef; }
    public String dailyRef() { return dailyRef; }
    public String aggregateRef() { return aggregateRef; }
    public SeriesResolveToolAdapter seriesResolve() { return seriesResolve; }
    public HistoryQueryToolAdapter historyQuery() { return historyQuery; }
    public PeriodMetricsToolAdapter periodMetrics() { return periodMetrics; }
    public QualityInspectToolAdapter qualityInspect() { return qualityInspect; }
    public WarningExplainToolAdapter warningExplain() { return warningExplain; }
    public ProvenanceTraceToolAdapter provenanceTrace() { return provenanceTrace; }
    public CostImpactToolAdapter costImpact() { return costImpact; }
    public ReportStore reportStore() { return reportStore; }

    public AgentOrchestrator orchestrator(LLMService.Port llm, AgentResponseVerifier verifier) {
        return new AgentOrchestrator(new ToolExecutor(seriesResolve, historyQuery, periodMetrics, qualityInspect,
                costImpact, warningExplain, provenanceTrace), llm, new TemplateFallbackService(),
                new EvidenceRefVerifier(root), reportStore, verifier);
    }

    public AgentOrchestrator.AgentQueryInput formalHistoryQuery() {
        return new AgentOrchestrator.AgentQueryInput("analyse deterministic D6 fixture", ITEM,
                "2026-08-10", "2026-08-10", null, null, null, null, null, "FORMAL");
    }

    public EvidencePackV1 verifiedEvidencePack(String requestId) {
        EvidencePackV1.EvidenceRefEntry entry = new EvidenceRefVerifier(root)
                .verifyWithAuthoritativeLineage(rawRef);
        return new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-" + requestId, requestId,
                "FORMAL", "fixture", AT,
                new EvidencePackV1.Scope(List.of(ITEM), "2026-08-10", null, null, "Asia/Shanghai"),
                List.of(), List.of(new EvidencePackV1.Fact("fact-0", "history.query", ITEM,
                        "2026-08-10", null, null, "123.45678901", "CNY/1 USD", "CNY", "true",
                        "VERIFIED", "pboc-basic-validation-v1", "arithmetic-mean-v1",
                        "weekday-asia-shanghai-v1", List.of("1"), SOURCE, "fixture-source-fingerprint",
                        List.of(rawRef))), List.of(entry), List.of(), List.of(), List.of());
    }

    public AgentReportV1 validReport(String reportId, String requestId) {
        EvidencePackV1 pack = verifiedEvidencePack(requestId);
        return new AgentReportV1("AGENT-REPORT-V1", reportId, requestId, pack,
                "JAVA_TEMPLATE", null, null, true, "TEST", List.of(), List.of(), List.of(), List.of(), AT);
    }

    public static MonitorSeriesItemV1 item() {
        return new MonitorSeriesItemV1(ITEM, "D6 USD fixture", true, "PBOC",
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, SOURCE,
                RouteDecision.PRIMARY, null, AT, null, "USD", "1 USD/CNY", "fx-d6-r2",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private static String writeRaw(DataRoot root, AtomicFileStore files) {
        byte[] payload = "d6-r2-formal-fixture".getBytes(StandardCharsets.UTF_8);
        String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB, ITEM, AT, RUN_ID);
        RawReceiptV1 raw = new RawReceiptV1("1.0", rawRef, "d6r2acq20260810", RUN_ID, Mode.FORMAL,
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1, SOURCE,
                "https://example.test/pboc", "d6-r2-fixture", ITEM, "2026-08-10", "2026-08-10",
                null, AT, AT, null, "123.45678901", "CNY/1 USD", "CNY", null, 200,
                "text/html; charset=UTF-8", "base64", Base64.getEncoder().encodeToString(payload),
                FileDigest.sha256(payload), "d6-r2", AT, null, null);
        byte[] data = JsonV1Codec.encodeFile(raw);
        ManifestV1 manifest = ManifestFactory.json(rawRef, data, List.of(RUN_ID), AT);
        files.commit("d6-r2-raw", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, rawRef, data,
                        JsonV1Codec.encodeFile(manifest), true)));
        return rawRef;
    }

    private static String writeDaily(DataRoot root, AtomicFileStore files, String rawRef) {
        String ref = DataPaths.dailyRef(ITEM, YearMonth.of(2026, 8));
        DailyRecordV1 row = new DailyRecordV1("1.0", "2026-08-10", ITEM,
                ProviderType.OFFICIAL_WEB, SOURCE, AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1", List.of(1),
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "123.45678901", 1, "123.45678901", 1, 0, true, "CNY", "CNY/1 USD",
                List.of(new DailyInputRefV1(RUN_ID, rawRef, 4)), AT, null);
        byte[] data = CsvV1Codec.encodeDaily(List.of(row));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1, "2026-08-10", "2026-08-10", List.of(RUN_ID), AT);
        files.commit("d6-r2-daily", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        JsonV1Codec.encodeFile(manifest), false)));
        return ref;
    }

    private static String writeAggregate(DataRoot root, AtomicFileStore files, String dailyRef) {
        String dailyHash;
        try {
            dailyHash = JsonV1Codec.decodeFile(java.nio.file.Files.readAllBytes(
                    root.resolveDataRef(DataPaths.manifestRef(dailyRef))), ManifestV1.class).fileSha256();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
        List<AggregateRecordV1> rows = List.of(
                aggregate("2026-07", "100.00000000", dailyRef, dailyHash),
                aggregate("2026-08", "123.45678901", dailyRef, dailyHash));
        String ref = DataPaths.aggregateRef(ITEM, "month", 2026);
        byte[] data = CsvV1Codec.encodeAggregate(rows);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 2, "2026-07-01", "2026-08-31", List.of(RUN_ID), AT);
        files.commit("d6-r2-aggregate", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        JsonV1Codec.encodeFile(manifest), false)));
        return ref;
    }

    private static AggregateRecordV1 aggregate(String monthKey, String avg, String dailyRef, String dailyHash) {
        YearMonth month = YearMonth.parse(monthKey);
        return new AggregateRecordV1("1.0", AggregateGrain.MONTH, month.atDay(1).toString(),
                month.atEndOfMonth().toString(), ITEM, ProviderType.OFFICIAL_WEB, SOURCE,
                AccessMethod.PUBLIC_OFFICIAL_HTML, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", avg, 1, avg, avg, avg, 1, 0, true,
                QualityStatus.COMPLETE, "CNY", "CNY/1 USD",
                CanonicalJsonV1.sha256LowerHex(CanonicalJsonV1.sourceIdentity(
                        ProviderType.OFFICIAL_WEB, SOURCE, AccessMethod.PUBLIC_OFFICIAL_HTML)),
                List.of(new AggregateInputRefV1(dailyRef, month.atDay(1).toString(),
                        "pboc-basic-validation-v1", dailyHash)), AT, null);
    }
}
