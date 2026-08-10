package com.supplymind.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * Optional scheduled Day 2 collection trigger. Disabled by default
 * (supplymind.scheduler.enabled=false); the bean only exists when explicitly enabled.
 */
public final class PbocDay2Scheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PbocDay2Scheduler.class);

    private final PbocDay2CollectionService collectionService;

    public PbocDay2Scheduler(PbocDay2CollectionService collectionService) {
        this.collectionService = Objects.requireNonNull(collectionService, "collectionService");
    }

    @Scheduled(cron = "${supplymind.scheduler.cron:0 30 9 * * MON-FRI}")
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
