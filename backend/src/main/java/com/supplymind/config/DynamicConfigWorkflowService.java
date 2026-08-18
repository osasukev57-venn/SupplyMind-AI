package com.supplymind.config;

import com.supplymind.backfill.BackfillJobStateV1;
import com.supplymind.backfill.BackfillJobStore;
import com.supplymind.backfill.BackfillOrchestrator;
import com.supplymind.backfill.BackfillJobQueryService;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.ModelRules;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.provider.DataProvider;
import com.supplymind.provider.DataProviderRegistry;
import com.supplymind.provider.ProviderSourceProfile;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * D8-T01 minimal application orchestration over the frozen chain. A config change goes through
 * the EXISTING ConfigManagementService activation (new configVersion + immutable history),
 * then - for ADD/REPLACE - a real backfill job is created through the EXISTING
 * BackfillOrchestrator for the new target (automatic routes run the real chain; manual routes
 * honestly reach AWAITING_MANUAL_INPUT). The service never writes config files itself and never
 * fakes completion. Backend-generated fields (configVersion, routeEffectiveAt, jobId, audit
 * time) are never taken from the client.
 */
public final class DynamicConfigWorkflowService {

    private final ConfigManagementService configs;
    private final BackfillOrchestrator backfill;
    private final BackfillJobStore backfillJobs;
    private final BackfillJobQueryService jobsQuery;
    private final ConfigHistoryQueryService historyQuery;
    private final DataProviderRegistry registry;
    private final Clock clock;

    public DynamicConfigWorkflowService(
            ConfigManagementService configs,
            BackfillOrchestrator backfill,
            BackfillJobStore backfillJobs,
            BackfillJobQueryService jobsQuery,
            ConfigHistoryQueryService historyQuery,
            DataProviderRegistry registry,
            Clock clock
    ) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.backfill = Objects.requireNonNull(backfill, "backfill");
        this.backfillJobs = Objects.requireNonNull(backfillJobs, "backfillJobs");
        this.jobsQuery = Objects.requireNonNull(jobsQuery, "jobsQuery");
        this.historyQuery = Objects.requireNonNull(historyQuery, "historyQuery");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * ADD: activate the new target (capability gate in ConfigManagementService), then ALWAYS
     * trigger the CURRENT-value acquisition (distinct semantic entry - real
     * ProviderCollectRequest.current, supportsCurrentData) and, when a full backfill range was
     * provided, auto-create and run the HISTORY backfill through the real orchestrator.
     * Manual targets honestly reach AWAITING_MANUAL_INPUT - never a fake SUCCEEDED.
     */
    public ConfigV1.WorkflowResult addItem(ConfigV1.AddItemRequest request) {
        Objects.requireNonNull(request, "request");
        MonitorSeriesItemV1 item = toItem(request, null);
        MonitorSeriesConfigV1 activated = configs.addItem(item);
        return new ConfigV1.WorkflowResult(
                toConfigView(activated),
                runIntakeChain(request).currentIntake(),
                runIntakeChain(request).backfillJobs());
    }

    /** ENABLE/DISABLE: pure activation; no jobs are created for a mere toggle. */
    public ConfigV1.ConfigView setEnabled(String itemId, boolean enabled) {
        Objects.requireNonNull(itemId, "itemId");
        return toConfigView(configs.setEnabled(itemId, enabled));
    }

    /**
     * REPLACE: disable the old target (history preserved) + activate replacement, then the
     * same automatic intake chain as ADD (CURRENT always attempted; full backfill range
     * auto-created and run; Manual honestly reaches AWAITING_MANUAL_INPUT).
     */
    public ConfigV1.WorkflowResult replaceItem(ConfigV1.ReplaceItemRequest request) {
        Objects.requireNonNull(request, "request");
        MonitorSeriesItemV1 replacement = toItem(request.newItem(), request.oldItemId());
        MonitorSeriesConfigV1 activated = configs.replaceItem(request.oldItemId(), replacement);
        return new ConfigV1.WorkflowResult(
                toConfigView(activated),
                runIntakeChain(request.newItem()).currentIntake(),
                runIntakeChain(request.newItem()).backfillJobs());
    }

