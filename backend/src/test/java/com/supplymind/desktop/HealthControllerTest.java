package com.supplymind.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D9-T01 MVC contract test for the additive desktop readiness endpoint. The endpoint is
 * deliberately dependency-free so the Electron shell can poll it immediately after the JAR
 * starts: it reports UP, the application name and the backend PID - never business data or
 * secrets.
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsUpWithApplicationAndPid() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("supplymind-backend"))
                .andExpect(jsonPath("$.pid").value(org.hamcrest.Matchers.matchesPattern("\\d+")));
    }
}
