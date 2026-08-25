package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.history.HistoryQueryService;
import com.supplymind.processing.CostImpactCalculator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * DEC-064 deterministic formal relative-change evaluation over published daily rows.
 * The current point is the latest date at or before asOf; the baseline is the latest published
 * point at or before currentDate-lookbackDays. Missing data never becomes zero.
 */
public final class FormalWarningEvaluationService {

    private static final int BASELINE_SEARCH_BUFFER_DAYS = 45;

    private final HistoryQueryService history;
    private final WarningStore warningStore;

    public FormalWarningEvaluationService(HistoryQueryService history, WarningStore warningStore) {
        this.history = Objects.requireNonNull(history, "history");
        this.warningStore = Objects.requireNonNull(warningStore, "warningStore");
    }

    public WarningEvaluationV1 evaluate(WarningRuleConfigV1 rule, LocalDate asOf) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(asOf, "asOf");
        if (!rule.enabled()) {
            return outcome(rule, WarningEvaluationV1.DISABLED, "规则已停用",
                    null, null, null, null, null, null, null);
        }
        LocalDate from = asOf.minusDays(rule.lookbackDays() + BASELINE_SEARCH_BUFFER_DAYS);
        HistoryQueryService.DailyHistoryResult result = history.queryDaily(rule.itemId(), from, asOf);
        if (!result.corruptRefs().isEmpty() || !result.conflictKeys().isEmpty()) {
            return outcome(rule, WarningEvaluationV1.INVALID_DATA,
                    "历史文件损坏或存在冲突，已停止预警计算",
                    null, null, null, null, null, null, null);
        }
        if (result.rows().isEmpty()) {
            return outcome(rule, WarningEvaluationV1.INSUFFICIENT_DATA,
                    "没有可用于预警计算的已发布数据",
                    null, null, null, null, null, null, null);
        }

        LocalDate currentDate = result.rows().stream()
                .map(row -> LocalDate.parse(row.businessDate()))
                .max(Comparator.naturalOrder()).orElseThrow();
        RowSelection currentSelection = uniqueRowOn(result.rows(), currentDate);
        if (!currentSelection.valid()) {
            return outcome(rule, WarningEvaluationV1.INVALID_DATA, currentSelection.message(),
                    null, null, null, null, null, null, null);
        }
        DailyRecordV1 current = currentSelection.row();
        LocalDate baselineTarget = currentDate.minusDays(rule.lookbackDays());
        LocalDate baselineDate = result.rows().stream()
                .map(row -> LocalDate.parse(row.businessDate()))
                .filter(date -> !date.isAfter(baselineTarget))
                .max(Comparator.naturalOrder()).orElse(null);
        if (baselineDate == null) {
            return outcome(rule, WarningEvaluationV1.INSUFFICIENT_DATA,
                    "缺少周期起点或此前最近的已发布数据",
                    current.businessDate(), current.avg(), null, null, null, current.unit(), null);
        }
        RowSelection baselineSelection = uniqueRowOn(result.rows(), baselineDate);
        if (!baselineSelection.valid()) {
            return outcome(rule, WarningEvaluationV1.INVALID_DATA, baselineSelection.message(),
                    current.businessDate(), current.avg(), null, null, null, current.unit(), null);
        }
        DailyRecordV1 baseline = baselineSelection.row();
        if (!Objects.equals(current.unit(), baseline.unit())
                || !Objects.equals(current.currency(), baseline.currency())) {
            return outcome(rule, WarningEvaluationV1.INVALID_DATA,
                    "当前值与基准值的单位或币种不一致",
                    current.businessDate(), current.avg(), baseline.businessDate(), baseline.avg(),
                    null, current.unit(), null);
        }
        BigDecimal currentValue = new BigDecimal(current.avg());
        BigDecimal baselineValue = new BigDecimal(baseline.avg());
        if (baselineValue.signum() == 0) {
            return outcome(rule, WarningEvaluationV1.INVALID_DATA, "基准值为 0，无法计算变化率",
                    current.businessDate(), current.avg(), baseline.businessDate(), baseline.avg(),
                    null, current.unit(), null);
        }
        BigDecimal change = CostImpactCalculator.changeRatio(currentValue, baselineValue);
        boolean triggered = switch (rule.direction()) {
            case UP -> change.compareTo(rule.thresholdValue()) >= 0;
            case DOWN -> change.compareTo(rule.thresholdValue().negate()) <= 0;
            case BOTH -> change.abs().compareTo(rule.thresholdValue()) >= 0;
        };
        if (!triggered) {
            return outcome(rule, WarningEvaluationV1.NOT_TRIGGERED, "当前变化未达到预警阈值",
                    current.businessDate(), current.avg(), baseline.businessDate(), baseline.avg(),
                    change.toPlainString(), current.unit(), null);
        }

