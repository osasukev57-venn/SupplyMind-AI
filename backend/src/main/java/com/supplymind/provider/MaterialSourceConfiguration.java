package com.supplymind.provider;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.ProviderType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D3-T02 production delivery: the specified commercial source intents (SMM, Asian Metal) are
 * registered as AuthorizedApi DataProviders that are honest about their runtime state. No
 * credentials are configured in this repository, so their capability probe reports
 * NOT_CONFIGURED and collect() rejects every target explicitly; they never access, bypass or
 * fabricate anything. The supported item sets are plain declarations (SMM intent items vs
 * Asian Metal intent items), not service-level if/else routing.
 */
@Configuration
public class MaterialSourceConfiguration {

    public static final String SMM_PROVIDER_ID = "smm-authorized-api";
    public static final String AM_PROVIDER_ID = "am-authorized-api";

    private static final Set<String> SMM_INTENT_ITEMS = Set.of(
            MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID);
    private static final Set<String> AM_INTENT_ITEMS = Set.of(
            MonitorSeriesDefaults.ADC12_AM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID);

    @Bean
    DataProvider smmAuthorizedApiProvider() {
        return notConfiguredAuthorizedApi(SMM_PROVIDER_ID, SMM_INTENT_ITEMS);
    }

    @Bean
    DataProvider amAuthorizedApiProvider() {
        return notConfiguredAuthorizedApi(AM_PROVIDER_ID, AM_INTENT_ITEMS);
    }

    private static DataProvider notConfiguredAuthorizedApi(String providerId, Set<String> itemIds) {
        return new DataProvider() {
            @Override
            public ProviderSourceProfile profile() {
                return ProviderSourceProfile.of(
                        providerId, ProviderType.AUTHORIZED_API, AccessMethod.AUTHORIZED_API,
                        providerId + "（未配置真实凭证）", null, true, false);
            }

            @Override
            public Set<String> supportedItemIds() {
                return Set.copyOf(itemIds);
            }

            /**
             * F1 explicit capability contract: this provider is a placeholder with NO configured
             * credentials/authorization, so it explicitly declares capability=false (fail-closed)
             * instead of inheriting the interface default. The declaration is based on its
             * profile semantics - AuthorizedApi access with no real acquisition capability -
             * never on itemId strings or ADC12/AZ91D hard-coding. Once real credentials are
             * configured, this contract must be re-declared from generic metadata
             * (providerType/accessMethod/rateKind/source intent).
             */
            @Override
            public boolean supports(com.supplymind.foundation.model.MonitorSeriesItemV1 item) {
                return false;
            }

            @Override
            public ProviderCollectOutcome collect(ProviderCollectRequest request) {
                Map<String, String> rejected = new LinkedHashMap<>();
                for (String itemId : request.itemIds()) {
                    rejected.put(itemId, "NOT_CONFIGURED");
                }
                return ProviderCollectOutcome.rejectedOnly(providerId, rejected);
            }
        };
    }

    public static List<String> intentItemIds() {
        return List.of(
                MonitorSeriesDefaults.ADC12_SMM_ITEM_ID, MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID,
                MonitorSeriesDefaults.ADC12_AM_ITEM_ID, MonitorSeriesDefaults.AZ91D_AM_ITEM_ID);
    }
}
