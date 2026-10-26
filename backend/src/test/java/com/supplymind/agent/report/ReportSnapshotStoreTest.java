package com.supplymind.agent.report;
import com.supplymind.agent.support.Day6R2Fixture;
import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.llm.LLMService;
import com.supplymind.foundation.storage.*;
import com.supplymind.foundation.codec.JsonV1Codec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReportSnapshotStoreTest {
 @TempDir Path temp;
 @ParameterizedTest @ValueSource(strings={"data","metadata","blob-manifest","mapping","binding","closure"})
 void snapshotTamperingFailsClosedEvenWhenOuterManifestIsRecomputed(String attack) throws Exception {
    var fixture=Day6R2Fixture.create(temp,"snapshot");
    var report=snapshotReport(fixture,"legacy-snapshot-"+attack);
    var root=fixture.root();var store=fixture.reportStore();String reportRef=store.storeSnapshot(report,()->{});var reportPath=root.resolveDataRef(reportRef);
    assertTrue(store.read(reportRef).ok());
    var envelope=JsonV1Codec.decodeFile(Files.readAllBytes(reportPath),ReportSnapshotV2.class);
    var daily=envelope.evidenceBindings().stream().filter(b->b.originalRef().startsWith("processed/daily/")).findFirst().orElseThrow();
    assertTrue(envelope.evidenceBindings().stream().filter(b->b.originalRef().startsWith("raw/")).allMatch(b->b.snapshotRef()==null));
    if(attack.equals("data"))Files.writeString(root.resolveDataRef(daily.snapshotRef()),"tampered");
    else if(attack.equals("metadata"))Files.writeString(root.resolveDataRef(daily.manifestSnapshotRef()),"tampered");
    else if(attack.equals("blob-manifest"))Files.writeString(root.resolveDataRef(DataPaths.manifestRef(daily.snapshotRef())),"tampered");
    else {
      var tree=(com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().readTree(Files.readAllBytes(reportPath));
      var bindings=(com.fasterxml.jackson.databind.node.ArrayNode)tree.get("evidenceBindings");
      if(attack.equals("mapping"))((com.fasterxml.jackson.databind.node.ObjectNode)bindings.get(0)).put("snapshotRef",daily.snapshotRef());
      if(attack.equals("closure")){for(int i=0;i<bindings.size();i++)if(bindings.get(i).get("originalRef").asText().startsWith("staging/")){bindings.remove(i);break;}}
      if(attack.equals("binding"))((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("report").get("evidencePack").get("evidenceRefs").get(0)).put("sha256","0".repeat(64));
      byte[] bytes=JsonV1Codec.encodeFile(tree);Files.write(reportPath,bytes);
      Files.write(root.resolveDataRef(DataPaths.manifestRef(reportRef)),JsonV1Codec.encodeFile(ManifestFactory.json(reportRef,bytes,List.of(),report.createdAt())));
    }
    assertFalse(store.read(reportRef).ok(),attack);
 }
 private AgentReportV1 snapshotReport(Day6R2Fixture fixture,String id) throws Exception {
    var entries=new com.supplymind.agent.evidence.EvidenceRefVerifier(fixture.root()).verifyAllWithAuthoritativeLineage(List.of(fixture.dailyRef(),fixture.aggregateRef()));
    assertEquals(2,entries.size());
    assertTrue(entries.stream().allMatch(e->e.status()==com.supplymind.agent.evidence.EvidenceStatus.VERIFIED),"fixture must reach snapshot verification");
    var tree=(com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().valueToTree(fixture.validReport(id,"request-"+id));
    ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("evidencePack")).set("evidenceRefs",JsonV1Codec.mapper().valueToTree(entries));
    return JsonV1Codec.mapper().treeToValue(tree,AgentReportV1.class);
 }
 @Test void validDailyWithStaleAggregateCannotFormMixedVersionSnapshot() throws Exception {
    var fixture=Day6R2Fixture.create(temp,"mixed-version");
    var report=snapshotReport(fixture,"mixed-report");
    var root=fixture.root();String ref=fixture.dailyRef();
    byte[] bytes=Files.readString(root.resolveDataRef(ref)).replace("123.45678901","123.45678902").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertNotEquals(FileDigest.sha256(root.resolveDataRef(ref)),FileDigest.sha256(bytes));
    var old=com.supplymind.foundation.codec.JsonV1Codec.decodeFile(Files.readAllBytes(root.resolveDataRef(DataPaths.manifestRef(ref))),com.supplymind.foundation.model.ManifestV1.class);
    Files.write(root.resolveDataRef(ref),bytes);
    Files.write(root.resolveDataRef(DataPaths.manifestRef(ref)),JsonV1Codec.encodeFile(ManifestFactory.csv(ref,bytes,old.rowCount(),old.minBusinessDate(),old.maxBusinessDate(),old.sourceRunIds(),old.generatedAt())));
    assertTrue(ManifestVerifier.matches(root,ref,root.resolveDataRef(ref),root.resolveDataRef(DataPaths.manifestRef(ref))));
    var failure=assertThrows(StorageException.class,()->fixture.reportStore().storeSnapshot(report,()->{}));
    assertEquals("REPORT_SNAPSHOT_LINEAGE_INVALID",failure.getMessage());
    assertFalse(fixture.reportStore().exists(DataPaths.reportRef(java.time.YearMonth.of(2026,8),report.reportId())));
 }
 @Test void interruptedSnapshotCannotExposeSuccessfulReport() throws Exception {
    var fixture=Day6R2Fixture.create(temp,"interrupt");var report=snapshotReport(fixture,"cancelled-report");
    assertThrows(StorageException.class,()->fixture.reportStore().storeSnapshot(report,()->{if(Files.exists(fixture.root().path().resolve("report-evidence")))throw new StorageException("cancelled");}));
    assertTrue(Files.exists(fixture.root().path().resolve("report-evidence")),"must interrupt after a real snapshot write");
    assertFalse(fixture.reportStore().exists(DataPaths.reportRef(java.time.YearMonth.of(2026,8),report.reportId())));
 }
}
