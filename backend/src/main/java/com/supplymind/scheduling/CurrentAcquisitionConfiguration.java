package com.supplymind.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Desktop current-data bootstrap. The service and operator endpoint always exist; only the
 * automatic ApplicationReady trigger is property-gated so tests and library-style backend use
 * remain deterministic.
 */
@Configuration
public class CurrentAcquisitionConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService currentAcquisitionExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pboc-current-acquisition");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    CurrentAcquisitionService currentAcquisitionService(
            PbocDay2CollectionService collectionService,
            Clock foundationClock,
            ExecutorService currentAcquisitionExecutor
    ) {
        return new CurrentAcquisitionService(collectionService, foundationClock, currentAcquisitionExecutor);
    }

    @Bean
    @ConditionalOnProperty(
            name = "supplymind.current-acquisition.on-startup-enabled",
            havingValue = "true")
    ApplicationListener<ApplicationReadyEvent> currentAcquisitionOnStartup(CurrentAcquisitionService acquisition) {
        return event -> acquisition.trigger();
    }
}
