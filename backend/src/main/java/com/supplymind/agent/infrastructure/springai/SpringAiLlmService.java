package com.supplymind.agent.infrastructure.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.tool.ToolResult;
import com.supplymind.agent.tool.ToolStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
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
 *
 * M1: the full ToolResults of the model-selected executions are captured into the ledger and
 * returned inside LLMResponse.toolResults, so the orchestration layer builds the formal
 * EvidencePack from the REAL executed tools - never from a separate fixed pre-execution chain.
 * M3: toolCallingEnabled=false requests the adapter to skip tool callbacks entirely (Phase B:
 * the model only explains the already-built EvidencePack facts).
 */
public final class SpringAiLlmService implements LLMService.Port {

    private static final ObjectMapper MAPPER = com.supplymind.foundation.codec.JsonV1Codec.mapper();

    private final ChatClient chatClient;
    private final ToolCallback[] toolCallbacks;
    private final String provider;
    private final String model;
    private ToolExecutionLedger ledger;

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
            // F3/M1: a request-scoped ledger records every tool execution (with full ToolResult)
            // inside the ChatClient tool-calling lifecycle; any REJECTED execution invalidates
            // the LLM interaction. Phase B requests (toolCallingEnabled=false) skip callbacks.
            ToolExecutionLedger requestLedger = ledger != null ? ledger : new ToolExecutionLedger();
            String prompt = buildPrompt(request);
            String content;
            if (request.toolCallingEnabled()) {
                content = chatClient.prompt()
                        .user(prompt)
                        .toolCallbacks(wrapWithLedger(toolCallbacks, requestLedger))
                        .call()
                        .content();
            } else {
                // Phase B: explain ONLY the already-built EvidencePack facts; no further tool
                // calls. The ToolCallAdvisor still needs ToolCallingChatOptions present, so we
                // attach the options with an EMPTY tool list - the model cannot select anything.
                content = chatClient.prompt()
                        .user(prompt)
                        .options(OpenAiChatOptions.builder()
                                .responseFormat(ResponseFormat.builder()
                                        .type(ResponseFormat.Type.JSON_OBJECT)
                                        .build())
                                .build())
                        .call()
                        .content();
            }
            if (requestLedger.hasFailure()) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,
                        "TOOL_EXECUTION_REJECTED");
            }
            if (content == null || content.isBlank()) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.MALFORMED, "empty_response");
            }
            return LLMService.LLMResponse.success(content.trim(), requestLedger.executedToolResults());
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
                        requestLedger.record(name, ToolStatus.REJECTED, "execution_failed");
                        throw exception;
                    }
                    ToolResult toolResult = parseToolResult(name, result);
                    requestLedger.record(name, toolResult == null ? ToolStatus.REJECTED : toolResult.status(),
                            toolResult == null || toolResult.status() == ToolStatus.SUCCESS
                                    ? (toolResult == null ? "unparseable_result" : null)
                                    : "tool_rejected",
                            toolResult);
                    return result;
                }
            };
        }
        return wrapped;
    }

    /** M1: parse the Spring AI tool-call result JSON back into the SupplyMind ToolResult contract. */
    private static ToolResult parseToolResult(String toolName, String result) {
        if (result == null || result.isBlank()) {
            return ToolResult.rejected(toolName, "1.0", "unknown", "empty tool result");
        }
        try {
            return MAPPER.readValue(result, ToolResult.class);
        } catch (Exception exception) {
            return ToolResult.rejected(toolName, "1.0", "unknown", "unparseable tool result");
        }
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

    static String buildPrompt(LLMService.LLMRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a read-only analyst for SupplyMind. Answer using ONLY the provided facts and evidence refs.\n");
        prompt.append("Question: ").append(request.question()).append("\n");
        prompt.append("Mode: ").append(request.mode()).append("\n");
        if (!request.facts().isEmpty()) {
            prompt.append("Verified facts as JSON. factId is an opaque identifier: copy it exactly and never renumber it.\n");
            prompt.append(MAPPER.valueToTree(request.facts()).toString()).append("\n");
            prompt.append("Allowed factIds: ")
                    .append(MAPPER.valueToTree(request.facts().stream().map(LLMService.LlmFact::factId).toList()).toString())
                    .append("\n");
        }
        if (!request.evidenceRefs().isEmpty()) {
            prompt.append("Allowed evidenceRefs: ")
                    .append(MAPPER.valueToTree(request.evidenceRefs()).toString())
                    .append("\n");
        }
        prompt.append("Do not invent numbers, dates, units, currencies, sources, factIds or evidenceRefs. ");
        prompt.append("If the verified facts are insufficient, say so without creating a business claim.");
        if (!request.toolCallingEnabled()) {
            // Phase B uses the provider's JSON_OBJECT response format and this strict envelope.
            prompt.append("\nPhase B response contract - return exactly one JSON object and nothing else:\n")
                    .append("{\"answer\":\"<short meta-summary without numbers, dates, sources or references>\",")
                    .append("\"claims\":[{\"claimId\":\"c1\",\"text\":\"<statement supported by cited facts>\",")
                    .append("\"factIds\":[\"<copy exact allowed factId>\"],")
                    .append("\"evidenceRefs\":[\"<optional exact allowed evidenceRef>\"],")
                    .append("\"sourceNames\":[\"<exact actualSourceName when the claim states a source>\"],")
                    .append("\"businessDates\":[\"<exact businessDate when the claim states a date>\"]}]}\n")
                    .append("Every claim must cite at least one allowed factId or evidenceRef. ")
                    .append("Every number, date, unit, currency and source in claim.text must come from that claim's cited facts. ")
                    .append("Put factIds/evidenceRefs only in their JSON arrays, never inside claim.text. ")
                    .append("Use sourceNames/businessDates only when claim.text states them, and copy exact values. ")
                    .append("Never output placeholder text or an ID not listed in the allowed arrays.");
        }
        return prompt.toString();
    }
}
