package com.supplymind.agent.llm;

import java.util.List;

/**
 * D6-T03 SupplyMind LLMService application port (DEC-060: the ONLY application LLM port).
 * Business code never depends on ChatClient/ChatModel/vendor SDKs - the Spring AI adapter
 * implements this port in the infrastructure layer. The LLM may explain deterministic facts,
 * never change them and never invent evidence.
 */
public final class LLMService {

    public record LlmFact(
            String statement,
            String value,
            String businessDate,
            String period,
            String validationStatus,
            String evidenceRef
    ) {
    }

    public record LLMRequest(
            String queryId,
            String question,
            String mode,
            List<LlmFact> facts,
            List<String> evidenceRefs
    ) {
        public LLMRequest {
            facts = facts == null ? List.of() : List.copyOf(facts);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
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
            String failureKind
    ) {
        public static LLMResponse success(String explanation) {
            return new LLMResponse(LLMStatus.SUCCESS, explanation, null);
        }

        public static LLMResponse failure(LLMStatus status, String failureKind) {
            return new LLMResponse(status, null, failureKind);
        }
    }

    public interface Port {
        LLMResponse analyze(LLMRequest request);
    }
}
