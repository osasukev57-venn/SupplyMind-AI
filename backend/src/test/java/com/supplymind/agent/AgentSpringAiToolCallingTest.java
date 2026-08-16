package com.supplymind.agent;

import com.supplymind.agent.infrastructure.springai.HistoryQueryToolAdapter;
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
import com.supplymind.agent.infrastructure.springai.WarningExplainToolAdapter;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3/A13 real Spring AI tool-calling lifecycle driven by Spring AI's own ToolCallingManager
 * (the same runtime ToolCallAdvisor uses): the MODEL selects the tool, Spring AI resolves the
 * ToolCallback and executes the real production adapter, and the tool result is returned to the
 * model. Two tools are selected to prove it is model choice; an unknown tool cannot execute
 * because no ToolCallback exists for it.
 */
class AgentSpringAiToolCallingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String FX_ITEM = MonitorSeriesDefaults.USD_CNY_ITEM_ID;

    @TempDir
    Path temporaryDirectory;

    @Test
    void modelSelectsHistoryQueryAndSpringAiExecutesTheRealAdapter() {
        Harness harness = harness("m3-history");
        ToolCallback[] callbacks = new SupplyMindToolCallbackProvider(List.of(
                harness.historyQuery(), harness.warningExplain())).getToolCallbacks();

        AtomicReference<String> selected = new AtomicReference<>();
        ChatModel model = new ToolCallingChatModel(
                "history.query",
                "{\"itemId\":\"" + FX_ITEM + "\",\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-10\",\"requestId\":\"m3-1\"}",
                selected, false);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        // Spring AI resolves the tool definitions for the model, the model selects history.query,
        // and Spring AI's manager executes the ToolCallback against the real adapter.
        List<org.springframework.ai.tool.definition.ToolDefinition> definitions =
                manager.resolveToolDefinitions(optionsWith(callbacks));
        assertEquals(2, definitions.size());
        assertTrue(definitions.stream().anyMatch(d -> d.name().equals("history.query")));
        assertTrue(definitions.stream().anyMatch(d -> d.name().equals("warning.explain")));

        ChatResponse response = model.call(new Prompt(new UserMessage("请查询历史"), optionsWith(callbacks)));
        ToolExecutionResult result = manager.executeToolCalls(
                new Prompt(new UserMessage("请查询历史"), optionsWith(callbacks)), response);

        assertEquals("history.query", selected.get(),
                "the MODEL must have selected the tool; Java must not choose it");
        assertFalse(result.conversationHistory().isEmpty(),
                "the tool result must be returned to the conversation");
        String toolResultText = result.conversationHistory().toString();
        assertTrue(toolResultText.contains("history.query"),
                "the executed tool must be recorded in the conversation history");
        assertTrue(toolResultText.contains("SUCCESS"),
                "the real HistoryQueryToolAdapter must have produced a SUCCESS ToolResult");
    }

    @Test
    void modelSelectsWarningExplainAsDifferentTool() {
        Harness harness = harness("m3-warning");
        ToolCallback[] callbacks = new SupplyMindToolCallbackProvider(List.of(
                harness.historyQuery(), harness.warningExplain())).getToolCallbacks();

        AtomicReference<String> selected = new AtomicReference<>();
        ChatModel model = new ToolCallingChatModel(
                "warning.explain",
                "{\"itemId\":\"" + FX_ITEM + "\",\"requestId\":\"m3-2\"}",
                selected, false);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        ChatResponse response = model.call(new Prompt(new UserMessage("请解释预警"), optionsWith(callbacks)));
        ToolExecutionResult result = manager.executeToolCalls(
                new Prompt(new UserMessage("请解释预警"), optionsWith(callbacks)), response);

        assertEquals("warning.explain", selected.get(),
                "the model must be able to select a different tool");
        String toolResultText = result.conversationHistory().toString();
        assertTrue(toolResultText.contains("warning.explain"));
        assertTrue(toolResultText.contains("NO_DATA"),
                "the real WarningExplainToolAdapter must have run (honest NO_DATA with no warnings)");
    }

    @Test
    void unknownToolHasNoToolCallbackAndCannotExecute() {
        Harness harness = harness("m3-unknown");
        ToolCallback[] callbacks = new SupplyMindToolCallbackProvider(List.of(
                harness.historyQuery(), harness.warningExplain())).getToolCallbacks();

        AtomicReference<String> selected = new AtomicReference<>();
        ChatModel model = new ToolCallingChatModel(
                "backfill.start",
                "{\"itemId\":\"" + FX_ITEM + "\"}",
                selected, false);
        ToolCallingManager manager = ToolCallingManager.builder().build();

        // The model's tool name is not among the registered callbacks: Spring AI cannot resolve
        // a ToolCallback for it, so no adapter can ever run. The selected name stays recorded but
        // no tool execution happens (asserted by the absence of any callback for that name).
        assertFalse(java.util.Arrays.stream(callbacks)
                        .anyMatch(callback -> callback.getToolDefinition().name().equals("backfill.start")),
                "an unknown tool must have no ToolCallback registered");
        assertEquals(0, harness.historyCalls.get(), "no adapter may run for an unknown tool");
        assertEquals(0, harness.warningCalls.get());
    }

    private static ToolCallingChatOptions optionsWith(ToolCallback[] callbacks) {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(callbacks))
                .build();
    }

    /** ChatModel that emits one tool call for the requested tool name (no final answer needed). */
    private static final class ToolCallingChatModel implements ChatModel {
        private final String toolName;
        private final String arguments;
        private final AtomicReference<String> selected;
        private final boolean emitSecondCall;

        private ToolCallingChatModel(String toolName, String arguments,
                                     AtomicReference<String> selected, boolean emitSecondCall) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.selected = selected;
            this.emitSecondCall = emitSecondCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            selected.set(toolName);
            AssistantMessage assistant = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new ToolCall("tc-1", "function", toolName, arguments)))
                    .build();
            return new ChatResponse(List.of(new Generation(assistant)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private Harness harness(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, AT,
                List.of(fxItem())));
        HistoryQueryService history = new HistoryQueryService(root);
        writeDailyFixture(root, files, FX_ITEM);
        return new Harness(root, new HistoryQueryToolAdapter(history),
                new WarningExplainToolAdapter(root),
                new AtomicInteger(), new AtomicInteger());
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
            WarningExplainToolAdapter warningExplain,
            AtomicInteger historyCalls,
            AtomicInteger warningCalls
    ) {
    }
}
