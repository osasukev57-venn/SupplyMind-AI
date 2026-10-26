package com.supplymind.agent.orchestration;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** One source for explicit ranges, synonyms, themes and fact-readiness requirements. */
public final class QueryPlanner {
    private QueryPlanner(){}
    public static boolean monthly(String q){String s=q==null?"":q.toLowerCase(Locale.ROOT);return s.contains("月均")||s.contains("月度均值")||s.contains("月度平均")||s.contains("monthly average");}
    public static boolean requiresMonthly(AgentOrchestrator.AgentQueryInput input){return "METRICS".equals(input.theme())||monthly(input.question())&&(input.theme()==null||"COMPREHENSIVE".equals(input.theme()));}
    public static AgentOrchestrator.AgentQueryInput plan(AgentOrchestrator.AgentQueryInput in,Clock clock,Supplier<LocalDate> latest) {
        if(in.theme()!=null&&!Set.of("COMPREHENSIVE","HISTORY","METRICS","WARNING").contains(in.theme()))throw new IllegalArgumentException("不支持的分析主题");
        var dates=new ArrayList<String>();var matcher=java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(in.question());while(matcher.find())dates.add(matcher.group());
        String start=in.startDate(),end=in.endDate();
        if((start==null)!=(end==null))throw new IllegalArgumentException("请同时选择开始和结束日期");
        if(start!=null&&!dates.isEmpty()&&(!start.equals(dates.get(0))||!end.equals(dates.size()>1?dates.get(1):dates.get(0))))throw new IllegalArgumentException("所选日期与问题中的日期冲突，请核对范围");
        if(start==null&&!dates.isEmpty()){start=dates.get(0);end=dates.size()>1?dates.get(1):start;}
        if(start==null&&in.periodStart()==null&&in.month()==null&&in.businessDate()==null){
            LocalDate last=latest.get();if(last==null)last=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
            start=YearMonth.from(last).atDay(1).toString();end=last.toString();
        }
        String grain=in.grain(),ps=in.periodStart(),pe=in.periodEnd(),month=in.month();
        boolean comprehensive="COMPREHENSIVE".equals(in.theme())||in.theme()==null&&in.question().contains("综合");
        boolean metrics=requiresMonthly(in)||comprehensive;
        if(metrics&&start!=null){if(grain==null)grain="month";if(ps==null)ps=YearMonth.parse(start.substring(0,7)).atDay(1).toString();if(pe==null)pe=YearMonth.parse(end.substring(0,7)).atEndOfMonth().toString();}
        if(month==null&&start!=null&&(in.theme()==null&&in.question().contains("预警")||"WARNING".equals(in.theme())||comprehensive))month=start.substring(0,7);
        return new AgentOrchestrator.AgentQueryInput(in.question(),in.itemId(),start,end,grain,ps,pe,month,in.businessDate(),in.mode(),in.theme());
    }
}
