package com.supplymind.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.supplymind.config.ConfigManagementService;
import com.supplymind.dashboard.api.DashboardController;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.warning.WarningService;
import com.supplymind.warning.WarningStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D7 real MockMvc API tests over the REAL DashboardService (persisted fixture data): HTTP
 * status codes, structured error bodies, and the frozen wire contract (business period
 * references only - internal CSV paths never appear; chart coordinates come from the backend).
 */
class DashboardApiMockMvcTest {

    private static final ZoneId SHANGHAI = DataPaths.SHANGHAI;
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:02:00Z"), SHANGHAI);
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.ofInstant(
            Instant.parse("2026-08-10T01:00:00Z"), SHANGHAI);

    @TempDir
    Path temporaryDirectory;

    private MockMvc mockMvc;
    private Harness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = harness();
        publish(harness, raw("run-dash-mock-001"));
        DashboardController controller = new DashboardController(harness.dashboard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void overviewEndpointReturns200WithFrozenContract() throws Exception {
        mockMvc.perform(get("/api/dashboard/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("FORMAL"))
                .andExpect(jsonPath("$.items[?(@.itemId == 'FX.USD.CNY.PBOC_MID')].latestValue")
                        .value("6.7904"))
                .andExpect(jsonPath("$.items[?(@.itemId == 'FX.USD.CNY.PBOC_MID')].source.actualSourceName")
                        .value(MonitorSeriesDefaults.PBOC_SOURCE_NAME))
                .andExpect(jsonPath("$.items[?(@.itemId == 'FX.USD.CNY.PBOC_MID')].quality.validationVersion")
                        .value("pboc-basic-validation-v1"));
    }

    @Test
    void historyEndpointReturnsPointsChartAndBusinessIssues() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = JsonV1Codec.mapper().readTree(result.getResponse().getContentAsString());
        assertTrue(body.has("chart"), "chart coordinates come from the backend");
        assertTrue(body.path("chart").path("points").isArray());
        assertTrue(body.path("evidenceIssues").isArray());
        assertFalse(body.toString().contains("processed/"),
                "internal CSV paths must never leave the backend");
    }

    @Test
    void missingPeriodIsReportedAsBusinessReference() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = JsonV1Codec.mapper().readTree(result.getResponse().getContentAsString());
        JsonNode issues = body.path("evidenceIssues");
        assertTrue(issues.size() > 0, "the missing period must be reported");
        boolean businessReference = false;
        for (JsonNode issue : issues) {
            for (JsonNode period : issue.path("periods")) {
                if (period.asText().equals("2026-01")) {
                    businessReference = true;
                }
                assertFalse(period.asText().contains("processed/"),
                        "a period is a business reference, never an internal path");
            }
            assertEquals("MISSING", issue.path("status").asText());
            assertFalse(issue.path("reason").asText().isBlank());
        }
        assertTrue(businessReference);
    }

    @Test
    void invalidDateParamIs400RejectedNever500() throws Exception {
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "not-a-date")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidGrainIs400RejectedNever500() throws Exception {
        mockMvc.perform(get("/api/dashboard/metrics")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("grain", "century")
                        .param("fromYear", "2026")
                        .param("toYear", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void unknownItemIdIs400RejectedWithExactMessage() throws Exception {
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", "FX.NOT.CONFIGURED")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("unknown itemId"));

        mockMvc.perform(get("/api/dashboard/quality")
                        .param("itemId", "FX.NOT.CONFIGURED")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("unknown itemId"));

        mockMvc.perform(get("/api/dashboard/metrics")
                        .param("itemId", "FX.NOT.CONFIGURED")
                        .param("grain", "month")
                        .param("fromYear", "2026")
                        .param("toYear", "2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("unknown itemId"));
    }

    @Test
    void fromAfterToIs400Rejected() throws Exception {
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "2026-08-31")
                        .param("to", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("from must not be after to"));
    }

    @Test
    void oversizedHistoryRangeIs400Rejected() throws Exception {
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "2000-01-01")
                        .param("to", "2050-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value(
                        "date range too large (max 3660 days)"));
    }

    @Test
    void validHistoryRequestStillReturns200() throws Exception {
        mockMvc.perform(get("/api/dashboard/history")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray());
    }

    @Test
    void qualityAndSourcesEndpointsReturn200() throws Exception {
        mockMvc.perform(get("/api/dashboard/quality")
                        .param("itemId", MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestStatus").isNotEmpty())
                .andExpect(jsonPath("$.evidenceIssues").isArray());

        mockMvc.perform(get("/api/dashboard/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualEntry.status").value("PENDING"))
                .andExpect(jsonPath("$.importEntry.status").value("PENDING"));
    }

    // ---- fixture ----

    private static void publish(Harness harness, RawReceiptV1 raw) throws Exception {
        new RawAcquisitionStore(harness.root, harness.files, FIXED_CLOCK)
                .store(com.supplymind.foundation.model.DomainFixtures.acquisitionFor(raw));
        harness.rawStore.store(raw);
        harness.timeline.createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
        harness.validation.process(raw.runId());
        harness.publish.process(raw.runId());
    }

    private static RawReceiptV1 raw(String runId) {
        byte[] payload = ("test/contract fixture PBOC-shaped page - NOT REAL PBOC - " + runId)
                .getBytes(StandardCharsets.UTF_8);
        return new RawReceiptV1(
                SchemaV1.VERSION,
                RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID, RECEIVED_AT, runId),
                "acq-" + runId, runId, Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, 1, MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                "https://example.test/pbc/fixture", "test fixture",
                MonitorSeriesDefaults.USD_CNY_ITEM_ID, "2026-08-10", "2026-08-10",
                "2026-08-10 09:25:38", RECEIVED_AT.plusMinutes(30), RECEIVED_AT, null,
                "6.7904", "CNY/1 USD", "CNY", null, 200, "text/html", "base64",
                Base64.getEncoder().encodeToString(payload), JsonV1Codec.sha256LowerHex(payload),
                "1美元对人民币", RECEIVED_AT, DataPaths.acquisitionRef("acq-" + runId), null);
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d7 mockmvc root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, fileStore, FIXED_CLOCK);
        configs.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation =
                new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish =
                new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        PublishedQueryService query = new PublishedQueryService(root, timelineStore, FIXED_CLOCK);
        ConfigManagementService configManagement = new ConfigManagementService(configs,
                new DataProviderRegistry());
        HistoryQueryService history = new HistoryQueryService(root);
        WarningService warnings = new WarningService(root,
                new WarningStore(root, fileStore, FIXED_CLOCK), FIXED_CLOCK, history);
        DashboardService dashboard = new DashboardService(configManagement, query, history, warnings, FIXED_CLOCK);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, publish, dashboard);
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            RawReceiptStore rawStore,
            TimelineStore timeline,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DashboardService dashboard
    ) {
    }
}
