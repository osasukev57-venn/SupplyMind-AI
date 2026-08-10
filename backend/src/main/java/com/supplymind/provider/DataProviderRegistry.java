package com.supplymind.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D3-T01 provider registry: unique provider identity registration, lookup by id, and
 * capability/target based discovery. A duplicate provider id fails registration closed and an
 * unknown provider lookup fails closed; the registry never silently picks or falls back to
 * another provider. New providers register through {@link #register(DataProvider)} (or Spring
 * bean discovery) without any change to this core class.
 */
public final class DataProviderRegistry {

    private final Map<String, DataProvider> byProviderId = new ConcurrentHashMap<>();

    public void register(DataProvider provider) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(provider.profile(), "provider.profile");
        String providerId = provider.profile().providerId();
        DataProvider previous = byProviderId.putIfAbsent(providerId, provider);
        if (previous != null) {
            throw new ProviderRegistryException("Duplicate provider identity: " + providerId);
        }
    }

    public Optional<DataProvider> find(String providerId) {
        Objects.requireNonNull(providerId, "providerId");
        return Optional.ofNullable(byProviderId.get(providerId));
    }

    public DataProvider require(String providerId) {
        return find(providerId).orElseThrow(() ->
                new ProviderRegistryException("Unknown provider identity: " + providerId));
    }

    public List<DataProvider> all() {
        return new ArrayList<>(byProviderId.values());
    }

    public List<DataProvider> providersForTarget(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        return byProviderId.values().stream()
                .filter(provider -> provider.supportedItemIds().contains(itemId))
                .toList();
    }

    public List<DataProvider> providersWithCurrentData() {
        return byProviderId.values().stream()
                .filter(provider -> provider.profile().supportsCurrentData())
                .toList();
    }

    public List<DataProvider> providersWithHistoryData() {
        return byProviderId.values().stream()
                .filter(provider -> provider.profile().supportsHistoryData())
                .toList();
    }
}
