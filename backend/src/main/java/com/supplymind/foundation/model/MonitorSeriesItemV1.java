package com.supplymind.foundation.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.RoundingMode;
import java.time.OffsetDateTime;

/** Frozen monitor-series item schema; semantic mutations require a new configVersion. */
@JsonPropertyOrder({
        "itemId", "displayName", "enabled", "sourceIntent", "providerType", "accessMethod", "actualSourceName",
        "routeDecision", "fallbackReason", "routeEffectiveAt", "supersedesItemId", "externalCode",
        "sourceFieldKey", "rateKind", "calculationVersion", "calculationScale", "displayScale", "roundingMode",
        "calendarVersion", "currency", "baseCurrency", "unit"
})
public record MonitorSeriesItemV1(
        String itemId,
        String displayName,
        boolean enabled,
        String sourceIntent,
        ProviderType providerType,
        AccessMethod accessMethod,
        String actualSourceName,
        RouteDecision routeDecision,
        String fallbackReason,
        OffsetDateTime routeEffectiveAt,
        String supersedesItemId,
        String externalCode,
        String sourceFieldKey,
        String rateKind,
        String calculationVersion,
        int calculationScale,
        int displayScale,
        RoundingMode roundingMode,
        String calendarVersion,
        String currency,
        String baseCurrency,
        String unit
) {
    public MonitorSeriesItemV1 {
        ModelRules.id(itemId, "itemId");
        ModelRules.nonBlank(displayName, "displayName");
        ModelRules.nonBlank(sourceIntent, "sourceIntent");
        ModelRules.required(providerType, "providerType");
        ModelRules.required(accessMethod, "accessMethod");
        ModelRules.providerPair(providerType, accessMethod);
        ModelRules.nonBlank(actualSourceName, "actualSourceName");
        ModelRules.required(routeDecision, "routeDecision");
        ModelRules.dateTime(routeEffectiveAt, "routeEffectiveAt");
        ModelRules.nonBlank(externalCode, "externalCode");
        ModelRules.nonBlank(calculationVersion, "calculationVersion");
        ModelRules.nonNegative(calculationScale, "calculationScale");
        ModelRules.nonNegative(displayScale, "displayScale");
        ModelRules.required(roundingMode, "roundingMode");
        ModelRules.nonBlank(calendarVersion, "calendarVersion");
        ModelRules.nonBlank(currency, "currency");
        ModelRules.nonBlank(unit, "unit");

        validateRoute(routeDecision, providerType, fallbackReason);
        if (supersedesItemId != null) {
            ModelRules.id(supersedesItemId, "supersedesItemId");
            if (itemId.equals(supersedesItemId)) {
                throw new SchemaValidationException("supersedesItemId must not equal itemId");
            }
        }
    }

    private static void validateRoute(
            RouteDecision routeDecision,
            ProviderType providerType,
            String fallbackReason
    ) {
        boolean providerMatchesRoute = switch (routeDecision) {
            case PRIMARY -> providerType == ProviderType.OFFICIAL_WEB || providerType == ProviderType.AUTHORIZED_API;
            case FALLBACK_FREE_PUBLIC -> providerType == ProviderType.FREE_PUBLIC;
            case FALLBACK_MANUAL -> providerType == ProviderType.MANUAL;
            case DIRECT_LOCAL_IMPORT -> providerType == ProviderType.LOCAL_IMPORT;
            case SYNTHETIC_DEMO -> providerType == ProviderType.SYNTHETIC_DEMO;
        };
        if (!providerMatchesRoute) {
            throw new SchemaValidationException("routeDecision and providerType are not a frozen legal pair");
        }
        boolean fallback = routeDecision == RouteDecision.FALLBACK_FREE_PUBLIC
                || routeDecision == RouteDecision.FALLBACK_MANUAL;
        if (fallback) {
            ModelRules.nonBlank(fallbackReason, "fallbackReason");
        } else if (fallbackReason != null) {
            throw new SchemaValidationException("fallbackReason must be null for non-fallback routeDecision");
        }
    }
}
