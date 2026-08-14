package com.supplymind.agent.evidence;

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
