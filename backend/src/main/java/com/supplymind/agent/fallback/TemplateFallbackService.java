package com.supplymind.agent.fallback;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.llm.LLMService;

import java.util.List;

/**
 * D6-T05 Java deterministic template fallback. Produces a natural-language explanation using
 * ONLY the facts carried in the LLM request (which come exclusively from Tool Results and the
 * EvidencePack) - it never recomputes business values, never hard-codes numbers, and never
 * invents evidence. Used whenever the cloud LLM is unavailable or malformed.
 */
public final class TemplateFallbackService {

    /** Builds the fallback explanation from the provided deterministic facts. */
    public String explain(LLMService.LLMRequest request, EvidencePackV1 evidencePack) {
        StringBuilder builder = new StringBuilder();
        builder.append("根据正式数据（Java 确定性工具链），");
        if (request.facts().isEmpty()) {
            builder.append("当前问题没有可用的已发布/已验证事实，无法给出确定性解释。");
            return builder.toString();
        }
        for (int index = 0; index < request.facts().size(); index++) {
            LLMService.LlmFact fact = request.facts().get(index);
            if (index > 0) {
                builder.append("；");
            }
            builder.append(fact.statement())
                    .append("，值为 ").append(fact.value());
            if (fact.period() != null && !fact.period().isBlank()) {
                builder.append("（周期 ").append(fact.period()).append("）");
            }
            if (fact.validationStatus() != null && !fact.validationStatus().isBlank()) {
                builder.append("，验证状态 ").append(fact.validationStatus());
            }
            if (fact.evidenceRef() != null && !fact.evidenceRef().isBlank()) {
                builder.append("，证据 ").append(fact.evidenceRef());
            }
        }
        builder.append("。");
        List<String> notices = evidencePack == null ? List.of() : evidencePack.notices();
        if (!notices.isEmpty()) {
            builder.append(" 注意：").append(String.join("；", notices)).append("。");
        }
        builder.append(" 本回答由 Java 模板生成（云端 LLM 不可用），数值均来自已发布的正式数据，未做任何重算。");
        return builder.toString();
    }
}
