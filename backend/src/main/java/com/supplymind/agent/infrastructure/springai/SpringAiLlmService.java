package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.llm.LLMService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;

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
        this.chatClient = chatClient;
        this.toolCallbacks = toolCallbackProvider == null
                ? new ToolCallback[0] : toolCallbackProvider.getToolCallbacks();
        this.provider = provider;
        this.model = model;
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
            String prompt = buildPrompt(request);
            String content = chatClient.prompt()
                    .user(prompt)
                    .toolCallbacks(toolCallbacks)   // request-scoped: exactly the seven SupplyMind tools
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                return LLMService.LLMResponse.failure(LLMService.LLMStatus.MALFORMED, "empty_response");
            }
            return LLMService.LLMResponse.success(content.trim());
        } catch (RuntimeException exception) {
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,
                    "chat_failed_" + exception.getClass().getSimpleName());
        }
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
