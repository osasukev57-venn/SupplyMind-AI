package com.supplymind.routing;

import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.provider.DataProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * D3-T02/D3-T06 wiring: the resolver, the safe default authorization probe (no credentials are
 * ever configured, logged or persisted; AuthorizedApi candidates stay NOT_CONFIGURED until a
 * real probe is supplied) and the production route-plan service that derives three-tier
 * MaterialRouteConfigV1 from the active monitor-series configuration plus the registry.
 */
@Configuration
public class MaterialRoutingConfiguration {

    @Bean
    MaterialRouteResolver materialRouteResolver() {
        return new MaterialRouteResolver();
    }

    @Bean
    ApiAuthorizationProbe apiAuthorizationProbe() {
        return ApiAuthorizationProbe.defaultProbe();
    }

    @Bean
    MaterialRoutePlanService materialRoutePlanService(
            ConfigActivationStore configActivationStore,
            DataProviderRegistry dataProviderRegistry,
            MaterialRouteResolver materialRouteResolver,
            ApiAuthorizationProbe apiAuthorizationProbe,
            Clock foundationClock
    ) {
        return new MaterialRoutePlanService(
                configActivationStore, dataProviderRegistry, materialRouteResolver,
                apiAuthorizationProbe, foundationClock);
    }
}
