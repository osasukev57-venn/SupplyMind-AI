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
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 fail-closed capability attacks: a provider that implements only the basic DataProvider
 * port (identity, itemIds, collect) without declaring generic capability must never be treated
 * as capable. Correct providerType alone is not enough; an explicit generic capability plus
 * legal metadata is required for activation; a failed activation leaves the previous active
 * config untouched.
 */
class ProviderDefaultCapabilityFailClosedTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T02:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime FIXED_AT = OffsetDateTime.parse("2026-08-10T10:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void basicPortOnlyProviderWithoutSupportsOverrideIsRejectedFailClosed() {
        DataRoot root = root("m2 basic port only");
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.TEST, FIXED_AT, java.util.List.of(syntheticAnchor())));

        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(basicPortOnly("m2-basic-port-only", ProviderType.OFFICIAL_WEB));
        ConfigManagementService management = new ConfigManagementService(configs, registry);

        assertThrows(StorageException.class, () -> management.addItem(legalTarget()),
                "a provider that does not override supports() must be BLOCKED (fail-closed), never assumed capable");
        assertEquals(1, management.active().configVersion(),
                "the failed activation must not mutate the active configVersion");
        assertFalse(management.active().items().stream().anyMatch(item -> item.itemId().equals("FX.M2.LEGAL.TARGET")));
    }

    @Test
    void correctProviderTypeWithoutDeclaredCapabilityIsBlocked() {
        DataRoot root = root("m2 type only");
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.TEST, FIXED_AT, java.util.List.of(syntheticAnchor())));

        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(basicPortOnly("m2-type-only", ProviderType.OFFICIAL_WEB));
        registry.register(explicitlyDeclared(ProviderType.MANUAL, item -> false));
        ConfigManagementService management = new ConfigManagementService(configs, registry);

        StorageException failure = assertThrows(StorageException.class, () -> management.addItem(legalTarget()),
                "providerType presence alone must never activate a target");
        assertTrue(failure.getMessage().contains("declares capability"),
                "the failure must name the capability contract: " + failure.getMessage());
        assertEquals(1, management.active().configVersion(),
                "the previous active config must stay fully effective");
    }

    @Test
    void explicitGenericCapabilityWithLegalMetadataIsAllowed() {
        DataRoot root = root("m2 explicit capability");
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        configs.activate(new MonitorSeriesConfigV1("1.0", 1, Mode.TEST, FIXED_AT, java.util.List.of(syntheticAnchor())));

        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(explicitlyDeclared(ProviderType.OFFICIAL_WEB,
                item -> item.providerType() == ProviderType.OFFICIAL_WEB
                        && item.accessMethod() == AccessMethod.PUBLIC_OFFICIAL_HTML
                        && "m2-rate-kind".equals(item.rateKind())));
        ConfigManagementService management = new ConfigManagementService(configs, registry);

        MonitorSeriesConfigV1 active = management.addItem(legalTarget());
        assertEquals(2, active.configVersion());
        assertTrue(active.requireItem("FX.M2.LEGAL.TARGET").enabled(),
                "an explicitly declared generic capability plus legal metadata must be ALLOWED");
    }

    @Test
    void failedActivationKeepsPreviousActiveConfigByteStable() {
        DataRoot root = root("m2 config stability");
        AtomicFileStore files = new AtomicFileStore(root, new DirtyMarkerCodec());
        ConfigActivationStore configs = new ConfigActivationStore(root, files, CLOCK);
        MonitorSeriesConfigV1 initial = new MonitorSeriesConfigV1("1.0", 1, Mode.TEST, FIXED_AT,
                List.of(syntheticAnchor(), legalTarget()));
        configs.activate(initial);

        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(basicPortOnly("m2-stability-provider", ProviderType.MANUAL));
        ConfigManagementService management = new ConfigManagementService(configs, registry);

        assertThrows(StorageException.class,
                () -> management.replaceItem("FX.M2.LEGAL.TARGET", replacementTarget()),
                "an activation that replaces a target with one lacking a capable provider must fail closed");
        MonitorSeriesConfigV1 after = management.active();
        assertEquals(1, after.configVersion());
        assertTrue(after.requireItem("FX.M2.LEGAL.TARGET").enabled(),
                "the old active config must remain exactly as it was after a failed activation");
        assertEquals(2, after.items().size(),
                "a failed replace must not add the replacement item");
    }

    private static MonitorSeriesItemV1 replacementTarget() {
        return new MonitorSeriesItemV1("FX.M2.REPLACEMENT", "M2 replacement", true, "m2-source",
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, "M2 generic source",
                RouteDecision.PRIMARY, null, FIXED_AT, "FX.M2.LEGAL.TARGET", "M2", "m2-field", "m2-rate-kind",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private static DataProvider basicPortOnly(String providerId, ProviderType providerType) {
        return new DataProvider() {
            @Override public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(providerId, providerType,
                        accessMethodFor(providerType), "basic port only fixture",
                        "https://example.test/basic", true, false);
            }
            @Override public Set<String> supportedItemIds() { return Set.of(); }
            @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly(providerId, Map.of());
            }
        };
    }

    private static DataProvider explicitlyDeclared(
            ProviderType providerType, java.util.function.Predicate<MonitorSeriesItemV1> capability) {
        return new DataProvider() {
            @Override public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of("m2-explicit-" + providerType.wireValue(), providerType,
                        accessMethodFor(providerType), "explicit capability fixture",
                        "https://example.test/explicit", true, false);
            }
            @Override public Set<String> supportedItemIds() { return Set.of(); }
            @Override public boolean supports(MonitorSeriesItemV1 item) { return capability.test(item); }
            @Override public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly("m2-explicit-" + providerType.wireValue(), Map.of());
            }
        };
    }

    private static AccessMethod accessMethodFor(ProviderType providerType) {
        return switch (providerType) {
            case MANUAL -> AccessMethod.MANUAL;
            case LOCAL_IMPORT -> AccessMethod.LOCAL_IMPORT;
            case SYNTHETIC_DEMO -> AccessMethod.SYNTHETIC_DEMO;
            default -> AccessMethod.PUBLIC_OFFICIAL_HTML;
        };
    }

    private static MonitorSeriesItemV1 syntheticAnchor() {
        return new MonitorSeriesItemV1("FIXTURE.M2.ANCHOR", "fixture anchor", true, "test",
                ProviderType.SYNTHETIC_DEMO, AccessMethod.SYNTHETIC_DEMO, "test only",
                RouteDecision.SYNTHETIC_DEMO, null, FIXED_AT, null, "anchor", "anchor", "fixture",
                "arithmetic-mean-v1", 2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "fixture", null);
    }

    private static MonitorSeriesItemV1 legalTarget() {
        return new MonitorSeriesItemV1("FX.M2.LEGAL.TARGET", "legal M2 target", true, "m2-source",
                ProviderType.OFFICIAL_WEB, AccessMethod.PUBLIC_OFFICIAL_HTML, "M2 generic source",
                RouteDecision.PRIMARY, null, FIXED_AT, null, "M2", "m2-field", "m2-rate-kind",
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "USD", "CNY/1 USD", null);
    }

    private DataRoot root(String leaf) {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve(leaf));
        AtomicMoveSupport.probeOrFail(root);
        return root;
    }
}
