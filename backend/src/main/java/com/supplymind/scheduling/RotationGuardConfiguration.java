package com.supplymind.scheduling;

import com.supplymind.rotation.TimeRotationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * D5-T01/F1 wiring: the rotation guard in front of the scheduled collection cycle. The guarded
 * trigger is disabled by default (supplymind.scheduler.guarded-enabled=false) exactly like the
 * plain PBOC trigger; when enabled, every scheduled cycle first consults the rotation high-water
 * mark and skips itself on rollback.
 *
 * M1 production-path fix: {@link RotationGuardedScheduler#runGuardedCycle()} is the ONLY formal
 * {@code @Scheduled} acquisition entry in the application. The legacy {@link PbocDay2Scheduler}
 * bean no longer carries a {@code @Scheduled} annotation, so an unguarded scheduled cycle can
 * never run in parallel with the guarded one.
 */
@Configuration
public class RotationGuardConfiguration {

    @Bean
    RotationGuardedCollectionService rotationGuardedCollectionService(
            TimeRotationService timeRotationService,
            PbocDay2CollectionService pbocDay2CollectionService
    ) {
        return new RotationGuardedCollectionService(
                timeRotationService,
                () -> pbocDay2CollectionService.runImmediateCycle());
    }

    @Bean
    RotationGuardedScheduler rotationGuardedScheduler(
            @Value("${supplymind.scheduler.guarded-enabled:false}") boolean enabled,
            Clock foundationClock,
            RotationGuardedCollectionService rotationGuardedCollectionService
    ) {
        return new RotationGuardedScheduler(enabled, foundationClock, rotationGuardedCollectionService);
    }

    /** The single formal scheduled acquisition entry (off by default, mirrors the legacy PBOC trigger). */
    public static final class RotationGuardedScheduler {
        private final boolean enabled;
        private final Clock clock;
        private final RotationGuardedCollectionService guardedCollectionService;

        RotationGuardedScheduler(boolean enabled, Clock clock, RotationGuardedCollectionService guardedCollectionService) {
            this.enabled = enabled;
            this.clock = clock;
            this.guardedCollectionService = guardedCollectionService;
        }

        @Scheduled(cron = "${supplymind.scheduler.cron:0 30 9 * * MON-FRI}")
        public void runGuardedCycle() {
            if (!enabled) {
                return;
            }
            guardedCollectionService.runIfNotRollback(OffsetDateTime.now(clock));
        }
    }
}
