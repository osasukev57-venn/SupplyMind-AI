package com.supplymind.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking current acquisition boundary used by the desktop bootstrap and the
 * operator refresh endpoint. The actual business chain remains owned by
 * {@link ActiveCurrentCollectionService}; this class only exposes honest observable state.
 */
public final class CurrentAcquisitionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CurrentAcquisitionService.class);

    public record Status(
            String state,
            String businessDate,
            String message,
            String updatedAt
    ) {
    }

    private final ActiveCurrentCollectionService collectionService;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status("IDLE", null, "Current acquisition has not run", null));

    public CurrentAcquisitionService(
            ActiveCurrentCollectionService collectionService,
            Clock clock,
            Executor executor
    ) {
        this.collectionService = Objects.requireNonNull(collectionService, "collectionService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public synchronized Status status() {
        return status.get();
    }

    /**
     * Starts one cycle if none is running. The method always returns immediately, so an
     * unavailable public website can never block Spring Boot or the desktop window.
     */
    public synchronized Status trigger() {
        if (!inFlight.compareAndSet(false, true)) {
            return status.get();
        }
        status.set(new Status("RUNNING", null, "正在处理启用的自动监测项", now()));
        try {
            executor.execute(this::runCycle);
        } catch (RuntimeException exception) {
            inFlight.set(false);
            status.set(new Status("FAILED", null,
                    "当前采集未完成，请查看来源状态后重试", now()));
            LOGGER.warn("current_acquisition scheduling_failed failure={}",
                    exception.getClass().getSimpleName());
        }
        return status.get();
    }

    private void runCycle() {
        Status completed = new Status("FAILED", null, "当前采集未完成，请查看来源状态后重试", now());
        try {
            ActiveCurrentCollectionService.Result result = collectionService.runImmediateCycle();
            completed = new Status(result.state(), result.businessDate(), result.message(), now());
            LOGGER.info("current_acquisition outcome={}", result.state());
        } catch (RuntimeException exception) {
            LOGGER.warn("current_acquisition outcome=FAILED failure={}", exception.getClass().getSimpleName());
        } finally {
            complete(completed);
        }
    }

    private synchronized void complete(Status completed) {
        inFlight.set(false);
        status.set(completed);
    }

    private String now() {
        return OffsetDateTime.now(clock).toString();
    }
}
