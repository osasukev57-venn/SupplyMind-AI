package com.supplymind.foundation.model;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/** Formal v1 PBOC defaults plus the frozen Day-3 material delivery default. Golden fixture overrides remain test-only and are not production defaults. */
public final class MonitorSeriesDefaults {
    public static final String USD_CNY_ITEM_ID = "FX.USD.CNY.PBOC_MID";
    public static final String EUR_CNY_ITEM_ID = "FX.EUR.CNY.PBOC_MID";
    public static final String PBOC_SOURCE_NAME = "中国人民银行官网（授权中国外汇交易中心公布）";
    public static final String PBOC_RATE_KIND = "人民币汇率中间价";
    public static final String CALCULATION_VERSION = "arithmetic-mean-v1";
    public static final String CALENDAR_VERSION = "weekday-asia-shanghai-v1";

    public static final String ADC12_SMM_ITEM_ID = "MAT.ADC12.SMM";
    public static final String AZ91D_SMM_ITEM_ID = "MAT.AZ91D.SMM";
    public static final String ADC12_AM_ITEM_ID = "MAT.ADC12.AM";
    public static final String AZ91D_AM_ITEM_ID = "MAT.AZ91D.AM";
    public static final String MANUAL_INGRESS_SOURCE_NAME = "人工录入（Manual）";
    public static final String MATERIAL_FALLBACK_REASON = "MANUAL_FALLBACK";

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

    /**
     * The production delivery default: the PBOC pair plus the four P0 material sequences
     * (SMM/Asian Metal intent x ADC12/AZ91D) on their frozen legal Manual route. The normal
     * startup path activates this via ConfigActivationStore; it is plain data in the existing
     * monitor-series schema (no route logic scattered in services) and does not lower the
     * H07/H08 dynamic-target capability (items stay configuration-driven).
     */
    public static MonitorSeriesConfigV1 initialDay3(OffsetDateTime updatedAt) {
        return new MonitorSeriesConfigV1(
                SchemaV1.VERSION,
                1,
                Mode.FORMAL,
                updatedAt,
                List.of(
                        item(USD_CNY_ITEM_ID, "美元/人民币中间价", "USD", "1美元对人民币", "USD", "CNY/1 USD", updatedAt),
                        item(EUR_CNY_ITEM_ID, "欧元/人民币中间价", "EUR", "1欧元对人民币", "EUR", "CNY/1 EUR", updatedAt),
                        materialItem(ADC12_SMM_ITEM_ID, "ADC12铝合金锭（SMM意图）", "SMM", "ADC12", updatedAt),
                        materialItem(AZ91D_SMM_ITEM_ID, "AZ91D镁合金锭（SMM意图）", "SMM", "AZ91D", updatedAt),
                        materialItem(ADC12_AM_ITEM_ID, "ADC12铝合金锭（Asian Metal意图）", "Asian Metal", "ADC12", updatedAt),
                        materialItem(AZ91D_AM_ITEM_ID, "AZ91D镁合金锭（Asian Metal意图）", "Asian Metal", "AZ91D", updatedAt)
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
                unit,
                null
        );
    }

    private static MonitorSeriesItemV1 materialItem(
            String itemId,
            String displayName,
            String sourceIntent,
            String externalCode,
            OffsetDateTime updatedAt
    ) {
        return new MonitorSeriesItemV1(
                itemId,
                displayName,
                true,
                sourceIntent,
                ProviderType.MANUAL,
                AccessMethod.MANUAL,
                MANUAL_INGRESS_SOURCE_NAME,
                RouteDecision.FALLBACK_MANUAL,
                MATERIAL_FALLBACK_REASON,
                updatedAt,
                null,
                externalCode,
                "material-field-key",
                "material",
                CALCULATION_VERSION,
                2,
                2,
                RoundingMode.HALF_UP,
                CALENDAR_VERSION,
                "CNY",
                "CNY",
                "元/吨",
                new MaterialValidationConfigV1("0", null, 7, externalCode, List.of())
        );
    }
}
