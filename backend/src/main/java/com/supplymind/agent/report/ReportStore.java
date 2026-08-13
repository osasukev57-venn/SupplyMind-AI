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
}
