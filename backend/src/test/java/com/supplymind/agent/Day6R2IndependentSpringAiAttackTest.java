package com.supplymind.agent;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.infrastructure.springai.SpringAiLlmService;
import com.supplymind.agent.infrastructure.springai.SupplyMindToolCallbackProvider;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Independent M3/M4 attack: no ToolCallback is directly invoked by this test. */
class Day6R2IndependentSpringAiAttackTest {

    private static final Set<String> FROZEN_TOOLS = Set.of(
            "series.resolve", "history.query", "period.metrics", "quality.inspect", "cost.impact",
            "warning.explain", "provenance.trace");

    @TempDir
    Path temp;

    @Test
    void realChatClientToolLifecycleExecutesModelSelectedHistoryAndWarningAdapters() {
        Day6R2Fixture historyFixture = Day6R2Fixture.create(temp, "history-tool");
        ToolSelectingModel historyModel = new ToolSelectingModel("history.query",
                "{\"itemId\":\"" + Day6R2Fixture.ITEM
                        + "\",\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-10\",\"requestId\":\"a4h\"}");
        LLMService.LLMResponse history = service(historyFixture, historyModel).analyze(request("history"));
        assertEquals(LLMService.LLMStatus.SUCCESS, history.status());
        assertTrue(historyModel.calls.get() >= 2, "ChatClient must return a real tool result to the model");
        assertTrue(historyModel.secondPrompt.get().toString().contains("history.query"));

        Day6R2Fixture warningFixture = Day6R2Fixture.create(temp, "warning-tool");
        ToolSelectingModel warningModel = new ToolSelectingModel("warning.explain",
                "{\"itemId\":\"" + Day6R2Fixture.ITEM + "\",\"month\":\"2026-08\",\"requestId\":\"a4w\"}");
        LLMService.LLMResponse warning = service(warningFixture, warningModel).analyze(request("warning"));
        assertEquals(LLMService.LLMStatus.SUCCESS, warning.status());
        assertTrue(warningModel.calls.get() >= 2, "warning tool result must return through Spring AI lifecycle");
        assertTrue(warningModel.secondPrompt.get().toString().contains("warning.explain"));
    }

    @Test
    void requestScopedProviderExposesExactlySevenToolsEvenWhenContextHasDangerousCallback() {
        Day6R2Fixture fixture = Day6R2Fixture.create(temp, "exact-seven");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("dangerousWrite", ToolCallback.class,
                    () -> MethodToolCallbackProvider.builder().toolObjects(new DangerousTool()).build().getToolCallbacks()[0]);
            context.refresh();
            assertEquals("dangerous.write", context.getBean(ToolCallback.class).getToolDefinition().name());

            CapturingAnswerModel model = new CapturingAnswerModel();
            SpringAiLlmService service = service(fixture, model);
            assertEquals(FROZEN_TOOLS, Set.copyOf(service.exposedToolNames()));
            assertFalse(service.exposedToolNames().contains("dangerous.write"));
            assertEquals(LLMService.LLMStatus.SUCCESS, service.analyze(request("seven")).status());
            assertFalse(model.requestToolNames.get().contains("dangerous.write"),
                    "an extra Spring bean must not leak into a request-scoped SupplyMind tool list");
            assertEquals(FROZEN_TOOLS, model.requestToolNames.get());
        }
    }

    @Test
    void unknownAndRejectedModelToolCallsCannotProduceRegularLlmReports() {
        Day6R2Fixture unknownFixture = Day6R2Fixture.create(temp, "unknown-tool");
        AgentOrchestrator.AgentResult unknown = unknownFixture.orchestrator(
                service(unknownFixture, new ToolSelectingModel("backfill.start", "{\"itemId\":\""
                        + Day6R2Fixture.ITEM + "\"}")), new AgentResponseVerifier(List.of()))
                .answer(unknownFixture.formalHistoryQuery());
        assertTrue(unknown.degraded());
        assertDeterministicFallback(unknown, "backfill.start");
        assertTrue(unknown.degradeReason().contains("UNKNOWN_TOOL")
                || unknown.degradeReason().contains("TOOL_EXECUTION_REJECTED"),
                "unknown model tool must have an explicit tool-rejected degradation reason");
        assertEquals("JAVA_TEMPLATE", unknown.report().generatedBy());

    }

    @Test
    void invalidModelToolArgumentsReturnRejectedToolResultBeforeDeterministicFallback() {
        Day6R2Fixture invalidFixture = Day6R2Fixture.create(temp, "invalid-tool-args");
        ToolSelectingModel invalidModel = new ToolSelectingModel("history.query",
                "{\"itemId\":\"../escape\",\"startDate\":\"2026-99-99\",\"endDate\":\"2026-08-10\",\"requestId\":\"a7\"}");
        AgentOrchestrator.AgentResult invalid = invalidFixture.orchestrator(service(invalidFixture, invalidModel),
                new AgentResponseVerifier(List.of())).answer(invalidFixture.formalHistoryQuery());
        assertTrue(invalid.degraded(), "a rejected model-selected tool call must force deterministic fallback");
        assertDeterministicFallback(invalid, "../escape");
        assertEquals("JAVA_TEMPLATE", invalid.report().generatedBy());
        assertTrue(invalidModel.calls.get() >= 2, "Spring AI must have received the adapter's REJECTED ToolResult");
        assertTrue(invalidModel.secondPrompt.get().toString().contains("REJECTED"));
    }

    private static SpringAiLlmService service(Day6R2Fixture fixture, ChatModel model) {
        return new SpringAiLlmService(ChatClient.builder(model).build(), new SupplyMindToolCallbackProvider(List.of(
                fixture.seriesResolve(), fixture.historyQuery(), fixture.periodMetrics(), fixture.qualityInspect(),
                fixture.costImpact(), fixture.warningExplain(), fixture.provenanceTrace())), "test", "test-model");
    }

    private static LLMService.LLMRequest request(String id) {
        return new LLMService.LLMRequest("req-" + id, "test", "FORMAL", List.of(), List.of());
    }


    private static void assertDeterministicFallback(AgentOrchestrator.AgentResult result, String untrustedToken) {
        String answer = result.report().claims().get(0).text();
        assertTrue(answer.contains("123.45678901"));
        assertFalse(answer.contains(untrustedToken));
    }
    static final class DangerousTool {
        @org.springframework.ai.tool.annotation.Tool(name = "dangerous.write", description = "must never be exposed")
        public String write(String value) { return value; }
    }

    private static final class ToolSelectingModel implements ChatModel {
        private final String toolName;
        private final String arguments;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Prompt> secondPrompt = new AtomicReference<>();

        private ToolSelectingModel(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        @Override public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() == 1) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new ToolCall("call-1", "function", toolName, arguments))).build())));
            }
            secondPrompt.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("tool result received"))));
        }

        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
    }

    private static final class CapturingAnswerModel implements ChatModel {
        private final AtomicReference<Set<String>> requestToolNames = new AtomicReference<>();

        @Override public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            requestToolNames.set(options.getToolCallbacks().stream()
                    .map(callback -> callback.getToolDefinition().name()).collect(java.util.stream.Collectors.toSet()));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("deterministic answer"))));
        }

        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().build(); }
    }
}
