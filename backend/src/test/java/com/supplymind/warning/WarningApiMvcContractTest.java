package com.supplymind.warning;

import com.supplymind.warning.api.WarningController;
import com.supplymind.warning.api.WarningV1;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D8-T02 FORMAL MVC contract test: real DispatcherServlet slice for /api/warnings. Missing
 * parameters, malformed JSON, unknown warningId and dispositionNote violations are all
 * 400 {status:REJECTED, message}; valid requests return 200 with the frozen wire contract.
 */
@WebMvcTest(WarningController.class)
class WarningApiMvcContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WarningService warnings;

    @MockBean
    private WarningQueryService query;

    @MockBean
    private WarningAckStore ackStore;

    private static final WarningRecordV1 RECORD = new WarningRecordV1(
            "1.0", "w1", "demo-price-change-x", "demo-v1", "MAT.ADC12.SMM", "month",
            "2026-08-01", "2026-08-31", null, "0.05", "0.087", "0.052",
            WarningRecordV1.RiskLevel.HIGH,
            List.of("processed/aggregate/MAT.ADC12.SMM/month/2026.csv"),
            "PUBLISHED_VERIFIED", OffsetDateTime.parse("2026-08-17T02:00:00+08:00"),
            "a".repeat(64), true, "TEST/DEMO threshold - not a final business threshold (EXT-07 open)");

    @Test
    void missingParameterIs400Rejected() throws Exception {
        mockMvc.perform(get("/api/warnings")
                        .param("itemId", "MAT.ADC12.SMM")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("required parameter 'from' is missing"));
    }

    @Test
    void malformedDateIs400Rejected() throws Exception {
        mockMvc.perform(get("/api/warnings")
                        .param("itemId", "MAT.ADC12.SMM")
                        .param("from", "not-a-date")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("from/to must be ISO yyyy-MM-dd dates"));
    }

    @Test
    void unknownWarningIdIs400Rejected() throws Exception {
        when(query.findByWarningId(anyString(), anyString())).thenReturn(java.util.Optional.empty());
        mockMvc.perform(post("/api/warnings/unknown-id/ack")
                        .param("itemId", "MAT.ADC12.SMM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispositionNote\":\"已核实\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("unknown warningId"));
    }

    @Test
    void blankDispositionNoteIs400Rejected() throws Exception {
        when(query.findByWarningId(anyString(), anyString()))
                .thenReturn(java.util.Optional.of(RECORD));
        mockMvc.perform(post("/api/warnings/w1/ack")
                        .param("itemId", "MAT.ADC12.SMM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispositionNote\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("dispositionNote is required"));
    }

    @Test
    void validListAndAckReturn200WithContractBody() throws Exception {
        when(query.queryByRange(anyString(), any(java.time.LocalDate.class), any(java.time.LocalDate.class)))
                .thenReturn(List.of(RECORD));
        when(query.findByWarningId(anyString(), anyString())).thenReturn(java.util.Optional.of(RECORD));
        when(query.isAcknowledged(any(WarningRecordV1.class))).thenReturn(false);
        when(query.ackRefOf(any(WarningRecordV1.class))).thenReturn(
                "warning/2026-08/w1.ack.json");
        when(ackStore.acknowledge(any(WarningRecordV1.class), anyString(), any(OffsetDateTime.class)))
                .thenReturn(new WarningAcknowledgementV1(
                        "1.0", "w1", "warning/2026-08/w1.json",
                        "a".repeat(64), WarningAcknowledgementV1.AckStatus.ACKNOWLEDGED,
                        OffsetDateTime.parse("2026-08-17T02:00:00+08:00"), "已核实"));

        mockMvc.perform(get("/api/warnings")
                        .param("itemId", "MAT.ADC12.SMM")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0].warningId").value("w1"))
                .andExpect(jsonPath("$.warnings[0].demoRule").value(true))
                .andExpect(jsonPath("$.warnings[0].acknowledged").value(false))
                .andExpect(jsonPath("$.warnings[0].ruleDescription").value(
                        "TEST/DEMO threshold - not a final business threshold (EXT-07 open)"));

        mockMvc.perform(post("/api/warnings/w1/ack")
                        .param("itemId", "MAT.ADC12.SMM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispositionNote\":\"已核实\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.dispositionNote").value("已核实"))
                .andExpect(jsonPath("$.warningRef").value("warning/2026-08/w1.json"));
    }

    @Test
    void evaluateResponseIsStructured() throws Exception {
        when(warnings.evaluate(any(WarningRuleV1.class), anyString(), anyString()))
                .thenReturn(RECORD);
        mockMvc.perform(post("/api/warnings/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleId\":\"demo-price-change-x\",\"ruleKind\":\"PRICE_CHANGE\"," +
                                "\"itemId\":\"MAT.ADC12.SMM\",\"grain\":\"month\"," +
                                "\"threshold\":\"0.05\",\"direction\":\"ABOVE\"," +
                                "\"periodStart\":\"2026-08-01\",\"periodEnd\":\"2026-08-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.warning.ruleId").value("demo-price-change-x"))
                .andExpect(jsonPath("$.warning.demoRule").value(true));
    }
}
