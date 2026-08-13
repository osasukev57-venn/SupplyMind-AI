package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.history.HistoryQueryService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * D5-T05 deterministic warning evaluation (AT-ALT-001 backend). Inputs are exclusively
 * published/processed data (aggregate/daily files that by construction only contain
 * PUBLISHED+VERIFIED-class rows), so PENDING/REJECTED/CONFLICT/DEMO can never trigger a
 * formal warning. All arithmetic is BigDecimal. Rules are explicit TEST/DEMO configurations
 * (EXT-07/EXT-08 thresholds not yet confirmed); the same logical inputs produce the same
 * fingerprint-derived warningId, so re-runs never duplicate business warnings.
 */
public final class WarningService {

    private final DataRoot dataRoot;
    private final WarningStore store;
    private final Clock clock;
    private final HistoryQueryService history;

    public WarningService(DataRoot dataRoot, WarningStore store, Clock clock, HistoryQueryService history) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.history = Objects.requireNonNull(history, "history");
    }

    /** Evaluate one rule against one aggregate period; persists the warning if triggered. */
    public WarningRecordV1 evaluate(WarningRuleV1 rule, String periodStart, String periodEnd) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodStart.compareTo(periodEnd) > 0) {
            throw new com.supplymind.foundation.storage.StorageException(
                    "warning evaluation periodStart must not be after periodEnd");
        }
        if (rule.ruleKind() == WarningRuleV1.RuleKind.DATA_QUALITY) {
            return evaluateDataQuality(rule, periodStart, periodEnd);
        }
        AggregateRecordV1 current = currentPeriodRow(rule.itemId(), rule.grain(), periodStart, periodEnd);
        if (current == null) {
            return null; // no published data in the period: nothing to warn about
        }
        AggregateRecordV1 baseline = previousPeriodRow(rule.itemId(), rule.grain(), periodStart);
        if (baseline == null || new BigDecimal(baseline.avg()).signum() == 0) {
            return null; // no comparable baseline: deterministically no price/rate warning
        }
        BigDecimal currentAvg = new BigDecimal(current.avg());
        BigDecimal baselineAvg = new BigDecimal(baseline.avg());
        BigDecimal changeRatio = currentAvg.subtract(baselineAvg)
                .divide(baselineAvg, 12, java.math.RoundingMode.HALF_UP);
        boolean triggered = rule.direction() == WarningRuleV1.Direction.ABOVE
                ? changeRatio.compareTo(rule.thresholdValue()) > 0
                : changeRatio.compareTo(rule.thresholdValue()) < 0;
        if (!triggered) {
            return null;
        }
        WarningRecordV1 warning = warning(
                rule, periodStart, periodEnd, null,
                changeRatio.toPlainString(), rule.thresholdValue().toPlainString(),
                rule.ruleKind() == WarningRuleV1.RuleKind.COST_IMPACT
                        ? costImpact(rule, changeRatio).toPlainString()
                        : changeRatio.toPlainString(),
                WarningRecordV1.RiskLevel.HIGH,
                List.of(DataPaths.aggregateRef(rule.itemId(), rule.grain(),
                        LocalDate.parse(periodStart).getYear())),
                current.calculatedAt());
        store.store(warning);
        return warning;
    }

    private WarningRecordV1 evaluateDataQuality(WarningRuleV1 rule, String periodStart, String periodEnd) {
        List<DailyRecordV1> rows = history.queryDaily(
                rule.itemId(), LocalDate.parse(periodStart), LocalDate.parse(periodEnd)).rows();
        long missing = rows.stream().mapToLong(DailyRecordV1::missingCount).sum();
        long expected = rows.stream().mapToLong(DailyRecordV1::expectedCount).sum();
        if (expected == 0 || missing == 0) {
            return null;
        }
        BigDecimal completeness = BigDecimal.valueOf(expected - missing)
                .divide(BigDecimal.valueOf(expected), 12, java.math.RoundingMode.HALF_UP);
        boolean triggered = completeness.compareTo(rule.thresholdValue()) < 0;
        if (!triggered) {
            return null;
        }
        List<String> evidenceRefs = List.of(DataPaths.dailyRef(
                rule.itemId(), java.time.YearMonth.parse(periodStart.substring(0, 7))));
        OffsetDateTime lineageEvaluatedAt = rows.stream()
                .map(DailyRecordV1::updatedAt)
                .max(java.util.Comparator.naturalOrder())
                .orElseThrow(() -> new com.supplymind.foundation.storage.StorageException(
                        "data-quality warning requires at least one daily row with updatedAt"));
        WarningRecordV1 warning = warning(
                rule, periodStart, periodEnd, null,
                completeness.toPlainString(), rule.thresholdValue().toPlainString(),
                String.valueOf(missing),
                completeness.compareTo(new BigDecimal("0.5")) < 0
                        ? WarningRecordV1.RiskLevel.HIGH : WarningRecordV1.RiskLevel.MEDIUM,
                evidenceRefs,
                lineageEvaluatedAt);
        store.store(warning);
        return warning;
    }

    private BigDecimal costImpact(WarningRuleV1 rule, BigDecimal changeRatio) {
        // EXT-08 cost weights are not confirmed: a demo weight of exactly 1 is used and the
        // rule is explicitly marked demoRule. BigDecimal only, never double/float.
        return changeRatio.multiply(new BigDecimal("1"), java.math.MathContext.DECIMAL64)
                .setScale(12, java.math.RoundingMode.HALF_UP);
    }

    private AggregateRecordV1 currentPeriodRow(String itemId, String grain, String start, String end) {
        int year = LocalDate.parse(start).getYear();
        return history.queryAggregate(itemId, grain, year, year).rows().stream()
                .filter(row -> row.periodStart().equals(start) && row.periodEnd().equals(end))
                .findFirst().orElse(null);
    }

    private AggregateRecordV1 previousPeriodRow(String itemId, String grain, String periodStart) {
        java.time.YearMonth month = java.time.YearMonth.parse(periodStart.substring(0, 7));
        java.time.YearMonth previous = switch (grain) {
            case "month" -> month.minusMonths(1);
            case "quarter" -> month.minusMonths(3);
            case "halfyear" -> month.minusMonths(6);
            case "year" -> month.minusYears(1);
            default -> throw new com.supplymind.foundation.storage.StorageException("Unsupported grain: " + grain);
        };
        java.time.YearMonth previousEnd = switch (grain) {
            case "month" -> previous;
            case "quarter" -> previous.plusMonths(2);
            case "halfyear" -> previous.plusMonths(5);
            case "year" -> previous.plusMonths(11);
            default -> throw new com.supplymind.foundation.storage.StorageException("Unsupported grain: " + grain);
        };
        return history.queryAggregate(itemId, grain, previous.getYear(), previous.getYear()).rows().stream()
                .filter(row -> row.periodStart().equals(previous.atDay(1).toString())
                        && row.periodEnd().equals(previousEnd.atEndOfMonth().toString()))
                .findFirst().orElse(null);
    }

    private WarningRecordV1 warning(
            WarningRuleV1 rule, String periodStart, String periodEnd, String businessDate,
            String current, String threshold, String baseline, WarningRecordV1.RiskLevel riskLevel,
            List<String> evidenceRefs, OffsetDateTime lineageEvaluatedAt
    ) {
        String fingerprint = fingerprint(rule, periodStart, periodEnd, current, evidenceRefs);
        String warningId = JsonV1Codec.sha256LowerHex(
                (rule.toString() + "|" + periodStart + "|" + periodEnd + "|" + fingerprint)
                        .getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        return new WarningRecordV1(
                "1.0", warningId, rule.ruleId(), rule.ruleVersion(), rule.itemId(), rule.grain(),
                periodStart, periodEnd, businessDate, threshold, current, baseline, riskLevel,
                evidenceRefs, "PUBLISHED_VERIFIED", lineageEvaluatedAt, fingerprint,
                rule.demoRule(), rule.description());
    }

    private static String fingerprint(WarningRuleV1 rule, String start, String end,
                                      String current, List<String> evidenceRefs) {
        List<String> canonical = new ArrayList<>();
        canonical.add(rule.toString());
        canonical.add(start);
        canonical.add(end);
        canonical.add(current == null ? "" : current);
        evidenceRefs.stream().sorted().forEach(canonical::add);
        return JsonV1Codec.sha256LowerHex(
                String.join("\u0000", canonical).getBytes(StandardCharsets.UTF_8));
    }

    /** Demo rule factory: clearly marked non-final (EXT-07/EXT-08 still open). */
    public static WarningRuleV1 demoPriceChangeRule(String itemId, String grain, String threshold) {
        return new WarningRuleV1(
                "demo-price-change-" + itemId, "demo-v1", WarningRuleV1.RuleKind.PRICE_CHANGE,
                itemId, grain, threshold, WarningRuleV1.Direction.ABOVE, 1, true,
                "TEST/DEMO threshold - not a final business threshold (EXT-07 open)");
    }
}
