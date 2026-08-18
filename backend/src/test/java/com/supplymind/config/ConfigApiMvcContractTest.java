package com.supplymind.config;

import com.supplymind.backfill.api.BackfillController;
import com.supplymind.config.api.ConfigController;
import com.supplymind.config.api.ConfigV1;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D8-T01 FORMAL MVC contract test: a real Spring MVC DispatcherServlet slice for the config
 * and backfill APIs (parameter binding, missing-parameter detection, malformed JSON handling
 * and ConfigApiAdvice exception handling). Every invalid request shape returns 400
 * {status:REJECTED, message}; valid requests return 200.
 */
@WebMvcTest({ConfigController.class, BackfillController.class})
class ConfigApiMvcContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DynamicConfigWorkflowService workflow;

    @Test
    void missingParameterIs400RejectedWithStructuredBody() throws Exception {
        mockMvc.perform(post("/api/backfill/jobs")
                        .param("itemId", "FX.GBP.CNY.PBOC_MID")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("required parameter 'from' is missing"));
    }

    @Test
    void invalidEnabledValueIs400Rejected() throws Exception {
        mockMvc.perform(post("/api/config/items/FX.EUR.CNY.PBOC_MID/enabled")
                        .param("enabled", "not-a-boolean"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("parameter 'enabled' has an invalid value"));
    }

    @Test
    void malformedJsonBodyIs400Rejected() throws Exception {
        mockMvc.perform(post("/api/config/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("request body must be a valid JSON object"));
    }

    @Test
    void invalidBodyFieldsAre400RejectedWithServiceMessage() throws Exception {
        when(workflow.addItem(any(ConfigV1.AddItemRequest.class)))
                .thenThrow(new IllegalArgumentException("itemId must match [A-Za-z0-9._-]+"));
        mockMvc.perform(post("/api/config/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"BAD ID\",\"displayName\":\"x\",\"sourceIntent\":\"PBOC\"," +
                                "\"providerType\":\"official_web\",\"accessMethod\":\"public_official_html\"," +
                                "\"actualSourceName\":\"中国人民银行官网\",\"routeDecision\":\"primary\"," +
                                "\"externalCode\":\"GBP\",\"sourceFieldKey\":\"1英镑对人民币\"," +
                                "\"rateKind\":\"人民币汇率中间价\",\"calculationVersion\":\"arithmetic-mean-v1\"," +
                                "\"calculationScale\":8,\"displayScale\":4,\"roundingMode\":\"HALF_UP\"," +
                                "\"calendarVersion\":\"weekday-asia-shanghai-v1\",\"currency\":\"CNY\"," +
                                "\"baseCurrency\":\"GBP\",\"unit\":\"CNY/1 GBP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("itemId must match [A-Za-z0-9._-]+"));
    }

    @Test
    void capabilityRejectedActivationIs400Rejected() throws Exception {
        when(workflow.addItem(any(ConfigV1.AddItemRequest.class)))
                .thenThrow(new com.supplymind.foundation.storage.StorageException(
                        "no registered provider of type FREE_PUBLIC declares capability for item FX.GBP.CNY.PBOC_MID"));
        mockMvc.perform(post("/api/config/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"FX.GBP.CNY.PBOC_MID\",\"displayName\":\"x\",\"sourceIntent\":\"PBOC\"," +
                                "\"providerType\":\"free_public\",\"accessMethod\":\"free_public_web\"," +
                                "\"actualSourceName\":\"s\",\"routeDecision\":\"fallback_free_public\"," +
                                "\"fallbackReason\":\"FREE_PUBLIC_FALLBACK\"," +
                                "\"externalCode\":\"GBP\",\"sourceFieldKey\":\"k\",\"rateKind\":\"人民币汇率中间价\"," +
                                "\"calculationVersion\":\"arithmetic-mean-v1\",\"calculationScale\":8," +
                                "\"displayScale\":4,\"roundingMode\":\"HALF_UP\"," +
                                "\"calendarVersion\":\"weekday-asia-shanghai-v1\",\"currency\":\"CNY\"," +
                                "\"baseCurrency\":\"GBP\",\"unit\":\"CNY/1 GBP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value(
                        "no registered provider of type FREE_PUBLIC declares capability for item FX.GBP.CNY.PBOC_MID"));
    }

    @Test
    void validRequestsReturn200WithContractBody() throws Exception {
        when(workflow.configView()).thenReturn(new ConfigV1.ConfigView(
                "1.0", 1, "FORMAL", "2026-08-12T02:00:00+08:00", List.of()));
        when(workflow.capabilities()).thenReturn(List.of(new ConfigV1.CapabilityView(
                "pboc-official-web", "official_web", "public_official_html",
                "中国人民银行官网（授权中国外汇交易中心公布）", true, false,
                List.of("FX.USD.CNY.PBOC_MID"), List.of("人民币汇率中间价"))));
        when(workflow.setEnabled(anyString(), anyBoolean())).thenReturn(new ConfigV1.ConfigView(
                "1.0", 2, "FORMAL", "2026-08-12T02:00:00+08:00", List.of()));
        when(workflow.retryBackfill(anyString())).thenReturn(new ConfigV1.BackfillJobView(
                "backfill-x", "FX.GBP.CNY.PBOC_MID", "2026-08-01", "2026-08-31", "WAITING",
                List.of(), null, List.of(), 2, "2026-08-12T02:00:00+08:00", "2026-08-12T02:00:00+08:00"));

        mockMvc.perform(get("/api/config/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/config/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].providerType").value("official_web"))
                .andExpect(jsonPath("$.providers[0].supportsCurrentData").value(true));

        mockMvc.perform(post("/api/config/items/FX.EUR.CNY.PBOC_MID/enabled")
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configVersion").value(2));

        mockMvc.perform(post("/api/backfill/jobs/backfill-x/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("backfill-x"))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }
}
