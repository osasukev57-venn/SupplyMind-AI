package com.supplymind.agent;
import com.supplymind.agent.api.AgentReportController;
import com.supplymind.agent.support.Day6R2Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class AgentReportReadTest {
 @TempDir Path temp;
 @Test void readIsVerifiedAndTamperingDoesNotFallbackToLatest() throws Exception {
  var f=Day6R2Fixture.create(temp,"report-view");
  String ref=f.reportStore().store(f.validReport("readable","request-readable"));
  var controller=new AgentReportController(f.reportStore());
  var response=controller.read(ref);assertEquals(200,response.getStatusCode().value());
  assertTrue(response.getBody() instanceof Map<?,?>);assertFalse(((Map<?,?>)response.getBody()).containsKey("evidencePack"));
  Files.writeString(f.root().resolveDataRef(ref),"tampered");
  assertEquals(409,controller.read(ref).getStatusCode().value());
  assertEquals(400,controller.read("../outside.json").getStatusCode().value());
 }
}
