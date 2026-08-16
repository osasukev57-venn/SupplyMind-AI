package com.supplymind.backfill;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * D8-T01 read-only backfill job listing over the frozen runtime/jobs/active pattern. Every job
 * document is verified against its adjacent manifest; a corrupt document is reported as a
 * StorageException (the caller surfaces a structured evidence issue) instead of being silently
 * skipped. Controllers never scan the filesystem themselves.
 */
public final class BackfillJobQueryService {

    private final DataRoot dataRoot;

    public BackfillJobQueryService(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    public List<BackfillJobStateV1> list() {
        Path jobDir = dataRoot.resolveInternalRelative("runtime/jobs/active");
        if (!Files.isDirectory(jobDir)) {
            return List.of();
        }
        List<BackfillJobStateV1> jobs = new ArrayList<>();
        try (Stream<Path> files = Files.list(jobDir)) {
            for (Path jobFile : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .toList()) {
                String jobId = jobFile.getFileName().toString()
                        .substring(0, jobFile.getFileName().toString().length() - ".json".length());
                String ref = DataPaths.backfillJobRef(jobId);
                Path manifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
                if (!ManifestVerifier.matches(dataRoot, ref, jobFile, manifest, List.of(jobId))) {
                    throw new StorageException("Backfill job fails its manifest: " + jobId);
                }
                try {
                    jobs.add(JsonV1Codec.decodeFile(Files.readAllBytes(jobFile), BackfillJobStateV1.class));
                } catch (IOException exception) {
                    throw new StorageException("Unable to read backfill job " + jobId, exception);
                }
            }
        } catch (IOException exception) {
            throw new StorageException("Unable to list backfill jobs", exception);
        }
        jobs.sort(Comparator.comparing(BackfillJobStateV1::jobId));
        return List.copyOf(jobs);
    }
}
