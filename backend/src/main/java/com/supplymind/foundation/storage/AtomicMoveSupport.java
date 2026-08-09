package com.supplymind.foundation.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Strict NIO ATOMIC_MOVE support; no non-atomic fallback is permitted. */
public final class AtomicMoveSupport {

    private AtomicMoveSupport() {
    }

    public static void moveToEmptyTarget(Path source, Path emptyTarget) {
        if (Files.exists(emptyTarget)) {
            throw new StorageException("Atomic move target must be absent: " + emptyTarget);
        }
        try {
            Files.move(source, emptyTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new StorageException("ATOMIC_MOVE is unavailable for " + source.getParent(), exception);
        } catch (IOException exception) {
            throw new StorageException("Atomic move failed from " + source + " to " + emptyTarget, exception);
        }
    }

    /**
     * Startup fail-fast probe. It deliberately moves an existing target away,
     * then moves a forced tmp file into the resulting empty target.
     */
    public static void probeOrFail(DataRoot dataRoot) {
        dataRoot.createIfAbsentAndRequireWritable();
        String id = "atomic-probe-" + UUID.randomUUID();
        Path target = dataRoot.path().resolve("." + id + ".target");
        Path temporary = dataRoot.path().resolve("." + id + ".tmp");
        Path backup = dataRoot.path().resolve("." + id + ".bak");
        byte[] expected = FileDigest.utf8("SupplyMind AI atomic move probe\n");
        try {
            FileDigest.writeCreateNewAndForce(target, FileDigest.utf8("previous\n"));
            FileDigest.writeCreateNewAndForce(temporary, expected);
            moveToEmptyTarget(target, backup);
            moveToEmptyTarget(temporary, target);
            if (!FileDigest.bytesEqual(target, expected)) {
                throw new StorageException("ATOMIC_MOVE probe bytes do not match after move: " + dataRoot.path());
            }
        } finally {
            deleteProbeFile(target);
            deleteProbeFile(temporary);
            deleteProbeFile(backup);
        }
    }

    private static void deleteProbeFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new StorageException("Unable to clean ATOMIC_MOVE probe file " + path, exception);
        }
    }
}
