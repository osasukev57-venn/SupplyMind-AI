package com.supplymind.agent;
import com.supplymind.agent.evidence.AggregateRowEvidenceV1;
import com.supplymind.foundation.codec.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AggregateRowEvidenceGoldenTest {
 @Test void frozenIndependentVectorsPreserveGroupVersionsAndDecimalScale() throws Exception {
  byte[] bytes=getClass().getResourceAsStream("/contracts/agent-v2/aggregate-row-evidence.csv").readAllBytes();
  var expected=JsonV1Codec.mapper().readTree(getClass().getResourceAsStream("/contracts/agent-v2/aggregate-row-evidence.expected.json"));
  var rows=CsvV1Codec.decodeAggregate(bytes);assertEquals(2,rows.size());
  for(int i=0;i<2;i++) {
   var row=rows.get(i);assertEquals(expected.get(i).path("rowId").asText(),AggregateRowEvidenceV1.rowId(row));
   assertEquals(expected.get(i).path("rowSha256").asText(),AggregateRowEvidenceV1.digest(row));
   assertEquals(i==0?"24800.00":"23200.00",row.avg());
   assertEquals(i==0?java.util.List.of(5,12):java.util.List.of(5),row.configVersions());
  }
  var modified=(com.fasterxml.jackson.databind.node.ObjectNode)JsonV1Codec.mapper().valueToTree(rows.get(0));modified.putArray("configVersions").add(5).add(13);
  var changed=JsonV1Codec.mapper().treeToValue(modified,com.supplymind.foundation.model.AggregateRecordV1.class);
  assertEquals(AggregateRowEvidenceV1.rowId(rows.get(0)),AggregateRowEvidenceV1.rowId(changed),"configVersions is lineage, never a new business group");
  assertNotEquals(AggregateRowEvidenceV1.digest(rows.get(0)),AggregateRowEvidenceV1.digest(changed),"full row digest binds its precise config versions");
 }
}
