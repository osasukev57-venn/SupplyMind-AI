package com.supplymind.agent;

import com.supplymind.agent.evidence.EvidencePackV1;
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
import com.supplymind.agent.report.AgentReportV1;
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
import java.nio.file.Files;
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
 * D6-T02/T03/T04/T05 integration: the full Agent pipeline over a REAL data root, with a
 * deterministic stub ChatModel that (a) proves the Spring AI ChatClient path (D6-T03), and
 * (b) in failure mode exercises the Java template fallback (D6-T05). EvidencePack follows
 * AGENT-EVIDENCE-SCHEMA-V1; the report is persisted under data/report with a manifest.
 */
class AgentPipelineIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void llmSuccessPathBuildsEvidencePackAndPersistsReport() throws Exception {
        Harness harness = harness("pipeline-llm");
        AgentOrchestrator orchestrator = orchestrator(harness, new FixedAnswerChatModel("LLM 解释"));
        AgentOrchestrator.AgentQueryInput input = new AgentOrchestrator.AgentQueryInput(
                "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL");

        AgentOrchestrator.AgentResult result = orchestrator.answer(input);

        assertEquals(LLMService.LLMStatus.SUCCESS, result.llmResponse().status());
        assertFalse(result.report().degraded());
        assertEquals("LLM", result.report().generatedBy());
        assertEquals("AGENT-EVIDENCE-SCHEMA-V1", result.evidencePack().schemaVersion());
        assertNotNull(result.evidencePack().evidencePackId());
        assertTrue(result.evidencePack().toolExecutions().size() >= 3,
                "series.resolve + history.query + quality.inspect must have run");
        assertTrue(result.evidencePack().toolExecutions().stream()
                .allMatch(execution -> execution.readOnly()));
        assertTrue(result.evidencePack().facts().stream().anyMatch(fact -> fact.value() != null));
        assertEquals("AGENT-REPORT-V1", result.report().schemaVersion());
        assertNotNull(result.reportRef());
        assertTrue(result.reportRef().startsWith("report/2026-08/"),
                "report must be persisted under data/report/YYYY-MM: " + result.reportRef());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(result.reportRef())));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.manifestRef(result.reportRef()))));
    }

    @Test
    void cloudUnavailableFallsBackToJavaTemplateWithDegradedReport() throws Exception {
        Harness harness = harness("pipeline-fallback");
        AgentOrchestrator orchestrator = orchestrator(harness, new UnavailableChatModel());

        AgentOrchestrator.AgentResult result = orchestrator.answer(
                new AgentOrchestrator.AgentQueryInput(
                        "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                        "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL"));

        assertEquals(LLMService.LLMStatus.UNAVAILABLE, result.llmResponse().status());
        assertTrue(result.report().degraded());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
        assertNotNull(result.report().degradeReason());
        String explanation = result.report().claims().isEmpty() ? null
                : result.report().claims().get(0).text();
        assertNotNull(explanation);
        assertTrue(explanation.contains("正式数据"),
                "the Java template must produce a Chinese explanation from the facts");
        assertNotNull(result.reportRef(), "fallback still persists the report");
        assertEquals("report-", result.report().reportId().substring(0, 7));
        assertEquals(result.evidencePack().requestId(), result.report().requestId());
    }

    @Test
    void malformedLlMResponseAlsoFallsBackDeterministically() throws Exception {
        Harness harness = harness("pipeline-malformed");
        AgentOrchestrator orchestrator = orchestrator(harness, new MalformedChatModel());

        AgentOrchestrator.AgentResult result = orchestrator.answer(
                new AgentOrchestrator.AgentQueryInput(
                        "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                        "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL"));

        assertEquals(LLMService.LLMStatus.MALFORMED, result.llmResponse().status());
        assertTrue(result.report().degraded());
        assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
    }

    @Test
    void missingInputIsRejectedWithoutStackTrace() {
        Harness harness = harness("pipeline-reject");
        AgentOrchestrator orchestrator = orchestrator(harness, new UnavailableChatModel());

        AgentOrchestrator.AgentResult result = orchestrator.answer(
                new AgentOrchestrator.AgentQueryInput(
                        "分析趋势", null, null, null, null, null, null, null, null, "FORMAL"));

        assertTrue(result.report().limitations().stream()
                .anyMatch(limitation -> limitation.contains("series.resolve")));
        assertTrue(result.report().degraded());
        // M4: without any verifiable evidence the report carries no formal claim - the honest
        // JAVA_TEMPLATE result is expressed through limitations, never an untraceable claim.
        if (!result.report().claims().isEmpty()) {
            String explanation = result.report().claims().get(0).text();
            assertFalse(explanation.contains("Exception"),
                    "no stack trace may reach the explanation");
        }
    }

    private AgentOrchestrator orchestrator(Harness harness, ChatModel chatModel) {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        LLMService.Port llm = new SpringAiLlmService(chatClient,
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

    private Harness harness(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
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
        for (int day = 1; day <= 10; day++) {
            String businessDate = "2026-08-" + String.format("%02d", day);
            String runId = "fx-run-" + businessDate.replace("-", "");
            String rawRef = DataPaths.rawRef("formal", "official_web", itemId, AT, runId);
            byte[] payload = ("fx-" + businessDate).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.supplymind.foundation.model.RawReceiptV1 raw = new com.supplymind.foundation.model.RawReceiptV1(
                    "1.0", rawRef, "acq-" + runId, runId, Mode.FORMAL,
                    ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                    "中国人民银行官网（授权中国外汇交易中心公布）",
                    "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                    "fx-ref", itemId, businessDate, businessDate, null, AT, AT, null,
                    String.format("7.%08d", 11000000 + day * 1000000), "CNY/1 USD", "CNY", null,
                    200, "text/html; charset=UTF-8", "base64",
                    java.util.Base64.getEncoder().encodeToString(payload),
                    com.supplymind.foundation.storage.FileDigest.sha256(payload),
                    null, AT, null, null);
            byte[] data = com.supplymind.foundation.codec.JsonV1Codec.encodeFile(raw);
            ManifestV1 manifest = ManifestFactory.json(rawRef, data, List.of(runId), AT);
            files.commit("raw-fixture-" + runId, DirtyTransactionType.SINGLE_FILE, AT,
                    List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, rawRef, data,
                            com.supplymind.foundation.codec.JsonV1Codec.encodeFile(manifest), true)));
        }
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
                "2026-08-01", "2026-08-10", rows.stream().flatMap(row -> row.inputRefs().stream()).map(DailyInputRefV1::runId).distinct().sorted().toList(), AT);
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

    private static final class FixedAnswerChatModel implements ChatModel {
        private final String answer;

        private FixedAnswerChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(answer))));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        }
    }

    private static final class UnavailableChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException("simulated cloud outage");
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        }
    }

    private static final class MalformedChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("   "))));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        }
    }
}
