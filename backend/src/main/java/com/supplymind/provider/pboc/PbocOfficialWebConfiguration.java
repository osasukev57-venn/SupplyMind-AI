package com.supplymind.provider.pboc;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.RawReceiptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Spring wiring only; collection is never scheduled or triggered at application startup. */
@Configuration
public class PbocOfficialWebConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbocOfficialWebConfiguration.class);

    @Bean
    PbocHttpTransport pbocHttpTransport() { return new JdkPbocHttpTransport(); }

    @Bean
    PbocAnnouncementParser pbocAnnouncementParser() { return new PbocAnnouncementParser(); }

    @Bean
    PbocOfficialWebDataProvider pbocOfficialWebDataProvider(
            DataRoot dataRoot, RawReceiptStore rawReceiptStore, AtomicFileStore atomicFileStore,
            Clock foundationClock, PbocHttpTransport transport, PbocAnnouncementParser parser
    ) {
        return new PbocOfficialWebDataProvider(dataRoot, rawReceiptStore, atomicFileStore, foundationClock, transport,
                parser, event -> LOGGER.info("pboc_official_web outcome={} stage={} url={} httpStatus={} exception={}",
                        event.outcome(), event.stage(), event.sanitizedUrl(), event.httpStatus(), event.exceptionType()));
    }
}