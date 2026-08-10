package com.supplymind.localimport;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
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
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.publish.PublishedQueryService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-T05 contract tests (R1+ findings fix): file-level raw-first for CSV and XLSX (the
 * complete original bytes are persisted before any parse, including on every failure),
 * exact row byte spans (quoted newlines, UTF-8 multi-byte, quoted commas, escaped quotes,
 * CRLF), XLSX support with text-cell-only value semantics (no float/double pollution), and
 * the unchanged SyntheticDemo isolation.
 */
class LocalImportIsolationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final String IMP_ADC12 = "IMP.ADC12.001";
    private static final String IMP_AZ91D = "IMP.AZ91D.001";
    private static final String HEADER = "schemaVersion,itemId,businessDate,value,unit,currency,"
            + "actualSourceName,sourceReference,sourceUrl";

    @TempDir
    Path temporaryDirectory;

    @Test
    void localImportProviderProfileAndRegistry() {
        LocalImportDataProvider provider = new LocalImportDataProvider(() -> Set.of(IMP_ADC12, IMP_AZ91D));
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);

        assertEquals("local-import", provider.profile().providerId());
        assertEquals(ProviderType.LOCAL_IMPORT, provider.profile().providerType());
        assertEquals(AccessMethod.LOCAL_IMPORT, provider.profile().accessMethod());
        assertNull(provider.profile().sourceUrl());
        assertEquals(provider, registry.require("local-import"));
        assertTrue(provider.collect(new com.supplymind.provider.ProviderCollectRequest(List.of(IMP_ADC12)))
                        .raws().isEmpty(),
                "LocalImport never fabricates data through the collect port");
    }

    @Test
    void validUtf8CsvImportReachesImmutableRawAndReceivedPending() throws IOException {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0,IMP.ADC12.001,2026-08-10,123.456789012345678,元/吨,CNY,华东某厂报价单,报价单号A-001,\n"
                + "1.0,IMP.AZ91D.001,2026-08-10,24500,元/吨,CNY,西南某厂报价单,\"报价单号,含税\",https://example.test/ref\n";
        LocalImportResult result = harness.service().importFile(csv.getBytes(StandardCharsets.UTF_8));

        assertFalse(result.fileFailed());
        assertEquals(2, result.accepted().size());
        assertTrue(result.rowErrors().isEmpty());
        for (LocalImportResult.RowOutcome outcome : result.accepted()) {
            assertEquals(ProcessingStage.RECEIVED, outcome.processingStage());
            assertEquals(ValidationStatus.PENDING, outcome.validationStatus());
            assertTrue(Files.isRegularFile(harness.root().resolveDataRef(outcome.rawRef())));
            assertTrue(ManifestVerifier.matches(harness.root(), outcome.rawRef(),
                    harness.root().resolveDataRef(outcome.rawRef()),
                    harness.root().resolveDataRef(DataPaths.manifestRef(outcome.rawRef())),
                    List.of(outcome.runId())));
            LifecycleTimelineV1 timeline = harness.timelineStore().read(outcome.runId());
            assertEquals(ProcessingStage.RECEIVED, timeline.current().processingStage());
            assertEquals(ValidationStatus.PENDING, timeline.current().validationStatus());
        }

        RawReceiptV1 adc12 = decodeRaw(harness.root(), result.accepted().get(0).rawRef());
        assertEquals("123.456789012345678", adc12.rawValue(),
                "the raw must preserve the exact decimal string without float/double transit");
        assertEquals(ProviderType.LOCAL_IMPORT, adc12.providerType());
        assertNotNull(adc12.inputAt());
        assertEquals("本地文件导入（LocalImport）", adc12.actualSourceName());
        assertEquals("import-file-", adc12.acquisitionId().substring(0, 12),
                "the row raw must trace to its source import file via acquisitionId");
        String rowPayload = new String(Base64.getDecoder().decode(adc12.payloadBase64()), StandardCharsets.UTF_8);
        assertTrue(rowPayload.contains("123.456789012345678"));
        assertTrue(rowPayload.contains("华东某厂报价单"),
                "the file-declared actual source must be preserved in the immutable row payload");

        assertSourceImportRaw(harness, csv.getBytes(StandardCharsets.UTF_8), adc12.acquisitionId());
    }

    @Test
    void fileLevelRawFirstPreservesSourceEvidenceOnEveryFailure() throws IOException {
        Harness harness = harness();

        byte[] invalidUtf8 = new byte[]{(byte) 0xC3, (byte) 0x28};
        LocalImportResult utf8Failure = harness.service().importFile(invalidUtf8);
        assertTrue(utf8Failure.fileFailed());
        assertImportRawPreserved(harness, invalidUtf8);

        byte[] malformedQuoting = (HEADER + "\n" + "1.0,IMP.ADC12.001,2026-08-10,1,\"unterminated\n")
                .getBytes(StandardCharsets.UTF_8);
        LocalImportResult quotingFailure = harness.service().importFile(malformedQuoting);
        assertTrue(quotingFailure.fileFailed());
        assertImportRawPreserved(harness, malformedQuoting);

        byte[] wrongHeader = "a,b,c,d,e,f,g,h,i\n".getBytes(StandardCharsets.UTF_8);
        LocalImportResult headerFailure = harness.service().importFile(wrongHeader);
        assertTrue(headerFailure.fileFailed());
        assertImportRawPreserved(harness, wrongHeader);

        byte[] bom = ("\uFEFF" + HEADER + "\n").getBytes(StandardCharsets.UTF_8);
        LocalImportResult bomFailure = harness.service().importFile(bom);
        assertTrue(bomFailure.fileFailed());
        assertImportRawPreserved(harness, bom);

        byte[] corruptXlsx = new byte[]{0x50, 0x4B, 0x03, 0x04};
        byte[] corruptXlsxBody = "corrupt-zip-bytes-not-a-workbook".getBytes(StandardCharsets.UTF_8);
        byte[] corruptXlsxBytes = new byte[corruptXlsx.length + corruptXlsxBody.length];
        System.arraycopy(corruptXlsx, 0, corruptXlsxBytes, 0, corruptXlsx.length);
        System.arraycopy(corruptXlsxBody, 0, corruptXlsxBytes, corruptXlsx.length, corruptXlsxBody.length);
        LocalImportResult xlsxFailure = harness.service().importFile(corruptXlsxBytes);
        assertTrue(xlsxFailure.fileFailed());
        assertImportRawPreserved(harness, corruptXlsxBytes);
    }

    @Test
    void quotedNewlineAndUtf8SpansMapToExactOriginalRowBytes() throws IOException {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0,IMP.ADC12.001,2026-08-10,19850.50,元/吨,CNY,\"华东某厂\n跨行报价单\",\"ref,含逗号\",\n";
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        LocalImportResult result = harness.service().importFile(bytes);

        assertFalse(result.fileFailed());
        assertEquals(1, result.accepted().size());
        RawReceiptV1 raw = decodeRaw(harness.root(), result.accepted().get(0).rawRef());
        byte[] rowPayload = Base64.getDecoder().decode(raw.payloadBase64());
        String payloadText = new String(rowPayload, StandardCharsets.UTF_8);
        assertTrue(payloadText.contains("华东某厂\n跨行报价单"),
                "the quoted embedded newline must be inside the row raw span");
        assertTrue(payloadText.contains("\"ref,含逗号\""),
                "quoted comma and quotes must map to the exact original bytes");
        assertTrue(payloadText.contains("19850.50"));
        assertTrue(payloadText.contains("元/吨"),
                "UTF-8 multi-byte characters must be inside the correct byte span");
        String expectedSpan = csv.substring(csv.indexOf('\n') + 1);
        assertEquals(expectedSpan, payloadText,
                "the row payload must be the exact original byte span of the logical record (including the quoted newline)");
    }

    @Test
    void crlfCsvIsValidAndRawKeepsOriginalCrlfBytes() throws IOException {
        Harness harness = harness();
        String crlf = HEADER + "\r\n"
                + "1.0,IMP.ADC12.001,2026-08-10,19850.50,元/吨,CNY,s,ref,\r\n";
        byte[] bytes = crlf.getBytes(StandardCharsets.UTF_8);
        LocalImportResult result = harness.service().importFile(bytes);

        assertFalse(result.fileFailed(), "CRLF must be accepted as a normal record terminator");
        assertEquals(1, result.accepted().size());
        RawReceiptV1 raw = decodeRaw(harness.root(), result.accepted().get(0).rawRef());
        String rowPayload = new String(Base64.getDecoder().decode(raw.payloadBase64()), StandardCharsets.UTF_8);
        assertTrue(rowPayload.contains("\r\n"),
                "the row raw must preserve the original CRLF bytes verbatim");
    }

    @Test
    void validXlsxImportMapsIdenticallyToCsvAndPreservesExactDecimal() throws IOException {
        Harness harness = harness();
        byte[] xlsx = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", "IMP.ADC12.001", "2026-08-10", "123.456789012345678", "元/吨", "CNY", "华东某厂报价单", "ref-xlsx", ""},
                {"1.0", "IMP.AZ91D.001", "2026-08-10", "24500", "元/吨", "CNY", "西南某厂报价单", "ref-xlsx-2", "https://example.test/ref"}
        });
        LocalImportResult result = harness.service().importFile(xlsx);

        assertFalse(result.fileFailed());
        assertEquals(2, result.accepted().size());
        assertTrue(result.rowErrors().isEmpty());
        String sourceImportId = "import-file-" + FileDigest.sha256(xlsx);
        String expectedPayloadSha = FileDigest.sha256(xlsx);
        String expectedImportRef = DataPaths.importRef(sourceImportId);
        for (LocalImportResult.RowOutcome outcome : result.accepted()) {
            RawReceiptV1 raw = decodeRaw(harness.root(), outcome.rawRef());
            assertEquals(sourceImportId, raw.acquisitionId(),
                    "every XLSX item raw must trace to the same immutable source import");
            assertEquals(expectedPayloadSha, raw.payloadSha256(),
                    "the XLSX item raw payload SHA must equal SHA256 of the original full XLSX bytes");
            assertArrayEquals(xlsx, Base64.getDecoder().decode(raw.payloadBase64()),
                    "the XLSX item raw payload must be the ORIGINAL FULL XLSX file bytes");
            assertEquals(ProcessingStage.RECEIVED, harness.timelineStore().read(
                    outcome.runId()).current().processingStage());
        }
        RawReceiptV1 adc12 = decodeRaw(harness.root(), result.accepted().get(0).rawRef());
        assertEquals("123.456789012345678", adc12.rawValue(),
                "XLSX text cells must keep the exact decimal without binary floating point pollution");
        assertEquals("ref-xlsx", adc12.sourceReference());
        RawReceiptV1 az91d = decodeRaw(harness.root(), result.accepted().get(1).rawRef());
        assertEquals("24500", az91d.rawValue());
        assertEquals("https://example.test/ref", az91d.sourceUrl());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(expectedImportRef)));
        assertSourceImportRaw(harness, xlsx, sourceImportId);
    }

    @Test
    void xlsxSameFileReimportIsIdempotentAndDifferentContentKeepsVersions() throws IOException {
        Harness harness = harness();
        byte[] xlsxA = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", "IMP.ADC12.001", "2026-08-10", "19850.50", "元/吨", "CNY", "s", "ref", ""}
        });
        LocalImportResult first = harness.service().importFile(xlsxA);
        LocalImportResult replay = harness.service().importFile(xlsxA);

        assertEquals(LocalImportResult.ImportMode.NEW, first.accepted().get(0).mode());
        assertEquals(LocalImportResult.ImportMode.IDEMPOTENT_REUSE, replay.accepted().get(0).mode());
        assertEquals(first.accepted().get(0).runId(), replay.accepted().get(0).runId());
        assertEquals(1, localImportRawCount(harness.root(), IMP_ADC12));

        byte[] xlsxB = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", "IMP.ADC12.001", "2026-08-10", "20000.00", "元/吨", "CNY", "s", "ref", ""}
        });
        LocalImportResult revision = harness.service().importFile(xlsxB);
        assertEquals(LocalImportResult.ImportMode.NEW, revision.accepted().get(0).mode());
        assertEquals(2, localImportRawCount(harness.root(), IMP_ADC12));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.stagingRef(first.accepted().get(0).runId()))));
    }

    @Test
    void xlsxDifferentDeclaredSourceNameIsANewPendingVersionNotAReplay() throws IOException {
        Harness harness = harness();
        byte[] xlsxA = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", "IMP.ADC12.001", "2026-08-10", "19850.50", "元/吨", "CNY", "SOURCE_A厂报价单", "ref-same", ""}
        });
        byte[] xlsxB = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"1.0", "IMP.ADC12.001", "2026-08-10", "19850.50", "元/吨", "CNY", "SOURCE_B厂报价单", "ref-same", ""}
        });
        LocalImportResult first = harness.service().importFile(xlsxA);
        LocalImportResult second = harness.service().importFile(xlsxB);

        assertEquals(LocalImportResult.ImportMode.NEW, first.accepted().get(0).mode());
        assertEquals(LocalImportResult.ImportMode.NEW, second.accepted().get(0).mode(),
                "the same stable key with a different declared actualSourceName must create a new pending version");
        assertFalse(first.accepted().get(0).runId().equals(second.accepted().get(0).runId()),
                "new business content must get a new run identity");
        assertEquals(2, localImportRawCount(harness.root(), IMP_ADC12));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                        DataPaths.stagingRef(first.accepted().get(0).runId()))),
                "the old version must be preserved");
        LifecycleTimelineV1 oldTimeline = harness.timelineStore().read(first.accepted().get(0).runId());
        assertEquals(ProcessingStage.RECEIVED, oldTimeline.current().processingStage());
        assertEquals(ValidationStatus.PENDING, oldTimeline.current().validationStatus());
        assertTrue(oldTimeline.records().stream().noneMatch(s -> s.validationStatus() == ValidationStatus.CONFLICT),
                "no CONFLICT may be produced for a source-name revision");

        RawReceiptV1 secondRaw = decodeRaw(harness.root(), second.accepted().get(0).rawRef());
        assertArrayEquals(xlsxB, Base64.getDecoder().decode(secondRaw.payloadBase64()),
                "the new version item raw must keep the ORIGINAL_FULL_FILE_BYTES semantics");
        assertEquals(FileDigest.sha256(xlsxB), secondRaw.payloadSha256());

        LocalImportResult replay = harness.service().importFile(xlsxB);
        assertEquals(LocalImportResult.ImportMode.IDEMPOTENT_REUSE, replay.accepted().get(0).mode(),
                "the identical file (same source name and content) must still replay idempotently");
    }

    @Test
    void xlsxNumericValueCellIsRejectedToPreventDoublePollution() throws IOException {
        Harness harness = harness();
        byte[] xlsx = buildXlsxWithNumericValue("IMP.ADC12.001", 19850.5);
        LocalImportResult result = harness.service().importFile(xlsx);

        assertTrue(result.fileFailed(),
                "a numeric Excel cell for value must be rejected instead of converting through double");
    }

    @Test
    void invalidXlsxHeaderAndSchemaFailClosedKeepingSourceRaw() throws IOException {
        Harness harness = harness();
        byte[] wrongHeader = buildXlsx(new String[][]{
                {"a", "b", "c", "d", "e", "f", "g", "h", "i"},
                {"1.0", "IMP.ADC12.001", "2026-08-10", "1", "元/吨", "CNY", "s", "ref", ""}
        });
        LocalImportResult headerFailure = harness.service().importFile(wrongHeader);
        assertTrue(headerFailure.fileFailed());
        assertImportRawPreserved(harness, wrongHeader);

        byte[] badSchema = buildXlsx(new String[][]{
                LocalImportCsvParser.TEMPLATE_HEADER.toArray(new String[0]),
                {"9.9", "IMP.ADC12.001", "2026-08-10", "1", "元/吨", "CNY", "s", "ref", ""}
        });
        LocalImportResult schemaFailure = harness.service().importFile(badSchema);
        assertTrue(schemaFailure.fileFailed(), "an unknown schemaVersion must fail closed");
        assertImportRawPreserved(harness, badSchema);
    }

    @Test
    void rowErrorsAreRecordedPerRowWithoutAffectingOtherRows() {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0,IMP.ADC12.001,2026-08-10,1.5E3,元/吨,CNY,s,ref,\n"
                + "1.0,IMP.ADC12.001,2026-08-10,1,元/吨,CNY,s,ref,\n"
                + "1.0,IMP.UNKNOWN.999,2026-08-10,1,元/吨,CNY,s,ref,\n"
                + "1.0,IMP.ADC12.001,2026-08-10,abc,元/吨,CNY,s,ref,\n";
        LocalImportResult result = harness.service().importFile(csv.getBytes(StandardCharsets.UTF_8));

        assertFalse(result.fileFailed());
        assertEquals(1, result.accepted().size(), "only the fully valid row is accepted");
        assertEquals(3, result.rowErrors().size());
        assertTrue(result.rowErrors().stream().anyMatch(e -> e.reason().equals("VALUE_SCIENTIFIC_NOTATION")));
        assertTrue(result.rowErrors().stream().anyMatch(e -> e.reason().equals("ITEM_NOT_CONFIGURED")));
        assertTrue(result.rowErrors().stream().anyMatch(e -> e.reason().equals("VALUE_NOT_DECIMAL")));
    }

    @Test
    void sameContentReimportIsIdempotentAndDifferentContentKeepsOldVersion() throws IOException {
        Harness harness = harness();
        String csvA = HEADER + "\n" + "1.0,IMP.ADC12.001,2026-08-10,19850.50,元/吨,CNY,s,ref,\n";
        LocalImportResult first = harness.service().importFile(csvA.getBytes(StandardCharsets.UTF_8));
        String csvB = HEADER + "\n" + "1.0,IMP.ADC12.001,2026-08-10,19850.50,元/吨,CNY,s,ref,\n";
        LocalImportResult replay = harness.service().importFile(csvB.getBytes(StandardCharsets.UTF_8));

        assertEquals(LocalImportResult.ImportMode.NEW, first.accepted().get(0).mode());
        assertEquals(LocalImportResult.ImportMode.IDEMPOTENT_REUSE, replay.accepted().get(0).mode());
        assertEquals(first.accepted().get(0).runId(), replay.accepted().get(0).runId());
        assertEquals(1, localImportRawCount(harness.root(), IMP_ADC12),
                "an idempotent re-import must not create duplicate raws");

        String csvC = HEADER + "\n" + "1.0,IMP.ADC12.001,2026-08-10,20000.00,元/吨,CNY,s,ref,\n";
        LocalImportResult revision = harness.service().importFile(csvC.getBytes(StandardCharsets.UTF_8));
        assertEquals(LocalImportResult.ImportMode.NEW, revision.accepted().get(0).mode());
        assertEquals(2, localImportRawCount(harness.root(), IMP_ADC12));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.stagingRef(first.accepted().get(0).runId()))),
                "the old version timeline must be preserved");
    }

    @Test
    void syntheticDemoIsDeterministicAndExplicitlyIsolated() throws IOException {
        Harness harness = harness();
        SyntheticDemoDataProvider synthetic = new SyntheticDemoDataProvider(SyntheticDemoDataProvider.defaultScenarioItems());
        com.supplymind.provider.ProviderCollectOutcome first = synthetic.collect(
                new com.supplymind.provider.ProviderCollectRequest(List.of("DEMO.ADC12.001")));
        com.supplymind.provider.ProviderCollectOutcome second = synthetic.collect(
                new com.supplymind.provider.ProviderCollectRequest(List.of("DEMO.ADC12.001")));

        assertEquals(first, second, "identical seed + scenario version must reproduce identical output");
        assertEquals(1, first.raws().size());
        assertEquals(ProviderType.SYNTHETIC_DEMO, first.raws().get(0).providerType());
        assertTrue(first.raws().get(0).rawRef().contains("demo-"));

        assertFalse(Files.exists(harness.root().resolveInternalRelative("raw/formal/synthetic_demo")),
                "synthetic demo data must never be persisted to the formal raw store");
        assertEquals(0, harness.publishedQuery().findPublished(
                "DEMO.ADC12.001", java.time.LocalDate.parse("2026-08-10")).size(),
                "synthetic data must never appear in the formal business read model");
    }

    @Test
    void formalNoDataNeverFallsBackToSynthetic() {
        Harness harness = harness();
        com.supplymind.provider.DataProviderRegistry registry = new com.supplymind.provider.DataProviderRegistry();
        SyntheticDemoDataProvider synthetic = new SyntheticDemoDataProvider(SyntheticDemoDataProvider.defaultScenarioItems());
        registry.register(synthetic);
        com.supplymind.routing.MaterialRouteResolver resolver = new com.supplymind.routing.MaterialRouteResolver();

        com.supplymind.routing.MaterialRouteDecision decision = resolver.resolve(
                com.supplymind.routing.MaterialRouteConfigV1.of(
                        "MAT.ADC12.SMM", List.of(), List.of(), List.of()),
                registry, (id, profile) -> java.util.Optional.empty(),
                com.supplymind.routing.DataKind.CURRENT,
                OffsetDateTime.parse("2026-08-10T02:00:00+08:00"));

        assertNull(decision.activeProviderId(),
                "formal no-data must be NO_DATA, never an automatic synthetic fallback");
        assertEquals(com.supplymind.routing.RouteAcceptance.ROUTE_UNAVAILABLE, decision.routeAcceptance());
    }

    @Test
    void localImportAndSyntheticIdentitiesAreNeverConfused() {
        LocalImportDataProvider localImport = new LocalImportDataProvider(() -> Set.of(IMP_ADC12));
        SyntheticDemoDataProvider synthetic = new SyntheticDemoDataProvider(
                SyntheticDemoDataProvider.defaultScenarioItems());
        assertFalse(localImport.profile().providerId().equals(synthetic.profile().providerId()));
        assertEquals(ProviderType.LOCAL_IMPORT, localImport.profile().providerType());
        assertEquals(ProviderType.SYNTHETIC_DEMO, synthetic.profile().providerType());
        assertFalse(localImport.profile().actualSourceName().equals(synthetic.profile().actualSourceName()));
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d3-t05 local import " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(localImportConfig());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        LocalImportFileStore importFileStore = new LocalImportFileStore(root, fileStore, CLOCK);
        LocalImportService service = new LocalImportService(
                root, rawStore, importFileStore, timelineStore, new LocalImportCsvParser(), CLOCK);
        PublishedQueryService publishedQuery = new PublishedQueryService(root, timelineStore, CLOCK);
        return new Harness(root, timelineStore, service, publishedQuery);
    }

    private static MonitorSeriesConfigV1 localImportConfig() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, now, List.of(
                new MonitorSeriesItemV1(
                        IMP_ADC12, "ADC12铝合金锭（本地导入）", true, "SMM/供应商", ProviderType.LOCAL_IMPORT,
                        AccessMethod.LOCAL_IMPORT, "本地文件导入（LocalImport）", RouteDecision.DIRECT_LOCAL_IMPORT,
                        null, now, null, "ADC12", "material-field-key", "material",
                        "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                        "CNY", "CNY", "元/吨"),
                new MonitorSeriesItemV1(
                        IMP_AZ91D, "AZ91D镁合金锭（本地导入）", true, "Asian Metal/供应商", ProviderType.LOCAL_IMPORT,
                        AccessMethod.LOCAL_IMPORT, "本地文件导入（LocalImport）", RouteDecision.DIRECT_LOCAL_IMPORT,
                        null, now, null, "AZ91D", "material-field-key", "material",
                        "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                        "CNY", "CNY", "元/吨")));
    }

    private static RawReceiptV1 decodeRaw(DataRoot root, String ref) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(ref)), RawReceiptV1.class);
    }

    private static void assertSourceImportRaw(Harness harness, byte[] originalBytes, String importId)
            throws IOException {
        String importRef = DataPaths.importRef(importId);
        Path importPath = harness.root().resolveDataRef(importRef);
        Path importManifest = harness.root().resolveDataRef(DataPaths.manifestRef(importRef));
        assertTrue(Files.isRegularFile(importPath), "the source import raw must exist");
        assertTrue(Files.isRegularFile(importManifest));
        LocalImportReceiptV1 receipt = JsonV1Codec.decodeFile(
                Files.readAllBytes(importPath), LocalImportReceiptV1.class);
        assertArrayEquals(originalBytes, Base64.getDecoder().decode(receipt.payloadBase64()),
                "the source import raw payload must be the exact original file bytes");
        assertEquals(FileDigest.sha256(originalBytes), receipt.payloadSha256());
        assertEquals(originalBytes.length, receipt.byteLength());
        assertTrue(ManifestVerifier.matches(harness.root(), importRef, importPath, importManifest,
                List.of(importId)));
    }

    private static void assertImportRawPreserved(Harness harness, byte[] originalBytes) throws IOException {
        String importId = "import-file-" + FileDigest.sha256(originalBytes);
        assertSourceImportRaw(harness, originalBytes, importId);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] headerBytes(byte[] file) {
        int end = 0;
        while (end < file.length && file[end] != '\n') {
            end++;
        }
        return Arrays.copyOfRange(file, 0, end + 1);
    }

    private static byte[] buildXlsx(String[][] values) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("import");
            for (int r = 0; r < values.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < values[r].length; c++) {
                    row.createCell(c).setCellValue(values[r][c]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        }
    }

    private static byte[] buildXlsxWithNumericValue(String itemId, double numericValue) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("import");
            Row header = sheet.createRow(0);
            for (int c = 0; c < LocalImportCsvParser.TEMPLATE_HEADER.size(); c++) {
                header.createCell(c).setCellValue(LocalImportCsvParser.TEMPLATE_HEADER.get(c));
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1.0");
            row.createCell(1).setCellValue(itemId);
            row.createCell(2).setCellValue("2026-08-10");
            row.createCell(3).setCellValue(numericValue);
            row.createCell(4).setCellValue("元/吨");
            row.createCell(5).setCellValue("CNY");
            row.createCell(6).setCellValue("s");
            row.createCell(7).setCellValue("ref");
            row.createCell(8).setCellValue("");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                workbook.write(out);
                return out.toByteArray();
            }
        }
    }

    private static int localImportRawCount(DataRoot root, String itemId) throws IOException {
        Path dir = root.resolveInternalRelative("raw/formal/local_import/" + itemId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private record Harness(
            DataRoot root,
            TimelineStore timelineStore,
            LocalImportService service,
            PublishedQueryService publishedQuery
    ) {
    }
}
