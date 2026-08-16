package com.supplymind.dashboard;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.dashboard.api.DashboardController;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.publish.PublishedQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * D7 dashboard Spring wiring: DashboardService composes ONLY existing services - it never reads
 * business files itself. The controller exposes the frozen /api/dashboard read-only contract.
 */
@Configuration
public class DashboardConfiguration {

    @Bean
    DashboardService dashboardService(
            DataRoot dataRoot,
            ConfigManagementService configManagementService,
            PublishedQueryService publishedQueryService,
            HistoryQueryService historyQueryService,
            Clock foundationClock
    ) {
        return new DashboardService(dataRoot, configManagementService, publishedQueryService,
                historyQueryService, foundationClock);
    }

    @Bean
    DashboardController dashboardController(DashboardService dashboardService) {
        return new DashboardController(dashboardService);
    }
}
