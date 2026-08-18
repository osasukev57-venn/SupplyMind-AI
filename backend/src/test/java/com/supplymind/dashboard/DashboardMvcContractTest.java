package com.supplymind.dashboard;

import com.supplymind.dashboard.api.DashboardController;
import com.supplymind.dashboard.api.DashboardV1;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D7 FORMAL MVC contract test: a real Spring MVC DispatcherServlet slice (parameter binding,
 * type conversion, missing-parameter detection and the DashboardApiAdvice exception handling)
 * - NOT a standalone MockMvc. Every invalid request shape returns 400 {status:REJECTED,
 * message}; valid requests return 200.
 */
@WebMvcTest(DashboardController.class)
class DashboardMvcContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboard;

    @Test
    void missingParameterIs400RejectedWithStructuredBody() throws Exception {
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", "FX.USD.CNY.PBOC_MID")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value(
                        "required parameter 'from' is missing"));
    }

    @Test
    void nonNumericYearIs400Rejected() throws Exception {
        mockMvc.perform(get("/api/dashboard/metrics")
                        .param("itemId", "FX.USD.CNY.PBOC_MID")
                        .param("grain", "month")
                        .param("fromYear", "abc")
                        .param("toYear", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value(
                        "parameter 'fromYear' has an invalid value"));
    }

    @Test
    void invalidDateRangeIs400RejectedWithServiceMessage() throws Exception {
        when(dashboard.history(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("from must not be after to"));
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", "FX.USD.CNY.PBOC_MID")
                        .param("from", "2026-08-31")
                        .param("to", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("from must not be after to"));
    }

    @Test
    void unknownItemIdIs400RejectedWithExactMessage() throws Exception {
        when(dashboard.history(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("unknown itemId"));
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", "FX.NOT.CONFIGURED")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("unknown itemId"));
    }

    @Test
    void validRequestsReturn200WithContractBody() throws Exception {
        when(dashboard.overview()).thenReturn(new DashboardV1.OverviewResponse(
                "FORMAL", List.of(), List.of()));
        when(dashboard.history(anyString(), anyString(), anyString()))
                .thenReturn(new DashboardV1.HistoryResponse(
                        "FX.USD.CNY.PBOC_MID", "2026-08-01", "2026-08-31",
                        List.of(new DashboardV1.HistoryPoint("2026-08-10", "6.79040000",
                                "CNY/1 USD", "中国人民银行官网", "VERIFIED",
                                "pboc-basic-validation-v1")),
                        new DashboardV1.Chart(640, 160, List.of(new DashboardV1.ChartPoint(
                                "2026-08-10 6.79040000", "8.0", "76.0"))),
                        List.of(), "2026-08-10"));
        when(dashboard.metrics(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new DashboardV1.MetricsResponse(
                        "FX.USD.CNY.PBOC_MID", "month", 2026, 2026, List.of(), List.of()));

        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", "FX.USD.CNY.PBOC_MID")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].value").value("6.79040000"))
                .andExpect(jsonPath("$.chart.width").value(640));

        mockMvc.perform(get("/api/dashboard/metrics")
                        .param("itemId", "FX.USD.CNY.PBOC_MID")
                        .param("grain", "month")
                        .param("fromYear", "2026")
                        .param("toYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grain").value("month"));
    }
}
