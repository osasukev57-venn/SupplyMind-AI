package com.supplymind.config;

import com.supplymind.config.api.ConfigV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * D8-T01 read-only configVersion audit query. It lists the immutable config/history snapshots,
 * verifies each file against its adjacent manifest and reports a corrupt/missing snapshot as an
 * explicit entry issue - it never throws away the rest and never throws 500 for one bad file.
 * Controllers never scan the filesystem themselves.
 */
public final class ConfigHistoryQueryService {

    private static final Pattern VERSION_FILE = Pattern.compile("([1-9][0-9]*)\\.json");

    private final DataRoot dataRoot;

    public ConfigHistoryQueryService(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    public List<ConfigV1.HistoryEntry> history() {
        Path historyDir = dataRoot.resolveInternalRelative("config/history");
        List<ConfigV1.HistoryEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(historyDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(historyDir)) {
            List<Path> snapshots = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().endsWith(".manifest.json"))
                    .toList();
            for (Path snapshot : snapshots) {
                String fileName = snapshot.getFileName().toString();
                Matcher matcher = VERSION_FILE.matcher(fileName);
                if (!matcher.matches()) {
                    entries.add(new ConfigV1.HistoryEntry(-1, false,
                            "unexpected file in config/history: " + fileName));
                    continue;
                }
                int configVersion = Integer.parseInt(matcher.group(1));
                String ref = DataPaths.configHistoryRef(configVersion);
                Path manifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
                boolean verified = ManifestVerifier.matches(dataRoot, ref, snapshot, manifest, List.of());
                // M1: manifest integrity alone is NOT "decode verification". The snapshot must
                // actually decode as MonitorSeriesConfigV1 AND its configVersion must match the
                // filename - any schema/decode/version mismatch is an explicit verified=false.
                if (verified) {
                    try {
                        com.supplymind.foundation.model.MonitorSeriesConfigV1 decoded =
                                JsonV1Codec.decodeFile(Files.readAllBytes(snapshot),
                                        com.supplymind.foundation.model.MonitorSeriesConfigV1.class);
                        if (decoded.configVersion() != configVersion) {
                            verified = false;
                        }
                    } catch (IOException | RuntimeException decodeFailed) {
                        verified = false;
                    }
                }
                entries.add(new ConfigV1.HistoryEntry(
                        configVersion, verified,
                        verified ? null : "config/history/" + fileName + " fails manifest, decode or configVersion verification"));
            }
        } catch (IOException exception) {
            throw new com.supplymind.foundation.storage.StorageException(
                    "Unable to list config/history", exception);
        }
        entries.sort(Comparator.comparingInt(ConfigV1.HistoryEntry::configVersion));
        return List.copyOf(entries);
    }
}
