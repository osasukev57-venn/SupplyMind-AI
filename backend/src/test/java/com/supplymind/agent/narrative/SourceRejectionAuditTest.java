package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SourceRejectionAuditTest {
    @TempDir Path root;

    @Test void realFactComparisonIsLocalOnlyAndReplaysWithoutChangingAdoption() throws Exception {
        var fixture=new SourceAssertionRegressionTest();
        String raw=JsonV1Codec.encodeCompact(Map.of("sections",List.of(Map.of("kind","OVERVIEW",
            "text","最新值为25500元/吨。数据来源为虚构商业行情。","factIds",List.of(fixture.latest.factId()))),"recommendations",List.of()));
        var guard=new AgentResponseVerifier(List.of());
        var plain=new NarrativeValidator(guard).validate(raw,fixture.pack);
        var audited=new NarrativeValidator(guard,new SourceRejectionAudit(root,guard)).validate(raw,fixture.pack);
        assertEquals(plain,audited);
        var files=files();assertEquals(1,files.size());
        var event=JsonV1Codec.mapper().readTree(Files.readString(files.get(0)));
        assertEquals("SOURCE_CONFLICT",event.path("reason").asText());
        assertEquals("虚构商业行情",event.path("asserted").asText());
        assertEquals("模拟演示(非真实市场数据)",event.path("expected").asText());
        assertFalse(event.path("negated").asBoolean());assertFalse(event.path("truncated").asBoolean());
        assertEquals(fixture.latest.factId(),event.path("facts").get(0).path("factId").asText());
        assertFalse(JsonV1Codec.encodeCompact(audited).contains("虚构商业"));
    }

    @Test void disabledByDefaultAndValidSourceLeavesNoTrace() throws Exception {
        assertNull(SourceRejectionAudit.configured(new AgentResponseVerifier(List.of())));
        var fixture=new SourceAssertionRegressionTest();
        var audit=new SourceRejectionAudit(root,new AgentResponseVerifier(List.of()));
        audit.capture(fixture.pack.requestId(),SourceAssertionCheck.inspect("来源为模拟演示（非真实市场数据）。",List.of(fixture.latest)),List.of(fixture.latest));
        assertTrue(files().isEmpty());
    }

    @Test void secretIsCheckedBeforeClippingAndWholeEventIsOmitted() throws Exception {
        var audit=new SourceRejectionAudit(root,new AgentResponseVerifier(List.of("private-fixture-credential")));
        audit.capture("req-safe",new SourceAssertionCheck.Inspection("SOURCE_CONFLICT","来源为","字".repeat(600)+"private-fixture-credential","模拟",false),List.of());
        assertTrue(files().isEmpty());
    }

    @Test void sizeCountAndPathAreBoundedAndExistingEvidenceIsNotOverwritten() throws Exception {
        var audit=new SourceRejectionAudit(root,new AgentResponseVerifier(List.of()));
        var event=new SourceAssertionCheck.Inspection("SOURCE_CONFLICT","来源为","字".repeat(2000),"模拟",true);
        audit.capture("../outside",event,List.of());assertTrue(files().isEmpty());
        Files.writeString(root.resolve("req-safe-source-1.json"),"protected");
        for(int i=0;i<20;i++)audit.capture("req-safe",event,List.of());
        assertEquals("protected",Files.readString(root.resolve("req-safe-source-1.json")));
        assertEquals(10,files().size());
        for(Path path:files())assertTrue(Files.size(path)<8192);
        var json=JsonV1Codec.mapper().readTree(Files.readString(root.resolve("req-safe-source-2.json")));
        assertTrue(json.path("truncated").asBoolean());assertEquals(512,json.path("asserted").asText().length());
    }
    @Test void otherGuardFailuresRetainOnlyOneSafeBoundedParagraphNotTheWholeDraft() throws Exception {
        var audit=new SourceRejectionAudit(root,new AgentResponseVerifier(List.of()));
        audit.captureSection("req-test","TREND","字".repeat(2000),List.of("fact-1"),"UNSUPPORTED_NUMBER");
        var event=JsonV1Codec.mapper().readTree(Files.readString(files().get(0)));
        assertEquals(1000,event.path("text").asText().length());assertTrue(event.path("truncated").asBoolean());
        assertEquals("UNSUPPORTED_NUMBER",event.path("reason").asText());
    }
    @Test void rejectedParagraphAuditOmitsCredentialsAndDoesNotDuplicateSourceRecords() throws Exception {
        var audit=new SourceRejectionAudit(root,new AgentResponseVerifier(List.of("private-fixture-credential")));
        audit.captureSection("req-test","OVERVIEW","private-fixture-credential",List.of("fact-1"),"WRONG_FACT_ROLE");
        audit.captureSection("req-test","OVERVIEW","untrusted",List.of("fact-1"),"SOURCE_CONFLICT");
        assertTrue(files().isEmpty());
    }
    private List<Path> files() throws Exception {try(var stream=Files.list(root)){return stream.toList();}}
}
