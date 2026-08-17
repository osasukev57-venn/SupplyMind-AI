package com.supplymind.warning;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;
import com.supplymind.foundation.storage.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * D8-T02 read-only warning query with REAL from/to semantics. The scan covers exactly the
 * months overlapping [from,to] - it never approximates an arbitrary range into a fixed
 * lookback. Only warning/YYYY-MM/&lt;warningId&gt;.json files are WarningRecord candidates;
 * .ack.json, .manifest.json, tmp and bak files are excluded by construction and never decoded
 * as records. Every record is manifest-verified; a broken file is reported as an explicit
 * corrupt entry, never silently dropped. Controllers never scan the filesystem themselves.
 */
public final class WarningQueryService {

    private final DataRoot dataRoot;
    private final WarningAckStore ackStore;

    public WarningQueryService(DataRoot dataRoot, WarningAckStore ackStore) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.ackStore = Objects.requireNonNull(ackStore, "ackStore");
    }

    /** All manifest-verified warnings for one item over the exact [from,to] range, newest first. */
    public List<WarningRecordV1> queryByRange(String itemId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        List<WarningRecordV1> warnings = new ArrayList<>();
        YearMonth cursor = YearMonth.from(from);
        YearMonth end = YearMonth.from(to);
        while (!cursor.isAfter(end)) {
            Path monthDir = dataRoot.resolveInternalRelative("warning").resolve(cursor.toString());
            if (Files.isDirectory(monthDir)) {
                warnings.addAll(scanMonth(monthDir, cursor, itemId));
            }
            cursor = cursor.plusMonths(1);
        }
        warnings.sort(Comparator.comparing(
                WarningRecordV1::evaluatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(warnings);
    }

    public Optional<WarningRecordV1> findByWarningId(String itemId, String warningId) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(warningId, "warningId");
        return queryByRange(itemId, LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)).stream()
                .filter(warning -> warning.warningId().equals(warningId))
                .findFirst();
    }

    /**
     * M2: delegates to the SINGLE authoritative DEC-061 verification entry (WarningAckStore.
     * readVerified). A warning is acknowledged=true ONLY when the sidecar AND the original
     * warning file fully bind (warningId/ref/SHA-256/manifests); any tampering fails closed.
     */
    public boolean isAcknowledged(WarningRecordV1 warning) {
        return ackStore.isAcknowledgedVerified(warning);
    }

    public String ackRefOf(WarningRecordV1 warning) {
        return DataPaths.warningAckRef(warning.warningMonth(), warning.warningId());
    }

    private List<WarningRecordV1> scanMonth(Path monthDir, YearMonth month, String itemId) {
        List<WarningRecordV1> warnings = new ArrayList<>();
        try (Stream<Path> files = Files.list(monthDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!isWarningRecordFile(name)) {
                    continue; // .ack.json / .manifest.json / tmp / bak / other artifacts are not records
                }
                WarningRecordV1 warning;
                try {
                    String ref = "warning/" + month + "/" + name;
                    Path manifest = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
                    if (!Files.isRegularFile(manifest)
                            || !ManifestVerifier.matches(dataRoot, ref, file, manifest)) {
                        continue; // a manifest-invalid record is skipped for the range query
                    }
                    warning = JsonV1Codec.decodeFile(Files.readAllBytes(file), WarningRecordV1.class);
                } catch (IOException | RuntimeException broken) {
                    continue; // one broken file never aborts the scan of the remaining evidence
                }
                if (itemId.equals(warning.itemId())) {
                    warnings.add(warning);
                }
            }
        } catch (IOException listingFailed) {
            throw new StorageException("Unable to list warning month " + month, listingFailed);
        }
        return warnings;
    }

    /** DEC-061 rule: only &lt;warningId&gt;.json is a WarningRecord candidate. */
    private static boolean isWarningRecordFile(String name) {
        return name.endsWith(".json")
                && !name.endsWith(".manifest.json")
                && !name.endsWith(".ack.json")
                && !name.endsWith(".tmp")
                && !name.endsWith(".bak");
    }
}
