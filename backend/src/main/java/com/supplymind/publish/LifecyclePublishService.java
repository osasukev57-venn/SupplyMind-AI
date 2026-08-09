package com.supplymind.publish;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.ProcessingStage;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;
import com.supplymind.foundation.model.ValidationStatus;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.QuarantineStore;
import com.supplymind.foundation.storage.StorageException;
import com.supplymind.foundation.storage.TimelineStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * D2-T02 minimal publish boundary. Only VALIDATED+VERIFIED-class snapshots are appended to
 * PUBLISHED; the three failed terminal states produce the frozen quarantine projection
 * (idempotent, timeline untouched); PENDING runs stay untouched; PUBLISHED replays are no-ops.
 * No published directory or hidden repository is created.
 */
public final class LifecyclePublishService {

    private final DataRoot dataRoot;
    private final TimelineStore timelineStore;
    private final QuarantineStore quarantineStore;
    private final Clock clock;

    public LifecyclePublishService(
            DataRoot dataRoot,
            TimelineStore timelineStore,
            QuarantineStore quarantineStore,
            Clock clock
    ) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.timelineStore = Objects.requireNonNull(timelineStore, "timelineStore");
        this.quarantineStore = Objects.requireNonNull(quarantineStore, "quarantineStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PublishOutcome process(String runId) {
        Objects.requireNonNull(runId, "runId");
        LifecycleTimelineV1 timeline = timelineStore.read(runId);
        LifecycleSnapshotV1 current = timeline.current();

        if (current.processingStage() == ProcessingStage.PUBLISHED) {
            return outcome(timeline, PublishOutcome.PublishAction.ALREADY_PUBLISHED, null);
        }

        if (current.processingStage() == ProcessingStage.VALIDATED
                && (current.validationStatus() == ValidationStatus.VERIFIED
                || current.validationStatus() == ValidationStatus.VERIFIED_WITH_NOTICE)) {
            OffsetDateTime publishedAt = now();
            LifecycleSnapshotV1 published = new LifecycleSnapshotV1(
                    4,
                    ProcessingStage.PUBLISHED,
                    current.validationStatus(),
                    current.candidate(),
                    current.reasonCode(),
                    current.validationVersion(),
                    current.validatedAt(),
                    publishedAt,
                    DataPaths.stagingRef(runId) + "#recordVersion=4",
                    publishedAt);
            LifecycleTimelineV1 updated = timelineStore.append(runId, published);
            return outcome(updated, PublishOutcome.PublishAction.PUBLISHED, null);
        }

        if (isTerminalFailure(current)) {
            RawReceiptV1 raw = readRaw(timeline.rawRef(), runId);
            String rawFileSha256 = readRawFileSha256(timeline.rawRef());
            QuarantineProjectionV1 projection = QuarantineProjectionV1.fromTerminal(raw, timeline, rawFileSha256);
            quarantineStore.store(projection);
            return outcome(timeline, PublishOutcome.PublishAction.QUARANTINED, projection.quarantineRef());
        }

        return outcome(timeline, PublishOutcome.PublishAction.NOT_READY, null);
    }

    private static boolean isTerminalFailure(LifecycleSnapshotV1 snapshot) {
        return (snapshot.processingStage() == ProcessingStage.RECEIVED
                && snapshot.validationStatus() == ValidationStatus.REJECTED)
                || (snapshot.processingStage() == ProcessingStage.VALIDATED
                && (snapshot.validationStatus() == ValidationStatus.REJECTED
                || snapshot.validationStatus() == ValidationStatus.CONFLICT));
    }

    private static PublishOutcome outcome(
            LifecycleTimelineV1 timeline,
            PublishOutcome.PublishAction action,
            String quarantineRef
    ) {
        LifecycleSnapshotV1 current = timeline.current();
        String publishRef = current.publishRef();
        if (action == PublishOutcome.PublishAction.QUARANTINED) {
            publishRef = null;
        }
        return new PublishOutcome(
                timeline.runId(),
                action,
                current.recordVersion(),
                current.processingStage(),
                current.validationStatus(),
                publishRef,
                quarantineRef,
                current.reasonCode());
    }

    private RawReceiptV1 readRaw(String rawRef, String runId) {
        Path rawPath = dataRoot.resolveDataRef(rawRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!Files.isRegularFile(rawPath)
                || !ManifestVerifier.matches(dataRoot, rawRef, rawPath, manifestPath, List.of(runId))) {
            throw new StorageException("Publish quarantine requires a manifest-valid raw: " + rawRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(rawPath), RawReceiptV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read raw " + rawRef, exception);
        }
    }

    private String readRawFileSha256(String rawRef) {
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(rawRef));
        if (!Files.isRegularFile(manifestPath)) {
            throw new StorageException("Publish quarantine requires the raw adjacent manifest: " + rawRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class).fileSha256();
        } catch (IOException exception) {
            throw new StorageException("Unable to read raw manifest for " + rawRef, exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
