package com.supplymind.backfill;

import com.supplymind.SupplyMindApplication;
import com.supplymind.config.DynamicConfigWorkflowService;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.dashboard.DashboardService;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.manual.ManualMaterialProcessingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real Spring wiring, explicit manual/import gates, persisted task state and restart; zero cloud/network. */
class ManualBackfillLoopIntegrationTest {
    private static final String MANUAL = "MAT.AZ91D.SMM";
    private static final String IMPORT = "MAT.AZ91D.LOOP_IMPORT";
    @TempDir Path temp;

    @Test
    void publishedManualAndBatchImportUpdateOnlyMatchingJobsAndSurviveRestart() throws Exception {
        String manualJob;
        String importJob;
        try (var ctx = open()) {
            var workflow = ctx.getBean(DynamicConfigWorkflowService.class);
            var dashboard = ctx.getBean(DashboardService.class);
            var processing = ctx.getBean(ManualMaterialProcessingService.class);
            manualJob = workflow.createBackfillJob(MANUAL, "2026-10-23", "2026-10-28").jobId();
            assertEquals("AWAITING_MANUAL_INPUT", workflow.runBackfill(manualJob).status());
            var outside = workflow.createBackfillJob(MANUAL, "2026-10-22", "2026-10-22");

            var pending = dashboard.manualPending(MANUAL, "模拟人工数据（非真实市场数据）",
                    "2026-10-23", "25300", "元/吨");
            assertEquals("PENDING", pending.status());
            assertEquals("AWAITING_MANUAL_INPUT", workflow.backfillJob(manualJob).status());
            assertEquals("PUBLISHED", processing.process(pending.runId()).status());
            var partial = workflow.backfillJob(manualJob); // GET only: no run/refresh shortcut in test.
            assertEquals("PARTIAL_SUCCESS", partial.status());
            assertEquals("2026-10-25", partial.currentCheckpoint(), "weekend needs no fabricated observations");
            assertEquals(List.of("2026-10-26:AWAITING_MANUAL_INPUT", "2026-10-27:AWAITING_MANUAL_INPUT",
                    "2026-10-28:AWAITING_MANUAL_INPUT"), partial.failureReasons());
            assertEquals(outside, workflow.backfillJob(outside.jobId()), "range-outside job must not be touched");

            var rejected = dashboard.manualPending(MANUAL, "模拟人工数据（非真实市场数据）",
                    "2026-10-26", "-1", "元/吨");
            assertEquals("REJECTED", processing.process(rejected.runId()).status());
            assertEquals(partial, workflow.backfillJob(manualJob), "rejection cannot trigger completion");
            for (String date : List.of("2026-10-28", "2026-10-26", "2026-10-27")) {
                var input = dashboard.manualPending(MANUAL, "模拟人工数据（非真实市场数据）", date, "26000", "元/吨");
                assertEquals("PUBLISHED", processing.process(input.runId()).status());
            }
            var complete = workflow.backfillJob(manualJob);
            assertEquals("SUCCEEDED", complete.status());
            assertEquals(List.of("2026-10"), complete.completedPeriods());
            assertEquals("2026-10-28", complete.currentCheckpoint());
            assertTrue(complete.failureReasons().isEmpty());
            assertEquals(complete, workflow.runBackfill(manualJob));
            assertEquals(complete, workflow.retryBackfill(manualJob));
            assertEquals(complete, workflow.createBackfillJob(MANUAL, "2026-10-23", "2026-10-28"));

            // Tasks created AFTER publication must also reconcile via both public button workflows.
            var late = workflow.createBackfillJob(MANUAL, "2026-10-27", "2026-10-28");
            assertEquals("SUCCEEDED", workflow.retryBackfill(late.jobId()).status());

            workflow.addItem(JsonV1Codec.mapper().readValue("""
                {"itemId":"MAT.AZ91D.LOOP_IMPORT","displayName":"模拟导入回填","sourceIntent":"模拟非真实市场数据",
                 "providerType":"local_import","accessMethod":"local_import","actualSourceName":"本地文件导入（LocalImport）",
                 "routeDecision":"direct_local_import","externalCode":"AZ91D","sourceFieldKey":"material-field-key",
                 "rateKind":"material","calculationVersion":"arithmetic-mean-v1","calculationScale":2,"displayScale":2,
                 "roundingMode":"HALF_UP","calendarVersion":"weekday-asia-shanghai-v1","currency":"CNY","baseCurrency":"CNY",
                 "unit":"元/吨","materialValidation":{"valueMinExclusive":"0","staleThresholdDays":7,
                 "canonicalSpecCode":"AZ91D","acceptedSpecAliases":[]}}
                """, ConfigV1.AddItemRequest.class));
            importJob = workflow.createBackfillJob(IMPORT, "2026-10-26", "2026-10-27").jobId();
            assertEquals("AWAITING_MANUAL_INPUT", workflow.runBackfill(importJob).status());
            String csv = "schemaVersion,itemId,businessDate,value,unit,currency,actualSourceName,sourceReference,sourceUrl\n"
                    + "1.0," + IMPORT + ",2026-10-26,24000,元/吨,CNY,模拟非真实市场数据,loop-26,\n"
                    + "1.0," + IMPORT + ",2026-10-27,25500,元/吨,CNY,模拟非真实市场数据,loop-27,\n";
            var accepted = dashboard.importPending("loop.csv", csv.getBytes(StandardCharsets.UTF_8));
            assertTrue(accepted.rowErrors().isEmpty());
            assertEquals(2, accepted.acceptedRows().size());
            assertEquals("AWAITING_MANUAL_INPUT", workflow.backfillJob(importJob).status());
            var runIds = accepted.acceptedRows().stream().map(row -> row.runId()).toList();
            assertEquals("PUBLISHED", dashboard.processImportBatch(runIds).status());
            assertEquals("SUCCEEDED", workflow.backfillJob(importJob).status());
            assertEquals(complete, workflow.backfillJob(manualJob), "another item must not change the manual job");
            assertEquals("PUBLISHED", dashboard.processImportBatch(runIds).status(), "repeat is safe");
        }
        try (var restarted = open()) {
            var workflow = restarted.getBean(DynamicConfigWorkflowService.class);
            for (String job : List.of(manualJob, importJob)) {
                assertEquals("SUCCEEDED", workflow.backfillJob(job).status());
                assertTrue(workflow.backfillJob(job).failureReasons().isEmpty());
            }
        }
    }

    private ConfigurableApplicationContext open() {
        return new SpringApplicationBuilder(SupplyMindApplication.class, FixedTime.class)
                .web(WebApplicationType.NONE).run("--spring.main.web-application-type=none",
                        "--supplymind.data-root=" + temp,
                        "--supplymind.current-acquisition.on-startup-enabled=false",
                        "--supplymind.scheduler.guarded-enabled=false", "--supplymind.agent.llm.enabled=false");
    }

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock backfillLoopClock() {
            return Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.ofHours(8));
        }
    }
}
