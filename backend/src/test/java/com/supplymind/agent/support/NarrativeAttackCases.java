package com.supplymind.agent.support;
import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Migrated production attacks send the NEW contract, so rejection must hit the intended branch. */
public final class NarrativeAttackCases {
 public static void run(Path temp,String attack,String adoption,String reason) throws Exception {
  var f=Day6R2Fixture.create(temp,attack);var calls=new AtomicInteger();
  var result=f.orchestrator(req->{calls.incrementAndGet();return LLMService.LLMResponse.success(response(req,attack));},new AgentResponseVerifier(List.of("super-secret-value")),Day6R2Fixture.CLOCK).answer(f.formalHistoryQuery());
  assertEquals(1,calls.get(),"attack must pass fact readiness and enter production narrative validation");
  assertEquals(adoption,result.narrative().adoption(),result.narrative().toString());
  if(reason!=null)assertTrue(result.narrative().diagnostics().stream().anyMatch(d->d.code().equals(reason)),result.narrative().toString());
  if(!adoption.equals("NONE"))assertFalse(result.narrative().sections().isEmpty());
  if(adoption.equals("NONE"))assertEquals("JAVA_TEMPLATE",result.report().generatedBy());
  String saved=Files.readString(f.root().resolveDataRef(result.reportRef()));
  assertFalse(saved.contains("super-secret-value"));assertFalse(saved.contains("999999.999"));
  assertTrue(f.reportStore().read(result.reportRef()).ok(),"adopted/local report must retain verified evidence");
  if(adoption.equals("PARTIAL")){assertEquals(1,result.narrative().sections().size());assertEquals("LLM_PARTIAL",result.report().generatedBy());}
 }
 public static String response(LLMService.LLMRequest req,String attack) {
  var latest=req.facts().stream().filter(f->f.statement().equals("latest published value")).findFirst().orElseThrow();
  var quality=req.facts().stream().filter(f->f.statement().equals("published row count")).findFirst().orElseThrow();
  if(attack.equals("metadata")){assertFalse(latest.itemId().isBlank());assertFalse(latest.unit().isBlank());assertFalse(latest.currency().isBlank());assertNotNull(latest.businessDate());assertFalse(latest.actualSourceName().isBlank());assertFalse(latest.evidenceRef().isBlank());}
  String text="最新值 "+latest.value();List<String> ids=List.of(latest.factId());String kind="OVERVIEW";
  switch(attack){
   case "invalidKind"->kind="INVALID";
   case "validDate"->text="2026-08-10 最新值 "+latest.value();
   case "validSource"->text="数据来自 "+latest.actualSourceName()+"，最新值 "+latest.value();
   case "validDecimals"->text="最新值 "+latest.value()+"0 "+latest.unit();
   case "chineseNumber"->text="最新值二万元。";
   case "currency"->text="最新值 "+latest.value()+"欧元";
   case "prediction"->text="未来一定会上涨。";
   case "number","partial","unrelatedFact"->text="最新值 999999.999";
   case "dateFragmentNumber"->text="2026-08-10 最新值20";
   case "crossRole"-> {text="最新值 "+quality.value();ids=List.of(latest.factId(),quality.factId());}
   case "twoValuesOneRef"->text="最新值 "+latest.value()+"，最新值 "+quality.value();
   case "twoRoles"->{var good=Map.of("kind","OVERVIEW","text",text,"factIds",ids);var q=Map.of("kind","QUALITY","text","观测数 "+quality.value(),"factIds",List.of(quality.factId()));return JsonV1Codec.encodeCompact(Map.of("sections",List.of(good,q),"recommendations",List.of()));}
   case "percent"->text="最新值 "+latest.value()+"%";
   case "wrongDate"->text="2026-08-01 最新值 "+latest.value();
   case "source"->text="来源：虚构银行。";
   case "sourceEnglish"->text="source: Fake Bank Ltd.";
   case "sourcePrefix"->text="来源："+latest.actualSourceName()+"伪造分行。";
   case "secret"->text="super-secret-value";
   case "secretId"->ids=List.of("super-secret-value");
   case "unknown"->ids=List.of("fact-does-not-exist");
   case "emptyIds"->ids=List.of();
   case "duplicateIds"->ids=List.of(latest.factId(),latest.factId());
   case "trend"->text="区间价格下跌。";
   case "risk"->text="风险已解除。";
   case "plain"->{return text;}
   case "malformed"->{return "{broken json";}
   case "retiredTemplate"->{return "{\"answerTemplate\":\"{{fact-0}}\",\"usedFactIds\":[\"fact-0\"]}";}
  }
  var section=Map.of("kind",kind,"text",text,"factIds",ids);
  var sections=new ArrayList<Map<String,Object>>();sections.add(section);
  if(attack.equals("partial")||attack.equals("invalidKind"))sections.add(Map.of("kind","QUALITY","text","观测数 "+quality.value(),"factIds",List.of(quality.factId())));
  return JsonV1Codec.encodeCompact(Map.of("sections",sections,"recommendations",List.of()));
 }
}
