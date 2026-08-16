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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mock.web.MockMultipartFile;

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

    @Test
    void manualSubmitGoesThroughTheRealManualIntakeBoundary() throws Exception {
        String manualItemId = harness.manualItemId();
        mockMvc.perform(post("/api/dashboard/manual")
                        .param("itemId", manualItemId)
                        .param("source", "operator source")
                        .param("businessDate", "2026-08-10")
                        .param("value", "18000.00000000")
                        .param("unit", "CNY/MT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.itemId").value(manualItemId))
                .andExpect(jsonPath("$.source").value("operator source"))
                .andExpect(jsonPath("$.unit").value("CNY/MT"))
                .andExpect(jsonPath("$.businessDate").value("2026-08-10"))
                .andExpect(jsonPath("$.value").value("18000.00000000"))
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andExpect(jsonPath("$.rawRef").isNotEmpty())
                .andExpect(jsonPath("$.timelineRef").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void manualSubmitMissingSourceOrUnitIs400Rejected() throws Exception {
        String manualItemId = harness.manualItemId();
        mockMvc.perform(post("/api/dashboard/manual")
                        .param("itemId", manualItemId)
                        .param("businessDate", "2026-08-10")
                        .param("value", "18000.00000000")
                        .param("unit", "CNY/MT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("source is required"));

        mockMvc.perform(post("/api/dashboard/manual")
                        .param("itemId", manualItemId)
                        .param("source", "operator source")
                        .param("businessDate", "2026-08-10")
                        .param("value", "18000.00000000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value("unit is required"));
    }

    @Test
    void manualSubmitUnitMismatchIs400Rejected() throws Exception {
        String manualItemId = harness.manualItemId();
        mockMvc.perform(post("/api/dashboard/manual")
                        .param("itemId", manualItemId)
                        .param("source", "operator source")
                        .param("businessDate", "2026-08-10")
                        .param("value", "18000.00000000")
                        .param("unit", "CNY/1 USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.message").value(
                        "unit does not match the configured item unit (CNY/MT)"));
    }

    @Test
    void importSubmitGoesThroughTheRealLocalImportBoundary() throws Exception {
        String importItemId = harness.importItemId();
        // Frozen LocalImport template header.
        String csv = "schemaVersion,itemId,businessDate,value,unit,currency,actualSourceName,sourceReference,sourceUrl\n"
                + "1.0," + importItemId + ",2026-08-10,18000.00000000,CNY/MT,CNY,import source,ref-1,\n"
                + "1.0," + importItemId + ",2026-08-11,,CNY/MT,CNY,import source,ref-2,\n";
        mockMvc.perform(multipart("/api/dashboard/import")
                        .file(new MockMultipartFile("file", "rows.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.acceptedRows[0].rowNumber").value(2))
                .andExpect(jsonPath("$.acceptedRows[0].runId").isNotEmpty())
                .andExpect(jsonPath("$.acceptedRows[0].rawRef").isNotEmpty())
                .andExpect(jsonPath("$.rowErrors[0].rowNumber").value(3));

        mockMvc.perform(multipart("/api/dashboard/import")
                        .file(new MockMultipartFile("file", "bad.csv", "text/csv",
                                "not a csv".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void importTemplateEndpointReturnsTheFrozenHeader() throws Exception {
        mockMvc.perform(get("/api/dashboard/import/template"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"import-template.csv\""))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "schemaVersion,itemId,businessDate,value,unit,currency,actualSourceName,sourceReference,sourceUrl")));
    }

    @Test
    void syntheticDemoEndpointRunsTheRealProvider() throws Exception {
        mockMvc.perform(post("/api/dashboard/synthetic-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEMO_GENERATED"))
                .andExpect(jsonPath("$.itemIds").isArray());
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
        ConfigActivationStore activationStore = new ConfigActivationStore(root, fileStore, FIXED_CLOCK);
        activationStore.ensureInitialDefault();
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, FIXED_CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, FIXED_CLOCK);
        LifecycleValidationService validation =
                new LifecycleValidationService(root, timelineStore, FIXED_CLOCK);
        QuarantineStore quarantineStore = new QuarantineStore(root, fileStore, FIXED_CLOCK);
        LifecyclePublishService publish =
                new LifecyclePublishService(root, timelineStore, quarantineStore, FIXED_CLOCK);
        PublishedQueryService query = new PublishedQueryService(root, timelineStore, FIXED_CLOCK);
        DataProviderRegistry registry = new DataProviderRegistry();
        ConfigManagementService configManagement = new ConfigManagementService(activationStore, registry);
        HistoryQueryService history = new HistoryQueryService(root);
        WarningService warnings = new WarningService(root,
                new WarningStore(root, fileStore, FIXED_CLOCK), FIXED_CLOCK, history);
        com.supplymind.manual.ManualMaterialIntakeService manualIntake =
                new com.supplymind.manual.ManualMaterialIntakeService(
                        root, rawStore, timelineStore, new com.supplymind.manual.ManualMaterialNormalizer(),
                        com.supplymind.manual.OperatorContext.configured("test-operator"),
                        FIXED_CLOCK);
        com.supplymind.localimport.LocalImportService localImport =
                new com.supplymind.localimport.LocalImportService(
                        root, rawStore,
                        new com.supplymind.localimport.LocalImportFileStore(root, fileStore, FIXED_CLOCK),
                        timelineStore, new com.supplymind.localimport.LocalImportCsvParser(),
                        FIXED_CLOCK);
        registry.register(new com.supplymind.localimport.SyntheticDemoDataProvider(
                com.supplymind.localimport.SyntheticDemoDataProvider.defaultScenarioItems()));
        registry.register(com.supplymind.dashboard.support.DashboardTestProviders.forType(
                ProviderType.OFFICIAL_WEB, "test-official-web"));
        registry.register(com.supplymind.dashboard.support.DashboardTestProviders.forType(
                ProviderType.MANUAL, "test-manual"));
        registry.register(com.supplymind.dashboard.support.DashboardTestProviders.forType(
                ProviderType.LOCAL_IMPORT, "test-local-import"));
        DashboardService dashboard = new DashboardService(configManagement, query, history, warnings,
                FIXED_CLOCK, manualIntake, localImport, registry);
        return new Harness(root, fileStore, rawStore, timelineStore, validation, publish, dashboard,
                configManagement, activationStore);
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore files,
            RawReceiptStore rawStore,
            TimelineStore timeline,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DashboardService dashboard,
            ConfigManagementService configs,
            ConfigActivationStore activationStore
    ) {
        String manualItemId() {
            com.supplymind.foundation.model.MonitorSeriesItemV1 item =
                    new com.supplymind.foundation.model.MonitorSeriesItemV1(
                            "MAT.MANUAL.TEST.001", "测试手动标的", true, "MANUAL-TEST",
                            ProviderType.MANUAL, AccessMethod.MANUAL, "测试手动来源",
                            com.supplymind.foundation.model.RouteDecision.FALLBACK_MANUAL,
                            "manual route (test)",
                            RECEIVED_AT, null, "EXT-MANUAL-TEST", null, null,
                            "manual-material-normalization-v1",
                            8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                            "CNY", "CNY", "CNY/MT", null);
            addItemDirect(item);
            return item.itemId();
        }

        String importItemId() {
            com.supplymind.foundation.model.MonitorSeriesItemV1 item =
                    new com.supplymind.foundation.model.MonitorSeriesItemV1(
                            "MAT.IMPORT.TEST.001", "测试导入标的", true, "IMPORT-TEST",
                            ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT, "测试导入来源",
                            com.supplymind.foundation.model.RouteDecision.DIRECT_LOCAL_IMPORT, null,
                            RECEIVED_AT, null, "EXT-IMPORT-TEST", null, null,
                            "local-import-material-normalization-v1",
                            8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                            "CNY", "CNY", "CNY/MT", null);
            addItemDirect(item);
            return item.itemId();
        }

        private void addItemDirect(com.supplymind.foundation.model.MonitorSeriesItemV1 item) {
            com.supplymind.foundation.model.MonitorSeriesConfigV1 current = configs.active();
            java.util.List<com.supplymind.foundation.model.MonitorSeriesItemV1> items =
                    new java.util.ArrayList<>(current.items());
            items.add(item);
            activationStore.activate(new com.supplymind.foundation.model.MonitorSeriesConfigV1(
                    current.schemaVersion(), current.configVersion() + 1, current.mode(),
                    RECEIVED_AT, items));
        }
    }
}
