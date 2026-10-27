package com.supplymind.agent.narrative;

import com.supplymind.agent.application.AgentResponseVerifier;
import com.supplymind.agent.evidence.EvidencePackV1;
import com.supplymind.foundation.codec.JsonV1Codec;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit, local acceptance diagnostics. Never part of an API/report or an adoption decision. */
public final class SourceRejectionAudit {
    public static final String DIRECTORY_PROPERTY="supplymind.agent.acceptance-diagnostics-dir";
    private final Path directory;
    private final AgentResponseVerifier secrets;
    private int entries;

    public SourceRejectionAudit(Path directory,AgentResponseVerifier secrets) {
        this.directory=Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.secrets=Objects.requireNonNull(secrets);
    }

    public static SourceRejectionAudit configured(AgentResponseVerifier secrets) {
        String value=System.getProperty(DIRECTORY_PROPERTY,"");
        return value.isBlank()?null:new SourceRejectionAudit(Path.of(value),secrets);
    }

    /** Only a rejected paragraph (not the full model response), for offline debugging of other guards. */
    synchronized void captureSection(String requestId,String kind,String text,List<String> ids,String code) {
        if(entries>=10||code.startsWith("SOURCE_")||requestId==null||!requestId.matches("req-[A-Za-z0-9-]{1,64}"))return;
        if(text==null||ids==null)return;
        try {
            var full=Map.of("text",text,"factIds",ids);
            secrets.requireNoSecret(JsonV1Codec.encodeCompact(full));
            var event=Map.of("schemaVersion","LOCAL-REJECTED-SECTION-V1","requestId",requestId,
                "reason",code,"kind",Objects.toString(kind,"UNKNOWN"),"text",clip(text,1000),
                "factIds",ids.stream().limit(12).map(id->clip(id,80)).toList(),
                "truncated",text.length()>1000||ids.size()>12);
            byte[] bytes=JsonV1Codec.encodeCompact(event).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>8192)return;
            Files.createDirectories(directory);if(Files.isSymbolicLink(directory))return;
            Files.write(directory.resolve(requestId+"-section-"+(++entries)+".json"),bytes,
                StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }catch(Exception ignored) { /* Optional audit cannot change acceptance or disclose failures. */ }
    }

    synchronized void capture(String requestId,SourceAssertionCheck.Inspection inspection,
                              List<EvidencePackV1.Fact> selected) {
        if(inspection.code()==null||entries>=10||requestId==null||!requestId.matches("req-[A-Za-z0-9-]{1,64}"))return;
        try {
            // Scan before clipping, so truncation cannot conceal a credential from the guard.
            var full=Map.of("marker",inspection.marker(),"asserted",inspection.asserted(),"expected",inspection.expected(),
                "facts",selected.stream().map(f->Map.of("id",f.factId(),"source",Objects.toString(f.actualSourceName(),""))).toList());
            secrets.requireNoSecret(JsonV1Codec.encodeCompact(full));
            var event=new LinkedHashMap<String,Object>();
            event.put("schemaVersion","LOCAL-SOURCE-REJECTION-V1");
            event.put("requestId",requestId);event.put("reason",inspection.code());event.put("negated",inspection.negated());
            event.put("marker",clip(inspection.marker(),40));
            event.put("asserted",clip(inspection.asserted(),512));event.put("expected",clip(inspection.expected(),512));
            event.put("truncated",inspection.asserted().length()>512||inspection.expected().length()>512||selected.size()>8
                ||selected.stream().anyMatch(f->Objects.toString(f.actualSourceName(),"").length()>160));
            event.put("facts",selected.stream().limit(8).map(f->Map.of("factId",clip(f.factId(),80),
                "actualSourceName",clip(Objects.toString(f.actualSourceName(),""),160))).toList());
            byte[] bytes=JsonV1Codec.encodeCompact(event).getBytes(StandardCharsets.UTF_8);
            if(bytes.length>8192)return;
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory))return;
            Path target=directory.resolve(requestId+"-source-"+(++entries)+".json");
            Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }catch(Exception ignored) {
            // Optional diagnostics must never alter a valid report, log untrusted text, or overwrite evidence.
        }
    }

    private static String clip(String text,int limit) {return text.substring(0,Math.min(text.length(),limit));}
}
