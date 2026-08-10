package com.supplymind.manual;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaValidationException;
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
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProviderRegistry;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-T04 contract tests (DEC-057 boundary): Manual intake reaches at most PARSED+PENDING,
 * never VERIFIED/PUBLISHED; raw is immutable and written before any Candidate; operatorRef
 * comes from the authentication context; same-key same-content is idempotent; same-key
 * different-content keeps a new pending version; PENDING stays invisible to the formal gates.
 */
class ManualMaterialIntakeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final String ADC12 = "MAT.ADC12.SMM";
    private static final String AZ91D = "MAT.AZ91D.AM";
    private static final String USD = "FX.USD.CNY.PBOC_MID";
    private static final String OPERATOR = "op-intake-001";

    @TempDir
    Path temporaryDirectory;

    @Test
    void manualDataProviderProfileRegistryAndNoFabricatedCollection() {
        Harness harness = harness();
        ManualDataProvider provider = new ManualDataProvider(() -> Set.of(ADC12, AZ91D));
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);

        assertEquals("manual-material", provider.profile().providerId());
        assertEquals(ProviderType.MANUAL, provider.profile().providerType());
        assertEquals(AccessMethod.MANUAL, provider.profile().accessMethod());
        assertTrue(provider.profile().supportsCurrentData());
        assertFalse(provider.profile().supportsHistoryData());
        assertNull(provider.profile().sourceUrl());

        assertEquals(provider, registry.require("manual-material"));
        assertEquals(List.of("manual-material"),
                registry.providersForTarget(ADC12).stream().map(p -> p.profile().providerId()).toList());

        com.supplymind.provider.ProviderCollectOutcome outcome = provider.collect(
                new com.supplymind.provider.ProviderCollectRequest(List.of(ADC12, AZ91D)));
        assertEquals(Map.of(ADC12, "MANUAL_INTAKE_REQUIRED", AZ91D, "MANUAL_INTAKE_REQUIRED"),
                outcome.rejectedItemIds());
        assertTrue(outcome.raws().isEmpty(),
                "manual without a real submission must never fabricate data");
    }

    @Test
    void adc12AndAz91dValidSubmissionsReachParsedPending() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome adc12 = harness.intake().submit(
                ManualMaterialSubmission.of(ADC12, "2026-08-10", "19850.50", "元/吨", "CNY",
                        "某供应商报价单（测试）", "供应商2026-08-10报价单编号PB-20260810", null));
        ManualIntakeOutcome az91d = harness.intake().submit(
                ManualMaterialSubmission.of(AZ91D, "2026-08-10", "24500", "元/吨", "CNY",
                        "某供应商报价单（测试）", "供应商2026-08-10报价单编号MG-20260810",
                        "https://example.test/source-reference"));

        assertEquals(ProcessingStage.PARSED, adc12.processingStage());
        assertEquals(ValidationStatus.PENDING, adc12.validationStatus());
        assertEquals("19850.50", adc12.normalizedValue());
        assertEquals(ManualIntakeOutcome.IntakeMode.NEW, adc12.mode());
        assertEquals(ProcessingStage.PARSED, az91d.processingStage());
        assertEquals(ValidationStatus.PENDING, az91d.validationStatus());

        LifecycleTimelineV1 timeline = harness.timelineStore().read(adc12.runId());
        assertEquals(ProcessingStage.PARSED, timeline.current().processingStage());
        assertEquals(ValidationStatus.PENDING, timeline.current().validationStatus());
        assertNotNull(timeline.current().candidate());
        assertEquals(ManualMaterialNormalizer.NORMALIZATION_VERSION,
                timeline.current().candidate().normalizationVersion());
        assertEquals("19850.50", timeline.current().candidate().value());
        assertEquals(ManualMaterialSubmission.class.getSimpleName(), "ManualMaterialSubmission");
        assertFalse(timeline.records().stream()
                        .anyMatch(s -> s.processingStage() == ProcessingStage.VALIDATED
                                || s.processingStage() == ProcessingStage.PUBLISHED),
                "no VALIDATED/PUBLISHED snapshot may exist in D3-T04");

        assertManualRaw(harness, adc12, "19850.50");
        assertManualRaw(harness, az91d, "24500");
    }

    @Test
    void unsupportedOrNonManualRouteItemFailsClosedBeforeRaw() throws IOException {
        Harness harness = harness();
        assertThrows(StorageException.class, () -> harness.intake().submit(
                ManualMaterialSubmission.of("MAT.UNKNOWN.999", "2026-08-10", "1.0", "元/吨", "CNY",
                        "test", "ref", null)));
        assertThrows(StorageException.class, () -> harness.intake().submit(
                ManualMaterialSubmission.of(USD, "2026-08-10", "6.79", "CNY/1 USD", "CNY",
                        "test", "ref", null)),
                "a configured item without the Manual route must fail closed");
        assertFalse(Files.exists(harness.root().resolveInternalRelative("raw/formal/manual")),
                "fail-closed intake must not create manual raws");
    }

    @Test
    void operatorRefComesFromAuthContextAndClientCannotSpecifyIt() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome outcome = harness.intake().submit(submission(ADC12, "19850.50"));

        RawReceiptV1 raw = decodeRaw(harness.root(), outcome.rawRef());
        assertEquals(OPERATOR, raw.operatorRef());
        assertEquals(outcome.runId(), raw.runId());

        assertFalse(Arrays.stream(ManualMaterialSubmission.class.getRecordComponents())
                        .anyMatch(c -> c.getName().equals("operatorRef")),
                "the submission DTO must not expose operatorRef to the client");

        ManualIntakeOutcome otherOperator = sameRootHarness(harness, CLOCK, OperatorContext.configured("op-other"))
                .intake().submit(submission(ADC12, "19850.50"));
        assertEquals(ManualIntakeOutcome.IntakeMode.NEW, otherOperator.mode(),
                "a different operator is different business content (DEC-057) and must create a new version");
        assertFalse(outcome.runId().equals(otherOperator.runId()));
    }

    @Test
    void sourceReferenceRequiredAndSourceUrlNullable() {
        Harness harness = harness();
        assertThrows(SchemaValidationException.class,
                () -> ManualMaterialSubmission.of(ADC12, "2026-08-10", "1.0", "元/吨", "CNY",
                        "test", "  ", null),
                "blank sourceReference must fail closed at the intake boundary");

        ManualIntakeOutcome noUrl = harness.intake().submit(
                ManualMaterialSubmission.of(ADC12, "2026-08-10", "19850.50", "元/吨", "CNY",
                        "test", "ref-no-url", null));
        assertEquals(ManualIntakeOutcome.IntakeMode.NEW, noUrl.mode(),
                "sourceUrl=null must not by itself cause PENDING/REJECTED/NOTICE side effects");
        assertEquals(ValidationStatus.PENDING, noUrl.validationStatus());
    }

    @Test
    void scientificNotationAndNonDecimalValuesFailMechanicallyKeepingRaw() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome scientific = harness.intake().submit(
                ManualMaterialSubmission.of(ADC12, "2026-08-10", "1.985E4", "元/吨", "CNY",
                        "test", "ref", null));
        assertEquals(ManualIntakeOutcome.IntakeMode.REJECTED_MECHANICAL, scientific.mode());
        assertEquals(ProcessingStage.RECEIVED, scientific.processingStage());
        assertEquals(ValidationStatus.REJECTED, scientific.validationStatus());
        assertEquals("VALUE_SCIENTIFIC_NOTATION", scientific.reasonCode());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(scientific.rawRef())),
                "the raw of a mechanically rejected submission must be preserved");
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.manifestRef(scientific.rawRef()))));

        ManualIntakeOutcome nonDecimal = harness.intake().submit(
                ManualMaterialSubmission.of(ADC12, "2026-08-10", "abc", "元/吨", "CNY",
                        "test", "ref", null));
        assertEquals("VALUE_NOT_DECIMAL", nonDecimal.reasonCode());
        assertEquals(ManualIntakeOutcome.IntakeMode.REJECTED_MECHANICAL, nonDecimal.mode());
    }

    @Test
    void businessDateMustBeStrictIso() {
        assertThrows(SchemaValidationException.class,
                () -> ManualMaterialSubmission.of(ADC12, "2026/08/10", "1.0", "元/吨", "CNY",
                        "test", "ref", null));
        assertThrows(SchemaValidationException.class,
                () -> ManualMaterialSubmission.of(ADC12, "10-08-2026", "1.0", "元/吨", "CNY",
                        "test", "ref", null));
    }

    @Test
    void normalizationDoesNotJudgeRangeOrFutureDate() {
        Harness harness = harness();
        ManualIntakeOutcome huge = harness.intake().submit(
                ManualMaterialSubmission.of(ADC12, "2099-01-01", "999999999.123456", "元/吨", "CNY",
                        "test", "ref-future", null));
        assertEquals(ManualIntakeOutcome.IntakeMode.NEW, huge.mode(),
                "value range and future date are DEFERRED_TO_D4_T01; D3-T04 must not reject them");
        assertEquals(ProcessingStage.PARSED, huge.processingStage());
        assertEquals("999999999.123456", huge.normalizedValue());
    }

    @Test
    void rawFirstRawPersistedAndImmutableBeforeCandidate() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome outcome = harness.intake().submit(submission(ADC12, "19850.50"));
        Path rawPath = harness.root().resolveDataRef(outcome.rawRef());
        Path manifestPath = harness.root().resolveDataRef(DataPaths.manifestRef(outcome.rawRef()));
        assertTrue(Files.isRegularFile(rawPath));
        assertTrue(Files.isRegularFile(manifestPath));
        ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        assertEquals(FileDigest.sha256(rawPath), manifest.fileSha256());
        assertTrue(ManifestVerifier.matches(harness.root(), outcome.rawRef(), rawPath, manifestPath,
                List.of(outcome.runId())));
        byte[] rawBytes = Files.readAllBytes(rawPath);

        ManualIntakeOutcome revision = harness.intake().submit(submission(ADC12, "20000.00"));
        assertArrayEquals(rawBytes, Files.readAllBytes(rawPath),
                "the original immutable raw must never change after a revision");
        assertFalse(outcome.runId().equals(revision.runId()));
    }

    @Test
    void sameKeySameContentIsIdempotentRegardlessOfReceivedAt() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome first = harness.intake().submit(submission(ADC12, "19850.50"));

        ManualIntakeOutcome second = sameRootHarness(harness, Clock.fixed(
                Instant.parse("2026-08-10T05:30:00Z"), ZoneOffset.UTC),
                OperatorContext.configured(OPERATOR)).intake().submit(submission(ADC12, "19850.50"));

        assertEquals(ManualIntakeOutcome.IntakeMode.IDEMPOTENT_REUSE, second.mode());
        assertEquals(first.runId(), second.runId());
        assertEquals(first.rawRef(), second.rawRef());
        assertEquals(manualRawCount(harness.root(), ADC12), 1,
                "an idempotent replay must not create duplicate raw files");
        assertEquals(1, manualTimelineCount(harness.root()),
                "an idempotent replay must not create a new timeline");
    }

    @Test
    void sameKeyDifferentContentCreatesNewPendingVersionAndPreservesOld() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome v1 = harness.intake().submit(submission(ADC12, "19850.50"));
        ManualIntakeOutcome v2 = harness.intake().submit(submission(ADC12, "20000.00"));

        assertEquals(ManualIntakeOutcome.IntakeMode.NEW, v2.mode());
        assertFalse(v1.runId().equals(v2.runId()));
        assertEquals(2, manualRawCount(harness.root(), ADC12));
        assertEquals(2, manualTimelineCount(harness.root()));

        LifecycleTimelineV1 v1Timeline = harness.timelineStore().read(v1.runId());
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.stagingRef(v1.runId()))),
                "the old version timeline must be permanently preserved");
        assertEquals(ProcessingStage.PARSED, v1Timeline.current().processingStage());
        assertEquals(ValidationStatus.PENDING, v1Timeline.current().validationStatus());
        assertTrue(v1Timeline.records().stream().noneMatch(s ->
                        s.validationStatus() == ValidationStatus.CONFLICT
                                || s.processingStage() == ProcessingStage.PUBLISHED),
                "no CONFLICT or PUBLISHED may be produced for a revision in D3-T04");
        assertNotNull(v2.runId());
    }

    @Test
    void pendingManualCannotPenetrateFormalGates() throws IOException {
        Harness harness = harness();
        ManualIntakeOutcome outcome = harness.intake().submit(submission(ADC12, "19850.50"));
        assertEquals(ValidationStatus.PENDING, outcome.validationStatus());

        PublishOutcome publish = harness.publish().process(outcome.runId());
        assertEquals(PublishOutcome.PublishAction.NOT_READY, publish.action(),
                "PARSED+PENDING manual must be rejected by the existing publish gate (negative)");

        assertEquals(0, harness.publishedQuery().findPublished(ADC12, java.time.LocalDate.parse("2026-08-10")).size(),
                "PENDING manual must be invisible to the business read model");
        assertEquals(0, harness.daily().processMonth(ADC12, YearMonth.of(2026, 8)).rows().size(),
                "PENDING manual must not produce daily rows");
        assertNull(harness.publish().process(outcome.runId()).publishRef());
    }

    @Test
    void manualRouteResolverFindsManualProviderButNeverFabricatesData() throws IOException {
        Harness harness = harness();
        ManualDataProvider provider = new ManualDataProvider(() -> Set.of(ADC12, AZ91D));
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new com.supplymind.provider.DataProvider() {
            @Override
            public com.supplymind.provider.ProviderSourceProfile profile() {
                return com.supplymind.provider.ProviderSourceProfile.of(
                        "smm-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                        "SMM 授权接口（未配置真实凭证）", "https://www.smm.cn/", true, true);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of(ADC12, AZ91D);
            }

            @Override
            public com.supplymind.provider.ProviderCollectOutcome collect(
                    com.supplymind.provider.ProviderCollectRequest request
            ) {
                java.util.LinkedHashMap<String, String> rejected = new java.util.LinkedHashMap<>();
                for (String itemId : request.itemIds()) {
                    rejected.put(itemId, "UNSUPPORTED_TARGET");
                }
                return com.supplymind.provider.ProviderCollectOutcome.rejectedOnly(
                        "smm-authorized-api", rejected);
            }
        });
        registry.register(provider);
        MaterialRouteResolver resolver = new MaterialRouteResolver();
        ApiAuthorizationProbe probe = (id, profile) ->
                profile.providerType() == ProviderType.AUTHORIZED_API
                        ? Optional.of(CandidateUnavailability.CREDENTIALS_MISSING)
                        : Optional.empty();

        MaterialRouteDecision decision = resolver.resolve(
                MaterialRouteConfigV1.of(
                        ADC12, List.of("smm-authorized-api"), List.of(), List.of("manual-material")),
                registry, probe, DataKind.CURRENT, OffsetDateTime.parse("2026-08-10T02:00:00+08:00"));

        assertEquals("manual-material", decision.activeProviderId());
        assertEquals(RouteDecision.FALLBACK_MANUAL, decision.routeDecision());
        assertTrue(provider.collect(new com.supplymind.provider.ProviderCollectRequest(List.of(ADC12)))
                        .raws().isEmpty(),
                "the Manual route intent must never by itself produce material data");
    }

    private Harness harness() {
        return harness(CLOCK, OperatorContext.configured(OPERATOR));
    }

    private Harness harness(Clock clock) {
        return harness(clock, OperatorContext.configured(OPERATOR));
    }

    private Harness harness(Clock clock, OperatorContext operatorContext) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d3-t04 manual " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, clock);
        configStore.activate(manualMaterialConfig(clock));
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, clock);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, clock);
        return harnessOn(root, fileStore, timelineStore, clock, operatorContext);
    }

    /** Rebuilds the services over the SAME physical dataRoot with a different clock/operator. */
    private Harness sameRootHarness(Harness base, Clock clock, OperatorContext operatorContext) {
        AtomicFileStore fileStore = new AtomicFileStore(base.root(), new DirtyMarkerCodec());
        TimelineStore timelineStore = new TimelineStore(base.root(), fileStore, clock);
        return harnessOn(base.root(), fileStore, timelineStore, clock, operatorContext);
    }

    private Harness harnessOn(
            DataRoot root,
            AtomicFileStore fileStore,
            TimelineStore timelineStore,
            Clock clock,
            OperatorContext operatorContext
    ) {
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, clock);
        ManualMaterialIntakeService intake = new ManualMaterialIntakeService(
                root, rawStore, timelineStore, new ManualMaterialNormalizer(), operatorContext, clock);
        LifecyclePublishService publish = new LifecyclePublishService(root, timelineStore,
                new com.supplymind.foundation.storage.QuarantineStore(root, fileStore, clock), clock);
        PublishedQueryService publishedQuery = new PublishedQueryService(root, timelineStore, clock);
        DailyProcessingService daily = new DailyProcessingService(root, timelineStore, fileStore, clock);
        return new Harness(root, fileStore, timelineStore, intake, publish, publishedQuery, daily);
    }

    private static MonitorSeriesConfigV1 manualMaterialConfig(Clock clock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        MonitorSeriesItemV1 adc12 = item(ADC12, "ADC12铝合金锭", "SMM/供应商", now, "ADC12", "元/吨");
        MonitorSeriesItemV1 az91d = item(AZ91D, "AZ91D镁合金锭", "Asian Metal/供应商", now, "AZ91D", "元/吨");
        MonitorSeriesItemV1 usd = new MonitorSeriesItemV1(
                USD, "美元兑人民币中间价", true, "PBOC", ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                RouteDecision.PRIMARY, null, now, null, "USD", "1美元对人民币", "FX",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD");
        return new MonitorSeriesConfigV1("1.0", 1, Mode.FORMAL, now, List.of(adc12, az91d, usd));
    }

    private static MonitorSeriesItemV1 item(
            String itemId, String displayName, String sourceIntent, OffsetDateTime now,
            String externalCode, String unit
    ) {
        return new MonitorSeriesItemV1(
                itemId, displayName, true, sourceIntent, ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", now, null,
                externalCode, "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", unit);
    }

    private static ManualMaterialSubmission submission(String itemId, String value) {
        return ManualMaterialSubmission.of(itemId, "2026-08-10", value, "元/吨", "CNY",
                "某供应商报价单（测试）", "ref-" + itemId + "-20260810", null);
    }

    private static void assertManualRaw(Harness harness, ManualIntakeOutcome outcome, String value)
            throws IOException {
        RawReceiptV1 raw = decodeRaw(harness.root(), outcome.rawRef());
        assertEquals(value, raw.rawValue(), "the raw must preserve the original value string verbatim");
        assertEquals(ProviderType.MANUAL, raw.providerType());
        assertEquals(AccessMethod.MANUAL, raw.accessMethod());
        assertEquals(OPERATOR, raw.operatorRef());
        assertEquals("2026-08-10", raw.sourceBusinessDate());
        assertNotNull(raw.inputAt());
        assertEquals("人工录入（Manual）", raw.actualSourceName(),
                "the raw source identity must stay the configured Manual identity, never the user-declared source");
        ManualMaterialSubmission submittedFacts = JsonV1Codec.decodeFile(
                Base64.getDecoder().decode(raw.payloadBase64()), ManualMaterialSubmission.class);
        assertEquals("某供应商报价单（测试）", submittedFacts.actualSourceName(),
                "the user-declared actual source name must be preserved verbatim as immutable submission facts");
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(DataPaths.manifestRef(outcome.rawRef()))));
    }

    private static RawReceiptV1 decodeRaw(DataRoot root, String ref) throws IOException {
        return JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(ref)), RawReceiptV1.class);
    }

    private static int manualRawCount(DataRoot root, String itemId) throws IOException {
        Path dir = root.resolveInternalRelative("raw/formal/manual/" + itemId);
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

    private static int manualTimelineCount(DataRoot root) throws IOException {
        Path dir = root.resolveInternalRelative("staging");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.list(dir)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("manual-"))
                    .filter(p -> !p.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private record Harness(
            DataRoot root,
            AtomicFileStore fileStore,
            TimelineStore timelineStore,
            ManualMaterialIntakeService intake,
            LifecyclePublishService publish,
            PublishedQueryService publishedQuery,
            DailyProcessingService daily
    ) {
    }
}
