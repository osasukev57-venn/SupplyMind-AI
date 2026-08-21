package com.supplymind.acceptance;

import com.supplymind.SupplyMindApplication;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.MaterialSourceConfiguration;
import com.supplymind.routing.DataKind;
import com.supplymind.routing.MaterialRouteDecision;
import com.supplymind.routing.MaterialRoutePlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane A1 production-path proof: a real Spring Boot startup on the normal bootstrap path
 * (ApplicationRunner -> ConfigActivationStore.ensureInitialDefault) must deliver the four P0
 * material sequences in the active configuration and resolve each to its frozen legal
 * non-synthetic route through the production registry, resolver and probe - with no
 * test-harness route construction anywhere.
 */
class MaterialRoutePlanProductionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void productionStartupDeliversFourP0MaterialRoutesWithoutTestHarnessInjection() throws IOException {
        Path dataRootPath = temporaryDirectory.resolve("production-route-plan").toAbsolutePath();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SupplyMindApplication.class)
                .web(WebApplicationType.NONE)
                .run("--supplymind.data-root=" + dataRootPath,
                        "--spring.main.web-application-type=none")) {
            DataRoot dataRoot = context.getBean(DataRoot.class);
            Path active = dataRoot.resolveDataRef(DataPaths.configActiveRef());
            MonitorSeriesConfigV1 configuration = com.supplymind.foundation.codec.JsonV1Codec.decodeFile(
                    Files.readAllBytes(active), MonitorSeriesConfigV1.class);
            assertEquals(Mode.FORMAL, configuration.mode());
            assertEquals(1, configuration.configVersion());

            List<String> p0Ids = MaterialSourceConfiguration.intentItemIds();
            for (String itemId : p0Ids) {
                MonitorSeriesItemV1 item = configuration.requireItem(itemId);
                assertTrue(item.enabled());
                assertTrue(item.sourceIntent().equals("SMM") || item.sourceIntent().equals("Asian Metal"));
                if ("ADC12".equals(item.externalCode())) {
                    assertEquals(ProviderType.FREE_PUBLIC, item.providerType(),
                            itemId + " must use the approved SHFE public benchmark fallback");
                    assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, item.routeDecision());
                } else {
                    assertEquals(ProviderType.MANUAL, item.providerType(),
                            itemId + " must keep the honest Manual fallback");
                    assertEquals(RouteDecision.FALLBACK_MANUAL, item.routeDecision());
                }
            }

            DataProviderRegistry registry = context.getBean(DataProviderRegistry.class);
            assertNotNull(registry.find(MaterialSourceConfiguration.SMM_PROVIDER_ID).orElse(null));
            assertNotNull(registry.find(MaterialSourceConfiguration.AM_PROVIDER_ID).orElse(null));
            assertNotNull(registry.find("manual-material").orElse(null));
            assertNotNull(registry.find("synthetic-demo").orElse(null));
            assertNotNull(registry.find("shfe-ad-free-public").orElse(null));

            MaterialRoutePlanService planService = context.getBean(MaterialRoutePlanService.class);
            for (String itemId : p0Ids) {
                MaterialRouteDecision decision = planService.resolveFor(itemId, DataKind.CURRENT).orElseThrow();
                boolean adc12 = "ADC12".equals(configuration.requireItem(itemId).externalCode());
                assertEquals(adc12 ? "shfe-ad-free-public" : "manual-material", decision.activeProviderId());
                assertEquals(adc12 ? RouteDecision.FALLBACK_FREE_PUBLIC : RouteDecision.FALLBACK_MANUAL,
                        decision.routeDecision());
                assertNotNull(decision.fallbackReason());
                assertEquals(adc12 ? 1L : 0L, decision.candidates().stream()
                                .filter(c -> c.tier() == com.supplymind.routing.RouteTier.FREE_PUBLIC)
                                .count(),
                        "only the approved ADC12-equivalent SHFE benchmark may occupy FREE_PUBLIC");
                assertTrue(decision.candidates().stream()
                                .noneMatch(c -> c.providerId().equals("synthetic-demo")),
                        "synthetic must never appear in the formal production route plan");
                assertTrue(decision.candidates().stream()
                                .noneMatch(c -> c.providerId().equals("pboc-official-web")),
                        "PBOC must never be drawn into material routes");
            }

            MaterialRouteDecision pboc = planService.resolveFor(
                    MonitorSeriesDefaults.USD_CNY_ITEM_ID, DataKind.CURRENT).orElseThrow();
            assertEquals("pboc-official-web", pboc.activeProviderId(),
                    "the production route plan must keep the PBOC pair on its OfficialWeb primary route");
            assertEquals(RouteDecision.PRIMARY, pboc.routeDecision());
            assertEquals(ProviderType.OFFICIAL_WEB,
                    context.getBean(DataProviderRegistry.class).require("pboc-official-web").profile().providerType());
        }
    }
}
