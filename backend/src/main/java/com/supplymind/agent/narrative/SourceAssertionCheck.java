package com.supplymind.agent.narrative;

import com.supplymind.agent.evidence.EvidencePackV1;
import java.util.*;
import java.util.regex.Pattern;

/** Checks bounded, explicit attribution spans against cited facts, not unrelated paragraph residue. */
final class SourceAssertionCheck {
    private static final Pattern MARKER=Pattern.compile("(?i)(?:来自|源于|源自|取自|来源\\s*(?:不是|并非|不为|为|是|于|自)?)(?:\\s*[:：])?|\\bsource\\s*[:：]");
    private static final Pattern NEGATION=Pattern.compile("(?:并非|不是|并不是|并不|不|未|非)\\s*$");
    private static final Pattern END=Pattern.compile("[，,。；;！!？?\\n]|(?<![A-Za-z0-9])\\.(?![A-Za-z0-9])");
    private SourceAssertionCheck() {}

    static String check(String text,List<EvidencePackV1.Fact> facts) {
        var claims=MARKER.matcher(text);
        int count=0;
        while(claims.find()) {
            if(++count>8)return "SOURCE_CLAIM_LIMIT";
            var sources=facts.stream().map(EvidencePackV1.Fact::actualSourceName).toList();
            if(sources.isEmpty()||sources.stream().anyMatch(s->s==null||s.isBlank()))return "SOURCE_FACT_MISSING";
            var unique=sources.stream().distinct().toList();
            // This span does not assign separate origins to separate factIds. Do not guess a binding.
            if(unique.size()!=1)return "SOURCE_BINDING_AMBIGUOUS";
            int end=text.length();var boundary=END.matcher(text);if(boundary.find(claims.end()))end=boundary.start();
            var next=MARKER.matcher(text);if(next.find(claims.end()))end=Math.min(end,next.start());
            String value=normalize(text.substring(claims.end(),end));
            if(value.isEmpty())return "SOURCE_EXTRACTION_UNCERTAIN";
            String expected=normalize(unique.get(0));
            String marker=claims.group().replaceAll("[\\s:：]","");
            boolean negated=marker.endsWith("不是")||marker.endsWith("并非")||marker.endsWith("不为")
                ||NEGATION.matcher(text.substring(Math.max(0,claims.start()-8),claims.start())).find();
            boolean matches=value.equals(expected)||simulationDescription(value,expected,text);
            if(negated) {
                if(matches||value.contains(expected))return "SOURCE_CONFLICT";
                // A denied external origin does not assert it as an actual origin.
            }else if(!matches)return "SOURCE_CONFLICT";
        }
        return null;
    }

    private static boolean simulationDescription(String value,String source,String text) {
        // A descriptive projection of an explicitly labelled simulation origin, never an alias
        // inferred from itemId, DEMO mode, a model statement or a commercial name containing 模拟.
        if(!source.endsWith("(非真实市场数据)"))return false;
        String identity=source.substring(0,source.length()-"(非真实市场数据)".length());
        if(!identity.matches("模拟(?:演示)?(?:数据)?"))return false;
        String description=value.replaceFirst("\\(非真实市场数据\\)$","");
        if(!description.equals(identity)&&!description.matches("模拟(?:演示)?数据"))return false;
        return value.endsWith("(非真实市场数据)")
            ||Pattern.compile("(?:^|[，,。；;])\\s*(?:这是|这些是|数据是)?(?:非真实市场数据|不代表真实市场(?:数据|行情|风险))").matcher(text).find();
    }

    private static String normalize(String value) {
        // Typography equivalence only; meaningful source letters/words are retained.
        return value.strip().replace('（','(').replace('）',')')
            .replaceAll("[\\s\\\"'“”‘’「」『』]","");
    }
}
