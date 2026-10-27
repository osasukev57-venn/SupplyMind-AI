package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the exact real qwen-plus paragraph; no generated or rewritten replacement prose. */
class LatestObservationAliasReplayTest {
    final com.fasterxml.jackson.databind.JsonNode original;
    final com.fasterxml.jackson.databind.JsonNode rejected;
    final EvidencePackV1 pack;
    final NarrativeValidator validator=new NarrativeValidator(new AgentResponseVerifier(List.of()));
    LatestObservationAliasReplayTest() throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/agent-v3-cloud/manual-input-rc9-partial-report.json")) {
            original=JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try(var in=getClass().getResourceAsStream("/contracts/agent-v3-cloud/manual-input-rejected-trend.json")) {
            rejected=JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        pack=JsonV1Codec.mapper().treeToValue(original.path("report").path("evidencePack"),EvidencePackV1.class);
    }
    @Test void actualUntruncatedTrendBindsTheSameDailyObservationWithoutRewritingTextOrIds() {
        assertEquals("req-a673e827-163",rejected.path("requestId").asText());
        assertFalse(rejected.path("truncated").asBoolean());
        assertEquals("UNSUPPORTED_NUMBER",rejected.path("reason").asText());
        var result=check("TREND",rejected.path("text").asText(),ids(),pack);
        assertEquals("FULL",result.adoption(),result.toString());
        assertEquals(rejected.path("text").asText(),result.sections().get(0).text());
        assertEquals(ids(),result.sections().get(0).factIds());
        assertFalse(result.sections().get(0).factIds().contains("fact-3"));
    }
    @Test void allFourSectionsFromThatOneRealResponsePassOnlyInAnOfflineReplay() {
        var sections=new ArrayList<Object>();
        original.path("narrative").path("sections").forEach(s->sections.add(Map.of("kind",s.path("kind").asText(),"text",s.path("text").asText(),"factIds",JsonV1Codec.mapper().convertValue(s.path("factIds"),List.class))));
        sections.add(Map.of("kind","TREND","text",rejected.path("text").asText(),"factIds",ids()));
        var result=validator.validate(JsonV1Codec.encodeCompact(Map.of("sections",sections,"recommendations",List.of())),pack);
        assertEquals("FULL",result.adoption(),result.toString());assertEquals(4,result.sections().size());
        assertEquals("PARTIAL",original.path("narrative").path("adoption").asText(),"The historical result must never be overwritten or relabelled");
    }
    @ParameterizedTest @ValueSource(strings={"itemId","businessDate","value","unit","currency","qualityStatus","validationStatus","validationVersion","calculationVersion","calendarVersion","configVersions","actualSourceName","sourceFingerprint","evidenceRefs","rowEvidenceId","periodStart","periodEnd"})
    void anyIdentityMismatchCannotBorrowTheLatestRole(String field) throws Exception {
        var tree=JsonV1Codec.mapper().valueToTree(pack);
        for(var fact:tree.path("facts"))if(fact.path("factId").asText().equals("fact-3")) {
            var edited=(com.fasterxml.jackson.databind.node.ObjectNode)fact;
            if(field.equals("configVersions")||field.equals("evidenceRefs"))edited.set(field,JsonV1Codec.mapper().valueToTree(List.of("different")));
            else if(field.equals("value"))edited.put(field,"26001.00");
            else if(field.equals("businessDate"))edited.put(field,"2026-10-26");
            else edited.put(field,"different");
        }
        var changed=JsonV1Codec.mapper().treeToValue(tree,EvidencePackV1.class);
        assertEquals("NONE",check("TREND",rejected.path("text").asText(),ids(),changed).adoption(),field);
    }
    @Test void latestDateAndOverviewCanUseAnIdenticalExplicitDailyCitation() {
        var result=check("OVERVIEW","最新值26000.00元/吨（2026-10-27）。",List.of("fact-1"),pack);
        assertEquals("FULL",result.adoption(),result.toString());
    }
    @Test void anEarlierObservationOrUncitedLatestCannotBePromoted() {
        assertEquals("NONE",check("OVERVIEW","最新值25300元/吨（2026-10-23）。",List.of("fact-0"),pack).adoption());
        assertEquals("NONE",check("OVERVIEW","最新值26000元/吨（2026-10-27）。",List.of("fact-0"),pack).adoption());
    }
    @Test void aCanonicalLatestIsRequiredRatherThanGuessingFromTheLastQueryRow() {
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().valueToTree(pack);
        tree.set("facts",JsonV1Codec.mapper().valueToTree(pack.facts().stream().filter(f->!f.factType().equals("history.latest")).toList()));
        try {
            var changed=JsonV1Codec.mapper().treeToValue(tree,EvidencePackV1.class);
            assertEquals("NONE",check("TREND",rejected.path("text").asText(),ids(),changed).adoption());
        }catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new AssertionError(ex);}
    }
    private List<String> ids(){return JsonV1Codec.mapper().convertValue(rejected.path("factIds"),List.class);}
    private NarrativeResult check(String kind,String text,List<String> ids,EvidencePackV1 evidence) {
        return validator.validate(JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind",kind,"text",text,"factIds",ids)),"recommendations",List.of())),evidence);
    }
}
