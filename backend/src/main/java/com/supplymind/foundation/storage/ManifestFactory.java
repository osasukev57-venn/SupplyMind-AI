package com.supplymind.foundation.storage;

import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.SchemaV1;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Builds only the derived integrity metadata specified for an adjacent manifest. */
public final class ManifestFactory {

    private ManifestFactory() {
    }

    public static ManifestV1 json(String dataRef, byte[] dataBytes, List<String> sourceRunIds, OffsetDateTime generatedAt) {
        return create(dataRef, dataBytes, null, null, null, sourceRunIds, generatedAt);
    }

    public static ManifestV1 csv(
            String dataRef,
            byte[] dataBytes,
            long rowCount,
            String minBusinessDate,
            String maxBusinessDate,
            List<String> sourceRunIds,
            OffsetDateTime generatedAt
    ) {
        if (rowCount < 0) {
            throw new StorageException("CSV manifest rowCount must not be negative");
        }
        return create(dataRef, dataBytes, rowCount, minBusinessDate, maxBusinessDate, sourceRunIds, generatedAt);
    }

    private static ManifestV1 create(
            String dataRef,
            byte[] dataBytes,
            Long rowCount,
            String minBusinessDate,
            String maxBusinessDate,
            List<String> sourceRunIds,
            OffsetDateTime generatedAt
    ) {
        DataPaths.requireLegalDataRef(dataRef);
        Objects.requireNonNull(dataBytes, "dataBytes");
        Objects.requireNonNull(sourceRunIds, "sourceRunIds");
        Objects.requireNonNull(generatedAt, "generatedAt");
        String filename = Path.of(dataRef).getFileName().toString();
        return new ManifestV1(
                SchemaV1.VERSION,
                filename,
                FileDigest.sha256(dataBytes),
                dataBytes.length,
                rowCount,
                minBusinessDate,
                maxBusinessDate,
                sourceRunIds,
                generatedAt,
                ManifestV1.COMMITTED
        );
    }
}
