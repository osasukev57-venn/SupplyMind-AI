package com.supplymind.foundation.boot;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.AtomicFileRecovery;
import com.supplymind.foundation.storage.AtomicMoveSupport;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.DirtyMarkerRecovery;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.SingleWriterGuard;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** Boot wiring for the one local file root; no database or alternate root exists. */
@Configuration
public class FoundationStorageConfiguration {

    @Bean
    Clock foundationClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    DataRoot dataRoot(SupplyMindDataProperties properties) {
        return DataRoot.fromConfiguredPath(properties.normalizedDataRoot().toString());
    }

    @Bean(destroyMethod = "close")
    SingleWriterGuard singleWriterGuard(DataRoot dataRoot) {
        return SingleWriterGuard.acquire(dataRoot);
    }

    @Bean
    DirtyMarkerCodec dirtyMarkerCodec() {
        return new DirtyMarkerCodec();
    }

    @Bean
    DirtyMarkerRecovery dirtyMarkerRecovery(DirtyMarkerCodec codec) {
        return new DirtyMarkerRecovery(codec);
    }

    @Bean
    AtomicFileStore atomicFileStore(DataRoot dataRoot, DirtyMarkerCodec codec) {
        return new AtomicFileStore(dataRoot, codec);
    }

    @Bean
    AtomicFileRecovery atomicFileRecovery(DataRoot dataRoot, DirtyMarkerCodec codec, Clock foundationClock) {
        return new AtomicFileRecovery(dataRoot, codec, foundationClock);
    }

    @Bean
    ConfigActivationStore configActivationStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock) {
        return new ConfigActivationStore(dataRoot, fileStore, foundationClock);
    }

    @Bean
    RawReceiptStore rawReceiptStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock foundationClock) {
        return new RawReceiptStore(dataRoot, fileStore, foundationClock);
    }

    @Bean
    ApplicationRunner foundationStorageStartup(
            DataRoot dataRoot,
            SingleWriterGuard writerGuard,
            AtomicFileRecovery recovery,
            ConfigActivationStore configActivationStore
    ) {
        return arguments -> {
            if (writerGuard == null) {
                throw new IllegalStateException("single dataRoot writer guard is required");
            }
            dataRoot.createIfAbsentAndRequireWritable();
            AtomicMoveSupport.probeOrFail(dataRoot);
            recovery.recoverAll();
            configActivationStore.ensureInitialDefault();
        };
    }
}
