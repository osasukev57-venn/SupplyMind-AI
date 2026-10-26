package com.supplymind.agent.narrative;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Java-owned output scope; available tool facts are not a request to discuss every role. */
public record NarrativeScope(Set<String> kinds, int maxRecommendations) {
    public NarrativeScope { kinds = Set.copyOf(kinds); }
    public static NarrativeScope unrestricted() {
        return new NarrativeScope(Set.of("OVERVIEW", "TREND", "METRICS", "WARNING", "QUALITY"), 4);
    }
    public static NarrativeScope requested(String question, String theme) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean latest = has(q, "最新", "日期", "单位", "来源", "latest");
        boolean trend = has(q, "趋势", "走势", "涨跌", "变化", "trend");
        boolean metrics = has(q, "月均", "均值", "平均", "统计", "metrics", "average");
        boolean warning = has(q, "预警", "风险", "确认", "warning");
        boolean quality = has(q, "质量", "完整", "缺失", "校验状态", "验证状态", "quality");
        boolean advice = has(q, "建议", "如何应对", "怎么办", "recommend", "advice");
        var kinds = new LinkedHashSet<String>();
        if (latest) kinds.add("OVERVIEW");
        if (trend) kinds.add("TREND");
        if (metrics) kinds.add("METRICS");
        if (warning) kinds.add("WARNING");
        if (quality) kinds.add("QUALITY");
        // A theme is an upper bound even if the question contains an unrelated keyword.
        Set<String> themeKinds = switch (theme == null ? "" : theme) {
            case "HISTORY" -> Set.of("OVERVIEW", "TREND", "QUALITY");
            case "METRICS" -> Set.of("METRICS", "QUALITY");
            case "WARNING" -> Set.of("WARNING", "QUALITY");
            default -> unrestricted().kinds();
        };
        kinds.retainAll(themeKinds);
        if (kinds.isEmpty()) {
            if ("HISTORY".equals(theme)) kinds.addAll(List.of("OVERVIEW", "TREND"));
            else if ("METRICS".equals(theme)) kinds.add("METRICS");
            else if ("WARNING".equals(theme)) kinds.add("WARNING");
            else kinds.addAll(unrestricted().kinds()); // genuinely open-ended analysis
        }
        return new NarrativeScope(kinds, advice ? 1 : 0);
    }
    public boolean recommendations() { return maxRecommendations > 0; }
    public boolean allowsStatement(String statement) {
        return switch (statement) {
            case "latest published value" -> kinds.contains("OVERVIEW") || kinds.contains("TREND");
            case "published daily value", "Java-computed trend direction", "Java-computed start-to-end change" -> kinds.contains("TREND");
            case "persisted period average" -> kinds.contains("METRICS");
            default -> statement.contains("warning") ? kinds.contains("WARNING") : kinds.contains("QUALITY");
        };
    }
    public boolean allowsFactType(String type) {
        if(type.equals("history.latest"))return kinds.contains("OVERVIEW")||kinds.contains("TREND");
        if(type.equals("history.query")||type.startsWith("history.trend."))return kinds.contains("TREND");
        if(type.equals("period.metrics.avg"))return kinds.contains("METRICS");
        if(type.startsWith("warning."))return kinds.contains("WARNING");
        return type.startsWith("quality.")&&kinds.contains("QUALITY");
    }
    private static boolean has(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
