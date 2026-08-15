package com.supplymind.agent.evidence;

import com.supplymind.foundation.codec.CsvV1Codec;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.ManifestVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * D6-T02 evidence verifier: checks that every referenced file really exists under the data
 * root and that its adjacent manifest verifies (integrity). A missing, unverifiable, or unsafe
 * reference is reported honestly (MISSING/INVALID/UNAVAILABLE) - never silently accepted, and
 * never completed by the LLM. sha256 is taken from the verified manifest when present.
 */
public final class EvidenceRefVerifier {

    private final DataRoot dataRoot;

    public EvidenceRefVerifier(DataRoot dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    }

    /** Verifies every ref and returns the schema entries (sorted by evidenceRefId). */
    public List<EvidencePackV1.EvidenceRefEntry> verifyAll(List<String> refs) {
        Objects.requireNonNull(refs, "refs");
        List<EvidencePackV1.EvidenceRefEntry> entries = new ArrayList<>();
        for (String ref : refs) {
            entries.add(verify(ref));
        }
        entries.sort(java.util.Comparator.comparing(EvidencePackV1.EvidenceRefEntry::evidenceRefId));
        return List.copyOf(entries);
    }

    /** Verifies refs and enriches the entries with the real production lineage from the tool result. */
    public List<EvidencePackV1.EvidenceRefEntry> verifyAll(
            List<String> refs, com.supplymind.agent.tool.ToolResult.Lineage lineage
    ) {
        List<EvidencePackV1.EvidenceRefEntry> entries = new ArrayList<>();
        for (String ref : refs) {
            EvidencePackV1.EvidenceRefEntry entry = verify(ref);
            entries.add(enrich(entry, lineage));
        }
        entries.sort(java.util.Comparator.comparing(EvidencePackV1.EvidenceRefEntry::evidenceRefId));
        return List.copyOf(entries);
    }

    private static EvidencePackV1.EvidenceRefEntry enrich(
            EvidencePackV1.EvidenceRefEntry entry, com.supplymind.agent.tool.ToolResult.Lineage lineage
    ) {
        if (lineage == null || entry.status() != EvidenceStatus.VERIFIED) {
            return entry;
        }
        return new EvidencePackV1.EvidenceRefEntry(
                entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                entry.status(), entry.reasonCode(), entry.runId(), entry.rawRef(), entry.publishRef(),
                entry.businessDate(), entry.periodStart(), entry.periodEnd(),
                entry.validationVersion() == null ? lineage.validationVersion() : entry.validationVersion(),
                entry.calculationVersion() == null ? lineage.calculationVersion() : entry.calculationVersion(),
                entry.calendarVersion() == null ? lineage.calendarVersion() : entry.calendarVersion(),
                entry.configVersions().isEmpty() ? lineage.configVersions() : entry.configVersions());
    }

    public EvidencePackV1.EvidenceRefEntry verify(String ref) {
        Objects.requireNonNull(ref, "ref");
        String evidenceRefId = "ev-" + Math.abs(ref.hashCode());
        String refType = refTypeOf(ref);
        if (refType == null) {
            return entry(evidenceRefId, ref, null, EvidenceStatus.UNAVAILABLE, "UNSAFE_REF", null);
        }
        try {
            DataPaths.requireLegalDataRef(ref);
        } catch (RuntimeException exception) {
            return entry(evidenceRefId, ref, refType, EvidenceStatus.UNAVAILABLE, "UNSAFE_REF", null);
        }
        Path filePath = dataRoot.resolveDataRef(ref);
        Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        if (!Files.isRegularFile(filePath)) {
            return entry(evidenceRefId, ref, refType, EvidenceStatus.MISSING, "FILE_NOT_FOUND", null);
        }
        if (!Files.isRegularFile(manifestPath)
                || !ManifestVerifier.matches(dataRoot, ref, filePath, manifestPath)) {
            return entry(evidenceRefId, ref, refType, EvidenceStatus.INVALID, "MANIFEST_MISMATCH", null);
        }
        String sha256 = null;
        try {
            ManifestV1 manifest = JsonV1Codec.decodeFile(Files.readAllBytes(manifestPath), ManifestV1.class);
            sha256 = manifest.fileSha256();
        } catch (IOException | RuntimeException ignored) {
            sha256 = null;
        }
        if (sha256 == null) {
            return entry(evidenceRefId, ref, refType, EvidenceStatus.UNAVAILABLE, "NO_SHA256", null);
        }
        return entry(evidenceRefId, ref, refType, EvidenceStatus.VERIFIED, null, sha256);
    }

