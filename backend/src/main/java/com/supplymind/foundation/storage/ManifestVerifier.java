package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Validates a manifest as derived integrity metadata, never as the source of business truth. */
public final class ManifestVerifier {

    private ManifestVerifier() {
    }

    public static boolean matches(Path dataPath, Path manifestPath) {
        return matches(dataPath, manifestPath, null);
    }

    /** When expected run IDs are supplied, verify their canonical deduplicated set too. */
    public static boolean matches(Path dataPath, Path manifestPath, List<String> expectedSourceRunIds) {
        if (!Files.isRegularFile(dataPath) || !Files.isRegularFile(manifestPath)) {
            return false;
        }
        try {
            byte[] dataBytes = Files.readAllBytes(dataPath);
            ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
            return hasBasicIntegrity(manifest, dataPath, dataBytes, expectedSourceRunIds);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Production write/recovery validation with an explicit dataRoot-relative
     * reference, so all frozen ManifestV1 derived fields can be rechecked.
     */
    public static boolean matches(DataRoot dataRoot, String dataRef, Path dataPath, Path manifestPath) {
        return matches(dataRoot, dataRef, dataPath, manifestPath, null);
    }

    public static boolean matches(
            DataRoot dataRoot,
            String dataRef,
            Path dataPath,
            Path manifestPath,
            List<String> expectedSourceRunIds
    ) {
        if (dataRoot == null || dataRef == null || !Files.isRegularFile(dataPath) || !Files.isRegularFile(manifestPath)) {
            return false;
        }
        try {
            Path expectedDataPath = dataRoot.resolveDataRef(dataRef);
            if (!expectedDataPath.equals(dataPath.toAbsolutePath().normalize())) {
                return false;
            }
            byte[] dataBytes = Files.readAllBytes(dataPath);
            ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
            if (!hasBasicIntegrity(manifest, dataPath, dataBytes, expectedSourceRunIds)) {
                return false;
            }
            new ManifestDerivedFieldsVerifier(dataRoot).verify(dataRef, dataBytes, manifest);
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasBasicIntegrity(
            ManifestV1 manifest,
            Path dataPath,
            byte[] dataBytes,
            List<String> expectedSourceRunIds
    ) {
        boolean sourceRunsMatch = expectedSourceRunIds == null
                || manifest.sourceRunIds().equals(canonicalRunIds(expectedSourceRunIds));
        return sourceRunsMatch
                && manifest.fileName().equals(dataPath.getFileName().toString())
                && manifest.fileSha256().equals(FileDigest.sha256(dataBytes))
                && manifest.byteLength() == dataBytes.length
                && ManifestV1.COMMITTED.equals(manifest.commitState());
    }

    private static List<String> canonicalRunIds(List<String> sourceRunIds) {
        List<String> canonical = new ArrayList<>(sourceRunIds);
        canonical.sort(Comparator.naturalOrder());
        return canonical.stream().distinct().toList();
    }
}