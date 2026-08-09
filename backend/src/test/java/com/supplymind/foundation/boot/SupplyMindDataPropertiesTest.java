package com.supplymind.foundation.boot;

import com.supplymind.foundation.model.SchemaValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplyMindDataPropertiesTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void normalizesTheOnlyAbsoluteDataRoot() {
        SupplyMindDataProperties properties = new SupplyMindDataProperties();
        properties.setDataRoot(temporaryRoot.resolve("nested").resolve("..").toString());

        assertEquals(temporaryRoot, properties.normalizedDataRoot());
        assertEquals(temporaryRoot, properties.requireSameRoot(temporaryRoot));
    }

    @Test
    void rejectsRelativeAndSecondRoots() {
        SupplyMindDataProperties properties = new SupplyMindDataProperties();
        properties.setDataRoot("relative-data");
        assertThrows(SchemaValidationException.class, properties::normalizedDataRoot);

        properties.setDataRoot(temporaryRoot.toString());
        assertThrows(SchemaValidationException.class,
                () -> properties.requireSameRoot(temporaryRoot.resolve("another-root")));
    }
}
