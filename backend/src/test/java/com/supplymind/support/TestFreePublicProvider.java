package com.supplymind.support;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;

import java.util.Set;

/** Capability-only test fixture for the production default ADC12 FreePublic route. */
public final class TestFreePublicProvider {
    private TestFreePublicProvider() {
    }

    public static DataProvider create() {
        return new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of("test-shfe-free-public", ProviderType.FREE_PUBLIC,
                        AccessMethod.FREE_PUBLIC_WEB, "SHFE public benchmark test fixture",
                        "https://www.shfe.com.cn/", true, true);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.of(
                        com.supplymind.foundation.model.MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,
                        com.supplymind.foundation.model.MonitorSeriesDefaults.ADC12_AM_ITEM_ID);
            }

            @Override
            public boolean supports(MonitorSeriesItemV1 item) {
                return item.providerType() == ProviderType.FREE_PUBLIC
                        && item.accessMethod() == AccessMethod.FREE_PUBLIC_WEB
                        && "material".equals(item.rateKind())
                        && "ADC12".equals(item.externalCode());
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                return ProviderCollectOutcome.rejectedOnly(profile().providerId(),
                        request.itemIds().stream().collect(java.util.stream.Collectors.toMap(
                                value -> value, value -> "TEST_CAPABILITY_ONLY")));
            }
        };
    }
}
