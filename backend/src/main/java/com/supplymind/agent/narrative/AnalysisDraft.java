package com.supplymind.agent.narrative;
import java.util.List;

/** Sole production cloud response: bounded prose sections with references, never placeholders. */
public record AnalysisDraft(List<Section> sections,List<Recommendation> recommendations) {
    public enum Kind { OVERVIEW,TREND,METRICS,WARNING,QUALITY }
    public record Section(Kind kind,String text,List<String> factIds) {}
    public record Recommendation(String text,List<String> factIds) {}
}
