package com.supplymind.agent;

import com.supplymind.agent.infrastructure.springai.SpringAiLlmService;
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
import com.supplymind.agent.infrastructure.springai.ToolExecutionLedger;
import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
import com.supplymind.agent.llm.LLMService;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-faithful F3 verification (replacement for the independent attack's test-double
 * expectations): the real ChatClient two-phase lifecycle through the official ToolCallAdvisor.
 */
class AgentF3LifecycleVerificationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void chatClientTwoPhaseToolLifecycleWithToolCallAdvisor() throws Exception {
        Harness harness = harness();
        TwoPhaseModel model = new TwoPhaseModel("history.query",
                "{\"itemId\":\"" + FX_ITEM + "\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-10\",\"requestId\":\"v1\"}");
        SupplyMindToolCallbackProvider provider = new SupplyMindToolCallbackProvider(List.of(
                harness.historyQuery(), harness.warningExplain()));

        LLMService.LLMResponse response = SpringAiLlmService.createWithToolCalling(
                model, provider, "test", "test-model").analyze(new LLMService.LLMRequest(
                "req-v1", "test", "FORMAL", List.of(), List.of()));

        assertEquals(LLMService.LLMStatus.SUCCESS, response.status(), "two-phase lifecycle must SUCCEED");
        assertTrue(model.calls.get() >= 2, "ChatClient must have returned the tool result to the model");
        assertTrue(model.secondPrompt.contains("history.query"),
                "the executed tool name must appear in the second model prompt");
    }

    @Test
    void invalidToolArgsAreReturnedToModelAndLedgerForcesRejection() throws Exception {
        Harness harness = harness();
        TwoPhaseModel model = new TwoPhaseModel("history.query",
                "{\"itemId\":\"../escape\",\"startDate\":\"2026-99-99\",\"endDate\":\"2026-08-10\",\"requestId\":\"v2\"}");
        SupplyMindToolCallbackProvider provider = new SupplyMindToolCallbackProvider(List.of(
                harness.historyQuery(), harness.warningExplain()));
        ToolExecutionLedger ledger = new ToolExecutionLedger();

        LLMService.LLMResponse response = SpringAiLlmService.createWithToolCalling(
                model, provider, "test", "test-model", ledger).analyze(new LLMService.LLMRequest(
                "req-v2", "test", "FORMAL", List.of(), List.of()));

        assertTrue(ledger.hasFailure(), "a REJECTED adapter result must be recorded in the ledger");
        assertEquals(LLMService.LLMStatus.UNAVAILABLE, response.status());
        assertEquals("TOOL_EXECUTION_REJECTED", response.failureKind());
        assertTrue(model.calls.get() >= 2, "Spring AI must have received the adapter's REJECTED ToolResult");
    }

    @Test
    void unknownToolCausesControlledRejection() throws Exception {
        Harness harness = harness();
        TwoPhaseModel model = new TwoPhaseModel("backfill.start",
                "{\"itemId\":\"" + FX_ITEM + "\"}");
        SupplyMindToolCallbackProvider provider = new SupplyMindToolCallbackProvider(List.of(
                harness.historyQuery(), harness.warningExplain()));

        LLMService.LLMResponse response = SpringAiLlmService.createWithToolCalling(
                model, provider, "test", "test-model").analyze(new LLMService.LLMRequest(
                "req-v3", "test", "FORMAL", List.of(), List.of()));

        assertTrue(response.status() != LLMService.LLMStatus.SUCCESS,
                "an unknown tool must never produce a regular SUCCESS response");
    }

    private static final class TwoPhaseModel implements ChatModel {
        private final String toolName;
        private final String arguments;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String secondPrompt;

        private TwoPhaseModel(String toolName, String arguments) {
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
            secondPrompt = prompt.getInstructions().toString();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("final"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("f3-" + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        writeDailyFixture(root, files, FX_ITEM);
        return new Harness(root, new HistoryQueryToolAdapter(history),
                new WarningExplainToolAdapter(root));
    }

    private static MonitorSeriesItemV1 fxItem() {
        return new MonitorSeriesItemV1(
                FX_ITEM, "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, AT, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
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
            HistoryQueryToolAdapter historyQuery,
            WarningExplainToolAdapter warningExplain
    ) {
    }
}
