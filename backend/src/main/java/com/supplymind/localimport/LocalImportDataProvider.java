package com.supplymind.localimport;

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
 * D3-T05 LocalImport material provider as a standard DataProvider. Local import data arrives
 * through the controlled import service, never through source collection; collect() therefore
 * rejects all targets explicitly with LOCAL_IMPORT_REQUIRED and never fabricates data.
 */
public final class LocalImportDataProvider implements DataProvider {

    public static final String PROVIDER_ID = "local-import";
    private static final String SOURCE_NAME = "本地文件导入（LocalImport）";

    private final Supplier<Set<String>> supportedItemIdsSupplier;

    public LocalImportDataProvider(Supplier<Set<String>> supportedItemIdsSupplier) {
        this.supportedItemIdsSupplier = Objects.requireNonNull(supportedItemIdsSupplier, "supportedItemIdsSupplier");
    }

    @Override
    public ProviderSourceProfile profile() {
        return ProviderSourceProfile.of(
                PROVIDER_ID,
                ProviderType.LOCAL_IMPORT,
                AccessMethod.LOCAL_IMPORT,
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
     * D5-T03/F3 explicit capability (M2 fail-closed): the LocalImport provider handles
     * local-import material targets only (LOCAL_IMPORT acquisition, material rate kind).
     * Configuration activation rejects anything else instead of pretending success.
     */
    @Override
    public boolean supports(com.supplymind.foundation.model.MonitorSeriesItemV1 item) {
        return item.providerType() == ProviderType.LOCAL_IMPORT
                && item.accessMethod() == AccessMethod.LOCAL_IMPORT
                && "material".equals(item.rateKind());
    }

    @Override
    public ProviderCollectOutcome collect(ProviderCollectRequest request) {
        Objects.requireNonNull(request, "request");
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String itemId : request.itemIds()) {
            rejected.put(itemId, "LOCAL_IMPORT_REQUIRED");
        }
        return ProviderCollectOutcome.rejectedOnly(PROVIDER_ID, rejected);
    }
}
