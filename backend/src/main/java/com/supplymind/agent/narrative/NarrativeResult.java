package com.supplymind.agent.narrative;
import java.util.List;

/** Only accepted prose is persisted. Rejected model text is never included in diagnostics. */
public record NarrativeResult(String schemaVersion,String adoption,String cloudStatus,
        List<Section> sections,List<Section> recommendations,List<Diagnostic> diagnostics,int attempts,String checkScope) {
    public NarrativeResult {
        if(!"ANALYSIS-NARRATIVE-V1".equals(schemaVersion)||!List.of("FULL","PARTIAL","NONE").contains(adoption))throw new IllegalArgumentException("narrative version/status");
        sections=List.copyOf(sections);recommendations=List.copyOf(recommendations);diagnostics=List.copyOf(diagnostics);
        if(!adoption.equals("NONE")&&(!cloudStatus.equals("SUCCESS")||sections.isEmpty()))throw new IllegalArgumentException("adopted narrative requires successful nonempty sections");
        if(attempts<0||attempts>1)throw new IllegalArgumentException("cloud attempt bound");
        if("NONE".equals(adoption)&&(!sections.isEmpty()||!recommendations.isEmpty()))throw new IllegalArgumentException("NONE cannot contain adopted prose");
    }
    public record Section(String sectionId,String kind,String text,List<String> factIds,List<String> evidenceRefs) {
        public Section { factIds=List.copyOf(factIds);evidenceRefs=List.copyOf(evidenceRefs); }
    }
    public record Diagnostic(String sectionId,String code) {}
    public static NarrativeResult none(String status,int attempts) {
        return new NarrativeResult("ANALYSIS-NARRATIVE-V1","NONE",status,List.of(),List.of(),List.of(new Diagnostic("cloud",status)),attempts,
                "结构、引用及关键数值/角色/受控语义检查；不证明任意自然语言绝对正确");
    }
}
