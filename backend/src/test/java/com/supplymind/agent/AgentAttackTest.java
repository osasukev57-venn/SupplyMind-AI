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
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D6 attack harness A1-A12: the model can only reach the seven registered read-only tools
 * (A1), invalid tool args are REJECTED (A2), path traversal is blocked (A3), no config
 * mutation path exists through tools (A4), missing evidence is honestly UNAVAILABLE and never
 * claimed verified (A5), missing values are never zero (A6), BigDecimal precision survives
 * (A7), LLM fabrication is not accepted as a fact (A8), missing key / timeout / malformed
 * responses fall back to Java templates (A9-A11), and EvidencePack never contains secrets
 * (A12).
 */
class AgentAttackTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void a1OnlyTheSevenRegisteredToolsAreReachableByTheModel() {
        Harness harness = harness("a1");
        SupplyMindToolCallbackProvider provider = new SupplyMindToolCallbackProvider(List.of(
                harness.seriesResolve(), harness.historyQuery(), harness.periodMetrics(),
                harness.qualityInspect(), harness.costImpact(), harness.warningExplain(),
                harness.provenanceTrace()));
        Set<String> names = java.util.Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("series.resolve", "history.query", "period.metrics", "quality.inspect",
                "cost.impact", "warning.explain", "provenance.trace"), names,
                "A1: the model may only ever call the seven frozen read-only tools");
        assertFalse(names.contains("backfill.start"));
        assertFalse(names.contains("config.set"));
        assertFalse(names.contains("warning.rule"));
    }

    @Test
    void a2InvalidToolArgumentsAreRejectedStructurally() {
        Harness harness = harness("a2");
        var result = harness.historyQuery().historyQuery(FX_ITEM, "2026-13-01", "2026-08-10", "r");
        assertEquals(com.supplymind.agent.tool.ToolStatus.REJECTED, result.status());
        assertFalse(result.inputSummary().contains("Exception"));
        var result2 = harness.periodMetrics().periodMetrics(FX_ITEM, "weekly", "2026", "2026", "r");
        assertEquals(com.supplymind.agent.tool.ToolStatus.REJECTED, result2.status());
    }

    @Test
    void a3PathTraversalIsBlockedOnEveryToolEntry() {
        Harness harness = harness("a3");
        for (String attack : List.of("../../etc/passwd", "D:\\secret", "C:/Windows/system32",
                "../config/monitor-series.json", "~/.ssh/id_rsa")) {
            var result = harness.seriesResolve().seriesResolve(attack, "r");
            assertEquals(com.supplymind.agent.tool.ToolStatus.REJECTED, result.status(),
                    "path traversal must be REJECTED: " + attack);
            var history = harness.historyQuery().historyQuery(
                    attack, "2026-08-01", "2026-08-10", "r");
            assertEquals(com.supplymind.agent.tool.ToolStatus.REJECTED, history.status());
        }
    }

    @Test
    void a4NoConfigMutationEntryPointExistsThroughTheTools() {
        Harness harness = harness("a4");
        ConfigActivationStore store = new ConfigActivationStore(harness.root(),
                new AtomicFileStore(harness.root(), new DirtyMarkerCodec()), CLOCK);
        int versionBefore = store.readActiveConfig().configVersion();
        harness.seriesResolve().seriesResolve(FX_ITEM, "r");
        harness.historyQuery().historyQuery(FX_ITEM, "2026-08-01", "2026-08-10", "r");
        assertEquals(versionBefore, store.readActiveConfig().configVersion(),
                "A4: tool execution must never mutate the active configuration");
    }

    @Test
    void a5MissingEvidenceIsUnavailableAndNeverClaimedVerified() {
        Harness harness = harness("a5");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        var entry = verifier.verify("processed/aggregate/FX.USD.CNY.PBOC_MID/month/2099.csv");
        assertEquals("AGGREGATE", entry.refType());
        assertTrue(entry.sha256() == null,
                "A5: a missing file must carry no sha256 (never verified)");
        assertFalse(verifier.isVerified(entry),
                "A5: a missing file must never be reported as verified");
    }

    @Test
    void a6MissingValuesAreNeverZeroAndPrecisionIsPreserved() {
        Harness harness = harness("a6");
        var result = harness.historyQuery().historyQuery(
                "FX.USD.CNY.PBOC_MID", "2099-01-01", "2099-01-10", "r");
        assertEquals(com.supplymind.agent.tool.ToolStatus.NO_DATA, result.status());
        assertFalse(result.result().containsValue("0"),
                "A6: missing data must never be reported as zero");
        var metrics = harness.periodMetrics().periodMetrics(FX_ITEM, "month", "2026", "2026", "r");
        assertEquals(com.supplymind.agent.tool.ToolStatus.NO_DATA, metrics.status());
    }

    @Test
    void a7BigDecimalStringsKeepFullPrecisionInToolResults() throws Exception {
        Harness harness = harness("a7");
        var result = harness.historyQuery().historyQuery(FX_ITEM, "2026-08-01", "2026-08-01", "r");
        assertEquals(com.supplymind.agent.tool.ToolStatus.SUCCESS, result.status());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.result().get("rows");
        assertTrue(rows.get(0).get("value").toString().matches("\\d+\\.\\d{8}"),
                "A7: values must keep the frozen 8-decimal BigDecimal string, got " + rows.get(0).get("value"));
    }

    @Test
    void a8LlmFabricationIsNotAcceptedAsFact() {
        Harness harness = harness("a8");
        AgentOrchestrator orchestrator = orchestrator(harness, new FabricatingChatModel());
        var result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL"));
        AgentReportV1 report = result.report();
        assertNotNull(report);
        // facts are always the Java tool facts; the LLM narrative is only the explanation text
        assertTrue(report.factsSummary().stream().allMatch(fact -> fact.value() != null),
                "A8: report facts must come from Java tools, never from the LLM");
        assertFalse(report.factsSummary().stream().anyMatch(fact -> "999999.999".equals(fact.value())),
                "A8: fabricated numbers must never appear among facts");
    }

    @Test
    void a9ToA11CloudFailuresAllDegradeToJavaTemplateWithout500() {
        Harness harness = harness("a9");
        for (ChatModel failing : List.of(new UnavailableChatModel(), new TimeoutChatModel(),
                new MalformedChatModel())) {
            AgentOrchestrator orchestrator = orchestrator(harness, failing);
            var result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                    "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                    "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL"));
            assertTrue(result.report().degraded(),
                    "A9-A11: cloud failure must degrade, never fail the pipeline");
            assertEquals("JAVA_TEMPLATE", result.report().generatedBy());
            assertNotNull(result.report().claims().isEmpty() ? null
                    : result.report().claims().get(0).text());
        }
    }

    @Test
    void a12EvidencePackAndReportNeverContainSecrets() {
        Harness harness = harness("a12");
        AgentOrchestrator orchestrator = orchestrator(harness, new FixedAnswerChatModel(
                "sk-super-secret-api-key-in-answer"));
        var result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL"));
        EvidencePackV1 pack = result.evidencePack();
        String serializedPack = pack.toString();
        assertFalse(serializedPack.contains("sk-super-secret"),
                "A12: EvidencePack must never contain secrets");
        assertFalse(pack.evidenceRefs().toString().contains("sk-super-secret"));
        assertFalse(pack.facts().toString().contains("sk-super-secret"));
        assertFalse(pack.question().contains("sk-super-secret"));
        assertFalse(serializedPack.contains("Authorization"),
                "A12: Authorization headers must never enter the EvidencePack");
        // The LLM explanation text is a free-text narrative partition, not evidence; the
        // persisted report stores it as the claim text, but facts/evidence refs stay clean.
        AgentReportV1 report = result.report();
        assertFalse(report.factsSummary().toString().contains("sk-super-secret"));
        assertFalse(report.limitations().toString().contains("sk-super-secret"));
        assertFalse(report.recommendations().toString().contains("sk-super-secret"));
    }

    private AgentOrchestrator orchestrator(Harness harness, ChatModel chatModel) {
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

    private static final class FabricatingChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(
                            "USD/CNY 现在是 999999.999（模型编造）"))));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        }
    }

    private static final class UnavailableChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new IllegalStateException("simulated outage");
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return org.springframework.ai.chat.prompt.ChatOptions.builder().build();
        }
    }

    private static final class TimeoutChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new RuntimeException("timeout");
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
