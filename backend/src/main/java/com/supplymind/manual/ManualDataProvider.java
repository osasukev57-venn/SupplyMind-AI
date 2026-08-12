package com.supplymind.manual;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * D3-T04 Manual material provider as a standard DataProvider (D3-T01 port). Manual data is
 * never collected from a source: it arrives through the controlled intake service. This
 * provider exists for registry discovery, route resolution (MANUAL tier) and honest source
 * identity; collect() therefore rejects all targets explicitly with MANUAL_INTAKE_REQUIRED
 * and never fabricates data.
 */
public final class ManualDataProvider implements DataProvider {

    public static final String PROVIDER_ID = "manual-material";
    private static final String SOURCE_NAME = "人工录入（Manual）";

    private final Supplier<Set<String>> supportedItemIdsSupplier;

    public ManualDataProvider(Supplier<Set<String>> supportedItemIdsSupplier) {
        this.supportedItemIdsSupplier = Objects.requireNonNull(supportedItemIdsSupplier, "supportedItemIdsSupplier");
    }

    @Override
    public ProviderSourceProfile profile() {
        return ProviderSourceProfile.of(
                PROVIDER_ID,
                ProviderType.MANUAL,
                AccessMethod.MANUAL,
                SOURCE_NAME,
                null,
                true,
                false);
    }

    @Override
    public Set<String> supportedItemIds() {
        return Set.copyOf(supportedItemIdsSupplier.get());
    }

    /**
     * D5-T03/F3 capability: the Manual provider handles material Manual-route targets only
     * (rateKind material, MANUAL acquisition). Configuration activation rejects anything else
     * instead of pretending success.
     */
    @Override
    public boolean supports(com.supplymind.foundation.model.MonitorSeriesItemV1 item) {
        return item.providerType() == ProviderType.MANUAL
                && item.accessMethod() == AccessMethod.MANUAL
                && "material".equals(item.rateKind());
    }

    @Override
    public ProviderCollectOutcome collect(ProviderCollectRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String itemId : request.itemIds()) {
            rejected.put(itemId, "MANUAL_INTAKE_REQUIRED");
        }
        return ProviderCollectOutcome.rejectedOnly(PROVIDER_ID, rejected);
    }
}
