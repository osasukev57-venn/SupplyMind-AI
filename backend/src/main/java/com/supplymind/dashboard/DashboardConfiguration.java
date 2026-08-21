package com.supplymind.dashboard;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.dashboard.api.DashboardController;
import com.supplymind.demo.DemoShowcaseService;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.localimport.LocalImportService;
import com.supplymind.manual.ManualMaterialIntakeService;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.warning.WarningService;
import com.supplymind.warning.WarningStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * D7 dashboard Spring wiring: DashboardService composes ONLY existing services - it never reads
 * business files itself. The controller exposes the frozen /api/dashboard read-only contract
 * plus the Day8-boundary intake endpoints that reuse the REAL Manual/LocalImport boundaries.
 */
@Configuration
public class DashboardConfiguration {

    @Bean
    WarningService dashboardWarningService(
            DataRoot dataRoot,
            AtomicFileStore atomicFileStore,
            Clock foundationClock,
            HistoryQueryService historyQueryService
    ) {
        return new WarningService(dataRoot,
                new WarningStore(dataRoot, atomicFileStore, foundationClock),
                foundationClock, historyQueryService);
    }

    @Bean
    DashboardService dashboardService(
            ConfigManagementService configManagementService,
            PublishedQueryService publishedQueryService,
            HistoryQueryService historyQueryService,
            WarningService dashboardWarningService,
            Clock foundationClock,
            ManualMaterialIntakeService manualMaterialIntakeService,
            LocalImportService localImportService,
            DataProviderRegistry dataProviderRegistry,
            DemoShowcaseService demoShowcaseService
    ) {
        return new DashboardService(configManagementService, publishedQueryService,
                historyQueryService, dashboardWarningService, foundationClock,
                manualMaterialIntakeService, localImportService, dataProviderRegistry,
                demoShowcaseService);
    }

    @Bean
    DashboardController dashboardController(DashboardService dashboardService) {
        return new DashboardController(dashboardService);
    }
}
