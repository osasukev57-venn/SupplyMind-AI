package com.supplymind.agent.report;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.DataPaths;
import java.util.*;

/** V2 envelope; the existing report projection remains unchanged. */
public record ReportSnapshotV2(String schemaVersion, AgentReportV1 report, List<Binding> evidenceBindings) {
    public ReportSnapshotV2 {
        if (!"AGENT-REPORT-V2".equals(schemaVersion)) throw new IllegalArgumentException("report schema");
        Objects.requireNonNull(report); evidenceBindings=List.copyOf(evidenceBindings);
        Set<String> refs=new HashSet<>();
        for(var binding:evidenceBindings) if(!refs.add(binding.originalRef())) throw new IllegalArgumentException("duplicate snapshot identity");
    }
    public record Binding(String originalRef, String sha256, String manifestSha256, String snapshotRef, String manifestSnapshotRef) {
        public Binding {
            DataPaths.requireLegalDataRef(originalRef);
            if(!sha256.matches("[a-f0-9]{64}")||!manifestSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("snapshot hash");
            if ((snapshotRef==null)!=(manifestSnapshotRef==null)) throw new IllegalArgumentException("incomplete snapshot binding");
            if(snapshotRef!=null) {
                String basename=originalRef.substring(originalRef.lastIndexOf('/')+1);
                if(!snapshotRef.equals("report-evidence/"+sha256+"/"+basename)
                    || !manifestSnapshotRef.equals("report-evidence/"+manifestSha256+"/metadata.json")) throw new IllegalArgumentException("snapshot address/identity mismatch");
            } else if(!originalRef.startsWith("raw/")) throw new IllegalArgumentException("mutable evidence requires snapshot");
        }
    }
    public static AgentReportV1 decodeReport(byte[] bytes) {
        try {
            var tree=JsonV1Codec.mapper().readTree(bytes);
            return "AGENT-REPORT-V2".equals(tree.path("schemaVersion").asText())
                    ? JsonV1Codec.decodeFile(bytes,ReportSnapshotV2.class).report() : JsonV1Codec.decodeFile(bytes,AgentReportV1.class);
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("report unreadable"); }
    }
}
