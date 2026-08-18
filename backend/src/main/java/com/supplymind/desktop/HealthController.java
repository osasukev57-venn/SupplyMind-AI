package com.supplymind.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * D9-T01 minimal desktop readiness endpoint. The Electron shell polls this endpoint after
 * spawning the Spring Boot JAR before it opens the main window. It is additive-only and does
 * not touch any Day1-Day8 business contract: no business data, no storage access, no secrets.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String applicationName;

    public HealthController(
            @Value("${spring.application.name:supplymind-backend}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "application", applicationName,
                "pid", String.valueOf(ProcessHandle.current().pid()));
    }
}
