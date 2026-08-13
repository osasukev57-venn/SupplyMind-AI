package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.TimeStateV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * D5-T01 single-file runtime state persistence for time/rotation recovery. The state file is a
 * rewritable runtime artifact (never a frozen business result): every update is an atomic
 * single-file commit with an adjacent manifest, so a crash can never leave a half-written
 * state. Reading verifies the manifest and fails closed on mismatch.
 */
public final class TimeStateStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public TimeStateStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TimeStateV1 read() {
        String ref = DataPaths.timeStateRef();
        Path path = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        if (!Files.isRegularFile(path)) {
            throw new StorageException("Time state does not exist: " + ref);
        }
        if (!ManifestVerifier.matches(dataRoot, ref, path, manifestPath, List.of())) {
            throw new StorageException("Time state fails its manifest: " + ref);
        }
        try {
            return JsonV1Codec.decodeFile(Files.readAllBytes(path), TimeStateV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read time state " + ref, exception);
        }
    }

    public boolean exists() {
        return Files.isRegularFile(dataRoot.resolveDataRef(DataPaths.timeStateRef()));
    }

    /** Atomic rewritable update; stateVersion advances monotonically. */
    public TimeStateV1 write(TimeStateV1 state) {
        Objects.requireNonNull(state, "state");
        String ref = DataPaths.timeStateRef();
        byte[] stateBytes = JsonV1Codec.encodeFile(state);
        OffsetDateTime at = OffsetDateTime.now(clock);
        ManifestV1 manifest = ManifestFactory.json(ref, stateBytes, List.of(), at);
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit("time-state-v" + state.stateVersion(),
                DirtyTransactionType.SINGLE_FILE, at,
                List.of(new FileTransactionTarget(
                        DirtyTargetRole.BUSINESS_FILE, ref, stateBytes, manifestBytes, false)));
        return state;
    }
}
