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
    static final AtomicInteger SHFE_CALLS = new AtomicInteger();
    static boolean changedPayload;
    static boolean failWarning;
    @TempDir Path temp;

    @Test
    void scheduledDynamicTargetsProduceFormalWarningAcknowledgementAndAgentReport() throws Exception {
        DAY.set(10); CLOUD_CALLS.set(0); SHFE_CALLS.set(0); changedPayload = false; failWarning = false;
        // Establish the TEMP fixture before Spring starts; do not race ready-event listeners.
        var fixtureRoot=DataRoot.forTest(temp);
        fixtureRoot.createIfAbsentAndRequireWritable();
        var fixtureClock=Clock.fixed(Instant.parse("2026-08-10T04:00:00Z"),ZoneOffset.ofHours(8));
        var initial=(com.fasterxml.jackson.databind.node.ObjectNode)com.supplymind.foundation.codec.JsonV1Codec.mapper()
                .valueToTree(MonitorSeriesDefaults.initialDay3(OffsetDateTime.now(fixtureClock)));
        for(var item:initial.path("items"))if(item.path("itemId").asText().equals(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID))
            ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("enabled",false);
        new ConfigActivationStore(fixtureRoot,new AtomicFileStore(fixtureRoot,new DirtyMarkerCodec()),fixtureClock)
                .activate(com.supplymind.foundation.codec.JsonV1Codec.mapper().treeToValue(initial,MonitorSeriesConfigV1.class));
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
            assertFalse(configs.active().items().stream().filter(i->i.itemId().equals(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID)).findFirst().orElseThrow().enabled());
            await(current); // ApplicationReady must trigger the real startup acquisition.
            assertEquals("SUCCEEDED", current.status().state(), current.status().message());
            String euroRef = DataPaths.dailyRef(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, YearMonth.of(2026,8));
            byte[] euroBefore = Files.readAllBytes(root.resolveDataRef(euroRef));
            runShfeScheduledPhase(context,configs,current);
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
            configs.setEnabled(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,false);
            // Keep each first-publish fixture phase within the unchanged 25s assertion; no fake executor or shortened chain.
            // Invoke the exact production scheduled bean, enabling just this instance without wall-clock waits.
            var guarded = context.getBean(RotationGuardedCollectionService.class);
            new RotationGuardConfiguration.RotationGuardedScheduler(true, context.getBean(Clock.class), guarded)
                    .runGuardedCycle();
            await(current);
            assertEquals("SUCCEEDED", current.status().state(), current.status().message());
            runShfeScheduledPhase(context,configs,current);
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
            var oldProjection = (com.fasterxml.jackson.databind.node.ObjectNode)com.supplymind.foundation.codec.JsonV1Codec.mapper().valueToTree(historical.report());
            oldProjection.put("reportId", "legacy-history-report");
            var legacyPack=(com.fasterxml.jackson.databind.node.ObjectNode)oldProjection.get("evidencePack");
            legacyPack.put("schemaVersion","AGENT-EVIDENCE-SCHEMA-V1");
            for(var fact:legacyPack.get("facts"))((com.fasterxml.jackson.databind.node.ObjectNode)fact).remove("rowEvidenceId");
            var strictEntries=new com.supplymind.agent.evidence.EvidenceRefVerifier(root).verifyAllWithAuthoritativeLineage(historical.evidencePack().evidenceRefs().stream().map(e->e.ref()).toList());
            assertTrue(strictEntries.stream().allMatch(e->e.status()==com.supplymind.agent.evidence.EvidenceStatus.VERIFIED),"V1 compatibility fixture requires genuinely scalar-verifiable files");
            legacyPack.set("evidenceRefs",com.supplymind.foundation.codec.JsonV1Codec.mapper().valueToTree(strictEntries));
            var legacyReport=com.supplymind.foundation.codec.JsonV1Codec.mapper().treeToValue(oldProjection,com.supplymind.agent.report.AgentReportV1.class);
            String legacyRef = reportStore.store(legacyReport);
            assertTrue(reportStore.read(legacyRef).ok());
            oldProjection.put("reportId","legacy-v2-history-report");
            String v2Ref=reportStore.storeSnapshot(com.supplymind.foundation.codec.JsonV1Codec.mapper().treeToValue(oldProjection,com.supplymind.agent.report.AgentReportV1.class),()->{});
            assertTrue(reportStore.read(v2Ref).ok());
            var snapshot = com.supplymind.foundation.codec.JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(historical.reportRef())),com.supplymind.agent.report.ReportSnapshotV3.class);
            assertEquals("AGENT-REPORT-V3",snapshot.schemaVersion());
            String dailyRef = DataPaths.dailyRef(GBP, YearMonth.of(2026, 8));
            byte[] dailyBytes = Files.readAllBytes(root.resolveDataRef(dailyRef));
            Files.writeString(root.resolveDataRef(dailyRef), "tampered temporary statistics");
            assertFalse(reportStore.read(legacyRef).ok(), "V1 must still reject current evidence tampering");
            assertTrue(reportStore.read(historical.reportRef()).ok(), "V2 reads original snapshot, not mutated current file");
            Files.write(root.resolveDataRef(dailyRef), dailyBytes);
            assertTrue(reportStore.read(historical.reportRef()).ok(), "restoring exact original evidence restores readability");
            byte[] historicalBytes = Files.readAllBytes(root.resolveDataRef(historical.reportRef()));
            var stable = businessSnapshot(root);
            run(current);
            assertEquals("SUCCEEDED", current.status().state(), current.status().message());
            assertEquals(stable, businessSnapshot(root), "same payload replay preserves raw, history and warning identity");
            changedPayload = true;
            int shfeBeforeConflict=SHFE_CALLS.get();
            run(current);
            assertEquals("PARTIAL_SUCCESS", current.status().state(), "PBOC conflict must not be reported as success; SHFE proceeds");
            assertTrue(current.status().message().contains(GBP));
            assertTrue(SHFE_CALLS.get()>shfeBeforeConflict,"SHFE is actually attempted after independent PBOC conflict");
            assertEquals(stable, businessSnapshot(root), "conflict cannot alter existing business files");
            try (var paths = Files.walk(root.resolveInternalRelative("runtime/conflicts/raw"))) {
                assertTrue(paths.anyMatch(Files::isRegularFile));
            }
            changedPayload = false;
            failWarning = true;
            DAY.set(12);
            configs.setEnabled(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,false);
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
            assertTrue(afterAppend.ok(), "V3 generation-time snapshot survives normal append");
            assertTrue(reportStore.read(v2Ref).ok(),"Old V2 also retains its generation-time snapshot");
            assertFalse(reportStore.read(legacyRef).ok(), "V1 must retain strict drift rejection");
            assertArrayEquals(historicalBytes, Files.readAllBytes(root.resolveDataRef(historical.reportRef())));
            assertTrue(ManifestVerifier.matches(root, dailyRef, root.resolveDataRef(dailyRef),
                    root.resolveDataRef(DataPaths.manifestRef(dailyRef))), "normal append has a valid current manifest");
            System.out.println("HISTORY_REPRO normal-append old-report=" + afterAppend.failureCode());
            String ackRef=DataPaths.warningAckRef(warning.warningMonth(),warning.warningId());
            Files.writeString(root.resolveDataRef(ackRef),"corrupted test acknowledgement");
            assertTrue(context.getBean(ReportStore.class).read(result.reportRef()).ok(), "later ack damage cannot rewrite a captured historical fact");
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
    static void runShfeScheduledPhase(org.springframework.context.ConfigurableApplicationContext context,ConfigManagementService configs,CurrentAcquisitionService current) throws Exception {
        var saved=configs.active().items().stream().filter(i->i.providerType()==ProviderType.OFFICIAL_WEB).toList();
        for(var item:saved)if(item.enabled())configs.setEnabled(item.itemId(),false);
        configs.setEnabled(MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,true);
        int before=SHFE_CALLS.get();
        new RotationGuardConfiguration.RotationGuardedScheduler(true,context.getBean(Clock.class),context.getBean(RotationGuardedCollectionService.class)).runGuardedCycle();
        await(current);assertEquals("SUCCEEDED",current.status().state(),current.status().message());
        assertTrue(SHFE_CALLS.get()>before,"real scheduled SHFE transport must execute");
        for(var item:saved)if(item.enabled())configs.setEnabled(item.itemId(),true);
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
        if(service.status().state().equals("RUNNING")) {
            Thread.getAllStackTraces().forEach((thread,stack)->{if(thread.getName().contains("current-acquisition")){
                System.err.println("CYCLE_TIMEOUT_DIAGNOSTIC thread="+thread.getName()+" state="+thread.getState());
                for(var frame:stack)System.err.println("  at "+frame);
            }});
        }
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
                SHFE_CALLS.incrementAndGet();
                String body=uri.equals(ShfeAdFreePublicDataProvider.CURRENT_TRADING_DAY_URI)
                    ? "{\"currentTradingday\":\"20260812\",\"lastTradingday\":\"202608"+DAY.get()+"\"}"
                    : "{\"o_curinstrument\":[{\"PRODUCTID\":\"ad_f\",\"DELIVERYMONTH\":\"2609\",\"SETTLEMENTPRICE\":\"22970\",\"VOLUME\":\"100\"}]}";
                return new ShfeHttpResponse(uri,200,"application/json",body.getBytes(StandardCharsets.UTF_8));
            };
        }
        @Bean @Primary LLMService.Port testLlm() {
            return request -> {
                CLOUD_CALLS.incrementAndGet();
                var ack=request.facts().stream().filter(f->f.statement().equals("verified warning acknowledgement status")).findFirst();
                var id=request.facts().stream().filter(f->f.statement().equals("immutable warning identifier")).findFirst();
                var latest=request.facts().stream().filter(f->f.statement().equals("latest published value")).findFirst();
                String kind;String text;List<String> ids;
                if(ack.isPresent()){kind="WARNING";text=ack.get().value().equals("ACKNOWLEDGED")?"正式预警已确认，确认不代表风险消失。":"正式预警待处理。";ids=List.of(ack.get().factId());}
                else if(latest.isPresent()){kind="OVERVIEW";text="已有可追溯的已发布数据。";ids=List.of(latest.get().factId());}
                else if(id.isPresent()){kind="WARNING";text="预警记录可追溯，确认信息尚不可验证。";ids=List.of(id.get().factId());}
                else return LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,"no_fixture_facts");
                return LLMService.LLMResponse.success(com.supplymind.foundation.codec.JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind",kind,"text",text,"factIds",ids)),"recommendations",List.of())));

            };
        }
    }
}
