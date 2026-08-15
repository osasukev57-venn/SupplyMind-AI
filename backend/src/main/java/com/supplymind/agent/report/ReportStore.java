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
        // M4: a formal claim must never carry empty evidenceRefs; without verifiable facts the
        // report carries no formal claim (orchestrator already avoids this).
        for (AgentReportV1.Claim claim : report.claims()) {
            if (claim.evidenceRefs() == null || claim.evidenceRefs().isEmpty()) {
                throw new IllegalArgumentException(
                        "AgentReport claim must carry non-empty evidenceRefs: " + claim.claimId());
            }
        }
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
                            // M4: re-verify the embedded evidence refs and compare every frozen
                            // identity field (sha256/status/reasonCode/lineage) with the current
                            // filesystem state; any drift fails closed.
                            com.supplymind.agent.evidence.EvidenceRefVerifier verifier =
                                    new com.supplymind.agent.evidence.EvidenceRefVerifier(dataRoot);
                            List<com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry> reverified =
                                    verifier.verifyAll(report.evidencePack().evidenceRefs().stream()
                                            .map(com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry::ref)
                                            .toList());
                            String bindingFailure = bindingMismatch(
                                    report.evidencePack().evidenceRefs(), reverified);
                            if (bindingFailure != null) {
                                return new ReadResult(null, bindingFailure);
                            }
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

    /**
     * M4: every frozen evidence field (ref, sha256, status, reasonCode and lineage) must equal
     * the current re-verification result. A status drift to non-VERIFIED is reported by the
     * caller as EVIDENCE_UNAVAILABLE; here we only detect drift between two VERIFIED states
     * (sha/lineage change) and status/reasonCode drift that is not an availability change.
     */
    private static String bindingMismatch(
            List<com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry> frozen,
            List<com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry> reverified
    ) {
        for (com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry frozenEntry : frozen) {
            com.supplymind.agent.evidence.EvidencePackV1.EvidenceRefEntry current = reverified.stream()
                    .filter(entry -> entry.ref().equals(frozenEntry.ref()))
                    .findFirst().orElse(null);
            if (current == null) {
                return "EVIDENCE_BINDING_MISMATCH";
            }
            if (frozenEntry.status() != current.status()) {
                // availability drift is handled by the caller; status/reason drift on a
                // VERIFIED frozen entry still must fail closed
                if (frozenEntry.status() == com.supplymind.agent.evidence.EvidenceStatus.VERIFIED
                        && current.status() != com.supplymind.agent.evidence.EvidenceStatus.VERIFIED) {
                    return null; // caller reports EVIDENCE_UNAVAILABLE
                }
                return "EVIDENCE_BINDING_MISMATCH";
            }
            if (frozenEntry.status() == com.supplymind.agent.evidence.EvidenceStatus.VERIFIED) {
                if (!Objects.equals(frozenEntry.sha256(), current.sha256())) {
                    return "EVIDENCE_BINDING_MISMATCH";
                }
                if (!Objects.equals(frozenEntry.validationVersion(), current.validationVersion())
                        || !Objects.equals(frozenEntry.calculationVersion(), current.calculationVersion())
                        || !Objects.equals(frozenEntry.calendarVersion(), current.calendarVersion())
                        || !Objects.equals(frozenEntry.configVersions(), current.configVersions())) {
                    return "EVIDENCE_LINEAGE_MISMATCH";
                }
            } else if (!Objects.equals(frozenEntry.reasonCode(), current.reasonCode())) {
                return "EVIDENCE_BINDING_MISMATCH";
            }
        }
        return null;
    }

    public record ReadResult(AgentReportV1 report, String failureCode) {
        public boolean ok() {
            return report != null && failureCode == null;
        }
    }
}
