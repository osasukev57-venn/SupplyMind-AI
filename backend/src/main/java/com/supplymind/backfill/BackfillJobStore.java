package com.supplymind.backfill;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * D5-T04 atomic backfill job persistence under the frozen runtime/jobs/active pattern.
 * Checkpoints are committed atomically with an adjacent manifest; restarts resume from the
 * persisted state and duplicate starts reuse the same jobId.
 */
public final class BackfillJobStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public BackfillJobStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean exists(String jobId) {
        return Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.backfillJobRef(jobId)));
    }

    public BackfillJobStateV1 read(String jobId) {
        String ref = DataPaths.backfillJobRef(jobId);
        Path path = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        if (!Files.isRegularFile(path)
                || !ManifestVerifier.matches(dataRoot, ref, path, manifestPath, List.of(jobId))) {
            throw new StorageException("Backfill job is missing or fails its manifest: " + jobId);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(path), BackfillJobStateV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read backfill job " + jobId, exception);
        }
    }

    public BackfillJobStateV1 write(BackfillJobStateV1 job) {
        Objects.requireNonNull(job, "job");
        String ref = DataPaths.backfillJobRef(job.jobId());
        byte[] jobBytes = JsonV1Codec.encodeFile(job);
        OffsetDateTime at = OffsetDateTime.now(clock);
        ManifestV1 manifest = ManifestFactory.json(ref, jobBytes, List.of(job.jobId()), at);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit("backfill-" + job.jobId(),
                DirtyTransactionType.SINGLE_FILE, at,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, jobBytes, manifestBytes, false)));
        return job;
    }
}
