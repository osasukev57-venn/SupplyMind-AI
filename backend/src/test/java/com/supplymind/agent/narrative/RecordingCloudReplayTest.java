package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exact recording rehearsal response, kept separate from later real cloud attempts. */
class RecordingCloudReplayTest {
    final com.fasterxml.jackson.databind.JsonNode original, rejected;
    final EvidencePackV1 pack;
    final NarrativeValidator validator = new NarrativeValidator(new AgentResponseVerifier(List.of()));
    RecordingCloudReplayTest() throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rc10-partial-report.json")) {
            original = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rejected-quality.json")) {
            rejected = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        pack = JsonV1Codec.mapper().treeToValue(original.path("report").path("evidencePack"), EvidencePackV1.class);
    }
    @Test void actualUnmodifiedQualityParagraphBindsItsAccurateSampleCount() {
        assertEquals("req-7f96f75b-b7d", rejected.path("requestId").asText());
        assertFalse(rejected.path("truncated").asBoolean());
        var result = check(rejected.path("text").asText());
        assertEquals("FULL", result.adoption(), result.toString());
        assertEquals(rejected.path("text").asText(), result.sections().get(0).text());
    }
    @Test void allFourOriginalParagraphsPassOfflineWithoutRelabellingHistoricalReport() {
        var sections = new ArrayList<Object>();
        original.path("narrative").path("sections").forEach(s -> sections.add(Map.of(
            "kind", s.path("kind").asText(), "text", s.path("text").asText(),
            "factIds", JsonV1Codec.mapper().convertValue(s.path("factIds"), List.class))));
        sections.add(Map.of("kind", "QUALITY", "text", rejected.path("text").asText(), "factIds", ids()));
        var result = validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", sections, "recommendations", List.of())), pack);
        assertEquals("FULL", result.adoption(), result.toString());
        assertEquals(4, result.sections().size());
        assertEquals("PARTIAL", original.path("narrative").path("adoption").asText());
    }
    @Test void actualMetricsParagraphWithUncitedDailyDateAndCountRemainsRejected() throws Exception {
        com.fasterxml.jackson.databind.JsonNode report, paragraph;
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-metrics-none-report.json")) {
            report = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rejected-metrics.json")) {
            paragraph = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        assertFalse(paragraph.path("truncated").asBoolean());
        var evidence = JsonV1Codec.mapper().treeToValue(report.path("report").path("evidencePack"), EvidencePackV1.class);
        var result = validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", List.of(Map.of(
            "kind", "METRICS", "text", paragraph.path("text").asText(),
            "factIds", JsonV1Codec.mapper().convertValue(paragraph.path("factIds"), List.class))), "recommendations", List.of())), evidence);
        assertEquals("NONE", result.adoption());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("WRONG_DATE_ROLE")));
    }
    @Test void actualNegatedCoverageIsAcceptedButPositiveCoverageStillRejected() throws Exception {
        com.fasterxml.jackson.databind.JsonNode report, paragraph;
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-coverage-partial-report.json")) {
            report = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rejected-coverage.json")) {
            paragraph = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        assertFalse(paragraph.path("truncated").asBoolean());
        String text = paragraph.path("text").asText();
        var evidence = JsonV1Codec.mapper().treeToValue(report.path("report").path("evidencePack"), EvidencePackV1.class);
        for (String variant : List.of(text, text.replace("不覆盖", "并不覆盖"), text.replace("不覆盖", "不代表"))) {
            var result = metrics(variant, paragraph, evidence);
            assertEquals("FULL", result.adoption(), result.toString());
            assertEquals(variant, result.sections().get(0).text());
        }
        for (String variant : List.of(text.replace("不覆盖", "覆盖"), text.replace("不覆盖", "表示"), text + "该统计覆盖完整月度行情。")) {
            var result = metrics(variant, paragraph, evidence);
            assertEquals("NONE", result.adoption());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("UNSUPPORTED_PERIOD_COVERAGE")));
        }
    }
    private NarrativeResult metrics(String text, com.fasterxml.jackson.databind.JsonNode paragraph, EvidencePackV1 evidence) {
        return validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", List.of(Map.of(
            "kind", "METRICS", "text", text,
            "factIds", JsonV1Codec.mapper().convertValue(paragraph.path("factIds"), List.class))), "recommendations", List.of())), evidence);
    }
    @Test void actualCountProseAndSourceSpanKeepExactMeaning() throws Exception {
        com.fasterxml.jackson.databind.JsonNode report, paragraph, span;
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-language-partial-report.json")) {
            report = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rejected-count-prose.json")) {
            paragraph = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rejected-source-span.json")) {
            span = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        var evidence = JsonV1Codec.mapper().treeToValue(report.path("report").path("evidencePack"), EvidencePackV1.class);
        var result = validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", List.of(Map.of(
            "kind", "QUALITY", "text", paragraph.path("text").asText(),
            "factIds", JsonV1Codec.mapper().convertValue(paragraph.path("factIds"), List.class))), "recommendations", List.of())), evidence);
        assertEquals("FULL", result.adoption(), result.toString());
        // This source audit contains the attribution span, not a saved full TREND paragraph.
        var facts = evidence.facts().stream().filter(f -> Set.of("fact-0", "fact-1", "fact-4", "fact-5").contains(f.factId())).toList();
        String source = "来源" + span.path("asserted").asText();
        assertNull(SourceAssertionCheck.check(source, facts));
        assertNotNull(SourceAssertionCheck.check(source.replace("模拟人工录入", "中国人民银行"), facts));
        assertNotNull(SourceAssertionCheck.check(source.replace("均为", "均不是"), facts));
        assertNotNull(SourceAssertionCheck.check(source.replace("均为", "都不是"), facts));
        assertNull(SourceAssertionCheck.check(source.replace("均为", "都为"), facts));
    }
    @Test void universalCountCannotHideAnotherValidationGroup() {
        var mixed = new SampleValidationSummary("item", Map.of("VERIFIED", 2L, "VERIFIED_WITH_NOTICE", 1L));
        assertFalse(mixed.countClause("当前全部2条样本均为VERIFIED"));
        assertFalse(mixed.countClause("所有2条样本均为VERIFIED"));
        assertFalse(mixed.countClause("2条样本全部为VERIFIED"));
        assertTrue(mixed.countClause("VERIFIED样本共2条"));
        var single = new SampleValidationSummary("item", Map.of("VERIFIED", 2L));
        assertFalse(single.countClause("当前全部3条样本均为VERIFIED"));
        assertFalse(single.countClause("共发布3条已核验样本（VERIFIED）"));
        assertFalse(single.countClause("当前全部2条样本均为VERIFIED且价格为26300元/吨"));
    }
    @Test void actualTrendUsingFromSynonymStillBindsBothEndpoints() throws Exception {
        com.fasterxml.jackson.databind.JsonNode report, paragraph;
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-trend-from-partial-report.json")) {
            report = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        try (var in = getClass().getResourceAsStream("/contracts/agent-v3-cloud/recording-rejected-trend-from.json")) {
            paragraph = JsonV1Codec.mapper().readTree(Objects.requireNonNull(in));
        }
        var evidence = JsonV1Codec.mapper().treeToValue(report.path("report").path("evidencePack"), EvidencePackV1.class);
        String text = paragraph.path("text").asText();
        for (String variant : List.of(text, text.replace("由", "从"))) {
            var result = trend(variant, paragraph, evidence);
            assertEquals("FULL", result.adoption(), result.toString());
            assertEquals(variant, result.sections().get(0).text());
        }
        for (String variant : List.of(text.replace("25300.00", "25301.00"), text.replace("26000.00", "26001.00"), text.replace("+700.00", "+701.00"), text.replace("由25300.00元/吨上升至26000.00", "由26000.00元/吨上升至25300.00"))) {
            assertEquals("NONE", trend(variant, paragraph, evidence).adoption(), variant);
        }
    }
    private NarrativeResult trend(String text, com.fasterxml.jackson.databind.JsonNode paragraph, EvidencePackV1 evidence) {
        return validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", List.of(Map.of(
            "kind", "TREND", "text", text,
            "factIds", JsonV1Codec.mapper().convertValue(paragraph.path("factIds"), List.class))), "recommendations", List.of())), evidence);
    }
    @ParameterizedTest @ValueSource(strings = {
        "但样本状态分布需参考sampleValidation：其中VERIFIED样本共2条",
        "样本状态分布可参见sampleValidation：VERIFIED样本共2条",
        "样本状态分布依据sampleValidation：VERIFIED样本共2条",
        "VERIFIED样本共2条",
        "VERIFIED_WITH_NOTICE样本共0条"
    }) void neutralSummaryReferenceDoesNotChangeCountMeaning(String text) {
        assertEquals("FULL", check(text).adoption(), text);
    }
    @ParameterizedTest @ValueSource(strings = {
        "但样本状态分布需参考sampleValidation：其中VERIFIED样本共3条",
        "样本状态分布需参考sampleValidation：其中VERIFIED_WITH_NOTICE样本共2条",
        "样本状态分布需参考sampleValidation：其中VERIFIED样本共2条且价格为26300元/吨",
        "样本状态分布需参考sampleValidation：其中VERIFIED样本共2条且完整率为99%",
        "样本状态分布需参考sampleValidation：其中VERIFIED样本共2条且风险已经消失",
        "样本状态分布需参考sampleValidation：其中VERIFIED样本共2条且全部指标状态为VERIFIED",
        "样本状态分布需参考sampleValidation：其中VERIFIED样本共2条且VERIFIED_WITH_NOTICE样本共1条",
        "VERIFIED样本共2条且趋势必然上涨"
    }) void incorrectCountsAndUnrelatedClaimsCannotBorrowTheCountExemption(String text) {
        assertEquals("NONE", check(text).adoption(), text);
    }
    private List<String> ids() { return JsonV1Codec.mapper().convertValue(rejected.path("factIds"), List.class); }
    private NarrativeResult check(String text) {
        return validator.validate(JsonV1Codec.encodeCompact(Map.of("sections", List.of(
            Map.of("kind", "QUALITY", "text", text, "factIds", ids())), "recommendations", List.of())), pack);
    }
}
