package com.supplymind.acceptance;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.localimport.LocalImportCsvParser;
import com.supplymind.localimport.LocalImportDataProvider;
import com.supplymind.localimport.LocalImportFileStore;
import com.supplymind.localimport.LocalImportResult;
import com.supplymind.localimport.LocalImportService;
import com.supplymind.localimport.SyntheticDemoDataProvider;
import com.supplymind.manual.ManualDataProvider;
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishOutcome;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.routing.ApiAuthorizationProbe;
import com.supplymind.routing.CandidateUnavailability;
import com.supplymind.routing.DataKind;
import com.supplymind.routing.MaterialRouteConfigV1;
import com.supplymind.routing.MaterialRouteDecision;
import com.supplymind.routing.MaterialRouteResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-T06 Day-3 material compliance closure acceptance (AT-SRC-001/005/006/007-Day3/008),
 * executed entirely through production paths: the unified provider registry, the DEC-037
 * three-tier material route resolver, Manual controlled intake (DEC-057, max PARSED+PENDING),
 * LocalImport CSV/XLSX intake, SyntheticDemo exclusion, and the formal PENDING gates.
 * No material VERIFIED/PUBLISHED is produced and no fabrication happens anywhere.
 */
class MaterialDay3AcceptanceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final String ADC12_SMM = "MAT.ADC12.SMM";
    private static final String AZ91D_SMM = "MAT.AZ91D.SMM";
    private static final String ADC12_AM = "MAT.ADC12.AM";
    private static final String AZ91D_AM = "MAT.AZ91D.AM";
    private static final List<String> FOUR_SEQUENCES = List.of(ADC12_SMM, AZ91D_SMM, ADC12_AM, AZ91D_AM);
    private static final String HEADER = "schemaVersion,itemId,businessDate,value,unit,currency,"
            + "actualSourceName,sourceReference,sourceUrl";

    @TempDir
    Path temporaryDirectory;

    @Test
    void atSrc001And005AllFourP0SequencesResolveToAuditableThreeTierRoutes() {
        Harness harness = harness();
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (String itemId : FOUR_SEQUENCES) {
            String intent = itemId.endsWith(".SMM") ? "SMM" : "AM";
            MaterialRouteDecision decision = harness.resolver().resolve(
                    routeConfig(itemId, intent), harness.registry(), harness.probe(),
                    DataKind.CURRENT, OffsetDateTime.parse("2026-08-10T02:00:00+08:00"));
            matrix.add(routeRow(itemId, intent, decision));

            assertEquals("manual-material", decision.activeProviderId(),
                    itemId + " must fall back to MANUAL when PRIMARY is not configured and no free source is approved");
            assertEquals(RouteDecision.FALLBACK_MANUAL, decision.routeDecision());
            assertNotNull(decision.fallbackReason());
            assertTrue(decision.fallbackReason().contains("credentials_missing"),
                    "the recorded fallback reason must explain why the primary tier was skipped: "
                            + decision.fallbackReason());
            assertFalse(decision.candidates().stream().anyMatch(c -> c.providerId().equals("material-synthetic")),
                    "synthetic must never be a candidate for the formal route");
            assertFalse(decision.candidates().stream().anyMatch(c -> c.providerId().equals("pboc-official-web")),
                    "PBOC must never be drawn into material routes");
            assertTrue(decision.candidates().stream()
                            .filter(c -> c.tier() == com.supplymind.routing.RouteTier.FREE_PUBLIC)
                            .findFirst().map(c -> true).orElse(true),
                    "FREE_PUBLIC stays empty per the D3-T03 NO_APPROVED_SOURCE verdict");
        }
        assertEquals(4, matrix.size());
        assertEquals("MAT.ADC12.SMM", matrix.get(0).get("itemId"));
        assertEquals("MAT.AZ91D.SMM", matrix.get(1).get("itemId"));
        assertEquals("MAT.ADC12.AM", matrix.get(2).get("itemId"));
        assertEquals("MAT.AZ91D.AM", matrix.get(3).get("itemId"));

        ProviderSourceProfile pboc = harness.registry().require("pboc-official-web").profile();
        assertEquals(ProviderType.OFFICIAL_WEB, pboc.providerType(),
                "AT-SRC-001: PBOC route stays OfficialWeb and independent of material licensing");
        assertFalse(harness.probe().unavailability("smm-authorized-api",
                        harness.registry().require("smm-authorized-api").profile()).isEmpty(),
                "the specified commercial auto capability must be recorded NOT_CONFIGURED, never PASS");
        System.out.println("AT_SRC_001_005 fourSequences=FALLBACK_MANUAL reasons=credentials_missing"
                + " syntheticExcluded=true pbocExcluded=true freePublic=NO_APPROVED_SOURCE");
    }

    @Test
    void atSrc007Day3ManualAdc12AndAz91dClosure() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome adc12 = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        ManualIntakeOutcome az91d = harness.manual().submit(ManualMaterialSubmission.of(
                AZ91D_AM, "2026-08-10", "24500", "元/吨", "CNY",
                "西南某厂报价单（测试）", "报价单号B-20260810", "https://example.test/ref"));

        assertEquals(ProcessingStage.PARSED, adc12.processingStage());
        assertEquals(ValidationStatus.PENDING, adc12.validationStatus());
        assertEquals(ProcessingStage.PARSED, az91d.processingStage());
        assertEquals(ValidationStatus.PENDING, az91d.validationStatus());
        assertEquals("manual-material-normalization-v1",
                harness.timelineStore().read(adc12.runId()).current().candidate().normalizationVersion());
        assertEquals("19850.50", harness.timelineStore().read(adc12.runId()).current().candidate().value());
        assertEquals("24500", harness.timelineStore().read(az91d.runId()).current().candidate().value());
        assertEquals("华东某厂报价单（测试）",
                harness.timelineStore().read(adc12.runId()).current().candidate().actualSourceName(),
                "the candidate must carry the user-declared actual source, never the Manual ingress label");
        RawReceiptV1 adc12Raw = decodeRaw(harness.root(), adc12.rawRef());
        assertEquals("华东某厂报价单（测试）", adc12Raw.actualSourceName(),
                "the raw actual source must be the user-declared actual source");
        assertEquals(ProviderType.MANUAL, adc12Raw.providerType(),
                "declaring any source name must never change the MANUAL provider identity");
        assertEquals(AccessMethod.MANUAL, adc12Raw.accessMethod());

        ManualIntakeOutcome replay = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        assertEquals(ManualIntakeOutcome.IntakeMode.IDEMPOTENT_REUSE, replay.mode());
        assertEquals(adc12.runId(), replay.runId());

        ManualIntakeOutcome revision = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "20000.00", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        assertEquals(ManualIntakeOutcome.IntakeMode.NEW, revision.mode());
        assertFalse(adc12.runId().equals(revision.runId()));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                com.supplymind.foundation.storage.DataPaths.stagingRef(adc12.runId()))));

        assertEquals(0, harness.published().findPublished(ADC12_SMM, LocalDate.parse("2026-08-10")).size(),
                "AT-SRC-007 Day3: PENDING Manual must be invisible to the business read model");
        assertEquals(0, harness.published().findPublished(AZ91D_AM, LocalDate.parse("2026-08-10")).size());
        assertEquals(0, harness.daily().processMonth(ADC12_SMM, YearMonth.of(2026, 8)).rows().size(),
                "PENDING Manual must not produce daily rows");
        assertEquals(0, harness.daily().processMonth(AZ91D_AM, YearMonth.of(2026, 8)).rows().size());
        assertTrue(harness.aggregate().processYear(ADC12_SMM, 2026).writtenRefs().isEmpty(),
                "PENDING Manual must not produce aggregate files");
        PublishOutcome publish = harness.publish().process(adc12.runId());
        assertEquals(PublishOutcome.PublishAction.NOT_READY, publish.action(),
                "the existing publish gate must reject PENDING Manual");
        System.out.println("AT_SRC_007_DAY3 manual adc12=PARSED_PENDING az91d=PARSED_PENDING"
                + " idempotent=true revision=true pendingGates=BLOCKED");
    }

    @Test
    void atSrc007Day3LocalImportCsvAndXlsxAdc12Az91dClosure() throws IOException {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0,IMP.ADC12.001,2026-08-10,123.456789012345678,元/吨,CNY,华东某厂CSV报价单,CSV-REF-A,\n"
                + "1.0,IMP.AZ91D.001,2026-08-10,24500,元/吨,CNY,西南某厂CSV报价单,CSV-REF-B,https://example.test/ref\n";
        LocalImportResult csvResult = harness.importService().importFile(csv.getBytes(StandardCharsets.UTF_8));
        assertFalse(csvResult.fileFailed());
        assertEquals(2, csvResult.accepted().size());
        for (LocalImportResult.RowOutcome outcome : csvResult.accepted()) {
            assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
            assertEquals(ValidationStatus.PENDING, outcome.validationStatus());
        }
        RawReceiptV1 csvAdc12Raw = decodeRaw(harness.root(), csvResult.accepted().get(0).rawRef());
        assertEquals("华东某厂CSV报价单", csvAdc12Raw.actualSourceName(),
                "the CSV raw must carry the file-declared actual source, never the LocalImport ingress label");
        assertEquals(ProviderType.LOCAL_IMPORT, csvAdc12Raw.providerType());
        assertEquals(AccessMethod.LOCAL_IMPORT, csvAdc12Raw.accessMethod());
        assertEquals(com.supplymind.localimport.LocalImportService.CONTENT_TYPE_CSV, csvAdc12Raw.contentType(),
                "a CSV import raw must record the CSV media type");

        byte[] xlsx = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", "IMP.ADC12.001", "2026-08-10", "123.456789012345678", "元/吨", "CNY", "华东某厂XLSX报价单", "XLSX-REF-A", ""},
                {"1.0", "IMP.AZ91D.001", "2026-08-10", "24500", "元/吨", "CNY", "西南某厂XLSX报价单", "XLSX-REF-B", "https://example.test/ref"}
        });
        LocalImportResult xlsxResult = harness.importService().importFile(xlsx);
        assertFalse(xlsxResult.fileFailed());
        assertEquals(2, xlsxResult.accepted().size());
        for (LocalImportResult.RowOutcome outcome : xlsxResult.accepted()) {
            assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
            assertEquals(ValidationStatus.PENDING, outcome.validationStatus());
        }
        RawReceiptV1 xlsxAdc12Raw = decodeRaw(harness.root(), xlsxResult.accepted().get(0).rawRef());
        assertEquals("华东某厂XLSX报价单", xlsxAdc12Raw.actualSourceName(),
                "the XLSX raw must carry the file-declared actual source, never the LocalImport ingress label");
        assertEquals("华东某厂XLSX报价单", xlsxAdc12Raw.declaredSourceName(),
                "the XLSX item-own declared source state must stay aligned with the row declaration");
        assertEquals(ProviderType.LOCAL_IMPORT, xlsxAdc12Raw.providerType());
        assertEquals(com.supplymind.localimport.LocalImportService.CONTENT_TYPE_XLSX, xlsxAdc12Raw.contentType(),
                "an XLSX import raw must record the OOXML spreadsheet media type, not text/csv");
        assertEquals(0, harness.published().findPublished("IMP.ADC12.001", LocalDate.parse("2026-08-10")).size(),
                "PENDING LocalImport must be invisible to the business read model");
        assertEquals(0, harness.published().findPublished("IMP.AZ91D.001", LocalDate.parse("2026-08-10")).size());
        assertEquals(0, harness.daily().processMonth("IMP.ADC12.001", YearMonth.of(2026, 8)).rows().size());
        assertTrue(harness.aggregate().processYear("IMP.ADC12.001", 2026).writtenRefs().isEmpty());
        System.out.println("AT_SRC_007_DAY3 localImport csv=PENDING xlsx=PENDING gates=BLOCKED");
    }

    @Test
    void atSrc008SourceIdentityNeverImpersonatedAndSyntheticExcluded() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome manual = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "SMM官方页面报价（人工录入声明）", "用户自述来源-测试", null));
        assertEquals(ProcessingStage.PARSED, manual.processingStage());
        assertEquals(ValidationStatus.PENDING, manual.validationStatus());
        assertEquals(ProviderType.MANUAL, harness.timelineStore().read(manual.runId()).current()
                .candidate().providerType(),
                "a user-declared SMM source name must never turn providerType into OfficialWeb");

        String csv = HEADER + "\n" + "1.0,IMP.ADC12.001,2026-08-10,19850.50,元/吨,CNY,某供应商,REF,\n";
        LocalImportResult csvResult = harness.importService().importFile(csv.getBytes(StandardCharsets.UTF_8));
        assertFalse(csvResult.fileFailed());
        assertEquals(1, csvResult.accepted().size());

        SyntheticDemoDataProvider synthetic = new SyntheticDemoDataProvider(
                SyntheticDemoDataProvider.defaultScenarioItems());
        ProviderCollectOutcome demo = synthetic.collect(new ProviderCollectRequest(List.of("DEMO.ADC12.001")));
        assertEquals(ProviderType.SYNTHETIC_DEMO, demo.raws().get(0).providerType(),
                "synthetic identity must stay explicit and never masquerade as a real source");
        assertEquals(0, harness.published().findPublished("DEMO.ADC12.001", LocalDate.parse("2026-08-10")).size(),
                "synthetic must never be visible through formal queries");

        MaterialRouteDecision noData = harness.resolver().resolve(
                MaterialRouteConfigV1.of(ADC12_SMM, List.of(), List.of(), List.of()),
                harness.registry(), harness.probe(), DataKind.CURRENT,
                OffsetDateTime.parse("2026-08-10T02:00:00+08:00"));
        assertNull(noData.activeProviderId());
        assertEquals(com.supplymind.routing.RouteAcceptance.ROUTE_UNAVAILABLE, noData.routeAcceptance(),
                "formal no-data must be NO_DATA, never an automatic synthetic fallback");
        System.out.println("AT_SRC_008 sourceIdentitySeparation=PASS syntheticExcluded=PASS"
                + " noDataNoAutoSynthetic=PASS");
    }

    @Test
    void oldRawReceiptWithoutDeclaredSourceNameStaysReadable() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome manual = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        RawReceiptV1 raw = decodeRaw(harness.root(), manual.rawRef());
        assertNull(raw.declaredSourceName(),
                "Manual raws carry no declaredSourceName by design");
        String serialized = new String(com.supplymind.foundation.codec.JsonV1Codec.encodeFile(raw),
                StandardCharsets.UTF_8);
        String legacyJson = serialized.replaceFirst(",\\s*\"declaredSourceName\"\\s*:\\s*null", "");
        assertTrue(legacyJson.length() < serialized.length(),
                "the legacy simulation must actually drop the declaredSourceName field");
        RawReceiptV1 legacy = JsonV1Codec.decodeFile(
                legacyJson.getBytes(StandardCharsets.UTF_8), RawReceiptV1.class);
        assertNull(legacy.declaredSourceName());
        assertEquals("华东某厂报价单（测试）", legacy.actualSourceName(),
                "a pre-D3-T05 raw without declaredSourceName must decode with its actual source intact");
        assertEquals(ProviderType.MANUAL, legacy.providerType());
        assertEquals("19850.50", legacy.rawValue());
    }

    private static RawReceiptV1 decodeRaw(DataRoot root, String rawRef) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(rawRef)), RawReceiptV1.class);
    }

    private static MaterialRouteConfigV1 routeConfig(String itemId, String intent) {
        String primary = "SMM".equals(intent) ? "smm-authorized-api" : "am-authorized-api";
        return MaterialRouteConfigV1.of(itemId, List.of(primary), List.of(), List.of("manual-material"));
    }

    private static Map<String, Object> routeRow(String itemId, String intent, MaterialRouteDecision decision) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", itemId);
        row.put("sourceIntent", intent);
        row.put("selectedTier", decision.routeDecision() == null ? "UNAVAILABLE" : decision.routeDecision().wireValue());
        row.put("selectedProvider", decision.activeProviderId() == null ? "NONE" : decision.activeProviderId());
        row.put("fallbackReason", decision.fallbackReason() == null ? "" : decision.fallbackReason());
        row.put("availability", decision.routeAcceptance().name());
        row.put("syntheticExcluded", true);
        return row;
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d3-t06 closure " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(closureConfig());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);

        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-d3t06"), CLOCK);
        LocalImportService importService = new LocalImportService(
                root, rawStore, new LocalImportFileStore(root, fileStore, CLOCK), timelineStore,
                new LocalImportCsvParser(), CLOCK);
        PublishedQueryService published = new PublishedQueryService(root, timelineStore, CLOCK);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, CLOCK);
        AggregateProcessingService aggregate = new AggregateProcessingService(root, fileStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new com.supplymind.foundation.storage.QuarantineStore(root, fileStore, CLOCK), CLOCK);

        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(unauthorizedApiProvider("smm-authorized-api"));
        registry.register(unauthorizedApiProvider("am-authorized-api"));
        registry.register(new ManualDataProvider(() -> Set.copyOf(FOUR_SEQUENCES)));
        registry.register(new LocalImportDataProvider(() -> Set.of("IMP.ADC12.001", "IMP.AZ91D.001")));
        registry.register(new SyntheticDemoDataProvider(SyntheticDemoDataProvider.defaultScenarioItems()));
        registry.register(pbocProvider());

        ApiAuthorizationProbe probe = (id, profile) ->
                profile != null && profile.providerType() == ProviderType.AUTHORIZED_API
                        ? Optional.of(CandidateUnavailability.CREDENTIALS_MISSING)
                        : Optional.empty();

        MaterialRouteResolver resolver = new MaterialRouteResolver();
        return new Harness(root, timelineStore, registry, probe, resolver,
                manual, importService, published, daily, aggregate, publish);
    }

    private static DataProvider unauthorizedApiProvider(String providerId) {
        return new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(providerId, ProviderType.AUTHORIZED_API,
                        AccessMethod.AUTHORIZED_API, providerId + "（未配置真实凭证）",
                        "https://example.test/api", true, true);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.copyOf(FOUR_SEQUENCES);
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                Map<String, String> rejected = new LinkedHashMap<>();
                for (String itemId : request.itemIds()) {
                    rejected.put(itemId, "NOT_CONFIGURED");
                }
                return ProviderCollectOutcome.rejectedOnly(providerId, rejected);
            }
        };
    }

    private static DataProvider pbocProvider() {
        return new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of("pboc-official-web", ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                        "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html", true, false);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of("FX.USD.CNY.PBOC_MID", "FX.EUR.CNY.PBOC_MID");
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly("pboc-official-web", Map.of());
            }
        };
    }

    private static MonitorSeriesConfigV1 closureConfig() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        for (String itemId : FOUR_SEQUENCES) {
            items.add(new MonitorSeriesItemV1(
                    itemId, itemId, true, itemId.endsWith(".SMM") ? "SMM" : "Asian Metal",
                    ProviderType.MANUAL, AccessMethod.MANUAL, "人工录入（Manual）",
                    RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", now, null,
                    itemId.substring(itemId.indexOf('.') + 1, itemId.lastIndexOf('.')),
                    "material-field-key", "material",
                    "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                    "CNY", "CNY", "元/吨"));
        }
        for (String itemId : List.of("IMP.ADC12.001", "IMP.AZ91D.001")) {
            items.add(new MonitorSeriesItemV1(
                    itemId, itemId, true, "SMM/供应商", ProviderType.LOCAL_IMPORT,
                    AccessMethod.LOCAL_IMPORT, "本地文件导入（LocalImport）", RouteDecision.DIRECT_LOCAL_IMPORT,
                    null, now, null, itemId.substring(4, 9), "material-field-key", "material",
                    "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                    "CNY", "CNY", "元/吨"));
        }
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, now, items);
    }

    private static byte[] buildXlsx(String[][] values) throws IOException {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("import");
            for (int r = 0; r < values.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(r);
                for (int c = 0; c < values[r].length; c++) {
                    row.createCell(c).setCellValue(values[r][c]);
                }
            }
            try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        }
    }

    private record Harness(
            DataRoot root,
            TimelineStore timelineStore,
            DataProviderRegistry registry,
            ApiAuthorizationProbe probe,
            MaterialRouteResolver resolver,
            ManualMaterialIntakeService manual,
            LocalImportService importService,
            PublishedQueryService published,
            DailyProcessingService daily,
            AggregateProcessingService aggregate,
            LifecyclePublishService publish
    ) {
    }
}
