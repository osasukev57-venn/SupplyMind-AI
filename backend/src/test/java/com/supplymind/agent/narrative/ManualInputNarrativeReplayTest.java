package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real input evidence; new prose below is an OFFLINE construction, not recovered cloud text. */
class ManualInputNarrativeReplayTest {
    private final com.fasterxml.jackson.databind.JsonNode original;
    private final EvidencePackV1 pack;
    private final NarrativeValidator validator = new NarrativeValidator(new AgentResponseVerifier(List.of()));

    ManualInputNarrativeReplayTest() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/agent-v3-cloud/manual-input-partial-report.json")) {
            original = JsonV1Codec.mapper().readTree(Objects.requireNonNull(input));
        }
        pack = JsonV1Codec.mapper().treeToValue(original.path("report").path("evidencePack"), EvidencePackV1.class);
    }

    @Test void originalPartialOutcomeIsPreservedAndNotCalledFullCloudSuccess() {
        assertEquals("req-6a1ebfbd-715", pack.requestId());
        assertEquals("PARTIAL", original.path("narrative").path("adoption").asText());
        assertEquals(1, original.path("narrative").path("attempts").asInt());
        assertEquals(2, original.path("narrative").path("sections").size());
        assertEquals(Map.of("VERIFIED", 2L), SampleValidationSummary.forSection(pack.facts(), pack).counts());
        var saved = original.path("narrative").path("sections");
        for (var section : saved) {
            var ids = new ArrayList<String>(); section.path("factIds").forEach(id -> ids.add(id.asText()));
            assertEquals("FULL", check(section.path("kind").asText(), section.path("text").asText(), ids).adoption());
        }
    }

    @ParameterizedTest @ValueSource(strings = {
        "区间呈上升趋势，起始值25300.00元/吨和结束值26000.00元/吨，首尾变化为700.00元/吨。",
        "区间呈上升趋势；期初值为25300元/吨、期末值为26000元/吨，累计变化为700元/吨。",
        "起始值为25300元/吨，结束值为26000元/吨且累计上涨700元/吨。",
        "起始值25300元/吨及最新值26000元/吨，增加700元/吨。",
        "价格从25300元/吨上涨至26000元/吨且累计上涨700元/吨。"
    }) void ordinaryEndpointAndChangeRolesAreBoundPerNumber(String text) {
        var result = check("TREND", text, ids("history.query", "history.latest", "history.trend.direction", "history.trend.change"));
        assertEquals("FULL", result.adoption(), result.toString());
        assertEquals(text, result.sections().get(0).text());
    }

    @ParameterizedTest @ValueSource(strings = {
        "起始值26000元/吨和结束值25300元/吨，变化700元/吨。",
        "起始值25300元/吨和结束值26000元/吨，变化26000元/吨。",
        "起始值25300元/吨，结束值700元/吨且增加26000元/吨。",
        "最新值26000元/吨和最高值26000元/吨。",
        "最新值26000元/吨，涨幅为2.77%。",
        "起始值25300元/吨和结束值26000元/吨，累计上涨700%。",
        "起始值25300元/吨和结束值26000元/吨，累计上涨701元/吨。",
        "价格从26000元/吨上涨至25300元/吨且累计上涨700元/吨。",
        "价格从25300元/吨上涨至26000元/吨且累计上涨26000元/吨。"
    }) void swappedRolesInventedPricesRatesAndExtremaRemainRejected(String text) {
        assertEquals("NONE", check("TREND", text, ids("history.query", "history.latest", "history.trend.direction", "history.trend.change")).adoption(), text);
    }

    @ParameterizedTest @ValueSource(strings = {
        "2条样本均为VERIFIED。",
        "2条记录的校验状态为VERIFIED。",
        "共2条观测记录，校验状态均为VERIFIED。",
        "VERIFIED状态的样本共2条。",
        "VERIFIED为2条，VERIFIED_WITH_NOTICE为0条。",
        "2条VERIFIED样本和0条VERIFIED_WITH_NOTICE样本。",
        "VERIFIED与VERIFIED_WITH_NOTICE分别为2条和0条。"
    }) void naturalCountBindingsAndExplicitZeroAreAccepted(String text) {
        var result = check("QUALITY", text, ids("quality.rowCount", "quality.expectedCount", "quality.missingCount", "quality.completeRowCount", "quality.completeness"));
        assertEquals("FULL", result.adoption(), result.toString());
    }

    @ParameterizedTest @ValueSource(strings = {
        "3条样本均为VERIFIED。",
        "VERIFIED状态的样本共1条。",
        "VERIFIED为2条，VERIFIED_WITH_NOTICE为1条。",
        "0条VERIFIED样本和2条VERIFIED_WITH_NOTICE样本。",
        "VERIFIED与VERIFIED_WITH_NOTICE分别为0条和2条。",
        "VERIFIED为2条且完整率999%。",
        "VERIFIED状态的样本共2条且最新值99999元/吨。",
        "VERIFIED与VERIFIED_WITH_NOTICE分别为2条和0条且缺失999条。"
    }) void inventedCountAndOtherClaimsCannotHitchhikeOnCountBindings(String text) {
        assertEquals("NONE", check("QUALITY", text, ids("quality.rowCount", "quality.expectedCount", "quality.missingCount", "quality.completeRowCount", "quality.completeness")).adoption(), text);
    }

    @Test void missingEndpointCitationCannotBeReplacedByCoincidentOtherRole() {
        assertEquals("NONE", check("TREND", "起始值25300元/吨和结束值26000元/吨。", ids("history.latest", "history.trend.direction", "history.trend.change")).adoption());
    }

    private List<String> ids(String... types) {
        var wanted = Set.of(types);
        return pack.facts().stream().filter(f -> wanted.contains(f.factType()))
            .filter(f -> !f.factType().equals("history.query") || f.businessDate().equals("2026-10-23"))
            .map(EvidencePackV1.Fact::factId).toList();
    }

    private NarrativeResult check(String kind, String text, List<String> ids) {
        return validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", List.of(Map.of("kind", kind, "text", text, "factIds", ids)), "recommendations", List.of())), pack);
    }
}
