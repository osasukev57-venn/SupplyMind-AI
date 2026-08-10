package com.supplymind.scheduling;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.processing.AggregateProcessingService;
import com.supplymind.processing.DailyProcessingService;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import com.supplymind.publish.LifecyclePublishService;
import com.supplymind.publish.PublishedQueryService;
import com.supplymind.validation.LifecycleValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * D2-T05 wiring: idempotent Day 2 stage services plus the optional scheduled trigger.
 * The scheduler bean only exists when supplymind.scheduler.enabled=true; by default no
 * timer is created and nothing runs at startup.
 */
@Configuration
@EnableScheduling
public class Day2SchedulingConfiguration {

    @Bean
    TimelineStore timelineStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock) {
        return new TimelineStore(dataRoot, fileStore, foundationClock);
    }

    @Bean
    QuarantineStore quarantineStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock) {
        return new QuarantineStore(dataRoot, fileStore, foundationClock);
    }

    @Bean
    LifecycleValidationService lifecycleValidationService(
            DataRoot dataRoot, TimelineStore timelineStore, Clock foundationClock
    ) {
        return new LifecycleValidationService(dataRoot, timelineStore, foundationClock);
    }

    @Bean
    LifecyclePublishService lifecyclePublishService(
            DataRoot dataRoot, TimelineStore timelineStore, QuarantineStore quarantineStore, Clock foundationClock
    ) {
        return new LifecyclePublishService(dataRoot, timelineStore, quarantineStore, foundationClock);
    }

    @Bean
    DailyProcessingService dailyProcessingService(
            DataRoot dataRoot, TimelineStore timelineStore, AtomicFileStore fileStore, Clock foundationClock
    ) {
        return new DailyProcessingService(dataRoot, timelineStore, fileStore, foundationClock);
    }

    @Bean
    AggregateProcessingService aggregateProcessingService(
            DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock
    ) {
        return new AggregateProcessingService(dataRoot, fileStore, foundationClock);
    }

    @Bean
    PublishedQueryService publishedQueryService(
            DataRoot dataRoot, TimelineStore timelineStore, Clock foundationClock
    ) {
        return new PublishedQueryService(dataRoot, timelineStore, foundationClock);
    }

    @Bean
    PbocDay2CollectionService pbocDay2CollectionService(
            PbocOfficialWebDataProvider provider,
            TimelineStore timelineStore,
            LifecycleValidationService validation,
            LifecyclePublishService publish,
            DailyProcessingService daily,
            AggregateProcessingService aggregate
    ) {
        return new PbocDay2CollectionService(provider, timelineStore, validation, publish, daily, aggregate);
    }

    @Bean
    @ConditionalOnProperty(name = "supplymind.scheduler.enabled", havingValue = "true")
    PbocDay2Scheduler pbocDay2Scheduler(PbocDay2CollectionService collectionService) {
        return new PbocDay2Scheduler(collectionService);
    }
}
