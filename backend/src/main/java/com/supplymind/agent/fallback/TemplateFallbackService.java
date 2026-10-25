package com.supplymind.agent.fallback;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.llm.LLMService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D6-T05 Java deterministic template fallback. Produces a natural-language explanation using
 * ONLY the facts carried in the LLM request (which come exclusively from Tool Results and the
 * EvidencePack) - it never recomputes business values, never hard-codes numbers, and never
 * invents evidence. Used whenever the cloud LLM is unavailable or malformed.
 */
public final class TemplateFallbackService {

    /** Builds a concise fallback summary from deterministic facts without recomputation. */
    public String explain(LLMService.LLMRequest request, EvidencePackV1 evidencePack) {
        StringBuilder builder = new StringBuilder("根据已发布并通过校验的数据，");
        if (request.facts().isEmpty()) {
            return builder.append("当前问题没有可用的已发布/已验证事实，无法给出确定性解释。").toString();
        }
        List<LLMService.LlmFact> summaryFacts = summaryFacts(request.facts());
        for (int index = 0; index < summaryFacts.size(); index++) {
            if (index > 0) builder.append("；");
            builder.append(render(summaryFacts.get(index)));
        }
        builder.append("。");
        List<String> notices = evidencePack == null ? List.of() : evidencePack.notices();
        if (!notices.isEmpty()) {
            builder.append(" 注意：").append(String.join("；", notices)).append("。");
        }
        builder.append(" 当前为 Java 事实摘要；云端语言服务未采用，所有数值均直接来自标准事实，未在回答阶段重算。");
        return builder.toString();
    }

    private static List<LLMService.LlmFact> summaryFacts(List<LLMService.LlmFact> facts) {
        List<String> priority = List.of(
            "latest published value", "Java-computed trend direction", "Java-computed start-to-end change",
            "persisted period average", "immutable warning identifier", "verified warning rule version",
            "verified warning current value", "verified warning baseline value", "Java-evaluated warning risk level",
            "Java-evaluated warning threshold percentage", "verified warning acknowledgement status",
            "verified warning acknowledgement time", "verified warning disposition note");
        var selected=new java.util.ArrayList<LLMService.LlmFact>();
        for(String type:priority)facts.stream().filter(f->type.equals(f.statement())).forEach(selected::add);
        return selected.isEmpty()?facts.stream().limit(3).toList():List.copyOf(selected);
    }

    private static String render(LLMService.LlmFact fact) {
        String period = fact.period() == null || fact.period().isBlank() ? "" : "（" + fact.period() + "）";
        String value = fact.value() + (fact.unit() == null || fact.unit().isBlank()
                || "%".equals(fact.unit()) ? "" : " " + fact.unit());
        return switch (fact.statement()) {
            case "latest published value" -> "最新值为" + value + period;
            case "Java-computed trend direction" -> "趋势为" + localize(fact.value()) + period;
            case "Java-computed start-to-end change" -> "首尾变化为" + value + period;
            case "persisted period average" -> "已持久化平均值（"+fact.validationStatus()+"组）为" + value + period;
            case "Java-computed completeness percentage" -> "数据完整率为" + fact.value() + period;
            case "missing observation count" -> "缺失观测数为" + fact.value() + period;
            case "Java-evaluated warning risk level" -> "适用预警风险为" + localize(fact.value())+period;
            case "Java-evaluated warning threshold percentage" -> "预警阈值为" + fact.value();
            case "verified warning acknowledgement status" -> "预警处理状态为" + localize(fact.value())+"（确认不代表风险消失）";
            case "immutable warning identifier" -> "预警编号为"+fact.value()+period;
            case "verified warning rule version" -> "预警规则版本为"+fact.value();
            case "verified warning current value" -> "预警当前值为"+value;
            case "verified warning baseline value" -> "预警基准值为"+value;
            case "verified warning acknowledgement time" -> "确认时间为"+fact.value();
            case "verified warning disposition note" -> "确认备注为"+fact.value();
            default -> fact.statement() + "为" + value + period;
        };
    }

    private static String localize(String value) {
        if (value == null) return "未知";
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "UPWARD" -> "上升";
            case "DOWNWARD" -> "下降";
            case "FLAT" -> "持平";
            case "HIGH" -> "高风险";
            case "MEDIUM" -> "中风险";
            case "LOW" -> "低风险";
            case "ACKNOWLEDGED" -> "已确认";
            case "PENDING" -> "待处理";
            default -> value;
        };
    }
}
