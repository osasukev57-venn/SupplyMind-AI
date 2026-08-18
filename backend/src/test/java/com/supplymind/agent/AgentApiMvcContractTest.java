package com.supplymind.agent;

import com.supplymind.agent.api.AgentQueryController;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.evidence.EvidenceStatus;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.AgentReportV1;
import com.supplymind.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D8-T03 FORMAL MVC contract test for the extended /api/agent/query response: the D6-T04
 * fields stay intact and the D8-T03 report projection (generatedBy/model/provider/scope/
 * limitations/claims/dataThrough) is mapped from the verified AgentReport/EvidencePack.
 */
@WebMvcTest(AgentQueryController.class)
class AgentApiMvcContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentOrchestrator orchestrator;

    @Test
    void missingQuestionIs400Rejected() throws Exception {
        mockMvc.perform(post("/api/agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"FX.USD.CNY.PBOC_MID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("question is required"));
    }

    @Test
    void extendedResponsePreservesD6FieldsAndAddsReportProjection() throws Exception {
        EvidencePackV1.Scope scope = new EvidencePackV1.Scope(
                List.of("FX.USD.CNY.PBOC_MID"), "2026-08-10", "2026-08-01", "2026-08-10", "Asia/Shanghai");
        EvidencePackV1 pack = new EvidencePackV1(
                "AGENT-EVIDENCE-SCHEMA-V1", "pack-1", "req-1", "FORMAL", "分析风险",
                OffsetDateTime.parse("2026-08-10T10:00:00+08:00"), scope,
                List.of(new EvidencePackV1.ToolExecution(0, "history.query", "v1", true,
                        "{\"itemId\":\"x\"}", "{\"points\":[]}", ToolStatus.SUCCESS,
                        List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"))),
                List.of(new EvidencePackV1.Fact("f1", "TREND", "FX.USD.CNY.PBOC_MID", "2026-08-10",
                        "2026-08-01", "2026-08-10", "6.79040000", "CNY/1 USD", "CNY", "COMPLETE",
                        "VERIFIED", "pboc-basic-validation-v1", "arithmetic-mean-v1",
                        "weekday-asia-shanghai-v1", List.of("1"), "中国人民银行官网", null,
                        List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"))),
                List.of(new EvidencePackV1.EvidenceRefEntry("e1", "DAILY",
                        "processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv", "a".repeat(64),
                        EvidenceStatus.VERIFIED, null, "run-1", "raw-1", "staging/run-1.json#4",
                        "2026-08-10", null, null, "pboc-basic-validation-v1",
                        "arithmetic-mean-v1", "weekday-asia-shanghai-v1", List.of("1"))),
                List.of(), List.of(), List.of("fallback: model did not select any tool"));
        AgentReportV1 report = new AgentReportV1(
                "AGENT-REPORT-V1", "report-1", "req-1", pack, "JAVA_TEMPLATE", null, null,
                true, "TOOL_EXECUTION_REJECTED",
                List.of(new AgentReportV1.FactSummary("f1", "TREND", "6.79040000", "2026-08-10",
                        "2026-08-01", "VERIFIED")),
                List.of(new AgentReportV1.Claim("claim-1", "趋势平稳",
                        List.of("processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"))),
                List.of(), List.of("fallback: model did not select any tool"),
                OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
        AgentOrchestrator.AgentResult result = new AgentOrchestrator.AgentResult(
                pack,
                new LLMService.LLMResponse(LLMService.LLMStatus.UNAVAILABLE, null, "UNAVAILABLE", List.of()),
                "report/2026-08/report-1.json", report, true, "TOOL_EXECUTION_REJECTED");
        when(orchestrator.answer(any(AgentOrchestrator.AgentQueryInput.class))).thenReturn(result);

        mockMvc.perform(post("/api/agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"分析风险\",\"itemId\":\"FX.USD.CNY.PBOC_MID\"}"))
                .andExpect(status().isOk())
                // D6-T04 fields preserved
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.llmStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.reportRef").value("report/2026-08/report-1.json"))
                .andExpect(jsonPath("$.toolTrace[0].toolName").value("history.query"))
                .andExpect(jsonPath("$.evidenceRefs[0]").value(
                        "processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"))
                // D8-T03 additions
                .andExpect(jsonPath("$.generatedBy").value("JAVA_TEMPLATE"))
                .andExpect(jsonPath("$.scope.itemIds[0]").value("FX.USD.CNY.PBOC_MID"))
                .andExpect(jsonPath("$.scope.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.limitations[0]").value("fallback: model did not select any tool"))
                .andExpect(jsonPath("$.claims[0].claimId").value("claim-1"))
                .andExpect(jsonPath("$.claims[0].text").value("趋势平稳"))
                .andExpect(jsonPath("$.claims[0].evidenceRefs[0]").value(
                        "processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv"))
                .andExpect(jsonPath("$.dataThrough").value("2026-08-10"))
                .andExpect(jsonPath("$.facts[0].value").value("6.79040000"))
                // M3: controlled evidence navigation links projected from VERIFIED entries
                .andExpect(jsonPath("$.evidenceLinks[0].evidenceId").value("e1"))
                .andExpect(jsonPath("$.evidenceLinks[0].evidenceType").value("DAILY"))
                .andExpect(jsonPath("$.evidenceLinks[0].itemId").value("FX.USD.CNY.PBOC_MID"))
                .andExpect(jsonPath("$.evidenceLinks[0].targetView").value("HISTORY"))
                .andExpect(jsonPath("$.evidenceLinks[0].route").value("/history"))
                .andExpect(jsonPath("$.evidenceLinks[0].query").value(
                        "itemId=FX.USD.CNY.PBOC_MID&from=2026-08-10&to=2026-08-10&grain=daily"))
                // M3: calculation basis from the verified lineage
                .andExpect(jsonPath("$.calculationBasis.validationVersion").value("pboc-basic-validation-v1"))
                .andExpect(jsonPath("$.calculationBasis.calculationVersion").value("arithmetic-mean-v1"))
                .andExpect(jsonPath("$.calculationBasis.calendarVersion").value("weekday-asia-shanghai-v1"))
                .andExpect(jsonPath("$.calculationBasis.configVersions[0]").value("1"));
    }

    @Test
    void nonVerifiedEvidenceNeverProducesANavigationLink() throws Exception {
        EvidencePackV1.Scope scope = new EvidencePackV1.Scope(
                List.of("FX.USD.CNY.PBOC_MID"), "2026-08-10", null, null, "Asia/Shanghai");
        EvidencePackV1 pack = new EvidencePackV1(
                "AGENT-EVIDENCE-SCHEMA-V1", "pack-2", "req-2", "FORMAL", "无数据",
                OffsetDateTime.parse("2026-08-10T10:00:00+08:00"), scope,
                List.of(new EvidencePackV1.ToolExecution(0, "history.query", "v1", true,
                        "{\"itemId\":\"x\"}", "{\"points\":[]}", ToolStatus.NO_DATA, List.of())),
                List.of(),
                List.of(new EvidencePackV1.EvidenceRefEntry("e9", "DAILY",
                        "processed/daily/FX.USD.CNY.PBOC_MID/2026-08.csv", "b".repeat(64),
                        EvidenceStatus.MISSING, "NO_FILE", null, null, null,
                        null, null, null, null, null, null, List.of())),
                List.of(), List.of(), List.of());
        AgentReportV1 report = new AgentReportV1(
                "AGENT-REPORT-V1", "report-2", "req-2", pack, "JAVA_TEMPLATE", null, null,
                true, "TOOL_EXECUTION_REJECTED",
                List.of(), List.of(), List.of(), List.of("no data"),
                OffsetDateTime.parse("2026-08-10T10:00:00+08:00"));
        AgentOrchestrator.AgentResult result = new AgentOrchestrator.AgentResult(
                pack,
                new LLMService.LLMResponse(LLMService.LLMStatus.UNAVAILABLE, null, "UNAVAILABLE", List.of()),
                "report/2026-08/report-2.json", report, true, "TOOL_EXECUTION_REJECTED");
        when(orchestrator.answer(any(AgentOrchestrator.AgentQueryInput.class))).thenReturn(result);

        mockMvc.perform(post("/api/agent/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"无数据\",\"itemId\":\"FX.USD.CNY.PBOC_MID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceLinks").isArray())
                .andExpect(jsonPath("$.evidenceLinks.length()").value(0))
                .andExpect(jsonPath("$.calculationBasis").doesNotExist())
                .andExpect(jsonPath("$.degraded").value(true));
    }
}

