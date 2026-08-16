package com.supplymind.config;

import com.supplymind.backfill.BackfillJobQueryService;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.backfill.api.BackfillController;
import com.supplymind.config.api.ConfigController;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.RawAcquisitionStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.validation.LifecycleValidationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * D8-T01 wiring: the dynamic-config workflow composes ONLY existing services/stores - the
 * frozen ConfigManagementService activation chain and the BackfillOrchestrator production
 * chain. No second config/job store exists; the controllers are thin adapters.
 */
@Configuration
public class DynamicConfigConfiguration {

    @Bean
    BackfillJobStore backfillJobStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock) {
        return new BackfillJobStore(dataRoot, fileStore, foundationClock);
    }

    @Bean
    BackfillJobQueryService backfillJobQueryService(DataRoot dataRoot) {
        return new BackfillJobQueryService(dataRoot);
    }

    @Bean
    BackfillOrchestrator backfillOrchestrator(
            DataRoot dataRoot,
            BackfillJobStore backfillJobStore,
            ConfigActivationStore configActivationStore,
            DataProviderRegistry dataProviderRegistry,
            RawAcquisitionStore rawAcquisitionStore,
            RawReceiptStore rawReceiptStore,
            TimelineStore timelineStore,
            LifecycleValidationService lifecycleValidationService,
            LifecyclePublishService lifecyclePublishService,
            DailyProcessingService dailyProcessingService,
            AggregateProcessingService aggregateProcessingService
    ) {
        return new BackfillOrchestrator(
                dataRoot, backfillJobStore, configActivationStore, dataProviderRegistry,
                rawAcquisitionStore, rawReceiptStore, timelineStore,
                lifecycleValidationService, lifecyclePublishService,
                dailyProcessingService, aggregateProcessingService);
    }

    @Bean
    ConfigHistoryQueryService configHistoryQueryService(DataRoot dataRoot) {
        return new ConfigHistoryQueryService(dataRoot);
    }

    @Bean
    DynamicConfigWorkflowService dynamicConfigWorkflowService(
            ConfigManagementService configManagementService,
            BackfillOrchestrator backfillOrchestrator,
            BackfillJobStore backfillJobStore,
            BackfillJobQueryService backfillJobQueryService,
            ConfigHistoryQueryService configHistoryQueryService,
            DataProviderRegistry dataProviderRegistry,
            Clock foundationClock
    ) {
        return new DynamicConfigWorkflowService(
                configManagementService, backfillOrchestrator, backfillJobStore,
                backfillJobQueryService, configHistoryQueryService,
                dataProviderRegistry, foundationClock);
    }

    @Bean
    ConfigController configController(DynamicConfigWorkflowService workflow) {
        return new ConfigController(workflow);
    }

    @Bean
    BackfillController backfillController(DynamicConfigWorkflowService workflow) {
        return new BackfillController(workflow);
    }
}