    public boolean isVerified(EvidencePackV1.EvidenceRefEntry entry) {
        return entry != null && entry.status() == EvidenceStatus.VERIFIED;
    }

    /**
     * M2/M4: verifies a ref AND recovers its AUTHORITATIVE lineage by decoding the real
     * evidence file (never the report's self-reported lineage). For multi-row CSV files EVERY
     * row is read and each frozen lineage field must be uniform across the whole file - a
     * heterogeneous field cannot be honestly expressed by the V1 scalar contract and fails
     * closed as UNAVAILABLE/AMBIGUOUS_FILE_LINEAGE (the first row never represents the file).
     * A manifest-valid file that cannot be decoded is INVALID/SCHEMA_DECODE_FAILED - it is
     * never silently marked VERIFIED. Applicable fields are filled per refType; fields not
     * applicable to a type stay null per the frozen contract.
     */
    public EvidencePackV1.EvidenceRefEntry verifyWithAuthoritativeLineage(String ref) {
        EvidencePackV1.EvidenceRefEntry entry = verify(ref);
        if (entry.status() != EvidenceStatus.VERIFIED) {
            return entry;
        }
        try {
            Path path = dataRoot.resolveDataRef(ref);
            byte[] bytes = Files.readAllBytes(path);
            if (ref.startsWith("raw/source/") || ref.startsWith("raw/import/")) {
                return entry; // SOURCE original entities are allowed non-structured: no decode
            }
            if (ref.startsWith("processed/daily/")) {
                ManifestV1 manifest = JsonV1Codec.decodeFile(
                        Files.readAllBytes(dataRoot.resolveDataRef(DataPaths.manifestRef(ref))),
                        ManifestV1.class);
                return dailyLineage(entry, bytes, manifest);
            }
            if (ref.startsWith("processed/aggregate/")) {
                ManifestV1 manifest = JsonV1Codec.decodeFile(
                        Files.readAllBytes(dataRoot.resolveDataRef(DataPaths.manifestRef(ref))),
                        ManifestV1.class);
                return aggregateLineage(entry, bytes, manifest);
            }
            if (ref.startsWith("raw/")) {
                com.supplymind.foundation.model.RawReceiptV1 raw = JsonV1Codec.decodeFile(
                        bytes, com.supplymind.foundation.model.RawReceiptV1.class);
                return new EvidencePackV1.EvidenceRefEntry(
                        entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                        entry.status(), entry.reasonCode(), raw.runId(), ref, null,
                        raw.sourceBusinessDate(), null, null,
                        null, null, null,
                        List.of(String.valueOf(raw.configVersion())));
            }
            if (ref.startsWith("staging/")) {
                com.supplymind.foundation.model.LifecycleTimelineV1 timeline =
                        JsonV1Codec.decodeFile(bytes,
                                com.supplymind.foundation.model.LifecycleTimelineV1.class);
                com.supplymind.foundation.model.LifecycleSnapshotV1 current = timeline.current();
                String publishRef = current == null ? null : current.publishRef();
                String validationVersion = current == null ? null : current.validationVersion();
                String businessDate = null;
                if (current != null && current.candidate() != null) {
                    businessDate = current.candidate().businessDate();
                }
                return new EvidencePackV1.EvidenceRefEntry(
                        entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                        entry.status(), entry.reasonCode(), timeline.runId(), timeline.rawRef(),
                        publishRef, businessDate, null, null,
                        validationVersion, null, null, List.of());
            }
            if (ref.equals(DataPaths.configActiveRef()) || ref.startsWith("config/history/")) {
                com.supplymind.foundation.model.MonitorSeriesConfigV1 config =
                        JsonV1Codec.decodeFile(bytes,
                                com.supplymind.foundation.model.MonitorSeriesConfigV1.class);
                return new EvidencePackV1.EvidenceRefEntry(
                        entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                        entry.status(), entry.reasonCode(), null, null, null,
                        null, null, null, null, null, null,
                        List.of(String.valueOf(config.configVersion())));
            }
            return entry; // WARNING and other manifest-valid refs carry no decodable lineage
        } catch (IOException | RuntimeException exception) {
            return new EvidencePackV1.EvidenceRefEntry(
                    entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                    EvidenceStatus.INVALID, "SCHEMA_DECODE_FAILED",
                    null, null, null, null, null, null, null, null, null, List.of());
        }
    }

