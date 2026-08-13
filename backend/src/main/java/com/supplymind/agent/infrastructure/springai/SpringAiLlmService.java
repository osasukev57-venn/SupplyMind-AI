package com.supplymind.agent.infrastructure.springai;

import com.supplymind.agent.llm.LLMService;
import org.springframework.ai.chat.client.ChatClient;

/**
 * D6-T03 Spring AI infrastructure adapter: implements the SupplyMind LLMService port over the
 * Spring AI ChatClient. Spring AI types stay inside this infrastructure layer - they never
 * leak into history/warning/backfill/validation/storage/aggregation/config packages. The
 * adapter is only created when a ChatClient is configured; when the LLM is not configured the
 * application still starts and the caller falls back to the Java template report.
 */
public final class SpringAiLlmService implements LLMService.Port {

    private final ChatClient chatClient;
    private final String provider;
    private final String model;

    public SpringAiLlmService(ChatClient chatClient, String provider, String model) {
        this.chatClient = chatClient;
        this.provider = provider;
        this.model = model;
    }

    public boolean isAvailable() {
        return chatClient != null;
    }

    @Override
    public LLMService.LLMResponse analyze(LLMService.LLMRequest request) {
        if (chatClient == null) {
            return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE, "not_configured");
        }
        try {
            String prompt = buildPrompt(request);
            String content = chatClient.prompt()
                    .user(prompt)
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
