package com.supplymind.provider.pboc;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8-M1 attack tests: the REAL PbocOfficialWebDataProvider (with a stubbed HTTP transport and
 * formal HTML fixtures) must handle a dynamically configured GBP target from the active
 * configuration metadata - no fake DataProvider proves production capability. Config-driven
 * anchor resolution, fail-closed missing-anchor behavior, USD/EUR regression and the raw-first
 * contract are all exercised.
 */
class PbocDynamicTargetAttackTest {

    private static final String FIXTURE_ROOT = "contracts/v1/d1-t04-pboc/";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:30:00Z"), ZoneOffset.UTC);
    private static final URI LIST_URI = PbocOfficialWebDataProvider.ANNOUNCEMENT_LIST_URI;
    private static final URI DETAIL_URI = URI.create(
            "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/fixture-announcement-20260810.html");
    private static final String GBP_ITEM = "FX.GBP.CNY.PBOC_MID";
    private static final String GBP_ANCHOR = "1\u82f1\u9551\u5bf9\u4eba\u6c11\u5e01"; // 1英镑对人民币
    private static final String GBP_DISPLAY = "\u82f1\u9551/\u4eba\u6c11\u5e01\u4e2d\u95f4\u4ef7"; // 英镑/人民币中间价
    private static final String PBOC_SOURCE_NAME =
            "\u4e2d\u56fd\u4eba\u6c11\u94f6\u884c\u5b98\u7f51\uff08\u6388\u6743\u4e2d\u56fd\u5916\u6c47\u4ea4\u6613\u4e2d\u5fc3\u516c\u5e03\uff09";

    @TempDir
    Path temporaryDirectory;

    @Test
    void realProviderCollectsDynamicallyConfiguredGbpFromItsConfigurationMetadata() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");
        // M1: GBP is added to the ACTIVE CONFIGURATION as a normal PBOC target (no Java
        // whitelist, no code change) - the provider must pick it up by metadata.
        addGbpTarget(harness.configs());
        assertTrue(harness.configs().active().items().stream()
                .anyMatch(item -> item.itemId().equals(GBP_ITEM) && item.enabled()));
        MonitorSeriesItemV1 cfgItem = harness.configs().active().requireItem(GBP_ITEM);
        assertTrue(PbocOfficialWebDataProvider.isConfiguredPbocTarget(cfgItem),
                "the configured GBP item must satisfy the metadata predicate");

        ProviderCollectOutcome outcome = harness.provider().collect(
                ProviderCollectRequest.current(List.of(GBP_ITEM)));

        assertEquals(0, outcome.rejectedItemIds().size(),
                "the real provider must accept the configured GBP target - no UNSUPPORTED_TARGET");
        assertTrue(outcome.raws().stream().anyMatch(raw -> raw.itemId().equals(GBP_ITEM)),
                "GBP is collected through the real provider");
        RawReceiptV1 gbpRaw = outcome.raws().stream()
                .filter(raw -> raw.itemId().equals(GBP_ITEM)).findFirst().orElseThrow();
        assertEquals(GBP_ITEM, gbpRaw.itemId());
        assertEquals(GBP_ANCHOR, gbpRaw.matchAnchor(),
                "the value is resolved by the configured sourceFieldKey anchor, never by itemId guessing");
        assertEquals("9.345678901", gbpRaw.rawValue());
        assertEquals("CNY/1 GBP", gbpRaw.rawUnit());
        assertEquals("CNY", gbpRaw.rawCurrency());
        assertEquals("2026-08-10", gbpRaw.sourceBusinessDate());
        assertEquals(PBOC_SOURCE_NAME, gbpRaw.actualSourceName());
        assertTrue(outcome.timelines().stream().anyMatch(
                        timeline -> timeline.runId().equals(gbpRaw.runId())),
                "GBP has its own independent timeline");
        LifecycleTimelineV1 timeline = outcome.timelines().stream()
                .filter(candidate -> candidate.runId().equals(gbpRaw.runId())).findFirst().orElseThrow();
        assertEquals(gbpRaw.runId(), timeline.runId());
        assertEquals(gbpRaw.rawRef(), timeline.rawRef());

