package com.supplymind.routing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D3-T02 wiring: the resolver plus the safe default authorization probe (no credentials are
 * ever configured, logged or persisted; AuthorizedApi candidates stay NOT_CONFIGURED until a
 * real probe is supplied).
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
}
