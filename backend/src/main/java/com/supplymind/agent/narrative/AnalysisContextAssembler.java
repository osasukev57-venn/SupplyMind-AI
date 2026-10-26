package com.supplymind.agent.narrative;
import com.supplymind.agent.llm.LLMService;
import java.util.*;

/** Cloud projection contains roles, no raw file references, hashes or tool transcripts. */
public final class AnalysisContextAssembler {
    private AnalysisContextAssembler(){}
    public static boolean withinLimits(LLMService.LLMRequest request) {
        return request.question().length()<=2000
            && request.facts().stream().filter(f->f.statement().equals("persisted period average")).count()<=8
            && com.supplymind.foundation.codec.JsonV1Codec.encodeCompact(assemble(request)).length()<=20000;
    }
    public static Map<String,Object> assemble(LLMService.LLMRequest request) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("mode",request.mode());
        var requested = request.narrativeScope();
        result.put("requestedOutput",Map.of("allowedKinds",requested.kinds().stream().sorted().toList(),
            "maxRecommendations",requested.maxRecommendations(),
            "instruction","只回答本次问题所需主题，不因工具返回了数据而补写其他主题"));
        result.put("sampleValidation",SampleValidationSummary.fromCloud(request.facts()));
        Map<String,List<Map<String,Object>>> roles=new LinkedHashMap<>();
        for(var f:request.facts()) {
            if(!requested.allowsStatement(f.statement()))continue;
            String role=switch(f.statement()) {
                case "published daily value" -> "historySample";
                case "latest published value" -> "latest";
                case "persisted period average" -> "aggregates";
                default -> f.statement().contains("warning")?"currentWarning":f.statement().contains("trend")||f.statement().contains("change")?"trend":"quality";
            };
            if(role.equals("historySample")&&roles.getOrDefault(role,List.of()).size()>=2)continue;
            Map<String,Object> value=new LinkedHashMap<>();
            value.put("factId",f.factId());value.put("role",f.statement());value.put("itemId",f.itemId());value.put("value",f.value());
            value.put("unit",f.unit());value.put("currency",f.currency());value.put("businessDate",f.businessDate());value.put("period",f.period());
            value.put("group",f.groupIdentity());value.put("source",f.actualSourceName());
            if(role.equals("quality")) {
                value.put("derivedMetricStatus","JAVA_COMPUTED_FROM_VERIFIED_EVIDENCE");
                value.put("validationStatusScope","指标的证据已核验，不表示底层样本均为VERIFIED；样本状态见sampleValidation");
            } else value.put("validationStatus",f.validationStatus());
            roles.computeIfAbsent(role,k->new ArrayList<>()).add(value);
        }
        result.put("roles",roles);
        var daily=request.facts().stream().filter(f->f.statement().equals("published daily value")).toList();
        Map<String,Object> scope=new LinkedHashMap<>();scope.put("itemIds",request.facts().stream().map(LLMService.LlmFact::itemId).distinct().toList());
        scope.put("start",daily.stream().map(LLMService.LlmFact::businessDate).filter(Objects::nonNull).min(String::compareTo).orElse(null));
        scope.put("end",daily.stream().map(LLMService.LlmFact::businessDate).filter(Objects::nonNull).max(String::compareTo).orElse(null));
        scope.put("sources",daily.stream().map(LLMService.LlmFact::actualSourceName).filter(Objects::nonNull).distinct().toList());result.put("scope",scope);
        result.put("simulation",request.facts().stream().anyMatch(f->f.actualSourceName()!=null&&f.actualSourceName().contains("模拟")));
        result.put("limitations",List.of("均值是各自持久化周期和校验组的统计；不合并组，不重算子区间均值", "确认仅表示知悉，不代表风险解除；只解释提供的当前预警", "月度未结束或样本不全时不得称完整月份行情"));
        return result;
    }
}
