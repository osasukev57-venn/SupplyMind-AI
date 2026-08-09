package com.supplymind.processing;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateGrain;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Minimal read-only entry for persisted aggregate files: it locates the frozen path, verifies
 * the adjacent manifest, checks the CSV bytes and decodes them. It never calculates,
 * rebuilds or writes aggregate output.
 */
public final class AggregateReadService {

    private final DataRoot dataRoot;

    public AggregateReadService(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    public AggregateFile read(String itemId, AggregateGrain grain, int year) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(grain, "grain");
        String ref = DataPaths.aggregateRef(itemId, grain.wireValue(), year);
        Path csvPath = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        if (!Files.isRegularFile(csvPath) || !Files.isRegularFile(manifestPath)) {
            return null;
        }
        if (!ManifestVerifier.matches(dataRoot, ref, csvPath, manifestPath)) {
            throw new StorageException("Aggregate read requires a manifest-valid file: " + ref);
        }
        byte[] csvBytes;
        try {
            csvBytes = Files.readAllBytes(csvPath);
        } catch (IOException exception) {
            throw new StorageException("Unable to read aggregate CSV " + ref, exception);
        }
        ManifestV1 manifest;
        try {
            manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
        } catch (IOException exception) {
            throw new StorageException("Unable to read aggregate manifest " + ref, exception);
        }
        return new AggregateFile(ref, manifest, csvBytes, CsvV1Codec.decodeAggregate(csvBytes));
    }

    public record AggregateFile(
            String ref,
            ManifestV1 manifest,
            byte[] csvBytes,
            List<AggregateRecordV1> rows
    ) {
    }
}
