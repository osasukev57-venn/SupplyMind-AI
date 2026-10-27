package com.supplymind.agent.narrative;

import com.supplymind.agent.evidence.EvidencePackV1;
import java.util.*;
import java.util.regex.Pattern;

/** Checks bounded, explicit attribution spans against cited facts, not unrelated paragraph residue. */
final class SourceAssertionCheck {
    private static final Pattern MARKER=Pattern.compile("(?i)(?:来自|源于|源自|取自|来源\\s*(?:(?:标注|标明|注明|标记|记载|记录|显示)\\s*)?(?:不是|并非|不为|为|是|于|自)?)(?:\\s*[:：])?|\\bsource\\s*[:：]");
    private static final Pattern NEGATION=Pattern.compile("(?:并非|不是|并不是|并不|不|未|非)\\s*$");
    private static final Pattern END=Pattern.compile("[，,。；;！!？?\\n]|(?<![A-Za-z0-9])\\.(?![A-Za-z0-9])");
    private SourceAssertionCheck() {}

    static String check(String text,List<EvidencePackV1.Fact> facts) {
        return inspect(text,facts).code();
    }

    record Inspection(String code,String marker,String asserted,String expected,boolean negated) {}

    static Inspection inspect(String text,List<EvidencePackV1.Fact> facts) {
        var claims=MARKER.matcher(text);
        int count=0;
        while(claims.find()) {
            if(++count>8)return new Inspection("SOURCE_CLAIM_LIMIT","","","",false);
            var sources=facts.stream().map(EvidencePackV1.Fact::actualSourceName).toList();
            if(sources.isEmpty()||sources.stream().anyMatch(s->s==null||s.isBlank()))return new Inspection("SOURCE_FACT_MISSING","","","",false);
            var unique=sources.stream().distinct().toList();
            // This span does not assign separate origins to separate factIds. Do not guess a binding.
            if(unique.size()!=1)return new Inspection("SOURCE_BINDING_AMBIGUOUS","","","",false);
            int end=text.length();var boundary=END.matcher(text);if(boundary.find(claims.end()))end=boundary.start();
            var next=MARKER.matcher(text);if(next.find(claims.end()))end=Math.min(end,next.start());
            String value=normalize(text.substring(claims.end(),end));
            if(value.isEmpty())return new Inspection("SOURCE_EXTRACTION_UNCERTAIN",claims.group(),"",normalize(unique.get(0)),false);
            String expected=normalize(unique.get(0));
            String marker=claims.group().replaceAll("[\\s:：]","");
            boolean negated=marker.endsWith("不是")||marker.endsWith("并非")||marker.endsWith("不为")
                ||NEGATION.matcher(text.substring(Math.max(0,claims.start()-8),claims.start())).find();
            boolean matches=value.equals(expected)||simulationDescription(value,expected,text);
            if(!negated&&matches&&Pattern.compile("(?:不是|并非|不属于)\\s*(?:模拟(?:演示)?数据|非真实市场数据)").matcher(text).find())
                return new Inspection("SOURCE_CONFLICT",marker,value,expected,false);
            if(negated) {
                if(matches||value.contains(expected))return new Inspection("SOURCE_CONFLICT",marker,value,expected,true);
                // A denied external origin does not assert it as an actual origin.
            }else if(!matches)return new Inspection("SOURCE_CONFLICT",marker,value,expected,false);
        }
        return new Inspection(null,"","","",false);
    }

    private static boolean simulationDescription(String value,String source,String text) {
        // A descriptive projection of an explicitly labelled simulation origin, never an alias
        // inferred from itemId, DEMO mode, a model statement or a commercial name containing 模拟.
        if(!source.endsWith("(非真实市场数据)"))return false;
        String identity=source.substring(0,source.length()-"(非真实市场数据)".length());
        if(!identity.matches("模拟(?:演示)?(?:数据)?"))return false;
        String description=value.replaceFirst("\\(非真实市场数据\\)$","")
            .replaceFirst("^已(?:发布|导入)的?","");
        if(!description.equals(identity)&&!description.matches("模拟(?:演示)?数据"))return false;
        // A source fact explicitly marked non-market simulation supports the literal description 模拟数据 itself.
        return description.matches("模拟(?:演示)?数据")||value.endsWith("(非真实市场数据)")
            ||Pattern.compile("(?:^|[，,。；;])\\s*(?:这是|这些是|数据是)?(?:非真实市场数据|不代表真实市场(?:数据|行情|风险))").matcher(text).find();
    }

    private static String normalize(String value) {
        // Typography equivalence only; meaningful source letters/words are retained.
        return value.strip().replace('（','(').replace('）',')')
            .replaceAll("[\\s\\\"'“”‘’「」『』]","");
    }
}
