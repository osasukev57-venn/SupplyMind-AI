package com.supplymind.foundation.boot;

import com.supplymind.foundation.model.SchemaValidationException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The sole runtime data-root configuration. It intentionally has no secondary
 * cache, fallback, or database location.
 */
@ConfigurationProperties(prefix = "supplymind")
public class SupplyMindDataProperties {

    private String dataRoot;

    public String getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }

    public Path normalizedDataRoot() {
        if (dataRoot == null || dataRoot.isBlank()) {
            throw new SchemaValidationException("supplymind.data-root is required");
        }
        Path supplied = Path.of(dataRoot);
        if (!supplied.isAbsolute()) {
            throw new SchemaValidationException("supplymind.data-root must be an absolute path");
        }
        return supplied.normalize();
    }

    public Path requireSameRoot(Path candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.equals(normalizedDataRoot())) {
            throw new SchemaValidationException("A second dataRoot is not permitted");
        }
        return normalized;
    }
}