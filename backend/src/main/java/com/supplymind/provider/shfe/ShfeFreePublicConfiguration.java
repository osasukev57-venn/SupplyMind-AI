package com.supplymind.provider.shfe;

import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.RawReceiptStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

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
            com.supplymind.foundation.storage.RawAcquisitionStore acquisitions,
            ShfeHttpTransport transport, ShfeDailyMarketParser parser
    ) {
        return new ShfeAdFreePublicDataProvider(configs, raws, acquisitions, foundationClock, transport, parser);
    }

    @Bean
    FreePublicCurrentAcquisitionService freePublicCurrentAcquisitionService(
            ConfigActivationStore configs, BackfillOrchestrator orchestrator,
            @Qualifier("currentAcquisitionExecutor") ExecutorService currentAcquisitionExecutor
    ) {
        return new FreePublicCurrentAcquisitionService(configs, orchestrator, currentAcquisitionExecutor);
    }

}
