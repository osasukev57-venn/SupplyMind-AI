package com.supplymind.provider;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.SchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3-T01 contract tests: the six frozen provider categories are expressed through one unified
 * port, the registry discovers providers and fails closed on duplicate/unknown identities,
 * capability and target queries behave correctly, unsupported targets are rejected explicitly,
 * source identity fields are not overridable, and adding a provider never changes the registry
 * core.
 */
class DataProviderRegistryTest {

    @Test
    void sixFrozenProviderCategoriesAreExpressibleThroughTheUnifiedPort() {
        List<DataProvider> six = SixTestProviders.all();
        assertEquals(6, six.size());
        Set<String> frozenWireTypes = Set.of("official_web", "authorized_api", "free_public",
                "manual", "local_import", "synthetic_demo");
        for (DataProvider provider : six) {
            ProviderSourceProfile profile = provider.profile();
            assertTrue(frozenWireTypes.contains(profile.providerType().wireValue()),
                    "unexpected providerType wire value: " + profile.providerType().wireValue());
            assertNotNull(profile.actualSourceName());
            assertNotNull(provider.supportedItemIds());
            assertTrue(!profile.supportsCurrentData() || !profile.supportsHistoryData()
                            || profile.supportsCurrentData(),
                    "capability flags must be expressible");
        }
        assertEquals(frozenWireTypes,
                SixTestProviders.all().stream()
                        .map(provider -> provider.profile().providerType().wireValue())
                        .collect(java.util.stream.Collectors.toSet()),
                "all six frozen providerType wire values must be covered by test dummies");
    }

    @Test
    void registryDiscoversRegisteredProvidersAndQueriesByCapabilityAndTarget() {
        DataProviderRegistry registry = new DataProviderRegistry();
        for (DataProvider provider : SixTestProviders.all()) {
            registry.register(provider);
        }
        assertEquals(6, registry.all().size());

        DataProvider manual = registry.require("test-manual");
        assertEquals(ProviderType.MANUAL, manual.profile().providerType());
        assertEquals(AccessMethod.MANUAL, manual.profile().accessMethod());
        assertEquals("test-manual", manual.profile().providerId());

        assertEquals(List.of("test-local-import"),
                registry.providersForTarget("TEST.LOCAL.IMPORTED").stream()
                        .map(provider -> provider.profile().providerId()).toList());
        assertEquals(Set.of("test-official-web", "test-free-public", "test-manual", "test-synthetic-demo"),
                Set.copyOf(registry.providersWithCurrentData().stream()
                        .map(provider -> provider.profile().providerId()).toList()));
        assertEquals(Set.of("test-authorized-api", "test-local-import"),
                Set.copyOf(registry.providersWithHistoryData().stream()
                        .map(provider -> provider.profile().providerId()).toList()));
    }

    @Test
    void duplicateProviderIdentityFailsClosed() {
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(SixTestProviders.manual());
        assertThrows(ProviderRegistryException.class,
                () -> registry.register(SixTestProviders.manual()),
                "a duplicate provider identity must fail registration closed");
        assertThrows(ProviderRegistryException.class,
                () -> registry.register(SixTestProviders.manualWithDifferentImplementation()),
                "the same identity from another implementation must also be rejected");
    }

    @Test
    void unknownProviderFailsClosed() {
        DataProviderRegistry registry = new DataProviderRegistry();
        assertThrows(ProviderRegistryException.class, () -> registry.require("no-such-provider"));
        assertFalse(registry.find("no-such-provider").isPresent());
        assertTrue(registry.find("test-official-web").isEmpty());
    }

    @Test
    void unsupportedTargetsAreExplicitlyRejectedNeverSilentlySkipped() {
        DataProvider manual = SixTestProviders.manual();
        ProviderCollectOutcome outcome = manual.collect(new ProviderCollectRequest(
                List.of("FX.USD.CNY.PBOC_MID", "MAT.ADC12.SMM")));
        assertEquals(Map.of("MAT.ADC12.SMM", "UNSUPPORTED_TARGET"), outcome.rejectedItemIds());
        assertTrue(outcome.raws().isEmpty(), "rejected targets must not produce raws");

        ProviderCollectOutcome allRejected = manual.collect(new ProviderCollectRequest(List.of("MAT.AZ91D.X")));
        assertEquals(1, allRejected.rejectedItemIds().size());
        assertTrue(allRejected.raws().isEmpty());
        assertEquals("test-manual", allRejected.providerId());
    }

