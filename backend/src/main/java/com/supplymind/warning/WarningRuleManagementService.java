package com.supplymind.warning;

import com.supplymind.config.ConfigManagementService;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.StorageException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DEC-064 default-plus-override rule management. Every enabled formal/local-import series gets
 * a usable system rule without operator setup. User changes are per item and versioned.
 */
public final class WarningRuleManagementService {

    public static final String FX_DEFAULT_THRESHOLD = "0.01";
    public static final int FX_DEFAULT_LOOKBACK_DAYS = 1;
    public static final String MATERIAL_DEFAULT_THRESHOLD = "0.05";
    public static final int MATERIAL_DEFAULT_LOOKBACK_DAYS = 7;

    private final ConfigManagementService configs;
    private final WarningRuleStore store;
    private final FormalWarningEvaluationService evaluator;
    private final Clock clock;

    public WarningRuleManagementService(
            ConfigManagementService configs,
            WarningRuleStore store,
            FormalWarningEvaluationService evaluator,
            Clock clock
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.store = Objects.requireNonNull(store, "store");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<BoundRule> listRules() {
        Map<String, WarningRuleConfigV1> overrides = new LinkedHashMap<>();
        store.readActive().ifPresent(set -> set.overrides()
                .forEach(rule -> overrides.put(rule.itemId(), rule)));
        List<BoundRule> result = new ArrayList<>();
        for (MonitorSeriesItemV1 item : configs.active().items()) {
            if (!item.enabled() || item.routeDecision() == RouteDecision.SYNTHETIC_DEMO) {
                continue;
            }
            WarningRuleConfigV1 rule = overrides.getOrDefault(item.itemId(), defaultRule(item));
            result.add(new BoundRule(
                    item.itemId(), item.displayName(), item.actualSourceName(), item.unit(),
                    item.rateKind(), true, rule));
        }
        return List.copyOf(result);
    }

    public List<WarningEvaluationV1> evaluateAll() {
        LocalDate asOf = LocalDate.now(clock.withZone(DataPaths.SHANGHAI));
        return listRules().stream()
                .map(bound -> evaluator.evaluate(bound.rule(), asOf))
                .toList();
    }

    public BoundRule update(
            String itemId,
            int lookbackDays,
            String threshold,
            WarningRuleConfigV1.Direction direction,
            boolean enabled,
            String description
    ) {
        MonitorSeriesItemV1 item = requireActiveItem(itemId);
        List<WarningRuleConfigV1> overrides = new ArrayList<>(
                store.readActive().map(WarningRuleSetV1::overrides).orElse(List.of()));
        overrides.removeIf(rule -> rule.itemId().equals(itemId));
        int nextVersion = store.readActive().map(set -> set.configVersion() + 1).orElse(1);
        String safeDescription = description == null || description.isBlank()
                ? "用户为 " + item.displayName() + " 配置的相对变化预警"
                : description.trim();
        WarningRuleConfigV1 custom = new WarningRuleConfigV1(
                "1.0", "custom-warning-" + item.itemId(),
                WarningRuleConfigV1.VERSION + "-custom-r" + nextVersion,
                item.itemId(), item.rateKind(), lookbackDays, threshold, direction, enabled,
                WarningRuleConfigV1.Origin.USER_CUSTOM, safeDescription);
        overrides.add(custom);
        store.activate(overrides);
        return bound(item, custom);
    }

    public BoundRule reset(String itemId) {
        MonitorSeriesItemV1 item = requireActiveItem(itemId);
        List<WarningRuleConfigV1> overrides = new ArrayList<>(
                store.readActive().map(WarningRuleSetV1::overrides).orElse(List.of()));
        boolean removed = overrides.removeIf(rule -> rule.itemId().equals(itemId));
        if (removed) {
            store.activate(overrides);
        }
        return bound(item, defaultRule(item));
    }

    public WarningEvaluationV1 evaluateItem(String itemId) {
        BoundRule bound = listRules().stream()
                .filter(value -> value.itemId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new StorageException("no enabled monitored item: " + itemId));
        LocalDate asOf = LocalDate.now(clock.withZone(DataPaths.SHANGHAI));
        return evaluator.evaluate(bound.rule(), asOf);
    }

    private MonitorSeriesItemV1 requireActiveItem(String itemId) {
        return configs.active().items().stream()
                .filter(item -> item.itemId().equals(itemId))
                .filter(MonitorSeriesItemV1::enabled)
                .filter(item -> item.routeDecision() != RouteDecision.SYNTHETIC_DEMO)
                .findFirst()
                .orElseThrow(() -> new StorageException("no enabled monitored item: " + itemId));
    }

    private static WarningRuleConfigV1 defaultRule(MonitorSeriesItemV1 item) {
        boolean material = "material".equals(item.rateKind());
        int lookback = material ? MATERIAL_DEFAULT_LOOKBACK_DAYS : FX_DEFAULT_LOOKBACK_DAYS;
        String threshold = material ? MATERIAL_DEFAULT_THRESHOLD : FX_DEFAULT_THRESHOLD;
        String description = material
                ? "系统通用材料预警模板：7 个自然日双向变化达到 5%"
                : "系统通用汇率预警模板：1 个自然日双向变化达到 1%";
        return new WarningRuleConfigV1(
                "1.0", "default-warning-" + item.itemId(), WarningRuleConfigV1.VERSION,
                item.itemId(), item.rateKind(), lookback, threshold,
                WarningRuleConfigV1.Direction.BOTH, true,
                WarningRuleConfigV1.Origin.SYSTEM_DEFAULT, description);
    }

    private static BoundRule bound(MonitorSeriesItemV1 item, WarningRuleConfigV1 rule) {
        return new BoundRule(item.itemId(), item.displayName(), item.actualSourceName(),
                item.unit(), item.rateKind(), item.enabled(), rule);
    }

    public record BoundRule(
            String itemId,
            String displayName,
            String actualSourceName,
            String unit,
            String rateKind,
            boolean monitorEnabled,
            WarningRuleConfigV1 rule
    ) {
    }
}
