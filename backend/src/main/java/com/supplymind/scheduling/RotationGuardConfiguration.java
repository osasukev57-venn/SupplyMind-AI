package com.supplymind.scheduling;

import com.supplymind.rotation.TimeRotationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;

/**
 * D5-T01/F1 wiring: the rotation guard in front of the scheduled collection cycle. The guarded
 * trigger is disabled by default (supplymind.scheduler.guarded-enabled=false) exactly like the
 * plain PBOC trigger; when enabled, every scheduled cycle first consults the rotation high-water
 * mark and skips itself on rollback.
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
            RotationGuardedCollectionService rotationGuardedCollectionService
    ) {
        return new RotationGuardedScheduler(enabled, rotationGuardedCollectionService);
    }

    /** Optional guarded scheduled trigger (off by default, mirrors the plain PBOC trigger). */
    public static final class RotationGuardedScheduler {
        private final boolean enabled;
        private final RotationGuardedCollectionService guardedCollectionService;

        RotationGuardedScheduler(boolean enabled, RotationGuardedCollectionService guardedCollectionService) {
            this.enabled = enabled;
            this.guardedCollectionService = guardedCollectionService;
        }

        public void runGuardedCycle() {
            if (!enabled) {
                return;
            }
            guardedCollectionService.runIfNotRollback(OffsetDateTime.now());
        }
    }
}
