package com.supplymind.agent.report;

import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.model.ManifestV1;
import com.supplymind.foundation.storage.AtomicFileStore;
import com.supplymind.foundation.storage.DataPaths;
import com.supplymind.foundation.storage.DataRoot;
import com.supplymind.foundation.storage.DirtyTargetRole;
import com.supplymind.foundation.storage.DirtyTransactionType;
import com.supplymind.foundation.storage.FileTransactionTarget;
import com.supplymind.foundation.storage.ManifestFactory;
import com.supplymind.foundation.storage.ManifestVerifier;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * D6-T04 report persistence per AGENT-EVIDENCE-SCHEMA-V1 §4: data/report/YYYY-MM/<reportId>.json
 * with adjacent manifest, written atomically through the production AtomicFileStore. The
 * EvidencePack is embedded in the report and persisted together. No database, no second data
 * root, no second atomic-write implementation.
 */
public final class ReportStore {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final DataRoot dataRoot;
    private final AtomicFileStore fileStore;

    public ReportStore(DataRoot dataRoot, AtomicFileStore fileStore) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    }

    public String store(AgentReportV1 report) {
        Objects.requireNonNull(report, "report");
        YearMonth month = YearMonth.from(report.createdAt().atZoneSameInstant(SHANGHAI));
        String ref = DataPaths.reportRef(month, report.reportId());
        byte[] dataBytes = JsonV1Codec.encodeFile(report);
        ManifestV1 manifest = ManifestFactory.json(ref, dataBytes, List.of(), report.createdAt());
        byte[] manifestBytes = JsonV1Codec.encodeFile(manifest);
        fileStore.commit("agent-report-" + report.reportId(), DirtyTransactionType.SINGLE_FILE,
                report.createdAt(),
                List.of(new FileTransactionTarget(DirtyTargetRole.BUSINESS_FILE, ref,
                        dataBytes, manifestBytes, true)));
        return ref;
    }

    public boolean exists(String ref) {
        Objects.requireNonNull(ref, "ref");
        try {
            DataPaths.requireLegalDataRef(ref);
        } catch (RuntimeException exception) {
            return false;
        }
        java.nio.file.Path dataPath = dataRoot.resolveDataRef(ref);
        java.nio.file.Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
        return java.nio.file.Files.isRegularFile(dataPath)
                && java.nio.file.Files.isRegularFile(manifestPath);
    }

    /**
     * M5 restart read path: resolve a legal report ref, verify the adjacent manifest, decode the
     * AgentReportV1, verify body identity (reportId == filename identity, month path matches the
     * report's createdAt) and re-verify the embedded evidence refs. Nothing is trusted from
     * write time - the report is re-validated on every read, exactly like the rest of the
     * persisted business data.
     */
    public ReadResult read(String ref) {
        Objects.requireNonNull(ref, "ref");
        String failure = null;
        try {
            DataPaths.requireLegalDataRef(ref);
        } catch (RuntimeException exception) {
            failure = "ILLEGAL_REF";
        }
        if (failure == null) {
            String[] segments = ref.split("/");
            if (segments.length != 3 || !segments[0].equals("report")) {
                failure = "ILLEGAL_REF";
            } else {
                try {
                    YearMonth month = YearMonth.parse(segments[1]);
                    String reportId = segments[2].substring(0, segments[2].length() - ".json".length());
                    java.nio.file.Path dataPath = dataRoot.resolveDataRef(ref);
                    java.nio.file.Path manifestPath = dataRoot.resolveDataRef(DataPaths.manifestRef(ref));
                    if (!java.nio.file.Files.isRegularFile(dataPath)
                            || !java.nio.file.Files.isRegularFile(manifestPath)) {
                        failure = "REPORT_MISSING";
                    } else if (!ManifestVerifier.matches(dataRoot, ref, dataPath, manifestPath)) {
                        failure = "MANIFEST_MISMATCH";
                    } else {
                        AgentReportV1 report;
                        try {
                            report = JsonV1Codec.decodeFile(
                                    java.nio.file.Files.readAllBytes(dataPath), AgentReportV1.class);
                        } catch (java.io.IOException | RuntimeException exception) {
                            return new ReadResult(null, "REPORT_UNREADABLE");
                        }
                        if (report.reportId() == null || !report.reportId().equals(reportId)) {
                            failure = "IDENTITY_MISMATCH";
                        } else if (!YearMonth.from(report.createdAt().atZoneSameInstant(SHANGHAI)).equals(month)) {
                            failure = "MONTH_MISMATCH";
                        } else if (report.requestId() == null || report.evidencePack() == null
                                || !report.requestId().equals(report.evidencePack().requestId())) {
                            failure = "REQUEST_IDENTITY_MISMATCH";
                        } else {
                            List<com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry> reverified =
                                    new com.supplymind.agent.evidence.EvidenceRefVerifier(dataRoot)
                                            .verifyAll(report.evidencePack().evidenceRefs().stream()
                                                    .map(com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry::ref)
                                                    .toList());
                            List<String> unavailable = reverified.stream()
                                    .filter(entry -> entry.status()
                                            != com.supplymind.agent.evidence.EvidenceStatus.VERIFIED)
                                    .map(com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry::ref)
                                    .toList();
                            return new ReadResult(report, unavailable.isEmpty() ? null : "EVIDENCE_UNAVAILABLE");
                        }
                    }
                } catch (java.time.format.DateTimeParseException exception) {
                    failure = "MONTH_MISMATCH";
                }
            }
        }
        return new ReadResult(null, failure);
    }

    public record ReadResult(AgentReportV1 report, String failureCode) {
        public boolean ok() {
            return report != null && failureCode == null;
        }
    }
}
