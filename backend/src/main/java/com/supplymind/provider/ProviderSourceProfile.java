package com.supplymind.provider;

import com.supplymind.foundation.model.AccessMethod;

import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.SchemaV1;

/**
 * D3-T01 source capability model: one immutable, caller-visible description of a
 * DataProvider's identity, legal source/access mode and current/history collection
 * capability. Source identity fields (providerType/accessMethod/actualSourceName/sourceUrl)
 * are fixed at construction and cannot be overridden by callers; route decisions are owned
 * by monitor-series configuration and deliberately have no place in this model.
 */
public record ProviderSourceProfile(
        String schemaVersion,
        String providerId,
        ProviderType providerType,
        AccessMethod accessMethod,
        String actualSourceName,
        String sourceUrl,
        boolean supportsCurrentData,
        boolean supportsHistoryData
) {
    public ProviderSourceProfile {
        ProviderModelChecks.schemaVersion(schemaVersion);
        ProviderModelChecks.identifier(providerId, "providerId");
        ProviderModelChecks.required(providerType, "providerType");
        ProviderModelChecks.required(accessMethod, "accessMethod");
        ProviderModelChecks.providerPair(providerType, accessMethod);
        ProviderModelChecks.nonBlank(actualSourceName, "actualSourceName");
        if (sourceUrl != null) {
            ProviderModelChecks.httpUrl(sourceUrl, "sourceUrl");
        }
    }

    public static ProviderSourceProfile of(
            String providerId,
            ProviderType providerType,
            AccessMethod accessMethod,
            String actualSourceName,
            String sourceUrl,
            boolean supportsCurrentData,
            boolean supportsHistoryData
    ) {
        return new ProviderSourceProfile(SchemaV1.VERSION, providerId, providerType, accessMethod,
                actualSourceName, sourceUrl, supportsCurrentData, supportsHistoryData);
    }
}
