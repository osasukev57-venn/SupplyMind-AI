package com.supplymind.agent.report;
import com.supplymind.agent.narrative.NarrativeResult;
import java.util.List;

/** Explicit new dispatch: V2 evidence rows and independently adopted narrative. */
public record ReportSnapshotV3(String schemaVersion,AgentReportV1 report,
        List<ReportSnapshotV2.Binding> evidenceBindings,NarrativeResult narrative) {
    public ReportSnapshotV3 {
        if(!"AGENT-REPORT-V3".equals(schemaVersion)||!"AGENT-EVIDENCE-SCHEMA-V2".equals(report.evidencePack().schemaVersion()))throw new IllegalArgumentException("V3 report evidence version");
        evidenceBindings=List.copyOf(evidenceBindings);java.util.Objects.requireNonNull(narrative);
        String expected= switch(narrative.adoption()){case "FULL"->"LLM";case "PARTIAL"->"LLM_PARTIAL";default->"JAVA_TEMPLATE";};
        boolean expectedDegraded=!narrative.adoption().equals("FULL");
        if(!expected.equals(report.generatedBy())||report.degraded()!=expectedDegraded)throw new IllegalArgumentException("report/narrative adoption mismatch");
        new ReportSnapshotV2("AGENT-REPORT-V2",report,evidenceBindings); // exact originalRef uniqueness/address rules
    }
    ReportSnapshotV2 snapshot(){return new ReportSnapshotV2("AGENT-REPORT-V2",report,evidenceBindings);}
}
