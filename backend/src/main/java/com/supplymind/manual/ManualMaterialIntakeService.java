package com.supplymind.manual;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AccessMethod;
import com.supplymind.foundation.model.CandidateV1;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.Mode;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesItemV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.ProviderType;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.RouteDecision;
import com.supplymind.foundation.model.SchemaV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.FileDigest;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.RawReceiptStore;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * D3-T04 controlled Manual material intake (DEC-057 boundary): submission -> immutable raw ->
 * RECEIVED+PENDING -> mechanical normalization (manual-material-normalization-v1) ->
 * PARSED+PENDING, then stop. It never produces VERIFIED/VERIFIED_WITH_NOTICE/PUBLISHED and
 * never runs material business validation. Same stable business key (manual+itemId+
 * businessDate) with the same business content is IDEMPOTENT; different content creates a new
 * pending version while all previous raws/timelines stay immutable.
 */
public final class ManualMaterialIntakeService {

    private final DataRoot dataRoot;
    private final RawReceiptStore rawReceiptStore;
    private final TimelineStore timelineStore;
    private final ManualMaterialNormalizer normalizer;
    private final OperatorContext operatorContext;
    private final Clock clock;

    public ManualMaterialIntakeService(
            DataRoot dataRoot,
            RawReceiptStore rawReceiptStore,
            TimelineStore timelineStore,
            ManualMaterialNormalizer normalizer,
            OperatorContext operatorContext,
            Clock clock
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.rawReceiptStore = Objects.requireNonNull(rawReceiptStore, "rawReceiptStore");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.operatorContext = Objects.requireNonNull(operatorContext, "operatorContext");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ManualIntakeOutcome submit(ManualMaterialSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        String operatorRef = operatorContext.currentOperatorRef();
        if (operatorRef == null || operatorRef.isBlank()) {
            throw new StorageException("operatorRef from the authentication context is required");
        }
        MonitorSeriesConfigV1 config = loadActiveConfig();
        MonitorSeriesItemV1 item = requireManualRouteItem(config, submission.itemId());
        OffsetDateTime receivedAt = OffsetDateTime.now(clock);

        Optional<ManualIntakeOutcome> replay = findIdempotentReplay(submission, operatorRef);
        if (replay.isPresent()) {
            return replay.get();
        }

        String contentHash = contentHash(submission, operatorRef);
        String runId = "manual-" + submission.itemId() + "-" + submission.businessDate().replace("-", "")
                + "-" + contentHash;
        String rawRef = RawReceiptV1.deriveRawRef(Mode.FORMAL, ProviderType.MANUAL,
                submission.itemId(), receivedAt, runId);
        byte[] payload = JsonV1Codec.encodeFile(submission);
        RawReceiptV1 raw = new RawReceiptV1(
                SchemaV1.VERSION, rawRef, "manual-acq-" + contentHash, runId, Mode.FORMAL,
                ProviderType.MANUAL, AccessMethod.MANUAL, config.configVersion(),
                item.actualSourceName(), submission.sourceUrl(),
                submission.sourceReference(), submission.itemId(),
                submission.businessDate(), submission.businessDate(), null, null,
                receivedAt, receivedAt, submission.value(), submission.unit(),
                submission.currency(), operatorRef, null, "application/json", "base64",
                Base64.getEncoder().encodeToString(payload),
                FileDigest.sha256(payload), null, receivedAt, null);
        rawReceiptStore.store(raw);
        LifecycleTimelineV1 timeline = timelineStore.createInitial(runId, rawRef, receivedAt);
        String timelineRef = DataPaths.stagingRef(runId);

        ManualNormalizationOutcome normalization = normalizer.normalize(submission, config);
        if (!normalization.accepted()) {
            LifecycleSnapshotV1 rejected = new LifecycleSnapshotV1(
                    2, ProcessingStage.RECEIVED, ValidationStatus.REJECTED, null,
                    normalization.reason(), null, null, null, null, receivedAt);
            timelineStore.append(runId, rejected);
            return new ManualIntakeOutcome(
                    SchemaV1.VERSION, runId, rawRef, timelineRef,
                    ProcessingStage.RECEIVED, ValidationStatus.REJECTED,
                    normalization.reason(), ManualIntakeOutcome.IntakeMode.REJECTED_MECHANICAL, null);
        }

        CandidateV1 candidate = normalization.candidate(
                submission.itemId(), item.actualSourceName(), ProviderType.MANUAL, AccessMethod.MANUAL);
        LifecycleSnapshotV1 parsed = new LifecycleSnapshotV1(
                2, ProcessingStage.PARSED, ValidationStatus.PENDING, candidate,
                null, null, null, null, null, receivedAt);
        timelineStore.append(runId, parsed);
        return new ManualIntakeOutcome(
                SchemaV1.VERSION, runId, rawRef, timelineRef,
                ProcessingStage.PARSED, ValidationStatus.PENDING,
                null, ManualIntakeOutcome.IntakeMode.NEW, normalization.value());
    }

    /**
     * DEC-057: operatorRef is part of the business content, so it participates in the run
     * identity hash; server-side generated times never participate in equality.
     */
    private static String contentHash(ManualMaterialSubmission submission, String operatorRef) {
        byte[] submissionBytes = JsonV1Codec.encodeFile(submission);
        byte[] operatorBytes = operatorRef.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] combined = new byte[submissionBytes.length + 1 + operatorBytes.length];
        System.arraycopy(submissionBytes, 0, combined, 0, submissionBytes.length);
        combined[submissionBytes.length] = (byte) '|';
        System.arraycopy(operatorBytes, 0, combined, submissionBytes.length + 1, operatorBytes.length);
        return FileDigest.sha256(combined);
    }

