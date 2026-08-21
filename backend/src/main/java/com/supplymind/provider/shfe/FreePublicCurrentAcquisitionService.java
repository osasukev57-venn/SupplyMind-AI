package com.supplymind.provider.shfe;

import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.storage.ConfigActivationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Non-blocking startup acquisition for enabled FREE_PUBLIC items. */
public final class FreePublicCurrentAcquisitionService {
    public record Status(String state, List<String> succeededItemIds, List<String> failures) {
        public Status {
            succeededItemIds = succeededItemIds == null ? List.of() : List.copyOf(succeededItemIds);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    private final ConfigActivationStore configs;
    private final BackfillOrchestrator orchestrator;
    private final Executor executor;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicReference<Status> status =
            new AtomicReference<>(new Status("IDLE", List.of(), List.of()));

    public FreePublicCurrentAcquisitionService(
            ConfigActivationStore configs, BackfillOrchestrator orchestrator, Executor executor
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public Status status() {
        return status.get();
    }

    public Status trigger() {
        if (!inFlight.compareAndSet(false, true)) {
            return status.get();
        }
        status.set(new Status("RUNNING", List.of(), List.of()));
        try {
            executor.execute(this::run);
        } catch (RuntimeException exception) {
            inFlight.set(false);
            status.set(new Status("FAILED", List.of(), List.of("SCHEDULING_FAILED")));
        }
        return status.get();
    }

    private void run() {
        List<String> succeeded = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        try {
            var items = configs.readActiveConfig().items().stream()
                    .filter(item -> item.enabled() && item.providerType() == ProviderType.FREE_PUBLIC)
                    .toList();
            for (var item : items) {
                try {
                    var outcome = orchestrator.collectCurrent(item.itemId());
                    if (outcome.status() == BackfillOrchestrator.CurrentIntakeStatus.SUCCEEDED) {
                        succeeded.add(item.itemId());
                    } else {
                        failures.add(item.itemId() + ":" + String.join(",", outcome.failureReasons()));
                    }
                } catch (RuntimeException exception) {
                    failures.add(item.itemId() + ":" + exception.getClass().getSimpleName());
                }
            }
            status.set(new Status(failures.isEmpty() ? "SUCCEEDED" :
                    (succeeded.isEmpty() ? "FAILED" : "PARTIAL_SUCCESS"), succeeded, failures));
        } finally {
            inFlight.set(false);
        }
    }
}
