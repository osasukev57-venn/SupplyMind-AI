package com.supplymind.publish;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
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
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.localimport.LocalImportCsvParser;
import com.supplymind.localimport.LocalImportFileStore;
import com.supplymind.localimport.LocalImportResult;
import com.supplymind.localimport.LocalImportService;
import com.supplymind.manual.ManualIntakeOutcome;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.manual.ManualMaterialNormalizer;
import com.supplymind.manual.ManualMaterialSubmission;
import com.supplymind.manual.OperatorContext;
import com.supplymind.validation.LifecycleValidationService;
import com.supplymind.validation.MaterialCandidateValidator;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4-T02 unified publication gate for materials: only VALIDATED+VERIFIED-class material runs
 * validated with the official material-basic-validation-v2 may be published; v1 material
 * results, PENDING/REJECTED/CONFLICT runs and DEMO-mode Synthetic runs are never published;
 * Manual/LocalImport/FreePublic share the same gate (no bypass); PublishedQueryService keeps
 * exposing only PUBLISHED+VERIFIED-class records. PBOC behavior is unchanged (PublishGateTest).
 */
class MaterialPublishGateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-10T02:00:00+08:00");
    private static final OffsetDateTime LATE = OffsetDateTime.parse("2026-08-10T12:00:00+08:00");
    private static final OffsetDateTime MID = OffsetDateTime.parse("2026-08-10T08:00:00+08:00");
    private static final String ADC12_SMM = "MAT.ADC12.SMM";
    private static final String IMP_ADC12 = "IMP.ADC12.001";
    private static final String FREE_ADC12 = "FREE.ADC12.001";
    private static final String DEMO_ADC12 = "DEMO.ADC12.001";
    private static final String HEADER = "schemaVersion,itemId,businessDate,value,unit,currency,"
            + "actualSourceName,sourceReference,sourceUrl";

    @TempDir
    Path temporaryDirectory;

    @Test
    void manualMaterialV2ValidatedIsPublishedAndVisibleToPublishedQuery() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(intake.runId());

        PublishOutcome outcome = harness.publish().process(intake.runId());
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, outcome.action());
        assertEquals(ProcessingStage.PUBLISHED, outcome.processingStage());
        assertTrue(outcome.publishRef().endsWith("#recordVersion=4"));
        LifecycleTimelineV1 timeline = harness.timelineStore().read(intake.runId());
        assertNotNull(timeline.current().publishedAt());
        assertEquals(4, timeline.current().recordVersion());

        assertEquals(1, harness.publishedQuery().findPublished(ADC12_SMM, LocalDate.parse("2026-08-10")).size(),
                "a published material must be visible through the business read model");
    }

    @Test
    void localImportMaterialV2ValidatedIsPublishedThroughTheSameGate() throws IOException {
        Harness harness = harness();
        String csv = HEADER + "\n"
                + "1.0," + IMP_ADC12 + ",2026-08-10,123.456789012345678,元/吨,CNY,华东某厂CSV报价单,CSV-REF-A,\n";
        LocalImportResult importResult = harness.importService().importFile(csv.getBytes(StandardCharsets.UTF_8));
        assertFalse(importResult.fileFailed());
        String runId = importResult.accepted().get(0).runId();
        harness.validation().process(runId);

        PublishOutcome outcome = harness.publish().process(runId);
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, outcome.action());
        assertEquals(1, harness.publishedQuery().findPublished(IMP_ADC12, LocalDate.parse("2026-08-10")).size());
    }

    @Test
    void freePublicMaterialV2ValidatedIsPublishedThroughTheSameGate() throws IOException {
        Harness harness = harness();
        String runId = "free-adc12-pub-001";
        byte[] payload = JsonV1Codec.encodeFile(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19900", "元/吨", "CNY",
                "SMM官网页面（声明）", "free-ref", null));
        RawReceiptV1 raw = new RawReceiptV1(
                "1.0", RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.FREE_PUBLIC, FREE_ADC12, NOW, runId),
                "free-acq-" + runId, runId, Mode.FORMAL,
                ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB, 1,
                "SMM官网页面（声明）", "https://example.test/source", "free-ref", FREE_ADC12,
                "2026-08-10", "2026-08-10", null, null, NOW, null,
                "19900", "元/吨", "CNY", null, 200, "text/html", "base64",
                Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, NOW,
                com.supplymind.foundation.storage.DataPaths.acquisitionRef("free-acq-" + runId), null);
        new com.supplymind.foundation.storage.RawAcquisitionStore(harness.root(),
                new AtomicFileStore(harness.root(), new DirtyMarkerCodec()), CLOCK)
                .store(com.supplymind.foundation.model.DomainFixtures.acquisitionFor(raw));
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(runId, raw.rawRef(), raw.receivedAt());
        harness.validation().process(runId);

        PublishOutcome outcome = harness.publish().process(runId);
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, outcome.action());
        assertEquals(1, harness.publishedQuery().findPublished(FREE_ADC12, LocalDate.parse("2026-08-10")).size());
    }

    @Test
    void materialWithPbocValidationVersionIsNeverPublished() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        appendValidatedSnapshot(harness, intake.runId(), "pboc-basic-validation-v1");
        PublishOutcome outcome = harness.publish().process(intake.runId());
        assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action(),
                "a material record declaring the PBOC validation version must be blocked");
        assertEquals(3, harness.timelineStore().read(intake.runId()).current().recordVersion());
    }

    @Test
    void pbocWithMaterialValidationVersionIsNeverPublished() throws IOException {
        Harness harness = harness();
        String runId = "pboc-forged-material-001";
        RawReceiptV1 raw = pbocRaw(runId);
        ingestHttp(harness, raw);
        appendForgedValidatedSnapshot(harness, runId, "material-basic-validation-v2");

        PublishOutcome outcome = harness.publish().process(runId);
        assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action(),
                "a PBOC record declaring the material validation version must be blocked");
        assertEquals(3, harness.timelineStore().read(runId).current().recordVersion());
    }

    @Test
    void pbocWithPbocValidationVersionIsPublished() throws IOException {
        Harness harness = harness();
        String runId = "pboc-legit-001";
        RawReceiptV1 raw = pbocRaw(runId);
        ingestHttp(harness, raw);
        appendForgedValidatedSnapshot(harness, runId, "pboc-basic-validation-v1");

        PublishOutcome outcome = harness.publish().process(runId);
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, outcome.action(),
                "the PBOC item must keep publishing with its own frozen validation version");
        assertEquals(4, harness.timelineStore().read(runId).current().recordVersion());
    }

    @Test
    void v1ValidatedMaterialIsNeverPublished() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        appendValidatedSnapshot(harness, intake.runId(), MaterialCandidateValidator.VALIDATION_VERSION);

        PublishOutcome outcome = harness.publish().process(intake.runId());
        assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action(),
                "material-basic-validation-v1 results are history-only and must never be newly published");
        assertEquals(3, harness.timelineStore().read(intake.runId()).current().recordVersion());
        assertEquals(0, harness.publishedQuery().findPublished(ADC12_SMM, LocalDate.parse("2026-08-10")).size());
    }

    @Test
    void unknownAndFutureValidationVersionsAreNeverPublishedEvenIfSnapshotsClaimVerified() throws IOException {
        for (String forgedVersion : List.of(
                "material-basic-validation-v3", "unknown-version", "random-version")) {
            Harness harness = harness();
            ManualIntakeOutcome intake = harness.manual().submit(ManualMaterialSubmission.of(
                    ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                    "华东某厂报价单（测试）", "报价单号A-20260810", null));
            appendValidatedSnapshot(harness, intake.runId(), forgedVersion);
            PublishOutcome outcome = harness.publish().process(intake.runId());
            assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action(),
                    "only the currently formal material validation version may be published: " + forgedVersion);
            assertEquals(3, harness.timelineStore().read(intake.runId()).current().recordVersion(),
                    "a forged VALIDATED+VERIFIED snapshot with an unknown version must never advance to PUBLISHED");
        }
    }

    @Test
    void pendingRejectedAndConflictMaterialAreNeverPublished() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome pending = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        assertEquals(PublishOutcome.PublishAction.NOT_READY, harness.publish().process(pending.runId()).action(),
                "PENDING must stay unpublished");

        ManualIntakeOutcome valid = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(valid.runId());
        assertEquals(PublishOutcome.PublishAction.PUBLISHED, harness.publish().process(valid.runId()).action());

        ManualIntakeOutcome rejected = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/公斤", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(rejected.runId());
        assertEquals(PublishOutcome.PublishAction.QUARANTINED, harness.publish().process(rejected.runId()).action(),
                "a REJECTED material run must be quarantined, never published");

        ManualIntakeOutcome conflict = harness.manual().submit(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19900", "元/吨", "CNY",
                "华东某厂报价单（测试）", "报价单号A-20260810", null));
        harness.validation().process(conflict.runId());
        assertEquals(PublishOutcome.PublishAction.QUARANTINED, harness.publish().process(conflict.runId()).action());
        assertEquals(1, harness.publishedQuery().findPublished(ADC12_SMM, LocalDate.parse("2026-08-10")).size(),
                "only the valid observation is published; rejected/conflict runs never enter the read model");
    }

    @Test
    void syntheticDemoModeIsNeverPublishedEvenIfSnapshotsClaimVerified() throws IOException {
        Harness harness = harness();
        String runId = "synthetic-pub-001";
        byte[] payload = JsonV1Codec.encodeFile(ManualMaterialSubmission.of(
                ADC12_SMM, "2026-08-10", "19850.50", "元/吨", "CNY",
                "SyntheticDemo（演示）", "fixture-demo-001", null));
        RawReceiptV1 raw = new RawReceiptV1(
                "1.0", RawReceiptV1.deriveRawRef(Mode.DEMO, ProviderType.SYNTHETIC_DEMO, DEMO_ADC12, NOW, runId),
                "demo-acq-" + runId, runId, Mode.DEMO,
                ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO, 1,
                "SyntheticDemo（演示）", null, "fixture-demo-001", DEMO_ADC12,
                "2026-08-10", "2026-08-10", null, null, NOW, null,
                "19850.50", "元/吨", "CNY", null, null, "application/json", "base64",
                Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, NOW, null, null);
        writeRawDirectly(harness, raw);
        harness.timelineStore().createInitial(runId, raw.rawRef(), raw.receivedAt());
        CandidateV1 candidate = new CandidateV1(raw.itemId(), raw.sourceBusinessDate(), raw.rawValue(),
                raw.rawCurrency(), raw.rawUnit(), raw.providerType(), raw.actualSourceName(),
                raw.accessMethod(), "synthetic-demo-test-v1");
        OffsetDateTime at = NOW.plusMinutes(1);
        harness.timelineStore().append(runId, new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null, null, null, null, null, at));
        harness.timelineStore().append(runId, new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate, null,
                "material-basic-validation-v2", at, null, null, at));

        PublishOutcome outcome = harness.publish().process(runId);
        assertEquals(PublishOutcome.PublishAction.NOT_READY, outcome.action(),
                "a DEMO-mode synthetic run must never enter the formal publish chain");
        assertEquals(3, harness.timelineStore().read(runId).current().recordVersion());
    }

    private static void appendValidatedSnapshot(Harness harness, String runId, String validationVersion)
            throws IOException {
        LifecycleTimelineV1 timeline = harness.timelineStore().read(runId);
        CandidateV1 candidate = timeline.current().candidate();
        harness.timelineStore().append(runId, new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate, null,
                validationVersion, LATE, null, null, LATE));
    }

    private static void appendForgedValidatedSnapshot(Harness harness, String runId, String validationVersion)
            throws IOException {
        LifecycleTimelineV1 timeline = harness.timelineStore().read(runId);
        CandidateV1 candidate = new com.supplymind.validation.PbocCandidateStandardizer()
                .standardize(JsonV1Codec.decodeFile(
                        Files.readAllBytes(harness.root().resolveDataRef(timeline.rawRef())), RawReceiptV1.class))
                .candidate();
        harness.timelineStore().append(runId, new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null, null, null, null, null, MID));
        harness.timelineStore().append(runId, new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate, null,
                validationVersion, MID, null, null, MID));
    }

    private static RawReceiptV1 pbocRaw(String runId) {
        byte[] payload = "{\"fixture\":\"pboc publish gate\",\"usd\":\"7.0000\"}"
                .getBytes(StandardCharsets.UTF_8);
        OffsetDateTime receivedAt = NOW;
        String acquisitionId = "pboc-acq-" + runId;
        return new RawReceiptV1(
                "1.0", RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.OFFICIAL_WEB,
                        "FX.USD.CNY.PBOC_MID", receivedAt, runId),
                acquisitionId, runId, Mode.FORMAL,
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, 1,
                "中国人民银行官网（授权中国外汇交易中心公布）", "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html",
                "pboc-ref-" + runId, "FX.USD.CNY.PBOC_MID",
                "2026-08-10", "2026-08-10", null, receivedAt, receivedAt, null,
                "7.0000", "CNY/1 USD", "CNY", null, 200, "text/html; charset=UTF-8", "base64",
                Base64.getEncoder().encodeToString(payload),
                com.supplymind.foundation.storage.FileDigest.sha256(payload),
                null, receivedAt,
                com.supplymind.foundation.storage.DataPaths.acquisitionRef(acquisitionId), null);
    }

    private static void ingestHttp(Harness harness, RawReceiptV1 raw) throws IOException {
        new com.supplymind.foundation.storage.RawAcquisitionStore(
                harness.root(), new AtomicFileStore(harness.root(), new DirtyMarkerCodec()), CLOCK)
                .store(com.supplymind.foundation.model.DomainFixtures.acquisitionFor(raw));
        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
    }

    private static void writeRawDirectly(Harness harness, RawReceiptV1 raw) throws IOException {
        byte[] rawBytes = JsonV1Codec.encodeFile(raw);
        ManifestV1 manifest = ManifestFactory.json(raw.rawRef(), rawBytes, List.of(raw.runId()), NOW);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        new AtomicFileStore(harness.root(), new DirtyMarkerCodec()).commit("raw-direct-" + raw.runId(),
                com.supplymind.foundation.storage.DirtyTransactionType.SINGLE_FILE, NOW,
                List.of(new com.supplymind.foundation.storage.FileTransactionTarget(
                        com.supplymind.foundation.storage.DirtyTargetRole.BUSINESS_FILE,
                        raw.rawRef(), rawBytes, manifestBytes, true)));
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(
                "d4-t02 publish root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.activate(materialConfig());
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        ManualMaterialIntakeService manual = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(),
                OperatorContext.configured("op-d4t02"), CLOCK);
        LocalImportService importService = new LocalImportService(
                root, rawStore, new LocalImportFileStore(root, fileStore, CLOCK), timelineStore,
                new LocalImportCsvParser(), CLOCK);
        LifecycleValidationService validation = new LifecycleValidationService(root, timelineStore, CLOCK);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new QuarantineStore(root, fileStore, CLOCK), CLOCK);
        PublishedQueryService publishedQuery = new PublishedQueryService(root, timelineStore, CLOCK);
        return new Harness(root, rawStore, timelineStore, manual, importService,
                validation, publish, publishedQuery);
    }

    private static MonitorSeriesConfigV1 materialConfig() {
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        items.add(item(ADC12_SMM, "SMM", ProviderType.MANUAL, AccessMethod.MANUAL,
                RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", "ADC12"));
        items.add(item(IMP_ADC12, "SMM/供应商", ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT,
                RouteDecision.DIRECT_LOCAL_IMPORT, null, "ADC12"));
        items.add(item(FREE_ADC12, "免费公开源", ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
                RouteDecision.FALLBACK_FREE_PUBLIC, "FREE_PUBLIC_FALLBACK", "ADC12"));
        items.add(item(DEMO_ADC12, "SyntheticDemo", ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO,
                RouteDecision.SYNTHETIC_DEMO, null, "ADC12"));
        items.add(new MonitorSeriesItemV1(
                "FX.USD.CNY.PBOC_MID", "美元/人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, NOW, null, "USD", "1美元对人民币", "人民币汇率中间价",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null));
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, NOW, items);
    }

    private static MonitorSeriesItemV1 item(
            String itemId, String sourceIntent, ProviderType providerType, AccessMethod accessMethod,
            RouteDecision routeDecision, String fallbackReason, String externalCode
    ) {
        return new MonitorSeriesItemV1(
                itemId, itemId, true, sourceIntent, providerType, accessMethod,
                "人工录入（Manual）", routeDecision, fallbackReason, NOW, null,
                externalCode, "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, externalCode, List.of()));
    }

    private record Harness(
            DataRoot root,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            ManualMaterialIntakeService manual,
            LocalImportService importService,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            PublishedQueryService publishedQuery
    ) {
    }
}
