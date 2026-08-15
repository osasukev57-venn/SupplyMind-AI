package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.application.ModelClaimV1;
import com.supplymind.agent.application.ModelDraftV1;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.SpringAiLlmService;
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
import com.supplymind.agent.infrastructure.springai.ToolExecutionLedger;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.orchestration.ToolExecutor;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Final Stage Major fixes M1-M5 attack harness.
 *
 * M1: model-selected ToolResults build the formal EvidencePack (no dual chain).
 * M2: EvidencePack keeps all four statuses + per-tool lineage; non-VERIFIED never reach LLM.
 * M3: structured claims + full-form numeric fabrication guard.
 * M4: report/evidence immutable binding; claims never empty-refs.
 * M5: user-input secret guard + strict mode.
 */
class AgentFinalStageMajorFixAttackTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    // ---- M1: model-selected ToolResult is the formal evidence ----

    @Test
    void m1ModelSelectedToolResultBuildsTheFormalEvidencePack() throws Exception {
        Harness harness = harness("m1-chain");
        ToolSelectingModel model = new ToolSelectingModel("history.query",
                "{\"itemId\":\"" + FX_ITEM + "\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-10\",\"requestId\":\"m1\"}");
        LLMService.Port llm = new SpringAiLlmService(ChatClient.builder(model).build(),
                new SupplyMindToolCallbackProvider(List.of(harness.historyQuery(), harness.warningExplain())),
                "test", "test-model");

        AgentOrchestrator.AgentResult result = harness.orchestrator(llm).answer(harness.formalQuery());

        // Phase A selected history.query; the formal EvidencePack must contain its ToolResult.
        assertTrue(result.evidencePack().toolExecutions().stream()
                        .anyMatch(execution -> execution.toolName().equals("history.query")),
                "the model-selected tool must be in the formal EvidencePack");
        assertTrue(result.evidencePack().facts().stream()
                        .anyMatch(fact -> "123.45678901".equals(fact.value())),
                "the fact must come from the model-selected history.query execution");
        // Every ToolExecution evidenceRef exists in the top-level EvidencePack.
        for (EvidencePackV1.ToolExecution execution : result.evidencePack().toolExecutions()) {
            for (String ref : execution.evidenceRefs()) {
                assertTrue(result.evidencePack().evidenceRefs().stream()
                                .anyMatch(entry -> entry.ref().equals(ref)),
                        "ToolExecution evidenceRef must exist at top level: " + ref);
            }
        }
        // A tool that was NOT selected must not appear as executed.
        assertTrue(result.evidencePack().toolExecutions().stream()
                        .noneMatch(execution -> execution.toolName().equals("warning.explain")));
    }

    @Test
    void m1UnselectedToolsAreNeverFakedAsExecuted() {
        Harness harness = harness("m1-unselected");
        ToolSelectingModel model = new ToolSelectingModel("history.query",
                "{\"itemId\":\"" + FX_ITEM + "\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-10\",\"requestId\":\"m1b\"}");
        ToolExecutionLedger ledger = new ToolExecutionLedger();
        LLMService.Port llm = SpringAiLlmService.createWithToolCalling(model,
                new SupplyMindToolCallbackProvider(List.of(harness.historyQuery(), harness.warningExplain())),
                "test", "test-model", ledger);
        AgentOrchestrator.AgentResult result = harness.orchestratorWithLedger(llm, ledger)
                .answer(harness.formalQuery());
        assertEquals(List.of("history.query"),
                ledger.all().stream().map(ToolExecutionLedger.Execution::toolName).toList(),
                "only the model-selected tool may be recorded as executed");
        assertFalse(result.evidencePack().toolExecutions().stream()
                        .anyMatch(execution -> execution.toolName().equals("warning.explain")));
    }

    @Test
    void m1UnknownToolAndInvalidArgsForceJavaTemplate() {
        Harness harness = harness("m1-unknown");
        ToolSelectingModel unknownModel = new ToolSelectingModel("backfill.start",
                "{\"itemId\":\"" + FX_ITEM + "\"}");
        LLMService.Port unknownLlm = new SpringAiLlmService(ChatClient.builder(unknownModel).build(),
                new SupplyMindToolCallbackProvider(List.of(harness.historyQuery(), harness.warningExplain())),
                "test", "test-model");
        AgentOrchestrator.AgentResult unknown = harness.orchestrator(unknownLlm).answer(harness.formalQuery());
        assertTrue(unknown.degraded(), "unknown model tool must force fallback");
        assertEquals("JAVA_TEMPLATE", unknown.report().generatedBy());
        assertTrue(unknown.degradeReason().contains("UNKNOWN_TOOL")
                        || unknown.degradeReason().contains("TOOL_EXECUTION_REJECTED"));

        Harness invalidHarness = harness("m1-invalid");
        ToolSelectingModel invalidModel = new ToolSelectingModel("history.query",
                "{\"itemId\":\"../escape\",\"startDate\":\"2026-99-99\",\"endDate\":\"2026-08-10\",\"requestId\":\"m1c\"}");
        LLMService.Port invalidLlm = new SpringAiLlmService(ChatClient.builder(invalidModel).build(),
                new SupplyMindToolCallbackProvider(List.of(invalidHarness.historyQuery(), invalidHarness.warningExplain())),
                "test", "test-model");
        AgentOrchestrator.AgentResult invalid = invalidHarness.orchestrator(invalidLlm)
                .answer(invalidHarness.formalQuery());
        assertTrue(invalid.degraded(), "rejected tool args must force fallback");
        assertEquals("JAVA_TEMPLATE", invalid.report().generatedBy());
    }

    // ---- M2: all four evidence statuses preserved; per-tool lineage ----

    @Test
    void m2EvidencePackPreservesAllFourStatusesWithReasonCodes() throws Exception {
        Harness harness = harness("m2-statuses");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        List<EvidencePackV1.EvidenceRefEntry> entries = verifier.verifyAll(List.of(
                harness.rawRef(),
                "raw/formal/official_web/FX.X/2026/08/does-not-exist.json",
                "../outside.json"));
        assertEquals(EvidenceStatus.VERIFIED, entries.get(0).status());
        assertEquals(EvidenceStatus.MISSING, entries.get(1).status());
        assertEquals(EvidenceStatus.UNAVAILABLE, entries.get(2).status());
        assertTrue(entries.get(1).reasonCode() != null && !entries.get(1).reasonCode().isBlank(),
                "MISSING must carry a reasonCode");
        assertTrue(entries.get(2).reasonCode() != null && !entries.get(2).reasonCode().isBlank(),
                "UNAVAILABLE must carry a reasonCode");
    }

    @Test
    void m2NonVerifiedEvidenceNeverReachesLlmContext() {
        Harness harness = harness("m2-llm-context");
        LLMService.Port capturing = request -> {
            // The LLM may only ever see VERIFIED evidence refs; any other ref must be rejected.
            for (String ref : request.evidenceRefs()) {
                assertTrue(ref.equals(harness.rawRef()) || ref.equals(harness.dailyRef())
                                || ref.equals("config/monitor-series.json"),
                        "unknown evidence ref in LLM context: " + ref);
            }
            return LLMService.LLMResponse.success("ok", List.of());
        };
        AgentOrchestrator.AgentResult result = harness.orchestrator(capturing).answer(harness.formalQuery());
        // The fixture raw/daily/config exist -> verified; limitations may still be non-empty
        // because the fallback query may produce NO_DATA tools, but no fabricated evidence.
        assertTrue(result.evidencePack().evidenceRefs().stream()
                        .anyMatch(entry -> entry.ref().equals(harness.rawRef())
                                && entry.status() == EvidenceStatus.VERIFIED),
                "the fixture raw must be VERIFIED in the EvidencePack");
    }

    @Test
    void m2PerToolLineageIsNotBatchAppliedAcrossTools() throws Exception {
        Harness harness = harness("m2-lineage");
        EvidenceRefVerifier verifier = new EvidenceRefVerifier(harness.root());
        // Tool 1 lineage vs Tool 2 lineage are separate; verifyAll with a per-tool lineage only
        // enriches that tool's entries (verified in the orchestrator per ToolResult).
        var toolOne = verifier.verifyAll(List.of(harness.rawRef()),
                new ToolResult.Lineage("calc-v1", "cal-v1", List.of("1"), "source-a", "fp-a", "val-v1"));
        assertEquals("calc-v1", toolOne.get(0).calculationVersion());
        var toolTwo = verifier.verifyAll(List.of(harness.dailyRef()),
                new ToolResult.Lineage("calc-v2", "cal-v2", List.of("2"), "source-b", "fp-b", "val-v2"));
        assertEquals("calc-v2", toolTwo.get(0).calculationVersion());
        assertEquals("calc-v1", toolOne.get(0).calculationVersion(),
                "tool-one lineage must never be overwritten by tool-two");
    }

    // ---- M3: full-form numeric fabrication guard ----

    @Test
    void m3FabricatedNumbersInEveryFormAreRejected() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000");
        for (String fabricated : List.of(
                "当前值 999999",            // integer
                "当前值 999.9",             // one decimal
                "当前值 -7.15000000",       // negative of a known value -> not a fact value
                "当前值 7.15000000%",       // percentage (different unit than the fact)
                "当前值 7,150.00000",       // thousands-separated
                "当前值 7.15e6")) {         // scientific notation
            com.supplymind.agent.application.AgentResponseVerifier.Verification v =
                    verifier.verify(new ModelDraftV1("r", fabricated, List.of()), pack);
            assertFalse(v.verified(), "fabricated form must be rejected: " + fabricated);
            assertTrue("FABRICATED_NUMBER".equals(v.reason())
                            || "MISSING_REQUIRED_REFERENCE".equals(v.reason()),
                    "reason=" + v.reason() + " for " + fabricated);
        }
    }

    @Test
    void m3KnownNumberInValidFormWithReferenceIsAccepted() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000");
        ModelDraftV1 draft = new ModelDraftV1("r", "当前值为 7.15000000", List.of(
                new ModelClaimV1("c1", "当前值为 7.15000000", List.of("fact-0"), List.of())));
        com.supplymind.agent.application.AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertTrue(v.verified(), "a known value with a supporting fact reference must be accepted: " + v.reason());
    }

    @Test
    void m3UnrelatedReferenceDoesNotValidateTheWholeDraft() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000");
        ModelDraftV1 draft = new ModelDraftV1("r", "当前值为 999999.999", List.of(
                new ModelClaimV1("c1", "当前值为 999999.999", List.of("fact-0"), List.of())));
        com.supplymind.agent.application.AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertFalse(v.verified(),
                "a claim that references a fact but states a different number must be rejected");
        assertTrue("FABRICATED_NUMBER".equals(v.reason()) || "UNSUPPORTED_CLAIM_REFERENCE".equals(v.reason()),
                "reason=" + v.reason());
    }

    @Test
    void m3ClaimReferenceMustSupportTheClaimNumbers() {
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of());
        EvidencePackV1 pack = packWithFact("7.15000000");
        ModelDraftV1 draft = new ModelDraftV1("r", "其他值为 9.00000000", List.of(
                new ModelClaimV1("c1", "其他值为 9.00000000", List.of("fact-0"), List.of())));
        com.supplymind.agent.application.AgentResponseVerifier.Verification v = verifier.verify(draft, pack);
        assertFalse(v.verified(), "a claim number not supported by its referenced fact must be rejected");
        assertTrue("FABRICATED_NUMBER".equals(v.reason()) || "UNSUPPORTED_CLAIM_REFERENCE".equals(v.reason()),
                "reason=" + v.reason());
    }

    // ---- M4: report/evidence immutable binding ----

    @Test
    void m4StoreRejectsClaimsWithEmptyEvidenceRefs() throws Exception {
        Harness harness = harness("m4-empty-claim");
        AgentReportV1 report = harness.validReport("report-m4-empty", "req-m4-empty",
                List.of(new AgentReportV1.Claim("c1", "text", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> harness.reportStore().store(report),
                "a formal claim with empty evidenceRefs must be rejected at store time");
    }

    @Test
    void m4EvidenceDataAndManifestTogetherReplacedFailsClosed() throws Exception {
        Harness harness = harness("m4-replace");
        AgentReportV1 report = harness.validReport("report-m4-replace", "req-m4-replace", List.of());
        String ref = harness.reportStore().store(report);

        // Replace evidence data AND its manifest together with a self-consistent new pair
        // (same ref, new sha) - the frozen binding must still fail closed.
        Path raw = harness.root().resolveDataRef(harness.rawRef());
        byte[] newData = ("replaced-evidence").getBytes(StandardCharsets.UTF_8);
        Files.write(raw, newData);
        Files.write(harness.root().resolveDataRef(DataPaths.manifestRef(harness.rawRef())),
                JsonV1Codec.encodeFile(ManifestFactory.json(harness.rawRef(), newData,
                        List.of("fx-run-20260810"), AT)));

        ReportStore.ReadResult read = new ReportStore(harness.root(), harness.files()).read(ref);
        assertFalse(read.ok(), "a fully replaced evidence pair must break the frozen binding");
        assertTrue("EVIDENCE_BINDING_MISMATCH".equals(read.failureCode())
                        || "EVIDENCE_UNAVAILABLE".equals(read.failureCode())
                        || "EVIDENCE_LINEAGE_MISMATCH".equals(read.failureCode()),
                "any frozen-binding drift must fail closed, got: " + read.failureCode());
    }

    @Test
    void m4EvidenceShaChangeFailsClosed() throws Exception {
        Harness harness = harness("m4-sha");
        AgentReportV1 report = harness.validReport("report-m4-sha", "req-m4-sha", List.of());
        String ref = harness.reportStore().store(report);
        Path raw = harness.root().resolveDataRef(harness.rawRef());
        byte[] newData = ("sha-change").getBytes(StandardCharsets.UTF_8);
        Files.write(raw, newData);
        Files.write(harness.root().resolveDataRef(DataPaths.manifestRef(harness.rawRef())),
                JsonV1Codec.encodeFile(ManifestFactory.json(harness.rawRef(), newData,
                        List.of("fx-run-20260810"), AT)));
        ReportStore.ReadResult read = new ReportStore(harness.root(), harness.files()).read(ref);
        assertFalse(read.ok());
        assertTrue("EVIDENCE_BINDING_MISMATCH".equals(read.failureCode())
                        || "EVIDENCE_UNAVAILABLE".equals(read.failureCode()),
                "sha drift must fail closed, got: " + read.failureCode());
    }

    @Test
    void m4ReportBodyIdentityChangeWithRebuiltManifestFailsClosed() throws Exception {
        Harness harness = harness("m4-body");
        AgentReportV1 report = harness.validReport("report-m4-body", "req-m4-body", List.of());
        String ref = harness.reportStore().store(report);
        Path body = harness.root().resolveDataRef(ref);
        AgentReportV1 forged = new AgentReportV1(
                "AGENT-REPORT-V1", "report-forged", report.requestId(), report.evidencePack(),
                report.generatedBy(), report.provider(), report.model(), report.degraded(),
                report.degradeReason(), report.factsSummary(), report.claims(),
                report.recommendations(), report.limitations(), report.createdAt());
        byte[] newBody = JsonV1Codec.encodeFile(forged);
        Files.write(body, newBody);
        Files.write(harness.root().resolveDataRef(DataPaths.manifestRef(ref)),
                JsonV1Codec.encodeFile(ManifestFactory.json(ref, newBody, List.of(), AT)));
        ReportStore.ReadResult read = new ReportStore(harness.root(), harness.files()).read(ref);
        assertFalse(read.ok(), "body identity drift must fail closed even with a rebuilt manifest");
        assertEquals("IDENTITY_MISMATCH", read.failureCode());
    }

    // ---- M5: user input secret guard + strict mode ----

    @Test
    void m5UserInputSecretIsRejectedBeforeAnyPersistence() throws Exception {
        Harness harness = harness("m5-secret-input");
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of("super-secret-value"));
        AgentOrchestrator orchestrator = harness.orchestratorWithVerifier(verifier);
        for (String payload : List.of(
                "Authorization: Bearer super-secret-value",
                "api_key=super-secret-value",
                "Cookie: session=super-secret-value",
                "password=super-secret-value")) {
            assertThrows(IllegalArgumentException.class,
                    () -> orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                            payload, FX_ITEM, "2026-08-10", "2026-08-10",
                            null, null, null, null, null, "FORMAL")),
                    "secret-bearing question must be rejected before any report is written");
        }
    }

    @Test
    void m5UnknownModeFailsClosed() throws Exception {
        Harness harness = harness("m5-mode");
        AgentOrchestrator orchestrator = harness.orchestrator(request -> LLMService.LLMResponse.success("ok"));
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                        "q", FX_ITEM, null, null, null, null, null, null, null, "ADMIN")),
                "unknown mode must fail closed");
    }

    @Test
    void m5SecretNeverAppearsInPersistedReport() throws Exception {
        Harness harness = harness("m5-persist");
        AgentResponseVerifier verifier = new AgentResponseVerifier(List.of("sk-super-secret-api-key"));
        AgentOrchestrator orchestrator = harness.orchestratorWithVerifier(verifier);
        try {
            orchestrator.answer(new AgentOrchestrator.AgentQueryInput(
                    "问题包含 sk-super-secret-api-key", FX_ITEM, "2026-08-10", "2026-08-10",
                    null, null, null, null, null, "FORMAL"));
        } catch (IllegalArgumentException expected) {
            // rejected before persistence
        }
        try (var stream = Files.walk(harness.root().path())) {
            List<Path> reports = stream.filter(p -> p.toString().contains("report")).toList();
            for (Path reportFile : reports) {
                String content = Files.readString(reportFile, StandardCharsets.UTF_8);
                assertFalse(content.contains("sk-super-secret-api-key"),
                        "secret must never be persisted: " + reportFile);
            }
        }
    }

    // ---- helpers ----

    private EvidencePackV1 packWithFact(String value) {
        return new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-1", "req-1", "FORMAL",
                "question", AT, new EvidencePackV1.Scope(List.of(FX_ITEM), "2026-08-10", null, null, "Asia/Shanghai"),
                List.of(), List.of(new EvidencePackV1.Fact(
                        "fact-0", "history.query", FX_ITEM, "2026-08-10", null, null,
                        value, "CNY/1 USD", "CNY", "true", "VERIFIED", "pboc-basic-validation-v1",
                        "arithmetic-mean-v1", "weekday-asia-shanghai-v1", List.of("1"),
                        "source", "fp", List.of("processed/daily/x.csv"))),
                List.of(new EvidencePackV1.EvidenceRefEntry(
                        "ev-1", "DAILY", "processed/daily/x.csv", "aa", EvidenceStatus.VERIFIED,
                        null, null, null, null, null, null, null,
                        "pboc-basic-validation-v1", "arithmetic-mean-v1", "weekday-asia-shanghai-v1",
                        List.of("1"))),
                List.of(), List.of(), List.of());
    }

    private Harness harness(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        String rawRef = writeRawFixture(root, files);
        writeDailyFixture(root, files, rawRef);
        return new Harness(root, files, rawRef,
                new HistoryQueryToolAdapter(history),
                new WarningExplainToolAdapter(root));
    }

    private MonitorSeriesItemV1 fxItem() {
        return new MonitorSeriesItemV1(
                FX_ITEM, "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, AT, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private static String writeRawFixture(DataRoot root, AtomicFileStore files) {
        String runId = "fx-run-20260810";
        String rawRef = DataPaths.rawRef("formal", "official_web", FX_ITEM, AT, runId);
        byte[] payload = "fx-2026-08-10".getBytes(StandardCharsets.UTF_8);
        com.supplymind.foundation.model.RawReceiptV1 raw = new com.supplymind.foundation.model.RawReceiptV1(
                "1.0", rawRef, "acq-fx", runId, Mode.FORMAL,
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                "中国人民银行官网（授权中国外汇交易中心公布）",
                "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                "fx-ref", FX_ITEM, "2026-08-10", "2026-08-10", null, AT, AT, null,
                "123.45678901", "CNY/1 USD", "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                java.util.Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, AT, null, null);
        byte[] data = JsonV1Codec.encodeFile(raw);
        ManifestV1 manifest = ManifestFactory.json(rawRef, data, List.of(runId), AT);
        files.commit("raw-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, rawRef, data,
                        JsonV1Codec.encodeFile(manifest), true)));
        return rawRef;
    }

    private static void writeDailyFixture(DataRoot root, AtomicFileStore files, String rawRef) {
        YearMonth month = YearMonth.of(2026, 8);
        DailyRecordV1 row = new DailyRecordV1(
                "1.0", "2026-08-10", FX_ITEM, ProviderType.OFFICIAL_WEB,
                "中国人民银行官网（授权中国外汇交易中心公布）", AccessMethod.PUBLIC_OFFICIAL_HTML,
                ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, "pboc-basic-validation-v1",
                List.of(1), "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP,
                "weekday-asia-shanghai-v1", "123.45678901",
                1, "123.45678901", 1, 0, true,
                "CNY", "CNY/1 USD", List.of(new DailyInputRefV1("fx-run-20260810", rawRef, 4)),
                AT, null);
        String ref = DataPaths.dailyRef(FX_ITEM, month);
        byte[] data = CsvV1Codec.encodeDaily(List.of(row));
        ManifestV1 manifest = ManifestFactory.csv(ref, data, 1,
                "2026-08-10", "2026-08-10", List.of("fx-run-20260810"), AT);
        files.commit("daily-fixture", DirtyTransactionType.SINGLE_FILE, AT,
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref, data,
                        JsonV1Codec.encodeFile(manifest), false)));
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            String rawRef,
            HistoryQueryToolAdapter historyQuery,
            WarningExplainToolAdapter warningExplain
    ) {
        private String dailyRef() {
            return DataPaths.dailyRef(FX_ITEM, YearMonth.of(2026, 8));
        }

        private AgentOrchestrator.AgentQueryInput formalQuery() {
            return new AgentOrchestrator.AgentQueryInput("analyse", FX_ITEM,
                    "2026-08-10", "2026-08-10", null, null, null, null, null, "FORMAL");
        }

        private AgentOrchestrator orchestrator(LLMService.Port llm) {
            return orchestratorWithVerifier(llm, new AgentResponseVerifier(List.of()));
        }

        private AgentOrchestrator orchestratorWithVerifier(AgentResponseVerifier verifier) {
            return orchestratorWithVerifier(request -> LLMService.LLMResponse.success("ok"), verifier);
        }

        private AgentOrchestrator orchestratorWithLedger(LLMService.Port llm, ToolExecutionLedger ledger) {
            return new AgentOrchestrator(
                    new ToolExecutor(new com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter(
                            new com.supplymind.config.ConfigManagementService(
                                    new com.supplymind.foundation.storage.ConfigActivationStore(root, files, CLOCK),
                                    new com.supplymind.provider.DataProviderRegistry())),
                            historyQuery,
                            new com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter(
                                    new HistoryQueryService(root)),
                            new com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter(
                                    new HistoryQueryService(root)),
                            new com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter(
                                    new HistoryQueryService(root)),
                            warningExplain,
                            new com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter(root,
                                    new HistoryQueryService(root))),
                    llm, new TemplateFallbackService(),
                    new EvidenceRefVerifier(root), new ReportStore(root, files),
                    new AgentResponseVerifier(List.of()));
        }

        private AgentOrchestrator orchestratorWithVerifier(LLMService.Port llm, AgentResponseVerifier verifier) {
            return new AgentOrchestrator(
                    new ToolExecutor(new com.supplymind.agent.infrastructure.springai.SeriesResolveToolAdapter(
                            new com.supplymind.config.ConfigManagementService(
                                    new com.supplymind.foundation.storage.ConfigActivationStore(root, files, CLOCK),
                                    new com.supplymind.provider.DataProviderRegistry())),
                            historyQuery,
                            new com.supplymind.agent.infrastructure.springai.PeriodMetricsToolAdapter(
                                    new HistoryQueryService(root)),
                            new com.supplymind.agent.infrastructure.springai.QualityInspectToolAdapter(
                                    new HistoryQueryService(root)),
                            new com.supplymind.agent.infrastructure.springai.CostImpactToolAdapter(
                                    new HistoryQueryService(root)),
                            warningExplain,
                            new com.supplymind.agent.infrastructure.springai.ProvenanceTraceToolAdapter(root,
                                    new HistoryQueryService(root))),
                    llm, new TemplateFallbackService(),
                    new EvidenceRefVerifier(root), new ReportStore(root, files), verifier);
        }

        private ReportStore reportStore() {
            return new ReportStore(root, files);
        }

        private AgentReportV1 validReport(String reportId, String requestId, List<AgentReportV1.Claim> claims) {
            return new AgentReportV1(
                    "AGENT-REPORT-V1", reportId, requestId,
                    new EvidencePackV1("AGENT-EVIDENCE-SCHEMA-V1", "pack-" + requestId, requestId,
                            "FORMAL", "q", AT,
                            new EvidencePackV1.Scope(List.of(FX_ITEM), "2026-08-10", null, null, "Asia/Shanghai"),
                            List.of(), List.of(),
                            List.of(new EvidencePackV1.EvidenceRefEntry(
                                    "ev-1", "RAW", rawRef, com.supplymind.foundation.storage.FileDigest.sha256(
                                            "fx-2026-08-10".getBytes(StandardCharsets.UTF_8)),
                                    EvidenceStatus.VERIFIED, null, null, rawRef, null,
                                    "2026-08-10", null, null, "pboc-basic-validation-v1",
                                    "arithmetic-mean-v1", "weekday-asia-shanghai-v1", List.of("1"))),
                            List.of(), List.of(), List.of()),
                    "JAVA_TEMPLATE", null, null, true, "TEST",
                    List.of(), claims, List.of(), List.of(), AT);
        }
    }

    private static final class ToolSelectingModel implements ChatModel {
        private final String toolName;
        private final String arguments;
        private final AtomicInteger calls = new AtomicInteger();

        private ToolSelectingModel(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new ToolCall("call-1", "function", toolName, arguments)))
                        .build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "根据正式数据，当前值为 123.45678901（引用 fact-0）。"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
