package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.foundation.codec.JsonV1Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RealCloudQualityRegressionTest {
    private final NarrativeValidator validator = new NarrativeValidator(new AgentResponseVerifier(List.of()));
    private final com.fasterxml.jackson.databind.JsonNode fixture;
    private final EvidencePackV1 pack;
    RealCloudQualityRegressionTest() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/agent-v3-cloud/adopted-quality-replay.json")) {
            fixture = JsonV1Codec.mapper().readTree(Objects.requireNonNull(input));
        }
        pack = JsonV1Codec.mapper().treeToValue(fixture.path("evidencePack"), EvidencePackV1.class);
    }
    @Test void actualAdoptedBadTextIsRejectedWithoutLosingIndependentChineseOverview() {
        assertEquals("MAT.AZ91D.SIMULATION在2026-10-13至2026-10-22共发布8条记录，与预期观测数一致，缺失数为0，完整行数为8，完整性百分比为100%，全部事实均经VERIFIED校验。",
            fixture.path("draft").path("sections").get(1).path("text").asText());
        assertEquals(Map.of("VERIFIED",4L,"VERIFIED_WITH_NOTICE",4L),SampleValidationSummary.forSection(pack.facts(),pack).counts());
        var result = validator.validate(fixture.path("draft").toString(), pack);
        assertEquals("PARTIAL", result.adoption());
        assertEquals(1,result.sections().size());
        assertEquals(fixture.path("draft").path("sections").get(0).path("text").asText(),result.sections().get(0).text());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SAMPLE_VALIDATION_OVERGENERALIZATION")));
    }
    @ParameterizedTest @ValueSource(strings={
        "底层样本同时包含VERIFIED与VERIFIED_WITH_NOTICE，完整性指标不能概括所有样本的业务校验状态。",
        "完整率为100%，不代表全部样本均为VERIFIED；底层记录存在VERIFIED_WITH_NOTICE。",
        "样本中4条VERIFIED，4条VERIFIED_WITH_NOTICE；这些是模拟数据，不代表真实市场行情。",
        "样本状态为VERIFIED与VERIFIED_WITH_NOTICE；派生质量指标由Java根据已核验证据计算。",
        "样本包含verified与verified_with_notice，两种状态并存。",
        "样本中4条verified，4条verified_with_notice。"})
    void variedTruthfulMixedStatusChinesePasses(String text) {
        var result=validator.validate(quality(text),pack);
        assertEquals("FULL",result.adoption(),result.toString());
        assertEquals(text,result.sections().get(0).text());
    }
    @ParameterizedTest @ValueSource(strings={
        "全部数据均为VERIFIED。", "全部样本均经verified校验。", "所有样本均通过校验。", "全部记录都是VERIFIED_WITH_NOTICE。",
        "样本为VERIFIED。", "只有VERIFIED。", "均是VERIFIED。", "VERIFIED共3条。", "VERIFIED_WITH_NOTICE共5条。", "VERIFIED共8条。",
        "质量指标校验状态为VERIFIED。", "完整率100%，所以全部样本均为VERIFIED。",
        "VERIFIED共999999999999999999999999999999999999999条。"})
    void overgeneralizedInventedCountsAndMetricStatusConfusionFailClosed(String text) {
        assertEquals("NONE",validator.validate(quality(text),pack).adoption(),text);
    }
    @Test void truthfulUniformVerifiedPopulationIsNotBanned() {
        var facts=pack.facts().stream().map(f -> f.factType().equals("history.query") ? new EvidencePackV1.Fact(
            f.factId(),f.factType(),f.itemId(),f.businessDate(),f.periodStart(),f.periodEnd(),f.value(),f.unit(),f.currency(),f.qualityStatus(),
            "VERIFIED",f.validationVersion(),f.calculationVersion(),f.calendarVersion(),f.configVersions(),f.actualSourceName(),f.sourceFingerprint(),f.evidenceRefs(),f.rowEvidenceId()) : f).toList();
        var allVerified=new EvidencePackV1(pack.schemaVersion(),pack.evidencePackId(),pack.requestId(),pack.mode(),pack.question(),pack.createdAt(),pack.scope(),pack.toolExecutions(),facts,pack.evidenceRefs(),pack.warnings(),pack.notices(),pack.limitations());
        assertEquals("FULL",validator.validate(quality("全部样本均为VERIFIED。"),allVerified).adoption());
    }
    @Test void latestRequestExposesOnlyLatestRoleAndCannotAdoptExtraQualityTrendOrAdvice() {
        String question=fixture.path("request").path("question").asText();
        var scope=NarrativeScope.requested(question,"HISTORY");
        assertEquals(Set.of("OVERVIEW"),scope.kinds());assertFalse(scope.recommendations());
        var sections=new ArrayList<Object>();
        sections.add(fixture.path("draft").path("sections").get(0));
        sections.add(Map.of("kind","QUALITY","text","样本包括VERIFIED和VERIFIED_WITH_NOTICE。","factIds",qualityIds()));
        var trend=pack.facts().stream().filter(f->f.factType().equals("history.trend.direction")).findFirst().orElseThrow();
        sections.add(Map.of("kind","TREND","text","区间呈上升趋势。","factIds",List.of(trend.factId())));
        var latest=pack.facts().stream().filter(f->f.factType().equals("history.latest")).findFirst().orElseThrow();
        var raw=JsonV1Codec.encodeCompact(Map.of("sections",sections,"recommendations",List.of(Map.of("text","建议继续观察后续数据。","factIds",List.of(latest.factId())))));
        var result=validator.validate(raw,pack,scope);
        assertEquals("PARTIAL",result.adoption());assertEquals(1,result.sections().size());assertTrue(result.recommendations().isEmpty());
        assertEquals(3,result.diagnostics().stream().filter(d->d.code().equals("OUT_OF_REQUEST_SCOPE")).count());
        var llmFacts=pack.facts().stream().map(f->new LLMService.LlmFact(f.factId(), switch(f.factType()) {
            case "history.query"->"published daily value";case "history.latest"->"latest published value";
            case "history.trend.direction"->"Java-computed trend direction";case "history.trend.change"->"Java-computed start-to-end change";
            default->"published row count";},f.itemId(),f.value(),f.unit(),f.currency(),f.businessDate(),f.periodStart(),f.validationStatus(),f.actualSourceName(),null)).toList();
        var context=AnalysisContextAssembler.assemble(new LLMService.LLMRequest("test",question,"FORMAL",llmFacts,List.of(),false,"HISTORY"));
        assertEquals(Set.of("latest"),((Map<?,?>)context.get("roles")).keySet());
        assertEquals(Map.of("VERIFIED",4L,"VERIFIED_WITH_NOTICE",4L),((List<SampleValidationSummary>)context.get("sampleValidation")).get(0).counts());
    }
    @Test void explicitQualityQuestionSeparatesDerivedMetricStatusAndKeepsAllSampleCounts() {
        var facts=pack.facts().stream().map(f->new LLMService.LlmFact(f.factId(),f.factType().equals("history.query")?"published daily value":"published row count",f.itemId(),f.value(),f.unit(),f.currency(),f.businessDate(),null,f.validationStatus(),f.actualSourceName(),null)).toList();
        var context=AnalysisContextAssembler.assemble(new LLMService.LLMRequest("test","说明数据质量和校验状态","FORMAL",facts,List.of(),false,"HISTORY"));
        var roles=(Map<?,?>)context.get("roles");
        var quality=(List<Map<String,Object>>)roles.get("quality");
        assertFalse(quality.isEmpty());
        assertTrue(quality.stream().allMatch(f->!f.containsKey("validationStatus")&&f.containsKey("derivedMetricStatus")));
    }
    @Test void themesBoundOutputAndAdviceRemainsAvailableWhenRequested() {
        assertEquals(Set.of("METRICS"),NarrativeScope.requested("说明月度均值和最新价格","METRICS").kinds());
        assertEquals(Set.of("WARNING"),NarrativeScope.requested("解释预警及确认处理状态","WARNING").kinds());
        assertTrue(NarrativeScope.requested("说明最新值并给出观察建议","HISTORY").recommendations());
    }
    private List<String> qualityIds() {return pack.facts().stream().filter(f->f.factType().startsWith("quality.")).map(EvidencePackV1.Fact::factId).toList();}
    private String quality(String text) {return JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind","QUALITY","text",text,"factIds",qualityIds())),"recommendations",List.of()));}
}
