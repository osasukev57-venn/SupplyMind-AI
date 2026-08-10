package com.supplymind.scheduling;

import java.util.List;

/** Result of one D2-T05 immediate Day 2 cycle across both PBOC currencies. */
public record Day2CycleResult(
        String businessDate,
        String acquisitionId,
        String payloadSha256,
        String usdRunId,
        String eurRunId,
        String usdRawRef,
        String eurRawRef,
        String usdRawValue,
        String eurRawValue,
        String usdDailyRef,
        String eurDailyRef,
        List<String> usdAggregateRefs,
        List<String> eurAggregateRefs,
        int usdDailyRowCount,
        int eurDailyRowCount
) {
    public Day2CycleResult {
        usdAggregateRefs = List.copyOf(usdAggregateRefs);
        eurAggregateRefs = List.copyOf(eurAggregateRefs);
    }
}
