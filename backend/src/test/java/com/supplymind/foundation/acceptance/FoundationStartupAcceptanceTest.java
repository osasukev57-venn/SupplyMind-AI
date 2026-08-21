package com.supplymind.foundation.acceptance;

import com.supplymind.SupplyMindApplication;
import com.supplymind.foundation.boot.SupplyMindDataProperties;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaValidationException;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * High-level AT-FILE-000 startup evidence. Every run injects one independent,
 * absolute temporary data root and therefore never writes product data.
 */
class FoundationStartupAcceptanceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsWithOneUnicodeDataRootAndActivatesTheFormalPbocHistoryPair() throws IOException {
        Path configuredRoot = temporaryDirectory.resolve("SupplyMind 空格 数据根").toAbsolutePath();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SupplyMindApplication.class)
                .web(WebApplicationType.NONE)
                .run("--supplymind.data-root=" + configuredRoot,
                        "--spring.main.web-application-type=none")) {
            DataRoot dataRoot = context.getBean(DataRoot.class);
            SupplyMindDataProperties properties = context.getBean(SupplyMindDataProperties.class);

            assertEquals(configuredRoot.normalize(), dataRoot.path());
            assertEquals(configuredRoot.normalize(), properties.normalizedDataRoot());
            assertEquals(configuredRoot.normalize(), properties.requireSameRoot(configuredRoot));
            assertThrows(SchemaValidationException.class,
                    () -> properties.requireSameRoot(temporaryDirectory.resolve("second-root")));
            assertTrue(Files.isDirectory(configuredRoot));

            Path active = dataRoot.resolveDataRef(DataPaths.configActiveRef());
            Path history = dataRoot.resolveDataRef(DataPaths.configHistoryRef(1));
            assertTrue(Files.isRegularFile(active));
            assertTrue(Files.isRegularFile(history));
            assertTrue(Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.manifestRef(DataPaths.configActiveRef()))));
            assertTrue(Files.isRegularFile(dataRoot.resolveDataRef(
                    DataPaths.manifestRef(DataPaths.configHistoryRef(1)))));
            assertArrayEquals(Files.readAllBytes(active), Files.readAllBytes(history),
                    "active config and immutable history/1 must be byte-identical");

            MonitorSeriesConfigV1 configuration = com.supplymind.foundation.codec.JsonV1Codec.decodeFile(
                    Files.readAllBytes(active), MonitorSeriesConfigV1.class);
            assertEquals(1, configuration.configVersion());
            assertEquals(Mode.FORMAL, configuration.mode());
            assertEquals(List.of(
                            MonitorSeriesDefaults.EUR_CNY_ITEM_ID, MonitorSeriesDefaults.USD_CNY_ITEM_ID,
                            MonitorSeriesDefaults.ADC12_AM_ITEM_ID, MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,
                            MonitorSeriesDefaults.AZ91D_AM_ITEM_ID, MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID),
                    configuration.items().stream().map(MonitorSeriesItemV1::itemId).toList());
            List<MonitorSeriesItemV1> pbocItems = configuration.items().stream()
                    .filter(item -> item.providerType() == ProviderType.OFFICIAL_WEB).toList();
            assertEquals(2, pbocItems.size());
            for (MonitorSeriesItemV1 item : pbocItems) {
                assertTrue(item.enabled());
                assertEquals("PBOC", item.sourceIntent());
                assertEquals(ProviderType.OFFICIAL_WEB, item.providerType());
                assertEquals(AccessMethod.PUBLIC_OFFICIAL_HTML, item.accessMethod());
                assertEquals(MonitorSeriesDefaults.PBOC_SOURCE_NAME, item.actualSourceName());
                assertEquals(RouteDecision.PRIMARY, item.routeDecision());
                assertEquals(null, item.fallbackReason());
                assertEquals(configuration.updatedAt(), item.routeEffectiveAt());
                assertEquals(null, item.supersedesItemId());
                assertEquals(MonitorSeriesDefaults.PBOC_RATE_KIND, item.rateKind());
                assertEquals("arithmetic-mean-v1", item.calculationVersion());
                assertEquals(8, item.calculationScale());
                assertEquals(4, item.displayScale());
                assertEquals(RoundingMode.HALF_UP, item.roundingMode());
                assertEquals(MonitorSeriesDefaults.CALENDAR_VERSION, item.calendarVersion());
                assertEquals("CNY", item.currency());
                assertNotNull(item.baseCurrency());
                assertTrue(item.unit().startsWith("CNY/1 "));
            }
            List<MonitorSeriesItemV1> materialItems = configuration.items().stream()
                    .filter(item -> "material".equals(item.rateKind())).toList();
            assertEquals(List.of(
                            MonitorSeriesDefaults.ADC12_AM_ITEM_ID, MonitorSeriesDefaults.ADC12_SMM_ITEM_ID,
                            MonitorSeriesDefaults.AZ91D_AM_ITEM_ID, MonitorSeriesDefaults.AZ91D_SMM_ITEM_ID),
                    materialItems.stream().map(MonitorSeriesItemV1::itemId).toList());
            for (MonitorSeriesItemV1 item : materialItems) {
                assertTrue(item.enabled());
                assertTrue(item.sourceIntent().equals("SMM") || item.sourceIntent().equals("Asian Metal"));
                if ("ADC12".equals(item.externalCode())) {
                    assertEquals(ProviderType.FREE_PUBLIC, item.providerType());
                    assertEquals(AccessMethod.FREE_PUBLIC_WEB, item.accessMethod());
                    assertEquals(RouteDecision.FALLBACK_FREE_PUBLIC, item.routeDecision());
                } else {
                    assertEquals(ProviderType.MANUAL, item.providerType());
                    assertEquals(AccessMethod.MANUAL, item.accessMethod());
                    assertEquals(MonitorSeriesDefaults.MANUAL_INGRESS_SOURCE_NAME, item.actualSourceName());
                    assertEquals(RouteDecision.FALLBACK_MANUAL, item.routeDecision());
                    assertEquals(MonitorSeriesDefaults.MATERIAL_FALLBACK_REASON, item.fallbackReason());
                }
                assertEquals(configuration.updatedAt(), item.routeEffectiveAt());
                assertEquals("material", item.rateKind());
                assertEquals("CNY", item.currency());
                assertEquals("元/吨", item.unit());
            }
            MonitorSeriesItemV1 usd = configuration.requireItem(MonitorSeriesDefaults.USD_CNY_ITEM_ID);
            MonitorSeriesItemV1 eur = configuration.requireItem(MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
            assertEquals("USD", usd.externalCode());
            assertEquals("1美元对人民币", usd.sourceFieldKey());
            assertEquals("USD", usd.baseCurrency());
            assertEquals("CNY/1 USD", usd.unit());
            assertEquals("EUR", eur.externalCode());
            assertEquals("1欧元对人民币", eur.sourceFieldKey());
            assertEquals("EUR", eur.baseCurrency());
            assertEquals("CNY/1 EUR", eur.unit());
            String activeJson = Files.readString(active, StandardCharsets.UTF_8);
            assertFalse(activeJson.contains("quoteCurrency"),
                    "runtime config must use currency, not an extra quoteCurrency field");
            Path defaultProductRoot = Path.of(System.getProperty("user.dir"), "data").toAbsolutePath().normalize();
            assertFalse(Files.exists(defaultProductRoot),
                    "a configured temporary root must not create a second default product data root");
            try (var paths = Files.walk(configuredRoot)) {
                assertFalse(paths.anyMatch(path -> path.getFileName() != null
                        && path.getFileName().toString().matches("(?i).+\\.(db|sqlite|h2|mv\\.db)$")),
                        "Spring Boot D1-T03 startup must not create database files");
            }
        }
    }

    @Test
    void repositoryBuildDescriptorHasNoDatabaseRuntimeStack() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String forbiddenRuntimeDependency : List.of(
                "spring-boot-starter-data-jpa", "mybatis", "r2dbc", "mysql", "postgresql",
                "sqlite", "mongodb", "redis", "<artifactid>h2</artifactid>")) {
            assertFalse(pom.contains(forbiddenRuntimeDependency),
                    () -> "D1-T03 must not add a database runtime dependency: " + forbiddenRuntimeDependency);
        }
    }
}
