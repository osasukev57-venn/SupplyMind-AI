package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.MonitorSeriesDefaults;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The only configuration activation path: immutable history data+manifest is
 * committed before active data+manifest in one CONFIG_ACTIVATION marker.
 */
public final class ConfigActivationStore {

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;
    private final Clock clock;

    public ConfigActivationStore(DataRoot dataRoot, AtomicFileStore fileStore, Clock clock) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Read-only access to the currently active configuration (used by D3-T04 manual intake). */
    public MonitorSeriesConfigV1 readActiveConfig() {
        Path active = dataRoot.resolveDataRef(DataPaths.configActiveRef());
        if (!Files.isRegularFile(active)) {
            throw new StorageException("No active monitor-series configuration exists");
        }
        return decodeActive(active);
    }

    /** Creates the formal two-item PBOC default only if no active configuration exists. */
    public MonitorSeriesConfigV1 ensureInitialDefault() {
        Path active = dataRoot.resolveDataRef(DataPaths.configActiveRef());
        if (Files.isRegularFile(active)) {
            MonitorSeriesConfigV1 existing = decodeActive(active);
            ensureHistoryAndActive(existing, readBytes(active));
            return existing;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        MonitorSeriesConfigV1 initial = MonitorSeriesDefaults.initialPboc(now);
        activateNewOrIdempotent(initial, JsonV1Codec.encodeFile(initial), now);
        return initial;
    }

    /**
     * Accepts either the byte-identical active version as an idempotent retry,
     * or exactly current configVersion+1. A skipped version fails closed.
     */
    public void activate(MonitorSeriesConfigV1 configuration) {
        MonitorSeriesConfigV1 requested = Objects.requireNonNull(configuration, "configuration");
        Path active = dataRoot.resolveDataRef(DataPaths.configActiveRef());
        if (Files.exists(active)) {
            MonitorSeriesConfigV1 previous = decodeActive(active);
            byte[] activeBytes = readBytes(active);
            if (requested.configVersion() == previous.configVersion()) {
                if (!sameSemantics(requested, previous)) {
                    throw new StorageException("Same configVersion with different bytes must fail closed: "
                            + requested.configVersion());
                }
                ensureHistoryAndActive(previous, activeBytes);
                return;
            }
            if (requested.configVersion() != previous.configVersion() + 1) {
                throw new StorageException("A semantic config activation must advance configVersion by exactly one");
            }
        } else if (requested.configVersion() != 1) {
            throw new StorageException("The first active configuration must be configVersion=1");
        }
        OffsetDateTime persistedAt = OffsetDateTime.now(clock);
        MonitorSeriesConfigV1 persisted = withUpdatedAt(requested, persistedAt);
        activateNewOrIdempotent(persisted, JsonV1Codec.encodeFile(persisted), persistedAt);
    }

    private static boolean sameSemantics(MonitorSeriesConfigV1 requested, MonitorSeriesConfigV1 persisted) {
        return withUpdatedAt(requested, persisted.updatedAt()).equals(persisted);
    }

    private static MonitorSeriesConfigV1 withUpdatedAt(MonitorSeriesConfigV1 configuration, OffsetDateTime updatedAt) {
        return new MonitorSeriesConfigV1(configuration.schemaVersion(), configuration.configVersion(), configuration.mode(),
                updatedAt, configuration.items());
    }

    private void ensureHistoryAndActive(MonitorSeriesConfigV1 configuration, byte[] activeBytes) {
        String historyRef = DataPaths.configHistoryRef(configuration.configVersion());
        Path history = dataRoot.resolveDataRef(historyRef);
        if (Files.exists(history) && !FileDigest.bytesEqual(history, activeBytes)) {
            throw new StorageException("Config history differs from active bytes for configVersion="
                    + configuration.configVersion());
        }
        if (healthy(historyRef, activeBytes) && healthy(DataPaths.configActiveRef(), activeBytes)) {
            // Exact active/history bytes plus both valid manifests is a true no-op.
            return;
        }
        activateNewOrIdempotent(configuration, activeBytes, OffsetDateTime.now(clock));
    }

    private void activateNewOrIdempotent(MonitorSeriesConfigV1 configuration, byte[] exactConfigBytes, OffsetDateTime now) {
        String historyRef = DataPaths.configHistoryRef(configuration.configVersion());
        byte[] historyManifestBytes = reusableOrRebuiltManifest(historyRef, exactConfigBytes, now);
        byte[] activeManifestBytes = reusableOrRebuiltManifest(DataPaths.configActiveRef(), exactConfigBytes, now);
        List<FileTransactionTarget> targets = List.of(
                new FileTransactionTarget(DirtyTargetRole.CONFIG_HISTORY, historyRef, exactConfigBytes,
                        historyManifestBytes, true),
                new FileTransactionTarget(DirtyTargetRole.CONFIG_ACTIVE, DataPaths.configActiveRef(), exactConfigBytes,
                        activeManifestBytes, false)
        );
        fileStore.commit(transactionId(), DirtyTransactionType.CONFIG_ACTIVATION, now, targets);
    }

    private boolean healthy(String dataRef, byte[] exactDataBytes) {
        Path data = dataRoot.resolveDataRef(dataRef);
        Path manifest = dataRoot.resolveDataRef(DataPaths.manifestRef(dataRef));
        return Files.isRegularFile(data) && FileDigest.bytesEqual(data, exactDataBytes)
                && ManifestVerifier.matches(dataRoot, dataRef, data, manifest, List.of());
    }

    private byte[] reusableOrRebuiltManifest(String dataRef, byte[] dataBytes, OffsetDateTime now) {
        Path dataPath = dataRoot.resolveDataRef(dataRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(dataRef));
        if (Files.isRegularFile(dataPath) && FileDigest.bytesEqual(dataPath, dataBytes)
                && ManifestVerifier.matches(dataRoot, dataRef, dataPath, manifestPath, List.of())) {
            return readBytes(manifestPath);
        }
        ManifestV1 manifest = ManifestFactory.json(dataRef, dataBytes, List.of(), now);
        return JsonV1Codec.encodeFile(manifest);
    }

    private MonitorSeriesConfigV1 decodeActive(Path active) {
        return JsonV1Codec.decodeFile(readBytes(active), MonitorSeriesConfigV1.class);
    }

    private byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to read configuration " + path, exception);
        }
    }

    private static String transactionId() {
        return "config-activation-" + UUID.randomUUID();
    }
}