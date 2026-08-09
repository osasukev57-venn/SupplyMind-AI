package com.supplymind.foundation.model;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/** Formal v1 PBOC defaults. Golden fixture overrides remain test-only and are not production defaults. */
public final class MonitorSeriesDefaults {
    public static final String USD_CNY_ITEM_ID = "FX.USD.CNY.PBOC_MID";
    public static final String EUR_CNY_ITEM_ID = "FX.EUR.CNY.PBOC_MID";
    public static final String PBOC_SOURCE_NAME = "中国人民银行官网（授权中国外汇交易中心公布）";
    public static final String PBOC_RATE_KIND = "人民币汇率中间价";
    public static final String CALCULATION_VERSION = "arithmetic-mean-v1";
    public static final String CALENDAR_VERSION = "weekday-asia-shanghai-v1";

    private MonitorSeriesDefaults() {
    }

    public static MonitorSeriesConfigV1 initialPboc(OffsetDateTime updatedAt) {
        return new MonitorSeriesConfigV1(
                SchemaV1.VERSION,
                1,
                Mode.FORMAL,
                updatedAt,
                List.of(
                        item(USD_CNY_ITEM_ID, "美元/人民币中间价", "USD", "1美元对人民币", "USD", "CNY/1 USD", updatedAt),
                        item(EUR_CNY_ITEM_ID, "欧元/人民币中间价", "EUR", "1欧元对人民币", "EUR", "CNY/1 EUR", updatedAt)
                )
        );
    }

    private static MonitorSeriesItemV1 item(
            String itemId,
            String displayName,
            String externalCode,
            String sourceFieldKey,
            String baseCurrency,
            String unit,
            OffsetDateTime updatedAt
    ) {
        return new MonitorSeriesItemV1(
                itemId,
                displayName,
                true,
                "PBOC",
                ProviderType.OFFICIAL_WEB,
                AccessMethod.PUBLIC_OFFICIAL_HTML,
                PBOC_SOURCE_NAME,
                RouteDecision.PRIMARY,
                null,
                updatedAt,
                null,
                externalCode,
                sourceFieldKey,
                PBOC_RATE_KIND,
                CALCULATION_VERSION,
                8,
                4,
                RoundingMode.HALF_UP,
                CALENDAR_VERSION,
                "CNY",
                baseCurrency,
                unit
        );
    }
}
