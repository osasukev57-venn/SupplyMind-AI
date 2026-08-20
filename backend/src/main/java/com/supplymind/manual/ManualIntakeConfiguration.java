package com.supplymind.manual;

import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * D3-T04 wiring: the manual intake service, the operator context (server-side configured,
 * never client-supplied) and the ManualDataProvider registered as a standard DataProvider.
 */
@Configuration
public class ManualIntakeConfiguration {

    @Bean
    ManualMaterialNormalizer manualMaterialNormalizer() {
        return new ManualMaterialNormalizer();
    }

    @Bean
    OperatorContext operatorContext(
            @Value("${supplymind.manual.operator-ref:}") String operatorRef
    ) {
        return OperatorContext.configured(operatorRef);
    }

    @Bean
    ManualMaterialIntakeService manualMaterialIntakeService(
            DataRoot dataRoot,
            RawReceiptStore rawReceiptStore,
            TimelineStore timelineStore,
            ManualMaterialNormalizer normalizer,
            OperatorContext operatorContext,
            Clock foundationClock
    ) {
        return new ManualMaterialIntakeService(
                dataRoot, rawReceiptStore, timelineStore, normalizer, operatorContext, foundationClock);
    }

    @Bean
    ManualMaterialProcessingService manualMaterialProcessingService(
            DataRoot dataRoot,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        return new ManualMaterialProcessingService(
                dataRoot, timelineStore, validation, publish, daily, aggregate);
    }

    @Bean
    ManualDataProvider manualDataProvider(
            DataRoot dataRoot,
            AtomicFileStore fileStore,
            Clock foundationClock
    ) {
        return new ManualDataProvider(() -> manualRouteItemIds(dataRoot, fileStore, foundationClock));
    }

    private static Set<String> manualRouteItemIds(
            DataRoot dataRoot, AtomicFileStore fileStore, Clock clock
    ) {
        try {
            MonitorSeriesConfigV1 config = new ConfigActivationStore(dataRoot, fileStore, clock)
                    .readActiveConfig();
            Set<String> ids = new HashSet<>();
            for (MonitorSeriesItemV1 item : config.items()) {
                if (item.enabled()
                        && (item.providerType() == ProviderType.MANUAL
                        || item.routeDecision() == RouteDecision.FALLBACK_MANUAL)) {
                    ids.add(item.itemId());
                }
            }
            return ids;
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }
}