    private Optional<ManualIntakeOutcome> findIdempotentReplay(
            ManualMaterialSubmission submission, String operatorRef
    ) {
        Path itemDir = dataRoot.resolveInternalRelative(
                "raw/formal/manual/" + submission.itemId());
        if (!Files.isDirectory(itemDir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(itemDir)) {
            List<Path> candidates = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .toList();
            for (Path candidate : candidates) {
                try {
                    RawReceiptV1 existing = JsonV1Codec.decodeFile(
                            Files.readAllBytes(candidate), RawReceiptV1.class);
                    if (!submission.businessDate().equals(existing.sourceBusinessDate())) {
                        continue;
                    }
                    ManualMaterialSubmission submittedFacts = decodeSubmittedFacts(existing);
                    if (submittedFacts != null
                            && submittedFacts.equals(submission)
                            && Objects.equals(operatorRef, existing.operatorRef())) {
                        String runId = existing.runId();
                        Path timelinePath = dataRoot.resolveDataRef(DataPaths.stagingRef(runId));
                        if (!Files.isRegularFile(timelinePath)) {
                            continue;
                        }
                        LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(
                                Files.readAllBytes(timelinePath), LifecycleTimelineV1.class);
                        return Optional.of(new ManualIntakeOutcome(
                                SchemaV1.VERSION, runId, existing.rawRef(),
                                DataPaths.stagingRef(runId),
                                timeline.current().processingStage(),
                                timeline.current().validationStatus(),
                                timeline.current().reasonCode(),
                                ManualIntakeOutcome.IntakeMode.IDEMPOTENT_REUSE,
                                existing.rawValue()));
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A corrupt candidate is never a replay; it stays untouched.
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to scan manual raws for " + submission.itemId(), exception);
        }
        return Optional.empty();
    }

    /**
     * DEC-057: the user-declared actual source name is preserved verbatim as immutable
     * submission facts (the raw payload), while the raw source identity stays the configured
     * Manual identity; the two must never be merged into one source identity.
     */
    private static ManualMaterialSubmission decodeSubmittedFacts(RawReceiptV1 raw) {
        try {
            return JsonV1Codec.decodeFile(
                    Base64.getDecoder().decode(raw.payloadBase64()), ManualMaterialSubmission.class);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private MonitorSeriesConfigV1 loadActiveConfig() {
        String activeRef = DataPaths.configActiveRef();
        Path activePath = dataRoot.resolveDataRef(activeRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(activeRef));
        if (!ManifestVerifier.matches(dataRoot, activeRef, activePath, manifestPath, List.of())) {
            throw new StorageException("Manual intake requires a valid active monitor-series configuration");
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(activePath), MonitorSeriesConfigV1.class);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("Unable to read the active monitor-series configuration", exception);
        }
    }

    private static MonitorSeriesItemV1 requireManualRouteItem(MonitorSeriesConfigV1 config, String itemId) {
        MonitorSeriesItemV1 item;
        try {
            item = config.requireItem(itemId);
        } catch (RuntimeException exception) {
            throw new StorageException("itemId is not configured: " + itemId);
        }
        if (!item.enabled()
                || (item.providerType() != ProviderType.MANUAL
                && item.routeDecision() != RouteDecision.FALLBACK_MANUAL)) {
            throw new StorageException("itemId is not configured for the Manual route: " + itemId);
        }
        return item;
    }
}
