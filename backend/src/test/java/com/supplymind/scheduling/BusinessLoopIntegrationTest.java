package com.supplymind.scheduling;

import com.supplymind.SupplyMindApplication;
import com.supplymind.config.*;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.foundation.model.*;
import com.supplymind.foundation.storage.*;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.provider.pboc.*;
import com.supplymind.provider.shfe.*;
import com.supplymind.warning.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Production Spring wiring and real providers/services; only transport, clock and LLM are fake. */
class BusinessLoopIntegrationTest {
    static final String GBP = "FX.GBP.CNY.PBOC_MID";
    static final AtomicInteger DAY = new AtomicInteger(10);
    static final AtomicInteger CLOUD_CALLS = new AtomicInteger();
    static boolean changedPayload;
    static boolean failWarning;
    @TempDir Path temp;

    @Test
    void scheduledDynamicTargetsProduceFormalWarningAcknowledgementAndAgentReport() throws Exception {
        DAY.set(10); CLOUD_CALLS.set(0); changedPayload = false; failWarning = false;
        java.util.concurrent.ExecutorService worker = null;
        try (var context = new SpringApplicationBuilder(SupplyMindApplication.class, Ports.class)
                .web(WebApplicationType.NONE).run(
                    "--supplymind.data-root=" + temp.toAbsolutePath(),
                    "--spring.main.web-application-type=none",
                    "--supplymind.current-acquisition.on-startup-enabled=true",
                    "--supplymind.scheduler.guarded-enabled=false",
                    "--supplymind.agent.llm.enabled=false", "--supplymind.agent.llm.api-key=")) {
            worker = context.getBean("currentAcquisitionExecutor", java.util.concurrent.ExecutorService.class);
            var root = context.getBean(DataRoot.class);
            var current = context.getBean(CurrentAcquisitionService.class);
            var configs = context.getBean(ConfigManagementService.class);
            await(current); // ApplicationReady must trigger the real startup acquisition.
            assertEquals("SUCCEEDED", current.status().state(), current.status().message());
            String euroRef = DataPaths.dailyRef(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, YearMonth.of(2026,8));
            byte[] euroBefore = Files.readAllBytes(root.resolveDataRef(euroRef));
            configs.setEnabled(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, false);
            var added = context.getBean(DynamicConfigWorkflowService.class).addItem(new ConfigV1.AddItemRequest(
                    GBP, "英镑", "PBOC", ProviderType.OFFICIAL_WEB.wireValue(),
                    AccessMethod.PUBLIC_OFFICIAL_HTML.wireValue(), MonitorSeriesDefaults.PBOC_SOURCE_NAME,
                    RouteDecision.PRIMARY.wireValue(), null, "GBP", "1英镑对人民币",
                    MonitorSeriesDefaults.PBOC_RATE_KIND, MonitorSeriesDefaults.CALCULATION_VERSION,
                    8,4,"HALF_UP",MonitorSeriesDefaults.CALENDAR_VERSION,"CNY","GBP","CNY/1 GBP",
                    null,null,null));
            assertEquals("SUCCEEDED", added.currentIntake().status(), added.currentIntake().toString());
            DAY.set(11);
            // Invoke the exact production scheduled bean, enabling just this instance without wall-clock waits.
            var guarded = context.getBean(RotationGuardedCollectionService.class);
            new RotationGuardConfiguration.RotationGuardedScheduler(true, context.getBean(Clock.class), guarded)
                    .runGuardedCycle();
            await(current);
            assertEquals("SUCCEEDED", current.status().state(), current.status().message());
            assertArrayEquals(euroBefore, Files.readAllBytes(root.resolveDataRef(euroRef)));
            var history = context.getBean(HistoryQueryService.class);
            assertEquals(2, history.queryDaily(GBP, LocalDate.of(2026,8,10), LocalDate.of(2026,8,11)).rows().size());
            assertEquals(2, history.queryDaily(MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                    LocalDate.of(2026,8,10), LocalDate.of(2026,8,11)).rows().size());
            assertEquals(2, history.queryDaily(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,
                    LocalDate.of(2026,8,10), LocalDate.of(2026,8,11)).rows().size());
            assertTrue(Files.isRegularFile(root.resolveDataRef("processed/aggregate/"+GBP+"/year/2026.csv")));
            List<Path> warningFiles;
            try (var paths = Files.list(root.resolveInternalRelative("warning/2026-08"))) {
                warningFiles = paths.filter(p -> p.toString().endsWith(".json")
                        && !p.toString().endsWith(".manifest.json")).toList();
            }
            var warning = warningFiles.stream().map(p -> {
                try { return com.supplymind.foundation.codec.JsonV1Codec.decodeFile(Files.readAllBytes(p), WarningRecordV1.class); }
                catch (Exception e) { throw new RuntimeException(e); }
            }).filter(w -> w.itemId().equals(GBP)).findFirst().orElseThrow();
            assertFalse(warning.demoRule());
            String warningRef = DataPaths.warningRef(warning.warningMonth(),warning.warningId());
            byte[] warningBefore = Files.readAllBytes(root.resolveDataRef(warningRef));
            var demo = new WarningRecordV1(warning.schemaVersion(), "demo-warning", warning.ruleId(),
                    warning.ruleVersion(), warning.itemId(), warning.grain(), warning.periodStart(),
                    warning.periodEnd(), warning.businessDate(), warning.threshold(), "999.00", warning.baselineValue(),
                    warning.riskLevel(), warning.evidenceRefs(), warning.dataStatus(), warning.evaluatedAt(),
                    warning.inputFingerprint(), true, "隔离演示规则");
            new WarningStore(root,context.getBean(AtomicFileStore.class),context.getBean(Clock.class)).store(demo);
            var ackStore = context.getBean(WarningAckStore.class);
            ackStore.acknowledge(warning,"已核对采购风险",OffsetDateTime.now(context.getBean(Clock.class)));
            assertArrayEquals(warningBefore,Files.readAllBytes(root.resolveDataRef(warningRef)));
            var result = context.getBean(AgentOrchestrator.class).answer(new AgentOrchestrator.AgentQueryInput(
                    "说明预警确认记录",GBP,null,null,null,null,null,"2026-08",null,"FORMAL"));
            assertFalse(result.degraded(),result.degradeReason());
            assertTrue(result.evidencePack().facts().stream().noneMatch(f -> f.value().equals("demo-warning") || f.value().equals("999.00")));
            assertEquals(1,CLOUD_CALLS.get());
            assertNotNull(result.riskProjection());
            assertTrue(result.evidencePack().facts().stream().anyMatch(f ->
                    f.factType().equals("warning.acknowledged") && f.value().equals("ACKNOWLEDGED")));
            assertTrue(new ReportStore(root,context.getBean(AtomicFileStore.class)).read(result.reportRef()).ok());
            assertTrue(new WarningAckStore(root,context.getBean(AtomicFileStore.class),context.getBean(Clock.class))
                    .readVerified(warning).isPresent());
            var historical = context.getBean(AgentOrchestrator.class).answer(new AgentOrchestrator.AgentQueryInput(
                    "分析历史趋势", GBP, "2026-08-10", "2026-08-11", "month", "2026-08", "2026-08", null, null, "FORMAL"));
            var reportStore = context.getBean(ReportStore.class);
            assertTrue(reportStore.read(historical.reportRef()).ok(), "generation-time evidence must read successfully");
            String dailyRef = DataPaths.dailyRef(GBP, YearMonth.of(2026, 8));
            byte[] dailyBytes = Files.readAllBytes(root.resolveDataRef(dailyRef));
            Files.writeString(root.resolveDataRef(dailyRef), "tampered temporary statistics");
            assertFalse(reportStore.read(historical.reportRef()).ok(), "tampering must remain rejected");
            Files.write(root.resolveDataRef(dailyRef), dailyBytes);
            assertTrue(reportStore.read(historical.reportRef()).ok(), "restoring exact original evidence restores readability");
            byte[] historicalBytes = Files.readAllBytes(root.resolveDataRef(historical.reportRef()));
            var stable = businessSnapshot(root);
            run(current);
            assertEquals("SUCCEEDED", current.status().state(), current.status().message());
            assertEquals(stable, businessSnapshot(root), "same payload replay preserves raw, history and warning identity");
            changedPayload = true;
            run(current);
            assertEquals("PARTIAL_SUCCESS", current.status().state(), "PBOC conflict must not be reported as success; SHFE proceeds");
            assertTrue(current.status().message().contains(GBP));
            assertEquals(stable, businessSnapshot(root), "conflict cannot alter existing business files");
            try (var paths = Files.walk(root.resolveInternalRelative("runtime/conflicts/raw"))) {
                assertTrue(paths.anyMatch(Files::isRegularFile));
            }
            changedPayload = false;
            failWarning = true;
            DAY.set(12);
            run(current);
            assertEquals("PARTIAL_SUCCESS", current.status().state(), "warning failure must be visible in the acquisition result");
            assertTrue(current.status().message().contains("未完成：" + GBP));
            var publishedRaw = context.getBean(RawReceiptStore.class).findByBusinessKey(
                    Mode.FORMAL, ProviderType.OFFICIAL_WEB, GBP, "2026-08-12").orElseThrow();
            var publishedState = context.getBean(TimelineStore.class).read(publishedRaw.runId()).current();
            assertEquals(ProcessingStage.PUBLISHED, publishedState.processingStage(),
                    "warning failure cannot undo a legitimate publish");
            assertTrue(publishedState.validationStatus() == ValidationStatus.VERIFIED
                    || publishedState.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE);
            assertEquals(3, history.queryDaily(GBP, LocalDate.of(2026,8,10), LocalDate.of(2026,8,12)).rows().size());
            failWarning = false;
            var afterAppend = reportStore.read(historical.reportRef());
            assertFalse(afterAppend.ok(), "reproduction: normal mutable statistics update invalidates old report binding");
            assertArrayEquals(historicalBytes, Files.readAllBytes(root.resolveDataRef(historical.reportRef())));
            assertTrue(ManifestVerifier.matches(root, dailyRef, root.resolveDataRef(dailyRef),
                    root.resolveDataRef(DataPaths.manifestRef(dailyRef))), "normal append has a valid current manifest");
            System.out.println("HISTORY_REPRO normal-append old-report=" + afterAppend.failureCode());
            String ackRef=DataPaths.warningAckRef(warning.warningMonth(),warning.warningId());
            Files.writeString(root.resolveDataRef(ackRef),"corrupted test acknowledgement");
            assertFalse(context.getBean(ReportStore.class).read(result.reportRef()).ok());
            var invalid = context.getBean(AgentOrchestrator.class).answer(new AgentOrchestrator.AgentQueryInput(
                    "说明预警",GBP,null,null,null,null,null,"2026-08",null,"FORMAL"));
            assertTrue(invalid.evidencePack().facts().stream().noneMatch(f -> f.factType().equals("warning.acknowledged")));
        } finally {
            if (worker != null) {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS),
                        "test must not leave current acquisition running after context close");
            }
        }
    }
    static Map<String,String> businessSnapshot(DataRoot root) throws Exception {
        Map<String,String> result = new TreeMap<>();
        for(String dir : List.of("raw/formal", "staging", "published", "processed", "warning")) {
            Path base = root.resolveInternalRelative(dir);
            if(Files.exists(base)) try(var paths=Files.walk(base)) {
                for(Path file : paths.filter(Files::isRegularFile).toList())
                    result.put(root.toDataRef(file),FileDigest.sha256(file));
            }
        }
        return result;
    }
    static void run(CurrentAcquisitionService service) throws Exception { service.trigger(); await(service); }
    static void await(CurrentAcquisitionService service) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
        while(service.status().state().equals("RUNNING") && System.nanoTime()<deadline) Thread.sleep(20);
        assertNotEquals("RUNNING",service.status().state(),"cycle must finish");
    }
    static String resource(String name) {
        try(var in=BusinessLoopIntegrationTest.class.getResourceAsStream("/contracts/v1/d1-t04-pboc/"+name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    @TestConfiguration
    static class Ports {
        @Bean @Primary WarningRuleManagementService testWarningService(ConfigManagementService configs,
                WarningRuleStore store, FormalWarningEvaluationService evaluator, Clock clock) {
            var real = new WarningRuleManagementService(configs, store, evaluator, clock);
            var spy = org.mockito.Mockito.spy(real);
            org.mockito.Mockito.doAnswer(invocation -> {
                String item = invocation.getArgument(0);
                if(failWarning && GBP.equals(item)) throw new StorageException("test warning write failed");
                return real.evaluateItem(item);
            }).when(spy).evaluateItem(org.mockito.ArgumentMatchers.anyString());
            return spy;
        }
        @Bean @Primary Clock testClock() {
            return new Clock() {
                public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
                public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
                public Instant instant(){return Instant.parse("2026-08-"+DAY.get()+"T04:00:00Z");}
            };
        }
        @Bean @Primary PbocHttpTransport testPbocTransport() {
            return uri -> {
                boolean list=uri.equals(PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI);
                String body=resource(list?"announcement-list-normal.html":"announcement-detail-normal-with-gbp.html");
                if(DAY.get()>=11) body=body.replace("20260810","202608"+DAY.get()).replace("2026-08-10","2026-08-"+DAY.get())
                        .replace("8月10日","8月"+DAY.get()+"日").replace("9.345678901","9.645678901");
                if(changedPayload && !list) body = body + "<!-- changed public response -->";
                return new PbocHttpResponse(uri,200,"text/html; charset=UTF-8",body.getBytes(StandardCharsets.UTF_8));
            };
        }
        @Bean @Primary ShfeHttpTransport testShfeTransport() {
            return uri -> {
                String body=uri.equals(ShfeAdFreePublicDataProvider.CURRENT_TRADING_DAY_URI)
                    ? "{\"currentTradingday\":\"20260812\",\"lastTradingday\":\"202608"+DAY.get()+"\"}"
                    : "{\"o_curinstrument\":[{\"PRODUCTID\":\"ad_f\",\"DELIVERYMONTH\":\"2609\",\"SETTLEMENTPRICE\":\"22970\",\"VOLUME\":\"100\"}]}";
                return new ShfeHttpResponse(uri,200,"application/json",body.getBytes(StandardCharsets.UTF_8));
            };
        }
        @Bean @Primary LLMService.Port testLlm() {
            return request -> {
                CLOUD_CALLS.incrementAndGet();
                var selected=request.facts().stream().filter(f -> f.statement().equals("immutable warning identifier")
                        || f.statement().equals("verified warning acknowledgement status")).toList();
                String template=selected.stream().map(f -> "{{"+f.factId()+"}}").collect(java.util.stream.Collectors.joining("。"));
                String ids=selected.stream().map(f -> "\""+f.factId()+"\"").collect(java.util.stream.Collectors.joining(","));
                return LLMService.LLMResponse.success("{\"answerTemplate\":\""+template+"\",\"usedFactIds\":["+ids+"]}");
            };
        }
    }
}