        List<String> evidenceRefs = new ArrayList<>(new LinkedHashSet<>(List.of(
                DataPaths.dailyRef(rule.itemId(), YearMonth.from(baselineDate)),
                DataPaths.dailyRef(rule.itemId(), YearMonth.from(currentDate)))));
        OffsetDateTime evaluatedAt = current.updatedAt().isAfter(baseline.updatedAt())
                ? current.updatedAt() : baseline.updatedAt();
        String fingerprint = fingerprint(rule, current, baseline, change, evidenceRefs);
        String warningId = JsonV1Codec.sha256LowerHex(
                (rule.toString() + "|" + currentDate + "|" + baselineDate + "|" + fingerprint)
                        .getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        WarningRecordV1 warning = new WarningRecordV1(
                "1.0", warningId, rule.ruleId(), rule.ruleVersion(), rule.itemId(), "day",
                baseline.businessDate(), current.businessDate(), current.businessDate(),
                rule.threshold(), current.avg(), baseline.avg(), WarningRecordV1.RiskLevel.HIGH,
                evidenceRefs, "PUBLISHED_VERIFIED", evaluatedAt, fingerprint, false,
                rule.description());
        warningStore.store(warning);
        return outcome(rule, WarningEvaluationV1.TRIGGERED, "当前变化已达到预警阈值",
                current.businessDate(), current.avg(), baseline.businessDate(), baseline.avg(),
                change.toPlainString(), current.unit(), warning);
    }

    private static RowSelection uniqueRowOn(List<DailyRecordV1> rows, LocalDate date) {
        List<DailyRecordV1> selected = rows.stream()
                .filter(row -> row.businessDate().equals(date.toString()))
                .sorted(DailyRecordV1.ORDER)
                .toList();
        if (selected.isEmpty()) {
            return new RowSelection(null, false, "指定日期没有可用 daily 数据");
        }
        DailyRecordV1 first = selected.get(0);
        boolean equivalent = selected.stream().allMatch(row ->
                row.avg().equals(first.avg())
                        && row.unit().equals(first.unit())
                        && row.currency().equals(first.currency()));
        if (!equivalent) {
            return new RowSelection(null, false,
                    "同一业务日期存在多个不一致的正式 daily 行，已停止预警计算");
        }
        return new RowSelection(first, true, null);
    }

    private static String fingerprint(
            WarningRuleConfigV1 rule,
            DailyRecordV1 current,
            DailyRecordV1 baseline,
            BigDecimal change,
            List<String> evidenceRefs
    ) {
        List<String> canonical = new ArrayList<>();
        canonical.add(rule.toString());
        canonical.add(current.businessDate());
        canonical.add(current.avg());
        canonical.add(baseline.businessDate());
        canonical.add(baseline.avg());
        canonical.add(change.toPlainString());
        canonical.addAll(evidenceRefs.stream().sorted().toList());
        return JsonV1Codec.sha256LowerHex(
                String.join("\u0000", canonical).getBytes(StandardCharsets.UTF_8));
    }

    private static WarningEvaluationV1 outcome(
            WarningRuleConfigV1 rule,
            String status,
            String message,
            String currentBusinessDate,
            String currentValue,
            String baselineBusinessDate,
            String baselineValue,
            String changeRatio,
            String unit,
            WarningRecordV1 warning
    ) {
        return new WarningEvaluationV1(
                rule, status, message, currentBusinessDate, currentValue,
                baselineBusinessDate, baselineValue, changeRatio, unit, warning);
    }

    private record RowSelection(DailyRecordV1 row, boolean valid, String message) {
    }
}
