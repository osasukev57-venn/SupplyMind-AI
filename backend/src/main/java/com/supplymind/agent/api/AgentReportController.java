package com.supplymind.agent.api;
import com.supplymind.agent.report.ReportStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;

/** Read-only saved-report projection; every open re-verifies the persisted evidence closure. */
@RestController
@RequestMapping("/api/agent/reports")
public final class AgentReportController {
 private final ReportStore reports;
 public AgentReportController(ReportStore reports){this.reports=reports;}
 @GetMapping("/read")
 public ResponseEntity<?> read(@RequestParam String ref) {
  if(ref==null||!ref.matches("report/[0-9]{4}-[0-9]{2}/[A-Za-z0-9._-]+\\.json"))return ResponseEntity.badRequest().body(Map.of("message","报告标识格式不正确"));
  var result=reports.read(ref);
  if(!result.ok())return ResponseEntity.status(409).body(Map.of("message","报告或生成时证据不可验证，未使用最新数据替换", "reason",result.failureCode()));
  var r=result.report();
  var view=new LinkedHashMap<String,Object>();view.put("requestId",r.requestId());view.put("reportRef",ref);view.put("createdAt",r.createdAt());
  view.put("generatedBy",r.generatedBy());view.put("narrative",result.narrative());view.put("scope",r.evidencePack().scope());
  view.put("javaSummary",r.claims().stream().map(c->c.text()).collect(java.util.stream.Collectors.joining("\n")));
  return ResponseEntity.ok(view);
 }
}
