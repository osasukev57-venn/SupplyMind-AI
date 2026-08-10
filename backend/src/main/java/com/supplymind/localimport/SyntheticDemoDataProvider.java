package com.supplymind.localimport;

import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.ProviderCollectOutcome;
import com.supplymind.provider.ProviderCollectRequest;
import com.supplymind.provider.ProviderSourceProfile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * D3-T05 SyntheticDemo provider: deterministic, fixed-seed demo scenarios with an explicit
 * synthetic source identity. It never persists to the formal stores, so synthetic data can
 * never enter daily/aggregate/API/Dashboard/warning/Agent evidence; it is never a route
 * candidate (the resolver excludes SYNTHETIC_DEMO) and never a fallback for missing formal
 * data. The scenario is reproducible: identical seed + scenario version produce identical
 * output.
 */
public final class SyntheticDemoDataProvider implements DataProvider {

    public static final String PROVIDER_ID = "synthetic-demo";
    public static final String DEMO_SEED = "supplymind-demo-seed-v1";
    public static final String SCENARIO_VERSION = "demo-scenario-v1";
    private static final String SOURCE_NAME = "演示合成数据（SyntheticDemo）";
    private static final String SCENARIO_ITEMS = "DEMO.ADC12.001,DEMO.AZ91D.001";

    private final Set<String> supportedItemIds;

    public SyntheticDemoDataProvider(Set<String> supportedItemIds) {
        this.supportedItemIds = Set.copyOf(Objects.requireNonNull(supportedItemIds, "supportedItemIds"));
    }

    @Override
    public ProviderSourceProfile profile() {
        return ProviderSourceProfile.of(
                PROVIDER_ID,
                ProviderType.SYNTHETIC_DEMO,
                AccessMethod.SYNTHETIC_DEMO,
                SOURCE_NAME,
                null,
                true,
                false);
    }

    @Override
    public Set<String> supportedItemIds() {
        return supportedItemIds;
    }

    @Override
    public ProviderCollectOutcome collect(ProviderCollectRequest request) {
        Objects.requireNonNull(request, "request");
        List<RawReceiptV1> raws = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        Random seeded = new Random(DEMO_SEED.hashCode() ^ SCENARIO_VERSION.hashCode());
        OffsetDateTime scenarioTime = OffsetDateTime.parse("2026-08-10T09:30:00+08:00");
        for (String itemId : request.itemIds()) {
            if (!supportedItemIds.contains(itemId)) {
                rejected.put(itemId, "UNSUPPORTED_TARGET");
                continue;
            }
            long demoValue = 10000 + seeded.nextInt(50000);
            String value = demoValue + "." + String.format("%02d", seeded.nextInt(100));
            String runId = "demo-" + itemId + "-20260810-" + SCENARIO_VERSION;
            String rawRef = RawReceiptV1.deriveRawRef(
                    com.supplymind.foundation.model.Mode.FORMAL, ProviderType.SYNTHETIC_DEMO,
                    itemId, scenarioTime, runId);
            byte[] payload = ("SCENARIO=" + SCENARIO_VERSION + ";SEED=" + DEMO_SEED + ";ITEM=" + itemId)
                    .getBytes(StandardCharsets.UTF_8);
            RawReceiptV1 demoRaw = new RawReceiptV1(
                    SchemaV1.VERSION, rawRef, "demo-acq-" + SCENARIO_VERSION, runId,
                    com.supplymind.foundation.model.Mode.FORMAL, ProviderType.SYNTHETIC_DEMO,
                    AccessMethod.SYNTHETIC_DEMO, 1, SOURCE_NAME, null,
                    "SCENARIO=" + SCENARIO_VERSION, itemId, "2026-08-10", "2026-08-10",
                    null, null, scenarioTime, null, value, "元/吨", "CNY",
                    null, null, "text/plain", "base64",
                    Base64.getEncoder().encodeToString(payload), FileDigest.sha256(payload),
                    null, scenarioTime, null);
            raws.add(demoRaw);
        }
        return new ProviderCollectOutcome(
                SchemaV1.VERSION, PROVIDER_ID, "demo-acq-" + SCENARIO_VERSION, "2026-08-10",
                FileDigest.sha256(SCENARIO_VERSION.getBytes(StandardCharsets.UTF_8)),
                raws, List.of(), rejected);
    }

    public static Set<String> defaultScenarioItems() {
        return Set.of(SCENARIO_ITEMS.split(","));
    }
}
