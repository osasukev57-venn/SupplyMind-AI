package com.supplymind.agent;

import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter;
import com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter;
import com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter;
import com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter;
import com.supplymind.agent.infrastructure.springai.SpringAiLlmService;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.orchestration.ToolExecutor;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.config.ConfigManagementService;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D6-T04 Agent API test: POST /api/agent/query returns a structured response; missing question
 * is a 400 REJECTED without stack trace; the pipeline always answers (LLM or Java fallback).
 */
class AgentApiTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void agentQueryReturnsStructuredResponseWithReportRef() throws Exception {
        Harness harness = harness();
        AgentOrchestrator orchestrator = orchestrator(harness);
        com.supplymind.agent.api.AgentQueryController controller =
                new com.supplymind.agent.api.AgentQueryController(orchestrator);

        var response = controller.query(Map.of(
                "question", "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势",
                "itemId", FX_ITEM, "startDate", "2026-08-01", "endDate", "2026-08-10"));

        assertEquals(200, response.getStatusCode().value());
        com.supplymind.agent.api.AgentQueryResponse body =
                (com.supplymind.agent.api.AgentQueryResponse) response.getBody();
        assertNotNull(body);
        assertNotNull(body.requestId());
        assertNotNull(body.answer(), "answer must be present (LLM or Java fallback)");
        assertTrue(body.degraded(), "no real LLM configured: fallback is expected and honest");
        assertNotNull(body.reportRef());
        assertTrue(body.reportRef().startsWith("report/2026-08/"));
        assertFalse(body.toolTrace().isEmpty());
        assertTrue(body.toolTrace().stream().allMatch(trace -> trace.readOnly()));
        // D8-T03: report-level projection is present and sourced from the verified report.
        assertEquals("JAVA_TEMPLATE", body.generatedBy(),
                "the fallback path is honestly labelled as JAVA_TEMPLATE");
        assertNotNull(body.scope());
        assertTrue(body.scope().itemIds().contains(FX_ITEM));
        assertNotNull(body.claims());
        assertFalse(body.claims().isEmpty(), "claims restate verified report content");
        assertTrue(body.claims().stream().allMatch(claim -> !claim.evidenceRefs().isEmpty()),
                "claims always carry traceable evidenceRefs");
        assertNotNull(body.dataThrough(), "dataThrough comes from the EvidencePack facts");
        assertNotNull(body.limitations(), "limitations are mapped from the report");
        assertTrue(body.limitations().stream().anyMatch(limit -> limit.contains("fallback")),
                "the fallback limitation is honestly mapped");
    }

    @Test
    void missingQuestionIsRejectedWithStructuredBadRequest() throws Exception {
        Harness harness = harness();
        AgentOrchestrator orchestrator = orchestrator(harness);
        com.supplymind.agent.api.AgentQueryController controller =
                new com.supplymind.agent.api.AgentQueryController(orchestrator);

        var response = controller.query(Map.of("itemId", FX_ITEM));

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("REJECTED", body.get("status"));
        assertFalse(String.valueOf(body.get("message")).contains("Exception"),
                "no stack trace may leak through the API");
    }

    private AgentOrchestrator orchestrator(Harness harness) {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("no cloud configured");
            }

            @Override
            public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
                return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
            }
        };
        LLMService.Port llm = new SpringAiLlmService(
                ChatClient.builder(chatModel).build(),
                new com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider(List.of(
                        harness.seriesResolve(), harness.historyQuery(),
                        harness.periodMetrics(), harness.qualityInspect(), harness.costImpact(),
                        harness.warningExplain(), harness.provenanceTrace())),
                "test-stub", "stub-model");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        return new AgentOrchestrator(
                new ToolExecutor(harness.seriesResolve(), harness.historyQuery(),
                        harness.periodMetrics(), harness.qualityInspect(), harness.costImpact(),
                        harness.warningExplain(), harness.provenanceTrace()),
                llm,
                new TemplateFallbackService(),
                verifier,
                new ReportStore(harness.root(), harness.files()),
                new com.supplymind.agent.application.AgentResponseVerifier(List.of()));
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("api"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        writeRawFixture(root, files, FX_ITEM);
        writeDailyFixture(root, files, FX_ITEM);
        ConfigManagementService configManagement =
                new ConfigManagementService(configs, new com.supplymind.provider.DataProviderRegistry());
        return new Harness(root, files,
                new SeriesResolveToolAdapter(configManagement),
                new HistoryQueryToolAdapter(history),
                new PeriodMetricsToolAdapter(history),
                new QualityInspectToolAdapter(history),
                new CostImpactToolAdapter(history),
                new WarningExplainToolAdapter(root),
                new ProvenanceTraceToolAdapter(root, history));
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
                    "weekday-asia-shanghai-v1", String.format("7.%08d", 11000000 + day * 1000000),
                    1, String.format("7.%08d", 11000000 + day * 1000000), 1, 0, true,
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
            SeriesResolveToolAdapter seriesResolve,
            HistoryQueryToolAdapter historyQuery,
            PeriodMetricsToolAdapter periodMetrics,
            QualityInspectToolAdapter qualityInspect,
            CostImpactToolAdapter costImpact,
            WarningExplainToolAdapter warningExplain,
            ProvenanceTraceToolAdapter provenanceTrace
    ) {
    }
}
