package com.supplymind.agent.narrative;

import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.agent.llm.LLMService;
import java.util.*;
import java.util.regex.Pattern;

/** Business validation of underlying samples, never the status of a derived metric. */
public record SampleValidationSummary(String itemId, Map<String, Long> counts) {
    private static final Pattern STATUS = Pattern.compile("(?<![A-Z_])(VERIFIED_WITH_NOTICE|VERIFIED)(?![A-Z_])",Pattern.CASE_INSENSITIVE);
    public SampleValidationSummary { counts = Collections.unmodifiableMap(new TreeMap<>(counts)); }
    public static List<SampleValidationSummary> fromCloud(List<LLMService.LlmFact> facts) {
        var result = new TreeMap<String, Map<String, Long>>();
        facts.stream().filter(f -> f.statement().equals("published daily value")).forEach(f ->
            result.computeIfAbsent(f.itemId(), k -> new TreeMap<>()).merge(status(f.validationStatus()), 1L, Long::sum));
        return result.entrySet().stream().map(e -> new SampleValidationSummary(e.getKey(), e.getValue())).toList();
    }
    public static SampleValidationSummary forSection(List<EvidencePackV1.Fact> selected, EvidencePackV1 pack) {
        Set<String> items = new HashSet<>(); selected.forEach(f -> items.add(f.itemId()));
        Map<String, Long> counts = new TreeMap<>();
        pack.facts().stream().filter(f -> f.factType().equals("history.query") && items.contains(f.itemId()))
            .forEach(f -> counts.merge(status(f.validationStatus()), 1L, Long::sum));
        return new SampleValidationSummary(String.join(",", new TreeSet<>(items)), counts);
    }
    private static String status(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }

    /** Bounded claims check. It accepts varied prose, not an exact approved sentence. */
    public String check(String kind, String text, List<EvidencePackV1.Fact> selected) {
        if (kind.equals("METRICS")) return null; // independently bound aggregate groups
        boolean quality = kind.equals("QUALITY");
        for (String clause : text.split("[，。；;]")) {
            if (Pattern.compile("并非|不是|不代表|不等于|不表示|不意味着|不能(?:说|概括|视为|据此|统一|认定)").matcher(clause).find()) continue;
            var mentioned = new HashSet<String>(); var matcher = STATUS.matcher(clause);
            while (matcher.find()) mentioned.add(matcher.group().toUpperCase(Locale.ROOT));
            if (!mentioned.isEmpty() && !counts.keySet().containsAll(mentioned)) return "UNSUPPORTED_SAMPLE_VALIDATION_STATUS";
            boolean universal = Pattern.compile("全部|所有|每条|全都|均经|均为|均已|均通过|都通过|都已|统一").matcher(clause).find();
            boolean claimsValidated = mentioned.contains("VERIFIED")
                || Pattern.compile("(?:全部|所有|均|都).{0,12}(?:通过校验|通过验证|已验证|已校验|完全验证|无附注|无提示)").matcher(clause).find();
            boolean mixedOrUnknown = counts.isEmpty() || counts.keySet().stream().anyMatch(s -> !s.equals("VERIFIED"));
            if (universal && mentioned.size() == 1 && (counts.size() != 1 || !counts.containsKey(mentioned.iterator().next()))) return "SAMPLE_VALIDATION_OVERGENERALIZATION";
            if (universal && claimsValidated && mixedOrUnknown && mentioned.size() <= 1) return "SAMPLE_VALIDATION_OVERGENERALIZATION";
            if (quality && !mentioned.isEmpty() && Pattern.compile("(?:指标|完整性).{0,12}(?:状态|校验|验证).{0,12}VERIFIED",Pattern.CASE_INSENSITIVE).matcher(clause).find()) return "DERIVED_METRIC_STATUS_CONFUSION";
            if (quality && !mentioned.isEmpty() && Pattern.compile("\\d+\\s*(?:条|个|笔)|(?:VERIFIED_WITH_NOTICE|VERIFIED).{0,8}\\d+\\s*(?:条|个|笔)").matcher(clause).find()
                && !countClause(clause)) return "WRONG_SAMPLE_VALIDATION_COUNT";
            if (quality && mentioned.size() == 1 && counts.size() > 1
                && !Pattern.compile("\\d+\\s*条|组|其中|部分|存在|包含|含有").matcher(clause).find()) return "SAMPLE_VALIDATION_OVERGENERALIZATION";
        }
        return null;
    }

    /** A numeric status count must bind to that exact status, not any metric's value. */
    public boolean countClause(String clause) {
        var statuses = STATUS.matcher(clause);
        Set<String> labels = new HashSet<>(); while (statuses.find()) labels.add(statuses.group().toUpperCase(Locale.ROOT));
        if (labels.isEmpty()) return false;
        String rest = clause;
        for (String label : labels) {
            if (!counts.containsKey(label)) return false;
            var before = Pattern.compile("(\\d+)\\s*条\\s*" + label + "(?![A-Z_])",Pattern.CASE_INSENSITIVE).matcher(rest);
            var after = Pattern.compile("(?<![A-Z_])" + label + "(?![A-Z_])\\s*(?:组|样本|记录)?\\s*(?:为|有|共|：|:|=)?\\s*(\\d+)\\s*条",Pattern.CASE_INSENSITIVE).matcher(rest);
            if (before.find()) { if (new java.math.BigDecimal(before.group(1)).compareTo(java.math.BigDecimal.valueOf(counts.get(label))) != 0) return false; rest = before.replaceFirst(""); }
            else if (after.find()) { if (new java.math.BigDecimal(after.group(1)).compareTo(java.math.BigDecimal.valueOf(counts.get(label))) != 0) return false; rest = after.replaceFirst(""); }
            else return false;
        }
        // No other numbers/claims can hitchhike on the status-count exemption.
        return !Pattern.compile("\\d|缺失|完整|最新|价格|均值|阈值").matcher(rest).find();
    }
}
