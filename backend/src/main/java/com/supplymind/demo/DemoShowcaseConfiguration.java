package com.supplymind.demo;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.TimelineStore;
import com.supplymind.provider.DataProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoShowcaseConfiguration {
    @Bean
    DemoShowcaseService demoShowcaseService(
            DataRoot dataRoot, AtomicFileStore files, TimelineStore timelines,
            DataProviderRegistry providers
    ) {
        return new DemoShowcaseService(dataRoot, files, timelines, providers);
    }
}