        assertRawFirst(harness.root(), gbpRaw);
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(gbpRaw.rawRef())));
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.manifestRef(gbpRaw.rawRef()))));
    }

    @Test
    void realProviderStillCollectsTheFrozenUsdEurPairAfterGbpIsConfigured() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");
        addGbpTarget(harness.configs());

        ProviderCollectOutcome outcome = harness.provider().collect(
                ProviderCollectRequest.current(List.of(
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                        MonitorSeriesDefaults.EUR_CNY_ITEM_ID,
                        GBP_ITEM)));

        assertEquals(0, outcome.rejectedItemIds().size());
        assertEquals(3, outcome.raws().size(), "USD/EUR/GBP all resolve from one shared acquisition");
        Map<String, RawReceiptV1> byItem = new java.util.LinkedHashMap<>();
        outcome.raws().forEach(raw -> byItem.put(raw.itemId(), raw));
        assertEquals("6.812345678", byItem.get(MonitorSeriesDefaults.USD_CNY_ITEM_ID).rawValue());
        assertEquals("7.901234567", byItem.get(MonitorSeriesDefaults.EUR_CNY_ITEM_ID).rawValue());
        assertEquals("9.345678901", byItem.get(GBP_ITEM).rawValue());
        assertEquals(1, acquisitionFileCount(harness.root()),
                "one announcement run shares one source acquisition across all targets");
        assertTrue(Files.isRegularFile(harness.root().resolveDataRef(
                DataPaths.acquisitionRef(byItem.get(GBP_ITEM).acquisitionId()))));
    }

    @Test
    void missingConfiguredAnchorOnTheOfficialPageFailsClosedWithoutFabrication() throws Exception {
        Harness harness = harness("announcement-detail-no-gbp.html");
        addGbpTarget(harness.configs());

        ProviderCollectOutcome outcome = harness.provider().collect(
                ProviderCollectRequest.current(List.of(GBP_ITEM)));

        assertEquals(1, outcome.rejectedItemIds().size(),
                "a configured anchor absent from the official page fails closed for that target");
        assertEquals("CONFIGURED_ANCHOR_NOT_ON_PAGE", outcome.rejectedItemIds().get(GBP_ITEM));
        assertFalse(outcome.raws().stream().anyMatch(raw -> raw.itemId().equals(GBP_ITEM)),
                "no fabricated GBP value is ever produced");
        assertTrue(outcome.raws().stream().allMatch(raw -> raw.itemId().equals(GBP_ITEM)
                        || raw.itemId().equals(MonitorSeriesDefaults.USD_CNY_ITEM_ID)
                        || raw.itemId().equals(MonitorSeriesDefaults.EUR_CNY_ITEM_ID)),
                "only genuinely present configured anchors produce raws");
        assertFalse(Files.exists(harness.root().path().resolve("raw").resolve("formal")
                        .resolve("official_web").resolve(GBP_ITEM)),
                "no item raw directory is created for the failed target");
    }

    @Test
    void missingFrozenUsdStillRejectsTheWholeLegacyCollectionWithAcquisitionOnly() throws Exception {
        Harness harness = harness("announcement-detail-missing-usd.html");

        PbocCollectionException exception = assertThrows(PbocCollectionException.class,
                () -> harness.provider().collectLatestAnnouncement());

        assertEquals(PbocCollectionFailureKind.PARSE_REJECTED, exception.failureKind());
        assertEquals("DETAIL", exception.stage());
        Path sourceDir = harness.root().path().resolve("raw").resolve("source");
        assertTrue(Files.isDirectory(sourceDir));
        assertFalse(Files.exists(harness.root().path().resolve("raw").resolve("formal")),
                "parse failure must not create item-level raws");
        assertFalse(Files.exists(harness.root().path().resolve("staging")));
    }

    @Test
    void unsupportedTargetIsRejectedWithoutAnyNetworkAccess() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");

        ProviderCollectOutcome outcome = harness.provider().collect(
                ProviderCollectRequest.current(List.of("MAT.ADC12.SMM")));

        assertEquals(Map.of("MAT.ADC12.SMM", "UNSUPPORTED_TARGET"), outcome.rejectedItemIds());
        assertTrue(outcome.raws().isEmpty());
        assertEquals(0, harness.transport().requestedUris().size(),
                "an all-unsupported request must be rejected without any network access");
    }

    @Test
    void gbpRunAndTimelineAreIndependentFromUsdEurSharesTheAcquisition() throws Exception {
        Harness harness = harness("announcement-detail-normal-with-gbp.html");
        addGbpTarget(harness.configs());

        ProviderCollectOutcome outcome = harness.provider().collect(
                ProviderCollectRequest.current(List.of(
                        MonitorSeriesDefaults.USD_CNY_ITEM_ID, GBP_ITEM)));

        RawReceiptV1 usd = outcome.raws().get(0);
        RawReceiptV1 gbp = outcome.raws().get(1);
        assertEquals(usd.acquisitionId(), gbp.acquisitionId(),
                "same announcement run shares the source acquisition");
        assertFalse(usd.runId().equals(gbp.runId()), "independent run per target");
        assertFalse(usd.rawRef().equals(gbp.rawRef()), "independent raw per target");
        assertFalse(usd.itemId().equals(gbp.itemId()));
    }

    // ---- fixture ----

    private Harness harness(String detailFixture) throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("d8 m1 pboc root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configStore = new ConfigActivationStore(root, fileStore, CLOCK);
        configStore.ensureInitialDefault();
        RawReceiptStore rawReceiptStore = new RawReceiptStore(root, fileStore, CLOCK);
        byte[] listEntity = fixtureBytes("announcement-list-normal.html");
        byte[] detailEntity = fixtureBytes(detailFixture);
        StubTransport transport = new StubTransport(Map.of(
                LIST_URI, new PbocHttpResponse(LIST_URI, 200, "text/html; charset=UTF-8", listEntity),
                DETAIL_URI, new PbocHttpResponse(DETAIL_URI, 200, "text/html; charset=UTF-8", detailEntity)));
        PbocOfficialWebDataProvider provider = new PbocOfficialWebDataProvider(
                root, rawReceiptStore,
                new com.supplymind.foundation.storage.RawAcquisitionStore(root, fileStore, CLOCK),
                fileStore, CLOCK, transport, new PbocAnnouncementParser(), event -> { });
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);
        registry.register(com.supplymind.support.TestFreePublicProvider.create());
        registry.register(new com.supplymind.manual.ManualDataProvider(() -> Set.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID)));
        ConfigManagementService configs = new ConfigManagementService(configStore, registry);
        return new Harness(root, provider, transport, configs);
    }

    private static ConfigV1.AddItemRequest gbpRequest() {
        return new ConfigV1.AddItemRequest(
                GBP_ITEM, GBP_DISPLAY, "PBOC",
                ProviderType.OFFICIAL_WEB.wireValue(), AccessMethod.PUBLIC_OFFICIAL_HTML.wireValue(),
                PBOC_SOURCE_NAME, RouteDecision.PRIMARY.wireValue(), null,
                "GBP", GBP_ANCHOR, MonitorSeriesDefaults.PBOC_RATE_KIND,
                MonitorSeriesDefaults.CALCULATION_VERSION, 8, 4, "HALF_UP",
                MonitorSeriesDefaults.CALENDAR_VERSION, "CNY", "GBP", "CNY/1 GBP",
                null, null, null);
    }

    /** Adds GBP to the active configuration through the frozen ConfigManagementService. */
    private static void addGbpTarget(ConfigManagementService configs) {
        ConfigV1.AddItemRequest request = gbpRequest();
        configs.addItem(new MonitorSeriesItemV1(
                request.itemId(), request.displayName(), true, request.sourceIntent(),
                ProviderType.fromWireValue(request.providerType()),
                AccessMethod.fromWireValue(request.accessMethod()),
                request.actualSourceName(),
                RouteDecision.fromWireValue(request.routeDecision()),
                request.fallbackReason(), OffsetDateTime.parse("2026-08-10T02:00:00+08:00"),
                null, request.externalCode(), request.sourceFieldKey(), request.rateKind(),
                request.calculationVersion(), request.calculationScale(), request.displayScale(),
                java.math.RoundingMode.HALF_UP, request.calendarVersion(),
                request.currency(), request.baseCurrency(), request.unit(), null));
    }

    private static void assertRawFirst(DataRoot root, RawReceiptV1 raw) throws IOException {
        Path acquisitionPath = root.resolveDataRef(raw.acquisitionRef());
        assertTrue(Files.isRegularFile(acquisitionPath), "the source acquisition must be persisted");
        assertTrue(Files.isRegularFile(root.resolveDataRef(DataPaths.manifestRef(raw.acquisitionRef()))));
        assertTrue(ManifestVerifier.matches(root, raw.acquisitionRef(), acquisitionPath,
                root.resolveDataRef(DataPaths.manifestRef(raw.acquisitionRef())),
                List.of(raw.acquisitionId())));
        com.supplymind.foundation.model.RawAcquisitionV1 acquisition = JsonV1Codec.decodeFile(
                Files.readAllBytes(acquisitionPath), com.supplymind.foundation.model.RawAcquisitionV1.class);
        assertEquals(raw.payloadSha256(), acquisition.payloadSha256(),
                "the item raw and the source acquisition must carry the same unparsed payload hash");
    }

    private static int acquisitionFileCount(DataRoot root) throws IOException {
        Path sourceDir = root.path().resolve("raw").resolve("source");
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        try (var stream = Files.list(sourceDir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .count();
        }
    }

    private byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(FIXTURE_ROOT + name),
                () -> "Missing D8-M1 synthetic fixture " + name)) {
            return stream.readAllBytes();
        }
    }

    private record Harness(
            DataRoot root,
            PbocOfficialWebDataProvider provider,
            StubTransport transport,
            ConfigManagementService configs
    ) {
    }

    private static final class StubTransport implements PbocHttpTransport {
        private final Map<URI, PbocHttpResponse> responses;
        private final List<URI> requestedUris = new ArrayList<>();

        StubTransport(Map<URI, PbocHttpResponse> responses) {
            this.responses = responses;
        }

        @Override
        public PbocHttpResponse get(URI uri) {
            requestedUris.add(uri);
            PbocHttpResponse response = responses.get(uri);
            if (response == null) {
                throw new AssertionError("Unexpected synthetic fixture request: " + uri);
            }
            return response;
        }

        List<URI> requestedUris() {
            return List.copyOf(requestedUris);
        }
    }
}
