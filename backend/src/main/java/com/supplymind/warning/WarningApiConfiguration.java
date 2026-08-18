package com.supplymind.warning;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.warning.api.WarningController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * D8-T02 warning API wiring: the controller composes the frozen WarningService (deterministic
 * evaluation), the new WarningQueryService (real from/to range, manifest-verified) and the
 * DEC-061 WarningAckStore (sidecar acknowledgement). No second warning store exists.
 */
@Configuration
public class WarningApiConfiguration {

    @Bean
    WarningQueryService warningQueryService(DataRoot dataRoot, WarningAckStore warningAckStore) {
        return new WarningQueryService(dataRoot, warningAckStore);
    }

    @Bean
    WarningAckStore warningAckStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock) {
        return new WarningAckStore(dataRoot, fileStore, foundationClock);
    }

    @Bean
    WarningController warningController(
            WarningService warningService,
            WarningQueryService warningQueryService,
            WarningAckStore warningAckStore,
            java.time.Clock foundationClock
    ) {
        return new WarningController(warningService, warningQueryService, warningAckStore, foundationClock);
    }
}
