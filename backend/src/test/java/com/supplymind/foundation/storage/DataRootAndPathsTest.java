package com.supplymind.foundation.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataRootAndPathsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void explicitRootMustBeAbsoluteAndAllReferencesStayInsideItsOneRoot() {
        assertThrows(StorageException.class, () -> DataRoot.fromConfiguredPath("relative-data"));

        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("测试数据目录"));
        root.createIfAbsentAndRequireWritable();
        assertTrue(root.path().isAbsolute());
        assertEquals(root.path().resolve("staging/run-1.json"), root.resolveDataRef("staging/run-1.json"));
        assertThrows(StorageException.class, () -> root.resolveDataRef("../outside.json"));
        assertThrows(StorageException.class, () -> root.resolveDataRef("C:/outside.json"));
        assertThrows(StorageException.class, () -> root.resolveDataRef("staging\\run-1.json"));
    }

    @Test
    void rawAndQuarantinePartitionByReceivedAtInAsiaShanghai() {
        OffsetDateTime boundary = OffsetDateTime.parse("2026-07-31T16:30:00Z");
        assertEquals("raw/test/synthetic_demo/ITEM.1/2026/08/run-1.json",
                DataPaths.rawRef("test", "synthetic_demo", "ITEM.1", boundary, "run-1"));
        assertEquals("quarantine/ITEM.1/2026-08/run-1.json",
                DataPaths.quarantineRef("ITEM.1", boundary, "run-1"));
    }

    @Test
    void atomicMoveProbeUsesOnlyTheConfiguredDataRootAndLeavesNoProbeFiles() throws Exception {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("atomic"));
        AtomicMoveSupport.probeOrFail(root);
        try (var stream = Files.list(root.path())) {
            assertFalse(stream.anyMatch(path -> path.getFileName().toString().contains("atomic-probe")));
        }
    }

    @Test
    void onlyOneWriterCanOwnTheSameDataRoot() {
        DataRoot root = DataRoot.forTest(temporaryDirectory.resolve("single-writer"));
        try (SingleWriterGuard ignored = SingleWriterGuard.acquire(root)) {
            assertThrows(StorageException.class, () -> SingleWriterGuard.acquire(root));
        }
    }
}