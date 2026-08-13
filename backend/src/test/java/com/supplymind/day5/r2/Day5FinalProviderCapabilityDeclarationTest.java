package com.supplymind.day5.r2;

import com.supplymind.localimport.LocalImportDataProvider;
import com.supplymind.localimport.SyntheticDemoDataProvider;
import com.supplymind.manual.ManualDataProvider;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.MaterialSourceConfiguration;
import com.supplymind.provider.pboc.PbocOfficialWebDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 declaration gate: EVERY formal production DataProvider must explicitly implement
 * supports(MonitorSeriesItemV1) - none may inherit the interface default (which is
 * fail-closed false). This mirrors the Terra independent attack contract without reusing
 * its test files: it scans the four production provider classes plus every DataProvider bean
 * assembled by the production MaterialSourceConfiguration context.
 */
class Day5FinalProviderCapabilityDeclarationTest {

    @Test
    void everyFormalProductionProviderExplicitlyOverridesSupports() throws Exception {
        List<String> inheritedDefaults = new ArrayList<>();
        for (Class<? extends DataProvider> type : List.of(
                PbocOfficialWebDataProvider.class,
                ManualDataProvider.class,
                LocalImportDataProvider.class,
                SyntheticDemoDataProvider.class)) {
            collectInheritedDefault(type, type.getName(), inheritedDefaults);
        }
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MaterialSourceConfiguration.class)) {
            for (Map.Entry<String, DataProvider> entry : context.getBeansOfType(DataProvider.class).entrySet()) {
                collectInheritedDefault(entry.getValue().getClass(), entry.getKey(), inheritedDefaults);
            }
        }
        assertTrue(inheritedDefaults.isEmpty(),
                "formal providers must explicitly declare supports(item), not inherit the fail-closed default: "
                        + inheritedDefaults);
    }

    @Test
    void authorizedApiPlaceholdersExplicitlyFailClosedWithoutCredentials() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MaterialSourceConfiguration.class)) {
            for (DataProvider provider : context.getBeansOfType(DataProvider.class).values()) {
                assertTrue(provider.profile().providerType()
                                != com.supplymind.foundation.model.ProviderType.AUTHORIZED_API
                                || !provider.supports(anyItem()),
                        provider.profile().providerId()
                                + " must explicitly fail closed: no configured credentials, no capability");
            }
        }
    }

    private static com.supplymind.foundation.model.MonitorSeriesItemV1 anyItem() {
        return new com.supplymind.foundation.model.MonitorSeriesItemV1(
                "FX.F1.PROBE", "probe", true, "F1", com.supplymind.foundation.model.ProviderType.AUTHORIZED_API,
                com.supplymind.foundation.model.AccessMethod.AUTHORIZED_API, "probe source",
                com.supplymind.foundation.model.RouteDecision.PRIMARY, null,
                java.time.OffsetDateTime.parse("2026-08-10T10:00:00+08:00"), null,
                "SMM", "material-field-key", "f1-probe-kind", "arithmetic-mean-v1", 2, 2,
                java.math.RoundingMode.HALF_UP, "weekday-asia-shanghai-v1", "CNY", "CNY", "元/吨", null);
    }

    private static void collectInheritedDefault(Class<?> type, String label, List<String> inheritedDefaults)
            throws Exception {
        if (type.getMethod("supports", com.supplymind.foundation.model.MonitorSeriesItemV1.class)
                .getDeclaringClass() == DataProvider.class) {
            inheritedDefaults.add(label);
        }
    }
}
