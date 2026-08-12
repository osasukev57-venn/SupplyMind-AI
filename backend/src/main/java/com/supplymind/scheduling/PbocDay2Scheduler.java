package com.supplymind.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Manual (non-scheduled) Day 2 collection trigger. Since the M1 production-path fix the
 * application has exactly one formal {@code @Scheduled} acquisition entry -
 * {@link RotationGuardConfiguration.RotationGuardedScheduler#runGuardedCycle()} - which passes
 * through the rotation guard. This class deliberately carries NO {@code @Scheduled} annotation:
 * it exists only for explicit/manual invocation (e.g. CLI or operator trigger), so an unguarded
 * scheduled cycle can never run in parallel with the guarded one.
 */
public final class PbocDay2Scheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PbocDay2Scheduler.class);

    private final PbocDay2CollectionService collectionService;

    public PbocDay2Scheduler(PbocDay2CollectionService collectionService) {
        this.collectionService = Objects.requireNonNull(collectionService, "collectionService");
    }

    /** Manual trigger only - never scheduled. Prefer the guarded scheduler for timed execution. */
    public void collectOnSchedule() {
        try {
            Day2CycleResult result = collectionService.runImmediateCycle();
            LOGGER.info("pboc_day2_scheduled businessDate={} payloadSha256={} usdDailyRows={} eurDailyRows={}",
                    result.businessDate(), result.payloadSha256(), result.usdDailyRowCount(), result.eurDailyRowCount());
        } catch (RuntimeException exception) {
            LOGGER.warn("pboc_day2_scheduled failed businessDateUnknown failure={}",
                    exception.getClass().getSimpleName());
        }
    }
}