    /**
     * M1: the automatic intake chain after an ADD/REPLACE activation.
     * 1. CURRENT acquisition is ALWAYS attempted through the real orchestrator's distinct
     *    collectCurrent entry (ProviderCollectRequest.current + supportsCurrentData) - it
     *    never depends on a backfill range and is NEVER disguised as a one-day backfill job.
     * 2. A FULL backfill range auto-creates and RUNS the HISTORY job (supportsHistoryData
     *    governs it, exactly as the orchestrator contract requires).
     * 3. One-sided ranges are rejected by the DTO (both-or-neither).
     * 4. Manual targets honestly reach AWAITING_MANUAL_INPUT / FAILED - never a fake success.
     */
    private ConfigV1.WorkflowResult.IntakeChain runIntakeChain(ConfigV1.AddItemRequest request) {
        ConfigV1.CurrentIntakeView current = toCurrentView(
                backfill.collectCurrent(request.itemId()));
        List<ConfigV1.BackfillJobView> jobs = new ArrayList<>();
        if (request.backfillFrom() != null && request.backfillTo() != null) {
            BackfillJobStateV1 history = backfill.createOrResume(
                    request.itemId(), LocalDate.parse(request.backfillFrom()),
                    LocalDate.parse(request.backfillTo()));
            jobs.add(toJobView(backfill.run(history.jobId())));
        }
        return new ConfigV1.WorkflowResult.IntakeChain(current, List.copyOf(jobs));
    }

    private static ConfigV1.CurrentIntakeView toCurrentView(
            com.supplymind.backfill.BackfillOrchestrator.CurrentIntakeOutcome outcome
    ) {
        return new ConfigV1.CurrentIntakeView(
                outcome.itemId(),
                outcome.status() == null ? null : outcome.status().name(),
                outcome.rawCount(),
                outcome.failureReasons());
    }

    /**
     * Backfill retry: a FAILED job is reopened to WAITING (checkpoint/completed/failures kept
     * in the persisted job) and resumed through the real orchestrator; non-terminal jobs are
     * resumed as-is; SUCCEEDED is terminal and returned unchanged. Reopening goes through the
     * existing BackfillJobStore write (atomic + manifest) - no frozen state machine is changed.
     */
    public ConfigV1.BackfillJobView retryBackfill(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        BackfillJobStateV1 job = backfillJobs.read(jobId);
        if (job.status() == BackfillJobStateV1.JobStatus.FAILED) {
            BackfillJobStateV1 reopened = job.withStatus(
                    BackfillJobStateV1.JobStatus.WAITING,
                    job.completedPeriods(), job.currentCheckpoint(),
                    new ArrayList<>(job.failureReasons()), OffsetDateTime.now(clock));
            backfillJobs.write(reopened);
        }
        BackfillJobStateV1 resumed = backfill.run(jobId);
        return toJobView(resumed);
    }

    public ConfigV1.ConfigView configView() {
        return toConfigView(configs.active());
    }

    /** Read-only configVersion audit trail over the immutable history snapshots. */
    public List<ConfigV1.HistoryEntry> configHistory() {
        return historyQuery.history();
    }

    /** All persisted backfill jobs with their real persisted state. */
    public List<ConfigV1.BackfillJobView> backfillJobs() {
        return jobsQuery.list().stream().map(DynamicConfigWorkflowService::toJobView).toList();
    }

    /** One persisted backfill job by id (manifest-verified read). */
    public ConfigV1.BackfillJobView backfillJob(String jobId) {
        return toJobView(backfillJobs.read(jobId));
    }

