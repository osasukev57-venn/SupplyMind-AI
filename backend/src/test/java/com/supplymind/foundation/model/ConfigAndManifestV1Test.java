package com.supplymind.foundation.model;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigAndManifestV1Test {
    @Test
    void formalDefaultUsesTheTwoFrozenPbocItemsAndProductionPrecision() {
        MonitorSeriesConfigV1 config = MonitorSeriesDefaults.initialPboc(OffsetDateTime.parse("2026-08-08T10:00:00+08:00"));

        assertEquals(SchemaV1.VERSION, config.schemaVersion());
        assertEquals(Mode.FORMAL, config.mode());
        assertEquals(2, config.items().size());
        MonitorSeriesItemV1 usd = config.requireItem(MonitorSeriesDefaults.USD_CNY_ITEM_ID);
        MonitorSeriesItemV1 eur = config.requireItem(MonitorSeriesDefaults.EUR_CNY_ITEM_ID);
        assertEquals("USD", usd.baseCurrency());
        assertEquals("CNY", usd.currency());
        assertEquals("CNY/1 USD", usd.unit());
        assertEquals("EUR", eur.baseCurrency());
        assertEquals("CNY/1 EUR", eur.unit());
        assertEquals(8, usd.calculationScale());
        assertEquals(4, usd.displayScale());
    }

    @Test
    void manifestSortsDistinctRunIdsAndRejectsLifecycleCommitState() {
        ManifestV1 manifest = new ManifestV1(
                SchemaV1.VERSION, "test-run-usd-001.json", "a".repeat(64), 12L, null, null, null,
                List.of("z-run", "a-run", "z-run"), OffsetDateTime.parse("2026-08-08T10:00:00+08:00"), ManifestV1.COMMITTED);

        assertEquals(List.of("a-run", "z-run"), manifest.sourceRunIds());
        assertThrows(SchemaValidationException.class, () -> new ManifestV1(
                SchemaV1.VERSION, "test.json", "a".repeat(64), 1L, null, null, null, List.of(),
                OffsetDateTime.parse("2026-08-08T10:00:00+08:00"), "PUBLISHED"));
    }
}
