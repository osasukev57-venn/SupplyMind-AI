package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.application.ModelClaimV1;
import com.supplymind.agent.application.ModelDraftV1;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
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
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.history.HistoryQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 micro-fix guard: a model draft that restates a real formal business number must cite at
 * least one valid factId or VERIFIED evidenceRef; otherwise MISSING_REQUIRED_REFERENCE ->
 * fallback. Pure explanatory text without business numbers is not rejected for missing refs.
 */
class AgentF2MissingReferenceGuardTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void numericClaimWithoutAnyReferenceIsRejectedWithMissingRequiredReference() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000", "fact-0");
        ModelDraftV1 draft = ModelDraftV1.untrusted("r1", "当前值为 7.15000000");
        AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertFalse(v.verified(), "a known business number restated without any reference must be rejected");
        assertTrue(v.reason().equals("MISSING_REQUIRED_REFERENCE"), "reason=" + v.reason());
    }

    @Test
    void numericClaimWithUnknownFactOrEvidenceRefIsStillRejected() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000", "fact-0");
        ModelDraftV1 draft = new ModelDraftV1("r1", "当前值为 7.15000000", List.of(
                new ModelClaimV1("c1", "claim", List.of("fact-unknown"), List.of())));
        AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertFalse(v.verified(), "unknown fact ref must reject the draft");
        assertTrue(v.reason().equals("UNKNOWN_FACT_REFERENCE"), "reason=" + v.reason());

        ModelDraftV1 unknownEvidence = new ModelDraftV1("r2", "当前值为 7.15000000", List.of(
                new ModelClaimV1("c1", "claim", List.of("fact-0"), List.of("raw/does/not/exist.json"))));
        AgentResponseVerifier.Verification v2 = verifier.verify(unknownEvidence, pack);
        assertFalse(v2.verified(), "unknown evidence ref must reject the draft");
        assertTrue(v2.reason().equals("UNKNOWN_EVIDENCE_REFERENCE"), "reason=" + v2.reason());
    }

    @Test
    void numericClaimWithValidFactReferenceContinuesNormalVerification() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000", "fact-0");
        ModelDraftV1 draft = new ModelDraftV1("r1", "当前值为 7.15000000", List.of(
                new ModelClaimV1("c1", "claim", List.of("fact-0"), List.of())));
        AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertTrue(v.verified(), "a referenced numeric claim may be accepted");
    }

    @Test
    void pureExplanatoryTextWithoutBusinessNumbersIsNotRejectedForMissingRefs() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000", "fact-0");
        ModelDraftV1 draft = ModelDraftV1.untrusted("r1",
                "根据正式数据，趋势总体平稳，未发现明显异常。");
        AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertTrue(v.verified(),
                "explanatory text without business numbers must not be rejected for missing references");
    }

    @Test
    void orchestratorDegradesWhenModelRestatesValueWithoutReference() {
        Harness harness = harness();
        AgentOrchestrator orchestrator = orchestrator(harness, request -> LLMService.LLMResponse.success(
                "当前值为 7.15000000"), new AgentResponseVerifier(List.of()));
        AgentOrchestrator.AgentResult result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "请分析 2026-08-10 的美元/人民币", FX_ITEM,
                "2026-08-10", "2026-08-10", null, null, null, null, null, "FORMAL"));
        assertTrue(result.degraded(), "a known value without any reference must degrade to fallback");
        assertTrue(result.degradeReason().contains("MISSING_REQUIRED_REFERENCE")
                        || result.degradeReason().contains("FABRICATED_NUMBER"),
                "reason=" + result.degradeReason());
        assertFalse(result.report().claims().isEmpty(),
                "the fallback report must still carry Java template claims");
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    private EvidencePackV1 packWithFact(String value, String factId) {
        return new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-1", "req-1", "FORMAL",
                "question", AT, new EvidencePackV1.Scope(List.of(FX_ITEM), "2026-08-10", null, null, "Asia/Shanghai"),
                List.of(), List.of(new EvidencePackV1.Fact(
                        factId, "history.query", FX_ITEM, "2026-08-10", null, null,
                        value, "CNY/1 USD", "CNY", "true", "VERIFIED", "pboc-basic-validation-v1",
                        "arithmetic-mean-v1", "weekday-asia-shanghai-v1", List.of("1"),
                        "source", "fp", List.of("processed/daily/x.csv"))),
                List.of(), List.of(), List.of(), List.of());
    }

    private AgentOrchestrator orchestrator(Harness harness, LLMService.Port llm,
                                           com.supplymind.agent.application.AgentResponseVerifier verifier) {
        return new AgentOrchestrator(
                new com.supplymind.agent.orchestration.ToolExecutor(
                        harness.seriesResolve(), harness.historyQuery(), harness.periodMetrics(),
                        harness.qualityInspect(), harness.costImpact(), harness.warningExplain(),
                        harness.provenanceTrace()),
                llm, new com.supplymind.agent.fallback.TemplateFallbackService(),
                new com.supplymind.agent.evidence.EvidenceRefVerifier(harness.root()),
                new com.supplymind.agent.report.ReportStore(harness.root(), harness.files()),
                verifier);
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("f2-" + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        writeRawFixture(root, files, FX_ITEM);
        writeDailyFixture(root, files, FX_ITEM);
        com.supplymind.config.ConfigManagementService configManagement =
                new com.supplymind.config.ConfigManagementService(configs,
                        new com.supplymind.provider.DataProviderRegistry());
        return new Harness(root, files,
                new com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter(configManagement),
                new HistoryQueryToolAdapter(history),
                new com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter(history),
                new com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter(history),
                new com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter(history),
                new WarningExplainToolAdapter(root),
                new com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter(root, history));
    }

    private static MonitorSeriesItemV1 fxItem() {
        return new MonitorSeriesItemV1(
                FX_ITEM, "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, AT, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private static void writeRawFixture(DataRoot root, AtomicFileStore files, String itemId) {
        String runId = "fx-run-20260810";
        String rawRef = DataPaths.rawRef("formal", "official_web", itemId, AT, runId);
        byte[] payload = "fx-2026-08-10".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        com.supplymind.foundation.model.RawReceiptV1 raw = new com.supplymind.foundation.model.RawReceiptV1(
                "1.0", rawRef, "acq-fx", runId, Mode.FORMAL,
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                "中国人民银行官网（授权中国外汇交易中心公布）",
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                "fx-ref", itemId, "2026-08-10", "2026-08-10", null, AT, AT, null,
                "7.15000000", "CNY/1 USD", "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                java.util.Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, AT, null, null);
        byte[] data = com.supplymind.foundation.codec.JsonV1Codec.encodeFile(raw);
        ManifestV1 manifest = com.supplymind.foundation.storage.ManifestFactory.json(
                rawRef, data, List.of(runId), AT);
        files.commit("raw-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, rawRef, data,
                        com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), true)));
    }

    private static void writeDailyFixture(DataRoot root, AtomicFileStore files, String itemId) {
        YearMonth month = YearMonth.of(2026, 8);
        java.util.ArrayList<DailyRecordV1> rows = new java.util.ArrayList<>();
        for (int day = 1; day <= 10; day++) {
            String businessDate = "2026-08-" + String.format("%02d", day);
            String runId = "fx-run-" + businessDate.replace("-", "");
            String rawRef = DataPaths.rawRef("formal", "official_web", itemId, AT, runId);
            rows.add(new DailyRecordV1(
                    "1.0", businessDate, itemId, ProviderType.OFFICIAL_WEB,
                    "中国人民银行官网（授权中国外汇交易中心公布）", AccessMethod.PUBLIC_OFFICIAL_HTML,
                    ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                    List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                    "weekday-asia-shanghai-v1", "7.15000000",
                    1, "7.15000000", 1, 0, true,
                    "CNY", "CNY/1 USD", List.of(new DailyInputRefV1(runId, rawRef, 4)),
                    AT, null));
        }
        String ref = DataPaths.dailyRef(itemId, month);
        byte[] data = CsvV1Codec.encodeDaily(rows);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, rows.size(),
                "2026-08-01", "2026-08-10",
                rows.stream().flatMap(row -> row.inputRefs().stream())
                        .map(DailyInputRefV1::runId).distinct().sorted().toList(), AT);
        files.commit("daily-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), false)));
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter seriesResolve,
            HistoryQueryToolAdapter historyQuery,
            com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter periodMetrics,
            com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter qualityInspect,
            com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter costImpact,
            WarningExplainToolAdapter warningExplain,
            com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter provenanceTrace
    ) {
    }
}
