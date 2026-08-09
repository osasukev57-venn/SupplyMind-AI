package com.supplymind.validation;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Manifest-verified immutable config history reader. Validation must always use the exact
 * configVersion carried by the RawReceipt, never the current active configuration, so that
 * historical raws keep deterministic, reproducible results across later config switches.
 */
public final class VersionedConfigReader {

    private VersionedConfigReader() {
    }

    public static MonitorSeriesConfigV1 readVersion(DataRoot dataRoot, int configVersion) {
        String historyRef = DataPaths.configHistoryRef(configVersion);
        Path historyPath = dataRoot.resolveDataRef(historyRef);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(historyRef));
        if (!Files.isRegularFile(historyPath)
                || !ManifestVerifier.matches(dataRoot, historyRef, historyPath, manifestPath, List.of())) {
            throw new StorageException("Validation requires the immutable config history snapshot for configVersion "
                    + configVersion);
        }
        try {
            MonitorSeriesConfigV1 config = JsonV1Codec.decodeFile(Files.readAllBytes(historyPath),
                    MonitorSeriesConfigV1.class);
            if (config.configVersion() != configVersion) {
                throw new StorageException("Config history content/version mismatch for configVersion " + configVersion);
            }
            return config;
        } catch (IOException exception) {
            throw new StorageException("Unable to read config history for configVersion " + configVersion, exception);
        }
    }
}