    /**
     * M2: DAILY file - the business date is row-level data: the manifest's min/max business
     * dates are the FILE-LEVEL authority (never a single row). The frozen file-level LINEAGE
     * fields (validationVersion/calculationVersion/calendarVersion/configVersions) must be
     * uniform across ALL rows or the file fails closed as ambiguous.
     */
    private static EvidencePackV1.EvidenceRefEntry dailyLineage(
            EvidencePackV1.EvidenceRefEntry entry, byte[] bytes, ManifestV1 manifest
    ) {
        List<com.supplymind.foundation.model.DailyRecordV1> rows = CsvV1Codec.decodeDaily(bytes);
        if (rows.isEmpty()) {
            return ambiguous(entry);
        }
        List<String> validationVersions = new ArrayList<>();
        List<String> calculationVersions = new ArrayList<>();
        List<String> calendarVersions = new ArrayList<>();
        List<String> configVersions = new ArrayList<>();
        for (com.supplymind.foundation.model.DailyRecordV1 row : rows) {
            validationVersions.add(row.validationVersion());
            calculationVersions.add(row.calculationVersion());
            calendarVersions.add(row.calendarVersion());
            if (row.configVersions() != null) {
                row.configVersions().stream().map(String::valueOf).forEach(configVersions::add);
            }
        }
        String validationVersion = uniform(validationVersions);
        String calculationVersion = uniform(calculationVersions);
        String calendarVersion = uniform(calendarVersions);
        String configVersion = uniform(configVersions);
        if (validationVersion == null || calculationVersion == null
                || calendarVersion == null || configVersion == null) {
            return ambiguous(entry);
        }
        return new EvidencePackV1.EvidenceRefEntry(
                entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                entry.status(), entry.reasonCode(), null, null, null,
                manifest.minBusinessDate(), manifest.minBusinessDate(), manifest.maxBusinessDate(),
                validationVersion, calculationVersion, calendarVersion,
                List.of(configVersion));
    }

    /**
     * M2: AGGREGATE file - the period is row-level data: the manifest's min/max business dates
     * are the FILE-LEVEL authority. The frozen file-level LINEAGE fields must be uniform across
     * ALL rows or the file fails closed as ambiguous.
     */
    private static EvidencePackV1.EvidenceRefEntry aggregateLineage(
            EvidencePackV1.EvidenceRefEntry entry, byte[] bytes, ManifestV1 manifest
    ) {
        List<com.supplymind.foundation.model.AggregateRecordV1> rows =
                CsvV1Codec.decodeAggregate(bytes);
        if (rows.isEmpty()) {
            return ambiguous(entry);
        }
        List<String> validationVersions = new ArrayList<>();
        List<String> calculationVersions = new ArrayList<>();
        List<String> calendarVersions = new ArrayList<>();
        List<String> configVersions = new ArrayList<>();
        for (com.supplymind.foundation.model.AggregateRecordV1 row : rows) {
            validationVersions.add(row.validationVersion());
            calculationVersions.add(row.calculationVersion());
            calendarVersions.add(row.calendarVersion());
            if (row.configVersions() != null) {
                row.configVersions().stream().map(String::valueOf).forEach(configVersions::add);
            }
        }
        String validationVersion = uniform(validationVersions);
        String calculationVersion = uniform(calculationVersions);
        String calendarVersion = uniform(calendarVersions);
        String configVersion = uniform(configVersions);
        if (validationVersion == null || calculationVersion == null
                || calendarVersion == null || configVersion == null) {
            return ambiguous(entry);
        }
        return new EvidencePackV1.EvidenceRefEntry(
                entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                entry.status(), entry.reasonCode(), null, null, null,
                null, manifest.minBusinessDate(), manifest.maxBusinessDate(),
                validationVersion, calculationVersion, calendarVersion,
                List.of(configVersion));
    }

