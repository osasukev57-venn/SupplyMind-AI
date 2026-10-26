package com.supplymind.agent;

import com.supplymind.SupplyMindApplication;
import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.api.AgentQueryResponse;
import com.supplymind.agent.evidence.EvidenceRefVerifier;
import com.supplymind.agent.fallback.TemplateFallbackService;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.*;
import com.supplymind.agent.report.ReportStore;
import com.supplymind.config.DynamicConfigWorkflowService;
import com.supplymind.config.api.ConfigV1;
import com.supplymind.dashboard.DashboardService;
import com.supplymind.foundation.codec.JsonV1Codec;
import com.supplymind.foundation.storage.*;
import com.supplymind.warning.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentDemoAcceptanceFixTest {
 @TempDir Path temp;
 static final String ITEM="MAT.AZ91D.SIMULATION";
 static final String QUESTION="请根据 MAT.AZ91D.SIMULATION 在 2026-10-13 至 2026-10-22 的已发布模拟数据，说明价格走势、最新值、单位和月度均值，并解释该监测项的预警及确认处理状态。请区分不同校验状态的统计结果，明确这些是模拟数据，不代表真实市场风险。";
 @Test void originalQuestionUsesProductionMetricsCurrentRuleAckAndSafeTemplates() throws Exception {
  try(var ctx=new SpringApplicationBuilder(SupplyMindApplication.class,Time.class).web(WebApplicationType.NONE).run(
      "--spring.main.web-application-type=none","--supplymind.data-root="+temp,"--supplymind.current-acquisition.on-startup-enabled=false",
      "--supplymind.scheduler.guarded-enabled=false","--supplymind.agent.llm.enabled=false")) {
   var config=ctx.getBean(DynamicConfigWorkflowService.class);
   String add="""
     {"itemId":"MAT.AZ91D.SIMULATION","displayName":"模拟演示AZ91D（非真实市场数据）","sourceIntent":"模拟演示非真实市场数据",
      "providerType":"local_import","accessMethod":"local_import","actualSourceName":"本地文件导入（LocalImport）","routeDecision":"direct_local_import",
      "externalCode":"AZ91D","sourceFieldKey":"material-field-key","rateKind":"material","calculationVersion":"arithmetic-mean-v1","calculationScale":2,
      "displayScale":2,"roundingMode":"HALF_UP","calendarVersion":"weekday-asia-shanghai-v1","currency":"CNY","baseCurrency":"CNY","unit":"元/吨",
      "materialValidation":{"valueMinExclusive":"0","valueMaxInclusive":null,"staleThresholdDays":7,"canonicalSpecCode":"AZ91D","acceptedSpecAliases":[]}}
     """;
   config.addItem(JsonV1Codec.mapper().readValue(add,ConfigV1.AddItemRequest.class));
   var dashboard=ctx.getBean(DashboardService.class);
   String[] dates={"2026-10-13","2026-10-14","2026-10-15","2026-10-16","2026-10-19","2026-10-20","2026-10-21","2026-10-22"};
   String[] values={"23000","23150","23250","23400","23600","24900","25200","25500"};
   StringBuilder csv=new StringBuilder("schemaVersion,itemId,businessDate,value,unit,currency,actualSourceName,sourceReference,sourceUrl\r\n");
   for(int i=0;i<dates.length;i++)csv.append("1.0,").append(ITEM).append(',').append(dates[i]).append(',').append(values[i]).append(",元/吨,CNY,模拟演示（非真实市场数据）,SIM-").append(dates[i]).append(",\r\n");
   var intake=dashboard.importPending("simulation.csv",csv.toString().getBytes(StandardCharsets.UTF_8));
   assertEquals(8,intake.acceptedRows().size());assertTrue(intake.rowErrors().isEmpty());
   assertEquals("PUBLISHED",dashboard.processImportBatch(intake.acceptedRows().stream().map(r->r.runId()).toList()).status());
   var rules=ctx.getBean(WarningRuleManagementService.class);
   WarningRecordV1 r4=null;
   for(int i=0;i<4;i++){rules.update(ITEM,7,"0.05",WarningRuleConfigV1.Direction.BOTH,true,"模拟阈值");r4=rules.evaluateItem(ITEM).warning();}
   assertNotNull(r4);assertTrue(r4.ruleVersion().endsWith("custom-r4"));
   ctx.getBean(WarningAckStore.class).acknowledge(r4,"模拟确认：确认不代表风险消失",OffsetDateTime.now(ctx.getBean(Clock.class)));
   var expected=r4;
   var model=new AtomicReference<LLMService.Port>(r->LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,"test_local"));
   var orchestrator=new AgentOrchestrator(ctx.getBean(ToolExecutor.class),r->model.get().analyze(r),new TemplateFallbackService(),ctx.getBean(EvidenceRefVerifier.class),ctx.getBean(ReportStore.class),new AgentResponseVerifier(List.of()),ctx.getBean(Clock.class));
   for(String term:List.of("月均","月均值","月度均值","月度平均")) {
    var result=orchestrator.answer(input(QUESTION.replace("月度均值",term)));
    assertTrue(result.evidencePack().toolExecutions().stream().anyMatch(t->t.toolName().equals("period.metrics")&&t.status()==com.supplymind.agent.tool.ToolStatus.SUCCESS),term);
    var averages=result.evidencePack().facts().stream().filter(f->f.factType().equals("period.metrics.avg")).toList();
    assertEquals(Set.of("24800.00","23200.00"),new HashSet<>(averages.stream().map(f->f.value()).toList()));
    assertTrue(averages.stream().anyMatch(f->f.value().equals("24800.00")&&f.validationStatus().equals("VERIFIED")));
    assertTrue(averages.stream().anyMatch(f->f.value().equals("23200.00")&&f.validationStatus().equals("VERIFIED_WITH_NOTICE")));
    assertEquals(expected.warningId(),result.riskProjection().warningId());assertEquals(expected.ruleVersion(),result.riskProjection().ruleVersion());
    assertEquals("ACKNOWLEDGED",result.riskProjection().acknowledgedStatus());assertEquals("2026-10-22",result.riskProjection().periodEnd());
    assertEquals("2026-10-13",result.evidencePack().scope().periodStart());assertEquals("2026-10-22",result.evidencePack().scope().periodEnd());
    assertEquals(1,result.evidencePack().facts().stream().filter(f->f.factType().equals("warning.id")).count());
    String answer=AgentQueryResponse.of(result).answer();
    for(String word:List.of("24800.00","23200.00","VERIFIED_WITH_NOTICE","custom-r4","已确认","不代表风险消失","模拟数据","25500"))assertTrue(answer.contains(word),word+": "+answer);
    assertTrue(ctx.getBean(ReportStore.class).read(result.reportRef()).ok());
   }
   model.set(r->LLMService.LLMResponse.success(narrative(r,"normal")));
   var normal=orchestrator.answer(input(QUESTION));
   assertFalse(normal.degraded(),"normal Chinese must be adopted: "+normal.narrative());
   assertEquals("FULL",normal.narrative().adoption());
   assertEquals(0,normal.narrative().recommendations().size(),"original question does not request advice");
   assertTrue(ctx.getBean(ReportStore.class).read(normal.reportRef()).ok());
   model.set(r->LLMService.LLMResponse.success(narrative(r,"fluent")));
   assertEquals("FULL",orchestrator.answer(input(QUESTION)).narrative().adoption(),"normal role-bound Chinese does not require template cues");
   model.set(r->LLMService.LLMResponse.success(narrative(r,"decimal")));
   assertEquals("FULL",orchestrator.answer(input(QUESTION)).narrative().adoption(),"equivalent decimals and dates are legal");
   for(String attack:List.of("number","unknown","wrongDate","wrongGroup","risk","trend","source","unit","warningPair","warningDate","warningRule","mixedLabel","warningRiskDecline")) {
    model.set(r->LLMService.LLMResponse.success(narrative(r,attack)));
    var rejected=orchestrator.answer(input(QUESTION));
    assertEquals("PARTIAL",rejected.narrative().adoption(),attack);
    assertEquals(3,rejected.narrative().sections().size(),"one bad section must not erase independent valid sections");
    assertFalse(rejected.narrative().diagnostics().isEmpty(),attack);
    if(attack.equals("warningPair"))assertTrue(rejected.narrative().diagnostics().stream().anyMatch(d->d.code().equals("WRONG_WARNING_PAIR")));
    if(attack.equals("warningDate"))assertTrue(rejected.narrative().diagnostics().stream().anyMatch(d->d.code().equals("WRONG_DATE_ROLE")));
    if(attack.equals("warningRule"))assertTrue(rejected.narrative().diagnostics().stream().anyMatch(d->d.code().equals("WRONG_RULE_VERSION")));
    assertFalse(rejected.narrative().sections().toString().contains("999999"));
   }
   model.set(r->LLMService.LLMResponse.success("{\"sections\":[{\"kind\":\"TREND\",\"text\":\"伪造999999\",\"factIds\":[\"unknown\"]}],\"recommendations\":[]}"));
   assertEquals("NONE",orchestrator.answer(input(QUESTION)).narrative().adoption());
   rules.update(ITEM,7,"0.99",WarningRuleConfigV1.Direction.BOTH,true,"新规则没有触发记录");
   var noApplicable=orchestrator.answer(input(QUESTION));assertNull(noApplicable.riskProjection(),"old rule must not become current after rule changes");
   String appended="schemaVersion,itemId,businessDate,value,unit,currency,actualSourceName,sourceReference,sourceUrl\r\n1.0,"+ITEM+",2026-10-23,25600,元/吨,CNY,模拟演示（非真实市场数据）,SIM-2026-10-23,\r\n";
   var next=dashboard.importPending("next.csv",appended.getBytes(StandardCharsets.UTF_8));assertEquals(1,next.acceptedRows().size());
   assertEquals("PUBLISHED",dashboard.processImportBatch(next.acceptedRows().stream().map(r->r.runId()).toList()).status());
   var historical=ctx.getBean(ReportStore.class).read(normal.reportRef());assertTrue(historical.ok());
   assertEquals("24800.00",historical.report().evidencePack().facts().stream().filter(f->f.factType().equals("period.metrics.avg")&&f.validationStatus().equals("VERIFIED")).findFirst().orElseThrow().value());
   Files.move(temp.resolve("processed/aggregate/"+ITEM+"/month/2026.csv"),temp.resolve("saved-month.csv"));
   model.set(r->{fail("Missing persisted monthly facts must block model before invocation");return null;});
   for(String term:List.of("月均","月均值","月度均值","月度平均")){var missing=orchestrator.answer(input(QUESTION.replace("月度均值",term)));assertTrue(missing.degradeReason().startsWith("STANDARD_FACTS_NOT_READY:"),term);}
  }
 }
 private static AgentOrchestrator.AgentQueryInput input(String q){return new AgentOrchestrator.AgentQueryInput(q,ITEM,null,null,null,null,null,null,null,"FORMAL");}
 private static com.fasterxml.jackson.databind.node.ObjectNode parse(String json){try{return (com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().readTree(json);}catch(java.io.IOException ex){throw new IllegalArgumentException(ex);}}
 private static String narrative(LLMService.LLMRequest req,String variant) {
   var latest=req.facts().stream().filter(f->f.statement().equals("latest published value")).findFirst().orElseThrow();
   var trend=req.facts().stream().filter(f->f.statement().equals("Java-computed trend direction")).findFirst().orElseThrow();
   var avg=req.facts().stream().filter(f->f.statement().equals("persisted period average")&&f.validationStatus().equals("VERIFIED")).findFirst().orElseThrow();
   var notice=req.facts().stream().filter(f->f.statement().equals("persisted period average")&&f.validationStatus().equals("VERIFIED_WITH_NOTICE")).findFirst().orElseThrow();
   String text="区间呈上升趋势，2026-10-22 最新值为25,500.0元/吨。";
   List<String> ids=List.of(latest.factId(),trend.factId());
   if(variant.equals("fluent"))text="观测价格为25,500元/吨，区间整体上升。";
   if(variant.equals("decimal"))text="观测区间上涨，最新值25500.00元/吨。";
   if(variant.equals("number"))text="最新值999999元/吨。";
   if(variant.equals("unknown"))ids=List.of("unknown-fact");
   if(variant.equals("wrongDate"))text="2026-10-21 最新值25500元/吨。";
   if(variant.equals("risk"))text="风险已经解除。";
   if(variant.equals("trend"))text="区间价格下跌。";
   if(variant.equals("source"))text="来源为路透社，最新值25500元/吨。";
   if(variant.equals("unit"))text="最新值25500美元/吨。";
   var sections=new ArrayList<Map<String,Object>>();
   sections.add(Map.of("kind","TREND","text",text,"factIds",ids));
   sections.add(Map.of("kind","METRICS","text",variant.equals("wrongGroup")?"VERIFIED_WITH_NOTICE组均值24800.00元/吨。":variant.equals("mixedLabel")?"VERIFIED_WITH_NOTICE和VERIFIED组均值24800.00元/吨。":"VERIFIED组月度均值为24,800.00元/吨。","factIds",List.of(avg.factId())));
   sections.add(Map.of("kind","METRICS","text","VERIFIED_WITH_NOTICE组月均为23200元/吨。","factIds",List.of(notice.factId())));
   var warning=req.facts().stream().filter(f->Set.of("verified warning current value","verified warning baseline value","Java-evaluated warning threshold percentage","Java-evaluated warning risk level","verified warning acknowledgement status","verified warning rule version").contains(f.statement())).toList();
   String warningText="比较区间2026-10-15至2026-10-22，基准值23250，当前值25500，阈值5%，规则custom-r4，风险等级HIGH；已确认，但确认不代表风险解除。";
   if(variant.equals("warningPair"))warningText="从25500至23250。";
   if(variant.equals("warningRule"))warningText="当前适用规则custom-r3，已确认。";
   if(variant.equals("warningDate"))warningText="2026-10-15 当前值25500元/吨。";
   if(variant.equals("warningRiskDecline"))warningText="该预警已确认，确认后风险下降。";
   sections.add(Map.of("kind","WARNING","text",warningText,"factIds",warning.stream().map(LLMService.LlmFact::factId).toList()));
   return JsonV1Codec.encodeCompact(Map.of("sections",sections,"recommendations",req.question().contains("建议")?List.of(Map.of("text","建议继续观察后续数据，并结合样本范围判断。","factIds",List.of(trend.factId()))):List.of()));
 }
 @TestConfiguration static class Time {@Bean @Primary Clock clock(){return Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"),ZoneOffset.ofHours(8));}}
}
