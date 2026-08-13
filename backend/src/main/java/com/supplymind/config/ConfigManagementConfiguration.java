package com.supplymind.config;

import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.provider.DataProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** D5-T03 wiring: dynamic configuration management on top of the frozen activation store. */
@Configuration
public class ConfigManagementConfiguration {

    @Bean
    ConfigManagementService configManagementService(
            ConfigActivationStore configActivationStore,
            DataProviderRegistry dataProviderRegistry
    ) {
        return new ConfigManagementService(configActivationStore, dataProviderRegistry);
    }
}
