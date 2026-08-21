package com.supplymind.agent.llm;

import com.supplymind.agent.tool.ToolResult;

import java.util.List;

/**
 * D6-T03 SupplyMind LLMService application port (DEC-060: the ONLY application LLM port).
 * Business code never depends on ChatClient/ChatModel/vendor SDKs - the Spring AI adapter
 * implements this port in the infrastructure layer. The LLM may explain deterministic facts,
 * never change them and never invent evidence.
 */
public final class LLMService {

    public record LlmFact(
            String factId,
            String statement,
            String itemId,
            String value,
            String unit,
            String currency,
            String businessDate,
            String period,
            String validationStatus,
            String actualSourceName,
            String evidenceRef
    ) {
    }

    public record LLMRequest(
            String queryId,
            String question,
            String mode,
            List<LlmFact> facts,
            List<String> evidenceRefs,
            boolean toolCallingEnabled
    ) {
        public LLMRequest {
            facts = facts == null ? List.of() : List.copyOf(facts);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }

        public LLMRequest(
                String queryId, String question, String mode,
                List<LlmFact> facts, List<String> evidenceRefs
        ) {
            this(queryId, question, mode, facts, evidenceRefs, true);
        }
    }

    public enum LLMStatus {
        SUCCESS,
        UNAVAILABLE,
        MALFORMED,
        REJECTED
    }

    public record LLMResponse(
            LLMStatus status,
            String explanation,
            String failureKind,
            List<ToolResult> toolResults
    ) {
        public LLMResponse {
            toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        }

        public static LLMResponse success(String explanation) {
            return new LLMResponse(LLMStatus.SUCCESS, explanation, null, List.of());
        }

        /** M1: SUCCESS carries the full ToolResults the model actually selected and executed. */
        public static LLMResponse success(String explanation, List<ToolResult> toolResults) {
            return new LLMResponse(LLMStatus.SUCCESS, explanation, null, toolResults);
        }

        public static LLMResponse failure(LLMStatus status, String failureKind) {
            return new LLMResponse(status, null, failureKind, List.of());
        }
    }

    public interface Port {
        LLMResponse analyze(LLMRequest request);
    }
}
