package com.supplymind.desktop;

import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.ConfigActivationStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyMarkerCodec;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Day-9 packaging entry point that exports the canonical initial configuration by
 * using the same storage implementation as the running application. It is never
 * invoked by normal application startup.
 */
public final class PortableInitialConfigExporter {

    private PortableInitialConfigExporter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <dataRoot> <ISO-8601 offset instant>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        OffsetDateTime seedTime = OffsetDateTime.parse(args[1]);
        Clock clock = Clock.fixed(seedTime.toInstant(), ZoneOffset.ofHours(8));
        DataRoot dataRoot = DataRoot.fromConfiguredPath(output.toString());
        dataRoot.createIfAbsentAndRequireWritable();
        AtomicFileStore fileStore = new AtomicFileStore(dataRoot, new DirtyMarkerCodec());
        new ConfigActivationStore(dataRoot, fileStore, clock).ensureInitialDefault();

        List<String> refs = List.of(
                DataPaths.configActiveRef(),
                DataPaths.manifestRef(DataPaths.configActiveRef()),
                DataPaths.configHistoryRef(1),
                DataPaths.manifestRef(DataPaths.configHistoryRef(1))
        );
        for (String ref : refs) {
            if (!Files.isRegularFile(dataRoot.resolveDataRef(ref))) {
                throw new StorageException("initial portable config is incomplete: " + ref);
            }
        }
        byte[] active = Files.readAllBytes(dataRoot.resolveDataRef(DataPaths.configActiveRef()));
        byte[] history = Files.readAllBytes(dataRoot.resolveDataRef(DataPaths.configHistoryRef(1)));
        if (!java.util.Arrays.equals(active, history)) {
            throw new StorageException("initial active config must equal history version 1 byte-for-byte");
        }
        if (!ManifestVerifier.matches(dataRoot, DataPaths.configActiveRef(),
                dataRoot.resolveDataRef(DataPaths.configActiveRef()),
                dataRoot.resolveDataRef(DataPaths.manifestRef(DataPaths.configActiveRef())), List.of())
                || !ManifestVerifier.matches(dataRoot, DataPaths.configHistoryRef(1),
                dataRoot.resolveDataRef(DataPaths.configHistoryRef(1)),
                dataRoot.resolveDataRef(DataPaths.manifestRef(DataPaths.configHistoryRef(1))), List.of())) {
            throw new StorageException("initial portable config manifest verification failed");
        }
        Files.deleteIfExists(output.resolve("runtime").resolve("dirty"));
        Files.deleteIfExists(output.resolve("runtime"));
        try (var files = Files.walk(output)) {
            List<String> actual = files.filter(Files::isRegularFile)
                    .map(output::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
            List<String> expected = refs.stream().sorted().toList();
            if (!actual.equals(expected)) {
                throw new StorageException("unexpected initial portable data files: " + actual);
            }
        }
    }
}