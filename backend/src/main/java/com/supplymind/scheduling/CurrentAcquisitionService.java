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
 * Non-blocking current PBOC acquisition boundary used by the desktop bootstrap and the
 * operator refresh endpoint. The actual business chain remains owned by
 * {@link PbocDay2CollectionService}; this class only exposes honest observable state.
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

    private final PbocDay2CollectionService collectionService;
    private final Clock clock;
    private final Executor executor;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status("IDLE", null, "Current acquisition has not run", null));

    public CurrentAcquisitionService(
            PbocDay2CollectionService collectionService,
            Clock clock,
            Executor executor
    ) {
        this.collectionService = Objects.requireNonNull(collectionService, "collectionService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public Status status() {
        return status.get();
    }

    /**
     * Starts one cycle if none is running. The method always returns immediately, so an
     * unavailable public website can never block Spring Boot or the desktop window.
     */
    public Status trigger() {
        if (!inFlight.compareAndSet(false, true)) {
            return status.get();
        }
        status.set(new Status("RUNNING", null, "Fetching latest official PBOC rates", now()));
        try {
            executor.execute(this::runCycle);
        } catch (RuntimeException exception) {
            inFlight.set(false);
            status.set(new Status("FAILED", null,
                    "Official PBOC rates are temporarily unavailable", now()));
            LOGGER.warn("pboc_current_acquisition scheduling_failed failure={}",
                    exception.getClass().getSimpleName());
        }
        return status.get();
    }

    private void runCycle() {
        try {
            Day2CycleResult result = collectionService.runImmediateCycle();
            status.set(new Status("SUCCEEDED", result.businessDate(),
                    "Latest official PBOC rates are available", now()));
            LOGGER.info("pboc_current_acquisition outcome=SUCCESS businessDate={} payloadSha256={}",
                    result.businessDate(), result.payloadSha256());
        } catch (RuntimeException exception) {
            status.set(new Status("FAILED", null,
                    "Official PBOC rates are temporarily unavailable", now()));
            LOGGER.warn("pboc_current_acquisition outcome=FAILED failure={}",
                    exception.getClass().getSimpleName());
        } finally {
            inFlight.set(false);
        }
    }

    private String now() {
        return OffsetDateTime.now(clock).toString();
    }
}
