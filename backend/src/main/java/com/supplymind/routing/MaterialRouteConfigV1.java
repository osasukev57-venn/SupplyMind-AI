package com.supplymind.routing;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

import java.util.List;
import java.util.Objects;

/**
 * D3-T02 route configuration for one material target: the candidate provider ids per frozen
 * tier (PRIMARY -> FREE_PUBLIC -> MANUAL). Candidate identity comes from the registry; route
 * decisions are configuration-owned and never part of a provider profile.
 */
public record MaterialRouteConfigV1(
        String schemaVersion,
        String itemId,
        List<TierCandidates> tiers
) {
    public MaterialRouteConfigV1 {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.identifier(itemId, "itemId");
        Objects.requireNonNull(tiers, "tiers");
        if (tiers.size() != 3
                || tiers.get(0).tier() != RouteTier.PRIMARY
                || tiers.get(1).tier() != RouteTier.FREE_PUBLIC
                || tiers.get(2).tier() != RouteTier.MANUAL) {
            throw new SchemaValidationException(
                    "Material route tiers must be exactly PRIMARY, FREE_PUBLIC, MANUAL in order");
        }
        tiers = List.copyOf(tiers);
    }

    public static MaterialRouteConfigV1 of(
            String itemId,
            List<String> primaryProviderIds,
            List<String> freePublicProviderIds,
            List<String> manualProviderIds
    ) {
        return new MaterialRouteConfigV1(
                "1.0",
                itemId,
                List.of(
                        new TierCandidates(RouteTier.PRIMARY, primaryProviderIds),
                        new TierCandidates(RouteTier.FREE_PUBLIC, freePublicProviderIds),
                        new TierCandidates(RouteTier.MANUAL, manualProviderIds)));
    }

    public record TierCandidates(RouteTier tier, List<String> providerIds) {
        public TierCandidates {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(providerIds, "providerIds");
            for (String providerId : providerIds) {
                ProviderModelChecks.identifier(providerId, "providerId");
            }
            providerIds = List.copyOf(providerIds);
        }
    }
}
