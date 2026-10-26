package com.supplymind.agent.report;
import com.supplymind.agent.evidence.*;
import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.agent.orchestration.AgentOrchestrator;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.foundation.codec.*;
import com.supplymind.foundation.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AggregateRowBindingV3Test {
 @TempDir Path temp;
 @ParameterizedTest @ValueSource(strings={"value","group","version","rowId","rowVersion","file","closure","rowManifest","narrative","otherGroup","businessDate","cloudLabel"})
 void tamperingRejectsEvenWithRecomputedOuterManifest(String attack) throws Exception {
  var f=Day6R2Fixture.create(temp,"row-binding");
  var answer=f.orchestrator(r->LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,"not_configured"),new AgentResponseVerifier(List.of()),Day6R2Fixture.CLOCK)
      .answer(new AgentOrchestrator.AgentQueryInput("2026-08-10 至 2026-08-10 最新值和月均",Day6R2Fixture.ITEM,null,null,null,null,null,null,null,"FORMAL"));
  assertTrue(f.reportStore().read(answer.reportRef()).ok());
  var rowFact=answer.evidencePack().facts().stream().filter(x->x.factType().equals("period.metrics.avg")).findFirst().orElseThrow();
  assertNotNull(rowFact.rowEvidenceId());
  var path=f.root().resolveDataRef(answer.reportRef());
  var tree=(com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().readTree(Files.readAllBytes(path));
  assertEquals("AGENT-REPORT-V3",tree.path("schemaVersion").asText());
  var pack=tree.path("report").path("evidencePack");
  var fact=(com.fasterxml.jackson.databind.node.ObjectNode)java.util.stream.StreamSupport.stream(pack.path("facts").spliterator(),false).filter(n->n.path("factId").asText().equals(rowFact.factId())).findFirst().orElseThrow();
  var entry=(com.fasterxml.jackson.databind.node.ObjectNode)java.util.stream.StreamSupport.stream(pack.path("evidenceRefs").spliterator(),false).filter(n->n.path("refType").asText().equals("AGGREGATE_ROWS_V1")).findFirst().orElseThrow();
  switch(attack) {
   case "cloudLabel"->((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("report")).put("generatedBy","LLM");
   case "otherGroup"->{var another=java.util.stream.StreamSupport.stream(entry.path("rowEvidence").spliterator(),false).filter(n->!n.path("rowEvidenceId").asText().equals(rowFact.rowEvidenceId())).findFirst().orElseThrow();fact.put("rowEvidenceId",another.path("rowEvidenceId").asText());}
   case "businessDate"->fact.put("businessDate","2026-08-09");
   case "value"->fact.put("value","999.00000000");
   case "group"->fact.put("validationStatus","VERIFIED_WITH_NOTICE");
   case "version"->fact.putArray("configVersions").add("99");
   case "rowId"->fact.put("rowEvidenceId","aggregate-row-"+"0".repeat(64));
   case "rowVersion"->((com.fasterxml.jackson.databind.node.ObjectNode)entry.path("rowEvidence").get(0).path("row")).putArray("configVersions").add(99);
   case "rowManifest"->((com.fasterxml.jackson.databind.node.ObjectNode)entry.path("rowEvidence").get(0)).put("manifestSha256","0".repeat(64));
   case "file"->entry.put("ref","processed/aggregate/WRONG.ITEM/month/2026.csv");
   case "closure"->{var bindings=(com.fasterxml.jackson.databind.node.ArrayNode)tree.path("evidenceBindings");for(int i=0;i<bindings.size();i++)if(bindings.get(i).path("originalRef").asText().startsWith("staging/")){bindings.remove(i);break;}}
   case "narrative"->{var nar=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("narrative");nar.put("adoption","FULL");nar.put("cloudStatus","SUCCESS");var segment=nar.putArray("sections").addObject();segment.put("sectionId","s1").put("kind","METRICS").put("text","VERIFIED组均值999999");segment.putArray("factIds").add(rowFact.factId());segment.set("evidenceRefs",JsonV1Codec.mapper().valueToTree(rowFact.evidenceRefs()));}
  }
  byte[] bytes=JsonV1Codec.encodeFile(tree);Files.write(path,bytes);
  Files.write(f.root().resolveDataRef(DataPaths.manifestRef(answer.reportRef())),JsonV1Codec.encodeFile(ManifestFactory.json(answer.reportRef(),bytes,List.of(),answer.report().createdAt())));
  assertFalse(f.reportStore().read(answer.reportRef()).ok(),attack);
 }
 @Test void wrongFileAndDuplicateGroupCannotBecomeVerifiedRows() throws Exception {
  var f=Day6R2Fixture.create(temp,"identity");String ref=f.aggregateRef();
  byte[] bytes=Files.readAllBytes(f.root().resolveDataRef(ref));
  String wrong="processed/aggregate/WRONG.ITEM/month/2026.csv";
  Files.createDirectories(f.root().resolveDataRef(wrong).getParent());Files.write(f.root().resolveDataRef(wrong),bytes);
  Files.write(f.root().resolveDataRef(DataPaths.manifestRef(wrong)),JsonV1Codec.encodeFile(ManifestFactory.csv(wrong,bytes,1,"2026-08-01","2026-08-31",List.of(),Day6R2Fixture.AT)));
  assertNotEquals(EvidenceStatus.VERIFIED,new EvidenceRefVerifier(f.root()).verifyAggregateRows(wrong).status());
  String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);String lines=text.substring(text.indexOf('\n')+1);
  bytes=(text+lines).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  Files.write(f.root().resolveDataRef(ref),bytes);
  Files.write(f.root().resolveDataRef(DataPaths.manifestRef(ref)),JsonV1Codec.encodeFile(ManifestFactory.csv(ref,bytes,2,"2026-08-01","2026-08-31",List.of(),Day6R2Fixture.AT)));
  assertNotEquals(EvidenceStatus.VERIFIED,new EvidenceRefVerifier(f.root()).verifyAggregateRows(ref).status());
 }
 @Test void appendedCurrentFileDoesNotReplaceGenerationTimeRow() throws Exception {
  var f=Day6R2Fixture.create(temp,"historical-rows");
  var answer=f.orchestrator(r->LLMService.LLMResponse.failure(LLMService.LLMStatus.UNAVAILABLE,"local"),new AgentResponseVerifier(List.of()),Day6R2Fixture.CLOCK)
      .answer(new AgentOrchestrator.AgentQueryInput("2026-08-10 月度均值",Day6R2Fixture.ITEM,null,null,null,null,null,null,null,"FORMAL"));
  String ref=f.aggregateRef();var rows=CsvV1Codec.decodeAggregate(Files.readAllBytes(f.root().resolveDataRef(ref)));
  var tree=(com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().valueToTree(rows.get(0));tree.put("avg","124.00000000");
  var updated=JsonV1Codec.mapper().treeToValue(tree,com.supplymind.foundation.model.AggregateRecordV1.class);
  byte[] bytes=CsvV1Codec.encodeAggregate(List.of(updated));Files.write(f.root().resolveDataRef(ref),bytes);
  Files.write(f.root().resolveDataRef(DataPaths.manifestRef(ref)),JsonV1Codec.encodeFile(ManifestFactory.csv(ref,bytes,1,"2026-08-01","2026-08-31",List.of(),Day6R2Fixture.AT)));
  var read=new ReportStore(f.root(),f.files()).read(answer.reportRef());assertTrue(read.ok());
  assertEquals("123.45678901",read.report().evidencePack().facts().stream().filter(x->x.factType().equals("period.metrics.avg")).findFirst().orElseThrow().value());
 }
}
