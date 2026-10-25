package com.supplymind.agent.orchestration;

import com.supplymind.agent.tool.*;
import java.util.*;

/** Selects a current warning view without removing historical evidence or re-evaluating risk. */
final class WarningSummarySelector {
 static List<ToolResult> select(List<ToolResult> results,AgentOrchestrator.AgentQueryInput input) {
  String latest=results.stream().filter(t->t.toolName().equals("history.query"))
      .flatMap(t->rows(t.result().get("rows")).stream()).map(r->text(r,"businessDate"))
      .filter(Objects::nonNull).max(String::compareTo).orElse(null);
  List<ToolResult> selected=new ArrayList<>();
  for(var tool:results) {
   if(!tool.toolName().equals("warning.explain")||tool.status()!=ToolStatus.SUCCESS){selected.add(tool);continue;}
   var body=new LinkedHashMap<>(tool.result());
   var candidates=rows(body.get("warnings")).stream()
       .filter(r->!"FORMAL".equals(input.mode())||!Boolean.TRUE.equals(r.get("demoRule")))
       .filter(r->input.itemId()==null||input.itemId().equals(text(r,"itemId")))
       .filter(r->text(r,"periodStart")!=null&&text(r,"periodEnd")!=null)
       .filter(r->input.startDate()==null||text(r,"periodStart").compareTo(input.startDate())>=0)
       .filter(r->input.endDate()==null||text(r,"periodEnd").compareTo(input.endDate())<=0)
       .filter(r->latest==null||latest.equals(text(r,"periodEnd")))
       .filter(r->!Boolean.TRUE.equals(body.get("activeRuleKnown")) ||
           Objects.equals(body.get("activeRuleId"),r.get("ruleId"))&&Objects.equals(body.get("activeRuleVersion"),r.get("ruleVersion")))
       .sorted(Comparator.<Map<?,?>,String>comparing(r->Objects.toString(r.get("periodEnd"),""))
           .thenComparing(r->Objects.toString(r.get("evaluatedAt"),""))
           .thenComparing(r->Objects.toString(r.get("warningId"),"")).reversed()).toList();
   body.put("summaryWarnings",candidates.isEmpty()?List.of():List.of(candidates.get(0)));
   var notices=new ArrayList<>(tool.notices());
   if(candidates.isEmpty())notices.add("请求范围和最新业务日没有匹配当前规则的适用预警；历史记录仍保留，不代表风险为零");
   selected.add(new ToolResult(tool.toolName(),tool.toolVersion(),tool.requestId(),tool.status(),tool.inputSummary(),body,notices,tool.evidenceRefs(),tool.lineage(),tool.evidenceLineageByRef()));
  }
  return List.copyOf(selected);
 }
 private static List<Map<?,?>> rows(Object value){if(!(value instanceof List<?> rows))return List.of();return rows.stream().filter(Map.class::isInstance).<Map<?,?>>map(v->(Map<?,?>)v).toList();}
 private static String text(Map<?,?> row,String key){return row.get(key)==null?null:String.valueOf(row.get(key));}
}
