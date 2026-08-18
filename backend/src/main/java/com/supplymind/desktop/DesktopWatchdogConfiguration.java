package com.supplymind.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * D9-T04 wires the parent-process watchdog ONLY when the Electron shell explicitly passes
 * {@code --supplymind.desktop.parent-pid}. Every other startup mode (dev, tests, browser)
 * does not set the property, so no watchdog is created and Day1-Day8 behavior is unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class DesktopWatchdogConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "supplymind.desktop", name = "parent-pid")
    ChildProcessWatchdog childProcessWatchdog(
            @Value("${supplymind.desktop.parent-pid}") String parentPidText) {
        long parentPid = Long.parseLong(parentPidText.trim());
        if (parentPid <= 0 || parentPid == ProcessHandle.current().pid()) {
            throw new IllegalStateException("supplymind.desktop.parent-pid must be a different positive pid");
        }
        ChildProcessWatchdog watchdog = new ChildProcessWatchdog(
                parentPid,
                () -> System.exit(0),
                Duration.ofSeconds(2));
        watchdog.start();
        return watchdog;
    }
}
