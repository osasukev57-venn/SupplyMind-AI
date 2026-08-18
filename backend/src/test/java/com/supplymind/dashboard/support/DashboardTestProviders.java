package com.supplymind.dashboard.support;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;

import java.util.List;
import java.util.Set;

/**
 * D7 test-only providers: declare generic capability for the provider types the fixture config
 * contains (OFFICIAL_WEB PBOC defaults, MANUAL and LOCAL_IMPORT intake items) so the
 * ConfigManagementService capability validate passes. They never collect anything.
 */
public final class DashboardTestProviders {

    private DashboardTestProviders() {
    }

    public static DataProvider forType(ProviderType type, String providerId) {
        AccessMethod accessMethod = switch (type) {
            case OFFICIAL_WEB -> AccessMethod.PUBLIC_OFFICIAL_HTML;
            case MANUAL -> AccessMethod.MANUAL;
            case LOCAL_IMPORT -> AccessMethod.LOCAL_IMPORT;
            default -> AccessMethod.SYNTHETIC_DEMO;
        };
        return new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(
                        providerId, type, accessMethod,
                        "test provider " + type.wireValue(), null, true, false);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of();
            }

            @Override
            public boolean supports(MonitorSeriesItemV1 item) {
                return item.providerType() == type;
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return new ProviderCollectOutcome(
                        "1.0", providerId, null, null, null,
                        List.of(), List.of(), java.util.Map.of());
            }
        };
    }
}
