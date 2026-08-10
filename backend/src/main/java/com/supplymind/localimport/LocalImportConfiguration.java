package com.supplymind.localimport;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.foundation.storage.AtomicFileStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * D3-T05 wiring: the LocalImport service, the LocalImportDataProvider and the
 * SyntheticDemoDataProvider as standard DataProviders (registry auto-discovers them).
 */
@Configuration
public class LocalImportConfiguration {

    @Bean
    LocalImportCsvParser localImportCsvParser() {
        return new LocalImportCsvParser();
    }

    @Bean
    LocalImportService localImportService(
            DataRoot dataRoot,
            RawReceiptStore rawReceiptStore,
            TimelineStore timelineStore,
            LocalImportCsvParser parser,
            Clock foundationClock
    ) {
        return new LocalImportService(dataRoot, rawReceiptStore, timelineStore, parser, foundationClock);
    }

    @Bean
    LocalImportDataProvider localImportDataProvider(
            DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock
    ) {
        return new LocalImportDataProvider(() ->
                localImportRouteItemIds(dataRoot, fileStore, foundationClock));
    }

    @Bean
    SyntheticDemoDataProvider syntheticDemoDataProvider() {
        return new SyntheticDemoDataProvider(SyntheticDemoDataProvider.defaultScenarioItems());
    }

    private static Set<String> localImportRouteItemIds(
            DataRoot dataRoot, AtomicFileStore fileStore, Clock clock
    ) {
        try {
            MonitorSeriesConfigV1 config = new ConfigActivationStore(dataRoot, fileStore, clock)
                    .readActiveConfig();
            Set<String> ids = new HashSet<>();
            for (MonitorSeriesItemV1 item : config.items()) {
                if (item.enabled()
                        && (item.providerType() == ProviderType.LOCAL_IMPORT
                        || item.routeDecision() == RouteDecision.DIRECT_LOCAL_IMPORT)) {
                    ids.add(item.itemId());
                }
            }
            return ids;
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }
}
