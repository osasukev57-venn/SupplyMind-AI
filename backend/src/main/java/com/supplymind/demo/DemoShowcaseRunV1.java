package com.supplymind.demo;

import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.SchemaV1;

import java.time.OffsetDateTime;
import java.util.List;

/** Immutable audit record for one fully calculated, strictly DEMO-isolated showcase run. */
public record DemoShowcaseRunV1(
        String schemaVersion,
        String demoRef,
        String scenarioId,
        String scenarioVersion,
        String mode,
        String status,
        OffsetDateTime generatedAt,
        List<String> stages,
        List<DemoItemResult> items
) {
    public DemoShowcaseRunV1 {
        ModelRules.schemaVersion(schemaVersion);
        ModelRules.relativeDataRef(demoRef, "demoRef");
        ModelRules.id(scenarioId, "scenarioId");
        ModelRules.nonBlank(scenarioVersion, "scenarioVersion");
        if (!"DEMO".equals(mode)) {
            throw new IllegalArgumentException("DemoShowcaseRunV1.mode must be DEMO");
        }
        if (!"COMPLETE".equals(status)) {
            throw new IllegalArgumentException("DemoShowcaseRunV1.status must be COMPLETE");
        }
        ModelRules.dateTime(generatedAt, "generatedAt");
        stages = List.copyOf(stages == null ? List.of() : stages);
        items = List.copyOf(items == null ? List.of() : items);
        if (stages.isEmpty() || items.isEmpty()) {
            throw new IllegalArgumentException("Demo showcase requires stages and item results");
        }
        if (!demoRef.equals(ref(scenarioId))) {
            throw new IllegalArgumentException("demoRef must be derived from scenarioId");
        }
    }

    public static String ref(String scenarioId) {
        return "demo/showcase/" + scenarioId + ".json";
    }

    public record DemoItemResult(
            String itemId,
            String runId,
            String rawRef,
            String payloadSha256,
            String businessDate,
            String sourceName,
            String value,
            String unit,
            String validationStatus,
            String validationVersion,
            String dailyAverage,
            String monthlyAverage,
            String quarterlyAverage,
            String halfyearAverage,
            String yearlyAverage,
            String warningOutcome
    ) {
        public DemoItemResult {
            ModelRules.id(itemId, "demo.itemId");
            ModelRules.id(runId, "demo.runId");
            ModelRules.relativeDataRef(rawRef, "demo.rawRef");
            ModelRules.sha256(payloadSha256, "demo.payloadSha256");
            ModelRules.isoDateText(businessDate, "demo.businessDate");
            ModelRules.nonBlank(sourceName, "demo.sourceName");
            ModelRules.nonBlank(value, "demo.value");
            ModelRules.nonBlank(unit, "demo.unit");
            ModelRules.nonBlank(validationStatus, "demo.validationStatus");
            ModelRules.nonBlank(validationVersion, "demo.validationVersion");
            ModelRules.nonBlank(dailyAverage, "demo.dailyAverage");
            ModelRules.nonBlank(monthlyAverage, "demo.monthlyAverage");
            ModelRules.nonBlank(quarterlyAverage, "demo.quarterlyAverage");
            ModelRules.nonBlank(halfyearAverage, "demo.halfyearAverage");
            ModelRules.nonBlank(yearlyAverage, "demo.yearlyAverage");
            ModelRules.nonBlank(warningOutcome, "demo.warningOutcome");
        }
    }
}
