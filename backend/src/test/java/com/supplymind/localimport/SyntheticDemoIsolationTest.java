package com.supplymind.localimport;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.routing.CandidateUnavailability;
import com.supplymind.routing.DataKind;
import com.supplymind.routing.MaterialRouteConfigV1;
import com.supplymind.routing.MaterialRouteDecision;
import com.supplymind.routing.MaterialRouteResolver;
import com.supplymind.routing.RouteAcceptance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAY3 STAGE FIX — Lane B contract tests. All data in this class is SyntheticDemo test data,
 * never real PBOC or formal business evidence.
 */
class SyntheticDemoIsolationTest {

    private static final String DEMO_ADC12 = "DEMO.ADC12.001";
    private static final String DEMO_AZ91D = "DEMO.AZ91D.001";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-10T09:30:00+08:00");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T01:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void syntheticOutputUsesDemoModeAndIsStablePerItemRegardlessOfRequestOrder() {
        SyntheticDemoDataProvider provider = new SyntheticDemoDataProvider(
                SyntheticDemoDataProvider.defaultScenarioItems());

        ProviderCollectOutcome ordered = provider.collect(new ProviderCollectRequest(List.of(DEMO_ADC12, DEMO_AZ91D)));
        ProviderCollectOutcome replay = provider.collect(new ProviderCollectRequest(List.of(DEMO_ADC12, DEMO_AZ91D)));
        ProviderCollectOutcome reversed = provider.collect(new ProviderCollectRequest(List.of(DEMO_AZ91D, DEMO_ADC12)));

