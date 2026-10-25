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
   model.set(r->{var chosen=r.facts().stream().filter(f->Set.of("latest published value","Java-computed trend direction","persisted period average","Java-evaluated warning risk level","verified warning rule version","verified warning acknowledgement status").contains(f.statement())).toList();return LLMService.LLMResponse.success(template(chosen,"事实说明："));});
   assertFalse(orchestrator.answer(input(QUESTION)).degraded(),"production prompt-compatible template must pass");
   for(String attack:List.of("number","unknown","duplicate","dangling","risk","trend","missingGroup","unselected")) {
    model.set(r->{var facts=r.facts().stream().filter(f->Set.of("latest published value","persisted period average").contains(f.statement())).toList();String json=template(facts,attack.equals("number")?"价格为999999。":attack.equals("risk")?"风险已消失。":attack.equals("trend")?"价格下跌。":"事实：");
     var node=parse(json);
     if(attack.equals("unknown")){node.put("answerTemplate","{{fact-unknown}}");node.set("usedFactIds",JsonV1Codec.mapper().valueToTree(List.of("fact-unknown")));}
     if(attack.equals("duplicate"))node.put("answerTemplate",node.get("answerTemplate").asText()+"{{"+facts.get(0).factId()+"}}");
     if(attack.equals("unselected"))node.put("answerTemplate",node.get("answerTemplate").asText()+"{{"+r.facts().stream().filter(f->f.statement().equals("immutable warning identifier")).findFirst().orElseThrow().factId()+"}}");
     if(attack.equals("dangling"))node.put("answerTemplate",node.get("answerTemplate").asText()+"{{bad}}");
     if(attack.equals("missingGroup")){node.put("answerTemplate","{{"+facts.get(0).factId()+"}}");node.set("usedFactIds",JsonV1Codec.mapper().valueToTree(List.of(facts.get(0).factId())));}
     return LLMService.LLMResponse.success(node.toString());});
    var rejected=orchestrator.answer(input(QUESTION));assertTrue(rejected.degraded(),attack);
    String expectedReason=Map.of("number","SHELL_NUMBER","unknown","UNKNOWN_SELECTED_FACT_ID","duplicate","DUPLICATE_TOKEN","dangling","DANGLING_TOKEN","risk","UNSUPPORTED_SHELL_SEMANTICS","trend","UNSUPPORTED_SHELL_SEMANTICS","missingGroup","MISSING_REQUIRED_MONTHLY_GROUP","unselected","TOKEN_NOT_SELECTED").get(attack);
    assertTrue(rejected.report().limitations().contains("FACT_TEMPLATE_REJECTED:"+expectedReason),attack+" "+rejected.report().limitations());
    assertFalse(rejected.report().claims().toString().contains("999999"));
   }
   rules.update(ITEM,7,"0.99",WarningRuleConfigV1.Direction.BOTH,true,"新规则没有触发记录");
   var noApplicable=orchestrator.answer(input(QUESTION));assertNull(noApplicable.riskProjection(),"old rule must not become current after rule changes");
   Files.move(temp.resolve("processed/aggregate/"+ITEM+"/month/2026.csv"),temp.resolve("saved-month.csv"));
   model.set(r->{fail("Missing persisted monthly facts must block model before invocation");return null;});
   for(String term:List.of("月均","月均值","月度均值","月度平均")){var missing=orchestrator.answer(input(QUESTION.replace("月度均值",term)));assertTrue(missing.degradeReason().startsWith("STANDARD_FACTS_NOT_READY:"),term);}
  }
 }
 private static AgentOrchestrator.AgentQueryInput input(String q){return new AgentOrchestrator.AgentQueryInput(q,ITEM,null,null,null,null,null,null,null,"FORMAL");}
 private static com.fasterxml.jackson.databind.node.ObjectNode parse(String json){try{return (com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().readTree(json);}catch(java.io.IOException ex){throw new IllegalArgumentException(ex);}}
 private static String template(List<LLMService.LlmFact> facts,String prefix){return JsonV1Codec.encodeCompact(Map.of("answerTemplate",prefix+facts.stream().map(f->"{{"+f.factId()+"}}").collect(java.util.stream.Collectors.joining("；")),"usedFactIds",facts.stream().map(LLMService.LlmFact::factId).toList()));}
 @TestConfiguration static class Time {@Bean @Primary Clock clock(){return Clock.fixed(Instant.parse("2026-10-25T04:00:00Z"),ZoneOffset.ofHours(8));}}
}
