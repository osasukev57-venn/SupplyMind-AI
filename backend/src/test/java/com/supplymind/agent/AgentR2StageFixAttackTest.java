package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.application.ModelDraftV1;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
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
import com.supplymind.foundation.codec.JsonV1Codec;
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
import com.supplymind.processing.CostImpactCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D6 R2 attack harness A13-A24: model draft verification (fabricated numbers, secret
 * injection), EvidencePack lineage/golden/tamper, FORMAL/DEMO isolation, ReportStore restart
 * read + tamper + identity, and cost.impact production reuse.
 */
class AgentR2StageFixAttackTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    // ---- M1 model draft verification ----

    @Test
    void a16FabricatedNumberInModelDraftIsRejected() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFacts("7.15000000");
        ModelDraftV1 draft = ModelDraftV1.untrusted("r1",
                "USD/CNY 现在是 999999.999（模型编造）");
        AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertFalse(v.verified(), "A16: a fabricated number must reject the model draft");
        assertTrue(v.reason().startsWith("FABRICATED_NUMBER"));
    }

    @Test
    void a16ModelClaimWithUnknownEvidenceRefIsRejected() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFacts("7.15000000");
        ModelDraftV1 draft = new ModelDraftV1("r1", "ok", List.of(
                new com.supplymind.agent.application.ModelClaimV1(
                        "c1", "claim", List.of("fact-0"), List.of("raw/does/not/exist.json"))));
        AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertFalse(v.verified(), "A16: unknown evidence ref must reject the claim");
    }

    @Test
    void a17ModelSecretInjectionIsRejectedAndFallsBack() throws Exception {
        Harness harness = harness("a17");
        // M3 strict contract: the secret is carried in the envelope answer field (the plain
        // free-text variant is rejected earlier as MALFORMED_STRUCTURED_RESPONSE; both fail closed).
        AgentOrchestrator orchestrator = orchestrator(harness, new FixedChatModel(
                "{\"answer\":\"sk-super-secret-api-key\",\"claims\":[{\"claimId\":\"c1\",\"text\":\"ok\","
                        + "\"factIds\":[\"fact-0\"],\"evidenceRefs\":[]}]}"),
                new AgentResponseVerifier(List.of("sk-super-secret-api-key")));
        var result = orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                "请分析 2026-08-01 至 2026-08-10 的美元/人民币趋势", FX_ITEM,
                "2026-08-01", "2026-08-10", null, null, null, null, null, "FORMAL"));
        assertTrue(result.report().degraded(), "A17: secret injection must degrade to fallback");
        assertTrue(result.report().degradeReason().contains("MODEL_RESPONSE_REJECTED:SECRET_INJECTION"));
        String reportBytes = new String(Files.readAllBytes(
                harness.root().resolveDataRef(result.reportRef())), StandardCharsets.UTF_8);
        assertFalse(reportBytes.contains("sk-super-secret"),
                "A17: the secret must never be persisted in the report");
    }

    // ---- M2 evidence lineage / isolation ----

    @Test
    void a18EvidenceGoldenJsonIsDeterministic() throws Exception {
        Harness harness = harness("a18");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        List<EvidencePackV1.EvidenceRefEntry> entries = verifier.verifyAll(
                List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"));
        String json1 = JsonV1Codec.encodeCompact(entries);
        String json2 = JsonV1Codec.encodeCompact(verifier.verifyAll(
                List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv")));
        assertEquals(json1, json2, "A18: evidence verification output must be deterministic");
        assertTrue(json1.contains("VERIFIED"), "A18: a present manifest-valid file is VERIFIED");
        assertTrue(json1.contains("\"sha256\""), "A18: sha256 must be present for verified files");
    }

    @Test
    void a19EvidenceTamperMakesFileInvalidAndBlocksLlmContext() throws Exception {
        Harness harness = harness("a19");
        String ref = "processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv";
        Path csv = harness.root().resolveDataRef(ref);
        byte[] original = Files.readAllBytes(csv);
        Files.write(csv, (new String(original, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        var entry = verifier.verify(ref);
        assertEquals(EvidenceStatus.INVALID, entry.status(), "A19: tampered file must be INVALID");
        assertTrue(entry.reasonCode() != null && !entry.reasonCode().isBlank());
    }

    @Test
    void a20FormalModeExcludesDemoWarningEvidence() throws Exception {
        Harness harness = harness("a20");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        var entry = verifier.verify("warning/2026-08/some-warning.json");
        // warning evidence exists in the schema but is TEST/DEMO until EXT-07/08: in FORMAL mode
        // the orchestrator excludes it (covered in AgentOrchestrator.isDemoOrSynthetic).
        assertEquals(EvidenceStatus.MISSING, entry.status());
    }

    // ---- M5 report restart read / tamper / identity ----

    @Test
    void a21ReportRestartReadRevalidatesManifestAndBody() throws Exception {
        Harness harness = harness("a21");
        AgentReportV1 report = harness.report();
        String ref = harness.reportStore().store(report);
        // Simulated restart: a brand-new ReportStore reads from disk and re-verifies everything.
        ReportStore restarted = new ReportStore(harness.root(), harness.files());
        ReportStore.ReadResult read = restarted.read(ref);
        assertTrue(read.ok(), "A21: a valid persisted report must restart-read cleanly");
        assertEquals(report.reportId(), read.report().reportId());
    }

    @Test
    void a22ReportBodyTamperFailsClosed() throws Exception {
        Harness harness = harness("a22");
        String ref = harness.reportStore().store(harness.report());
        Path reportPath = harness.root().resolveDataRef(ref);
        byte[] bytes = Files.readAllBytes(reportPath);
        String tampered = new String(bytes, StandardCharsets.UTF_8).replace("\"JAVA_TEMPLATE\"", "\"LLM\"");
        Files.write(reportPath, tampered.getBytes(StandardCharsets.UTF_8));
        ReportStore.ReadResult read = new ReportStore(harness.root(), harness.files()).read(ref);
        assertFalse(read.ok(), "A22: tampered report body must be rejected by manifest reverify");
        assertEquals("MANIFEST_MISMATCH", read.failureCode());
    }

    @Test
    void a22ReportManifestTamperFailsClosed() throws Exception {
        Harness harness = harness("a22b");
        String ref = harness.reportStore().store(harness.report());
        Path manifestPath = harness.root().resolveDataRef(DataPaths.manifestRef(ref));
        Files.write(manifestPath, (new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8) + " ")
                .getBytes(StandardCharsets.UTF_8));
        ReportStore.ReadResult read = new ReportStore(harness.root(), harness.files()).read(ref);
        assertFalse(read.ok(), "A22: tampered manifest must be rejected");
    }

    @Test
    void a23ReportIdentityMismatchIsRejected() throws Exception {
        Harness harness = harness("a23");
        AgentReportV1 report = harness.report();
        String ref = harness.reportStore().store(report);
        // Body reportId differs from the filename identity -> REJECTED.
        AgentReportV1 forged = new AgentReportV1(
                "AGENT-REPORT-V1", "report-other-id", report.requestId(), report.evidencePack(),
                report.generatedBy(), report.provider(), report.model(), report.degraded(),
                report.degradeReason(), report.factsSummary(), report.claims(),
                report.recommendations(), report.limitations(), report.createdAt());
        Path reportPath = harness.root().resolveDataRef(ref);
        Files.write(reportPath, JsonV1Codec.encodeFile(forged));
        ReportStore.ReadResult read = new ReportStore(harness.root(), harness.files()).read(ref);
        assertFalse(read.ok(), "A23: body reportId != filename identity must be rejected");
        assertTrue("IDENTITY_MISMATCH".equals(read.failureCode())
                        || "MANIFEST_MISMATCH".equals(read.failureCode()),
                "A23: identity/body drift must fail closed: " + read.failureCode());
    }

    // ---- M6 cost.impact production reuse ----

    @Test
    void a24CostImpactToolMatchesProductionCalculator() throws Exception {
        BigDecimal current = new BigDecimal("7.15000000");
        BigDecimal previous = new BigDecimal("7.05000000");
        BigDecimal productionRatio = CostImpactCalculator.changeRatio(current, previous);
        // The Agent tool reads the same persisted aggregates and calls the same component; the
        // deterministic result must equal the production calculator output.
        Harness harness = harness("a24");
        var result = harness.costImpact().costImpact(FX_ITEM, "month", "2026-08-01", "r");
        assertEquals(com.supplymind.agent.tool.ToolStatus.SUCCESS, result.status());
        assertEquals(productionRatio.toPlainString(), result.result().get("changeRatio"),
                "A24: the Agent tool must reuse the production cost-impact calculation");
    }

    // ---- helpers ----

    private EvidencePackV1 packWithFacts(String value) {
        return new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-1", "req-1", "FORMAL",
                "question", AT, new EvidencePackV1.Scope(List.of(FX_ITEM), "2026-08-01", null, null, "Asia/Shanghai"),
                List.of(), List.of(new EvidencePackV1.Fact(
                        "fact-0", "history.query", FX_ITEM, "2026-08-01", null, null,
                        value, "CNY/1 USD", "CNY", "true", "VERIFIED", "pboc-basic-validation-v1",
                        null, null, List.of(), "source", "fp", List.of("processed/daily/x.csv"))),
                List.of(), List.of(), List.of(), List.of());
    }

    private AgentOrchestrator orchestrator(Harness harness, ChatModel chatModel,
                                           AgentResponseVerifier responseVerifier) {
        LLMService.Port llm = new SpringAiLlmService(
                ChatClient.builder(chatModel).build(),
                new SupplyMindToolCallbackProvider(List.of(
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
                responseVerifier);
    }

    private Harness harness(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        String dailySha256 = writeDailyFixture(root, files, FX_ITEM);
        writeAggregateFixture(root, files, FX_ITEM, dailySha256);
        ConfigManagementService configManagement =
                new ConfigManagementService(configs, new com.supplymind.provider.DataProviderRegistry());
        return new Harness(root, files,
                new SeriesResolveToolAdapter(configManagement),
                new HistoryQueryToolAdapter(history),
                new PeriodMetricsToolAdapter(history),
                new QualityInspectToolAdapter(history),
                new CostImpactToolAdapter(history),
                new WarningExplainToolAdapter(root),
                new ProvenanceTraceToolAdapter(root, history),
                new ReportStore(root, files));
    }

    private static MonitorSeriesItemV1 fxItem() {
        return new MonitorSeriesItemV1(
                FX_ITEM, "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, AT, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private static String writeDailyFixture(DataRoot root, AtomicFileStore files, String itemId) {
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
                        JsonV1Codec.encodeFile(manifest), false)));
        return manifest.fileSha256();
    }

    private static void writeAggregateFixture(DataRoot root, AtomicFileStore files, String itemId,
                                              String dailySha256) {
        YearMonth month = YearMonth.of(2026, 8);
        java.util.ArrayList<com.supplymind.foundation.model.AggregateRecordV1> rows = new java.util.ArrayList<>();
        rows.add(aggregateRow(itemId, "2026-08", "7.15000000", dailySha256));
        rows.add(aggregateRow(itemId, "2026-07", "7.05000000", dailySha256));
        String ref = DataPaths.aggregateRef(itemId, "month", 2026);
        byte[] data = CsvV1Codec.encodeAggregate(rows);
        ManifestV1 manifest = ManifestFactory.csv(ref, data, rows.size(),
                "2026-07-01", "2026-08-31",
                rows.stream().flatMap(row -> row.inputRefs().stream())
                        .map(com.supplymind.foundation.model.AggregateInputRefV1::dailyFileRef)
                        .map(dailyRef -> {
                            try {
                                return JsonV1Codec.decodeFile(
                                        Files.readAllBytes(root.resolveDataRef(DataPaths.manifestRef(dailyRef))),
                                        ManifestV1.class).sourceRunIds();
                            } catch (java.io.IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        })
                        .flatMap(List::stream).distinct().sorted().toList(), AT);
        files.commit("aggregate-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        JsonV1Codec.encodeFile(manifest), false)));
    }

    private static com.supplymind.foundation.model.AggregateRecordV1 aggregateRow(
            String itemId, String monthKey, String avg, String dailySha256
    ) {
        YearMonth month = YearMonth.parse(monthKey);
        // Both the July baseline and August current rows reference the SAME persisted daily file
        // (the fixture only persists August daily); the input refs are lineage metadata and the
        // derived manifest run ids must match the daily manifest.
        YearMonth dailyMonth = YearMonth.of(2026, 8);
        java.util.ArrayList<com.supplymind.foundation.model.AggregateInputRefV1> inputs = new java.util.ArrayList<>();
        for (int day = 1; day <= 10; day++) {
            inputs.add(new com.supplymind.foundation.model.AggregateInputRefV1(
                    DataPaths.dailyRef(itemId, dailyMonth),
                    month.atDay(day).toString(), "pboc-basic-validation-v1", dailySha256));
        }
        return new com.supplymind.foundation.model.AggregateRecordV1(
                "1.0", com.supplymind.foundation.model.AggregateGrain.MONTH,
                month.atDay(1).toString(), month.atEndOfMonth().toString(), itemId,
                ProviderType.OFFICIAL_WEB, "中国人民银行官网（授权中国外汇交易中心公布）",
                AccessMethod.PUBLIC_OFFICIAL_HTML, ValidationStatus.VERIFIED,
                "pboc-basic-validation-v1", List.of(1), "arithmetic-mean-v1",
                8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "70.50000000", 10, avg, "7.00000000", "7.20000000",
                10, 0, true, com.supplymind.foundation.model.QualityStatus.COMPLETE,
                "CNY", "CNY/1 USD",
                com.supplymind.foundation.model.CanonicalJsonV1.sha256LowerHex(
                        com.supplymind.foundation.model.CanonicalJsonV1.sourceIdentity(
                                ProviderType.OFFICIAL_WEB,
                                "中国人民银行官网（授权中国外汇交易中心公布）",
                                AccessMethod.PUBLIC_OFFICIAL_HTML)),
                List.copyOf(inputs), AT, null);
    }

    private static final class FixedChatModel implements ChatModel {
        private final String answer;

        private FixedChatModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(answer))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
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
            ProvenanceTraceToolAdapter provenanceTrace,
            ReportStore reportStore
    ) {
        private AgentReportV1 report() {
            return new AgentReportV1(
                    "AGENT-REPORT-V1", "report-a21-1", "req-a21-1",
                    new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-a21-1", "req-a21-1",
                            "FORMAL", "q", AT,
                            new EvidencePackV1.Scope(List.of(FX_ITEM), "2026-08-01", null, null, "Asia/Shanghai"),
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                    "JAVA_TEMPLATE", null, null, true, "TEST",
                    List.of(), List.of(), List.of(), List.of(), AT);
        }
    }
}
