package com.supplymind.foundation.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * The one and only physical data root used by the application process.
 * References are always slash-separated paths relative to this root.
 */
public final class DataRoot {

    private final Path absoluteNormalizedPath;
    private java.util.Map<String, String> evidenceBindings;

    private DataRoot(Path absoluteNormalizedPath) {
        this.absoluteNormalizedPath = absoluteNormalizedPath;
    }

    public static DataRoot fromConfiguredPath(String configuredPath) {
        boolean developmentDefault = configuredPath == null || configuredPath.isBlank();
        Path candidate = developmentDefault
                ? Paths.get(System.getProperty("user.dir"), "data")
                : Paths.get(configuredPath);
        if (!developmentDefault && !candidate.isAbsolute()) {
            throw new StorageException("supplymind.data-root must be an absolute path when explicitly configured");
        }
        return new DataRoot(candidate.toAbsolutePath().normalize());
    }

    /** Tests must call this explicitly rather than relying on the development default. */
    public static DataRoot forTest(Path temporaryDirectory) {
        Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
        return new DataRoot(temporaryDirectory.toAbsolutePath().normalize());
    }

    /** Read-only resolver used only by report verification; never falls back to current mutable files. */
    public DataRoot evidenceView(java.util.Map<String, String> bindings) {
        for (var entry : bindings.entrySet()) {
            DataPaths.requireLegalDataRef(entry.getKey()); DataPaths.requireLegalDataRef(entry.getValue());
            if (!entry.getValue().startsWith("report-evidence/") && !entry.getKey().equals(entry.getValue()))
                throw new StorageException("Invalid evidence mapping");
        }
        DataRoot view = new DataRoot(absoluteNormalizedPath);
        view.evidenceBindings = java.util.Map.copyOf(bindings);
        return view;
    }

    public Path path() {
        return absoluteNormalizedPath;
    }

    public void createIfAbsentAndRequireWritable() {
        try {
            Files.createDirectories(absoluteNormalizedPath);
            if (!Files.isDirectory(absoluteNormalizedPath) || !Files.isWritable(absoluteNormalizedPath)) {
                throw new StorageException("Configured supplymind.data-root is not a writable directory: "
                        + absoluteNormalizedPath);
            }
        } catch (IOException exception) {
            throw new StorageException("Cannot create configured supplymind.data-root: " + absoluteNormalizedPath,
                    exception);
        }
    }

    public Path resolveDataRef(String dataRef) {
        DataPaths.requireLegalDataRef(dataRef);
        if (evidenceBindings != null) {
            String bound = evidenceBindings.get(dataRef);
            if (bound == null) throw new StorageException("Evidence is outside the frozen closure: " + dataRef);
            dataRef = bound;
        }
        Path resolved = absoluteNormalizedPath.resolve(dataRef.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(absoluteNormalizedPath)) {
            throw new StorageException("Data reference escapes dataRoot: " + dataRef);
        }
        return resolved;
    }

    public Path resolveInternalRelative(String relativePath) {
        DataPaths.requireSafeRelativePath(relativePath);
        Path resolved = absoluteNormalizedPath.resolve(relativePath.replace('/', java.io.File.separatorChar)).normalize();
        if (!resolved.startsWith(absoluteNormalizedPath)) {
            throw new StorageException("Internal relative path escapes dataRoot: " + relativePath);
        }
        return resolved;
    }

    public String toDataRef(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(absoluteNormalizedPath)) {
            throw new StorageException("Path is outside dataRoot: " + normalized);
        }
        String reference = absoluteNormalizedPath.relativize(normalized).toString().replace('\\', '/');
        DataPaths.requireLegalDataRef(reference);
        return reference;
    }
}
