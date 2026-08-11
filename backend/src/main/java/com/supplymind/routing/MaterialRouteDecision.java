package com.supplymind.routing;

import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.provider.ProviderModelChecks;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * D3-T02 traceable route decision for one target: every candidate provider, its tier and its
 * final availability reason, plus the selected activeProviderId, the frozen routeDecision,
 * the fallbackReason (why earlier tiers were not used) and the conditional acceptance state.
 * A null activeProviderId means no legal candidate was available (fail-closed).
 */
public record MaterialRouteDecision(
        String schemaVersion,
        String itemId,
        DataKind dataKind,
        List<ProviderCandidate> candidates,
        String activeProviderId,
        com.supplymind.foundation.model.RouteDecision routeDecision,
        String fallbackReason,
        OffsetDateTime routeEffectiveAt,
        RouteAcceptance routeAcceptance
) {
    public MaterialRouteDecision {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.identifier(itemId, "itemId");
        Objects.requireNonNull(dataKind, "dataKind");
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        if (activeProviderId != null) {
            ProviderModelChecks.identifier(activeProviderId, "activeProviderId");
            boolean activeIsAvailable = candidates.stream()
                    .anyMatch(candidate -> candidate.providerId().equals(activeProviderId)
                            && candidate.unavailability().isAvailable());
            if (!activeIsAvailable) {
                throw new SchemaValidationException(
                        "activeProviderId must be an AVAILABLE candidate: " + activeProviderId);
            }
        }
        if (routeDecision == null && routeAcceptance != RouteAcceptance.ROUTE_UNAVAILABLE) {
            throw new SchemaValidationException("routeDecision is required unless the route is unavailable");
        }
        Objects.requireNonNull(routeAcceptance, "routeAcceptance");
    }

    public static MaterialRouteDecision unavailable(
            String itemId,
            DataKind dataKind,
            List<ProviderCandidate> candidates,
            String fallbackReason,
            OffsetDateTime routeEffectiveAt
    ) {
        return new MaterialRouteDecision(
                "1.0", itemId, dataKind, candidates, null, null, fallbackReason,
                routeEffectiveAt, RouteAcceptance.ROUTE_UNAVAILABLE);
    }

    public static MaterialRouteDecision accepted(
            String itemId,
            DataKind dataKind,
            List<ProviderCandidate> candidates,
            String activeProviderId,
            com.supplymind.foundation.model.RouteDecision routeDecision,
            String fallbackReason,
            OffsetDateTime routeEffectiveAt
    ) {
        RouteAcceptance acceptance = routeDecision == com.supplymind.foundation.model.RouteDecision.PRIMARY
                ? RouteAcceptance.ROUTE_ACCEPTED
                : RouteAcceptance.ROUTE_CONDITIONAL;
        return new MaterialRouteDecision(
                "1.0", itemId, dataKind, candidates, activeProviderId, routeDecision,
                fallbackReason, routeEffectiveAt, acceptance);
    }

    /** Per-candidate traceability: the tier it belongs to and the final availability reason. */
    public record ProviderCandidate(
            String providerId,
            RouteTier tier,
            CandidateUnavailability unavailability
    ) {
        public ProviderCandidate {
            ProviderModelChecks.identifier(providerId, "providerId");
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(unavailability, "unavailability");
        }
    }

    public static final Map<RouteTier, com.supplymind.foundation.model.RouteDecision> TIER_TO_DECISION = Map.of(
            RouteTier.PRIMARY, com.supplymind.foundation.model.RouteDecision.PRIMARY,
            RouteTier.FREE_PUBLIC, com.supplymind.foundation.model.RouteDecision.FALLBACK_FREE_PUBLIC,
            RouteTier.MANUAL, com.supplymind.foundation.model.RouteDecision.FALLBACK_MANUAL);
}
