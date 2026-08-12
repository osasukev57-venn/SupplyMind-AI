package com.supplymind.config;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.provider.DataProviderRegistry;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D5-T03 dynamic monitor-series configuration (H07/H09). ADD/ENABLE/DISABLE/REPLACE are
 * implemented strictly on top of the existing ConfigActivationStore: every mutation builds the
 * next configVersion and goes through the frozen +1/history/manifest atomic activation. No
 * second config store exists. Full dependency validation runs BEFORE activation; any failure
 * leaves the previous active config untouched. New targets never require Java code changes -
 * everything is driven by monitor-series items, the provider registry, route metadata,
 * rateKind, enabled and supersedesItemId.
 */
public final class ConfigManagementService {

    private final ConfigActivationStore configActivationStore;
    private final DataProviderRegistry registry;

    public ConfigManagementService(ConfigActivationStore configActivationStore, DataProviderRegistry registry) {
        this.configActivationStore = Objects.requireNonNull(configActivationStore, "configActivationStore");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public MonitorSeriesConfigV1 active() {
        return configActivationStore.readActiveConfig();
    }

    /** ADD: a brand-new target with its own stable itemId. */
    public MonitorSeriesConfigV1 addItem(MonitorSeriesItemV1 newItem) {
        Objects.requireNonNull(newItem, "newItem");
        MonitorSeriesConfigV1 current = active();
        if (current.items().stream().anyMatch(item -> item.itemId().equals(newItem.itemId()))) {
            throw new StorageException("itemId already configured: " + newItem.itemId());
        }
        List<MonitorSeriesItemV1> items = new ArrayList<>(current.items());
        items.add(newItem);
        return activate(new MonitorSeriesConfigV1(
                current.schemaVersion(), current.configVersion() + 1, current.mode(),
                now(), items));
    }

    /** ENABLE/DISABLE: only toggles enabled; raw/timeline/daily/aggregate/history stay untouched. */
    public MonitorSeriesConfigV1 setEnabled(String itemId, boolean enabled) {
        Objects.requireNonNull(itemId, "itemId");
        MonitorSeriesConfigV1 current = active();
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        boolean found = false;
        for (MonitorSeriesItemV1 item : current.items()) {
            if (item.itemId().equals(itemId)) {
                items.add(withEnabled(item, enabled));
                found = true;
            } else {
                items.add(item);
            }
        }
        if (!found) {
            throw new StorageException("itemId is not configured: " + itemId);
        }
        return activate(new MonitorSeriesConfigV1(
                current.schemaVersion(), current.configVersion() + 1, current.mode(),
                now(), items));
    }

    /**
     * REPLACE: the old item is disabled (never deleted, history preserved) and the new item
     * gets an independent itemId with supersedesItemId=oldItemId - the new sequence never
     * masquerades as the old one and the old config snapshots are never overwritten.
     */
    public MonitorSeriesConfigV1 replaceItem(String oldItemId, MonitorSeriesItemV1 replacement) {
        Objects.requireNonNull(oldItemId, "oldItemId");
        Objects.requireNonNull(replacement, "replacement");
        if (!replacement.supersedesItemId().equals(oldItemId)) {
            throw new StorageException("replacement.supersedesItemId must equal the replaced itemId: " + oldItemId);
        }
        MonitorSeriesConfigV1 current = active();
        List<MonitorSeriesItemV1> items = new ArrayList<>();
        boolean found = false;
        for (MonitorSeriesItemV1 item : current.items()) {
            if (item.itemId().equals(oldItemId)) {
                items.add(withEnabled(item, false));
                found = true;
            } else {
                items.add(item);
            }
        }
        if (!found) {
            throw new StorageException("old itemId is not configured: " + oldItemId);
        }
        items.add(replacement);
        return activate(new MonitorSeriesConfigV1(
                current.schemaVersion(), current.configVersion() + 1, current.mode(),
                now(), items));
    }

    /** Dependency validation + frozen activation; any failure keeps the old active config. */
    private MonitorSeriesConfigV1 activate(MonitorSeriesConfigV1 next) {
        validate(next);
        configActivationStore.activate(next);
        return active();
    }

    private void validate(MonitorSeriesConfigV1 config) {
        for (MonitorSeriesItemV1 item : config.items()) {
            if (!item.enabled()) {
                continue;
            }
            // A material item must carry its DEC-059 validation config (enforced by the model
            // constructor too; this is a defensive re-check before the whole config activates).
            if ("material".equals(item.rateKind()) && item.materialValidation() == null) {
                throw new StorageException("material item lacks materialValidation config: " + item.itemId());
            }
            if (item.routeDecision() == RouteDecision.SYNTHETIC_DEMO) {
                continue;
            }
            boolean providerRegistered = registry.all().stream()
                    .anyMatch(provider -> provider.profile().providerType() == item.providerType());
            if (!providerRegistered) {
                throw new StorageException(
                        "no registered provider of type " + item.providerType() + " for item " + item.itemId());
            }
        }
    }

    private static MonitorSeriesItemV1 withEnabled(MonitorSeriesItemV1 item, boolean enabled) {
        return new MonitorSeriesItemV1(
                item.itemId(), item.displayName(), enabled, item.sourceIntent(), item.providerType(),
                item.accessMethod(), item.actualSourceName(), item.routeDecision(), item.fallbackReason(),
                item.routeEffectiveAt(), item.supersedesItemId(), item.externalCode(), item.sourceFieldKey(),
                item.rateKind(), item.calculationVersion(), item.calculationScale(), item.displayScale(),
                item.roundingMode(), item.calendarVersion(), item.currency(), item.baseCurrency(), item.unit(),
                item.materialValidation());
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