    /** M2: the first row never represents a multi-row file - heterogeneous lineage fails closed. */
    private static EvidencePackV1.EvidenceRefEntry ambiguous(
            EvidencePackV1.EvidenceRefEntry entry
    ) {
        return new EvidencePackV1.EvidenceRefEntry(
                entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                EvidenceStatus.UNAVAILABLE, "AMBIGUOUS_FILE_LINEAGE",
                null, null, null, null, null, null, null, null, null, List.of());
    }

    /** M2: all values equal -> the uniform value; otherwise null (heterogeneous). */
    private static String uniform(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String first = values.get(0);
        for (String value : values) {
            if (!java.util.Objects.equals(first, value)) {
                return null;
            }
        }
        return first;
    }

    /** M2: verifies all refs and recovers authoritative lineage for each VERIFIED ref. */
    public List<EvidencePackV1.EvidenceRefEntry> verifyAllWithAuthoritativeLineage(List<String> refs) {
        List<EvidencePackV1.EvidenceRefEntry> entries = new ArrayList<>();
        for (String ref : refs) {
            entries.add(verifyWithAuthoritativeLineage(ref));
        }
        entries.sort(java.util.Comparator.comparing(EvidencePackV1.EvidenceRefEntry::evidenceRefId));
        return List.copyOf(entries);
    }

    /**
     * M2/M4: write-path binding - the real file is the authority for every lineage field it can
     * decode; the tool result only FILLS fields the file cannot carry (RAW files have no
     * validation/calculation/calendar/config versions). Never the reverse.
     */
    public EvidencePackV1.EvidenceRefEntry verifyMerged(
            String ref, com.supplymind.agent.tool.ToolResult.Lineage toolLineage
    ) {
        EvidencePackV1.EvidenceRefEntry entry = verifyWithAuthoritativeLineage(ref);
        if (entry.status() != EvidenceStatus.VERIFIED || toolLineage == null) {
            return entry;
        }
        return new EvidencePackV1.EvidenceRefEntry(
                entry.evidenceRefId(), entry.refType(), entry.ref(), entry.sha256(),
                entry.status(), entry.reasonCode(),
                entry.runId(), entry.rawRef(), entry.publishRef(),
                entry.businessDate(), entry.periodStart(), entry.periodEnd(),
                entry.validationVersion() != null ? entry.validationVersion() : toolLineage.validationVersion(),
                entry.calculationVersion() != null ? entry.calculationVersion() : toolLineage.calculationVersion(),
                entry.calendarVersion() != null ? entry.calendarVersion() : toolLineage.calendarVersion(),
                entry.configVersions().isEmpty() ? toolLineage.configVersions() : entry.configVersions());
    }

    private static EvidencePackV1.EvidenceRefEntry entry(
            String id, String ref, String refType, EvidenceStatus status, String reason, String sha256
    ) {
        return new EvidencePackV1.EvidenceRefEntry(
                id, refType == null ? "UNAVAILABLE" : refType,
                ref, sha256, status, reason,
                null, ref.startsWith("raw/") ? ref : null, null,
                null, null, null, null, null, null, List.of());
    }

    private static String refTypeOf(String ref) {
        if (ref == null || ref.indexOf('\\') >= 0 || ref.startsWith("/")
                || ref.matches("^[A-Za-z]:.*") || ref.indexOf("..") >= 0) {
            return null;
        }
        if (ref.startsWith("raw/source/") || ref.startsWith("raw/import/")) {
            return "SOURCE";
        }
        if (ref.startsWith("raw/")) {
            return "RAW";
        }
        if (ref.startsWith("staging/")) {
            return "LIFECYCLE";
        }
        if (ref.startsWith("processed/daily/")) {
            return "DAILY";
        }
        if (ref.startsWith("processed/aggregate/")) {
            return "AGGREGATE";
        }
        if (ref.startsWith("warning/")) {
            return "WARNING";
        }
        if (ref.startsWith("config/")) {
            return "CONFIG";
        }
        return null;
    }
}
