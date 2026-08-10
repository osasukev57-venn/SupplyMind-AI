package com.supplymind.routing;

import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderSourceProfile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * D3-T02 three-tier controlled-degradation resolver (DEC-037): 合法指定源自动
 * (PRIMARY: OfficialWeb/AuthorizedApi) -> 同类免费公开信源 (FREE_PUBLIC) -> Manual.
 * Candidates are discovered exclusively through the DataProviderRegistry; every candidate and
 * its final reason is recorded, every downgrade records routeDecision/fallbackReason/actual
 * source. Capability filtering is data-kind aware (current/history). Unsupported targets,
 * unknown configured providers and routes without a legal candidate fail closed.
 */
public final class MaterialRouteResolver {

    private static final Map<RouteTier, List<ProviderType>> TIER_PROVIDER_TYPES = Map.of(
            RouteTier.PRIMARY, List.of(ProviderType.OFFICIAL_WEB, ProviderType.AUTHORIZED_API),
            RouteTier.FREE_PUBLIC, List.of(ProviderType.FREE_PUBLIC),
            RouteTier.MANUAL, List.of(ProviderType.MANUAL));

    public MaterialRouteDecision resolve(
            MaterialRouteConfigV1 config,
            DataProviderRegistry registry,
            ApiAuthorizationProbe authorizationProbe,
            DataKind dataKind,
            OffsetDateTime routeEffectiveAt
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(authorizationProbe, "authorizationProbe");
        Objects.requireNonNull(dataKind, "dataKind");
        Objects.requireNonNull(routeEffectiveAt, "routeEffectiveAt");

        List<MaterialRouteDecision.ProviderCandidate> candidates = new ArrayList<>();
        List<String> skippedReasons = new ArrayList<>();
        String activeProviderId = null;
        RouteTier activeTier = null;

        for (MaterialRouteConfigV1.TierCandidates tier : config.tiers()) {
            for (String providerId : tier.providerIds()) {
                DataProvider provider = registry.find(providerId)
                        .orElseThrow(() -> new ProviderRouteException(
                                "Material route config references an unknown provider: " + providerId));
                ProviderSourceProfile profile = provider.profile();
                CandidateUnavailability unavailability =
                        availability(profile, tier.tier(), dataKind, authorizationProbe.unavailability(
                                providerId, profile).orElse(CandidateUnavailability.AVAILABLE));
                candidates.add(new MaterialRouteDecision.ProviderCandidate(
                        providerId, tier.tier(), unavailability));
                if (unavailability.isAvailable()) {
                    if (activeProviderId == null) {
                        activeProviderId = providerId;
                        activeTier = tier.tier();
                    }
                } else {
                    skippedReasons.add(providerId + "=" + unavailability.wireValue());
                }
            }
            if (activeProviderId != null) {
                break;
            }
        }

        if (activeProviderId == null) {
            return MaterialRouteDecision.unavailable(
                    config.itemId(), dataKind, candidates,
                    skippedReasons.isEmpty() ? "NO_CANDIDATE" : String.join(";", skippedReasons),
                    routeEffectiveAt);
        }
        RouteDecision routeDecision = MaterialRouteDecision.TIER_TO_DECISION.get(activeTier);
        String fallbackReason = activeTier == RouteTier.PRIMARY
                ? null
                : (skippedReasons.isEmpty() ? "NO_PRIMARY_AVAILABLE" : String.join(";", skippedReasons));
        return MaterialRouteDecision.accepted(
                config.itemId(), dataKind, candidates, activeProviderId, routeDecision,
                fallbackReason, routeEffectiveAt);
    }

    /** Fail-closed entry: throws when no legal candidate is available for the target. */
    public MaterialRouteDecision resolveRequired(
            MaterialRouteConfigV1 config,
            DataProviderRegistry registry,
            ApiAuthorizationProbe authorizationProbe,
            DataKind dataKind,
            OffsetDateTime routeEffectiveAt
    ) {
        MaterialRouteDecision decision = resolve(
                config, registry, authorizationProbe, dataKind, routeEffectiveAt);
        if (decision.activeProviderId() == null) {
            throw new ProviderRouteException("No legal material provider for target " + config.itemId()
                    + " (" + dataKind.name() + "): " + decision.fallbackReason());
        }
        return decision;
    }

    private static CandidateUnavailability availability(
            ProviderSourceProfile profile,
            RouteTier tier,
            DataKind dataKind,
            CandidateUnavailability probed
    ) {
        if (profile.providerType() == ProviderType.SYNTHETIC_DEMO) {
            return CandidateUnavailability.SYNTHETIC_NOT_FORMAL;
        }
        if (!TIER_PROVIDER_TYPES.get(tier).contains(profile.providerType())) {
            return CandidateUnavailability.TIER_TYPE_MISMATCH;
        }
        boolean capabilityOk = dataKind == DataKind.CURRENT
                ? profile.supportsCurrentData()
                : profile.supportsHistoryData();
        if (!capabilityOk) {
            return CandidateUnavailability.CAPABILITY_MISMATCH;
        }
        return probed;
    }
}
