package com.supplymind.foundation.storage;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.AggregateInputRefV1;
import com.supplymind.foundation.model.AggregateRecordV1;
import com.supplymind.foundation.model.DailyInputRefV1;
import com.supplymind.foundation.model.DailyRecordV1;
import com.supplymind.foundation.model.LifecycleTimelineV1;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.model.MonitorSeriesConfigV1;
import com.supplymind.foundation.model.QuarantineProjectionV1;
import com.supplymind.foundation.model.RawReceiptV1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * One frozen ManifestV1 derivation implementation for both atomic commit
 * validation and marker-proven recovery. Aggregate provenance is resolved
 * only from persisted, independently valid daily files and manifests.
 */
final class ManifestDerivedFieldsVerifier {

    private final DataRoot dataRoot;

    ManifestDerivedFieldsVerifier(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    void verify(String dataRef, byte[] dataBytes, ManifestV1 manifest) {
        Objects.requireNonNull(manifest, "manifest");
        DerivedFields expected = deriveFields(dataRef, dataBytes);
        if (!Objects.equals(manifest.rowCount(), expected.rowCount())
                || !Objects.equals(manifest.minBusinessDate(), expected.minBusinessDate())
                || !Objects.equals(manifest.maxBusinessDate(), expected.maxBusinessDate())) {
            if (expected.rowCount() == null) {
                throw new StorageException("JSON manifest rowCount/minBusinessDate/maxBusinessDate must all be null: " + dataRef);
            }
            throw new StorageException("Manifest CSV rowCount/date range must be derived from " + dataRef);
        }
        if (!manifest.sourceRunIds().equals(expected.sourceRunIds())) {
            throw new StorageException("Manifest sourceRunIds must be derived from " + dataRef);
        }
    }

    /** Package-private for the recovery path; generatedAt is supplied by its server Clock. */
    ManifestV1 derive(String dataRef, byte[] dataBytes, OffsetDateTime generatedAt) {
        Objects.requireNonNull(generatedAt, "generatedAt");
        DerivedFields fields = deriveFields(dataRef, dataBytes);
        if (fields.rowCount() == null) {
            return ManifestFactory.json(dataRef, dataBytes, fields.sourceRunIds(), generatedAt);
        }
        return ManifestFactory.csv(
                dataRef,
                dataBytes,
                fields.rowCount(),
                fields.minBusinessDate(),
                fields.maxBusinessDate(),
                fields.sourceRunIds(),
                generatedAt
        );
    }

    private DerivedFields deriveFields(String dataRef, byte[] dataBytes) {
        Objects.requireNonNull(dataRef, "dataRef");
        Objects.requireNonNull(dataBytes, "dataBytes");
        if (isJsonTarget(dataRef)) {
            return deriveJson(dataRef, dataBytes);
        }
        if (dataRef.startsWith("processed/daily/")) {
            return deriveDaily(dataBytes);
        }
        if (dataRef.startsWith("processed/aggregate/")) {
            return deriveAggregate(dataBytes);
        }
        throw new StorageException("No D1-T03 manifest derivation rule is registered for " + dataRef);
    }

    private static boolean isJsonTarget(String dataRef) {
        return dataRef.equals(DataPaths.configActiveRef())
                || dataRef.startsWith("config/history/")
                || dataRef.startsWith("raw/")
                || dataRef.startsWith("staging/")
                || dataRef.startsWith("quarantine/")
                || dataRef.startsWith("runtime/conflicts/raw/");
    }

    private static DerivedFields deriveJson(String dataRef, byte[] dataBytes) {
        if (dataRef.equals(DataPaths.configActiveRef()) || dataRef.startsWith("config/history/")) {
            JsonV1Codec.decodeFile(dataBytes, MonitorSeriesConfigV1.class);
            return jsonFields(List.of());
        }
        if (dataRef.startsWith("raw/")) {
            RawReceiptV1 raw = JsonV1Codec.decodeFile(dataBytes, RawReceiptV1.class);
            return jsonFields(List.of(raw.runId()));
        }
        if (dataRef.startsWith("staging/")) {
            LifecycleTimelineV1 timeline = JsonV1Codec.decodeFile(dataBytes, LifecycleTimelineV1.class);
            return jsonFields(List.of(timeline.runId()));
        }
        if (dataRef.startsWith("quarantine/")) {
            QuarantineProjectionV1 quarantine = JsonV1Codec.decodeFile(dataBytes, QuarantineProjectionV1.class);
            return jsonFields(List.of(quarantine.runId()));
        }
        if (dataRef.startsWith("runtime/conflicts/raw/")) {
            RawConflictEvidenceV1 conflict = JsonV1Codec.decodeFile(dataBytes, RawConflictEvidenceV1.class);
            return jsonFields(List.of(conflict.runId()));
        }
        throw new StorageException("No JSON manifest derivation rule is registered for " + dataRef);
    }

    private static DerivedFields jsonFields(Collection<String> sourceRunIds) {
        return new DerivedFields(null, null, null, canonicalRunIds(sourceRunIds));
    }

    private static DerivedFields deriveDaily(byte[] dataBytes) {
        List<DailyRecordV1> rows = CsvV1Codec.decodeDaily(dataBytes);
        return new DerivedFields(
                (long) rows.size(),
                rows.stream().map(DailyRecordV1::businessDate).min(String::compareTo).orElse(null),
                rows.stream().map(DailyRecordV1::businessDate).max(String::compareTo).orElse(null),
                canonicalRunIds(rows.stream()
                        .flatMap(row -> row.inputRefs().stream())
                        .map(DailyInputRefV1::runId)
                        .toList())
        );
    }

    private DerivedFields deriveAggregate(byte[] dataBytes) {
        List<AggregateRecordV1> rows = CsvV1Codec.decodeAggregate(dataBytes);
        TreeSet<String> sourceRunIds = new TreeSet<>();
        for (AggregateRecordV1 row : rows) {
            for (AggregateInputRefV1 input : row.inputRefs()) {
                sourceRunIds.addAll(sourceRunIdsFromPersistedDailyManifest(input));
            }
        }
        return new DerivedFields(
                (long) rows.size(),
                rows.stream().map(AggregateRecordV1::periodStart).min(String::compareTo).orElse(null),
                rows.stream().map(AggregateRecordV1::periodEnd).max(String::compareTo).orElse(null),
                List.copyOf(sourceRunIds)
        );
    }

    private List<String> sourceRunIdsFromPersistedDailyManifest(AggregateInputRefV1 input) {
        Path dailyPath = dataRoot.resolveDataRef(input.dailyFileRef());
        Path dailyManifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(input.dailyFileRef()));
        if (!ManifestVerifier.matches(dataRoot, input.dailyFileRef(), dailyPath, dailyManifestPath)) {
            throw new StorageException("Aggregate manifest cannot derive sourceRunIds from a valid persisted daily manifest: "
                    + input.dailyFileRef());
        }
        try {
            ManifestV1 dailyManifest = JsonV1Codec.decodeFile(Files.readAllBytes(dailyManifestPath), ManifestV1.class);
            if (!input.fileSha256().equals(dailyManifest.fileSha256())) {
                throw new StorageException("Aggregate inputRef.fileSha256 must match its referenced daily manifest: "
                        + input.dailyFileRef());
            }
            return dailyManifest.sourceRunIds();
        } catch (IOException exception) {
            throw new StorageException("Unable to read referenced daily manifest for aggregate: " + dailyManifestPath, exception);
        }
    }

    private static List<String> canonicalRunIds(Collection<String> sourceRunIds) {
        return new TreeSet<>(sourceRunIds).stream().toList();
    }

    private record DerivedFields(
            Long rowCount,
            String minBusinessDate,
            String maxBusinessDate,
            List<String> sourceRunIds
    ) {
    }
}