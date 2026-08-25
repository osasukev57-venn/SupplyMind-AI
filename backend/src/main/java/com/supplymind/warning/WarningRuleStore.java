package com.supplymind.warning;

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
import java.util.Optional;
import java.util.UUID;

/**
 * DEC-064 one active warning override set plus byte-identical immutable history. Defaults are
 * derived from monitor metadata; only user overrides are persisted.
 */
public final class WarningRuleStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public WarningRuleStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<WarningRuleSetV1> readActive() {
        String ref = DataPaths.warningRulesActiveRef();
        Path data = dataRoot.resolveDataRef(ref);
        if (!Files.isRegularFile(data)) {
            return Optional.empty();
        }
        Path manifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        if (!ManifestVerifier.matches(dataRoot, ref, data, manifest, List.of())) {
            throw new StorageException("warning-rule active manifest verification failed");
        }
        WarningRuleSetV1 set = JsonV1Codec.decodeFile(readBytes(data), WarningRuleSetV1.class);
        String historyRef = DataPaths.warningRulesHistoryRef(set.configVersion());
        Path history = dataRoot.resolveDataRef(historyRef);
        Path historyManifest = dataRoot.resolveDataRef(DataPaths.manifestRef(historyRef));
        if (!Files.isRegularFile(history)
                || !java.util.Arrays.equals(readBytes(history), readBytes(data))
                || !ManifestVerifier.matches(dataRoot, historyRef, history, historyManifest, List.of())) {
            throw new StorageException("warning-rule active/history binding failed for version " + set.configVersion());
        }
        return Optional.of(set);
    }

    public WarningRuleSetV1 activate(List<WarningRuleConfigV1> overrides) {
        Optional<WarningRuleSetV1> current = readActive();
        int nextVersion = current.map(value -> value.configVersion() + 1).orElse(1);
        OffsetDateTime at = OffsetDateTime.now(clock);
        WarningRuleSetV1 next = new WarningRuleSetV1(
                "1.0", nextVersion, at, overrides == null ? List.of() : overrides);
        byte[] bytes = JsonV1Codec.encodeFile(next);
        String historyRef = DataPaths.warningRulesHistoryRef(nextVersion);
        String activeRef = DataPaths.warningRulesActiveRef();
        ManifestV1 historyManifest = ManifestFactory.json(historyRef, bytes, List.of(), at);
        ManifestV1 activeManifest = ManifestFactory.json(activeRef, bytes, List.of(), at);
        fileStore.commit("warning-rules-" + UUID.randomUUID(),
                DirtyTransactionType.CONFIG_ACTIVATION, at, List.of(
                        new FileTransactionTarget(
                                DirtyTargetRole.CONFIG_HISTORY, historyRef, bytes,
                                JsonV1Codec.encodeFile(historyManifest), true),
                        new FileTransactionTarget(
                                DirtyTargetRole.CONFIG_ACTIVE, activeRef, bytes,
                                JsonV1Codec.encodeFile(activeManifest), false)));
        return readActive().orElseThrow();
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new StorageException("unable to read warning-rule configuration " + path, exception);
        }
    }
}
