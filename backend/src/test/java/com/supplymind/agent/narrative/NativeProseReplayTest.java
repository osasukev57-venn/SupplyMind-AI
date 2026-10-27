package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeProseReplayTest {
    final com.fasterxml.jackson.databind.JsonNode fixture;
    final EvidencePackV1 pack;
    final NarrativeValidator validator=new NarrativeValidator(new AgentResponseVerifier(List.of()));
    NativeProseReplayTest() throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/agent-v3-cloud/native-prose-replay.json")) {
            fixture=JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        pack=JsonV1Codec.mapper().treeToValue(fixture.path("evidencePack"),EvidencePackV1.class);
    }
    @Test void actualTruthfulMonthlyCaveatAndChinesePeriodAreAccepted() {
        var section=fixture.path("rejections").get(2);
        assertEquals("UNSUPPORTED_PERIOD_COVERAGE",section.path("reason").asText());
        assertEquals("FULL",replay(section,"METRICS").adoption());
    }
    @Test void actualTruthfulMixedSampleCountsAndChineseDatesAreAccepted() {
        var section=fixture.path("rejections").get(5);
        assertEquals("FULL",replay(section,"QUALITY").adoption());
    }
    @Test void latestNumbersPassWithTheRightRoleButWrongKindStillFails() {
        var section=fixture.path("rejections").get(1);
        assertEquals("NONE",replay(section,"METRICS").adoption());
        assertEquals("FULL",replay(section,"OVERVIEW").adoption());
    }
    @Test void trendRequiresEachStatedEndpointAsWellAsDerivedChange() {
        var section=fixture.path("rejections").get(0);
        assertEquals("NONE",replay(section,"TREND").adoption(),"The original response omitted endpoint citations");
        assertEquals("FULL",check("TREND",section.path("text").asText(),ids("history.query","history.latest","history.trend.direction","history.trend.change")).adoption());
    }
    @Test void correctlyCitedAcknowledgementTimestampDoesNotBecomeAnUnrelatedBusinessDate() {
        String text="比较期2026-10-15至2026-10-22，基准值23250.00，当前值25500.00，阈值5%，风险为HIGH，已确认，确认时间为2026-10-25T17:06:15.339511200+08:00；确认不代表风险消失。";
        assertEquals("FULL",check("WARNING",text,ids("warning.explain","warning.baselineValue","warning.thresholdPercent","warning.riskLevel","warning.acknowledged","warning.acknowledgedAt")).adoption());
        assertEquals("NONE",check("WARNING",text.replace("17:06:15","17:07:15"),ids("warning.explain","warning.baselineValue","warning.thresholdPercent","warning.riskLevel","warning.acknowledged","warning.acknowledgedAt")).adoption());
    }
    @Test void incorrectOrUnsupportedDatesAndFullCoverageRemainRejected() {
        assertEquals("NONE",check("OVERVIEW","截至2026年10月23日，最新值为25500元/吨。",ids("history.latest")).adoption());
        assertEquals("NONE",check("OVERVIEW","截至2026年2月30日，最新值为25500元/吨。",ids("history.latest")).adoption());
        assertEquals("NONE",check("METRICS","2026年11月均值为24800元/吨，VERIFIED组。",ids("period.metrics.avg")).adoption());
        assertEquals("NONE",check("METRICS","VERIFIED组均值24800元/吨，是完整月份行情，覆盖全部交易日。",ids("period.metrics.avg")).adoption());
    }
    @Test void fabricatedRateAndCrossGroupInternalFactReferenceAreNotExcused() {
        assertEquals("NONE",replay(fixture.path("rejections").get(4),"WARNING").adoption());
        assertEquals("NONE",replay(fixture.path("rejections").get(3),"METRICS").adoption());
    }
    private List<String> ids(String... types) {
        var wanted=Set.of(types);
        return pack.facts().stream().filter(f->wanted.contains(f.factType()))
            .filter(f->!f.factType().equals("history.query")||f.businessDate().equals("2026-10-13"))
            .filter(f->!f.factType().equals("period.metrics.avg")||f.validationStatus().equals("VERIFIED"))
            .map(EvidencePackV1.Fact::factId).toList();
    }
    private NarrativeResult replay(com.fasterxml.jackson.databind.JsonNode section,String kind) {
        var ids=new ArrayList<String>();section.path("factIds").forEach(id->ids.add(id.asText()));
        return check(kind,section.path("text").asText(),ids);
    }
    private NarrativeResult check(String kind,String text,List<String> ids) {
        return validator.validate(JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind",kind,"text",text,"factIds",ids)),"recommendations",List.of())),pack);
    }
}
