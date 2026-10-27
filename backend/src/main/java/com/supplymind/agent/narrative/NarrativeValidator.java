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
    private static final Pattern TREND_VALUE_LABEL=Pattern.compile(
        "最新(?:值|价格)?|当前(?:值|价格)?|结束值|期末值|终点值|起始值|起点值|期初值|首尾变化|累计变化|累计上涨|累计下跌|变化|增加|减少|基准值|阈值|最高值|最低值|最高价|最低价|波动率|中位数|标准差");
    private final AgentResponseVerifier secrets;
    private final SourceRejectionAudit sourceAudit;
    public NarrativeValidator(AgentResponseVerifier secrets){this(secrets,null);}
    public NarrativeValidator(AgentResponseVerifier secrets,SourceRejectionAudit sourceAudit){this.secrets=secrets;this.sourceAudit=sourceAudit;}
    public NarrativeResult validate(String raw,EvidencePackV1 pack) {
        // Stored V3 reports predate request output scoping. Recheck all semantic/evidence
        // rules without retroactively inventing a theme that was never persisted.
        return validate(raw,pack,NarrativeScope.unrestricted());
    }
    public NarrativeResult validate(String raw,EvidencePackV1 pack,NarrativeScope scope) {
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
            String code=section==null?"MALFORMED_SECTION":check(section.kind()==null?null:section.kind().name(),section.text(),section.factIds(),facts,false,pack);
            if(code==null&&!scope.kinds().contains(section.kind().name()))code="OUT_OF_REQUEST_SCOPE";
            if(code==null&&section.factIds().stream().map(facts::get).anyMatch(f->!scope.allowsFactType(f.factType())))code="OUT_OF_REQUEST_SCOPE";
            if(code==null)accepted.add(adopt(id,section.kind().name(),section.text(),section.factIds(),facts));else {
                if(sourceAudit!=null&&section!=null)sourceAudit.captureSection(pack.requestId(),
                    section.kind()==null?null:section.kind().name(),section.text(),section.factIds(),code);
                diagnostic(errors,id,code);
            }
        }
        var acceptedIds=new HashSet<String>();accepted.forEach(s->acceptedIds.addAll(s.factIds()));
        i=0;
        for(var r:draft.recommendations()) {
            String id="recommendation-"+(++i);
            String code=r==null?"MALFORMED_SECTION":check("RECOMMENDATION",r.text(),r.factIds(),facts,true,pack);
            if(code==null&&recs.size()>=scope.maxRecommendations())code="OUT_OF_REQUEST_SCOPE";
            if(code==null&&!acceptedIds.containsAll(r.factIds()))code="DEPENDENCY_NOT_ADOPTED";
            if(code==null)recs.add(adopt(id,"RECOMMENDATION",r.text(),r.factIds(),facts));else {
                if(sourceAudit!=null&&r!=null)sourceAudit.captureSection(pack.requestId(),"RECOMMENDATION",r.text(),r.factIds(),code);
                diagnostic(errors,id,code);
            }
        }
        if(accepted.isEmpty())return new NarrativeResult("ANALYSIS-NARRATIVE-V1","NONE","CONTENT_REJECTED",List.of(),List.of(),errors,1,"关键主张受控检查，不等于任意语义证明");
        return new NarrativeResult("ANALYSIS-NARRATIVE-V1",errors.isEmpty()?"FULL":"PARTIAL","SUCCESS",accepted,recs,errors,1,"结构、引用、分组、日期、单位、来源、关键数值及受控语义；不证明任意自然语言绝对正确");
    }
    private NarrativeResult.Section adopt(String id,String kind,String text,List<String> ids,Map<String,EvidencePackV1.Fact> facts) {
        return new NarrativeResult.Section(id,kind,text,ids,ids.stream().flatMap(f->facts.get(f).evidenceRefs().stream()).distinct().toList());
    }
    private static void diagnostic(List<NarrativeResult.Diagnostic> errors,String id,String code) {
        // Preserve the public category and add only a bounded reason enum, never source/model text.
        if(code.startsWith("SOURCE_"))errors.add(new NarrativeResult.Diagnostic(id,"UNSUPPORTED_SOURCE"));
        errors.add(new NarrativeResult.Diagnostic(id,code));
    }
    private String check(String kind,String text,List<String> ids,Map<String,EvidencePackV1.Fact> all,boolean recommendation,EvidencePackV1 pack) {
        int referenceLimit="WARNING".equals(kind)?12:8;
        if(kind==null||text==null||text.isBlank()||text.length()>1000||ids==null||ids.isEmpty()||ids.size()>referenceLimit||new HashSet<>(ids).size()!=ids.size())return "MALFORMED_SECTION";
        if(ids.stream().anyMatch(id->!all.containsKey(id)))return "UNKNOWN_FACT_ID";
        if(Pattern.compile("[零〇一二两三四五六七八九十百千万亿]+\\s*(?:元|美元|吨|%|％|个百分点)").matcher(text).find())return "UNSUPPORTED_NUMBER_FORMAT";
        if(Pattern.compile("一定会|必然会|保证.{0,8}(上涨|下跌|收益)|未来.{0,6}(上涨|下跌)|将会.{0,6}(上涨|下跌)").matcher(text).find())return "UNSUPPORTED_PREDICTION";
        var selected=ids.stream().map(all::get).toList();
        if(selected.stream().anyMatch(f->!allowed(kind,f.factType())))return "WRONG_FACT_ROLE";
        text=NaturalDateText.checked(text,selected);
        if(text==null)return "WRONG_DATE";
        var sampleValidation=SampleValidationSummary.forSection(selected,pack);
        String validationClaim=sampleValidation.check(kind,text,selected);
        if(validationClaim!=null)return validationClaim;
        if(!recommendation&&!"METRICS".equals(kind)&&any(text,"月均","均值","平均值","月度平均"))return "WRONG_FACT_ROLE";
        if("METRICS".equals(kind)&&Pattern.compile("最新(?:值|价格|价)|当前(?:值|价格)|基准(?:值|价格)|现价|阈值").matcher(text).find())return "WRONG_FACT_ROLE";
        for(String clause:text.split("[，。；;、]")) {
            if(any(clause,"最新","当前")&&!any(clause,"至","→","~","～")) {
                var actual=new ArrayList<String>();
                valueRoleFacts(selected,pack,"history.latest").forEach(f->actual.add(f.businessDate()));
                selected.stream().filter(f->f.factType().equals("warning.explain")).forEach(f->actual.add(f.businessDate()));
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
        var sourceClaim=SourceAssertionCheck.inspect(text,selected);
        if(sourceClaim.code()!=null) {
            if(sourceAudit!=null)sourceAudit.capture(pack.requestId(),sourceClaim,selected);
            return sourceClaim.code();
        }
        var dates=DATE.matcher(text);Set<String> allowedDates=new HashSet<>();
        selected.forEach(f->{allowedDates.add(f.businessDate());allowedDates.add(f.periodStart());allowedDates.add(f.periodEnd());
            if(f.factType().equals("warning.acknowledgedAt"))allowedDates.add(java.time.OffsetDateTime.parse(f.value()).toLocalDate().toString());});
        while(dates.find())if(!allowedDates.contains(dates.group()))return "WRONG_DATE";
        String withoutDates=text;
        // Remove an exactly cited timestamp before its date, otherwise time fragments become false business numbers.
        for(var f:selected)if(f.factType().equals("warning.acknowledgedAt"))withoutDates=withoutDates.replace(f.value(),"");
        var dateMarkers=new LinkedHashMap<String,String>();
        final String dateInput=withoutDates;
        withoutDates=DATE.matcher(withoutDates).replaceAll(m->{
            int candidate=0xE000+dateMarkers.size();
            while(dateInput.indexOf((char)candidate)>=0||dateMarkers.containsKey(String.valueOf((char)candidate)))candidate++;
            String marker=String.valueOf((char)candidate);dateMarkers.put(marker,m.group());return marker;
        });
        for(var f:selected)if(f.unit()!=null&&f.unit().chars().anyMatch(Character::isDigit))withoutDates=withoutDates.replace(f.unit(),"");
        // Strip whole cited identifiers before optional short aliases, avoiding leftover version digits.
        for(var f:selected)if(f.factType().equals("warning.ruleVersion")||f.factType().equals("warning.id"))withoutDates=withoutDates.replace(f.value(),"");
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
            String positiveCoverage=text.replaceAll("(?:并不|不)(?:代表|构成|表示|等于|属于|是)\\s*(?:一个)?(?:完整月份(?:行情)?|完整月度(?:行情)?|全月完整|全部交易日)","");
            if("false".equals(aggregate.get(0).qualityStatus())&&any(positiveCoverage,"全月完整","完整月份","完整月度","全部交易日"))return "UNSUPPORTED_PERIOD_COVERAGE";
            var f=aggregate.get(0);
            var contrastStatuses=pack.facts().stream().filter(other->other.factType().equals("period.metrics.avg")
                &&other.itemId().equals(f.itemId())&&Objects.equals(other.periodStart(),f.periodStart())&&Objects.equals(other.periodEnd(),f.periodEnd()))
                .map(EvidencePackV1.Fact::validationStatus).collect(java.util.stream.Collectors.toSet());
            String groupClaims=Pattern.compile("(?:与|和)\\s*(VERIFIED_WITH_NOTICE|VERIFIED)\\s*组\\s*(?:独立|分别|分开)(?:统计|计算)")
                .matcher(text).replaceAll(m->contrastStatuses.contains(m.group(1))?"":m.group());
            boolean notice=any(groupClaims,"VERIFIED_WITH_NOTICE","附注","有提示","有通知");
            boolean explicitVerified=Pattern.compile("(?<![A-Z_])VERIFIED(?![A-Z_])").matcher(groupClaims).find();
            boolean verified=explicitVerified||!notice&&any(text,"已验证组","完全验证","通过组");
            if(notice&&explicitVerified)return "AMBIGUOUS_AGGREGATE_GROUP";
            if(notice&&!"VERIFIED_WITH_NOTICE".equals(f.validationStatus())||verified&&!notice&&!"VERIFIED".equals(f.validationStatus()))return "WRONG_AGGREGATE_GROUP";
            if(NUMBER.matcher(withoutDates).find()&&!notice&&!verified)return "UNLABELLED_AGGREGATE_GROUP";
        }
        String normalized=withoutDates.replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))","");
        for(String clause:normalized.split("[，。；;、]")) {
            if("QUALITY".equals(kind)&&sampleValidation.countClause(clause))continue;
            var nums=NUMBER.matcher(clause);var roles=new ArrayList<EvidencePackV1.Fact>(selected);
            boolean trendKind="TREND".equals(kind)||"OVERVIEW".equals(kind);
            var endpointPair=pairOf(clause);
            boolean hasEndpointPair=trendKind&&any(clause,"从","→")&&endpointPair.find();
            if(hasEndpointPair&&(!hasDecimal(selected,"history.query",endpointPair.group(1))||valueRoleFacts(selected,pack,"history.latest").stream().noneMatch(f->numericEquals(f.value(),endpointPair.group(2)))))return "WRONG_TREND_PAIR";
            boolean labelledTrend=trendKind&&(TREND_VALUE_LABEL.matcher(clause).find()||hasEndpointPair);
            if(("TREND".equals(kind)||"OVERVIEW".equals(kind))&&!labelledTrend) {
                if(any(clause,"从","→")&&any(clause,"至","→")) {
                    var pair=pairOf(clause);if(!pair.find())return "AMBIGUOUS_TREND_PAIR";
                    if(!hasDecimal(selected,"history.query",pair.group(1))||valueRoleFacts(selected,pack,"history.latest").stream().noneMatch(f->numericEquals(f.value(),pair.group(2))))return "WRONG_TREND_PAIR";
                    roles.removeIf(f->!f.factType().equals("history.query")&&!f.factType().equals("history.latest"));
                }
                else if(any(clause,"最新","当前","上涨至","下跌至","升至","降至","涨至","跌至"))roles=new ArrayList<>(valueRoleFacts(selected,pack,"history.latest"));
                else if(any(clause,"结束值","期末值","终点值"))roles=new ArrayList<>(valueRoleFacts(selected,pack,"history.latest"));
                else if(any(clause,"起始值","起点值","期初值"))roles.removeIf(f->!f.factType().equals("history.query"));
                else if(any(clause,"变化","增加","减少","累计上涨","累计下跌"))roles.removeIf(f->!f.factType().equals("history.trend.change"));
                else if(any(clause,"历史值"))roles.removeIf(f->!f.factType().equals("history.query"));
                else if(dateMarkers.keySet().stream().anyMatch(clause::contains)&&nums.find()) {
                    var days=dateMarkers.entrySet().stream().filter(e->clause.contains(e.getKey())).map(Map.Entry::getValue).collect(java.util.stream.Collectors.toSet());
                    if(days.size()!=1)return "AMBIGUOUS_VALUE_ROLE";
                    roles.removeIf(f->!days.contains(f.businessDate())||!Set.of("history.query","history.latest").contains(f.factType()));
                }
                else if(nums.find()) {
                    var numeric=roles.stream().filter(f->{try{new BigDecimal(f.value());return true;}catch(NumberFormatException nonnumeric){return false;}}).toList();
                    if(numeric.size()!=1||any(clause,"基准值","阈值","最高值","最低值","最高价","最低价","波动率","中位数","标准差"))return "AMBIGUOUS_VALUE_ROLE";
                    roles=new ArrayList<>(numeric);
                }
                nums=NUMBER.matcher(clause);
            }
            boolean labelledWarning="WARNING".equals(kind)&&Pattern.compile("当前(?:值|价格)?|最新(?:值|价格)?|基准(?:值|价格)?|阈值").matcher(clause).find();
            if("WARNING".equals(kind)&&!labelledWarning) {
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
                var tokenRoles=boundRoles;
                if(labelledTrend) {
                    // A clause may contain both endpoints AND a change. Bind each number
                    // to its nearest explicit role, never to any coincident selected value.
                    var labels=TREND_VALUE_LABEL.matcher(clause.substring(0,nums.start()));
                    String label=null;while(labels.find())label=labels.group();
                    String type=trendValueType(label);
                    if(hasEndpointPair&&nums.start()==endpointPair.start(1))type="history.query";
                    if(hasEndpointPair&&nums.start()==endpointPair.start(2))type="history.latest";
                    tokenRoles=valueRoleFacts(selected,pack,type);
                }
                if(labelledWarning) {
                    var labels=Pattern.compile("当前(?:值|价格)?|最新(?:值|价格)?|基准(?:值|价格)?|阈值").matcher(clause.substring(0,nums.start()));
                    String label=null;while(labels.find())label=labels.group();
                    if((token.endsWith("%")||token.endsWith("％"))&&clause.substring(nums.end()).stripLeading().startsWith("阈值"))label="阈值";
                    String type=label==null?null:label.startsWith("基准")?"warning.baselineValue":label.equals("阈值")?"warning.thresholdPercent":"warning.explain";
                    tokenRoles=selected.stream().filter(f->f.factType().equals(type)).toList();
                }
                if(tokenRoles.stream().noneMatch(f->numericEquals(f.value(),token)))return "UNSUPPORTED_NUMBER";
                if(token.endsWith("%")||token.endsWith("％")) {
                    if(tokenRoles.stream().noneMatch(f->f.value().endsWith("%")&&numericEquals(f.value(),token)))return "WRONG_UNIT";
                }
            }
        }
        Set<String> units=new HashSet<>();selected.forEach(f->{if(f.unit()!=null)units.add(f.unit());});
        if("WARNING".equals(kind)) {
            // Unit is shared item metadata, not a new price/rate. Never borrow a numeric value or guess between conflicting units.
            var items=selected.stream().map(EvidencePackV1.Fact::itemId).collect(java.util.stream.Collectors.toSet());
            var priceUnits=pack.facts().stream().filter(f->items.contains(f.itemId())&&Set.of("history.query","history.latest").contains(f.factType()))
                .map(EvidencePackV1.Fact::unit).filter(u->u!=null&&!u.isBlank()).distinct().toList();
            if(items.size()==1&&priceUnits.size()==1)units.add(priceUnits.get(0));
        }
        for(String compound:List.of("万元/吨","元/千克","元/公斤","美元/吨","元/吨","CNY/1 USD","CNY/1 EUR"))if(text.contains(compound)&&!units.contains(compound))return "WRONG_UNIT";
        for(var currency:Map.of("欧元","EUR","英镑","GBP","美元","USD").entrySet())if(Pattern.compile("(?:\\d\\s*|单位(?:为|是|：|:)\\s*)"+currency.getKey()).matcher(text).find()&&selected.stream().noneMatch(f->currency.getValue().equals(f.currency())))return "WRONG_UNIT";
        String unitText=text;for(String known:units)unitText=unitText.replace(known,"");
        for(var f:selected)unitText=unitText.replace(f.itemId(),"");
        for(String u:List.of("元/吨","美元/吨","CNY/1 USD","CNY/1 EUR","USD","EUR","GBP"))if(unitText.contains(u)&&!units.contains(u)&&selected.stream().noneMatch(f->u.equals(f.currency())))return "WRONG_UNIT";
        return null;
    }
    private static String trendValueType(String label) {
        if(label==null)return null;
        if(label.startsWith("最新")||label.startsWith("当前")||Set.of("结束值","期末值","终点值").contains(label))return "history.latest";
        if(Set.of("起始值","起点值","期初值").contains(label))return "history.query";
        if(Set.of("首尾变化","累计变化","累计上涨","累计下跌","变化","增加","减少").contains(label))return "history.trend.change";
        return null; // Extrema, baseline and rates are not supported by endpoint/change facts.
    }
    private static List<EvidencePackV1.Fact> valueRoleFacts(List<EvidencePackV1.Fact> selected,EvidencePackV1 pack,String type) {
        return selected.stream().filter(f->f.factType().equals(type)||"history.latest".equals(type)&&isLatestObservation(f,pack)).toList();
    }
    private static boolean isLatestObservation(EvidencePackV1.Fact daily,EvidencePackV1 pack) {
        if(!daily.factType().equals("history.query")||daily.businessDate()==null||daily.evidenceRefs().isEmpty()||daily.sourceFingerprint()==null||daily.sourceFingerprint().isBlank())return false;
        var canonical=pack.facts().stream().filter(f->f.factType().equals("history.latest")&&f.itemId().equals(daily.itemId())).toList();
        if(canonical.size()!=1)return false;
        var latest=canonical.get(0);
        // These are projections of one already verified observation, not interchangeable
        // prices. Keep the model's exact cited IDs and original text; never add a citation.
        return Objects.equals(daily.businessDate(),latest.businessDate())&&numericEquals(daily.value(),latest.value())
            &&Objects.equals(daily.unit(),latest.unit())&&Objects.equals(daily.currency(),latest.currency())
            &&Objects.equals(daily.qualityStatus(),latest.qualityStatus())&&Objects.equals(daily.validationStatus(),latest.validationStatus())
            &&Objects.equals(daily.validationVersion(),latest.validationVersion())&&Objects.equals(daily.calculationVersion(),latest.calculationVersion())
            &&Objects.equals(daily.calendarVersion(),latest.calendarVersion())&&Objects.equals(daily.configVersions(),latest.configVersions())
            &&Objects.equals(daily.actualSourceName(),latest.actualSourceName())&&Objects.equals(daily.sourceFingerprint(),latest.sourceFingerprint())
            &&Objects.equals(daily.evidenceRefs(),latest.evidenceRefs())&&Objects.equals(daily.rowEvidenceId(),latest.rowEvidenceId())
            &&Objects.equals(daily.periodStart(),latest.periodStart())&&Objects.equals(daily.periodEnd(),latest.periodEnd());
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