        assertEquals(ordered, replay, "the same frozen scenario input must replay exactly");
        assertEquals(ordered, reversed, "request ordering must not affect the deterministic scenario output");
        assertEquals(2, ordered.raws().size());
        for (RawReceiptV1 raw : ordered.raws()) {
            assertEquals(Mode.DEMO, raw.mode());
            assertFalse(raw.mode() == Mode.FORMAL, "SyntheticDemo must never emit a formal raw");
            assertEquals(ProviderType.SYNTHETIC_DEMO, raw.providerType());
            assertEquals(AccessMethod.SYNTHETIC_DEMO, raw.accessMethod());
            assertTrue(raw.rawRef().startsWith("raw/demo/synthetic_demo/"));
        }
        Map<String, RawReceiptV1> byItem = ordered.raws().stream()
                .collect(java.util.stream.Collectors.toMap(RawReceiptV1::itemId, raw -> raw));
        assertEquals(byItem.get(DEMO_ADC12), reversed.raws().stream()
                .filter(raw -> raw.itemId().equals(DEMO_ADC12)).findFirst().orElseThrow());
        assertEquals(byItem.get(DEMO_AZ91D), reversed.raws().stream()
                .filter(raw -> raw.itemId().equals(DEMO_AZ91D)).findFirst().orElseThrow());
    }

    @Test
    void syntheticIsExcludedFromFormalRoutingAndNeverFillsFormalNoData() {
        SyntheticDemoDataProvider provider = new SyntheticDemoDataProvider(
                SyntheticDemoDataProvider.defaultScenarioItems());
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(provider);
        MaterialRouteResolver resolver = new MaterialRouteResolver();

        MaterialRouteDecision explicitlySynthetic = resolver.resolve(
                MaterialRouteConfigV1.of(DEMO_ADC12, List.of(provider.profile().providerId()), List.of(), List.of()),
                registry, (id, profile) -> Optional.empty(), DataKind.CURRENT, AT);
        assertNull(explicitlySynthetic.activeProviderId());
        assertEquals(RouteAcceptance.ROUTE_UNAVAILABLE, explicitlySynthetic.routeAcceptance());
        assertTrue(explicitlySynthetic.candidates().stream().anyMatch(candidate ->
                candidate.providerId().equals(provider.profile().providerId())
                        && candidate.unavailability() == CandidateUnavailability.SYNTHETIC_NOT_FORMAL));

        MaterialRouteDecision noFormalCandidate = resolver.resolve(
                MaterialRouteConfigV1.of(DEMO_ADC12, List.of(), List.of(), List.of()),
                registry, (id, profile) -> Optional.empty(), DataKind.CURRENT, AT);
        assertNull(noFormalCandidate.activeProviderId(), "formal no-data must never auto-fallback to SyntheticDemo");
        assertEquals(RouteAcceptance.ROUTE_UNAVAILABLE, noFormalCandidate.routeAcceptance());
    }

    @Test
    void persistedSyntheticDemoRawAndPublishedTimelineAreBlockedAtFormalReadBoundaries() throws IOException {
        SyntheticDemoDataProvider provider = new SyntheticDemoDataProvider(
                SyntheticDemoDataProvider.defaultScenarioItems());
        RawReceiptV1 raw = provider.collect(new ProviderCollectRequest(List.of(DEMO_ADC12))).raws().get(0);
        Harness harness = harness(raw.actualSourceName());

        harness.rawStore().store(raw);
        harness.timelineStore().createInitial(raw.runId(), raw.rawRef(), raw.receivedAt());
        assertEquals(ProcessingStage.RECEIVED, harness.timelineStore().read(raw.runId()).current().processingStage());
        assertEquals(ValidationStatus.PENDING, harness.timelineStore().read(raw.runId()).current().validationStatus());
        appendPublishedSyntheticTimeline(harness.timelineStore(), raw);

        Path rawPath = harness.root().resolveDataRef(raw.rawRef());
        assertTrue(Files.isRegularFile(rawPath));
        assertTrue(ManifestVerifier.matches(harness.root(), raw.rawRef(), rawPath,
                harness.root().resolveDataRef(DataPaths.manifestRef(raw.rawRef())), List.of(raw.runId())));
        assertTrue(raw.rawRef().startsWith("raw/demo/synthetic_demo/"));
        assertFalse(Files.exists(harness.root().resolveInternalRelative("raw/formal/synthetic_demo")));

        assertTrue(harness.publishedQuery().findPublished(DEMO_ADC12, LocalDate.parse("2026-08-10")).isEmpty(),
                "PublishedQueryService is a formal business read boundary and must exclude SyntheticDemo");
        assertNull(harness.publishedQuery().latestPublished(DEMO_ADC12));
        assertTrue(harness.daily().processMonth(DEMO_ADC12, YearMonth.of(2026, 8)).rows().isEmpty(),
                "daily formal processing must not consume a SyntheticDemo lifecycle, even if it is forged published");
        assertFalse(Files.exists(harness.root().resolveInternalRelative(
                "processed/daily/" + DEMO_ADC12 + "/2026-08.csv")));
    }

    private Harness harness(String actualSourceName) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("synthetic demo isolation"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        new ConfigActivationStore(root, fileStore, CLOCK).activate(demoConfig(actualSourceName));
        RawReceiptStore rawStore = new RawReceiptStore(root, fileStore, CLOCK);
        TimelineStore timelineStore = new TimelineStore(root, fileStore, CLOCK);
        return new Harness(root, rawStore, timelineStore,
                new PublishedQueryService(root, timelineStore, CLOCK),
                new DailyProcessingService(root, timelineStore, fileStore, CLOCK));
    }

    private static MonitorSeriesConfigV1 demoConfig(String actualSourceName) {
        return new MonitorSeriesConfigV1(SchemaV1.VERSION, 1, Mode.DEMO, AT, List.of(
                demoItem(DEMO_ADC12, actualSourceName),
                demoItem(DEMO_AZ91D, actualSourceName)));
    }

    private static MonitorSeriesItemV1 demoItem(String itemId, String actualSourceName) {
        return new MonitorSeriesItemV1(
                itemId, itemId, true, "SyntheticDemo test scenario", ProviderType.SYNTHETIC_DEMO,
                AccessMethod.SYNTHETIC_DEMO, actualSourceName, RouteDecision.SYNTHETIC_DEMO,
                null, AT, null, itemId, "synthetic-demo-field", "synthetic-demo",
                "arithmetic-mean-v1", 8, 4, RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨", null);
    }

    private static void appendPublishedSyntheticTimeline(TimelineStore timelineStore, RawReceiptV1 raw) {
        CandidateV1 candidate = new CandidateV1(
                raw.itemId(), raw.sourceBusinessDate(), raw.rawValue(), raw.rawCurrency(), raw.rawUnit(),
                raw.providerType(), raw.actualSourceName(), raw.accessMethod(), "synthetic-demo-test-v1");
        OffsetDateTime parsedAt = raw.receivedAt().plusMinutes(1);
        OffsetDateTime validatedAt = raw.receivedAt().plusMinutes(2);
        OffsetDateTime publishedAt = raw.receivedAt().plusMinutes(3);
        timelineStore.append(raw.runId(), new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate, null,
                null, null, null, null, parsedAt));
        timelineStore.append(raw.runId(), new LifecycleSnapshotV1(
                3, ProcessingStage.VALIDATED, ValidationStatus.VERIFIED, candidate, null,
                "synthetic-demo-test-v1", validatedAt, null, null, validatedAt));
        timelineStore.append(raw.runId(), new LifecycleSnapshotV1(
                4, ProcessingStage.PUBLISHED, ValidationStatus.VERIFIED, candidate, null,
                "synthetic-demo-test-v1", validatedAt, publishedAt,
                "staging/" + raw.runId() + ".json#recordVersion=4", publishedAt));
    }

    private record Harness(
            DataRoot root,
            RawReceiptStore rawStore,
            TimelineStore timelineStore,
            PublishedQueryService publishedQuery,
            DailyProcessingService daily
    ) {
    }
}