    @Test
    void sourceIdentityFieldsAreNotOverridableAndPairsAreFrozen() {
        ProviderSourceProfile profile = SixTestProviders.freePublic().profile();
        assertEquals("test-free-public", profile.providerId());
        assertEquals(ProviderType.FREE_PUBLIC, profile.providerType());
        assertEquals(AccessMethod.FREE_PUBLIC_WEB, profile.accessMethod());
        assertEquals("test/free public source fixture", profile.actualSourceName());

        assertThrows(SchemaValidationException.class,
                () -> ProviderSourceProfile.of("test-forged", ProviderType.FREE_PUBLIC,
                        AccessMethod.AUTHORIZED_API, "forged source name", null, true, false),
                "a mismatched providerType/accessMethod pair must be rejected (no source impersonation)");
        assertThrows(SchemaValidationException.class,
                () -> ProviderSourceProfile.of("test-forged", ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "", null, true, false),
                "an empty source name must be rejected");

        assertFalse(Arrays.stream(ProviderSourceProfile.class.getRecordComponents())
                        .anyMatch(component -> component.getName().equals("routeDecision")),
                "route decisions are owned by monitor-series configuration, never by the provider model");
    }

    @Test
    void addingANewProviderNeverChangesTheRegistryCore() {
        DataProviderRegistry registry = new DataProviderRegistry();
        for (DataProvider provider : SixTestProviders.all()) {
            registry.register(provider);
        }
        DataProvider seventh = new TestDataProvider(
                "test-extra-provider", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                "test/extra authorized fixture", true, true, Set.of("TEST.EXTRA.001"));
        registry.register(seventh);
        assertEquals(7, registry.all().size());
        assertEquals(seventh, registry.require("test-extra-provider"));
        assertEquals(List.of("test-extra-provider"),
                registry.providersForTarget("TEST.EXTRA.001").stream()
                        .map(provider -> provider.profile().providerId()).toList());
    }

    @Test
    void collectRequestRequiresAtLeastOneTarget() {
        assertThrows(SchemaValidationException.class, () -> new ProviderCollectRequest(List.of()));
        assertThrows(SchemaValidationException.class, () -> new ProviderCollectRequest(null));
    }

    private static final class SixTestProviders {

        private SixTestProviders() {
        }

        static List<DataProvider> all() {
            return List.of(
                    officialWeb(), authorizedApi(), freePublic(), manual(), localImport(), syntheticDemo());
        }

        static DataProvider manual() {
            return new TestDataProvider(
                    "test-manual", ProviderType.MANUAL, AccessMethod.MANUAL,
                    "test/manual operator fixture", true, false, Set.of("FX.USD.CNY.PBOC_MID"));
        }

        static DataProvider manualWithDifferentImplementation() {
            return new TestDataProvider(
                    "test-manual", ProviderType.MANUAL, AccessMethod.MANUAL,
                    "test/manual operator fixture", true, false, Set.of("FX.USD.CNY.PBOC_MID"));
        }

        static DataProvider freePublic() {
            return new TestDataProvider(
                    "test-free-public", ProviderType.FREE_PUBLIC, AccessMethod.FREE_PUBLIC_WEB,
                    "test/free public source fixture", true, false, Set.of("FX.USD.CNY.PBOC_MID"));
        }

        private static DataProvider officialWeb() {
            return new TestDataProvider(
                    "test-official-web", ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML,
                    "test/official web fixture", true, false, Set.of("FX.USD.CNY.PBOC_MID"));
        }

        private static DataProvider authorizedApi() {
            return new TestDataProvider(
                    "test-authorized-api", ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                    "test/authorized api fixture", false, true, Set.of("TEST.AUTH.001"));
        }

        private static DataProvider localImport() {
            return new TestDataProvider(
                    "test-local-import", ProviderType.LOCAL_IMPORT, AccessMethod.LOCAL_IMPORT,
                    "test/local import fixture", false, true, Set.of("TEST.LOCAL.IMPORTED"));
        }

        private static DataProvider syntheticDemo() {
            return new TestDataProvider(
                    "test-synthetic-demo", ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO,
                    "test/synthetic demo fixture", true, false, Set.of("TEST.SYNTH.001"));
        }
    }

    private record TestDataProvider(
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
