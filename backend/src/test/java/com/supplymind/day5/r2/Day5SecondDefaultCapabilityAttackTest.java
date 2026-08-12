package com.supplymind.day5.r2;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.localimport.LocalImportDataProvider;
import com.supplymind.localimport.SyntheticDemoDataProvider;
import com.supplymind.manual.ManualDataProvider;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.MaterialSourceConfiguration;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent post-fix A2 attack.  It deliberately leaves {@link DataProvider#supports}
 * unimplemented on the minimal fixture, then checks both activation and every formal production
 * provider declaration.  It must fail if a production provider falls back to the interface
 * default rather than explicitly declaring its capability.
 */
class Day5SecondDefaultCapabilityAttackTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-12T10:00:00+08:00");

    @TempDir
    Path temp;

    @Test
    void unspecifiedProviderCapabilityIsBlockedAndExplicitGenericCapabilityIsAllowed() {
        DataRoot root = root("default-capability");
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.TEST, AT, List.of(anchor())));

        DataProvider unspecified = unspecifiedProvider("a2-unspecified");
        assertFalse(unspecified.supports(target()), "the interface default must fail closed");
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(unspecified);
        ConfigManagementService management = new ConfigManagementService(configs, registry);
        assertThrows(StorageException.class, () -> management.addItem(target()),
                "correct providerType without an explicit capability must be blocked");
        assertEquals(1, management.active().configVersion(), "failed activation must preserve active config");

        registry.register(explicitGenericProvider("a2-explicit-generic"));
        MonitorSeriesConfigV1 active = management.addItem(target());
        assertEquals(2, active.configVersion());
        assertTrue(active.requireItem(target().itemId()).enabled());
    }

    @Test
    void everyFormalProductionProviderExplicitlyOverridesSupports() throws Exception {
        List<String> inheritedDefaults = new ArrayList<>();
        for (Class<? extends DataProvider> type : List.of(
                PbocOfficialWebDataProvider.class,
                ManualDataProvider.class,
                LocalImportDataProvider.class,
                SyntheticDemoDataProvider.class)) {
            collectInheritedDefault(type, type.getName(), inheritedDefaults);
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(MaterialSourceConfiguration.class)) {
            for (Map.Entry<String, DataProvider> entry : context.getBeansOfType(DataProvider.class).entrySet()) {
                collectInheritedDefault(entry.getValue().getClass(), entry.getKey(), inheritedDefaults);
            }
        }
        assertTrue(inheritedDefaults.isEmpty(),
                "formal providers must explicitly declare supports(item), not inherit fail-closed default: " + inheritedDefaults);
    }

    private static void collectInheritedDefault(Class<?> type, String label, List<String> inheritedDefaults)
            throws Exception {
        if (type.getMethod("supports", MonitorSeriesItemV1.class).getDeclaringClass() == DataProvider.class) {
            inheritedDefaults.add(label);
        }
    }


    private static DataProvider unspecifiedProvider(String providerId) {
        return new DataProvider() {
            @Override public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(providerId, ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "test-only provider", "https://example.test/a2", true, false);
            }
            @Override public Set<String> supportedItemIds() { return Set.of(); }
            @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly(providerId, Map.of());
            }
        };
    }

    private static DataProvider explicitGenericProvider(String providerId) {
        return new DataProvider() {
            @Override public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(providerId, ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "test-only provider", "https://example.test/a2", true, false);
            }
            @Override public Set<String> supportedItemIds() { return Set.of(); }
            @Override public boolean supports(MonitorSeriesItemV1 item) {
                return item.providerType() == ProviderType.OFFICIAL_WEB && "a2-generic".equals(item.rateKind());
            }
            @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly(providerId, Map.of());
            }
        };
    }

    private static MonitorSeriesItemV1 anchor() {
        return new MonitorSeriesItemV1("FIXTURE.A2.ANCHOR", "fixture", true, "test",
                ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO, "test only",
                RouteDecision.SYNTHETIC_DEMO, null, AT, null, "fixture", "fixture", "fixture",
                "arithmetic-mean-v1", 2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "fixture", null);
    }

    private static MonitorSeriesItemV1 target() {
        return new MonitorSeriesItemV1("FX.A2.GENERIC", "generic target", true, "a2",
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, "A2 source",
                RouteDecision.PRIMARY, null, AT, null, "USD", "1 USD/CNY", "a2-generic",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private DataRoot root(String leaf) {
        DataRoot root = DataRoot.forTest(temp.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        return root;
    }
}
