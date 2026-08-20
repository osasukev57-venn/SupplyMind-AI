package com.supplymind.desktop;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableInitialConfigExporterTest {

    @TempDir
    Path temp;

    @Test
    void exportsOnlyCanonicalDeterministicConfigAndManifests() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        PortableInitialConfigExporter.main(new String[]{first.toString(), "2026-08-20T00:00:00+08:00"});
        PortableInitialConfigExporter.main(new String[]{second.toString(), "2026-08-20T00:00:00+08:00"});

        Map<String, byte[]> firstFiles = files(first);
        Map<String, byte[]> secondFiles = files(second);
        assertEquals(firstFiles.keySet(), secondFiles.keySet());
        assertEquals(4, firstFiles.size());
        firstFiles.forEach((name, bytes) -> assertArrayEquals(bytes, secondFiles.get(name), name));

        assertArrayEquals(firstFiles.get("config/monitor-series.json"),
                firstFiles.get("config/history/1.json"));
        MonitorSeriesConfigV1 config = JsonV1Codec.decodeFile(
                firstFiles.get("config/monitor-series.json"), MonitorSeriesConfigV1.class);
        assertEquals(1, config.configVersion());
        assertEquals(6, config.items().size());
        assertTrue(config.items().stream().anyMatch(item -> item.itemId().equals("FX.USD.CNY.PBOC_MID")));
        assertTrue(config.items().stream().anyMatch(item -> item.itemId().equals("MAT.ADC12.AM")));
    }

    private static Map<String, byte[]> files(Path root) throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return result;
    }
}