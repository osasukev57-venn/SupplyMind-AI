package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded role checks, independent adoption. It does not claim general semantic proof. */
public final class NarrativeValidator {
    private static final Pattern DATE=Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NUMBER=Pattern.compile("-?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:%|％)?");
    private final AgentResponseVerifier secrets;
    public NarrativeValidator(AgentResponseVerifier secrets){this.secrets=secrets;}
    public NarrativeResult validate(String raw,EvidencePackV1 pack) {
        try { secrets.requireNoSecret(raw); }catch(RuntimeException ex){return NarrativeResult.none("SECRET_INJECTION",1);}
        if(raw==null||raw.length()>16000)return NarrativeResult.none("MALFORMED_STRUCTURE",1);
        if(Pattern.compile("(?iu)忽略.{0,8}指令|system\\s*prompt|<script|(?:raw|processed|config|staging)/|[A-Z]:[\\\\/]|\\{\\{").matcher(raw).find())return NarrativeResult.none("UNSAFE_CONTENT",1);
        AnalysisDraft draft;
        try {
            var strict=JsonV1Codec.mapper().copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            var tree=strict.readTree(raw);
            if(!tree.isObject()||tree.size()!=2||!tree.has("sections")||!tree.has("recommendations"))throw new IllegalArgumentException();
            if(!tree.path("sections").isArray()||tree.path("sections").isEmpty()||tree.path("sections").size()>10||!tree.path("recommendations").isArray()||tree.path("recommendations").size()>4)throw new IllegalArgumentException();
            var sections=new ArrayList<AnalysisDraft.Section>();var recs=new ArrayList<AnalysisDraft.Recommendation>();
            for(var node:tree.path("sections")) {try{if(!node.path("text").isTextual()||!node.path("factIds").isArray())throw new IllegalArgumentException();sections.add(strict.treeToValue(node,AnalysisDraft.Section.class));}catch(Exception invalidSection){sections.add(null);}}
            for(var node:tree.path("recommendations")) {try{if(!node.path("text").isTextual()||!node.path("factIds").isArray())throw new IllegalArgumentException();recs.add(strict.treeToValue(node,AnalysisDraft.Recommendation.class));}catch(Exception invalidRecommendation){recs.add(null);}}
            draft=new AnalysisDraft(sections,recs);
        }catch(Exception ex){return NarrativeResult.none("MALFORMED_STRUCTURE",1);}
        Map<String,EvidencePackV1.Fact> facts=new HashMap<>();int historical=0;
        for(var f:pack.facts()){if(f.factType().equals("history.query")&&historical++>=2)continue;facts.put(f.factId(),f);}
        var accepted=new ArrayList<NarrativeResult.Section>();var recs=new ArrayList<NarrativeResult.Section>();var errors=new ArrayList<NarrativeResult.Diagnostic>();
        int i=0;
        for(var section:draft.sections()) {
            String id="section-"+(++i);
            String code=section==null?"MALFORMED_SECTION":check(section.kind()==null?null:section.kind().name(),section.text(),section.factIds(),facts,false);
            if(code==null)accepted.add(adopt(id,section.kind().name(),section.text(),section.factIds(),facts));else errors.add(new NarrativeResult.Diagnostic(id,code));
        }
        var acceptedIds=new HashSet<String>();accepted.forEach(s->acceptedIds.addAll(s.factIds()));
        i=0;
        for(var r:draft.recommendations()) {
            String id="recommendation-"+(++i);
            String code=r==null?"MALFORMED_SECTION":check("RECOMMENDATION",r.text(),r.factIds(),facts,true);
            if(code==null&&!acceptedIds.containsAll(r.factIds()))code="DEPENDENCY_NOT_ADOPTED";
            if(code==null)recs.add(adopt(id,"RECOMMENDATION",r.text(),r.factIds(),facts));else errors.add(new NarrativeResult.Diagnostic(id,code));
        }
        if(accepted.isEmpty())return new NarrativeResult("ANALYSIS-NARRATIVE-V1","NONE","CONTENT_REJECTED",List.of(),List.of(),errors,1,"关键主张受控检查，不等于任意语义证明");
        return new NarrativeResult("ANALYSIS-NARRATIVE-V1",errors.isEmpty()?"FULL":"PARTIAL","SUCCESS",accepted,recs,errors,1,"结构、引用、分组、日期、单位、来源、关键数值及受控语义；不证明任意自然语言绝对正确");
    }
    private NarrativeResult.Section adopt(String id,String kind,String text,List<String> ids,Map<String,EvidencePackV1.Fact> facts) {
        return new NarrativeResult.Section(id,kind,text,ids,ids.stream().flatMap(f->facts.get(f).evidenceRefs().stream()).distinct().toList());
    }
    private String check(String kind,String text,List<String> ids,Map<String,EvidencePackV1.Fact> all,boolean recommendation) {
        if(kind==null||text==null||text.isBlank()||text.length()>1000||ids==null||ids.isEmpty()||ids.size()>8||new HashSet<>(ids).size()!=ids.size())return "MALFORMED_SECTION";
        if(ids.stream().anyMatch(id->!all.containsKey(id)))return "UNKNOWN_FACT_ID";
        if(Pattern.compile("[零〇一二两三四五六七八九十百千万亿]+\\s*(?:元|美元|吨|%|％|个百分点)").matcher(text).find())return "UNSUPPORTED_NUMBER_FORMAT";
        if(Pattern.compile("一定会|必然会|保证.{0,8}(上涨|下跌|收益)|未来.{0,6}(上涨|下跌)|将会.{0,6}(上涨|下跌)").matcher(text).find())return "UNSUPPORTED_PREDICTION";
        var selected=ids.stream().map(all::get).toList();
        if(selected.stream().anyMatch(f->!allowed(kind,f.factType())))return "WRONG_FACT_ROLE";
        if(!recommendation&&!"METRICS".equals(kind)&&any(text,"月均","均值","平均值","月度平均"))return "WRONG_FACT_ROLE";
        if("METRICS".equals(kind)&&Pattern.compile("最新(?:值|价格|价)|当前(?:值|价格)|基准(?:值|价格)|现价|阈值").matcher(text).find())return "WRONG_FACT_ROLE";
        for(String clause:text.split("[，。；;、]")) {
            if(any(clause,"最新","当前")&&!any(clause,"至","→")) {
                var actual=selected.stream().filter(f->f.factType().equals("history.latest")||f.factType().equals("warning.explain")).map(EvidencePackV1.Fact::businessDate).toList();
                var dated=DATE.matcher(clause);while(dated.find())if(!actual.contains(dated.group()))return "WRONG_DATE_ROLE";
            }
        }
        if(Pattern.compile("上述|前文|上一段|如上|根据以上").matcher(text).find())return "UNVERIFIABLE_DEPENDENCY";
        String nonNegated=text.replaceAll("(?:并不意味着|不代表|不表示|不意味着|不等于|并非|不能证明)风险(?:已经|已)?(?:解除|消失|降低|下降|受控|可控|缓解)","");
        if(Pattern.compile("风险.{0,8}(消失|解除|为零|不存在|降低|下降|受控|可控|缓解|得到控制)|(?:消除|解除).{0,8}风险|没有风险|无风险|未触发预警|没有预警|无需处理|可以放心|建议买入|建议卖出|立即调价|目标价").matcher(nonNegated).find())return "UNSUPPORTED_RISK_OR_ACTION";
        if(Pattern.compile("供需|厂家|央行宣布|政策发布|据.{1,20}(报道|消息)|市场需求|供给短缺|产能").matcher(text).find())return "UNSUPPORTED_EXTERNAL_EVENT";
        if(any(text,"上升","上涨","走高")&&!directionSupported(selected,kind,"UPWARD"))return "UNSUPPORTED_TREND";
        if(any(text,"下降","下跌","走低")&&!directionSupported(selected,kind,"DOWNWARD"))return "UNSUPPORTED_TREND";
        if(any(text,"持平","平稳")&&!directionSupported(selected,kind,"FLAT"))return "UNSUPPORTED_TREND";
        var ruleTokens=Pattern.compile("custom-r[0-9]+").matcher(text);
        while(ruleTokens.find()){String rule=ruleTokens.group();if(selected.stream().noneMatch(f->f.factType().equals("warning.ruleVersion")&&f.value().endsWith(rule)))return "WRONG_RULE_VERSION";}
        if(any(text,"HIGH","高风险","高等级")&&!has(selected,"warning.riskLevel","HIGH"))return "WRONG_RISK";
        if(any(text,"LOW","低风险")&&!has(selected,"warning.riskLevel","LOW"))return "WRONG_RISK";
        if(any(text,"MEDIUM","中风险")&&!has(selected,"warning.riskLevel","MEDIUM"))return "WRONG_RISK";
        if(any(text,"已确认","已知悉","已处理")&&!has(selected,"warning.acknowledged","ACKNOWLEDGED"))return "WRONG_ACKNOWLEDGEMENT";
        if(any(text,"未确认","待处理")&&!has(selected,"warning.acknowledged","PENDING"))return "WRONG_ACKNOWLEDGEMENT";
        if(recommendation) {
            if(NUMBER.matcher(text).find()||!any(text,"建议","可","可以","观察","关注","核对"))return "UNSUPPORTED_RECOMMENDATION";
        }
        if(any(text,"来源","来自","source:","source：","Source:")) {
            var source=selected.stream().map(EvidencePackV1.Fact::actualSourceName).filter(Objects::nonNull).distinct().toList();
            if(source.size()!=1||!text.contains(source.get(0)))return "UNSUPPORTED_SOURCE";
            String rest=text.replace(source.get(0),"");
            if(Pattern.compile("(?:来自|来源[为是：:]?|source[:：]\\s*)[^，。；;：:]*[\\p{IsHan}A-Za-z]").matcher(rest).find())return "UNSUPPORTED_SOURCE";
        }
        var dates=DATE.matcher(text);Set<String> allowedDates=new HashSet<>();
        selected.forEach(f->{allowedDates.add(f.businessDate());allowedDates.add(f.periodStart());allowedDates.add(f.periodEnd());});
        while(dates.find())if(!allowedDates.contains(dates.group()))return "WRONG_DATE";
        String withoutDates=DATE.matcher(text).replaceAll("");
        for(var f:selected)if(f.unit()!=null&&f.unit().chars().anyMatch(Character::isDigit))withoutDates=withoutDates.replace(f.unit(),"");
        for(var f:selected) {
            withoutDates=withoutDates.replace(f.itemId(),"");
            for(String part:f.itemId().split("\\."))if(part.matches("[A-Za-z]+[0-9]+[A-Za-z0-9]*"))withoutDates=withoutDates.replace(part,"");
            if(f.factType().equals("warning.ruleVersion")&&f.value().contains("custom-r"))withoutDates=withoutDates.replace(f.value().substring(f.value().indexOf("custom-r")),"");
        }
        // Rule/id strings may contain digits, but are accepted only when copied from the selected role.
        for(var f:selected)if(f.factType().equals("warning.ruleVersion")||f.factType().equals("warning.id")||f.factType().equals("warning.acknowledgedAt"))withoutDates=withoutDates.replace(f.value(),"");
        var aggregate=selected.stream().filter(f->f.factType().equals("period.metrics.avg")).toList();
        if("METRICS".equals(kind)) {
            if(aggregate.size()!=1)return "AMBIGUOUS_AGGREGATE_GROUP";
            if("false".equals(aggregate.get(0).qualityStatus())&&any(text,"全月完整","完整月份","完整月度","全部交易日"))return "UNSUPPORTED_PERIOD_COVERAGE";
            var f=aggregate.get(0);boolean notice=any(text,"VERIFIED_WITH_NOTICE","附注","有提示","有通知");
            boolean explicitVerified=Pattern.compile("(?<![A-Z_])VERIFIED(?![A-Z_])").matcher(text).find();
            boolean verified=explicitVerified||!notice&&any(text,"已验证组","完全验证","通过组");
            if(notice&&explicitVerified)return "AMBIGUOUS_AGGREGATE_GROUP";
            if(notice&&!"VERIFIED_WITH_NOTICE".equals(f.validationStatus())||verified&&!notice&&!"VERIFIED".equals(f.validationStatus()))return "WRONG_AGGREGATE_GROUP";
            if(NUMBER.matcher(withoutDates).find()&&!notice&&!verified)return "UNLABELLED_AGGREGATE_GROUP";
        }
        String normalized=withoutDates.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))","");
        for(String clause:normalized.split("[，。；;、]")) {
            var nums=NUMBER.matcher(clause);var roles=new ArrayList<EvidencePackV1.Fact>(selected);
            if("TREND".equals(kind)||"OVERVIEW".equals(kind)) {
                if(any(clause,"从","→")&&any(clause,"至","→")) {
                    var pair=pairOf(clause);if(!pair.find())return "AMBIGUOUS_TREND_PAIR";
                    if(!hasDecimal(selected,"history.query",pair.group(1))||!hasDecimal(selected,"history.latest",pair.group(2)))return "WRONG_TREND_PAIR";
                    roles.removeIf(f->!f.factType().equals("history.query")&&!f.factType().equals("history.latest"));
                }
                else if(any(clause,"最新","当前","上涨至","下跌至","升至","降至","涨至","跌至"))roles.removeIf(f->!f.factType().equals("history.latest"));
                else if(any(clause,"变化","增加","减少"))roles.removeIf(f->!f.factType().equals("history.trend.change"));
                else if(any(clause,"历史值"))roles.removeIf(f->!f.factType().equals("history.query"));
                else if(nums.find()) {
                    var numeric=roles.stream().filter(f->{try{new BigDecimal(f.value());return true;}catch(NumberFormatException nonnumeric){return false;}}).toList();
                    if(numeric.size()!=1||any(clause,"基准值","阈值","最高值","最低值","最高价","最低价","波动率","中位数","标准差"))return "AMBIGUOUS_VALUE_ROLE";
                    roles=new ArrayList<>(numeric);
                }
                nums=NUMBER.matcher(clause);
            }
            if("WARNING".equals(kind)) {
                if(any(clause,"基准")&&!any(clause,"当前","最新","至","→"))roles.removeIf(f->!f.factType().equals("warning.baselineValue"));
                else if(any(clause,"当前","最新")&&!any(clause,"基准"))roles.removeIf(f->!f.factType().equals("warning.explain"));
                else if(any(clause,"阈值"))roles.removeIf(f->!f.factType().equals("warning.thresholdPercent"));
                else if(any(clause,"从","→")&&any(clause,"至","→")) {
                    var pair=pairOf(clause);
                    if(!pair.find())return "AMBIGUOUS_WARNING_ROLE";
                    if(!hasDecimal(selected,"warning.baselineValue",pair.group(1))||!hasDecimal(selected,"warning.explain",pair.group(2)))return "WRONG_WARNING_PAIR";
                }else if(nums.find())return "AMBIGUOUS_WARNING_ROLE";
                nums=NUMBER.matcher(clause);
            }
            var boundRoles=List.copyOf(roles);
            while(nums.find()) {
                String token=nums.group();
                if(boundRoles.stream().noneMatch(f->numericEquals(f.value(),token)))return "UNSUPPORTED_NUMBER";
                if(token.endsWith("%")||token.endsWith("％")) {
                    if(boundRoles.stream().noneMatch(f->f.value().endsWith("%")&&numericEquals(f.value(),token)))return "WRONG_UNIT";
                }
            }
        }
        Set<String> units=new HashSet<>();selected.forEach(f->{if(f.unit()!=null)units.add(f.unit());});
        for(String compound:List.of("万元/吨","元/千克","元/公斤","美元/吨","元/吨","CNY/1 USD","CNY/1 EUR"))if(text.contains(compound)&&!units.contains(compound))return "WRONG_UNIT";
        for(var currency:Map.of("欧元","EUR","英镑","GBP","美元","USD").entrySet())if(Pattern.compile("(?:\\d\\s*|单位(?:为|是|：|:)\\s*)"+currency.getKey()).matcher(text).find()&&selected.stream().noneMatch(f->currency.getValue().equals(f.currency())))return "WRONG_UNIT";
        String unitText=text;for(String known:units)unitText=unitText.replace(known,"");
        for(var f:selected)unitText=unitText.replace(f.itemId(),"");
        for(String u:List.of("元/吨","美元/吨","CNY/1 USD","CNY/1 EUR","USD","EUR","GBP"))if(unitText.contains(u)&&!units.contains(u)&&selected.stream().noneMatch(f->u.equals(f.currency())))return "WRONG_UNIT";
        return null;
    }
    private static boolean directionSupported(List<EvidencePackV1.Fact> facts,String kind,String expected) {
        if(has(facts,"history.trend.direction",expected))return true;
        if(!"WARNING".equals(kind))return false;
        var current=facts.stream().filter(f->f.factType().equals("warning.explain")).findFirst();
        var baseline=facts.stream().filter(f->f.factType().equals("warning.baselineValue")).findFirst();
        if(current.isEmpty()||baseline.isEmpty())return false;
        try {int comparison=new BigDecimal(current.get().value()).compareTo(new BigDecimal(baseline.get().value()));return expected.equals(comparison>0?"UPWARD":comparison<0?"DOWNWARD":"FLAT");}catch(NumberFormatException invalid){return false;}
    }
    private static java.util.regex.Matcher pairOf(String clause){return Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*(?:元/吨)?\\s*(?:上升|下降|上涨|下跌|涨|升|降|跌)?(?:至|→)\\s*(-?\\d+(?:\\.\\d+)?)").matcher(clause);}
    private static boolean allowed(String kind,String type){return switch(kind){case "METRICS"->type.equals("period.metrics.avg");case "WARNING"->type.startsWith("warning.");case "QUALITY"->type.startsWith("quality.");case "TREND"->type.startsWith("history.trend.")||type.equals("history.latest")||type.equals("history.query");case "OVERVIEW"->type.equals("history.query")||type.equals("history.latest")||type.startsWith("history.trend.");case "RECOMMENDATION"->true;default->false;};}
    private static boolean any(String s,String... values){for(String v:values)if(s.contains(v))return true;return false;}
    private static boolean has(List<EvidencePackV1.Fact> fs,String type,String v){return fs.stream().anyMatch(f->f.factType().equals(type)&&f.value().equals(v));}
    private static boolean hasDecimal(List<EvidencePackV1.Fact> fs,String type,String v){return fs.stream().anyMatch(f->f.factType().equals(type)&&numericEquals(f.value(),v));}
    private static boolean numericEquals(String a,String b){try{boolean ap=a.endsWith("%")||a.endsWith("％"),bp=b.endsWith("%")||b.endsWith("％");return ap==bp&&new BigDecimal(a.replace(",","").replace("%","").replace("％","")).compareTo(new BigDecimal(b.replace(",","").replace("%","").replace("％","")))==0;}catch(Exception ex){return false;}}
}
