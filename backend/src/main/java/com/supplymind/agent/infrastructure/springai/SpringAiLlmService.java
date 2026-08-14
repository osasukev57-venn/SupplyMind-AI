package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.llm.LLMService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * D6-T03 Spring AI infrastructure adapter: implements the SupplyMind LLMService port over the
 * Spring AI ChatClient with REAL Spring AI tool calling (M3). Every Agent request explicitly
 * attaches exactly the seven SupplyMind read-only ToolCallbacks (request-scoped); no other
 * ToolCallback bean in the context is ever exposed to the model. The model selects a tool,
 * Spring AI runs its tool-calling lifecycle, and the SupplyMind adapter executes the
 * production service. Spring AI types stay inside this infrastructure layer.
 */
public final class SpringAiLlmService implements LLMService.Port {

    private final ChatClient chatClient;
    private final ToolCallback[] toolCallbacks;
    private final String provider;
    private final String model;

    public SpringAiLlmService(
            ChatClient chatClient, ToolCallbackProvider toolCallbackProvider,
            String provider, String model
    ) {
        this.chatClient = enhanceWithToolCalling(chatClient);
        this.toolCallbacks = toolCallbackProvider == null
                ? new ToolCallback[0] : toolCallbackProvider.getToolCallbacks();
        this.provider = provider;
        this.model = model;
    }

    /**
     * F3: ensures the official Spring AI ToolCallAdvisor is registered on the ChatClient so the
     * tool-calling lifecycle (model selects -> ToolCallback executes -> tool result returned to
     * the model) really runs inside ChatClient - the same wiring Spring Boot auto-configuration
     * provides for cloud ChatClients. A ChatClient without the advisor (e.g. built directly in
     * tests) is mutated to include it, so the production tool-calling path is never bypassed.
     */
    private static ChatClient enhanceWithToolCalling(ChatClient chatClient) {
        Objects.requireNonNull(chatClient, "chatClient");
        ChatClient.Builder builder = chatClient.mutate();
        builder.defaultAdvisors(ToolCallAdvisor.builder()
                .toolCallingManager(ToolCallingManager.builder().build())
                .build());
        return builder.build();
    }

