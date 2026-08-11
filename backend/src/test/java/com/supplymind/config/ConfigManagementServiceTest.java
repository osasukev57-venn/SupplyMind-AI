package com.supplymind.config;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MaterialValidationConfigV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.manual.ManualDataProvider;
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
 * D5-T03 dynamic monitor configuration (AT-CFG-001/002/004 + H07/H09 backend): ADD, ENABLE,
 * DISABLE and REPLACE go through the frozen +1 activation; validation failures keep the old
 * active config; restart keeps the active config; disabling never deletes history and
 * disabled items remain queryable by history-query.
 */
class ConfigManagementServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-11T10:00:00+08:00");

    @TempDir
    Path temporaryDirectory;

    @Test
    void addGbpH07NoJavaCodeChangeRequired() {
        Harness harness = harness();
        MonitorSeriesItemV1 gbp = manualItem("FX.GBP.CNY.PBOC_MID", "英镑/人民币中间价", "GBP", "1英镑对人民币", "CNY/1 GBP", "FX");
        MonitorSeriesConfigV1 next = harness.management().addItem(gbp);
        assertEquals(2, next.configVersion());
        assertEquals(true, next.requireItem("FX.GBP.CNY.PBOC_MID").enabled());
        assertEquals(7, next.items().size(), "2 PBOC + 4 P0 material + 1 GBP");
        assertTrue(harness.management().active().configVersion() == 2,
                "H07: a new target is pure configuration, no Java business code involved");
    }

    @Test
    void disableEuroKeepsHistoryAndDisablesWithoutDeletion() {
        Harness harness = harness();
        harness.management().setEnabled(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, false);
        MonitorSeriesConfigV1 active = harness.management().active();
        assertFalse(active.requireItem(MonitorSeriesDefaults.EUR_CNY_ITEM_ID).enabled());
        assertTrue(active.requireItem(MonitorSeriesDefaults.USD_CNY_ITEM_ID).enabled());
        assertEquals(2, active.configVersion());
        assertTrue(harness.historyConfigVersions() >= 2,
                "each activation writes a CREATE_NEW immutable history snapshot; nothing is deleted");
        harness.management().setEnabled(MonitorSeriesDefaults.EUR_CNY_ITEM_ID, true);
        assertTrue(harness.management().active().requireItem(MonitorSeriesDefaults.EUR_CNY_ITEM_ID).enabled(),
                "H09: show again is another config activation, history still intact");
    }

    @Test
    void replaceAz91dCreatesIndependentItemAndDisablesOldOne() {
        Harness harness = harness();
        String oldId = MonitorSeriesDefaults.AZ91D_AM_ITEM_ID;
        MonitorSeriesItemV1 replacement = manualItem(
                "MAT.REPL-01.AM", "AZ91D替代材料（Asian Metal意图）", "AZ91D", "material-field-key", "元/吨", "material");
        MonitorSeriesItemV1 withSupersede = new MonitorSeriesItemV1(
                replacement.itemId(), replacement.displayName(), true, replacement.sourceIntent(),
                replacement.providerType(), replacement.accessMethod(), replacement.actualSourceName(),
                replacement.routeDecision(), replacement.fallbackReason(), replacement.routeEffectiveAt(),
                oldId, replacement.externalCode(), replacement.sourceFieldKey(), replacement.rateKind(),
                replacement.calculationVersion(), replacement.calculationScale(), replacement.displayScale(),
                replacement.roundingMode(), replacement.calendarVersion(), replacement.currency(),
                replacement.baseCurrency(), replacement.unit(), replacement.materialValidation());
        MonitorSeriesConfigV1 next = harness.management().replaceItem(oldId, withSupersede);
        assertFalse(next.requireItem(oldId).enabled(), "the old sequence is disabled, never deleted");
        MonitorSeriesItemV1 replaced = next.requireItem(withSupersede.itemId());
        assertEquals(oldId, replaced.supersedesItemId(),
                "the new item gets an independent itemId and links via supersedesItemId");
        assertFalse(replaced.itemId().equals(oldId), "the new sequence never masquerades as the old one");
    }

    @Test
    void validationFailureLeavesOldActiveConfigUntouched() {
        Harness harness = harness();
        MonitorSeriesItemV1 bogus = new MonitorSeriesItemV1(
                "MAT.BOGUS.FREEPUBLIC", "bogus", true, "SMM", ProviderType.FREE_PUBLIC,
                AccessMethod.FREE_PUBLIC_WEB, "bogus", RouteDecision.FALLBACK_FREE_PUBLIC,
                "FREE_PUBLIC_FALLBACK", NOW, null, "ADC12", "material-field-key", "material",
                "arithmetic-mean-v1", 2, 2, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", "元/吨",
                new MaterialValidationConfigV1("0", null, 7, "ADC12", List.of()));
        assertThrows(com.supplymind.foundation.storage.StorageException.class,
                () -> harness.management().addItem(bogus),
                "no registered FREE_PUBLIC provider must reject the activation");
        assertEquals(1, harness.management().active().configVersion(),
                "a failed activation leaves the previous active config fully effective");
    }

    @Test
    void restartKeepsActiveConfigAndImmutableHistory() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("config restart root"));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T02:00:00Z"), ZoneOffset.UTC);
        ConfigActivationStore activation = new ConfigActivationStore(root, fileStore, clock);
        activation.ensureInitialDefault();
        ConfigManagementService first = new ConfigManagementService(activation, registry());
        first.setEnabled(MonitorSeriesDefaults.USD_CNY_ITEM_ID, false);
        assertEquals(2, first.active().configVersion());

        ConfigManagementService restarted = new ConfigManagementService(
                new ConfigActivationStore(root, new AtomicFileStore(root, new DirtyMarkerCodec()), clock), registry());
        assertEquals(2, restarted.active().configVersion(), "restart keeps the active configuration");
        assertFalse(restarted.active().requireItem(MonitorSeriesDefaults.USD_CNY_ITEM_ID).enabled());
    }

    private Harness harness() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("config root " + System.nanoTime()));
        AtomicMoveSupport.probeOrFail(root);
        AtomicFileStore fileStore = new AtomicFileStore(root, new DirtyMarkerCodec());
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T02:00:00Z"), ZoneOffset.UTC);
        ConfigActivationStore activation = new ConfigActivationStore(root, fileStore, clock);
        activation.ensureInitialDefault();
        ConfigManagementService management = new ConfigManagementService(activation, registry());
        return new Harness(root, management);
    }

    private static DataProviderRegistry registry() {
        DataProviderRegistry registry = new DataProviderRegistry();
        registry.register(new ManualDataProvider(() -> Set.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.ADC12_AM_ITEM_ID,
                MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID)));
        registry.register(new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of("pboc-official-web", ProviderType.OFFICIAL_WEB,
                        AccessMethod.PUBLIC_OFFICIAL_HTML, "中国人民银行官网（授权中国外汇交易中心公布）",
                        "https://www.pbc.gov.cn/zhengcehuobisi/125207/125217/125925/index.html", true, false);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of(MonitorSeriesDefaults.USD_CNY_ITEM_ID, MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly("pboc-official-web", Map.of());
            }
        });
        return registry;
    }

    private static MonitorSeriesItemV1 manualItem(
            String itemId, String displayName, String externalCode, String sourceFieldKey,
            String unit, String rateKind
    ) {
        return new MonitorSeriesItemV1(
                itemId, displayName, true, "PBOC", ProviderType.MANUAL, AccessMethod.MANUAL,
                "人工录入（Manual）", RouteDecision.FALLBACK_MANUAL, "MANUAL_FALLBACK", NOW, null,
                externalCode, sourceFieldKey, rateKind,
                "arithmetic-mean-v1", 8, 4, java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1",
                "CNY", "CNY", unit,
                "material".equals(rateKind)
                        ? new MaterialValidationConfigV1("0", null, 7, externalCode, List.of())
                        : null);
    }

    private record Harness(DataRoot root, ConfigManagementService management) {
        int historyConfigVersions() {
            int count = 0;
            try (var stream = java.nio.file.Files.list(root.resolveInternalRelative("config/history"))) {
                count = (int) stream.filter(path -> path.getFileName().toString().endsWith(".json")
                                && !path.getFileName().toString().endsWith(".manifest.json"))
                        .count();
            } catch (java.io.IOException exception) {
                throw new RuntimeException(exception);
            }
            return count;
        }
    }
}
