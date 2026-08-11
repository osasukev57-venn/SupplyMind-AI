package com.supplymind.routing;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.provider.DataProviderRegistry;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * D3-T06 production route planning: the normal startup path derives a DEC-037 three-tier
 * MaterialRouteConfigV1 for every configured monitor-series item purely from the active
 * configuration and the DataProviderRegistry (no hard-coded material if/else, no parallel
 * configuration system). Tiers are populated from registered providers that declare support
 * for the itemId: PRIMARY = OfficialWeb/AuthorizedApi, FREE_PUBLIC = FreePublic, MANUAL =
 * Manual. Items on DIRECT_LOCAL_IMPORT/SYNTHETIC_DEMO routes are outside the three-tier plan
 * and yield an empty result.
 */
public final class MaterialRoutePlanService {

    private final ConfigActivationStore configStore;
    private final DataProviderRegistry registry;
    private final MaterialRouteResolver resolver;
    private final ApiAuthorizationProbe authorizationProbe;
    private final Clock clock;

    public MaterialRoutePlanService(
            ConfigActivationStore configStore,
            DataProviderRegistry registry,
            MaterialRouteResolver resolver,
            ApiAuthorizationProbe authorizationProbe,
            Clock clock
    ) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.authorizationProbe = Objects.requireNonNull(authorizationProbe, "authorizationProbe");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MaterialRouteConfigV1 routeConfigFor(MonitorSeriesItemV1 item) {
        Objects.requireNonNull(item, "item");
        return MaterialRouteConfigV1.of(
                item.itemId(),
                tierProviderIds(item, RouteTier.PRIMARY),
                tierProviderIds(item, RouteTier.FREE_PUBLIC),
                tierProviderIds(item, RouteTier.MANUAL));
    }

    /** Legal route for a configured item, resolved through the production resolver and probe. */
    public Optional<MaterialRouteDecision> resolveFor(String itemId, DataKind dataKind) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(dataKind, "dataKind");
        MonitorSeriesConfigV1 config = configStore.readActiveConfig();
        MonitorSeriesItemV1 item = config.requireItem(itemId);
        if (item.routeDecision() == RouteDecision.DIRECT_LOCAL_IMPORT
                || item.routeDecision() == RouteDecision.SYNTHETIC_DEMO) {
            return Optional.empty();
        }
        return Optional.of(resolver.resolve(
                routeConfigFor(item), registry, authorizationProbe, dataKind,
                OffsetDateTime.now(clock)));
    }

    private List<String> tierProviderIds(MonitorSeriesItemV1 item, RouteTier tier) {
        List<ProviderType> legalTypes = switch (tier) {
            case PRIMARY -> List.of(ProviderType.OFFICIAL_WEB, ProviderType.AUTHORIZED_API);
            case FREE_PUBLIC -> List.of(ProviderType.FREE_PUBLIC);
            case MANUAL -> List.of(ProviderType.MANUAL);
        };
        return registry.providersForTarget(item.itemId()).stream()
                .filter(provider -> legalTypes.contains(provider.profile().providerType()))
                .map(provider -> provider.profile().providerId())
                .sorted()
                .toList();
    }
}
