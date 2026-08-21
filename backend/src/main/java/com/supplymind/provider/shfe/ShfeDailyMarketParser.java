package com.supplymind.provider.shfe;

import com.fasterxml.jackson.databind.JsonNode;
import com.supplymind.foundation.codec.JsonV1Codec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Strict parser for SHFE's public daily JSON files. */
final class ShfeDailyMarketParser {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    LocalDate lastCompletedTradingDay(byte[] bytes) {
        try {
            JsonNode root = JsonV1Codec.mapper().readTree(bytes);
            String value = requiredText(root, "lastTradingday");
            return LocalDate.parse(value, BASIC_DATE);
        } catch (RuntimeException | java.io.IOException exception) {
            throw new IllegalArgumentException("SHFE trading-day document does not match the expected contract", exception);
        }
    }

    AdSettlement parseAdMainSettlement(LocalDate businessDate, byte[] bytes) {
        try {
            JsonNode root = JsonV1Codec.mapper().readTree(bytes);
            JsonNode rows = root.get("o_curinstrument");
            if (rows == null || !rows.isArray()) {
                throw new IllegalArgumentException("o_curinstrument is missing");
            }
            List<AdSettlement> candidates = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!"ad_f".equals(text(row, "PRODUCTID").trim())) {
                    continue;
                }
                String deliveryMonth = text(row, "DELIVERYMONTH").trim();
                String settlement = text(row, "SETTLEMENTPRICE").trim();
                String volumeText = text(row, "VOLUME").trim();
                if (!deliveryMonth.matches("\\d{4}") || settlement.isBlank() || volumeText.isBlank()) {
                    continue;
                }
                BigDecimal value = new BigDecimal(settlement);
                long volume = new BigDecimal(volumeText).longValueExact();
                if (value.signum() <= 0 || volume < 0) {
                    continue;
                }
                candidates.add(new AdSettlement(businessDate, deliveryMonth,
                        value.stripTrailingZeros().toPlainString(), volume));
            }
            return candidates.stream()
                    .max(Comparator.comparingLong(AdSettlement::volume)
                            .thenComparing(AdSettlement::deliveryMonth, Comparator.reverseOrder()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No usable SHFE cast-aluminium-alloy settlement row for " + businessDate));
        } catch (RuntimeException | java.io.IOException exception) {
            if (exception instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("SHFE daily document does not match the expected contract", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    record AdSettlement(LocalDate businessDate, String deliveryMonth, String settlementPrice, long volume) {
    }
}