    /** Create or resume a backfill job for an existing target (idempotent; WAITING). */
    public ConfigV1.BackfillJobView createBackfillJob(String itemId, String from, String to) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        ModelRules.isoDateText(from, "from");
        ModelRules.isoDateText(to, "to");
        BackfillJobStateV1 job = backfill.createOrResume(itemId, LocalDate.parse(from), LocalDate.parse(to));
        return toJobView(job);
    }

    /** Run a backfill job through the real orchestrator (checkpoint-resumed). */
    public ConfigV1.BackfillJobView runBackfill(String jobId) {
        return toJobView(backfill.run(jobId));
    }

    /** Secret-free capability projection over the provider registry. */
    public List<ConfigV1.CapabilityView> capabilities() {
        MonitorSeriesConfigV1 active = configs.active();
        List<ConfigV1.CapabilityView> views = new ArrayList<>();
        for (DataProvider provider : registry.all()) {
            ProviderSourceProfile profile = provider.profile();
            List<String> rateKinds = active.items().stream()
                    .filter(item -> item.providerType() == profile.providerType())
                    .map(MonitorSeriesItemV1::rateKind)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            views.add(new ConfigV1.CapabilityView(
                    profile.providerId(),
                    profile.providerType().wireValue(),
                    profile.accessMethod().wireValue(),
                    profile.actualSourceName(),
                    profile.supportsCurrentData(),
                    profile.supportsHistoryData(),
                    provider.supportedItemIds().stream().sorted().toList(),
                    rateKinds));
        }
        views.sort(Comparator.comparing(ConfigV1.CapabilityView::providerId));
        return List.copyOf(views);
    }

    private MonitorSeriesItemV1 toItem(ConfigV1.AddItemRequest request, String supersedesItemId) {
        ProviderType providerType = ProviderType.fromWireValue(request.providerType());
        AccessMethod accessMethod = AccessMethod.fromWireValue(request.accessMethod());
        RouteDecision routeDecision = RouteDecision.fromWireValue(request.routeDecision());
        boolean fallback = routeDecision == RouteDecision.FALLBACK_FREE_PUBLIC
                || routeDecision == RouteDecision.FALLBACK_MANUAL;
        if (fallback && (request.fallbackReason() == null || request.fallbackReason().isBlank())) {
            throw new StorageException("fallbackReason is required for routeDecision " + routeDecision.wireValue());
        }
        if (!fallback && request.fallbackReason() != null) {
            throw new StorageException("fallbackReason must be null for non-fallback routeDecision");
        }
        return new MonitorSeriesItemV1(
                request.itemId(), request.displayName(), true, request.sourceIntent(),
                providerType, accessMethod, request.actualSourceName(), routeDecision,
                request.fallbackReason(), OffsetDateTime.now(clock), supersedesItemId,
                request.externalCode(), request.sourceFieldKey(), request.rateKind(),
                request.calculationVersion(), request.calculationScale(), request.displayScale(),
                ConfigV1.roundingMode(request.roundingMode()), request.calendarVersion(),
                request.currency(), request.baseCurrency(), request.unit(),
                "material".equals(request.rateKind())
                        ? request.materialValidation().toFrozen()
                        : null);
    }

    private static ConfigV1.ConfigView toConfigView(MonitorSeriesConfigV1 config) {
        List<ConfigV1.ItemView> items = new ArrayList<>();
        for (MonitorSeriesItemV1 item : config.items()) {
            items.add(new ConfigV1.ItemView(
                    item.itemId(), item.displayName(), item.enabled(), item.sourceIntent(),
                    item.providerType() == null ? null : item.providerType().wireValue(),
                    item.accessMethod() == null ? null : item.accessMethod().wireValue(),
                    item.actualSourceName(),
                    item.routeDecision() == null ? null : item.routeDecision().name(),
                    item.fallbackReason(),
                    item.routeEffectiveAt() == null ? null : item.routeEffectiveAt().toString(),
                    item.supersedesItemId(), item.externalCode(), item.sourceFieldKey(),
                    item.rateKind(), item.calculationVersion(), item.calculationScale(),
                    item.displayScale(),
                    item.roundingMode() == null ? null : item.roundingMode().name(),
                    item.calendarVersion(), item.currency(), item.baseCurrency(), item.unit()));
        }
        return new ConfigV1.ConfigView(
                config.schemaVersion(), config.configVersion(),
                config.mode() == null ? null : config.mode().name(),
                config.updatedAt() == null ? null : config.updatedAt().toString(),
                items);
    }

    private static ConfigV1.BackfillJobView toJobView(BackfillJobStateV1 job) {
        return new ConfigV1.BackfillJobView(
                job.jobId(), job.itemId(), job.fromDate(), job.toDate(),
                job.status() == null ? null : job.status().name(),
                job.completedPeriods(), job.currentCheckpoint(), job.failureReasons(),
                job.configVersion(),
                job.createdAt() == null ? null : job.createdAt().toString(),
                job.updatedAt() == null ? null : job.updatedAt().toString());
    }
}
