package com.supplymind.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * D3-T01 dynamic registration: every DataProvider Spring bean is discovered and registered by
 * identity. Adding a future provider implementation is a new bean + implementation, with no
 * change to the registry core or to business services. A duplicate provider identity fails the
 * context startup closed.
 */
@Configuration
public class ProviderRegistryConfiguration {

    @Bean
    DataProviderRegistry dataProviderRegistry(List<DataProvider> providers) {
        DataProviderRegistry registry = new DataProviderRegistry();
        for (DataProvider provider : providers) {
            registry.register(provider);
        }
        return registry;
    }
}
