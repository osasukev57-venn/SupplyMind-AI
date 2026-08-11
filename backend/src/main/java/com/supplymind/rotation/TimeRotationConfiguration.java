package com.supplymind.rotation;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.TimeStateStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** D5-T01 wiring: recoverable time state + rotation detection + startup recovery hook. */
@Configuration
public class TimeRotationConfiguration {

    @Bean
    TimeStateStore timeStateStore(DataRoot dataRoot, AtomicFileStore atomicFileStore, Clock foundationClock) {
        return new TimeStateStore(dataRoot, atomicFileStore, foundationClock);
    }

    @Bean
    TimeRotationService timeRotationService(TimeStateStore timeStateStore) {
        return new TimeRotationService(timeStateStore);
    }

    @Bean
    ApplicationRunner timeRotationStartupRecovery(TimeRotationService timeRotationService) {
        return arguments -> timeRotationService.recover();
    }
}
