package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.LifecycleSnapshotV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Immutable-history lifecycle persistence: initial snapshot creation and atomic append-only updates. */
public final class TimelineStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public TimelineStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LifecycleTimelineV1 read(String runId) {
        String stagingRef = DataPaths.stagingRef(runId);
        Path stagingPath = dataRoot.resolveDataRef(stagingRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(stagingRef));
        if (!Files.isRegularFile(stagingPath)
                || !ManifestVerifier.matches(dataRoot, stagingRef, stagingPath, manifestPath, List.of(runId))) {
            throw new StorageException("Lifecycle timeline is missing or fails its manifest: " + stagingRef);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(stagingPath), LifecycleTimelineV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read lifecycle timeline " + stagingRef, exception);
        }
    }

    public LifecycleTimelineV1 createInitial(String runId, String rawRef, OffsetDateTime receivedAt) {
        LifecycleTimelineV1 timeline = LifecycleTimelineV1.initial("record-" + runId, runId, rawRef, receivedAt);
        return commit(timeline, "timeline-" + runId, receivedAt);
    }

    /**
     * Appends exactly one legal next snapshot. Replaying the identical current snapshot is a no-op;
     * any skipped/reversed version or illegal transition fails closed in the model.
     */
    public LifecycleTimelineV1 append(String runId, LifecycleSnapshotV1 next) {
        Objects.requireNonNull(next, "next snapshot");
        LifecycleTimelineV1 current = read(runId);
        if (current.current().equals(next)) {
            return current;
        }
        LifecycleTimelineV1 updated = current.append(next);
        return commit(updated, "timeline-" + runId + "-v" + next.recordVersion(), next.updatedAt());
    }

    private LifecycleTimelineV1 commit(LifecycleTimelineV1 timeline, String transactionId, OffsetDateTime at) {
        String stagingRef = DataPaths.stagingRef(timeline.runId());
        byte[] dataBytes = JsonV1Codec.encodeFile(timeline);
        ManifestV1 manifest = ManifestFactory.json(stagingRef, dataBytes, List.of(timeline.runId()), at);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit(transactionId, DirtyTransactionType.SINGLE_FILE, at,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, stagingRef, dataBytes, manifestBytes, false)));
        return timeline;
    }
}
