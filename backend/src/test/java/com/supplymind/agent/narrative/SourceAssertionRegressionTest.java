package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Constructed offline prose using the actual rc5 pack, never a captured rejected cloud response. */
class SourceAssertionRegressionTest {
    final NarrativeValidator validator=new NarrativeValidator(new AgentResponseVerifier(List.of()));
    final EvidencePackV1 pack;
    final EvidencePackV1.Fact latest;
    SourceAssertionRegressionTest() throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/agent-v3-cloud/source-assertion-replay.json")) {
            pack=JsonV1Codec.mapper().treeToValue(JsonV1Codec.mapper().readTree(Objects.requireNonNull(in)).path("evidencePack"),EvidencePackV1.class);
        }
        latest=pack.facts().stream().filter(f->f.factType().equals("history.latest")).findFirst().orElseThrow();
    }
    @ParameterizedTest @ValueSource(strings={
        "数据来自模拟演示（非真实市场数据）。",
        "数据来源为模拟演示（非真实市场数据）。",
        "数据来源是模拟演示（非真实市场数据）。",
        "数据来源为“模拟演示（非真实市场数据）”。",
        "数据来源是『模拟演示（非真实市场数据）』！",
        "数据来源于模拟演示（非真实市场数据）。",
        "数据源自模拟演示（非真实市场数据）。",
        "来源 模拟演示（非真实市场数据）。",
        "数据来自：“模拟演示（非真实市场数据）”。",
        "Source: \"模拟演示（非真实市场数据）\".",
        "数据来源：『模拟演示(非真实市场数据)』；这是模拟数据。",
        "数据来自模拟演示，非真实市场数据。",
        "数据来源为模拟数据，不代表真实市场行情。",
        "数据不是来自上海有色网，数据来自模拟演示（非真实市场数据）。",
        "数据来源不是商业报价；这是模拟数据，不代表真实市场行情。",
        "这些是模拟数据，不代表真实市场行情。",
        "来源为模拟演示（非真实市场数据），来源是模拟演示（非真实市场数据）。"})
    void truthfulNormalChineseIsAdopted(String prose) {
        var result=validate(prose,pack);
        assertEquals("FULL",result.adoption(),result.toString());
        assertTrue(result.sections().get(0).text().endsWith(prose));
    }
    @ParameterizedTest @ValueSource(strings={
        "数据来源为上海有色网真实现货行情。",
        "数据来源于上海有色网。",
        "来源 上海有色网。",
        "数据来源不为模拟演示（非真实市场数据）。",
        "数据来自模拟演示（非真实市场数据）；另一个来源是路透社。",
        "数据来源为模拟演示（非真实市场数据）及上海有色网。",
        "数据来自模拟演示（非真实市场数据），也来自商业报价。",
        "数据来自模拟数据上海有色网，非真实市场数据。",
        "数据来自上海有色网，这是模拟数据，不代表真实市场行情。",
        "数据来源为模拟数据，不是非真实市场数据。",
        "数据并非来自模拟演示（非真实市场数据）。",
        "数据来源不是模拟演示（非真实市场数据）。",
        "数据来源为；这是模拟数据。"})
    void inventedMixedDeniedOrIncompleteClaimsAreRejected(String prose) {
        assertEquals("NONE",validate(prose,pack).adoption(),prose);
    }
    @Test void boundedReasonsDistinguishMissingFactsConflictsAndExtractionWithoutModelText() throws Exception {
        assertReason(validate("来源为商业报价。",pack),"SOURCE_CONFLICT");
        assertReason(validate("来源为。",pack),"SOURCE_EXTRACTION_UNCERTAIN");
        var f=latest;
        var missing=new EvidencePackV1.Fact(f.factId(),f.factType(),f.itemId(),f.businessDate(),f.periodStart(),f.periodEnd(),f.value(),f.unit(),f.currency(),f.qualityStatus(),f.validationStatus(),f.validationVersion(),f.calculationVersion(),f.calendarVersion(),f.configVersions(),null,f.sourceFingerprint(),f.evidenceRefs(),f.rowEvidenceId());
        var noSource=new EvidencePackV1(pack.schemaVersion(),pack.evidencePackId(),pack.requestId(),pack.mode(),pack.question(),pack.createdAt(),pack.scope(),pack.toolExecutions(),List.of(missing),pack.evidenceRefs(),pack.warnings(),pack.notices(),pack.limitations());
        assertReason(validate("来源为商业报价。",noSource),"SOURCE_FACT_MISSING");
        assertEquals("FULL",validate("这些是模拟数据。",noSource).adoption(),"Omitting attribution is not a fabricated origin");
        assertReason(validate("来源为模拟演示（非真实市场数据）。".repeat(9),pack),"SOURCE_CLAIM_LIMIT");
    }
    @Test void badSecondSectionDoesNotEraseIndependentChineseAndDiagnosticsAreOnlyEnums() throws Exception {
        var raw=JsonV1Codec.encodeCompact(Map.of("sections",List.of(
            Map.of("kind","OVERVIEW","text","最新值为25500元/吨，数据来源是模拟演示（非真实市场数据）。","factIds",List.of(latest.factId())),
            Map.of("kind","OVERVIEW","text","数据来源为假冒公司。","factIds",List.of(latest.factId()))),"recommendations",List.of()));
        var result=validator.validate(raw,pack);
        assertEquals("PARTIAL",result.adoption());assertEquals(1,result.sections().size());
        assertReason(result,"SOURCE_CONFLICT");
        String diagnostic=JsonV1Codec.encodeCompact(result.diagnostics());
        assertFalse(diagnostic.contains("假冒"));assertFalse(diagnostic.contains("模拟演示"));
        assertTrue(result.diagnostics().stream().allMatch(d->d.code().matches("[A-Z_]{1,40}")));
    }
    @Test void simulationDescriptionRequiresExplicitSimulationOriginRatherThanItemIdOrDisclaimer() {
        for(String source:List.of("上海有色网","上海模拟公司（非真实市场数据）","模拟演示")) {
            assertEquals("SOURCE_CONFLICT",SourceAssertionCheck.check("数据来源为模拟数据，不代表真实市场行情。",List.of(withSource(source))));
        }
    }
    @Test void oneAttributionCannotGuessBetweenDistinctCitedOrigins() {
        assertEquals("SOURCE_BINDING_AMBIGUOUS",SourceAssertionCheck.check("数据来源为模拟演示（非真实市场数据）。",List.of(latest,withSource("本地文件导入"))));
    }
    private EvidencePackV1.Fact withSource(String source) {
        var f=latest;
        return new EvidencePackV1.Fact(f.factId(),f.factType(),f.itemId(),f.businessDate(),f.periodStart(),f.periodEnd(),f.value(),f.unit(),f.currency(),f.qualityStatus(),f.validationStatus(),f.validationVersion(),f.calculationVersion(),f.calendarVersion(),f.configVersions(),source,f.sourceFingerprint(),f.evidenceRefs(),f.rowEvidenceId());
    }
    private static void assertReason(NarrativeResult result,String code) {
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("UNSUPPORTED_SOURCE")));
        assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals(code)),result.toString());
    }
    NarrativeResult validate(String prose,EvidencePackV1 evidence) {
        var raw=JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind","OVERVIEW","text","最新值为25500.00元/吨（2026-10-22）。"+prose,"factIds",List.of(latest.factId()))),"recommendations",List.of()));
        return validator.validate(raw,evidence,new NarrativeScope(Set.of("OVERVIEW"),0));
    }
}
