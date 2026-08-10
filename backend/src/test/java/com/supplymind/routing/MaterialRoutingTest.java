package com.supplymind.routing;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-T02 contract tests: the frozen three-tier controlled degradation (DEC-037) resolves
 * through the shared DataProviderRegistry only, is capability-aware for current/history,
 * records every candidate reason and downgrade, and fails closed on unknown providers,
 * unsupported targets and routes without a legal candidate. No real material source is ever
 * accessed and no secret is represented.
 */
class MaterialRoutingTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");
    private static final String ADC12 = "MAT.ADC12.SMM";
    private static final String AZ91D = "MAT.AZ91D.AM";

    @Test
    void primaryAuthorizedApiAvailableIsAcceptedWithoutFallback() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.AVAILABLE));
        MaterialRouteDecision decision = fixture.resolve(ADC12, DataKind.CURRENT);

        assertEquals("smm-authorized-api", decision.activeProviderId());
        assertEquals(RouteDecision.PRIMARY, decision.routeDecision());
        assertNull(decision.fallbackReason());
        assertEquals(RouteAcceptance.ROUTE_ACCEPTED, decision.routeAcceptance());
        assertEquals(2, decision.candidates().size());
        assertTrue(decision.candidates().stream()
                        .anyMatch(candidate -> candidate.providerId().equals("smm-authorized-api")
                                && candidate.unavailability().isAvailable()));
        assertTrue(decision.candidates().stream()
                        .anyMatch(candidate -> candidate.providerId().equals("am-authorized-api")
                                && candidate.unavailability() == CandidateUnavailability.CREDENTIALS_MISSING));
        assertNotNull(decision.routeEffectiveAt());
    }

    @Test
    void firstTierUnavailableFallsBackToFreePublicExplicitly() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.CREDENTIALS_MISSING,
                "am-authorized-api", CandidateUnavailability.NOT_AUTHORIZED));
        MaterialRouteDecision decision = fixture.resolve(ADC12, DataKind.CURRENT);

        assertEquals("material-free-public", decision.activeProviderId());
        assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, decision.routeDecision());
        assertTrue(decision.fallbackReason().contains("smm-authorized-api=credentials_missing"),
                "fallbackReason must record why the primary tier was skipped: " + decision.fallbackReason());
        assertTrue(decision.fallbackReason().contains("am-authorized-api=not_authorized"));
        assertEquals(RouteAcceptance.ROUTE_CONDITIONAL, decision.routeAcceptance());
        assertEquals(3, decision.candidates().size());
    }

    @Test
    void authorizedApiWithoutConfiguredCredentialsIsNeverActive() {
        TestFixture fixture = TestFixture.withProbe(Map.of());
        MaterialRouteDecision decision = fixture.resolve(ADC12, DataKind.CURRENT);

        assertEquals("material-free-public", decision.activeProviderId());
        assertFalse(decision.candidates().stream()
                        .filter(candidate -> candidate.providerId().startsWith("smm"))
                        .anyMatch(candidate -> candidate.unavailability().isAvailable()),
                "an AuthorizedApi without configured credentials must stay NOT_CONFIGURED");
    }

    @Test
    void configuredButUnavailableAuthorizedApiIsNeverSelected() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.MEMBER_ONLY));
        MaterialRouteDecision decision = fixture.resolve(ADC12, DataKind.CURRENT);

        assertFalse("smm-authorized-api".equals(decision.activeProviderId()));
        assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, decision.routeDecision());
        assertTrue(decision.fallbackReason().contains("member_only"));
    }

    @Test
    void currentCapabilityFiltersCandidates() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.AVAILABLE,
                "am-authorized-api", CandidateUnavailability.AVAILABLE));
        MaterialRouteDecision decision = fixture.resolve(ADC12, DataKind.CURRENT);

        assertEquals("smm-authorized-api", decision.activeProviderId());
        assertEquals(RouteDecision.PRIMARY, decision.routeDecision());
    }

    @Test
    void historyCapabilityFiltersCandidates() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.AVAILABLE,
                "am-authorized-api", CandidateUnavailability.AVAILABLE));
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12,
                List.of("material-official-web-historyless"),
                List.of("material-free-public"),
                List.of());
        MaterialRouteDecision decision = fixture.resolver().resolve(
                config, fixture.registry(), fixture.probe(), DataKind.HISTORY, NOW);

        assertFalse("material-official-web-historyless".equals(decision.activeProviderId()),
                "a provider without history capability must be skipped for HISTORY routes");
        assertTrue(decision.candidates().stream()
                        .anyMatch(candidate -> candidate.providerId().equals("material-official-web-historyless")
                                && candidate.unavailability() == CandidateUnavailability.CAPABILITY_MISMATCH));
        assertEquals("material-free-public", decision.activeProviderId());
        assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, decision.routeDecision());
    }

    @Test
    void fallbackOrderIsPrimaryThenFreePublicThenManual() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.CREDENTIALS_MISSING,
                "am-authorized-api", CandidateUnavailability.CREDENTIALS_MISSING,
                "material-free-public", CandidateUnavailability.NO_PUBLIC_INTERFACE));
        MaterialRouteDecision decision = fixture.resolve(AZ91D, DataKind.CURRENT);

        assertEquals("material-manual", decision.activeProviderId());
        assertEquals(RouteDecision.FALLBACK_MANUAL, decision.routeDecision());
        assertEquals(RouteAcceptance.ROUTE_CONDITIONAL, decision.routeAcceptance());
        assertEquals(4, decision.candidates().size());
    }

    @Test
    void noLegalCandidateFailsClosedWithTraceableReasons() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.MEMBER_ONLY,
                "am-authorized-api", CandidateUnavailability.ANTI_SCRAPING));
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12,
                List.of("smm-authorized-api", "am-authorized-api"),
                List.of("material-free-public"),
                List.of("material-manual"));
        ApiAuthorizationProbe probe = TestFixture.probe(Map.of(
                "smm-authorized-api", CandidateUnavailability.MEMBER_ONLY,
                "am-authorized-api", CandidateUnavailability.ANTI_SCRAPING,
                "material-free-public", CandidateUnavailability.NO_PUBLIC_INTERFACE,
                "material-manual", CandidateUnavailability.CREDENTIALS_MISSING));

        MaterialRouteDecision decision = fixture.resolver().resolve(
                config, fixture.registry(), probe, DataKind.CURRENT, NOW);

        assertNull(decision.activeProviderId());
        assertEquals(RouteAcceptance.ROUTE_UNAVAILABLE, decision.routeAcceptance());
        assertNull(decision.routeDecision());
        assertTrue(decision.fallbackReason().contains("member_only"));
        assertTrue(decision.fallbackReason().contains("anti_scraping"));
        assertTrue(decision.fallbackReason().contains("no_public_interface"));
        assertThrows(ProviderRouteException.class,
                () -> fixture.resolver().resolveRequired(config, fixture.registry(), probe, DataKind.CURRENT, NOW));
    }

    @Test
    void unknownConfiguredProviderFailsClosed() {
        TestFixture fixture = TestFixture.withProbe(Map.of());
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12,
                List.of("not-registered-provider"),
                List.of(),
                List.of());
        assertThrows(ProviderRouteException.class,
                () -> fixture.resolver().resolve(config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW));
    }

    @Test
    void unsupportedTargetWithoutCandidatesFailsClosed() {
        TestFixture fixture = TestFixture.withProbe(Map.of());
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                "MAT.UNKNOWN.001", List.of(), List.of(), List.of());
        MaterialRouteDecision decision = fixture.resolver().resolve(
                config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW);
        assertNull(decision.activeProviderId());
        assertEquals(RouteAcceptance.ROUTE_UNAVAILABLE, decision.routeAcceptance());
        assertThrows(ProviderRouteException.class,
                () -> fixture.resolver().resolveRequired(
                        config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW));
    }

    @Test
    void manualCannotImpersonateOfficialTier() {
        TestFixture fixture = TestFixture.withProbe(Map.of());
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12,
                List.of("material-manual"),
                List.of(),
                List.of());
        MaterialRouteDecision decision = fixture.resolver().resolve(
                config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW);

        assertNull(decision.activeProviderId());
        assertTrue(decision.candidates().stream()
                        .anyMatch(candidate -> candidate.providerId().equals("material-manual")
                                && candidate.unavailability() == CandidateUnavailability.TIER_TYPE_MISMATCH),
                "a Manual provider in the PRIMARY tier must be rejected by tier/type mismatch");
    }

    @Test
    void syntheticNeverSilentlyEntersFormalRoute() {
        TestFixture fixture = TestFixture.withProbe(Map.of());
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12,
                List.of("material-synthetic"),
                List.of(),
                List.of());
        MaterialRouteDecision decision = fixture.resolver().resolve(
                config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW);

        assertNull(decision.activeProviderId());
        assertTrue(decision.candidates().stream()
                        .anyMatch(candidate -> candidate.providerId().equals("material-synthetic")
                                && candidate.unavailability() == CandidateUnavailability.SYNTHETIC_NOT_FORMAL),
                "SyntheticDemo must be explicitly excluded from the formal route, never silently active");
    }

    @Test
    void routeDecisionIsFullyTraceable() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.CREDENTIALS_MISSING,
                "am-authorized-api", CandidateUnavailability.CREDENTIALS_MISSING));
        MaterialRouteDecision decision = fixture.resolve(ADC12, DataKind.CURRENT);

        assertEquals("MAT.ADC12.SMM", decision.itemId());
        assertEquals(DataKind.CURRENT, decision.dataKind());
        assertEquals(3, decision.candidates().size());
        for (MaterialRouteDecision.ProviderCandidate candidate : decision.candidates()) {
            assertNotNull(candidate.tier());
            assertNotNull(candidate.unavailability());
        }
        assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, decision.routeDecision());
        assertNotNull(decision.fallbackReason());
        assertEquals(NOW, decision.routeEffectiveAt());
    }

    @Test
    void dynamicProviderRegisteredLaterIsRoutableWithoutCoreChange() {
        TestFixture fixture = TestFixture.withProbe(Map.of(
                "smm-authorized-api", CandidateUnavailability.CREDENTIALS_MISSING));
        DataProvider extra = new RouteTestProvider(
                "extra-free-public", ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
                "test/extra free public", true, false, Set.of(ADC12, AZ91D));
        fixture.registry().register(extra);

        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12, List.of("smm-authorized-api"), List.of("extra-free-public"), List.of());
        MaterialRouteDecision decision = fixture.resolver().resolve(
                config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW);

        assertEquals("extra-free-public", decision.activeProviderId());
        assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, decision.routeDecision());
    }

    @Test
    void pbocOfficialWebIsNeverDrawnIntoMaterialRoutes() {
        TestFixture fixture = TestFixture.withProbe(Map.of());
        fixture.registry().register(new RouteTestProvider(
                "pboc-official-web", ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML,
                "中国人民银行官网（授权中国外汇交易中心公布）", true, false,
                Set.of("FX.USD.CNY.PBOC_MID", "FX.EUR.CNY.PBOC_MID")));
        MaterialRouteConfigV1 config = MaterialRouteConfigV1.of(
                ADC12, List.of("smm-authorized-api"), List.of("material-free-public"), List.of());

        MaterialRouteDecision current = fixture.resolver().resolve(
                config, fixture.registry(), fixture.probe(), DataKind.CURRENT, NOW);
        assertFalse(current.candidates().stream().anyMatch(c -> c.providerId().equals("pboc-official-web")),
                "PBOC must not be a candidate for material targets");
        assertNotNull(current.activeProviderId());

        MaterialRouteDecision history = fixture.resolver().resolve(
                MaterialRouteConfigV1.of(
                        ADC12, List.of("pboc-official-web"), List.of(), List.of()),
                fixture.registry(), fixture.probe(), DataKind.HISTORY, NOW);
        assertNull(history.activeProviderId(),
                "a provider without history capability must never carry a HISTORY material route");
        assertTrue(history.candidates().stream()
                        .anyMatch(candidate -> candidate.providerId().equals("pboc-official-web")
                                && candidate.unavailability() == CandidateUnavailability.CAPABILITY_MISMATCH));
    }

    private static final class TestFixture {
        private final DataProviderRegistry registry;
        private final ApiAuthorizationProbe probe;
        private final MaterialRouteResolver resolver = new MaterialRouteResolver();

        private TestFixture(DataProviderRegistry registry, ApiAuthorizationProbe probe) {
            this.registry = registry;
            this.probe = probe;
        }

        static TestFixture withProbe(Map<String, CandidateUnavailability> states) {
            DataProviderRegistry registry = new DataProviderRegistry();
            registry.register(new RouteTestProvider(
                    "smm-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                    "SMM 授权接口（未配置真实凭证）", true, true, Set.of(ADC12, AZ91D)));
            registry.register(new RouteTestProvider(
                    "am-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                    "Asian Metal 授权接口（未配置真实凭证）", true, true, Set.of(ADC12, AZ91D)));
            registry.register(new RouteTestProvider(
                    "material-official-web-historyless", ProviderType.OFFICIAL_WEB,
                    AccessMethod.PUBLIC_OFFICIAL_HTML,
                    "测试无历史能力的官方网页源", true, false, Set.of(ADC12, AZ91D)));
            registry.register(new RouteTestProvider(
                    "material-free-public", ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
                    "测试同类免费公开材料信源", true, true, Set.of(ADC12, AZ91D)));
            registry.register(new RouteTestProvider(
                    "material-manual", ProviderType.MANUAL, AccessMethod.MANUAL,
                    "测试材料人工录入", true, false, Set.of(ADC12, AZ91D)));
            registry.register(new RouteTestProvider(
                    "material-synthetic", ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO,
                    "测试合成演示源", true, false, Set.of(ADC12, AZ91D)));
            return new TestFixture(registry, probe(states));
        }

        static ApiAuthorizationProbe probe(Map<String, CandidateUnavailability> states) {
            return (providerId, profile) -> {
                CandidateUnavailability explicit = states.get(providerId);
                if (explicit != null) {
                    return Optional.of(explicit);
                }
                return profile.providerType() == ProviderType.AUTHORIZED_API
                        ? Optional.of(CandidateUnavailability.CREDENTIALS_MISSING)
                        : Optional.empty();
            };
        }

        MaterialRouteDecision resolve(String itemId, DataKind dataKind) {
            return resolver.resolve(MaterialRouteConfigV1.of(
                    itemId,
                    List.of("smm-authorized-api", "am-authorized-api"),
                    List.of("material-free-public"),
                    List.of("material-manual")), registry, probe, dataKind, NOW);
        }

        DataProviderRegistry registry() {
            return registry;
        }

        ApiAuthorizationProbe probe() {
            return probe;
        }

        MaterialRouteResolver resolver() {
            return resolver;
        }
    }

    private record RouteTestProvider(
            String providerId,
            ProviderType providerType,
            AccessMethod accessMethod,
            String actualSourceName,
            boolean supportsCurrentData,
            boolean supportsHistoryData,
            Set<String> supportedItemIds
    ) implements DataProvider {
        @Override
        public ProviderSourceProfile profile() {
            return ProviderSourceProfile.of(providerId, providerType, accessMethod,
                    actualSourceName, "https://test.example/source", supportsCurrentData, supportsHistoryData);
        }

        @Override
        public Set<String> supportedItemIds() {
            return supportedItemIds;
        }

        @Override
        public ProviderCollectOutcome collect(ProviderCollectRequest request) {
            java.util.LinkedHashMap<String, String> rejected = new java.util.LinkedHashMap<>();
            for (String itemId : request.itemIds()) {
                if (!supportedItemIds.contains(itemId)) {
                    rejected.put(itemId, "UNSUPPORTED_TARGET");
                }
            }
            return ProviderCollectOutcome.rejectedOnly(providerId, rejected);
        }
    }
}