    /**
     * Creates a ChatClient over the model with the official Spring AI ToolCallAdvisor and
     * returns the adapter (production wiring; also used by tests that build models directly).
     */
    public static SpringAiLlmService createWithToolCalling(
            ChatModel chatModel, ToolCallbackProvider toolCallbackProvider,
            String provider, String model
    ) {
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallAdvisor.builder()
                        .toolCallingManager(ToolCallingManager.builder().build())
                        .build())
                .build();
        return new SpringAiLlmService(chatClient, toolCallbackProvider, provider, model);
    }

    public static SpringAiLlmService createWithToolCalling(
            ChatModel chatModel, ToolCallbackProvider toolCallbackProvider,
            String provider, String model, ToolExecutionLedger ledger
    ) {
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(ToolCallAdvisor.builder()
                        .toolCallingManager(ToolCallingManager.builder().build())
                        .build())
                .build();
        SpringAiLlmService service = new SpringAiLlmService(chatClient, toolCallbackProvider, provider, model);
        service.ledger = ledger;
        return service;
    }

    private ToolExecutionLedger ledger;

    public boolean isAvailable() {
        return chatClient != null && toolCallbacks.length > 0;
    }

    @Override
    public LLMService.LLMResponse analyze(LLMService.LLMRequest request) {
        if (chatClient == null) {
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "not_configured");
        }
        if (toolCallbacks.length == 0) {
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "no_tools_configured");
        }
        try {
            // F3: a request-scoped ledger records every tool execution inside the ChatClient
            // tool-calling lifecycle; any REJECTED execution invalidates the LLM interaction.
            ToolExecutionLedger requestLedger = ledger != null ? ledger : new ToolExecutionLedger();
            String prompt = buildPrompt(request);
            String content = chatClient.prompt()
                    .user(prompt)
                    .toolCallbacks(wrapWithLedger(toolCallbacks, requestLedger))
                    .call()
                    .content();
            if (requestLedger.hasFailure()) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,
                        "TOOL_EXECUTION_REJECTED");
            }
            if (content == null || content.isBlank()) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.MALFORMED, "empty_response");
            }
            return LLMService.LLMResponse.success(content.trim());
        } catch (RuntimeException exception) {
            // F3: map framework tool-resolution failures to the controlled reason code so the
            // orchestration layer can degrade deterministically (UNKNOWN_TOOL / tool rejected).
            if (isUnknownToolFailure(exception)) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "UNKNOWN_TOOL");
            }
            if (isToolRejectedFailure(exception)) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "TOOL_EXECUTION_REJECTED");
            }
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,
                    "chat_failed_" + exception.getClass().getSimpleName());
        }
    }

    private ToolCallback[] wrapWithLedger(ToolCallback[] callbacks, ToolExecutionLedger requestLedger) {
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int index = 0; index < callbacks.length; index++) {
            ToolCallback callback = callbacks[index];
            String name = callback.getToolDefinition().name();
            wrapped[index] = new ToolCallback() {
                @Override
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return callback.getToolDefinition();
                }

                @Override
                public String call(String toolInput) {
                    String result;
                    try {
                        result = callback.call(toolInput);
                    } catch (RuntimeException exception) {
                        requestLedger.record(name, com.supplymind.agent.tool.ToolStatus.REJECTED,
                                "execution_failed");
                        throw exception;
                    }
                    boolean rejected = result != null && (result.contains("\"status\":\"REJECTED\"")
                            || result.contains("REJECTED"));
                    requestLedger.record(name, rejected ? com.supplymind.agent.tool.ToolStatus.REJECTED
                            : com.supplymind.agent.tool.ToolStatus.SUCCESS,
                            rejected ? "tool_rejected" : null);
                    return result;
                }
            };
        }
        return wrapped;
    }

    private static boolean isUnknownToolFailure(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("No tool callbacks found")
                    || message.contains("unknown tool")
                    || message.contains("Tool not found")
                    || message.contains("No tool execution")
                    || message.contains("tool callbacks found for")
                    || message.contains("tool name '")
                    || message.contains("No tool callbacks provided")
                    || message.contains("tool call requested")
                    || message.contains("No ToolCallback found for tool name"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isToolRejectedFailure(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("ToolExecutionException")
                    || message.contains("rejected")
                    || message.contains("ToolInputException"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public List<String> exposedToolNames() {
        return Arrays.stream(toolCallbacks)
                .map(callback -> callback.getToolDefinition().name())
                .sorted()
                .toList();
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    private static String buildPrompt(LLMService.LLMRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a read-only analyst for SupplyMind. Answer using ONLY the provided facts and evidence refs.\n");
        prompt.append("Question: ").append(request.question()).append("\n");
        prompt.append("Mode: ").append(request.mode()).append("\n");
        if (!request.facts().isEmpty()) {
            prompt.append("Deterministic facts (never alter these values):\n");
            for (LLMService.LlmFact fact : request.facts()) {
                prompt.append("- ").append(fact.statement())
                        .append(" value=").append(fact.value())
                        .append(" date=").append(fact.businessDate())
                        .append(" period=").append(fact.period())
                        .append(" validation=").append(fact.validationStatus())
                        .append(" evidence=").append(fact.evidenceRef()).append("\n");
            }
        }
        if (!request.evidenceRefs().isEmpty()) {
            prompt.append("Evidence refs: ").append(String.join(", ", request.evidenceRefs())).append("\n");
        }
        prompt.append("Do not invent numbers, dates, sources or evidence refs. ");
        prompt.append("If the facts are insufficient, say so explicitly.");
        return prompt.toString();
    }
}
