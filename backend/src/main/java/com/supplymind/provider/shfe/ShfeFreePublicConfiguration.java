package com.supplymind.provider.shfe;

import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Spring wiring for the approved credential-free SHFE public benchmark. */
@Configuration
public class ShfeFreePublicConfiguration {
    @Bean
    ShfeHttpTransport shfeHttpTransport() {
        return new JdkShfeHttpTransport();
    }

    @Bean
    ShfeDailyMarketParser shfeDailyMarketParser() {
        return new ShfeDailyMarketParser();
    }

    @Bean
    ShfeAdFreePublicDataProvider shfeAdFreePublicDataProvider(
            ConfigActivationStore configs, RawReceiptStore raws, Clock foundationClock,
            ShfeHttpTransport transport, ShfeDailyMarketParser parser
    ) {
        return new ShfeAdFreePublicDataProvider(configs, raws, foundationClock, transport, parser);
    }

    @Bean
    FreePublicCurrentAcquisitionService freePublicCurrentAcquisitionService(
            ConfigActivationStore configs, BackfillOrchestrator orchestrator,
            @Qualifier("currentAcquisitionExecutor") ExecutorService currentAcquisitionExecutor
    ) {
        return new FreePublicCurrentAcquisitionService(configs, orchestrator, currentAcquisitionExecutor);
    }

    @Bean
    @ConditionalOnProperty(name = "supplymind.current-acquisition.on-startup-enabled", havingValue = "true")
    ApplicationListener<ApplicationReadyEvent> freePublicCurrentOnStartup(
            FreePublicCurrentAcquisitionService service
    ) {
        return event -> service.trigger();
    }
}
